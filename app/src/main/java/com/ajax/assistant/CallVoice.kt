package com.ajax.assistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager

/**
 * Incoming calls by voice: says who is calling, then listens for "answer" or "decline" while the
 * phone rings. Runs inside the wake word listener (it needs the microphone already open). The
 * ringtone is turned down while Ajax talks and listens, and put back afterwards. Main thread only.
 */
object CallVoice {
    enum class Reply { ANSWER, DECLINE }

    /** Words listened for while the phone rings; nothing else counts. */
    val phrases = listOf("answer", "answer it", "pick up", "accept", "decline", "reject", "ignore", "hang up", "not now")

    fun reply(text: String): Reply? = when (WakeWords.normalize(text)) {
        "answer", "answer it", "pick up", "accept" -> Reply.ANSWER
        "decline", "reject", "ignore", "hang up", "not now" -> Reply.DECLINE
        else -> null
    }

    val permissions: Array<String> = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG) // Android only shares the caller's number with this
        add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 26) add(Manifest.permission.ANSWER_PHONE_CALLS)
    }.toTypedArray()

    fun enabled(ctx: Context) =
        ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).getBoolean(Prefs.CALLS, false) &&
            permissions.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** True from the announcement until the call is answered, declined or stops ringing. */
    @Volatile var listening = false
        private set

    /** Lets the wake word listener keep the microphone while the phone rings. */
    @Volatile var ringing = false
        private set

    private var appCtx: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var speaker: Speaker? = null
    private var savedRingVolume = -1
    private var onChange: (() -> Unit)? = null

    /** Starts watching for calls. [changed] runs when listening starts or stops. */
    fun watch(ctx: Context, changed: () -> Unit) {
        if (receiver != null) return
        val app = ctx.applicationContext
        appCtx = app
        onChange = changed
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        @Suppress("DEPRECATION")
                        val number = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                        // Android sends this twice: once without the number, once with it.
                        if (number != null) ring(app, number)
                    }
                    else -> stop()
                }
            }
        }.also {
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            // System broadcasts still arrive; other apps can't fake a call.
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(it, filter, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(it, filter)
        }
    }

    fun unwatch() {
        stop()
        receiver?.let { appCtx?.unregisterReceiver(it) }
        receiver = null
        speaker?.shutdown()
        speaker = null
        onChange = null
    }

    private fun ring(ctx: Context, number: String) {
        if (ringing || !enabled(ctx)) return
        ringing = true
        quietRingtone(ctx)
        val who = contactName(ctx, number) ?: "an unknown number"
        val s = speaker ?: Speaker(ctx).also { speaker = it }
        s.speak("Call from $who. Say answer, or decline.") {
            if (ringing) {
                listening = true
                onChange?.invoke()
            }
        }
    }

    /** What the listener heard while the phone rang. */
    fun heard(ctx: Context, text: String) {
        if (!listening) return
        when (reply(text)) {
            Reply.ANSWER -> act(ctx, answer = true)
            Reply.DECLINE -> act(ctx, answer = false)
            null -> {}
        }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun act(ctx: Context, answer: Boolean) {
        val tm = ctx.getSystemService(TelecomManager::class.java) ?: return
        val ok = try {
            if (answer) { tm.acceptRingingCall(); true } else if (Build.VERSION.SDK_INT >= 28) tm.endCall() else false
        } catch (e: SecurityException) {
            false
        }
        stop()
        if (!ok) speaker?.speak(if (answer) "Android wouldn't let me answer. Swipe to answer." else "Android wouldn't let me decline. Swipe to decline.")
    }

    private fun stop() {
        val wasActive = ringing || listening
        ringing = false
        listening = false
        restoreRingtone()
        if (wasActive) onChange?.invoke()
    }

    /** The ringtone at about a third, so Ajax can be heard and can hear you. */
    private fun quietRingtone(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        try {
            val now = am.getStreamVolume(AudioManager.STREAM_RING)
            val target = maxOf(1, am.getStreamMaxVolume(AudioManager.STREAM_RING) / 3)
            if (now > target) {
                savedRingVolume = now
                am.setStreamVolume(AudioManager.STREAM_RING, target, 0)
            }
        } catch (e: SecurityException) {
            // Do Not Disturb access missing: leave the ringtone alone.
        }
    }

    private fun restoreRingtone() {
        val am = appCtx?.getSystemService(AudioManager::class.java) ?: return
        if (savedRingVolume >= 0) {
            try { am.setStreamVolume(AudioManager.STREAM_RING, savedRingVolume, 0) } catch (e: SecurityException) {}
            savedRingVolume = -1
        }
    }

    private fun contactName(ctx: Context, number: String): String? {
        if (number.isBlank()) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return try {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** "Answer the call" / "hang up" said to Ajax directly, e.g. through the overlay. */
    @Suppress("DEPRECATION", "MissingPermission")
    fun answerNow(ctx: Context): Result {
        if (Build.VERSION.SDK_INT < 26 || ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            return Result(false, "Turn on Announce callers in Ajax first.")
        }
        return try {
            ctx.getSystemService(TelecomManager::class.java)?.acceptRingingCall()
            Result(true, "Answering")
        } catch (e: SecurityException) {
            Result(false, "Android wouldn't let me answer.")
        }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    fun endNow(ctx: Context): Result {
        if (Build.VERSION.SDK_INT < 28 || ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            return Result(false, "Turn on Announce callers in Ajax first.")
        }
        return try {
            if (ctx.getSystemService(TelecomManager::class.java)?.endCall() == true) Result(true, "Call ended")
            else Result(false, "There's no call to end.")
        } catch (e: SecurityException) {
            Result(false, "Android wouldn't let me end the call.")
        }
    }
}
