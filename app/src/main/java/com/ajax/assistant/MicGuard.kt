package com.ajax.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/**
 * Knows when Ajax itself is using the microphone or speaking, so the wake word listener steps aside,
 * and warns when some other app starts recording. Android tells any app that a recording started,
 * never which app, so "other" means "not one of Ajax's". Main thread only.
 */
object MicGuard {
    private const val GRACE_MS = 2_500L
    private const val CHANNEL = "mic-alert"
    private const val NOTIFICATION_ID = 7

    private val main = Handler(Looper.getMainLooper())
    private val holders = HashSet<Any>()
    private var lastRelease = 0L

    /** Called whenever Ajax starts or stops using the microphone or speaker. */
    var onChange: (() -> Unit)? = null

    /** The wake word listener's recordings, current and just closed (Android reports a closed one a little late). */
    private val ownSessions: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Called from the audio thread when the listener opens or closes the microphone. */
    fun ownRecording(session: Int, open: Boolean) {
        if (open) ownSessions.add(session)
        else main.postDelayed({ ownSessions.remove(session) }, 5_000)
    }

    /** A recording the user started on purpose is under way (a recorder app on screen): no warning. */
    var expected: () -> Boolean = { false }

    private var appCtx: Context? = null
    private var callback: AudioManager.AudioRecordingCallback? = null
    private val alerted = HashSet<Int>()
    private val recheck = Runnable { check() }

    val busy: Boolean get() = holders.isNotEmpty()

    /** Another app is recording, or a call is on: the wake word listener lets go of the microphone. */
    var othersNeedMic = false
        private set
    private var othersSince = 0L

    /** Phone, video and voice calls, and a ringing phone. */
    fun inCall(ctx: Context): Boolean {
        val mode = ctx.getSystemService(AudioManager::class.java)?.mode ?: return false
        // While the phone rings Ajax may keep listening, for "answer" or "decline".
        if (mode == AudioManager.MODE_RINGTONE) return !CallVoice.ringing
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_CALL_SCREENING ||
            (Build.VERSION.SDK_INT >= 33 && mode == AudioManager.MODE_COMMUNICATION_REDIRECT) ||
            (Build.VERSION.SDK_INT >= 30 && mode == AudioManager.MODE_CALL_REDIRECT)
    }

    /** True when Ajax has not used the microphone or speaker for [ms]. */
    fun quietFor(ms: Long) = !busy && SystemClock.uptimeMillis() - lastRelease >= ms

    private val limits = HashMap<Any, Runnable>()

    /**
     * Ajax is using the microphone or speaker. [maxMs] lets go by itself after that long, so a
     * callback that never comes can't keep wake word listening off for good.
     */
    fun hold(owner: Any, maxMs: Long = 0) {
        limits.remove(owner)?.let { main.removeCallbacks(it) }
        if (maxMs > 0) {
            val r = Runnable { release(owner) }
            limits[owner] = r
            main.postDelayed(r, maxMs)
        }
        if (holders.add(owner)) onChange?.invoke()
    }

    fun release(owner: Any) {
        limits.remove(owner)?.let { main.removeCallbacks(it) }
        if (!holders.remove(owner)) return
        lastRelease = SystemClock.uptimeMillis()
        onChange?.invoke()
        main.removeCallbacks(recheck)
        main.postDelayed(recheck, GRACE_MS)
    }

    private var focus: android.media.AudioFocusRequest? = null

    /**
     * Asks music and video apps to pause while Ajax listens to you (they resume afterwards), the way
     * other assistants do. Call [quietMedia] with false to give the audio back.
     */
    fun quietMedia(ctx: Context, quiet: Boolean) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT < 26) return
        if (quiet) {
            if (focus != null) return
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { }
                .build()
            if (am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = req
        } else {
            focus?.let { am.abandonAudioFocusRequest(it) }
            focus = null
        }
    }

    /** Looks at the microphone again now (something that changes who may use it just happened). */
    fun refresh() = check()

    fun watch(ctx: Context) {
        if (callback != null) return
        val app = ctx.applicationContext
        appCtx = app
        val am = app.getSystemService(AudioManager::class.java) ?: return
        callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) = check()
        }.also { am.registerAudioRecordingCallback(it, main) }
        if (Build.VERSION.SDK_INT >= 31) {
            modeListener = AudioManager.OnModeChangedListener { main.post { check() } }
                .also { am.addOnModeChangedListener(app.mainExecutor, it) }
        }
        check()
    }

    private var modeListener: Any? = null

    fun unwatch() {
        val app = appCtx ?: return
        val am = app.getSystemService(AudioManager::class.java)
        callback?.let { am?.unregisterAudioRecordingCallback(it) }
        callback = null
        if (Build.VERSION.SDK_INT >= 31) {
            (modeListener as? AudioManager.OnModeChangedListener)?.let { am?.removeOnModeChangedListener(it) }
        }
        modeListener = null
        othersNeedMic = false
        expected = { false }
        main.removeCallbacks(recheck)
        alerted.clear()
        app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun check() {
        val app = appCtx ?: return
        if (callback == null) return
        val am0 = app.getSystemService(AudioManager::class.java) ?: return
        val call = inCall(app)
        val someoneElse = !busy && am0.activeRecordingConfigurations.any { it.clientAudioSessionId !in ownSessions }
        val need = call || someoneElse
        if (need) othersSince = SystemClock.uptimeMillis()
        if (need != othersNeedMic) {
            // Take the microphone back only after it has been free for a moment.
            if (need || SystemClock.uptimeMillis() - othersSince >= GRACE_MS) {
                othersNeedMic = need
                onChange?.invoke()
            } else {
                main.removeCallbacks(recheck)
                main.postDelayed(recheck, GRACE_MS)
            }
        }
        // Calls are on screen anyway: no warning for them.
        if (call) return
        // Ajax's own speech recognition runs in another app (Google), so it can't be told apart:
        // ignore recordings while Ajax is listening, and look again once it has finished.
        if (!quietFor(GRACE_MS)) return
        val am = app.getSystemService(AudioManager::class.java) ?: return
        val others = am.activeRecordingConfigurations
            .map { it.clientAudioSessionId }
            .filter { it !in ownSessions }
            .toSet()
        alerted.retainAll(others)
        val fresh = others - alerted
        if (fresh.isNotEmpty()) {
            alerted.addAll(fresh)
            nameAndNotify(app, fresh)
        }
        // The warning stays after the recording ends, so a short one isn't missed.
    }

    private val namer: Handler by lazy { Handler(android.os.HandlerThread("ajax-mic-names").apply { start() }.looper) }

    /** Names the app from Android's audio report when Ajax may read it. */
    private fun nameAndNotify(ctx: Context, sessions: Set<Int>) {
        if (!SystemDump.allowed(ctx)) { notify(ctx, null); return }
        namer.post {
            // By session, from the recordings still running or the audio service's recent history,
            // so a recording that has already ended by now is still named.
            val pkgs = SystemDump.read(ctx, "audio")?.let { SystemDump.packagesForSessions(it, sessions) }
            main.post {
                if (pkgs == null) { notify(ctx, null); return@post }
                // Every app but Ajax, even the one on screen: you asked to always be told.
                val shown = pkgs.filter { it != ctx.packageName }
                if (shown.isNotEmpty()) notify(ctx, shown)
            }
        }
    }

    private fun notify(ctx: Context, pkgs: List<String>?) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Other apps using the microphone", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        // The privacy page lists which apps used the microphone and when.
        val open = Intent(if (Build.VERSION.SDK_INT >= 31) "android.intent.action.REVIEW_PERMISSION_USAGE" else Settings.ACTION_PRIVACY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else Notification.Builder(ctx))
            .setSmallIcon(R.drawable.ic_stat_ajax)
            .setColor(0xFFE5484D.toInt())
            .setLargeIcon(android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_notification_large))
            .setContentTitle(
                if (pkgs.isNullOrEmpty()) "Another app is using the microphone"
                else "${SystemDump.names(ctx, pkgs)} ${if (pkgs.size == 1) "is" else "are"} using the microphone",
            )
            .setContentText(if (pkgs.isNullOrEmpty()) "Not Ajax. Tap to see which apps used it." else "Not Ajax. Tap to see microphone history.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try { nm.notify(NOTIFICATION_ID, n) } catch (e: SecurityException) {}
    }
}
