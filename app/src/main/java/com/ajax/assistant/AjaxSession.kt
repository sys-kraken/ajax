package com.ajax.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The small overlay shown when the side key is held: listens and acts, then keeps listening for
 * [FOLLOW_UP_MS] so commands can follow each other. Closes on silence, on "stop"/"thanks", on a tap
 * outside the card, or after a command that hands the user over to something else.
 */
class AjaxSession(private val ctx: Context) : VoiceInteractionSession(ctx), SpeechInput.Callback {

    private val main = Handler(Looper.getMainLooper())
    private val brain = Brain(ctx)
    private val speech = SpeechInput(ctx, this)
    private val speaker = Speaker(ctx)

    init { Actions.watchTorch(ctx) }
    private lateinit var heardView: TextView
    private lateinit var resultView: TextView

    /** False once hidden: late speech/TTS callbacks from a previous press are ignored. */
    private var active = false

    /** Bumped on every show and on destroy, so a command running in the background can't reopen a stale overlay. */
    private var showCount = 0
    private var resuming = false

    /** Uptime until which silence means "listen again"; 0 before the first command of a conversation. */
    private var followUpUntil = 0L

    /** A reply is on the card: listening must not overwrite it. */
    private var replyShown = false

    override fun onCreateContentView(): View {
        val dp = ctx.resources.displayMetrics.density
        fun pad(v: Int) = (v * dp).toInt()
        heardView = TextView(ctx).apply { textSize = 16f; setTextColor(Color.LTGRAY) }
        resultView = TextView(ctx).apply { textSize = 20f; setTextColor(Color.WHITE) }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true // taps on the card must not fall through and close the overlay
            setPadding(pad(20), pad(16), pad(20), pad(16))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6202124"))
                cornerRadius = pad(24).toFloat()
            }
            addView(heardView)
            addView(resultView)
        }
        return LinearLayout(ctx).apply {
            gravity = Gravity.BOTTOM
            setPadding(pad(16), pad(16), pad(16), pad(48))
            addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            setOnClickListener { close() }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        main.removeCallbacksAndMessages(null)
        speaker.stop()
        brain.reset()
        active = true
        showCount++
        if (!resuming) followUpUntil = 0L
        resuming = false
        replyShown = false
        heardView.text = ""
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finishWith(Result(false, "Open Ajax and allow the microphone first."))
            return
        }
        resultView.text = "Listening…"
        speech.start()
    }

    override fun onHide() {
        active = false
        main.removeCallbacksAndMessages(null)
        speech.destroy()
        speaker.stop()
        super.onHide()
    }

    override fun onDestroy() {
        active = false
        showCount++
        main.removeCallbacksAndMessages(null)
        speech.destroy()
        speaker.shutdown()
        super.onDestroy()
    }

    override fun onListening(mode: String) {
        if (!active) return
        if (replyShown) heardView.text = "Listening…" else resultView.text = "Listening…"
    }

    override fun onPartial(text: String) {
        if (active) heardView.text = "“$text”"
    }

    override fun onResults(candidates: List<String>) {
        if (!active) return
        heardView.text = "“${candidates.first()}”"
        brain.handle(candidates, onThinking = { if (active) resultView.text = "Thinking…" }) { reply ->
            if (active) act(reply)
        }
    }

    private fun act(reply: Brain.Reply) {
        heardView.text = "“${brain.heard}”"
        val after = reply.afterHide
        when {
            after != null -> {
                // Accessibility acts on the window underneath, so get the overlay out of the way first.
                // Own handler: closing or hiding clears `main`.
                val shown = showCount
                if (reply.ends) close() else { active = false; hide() }
                Handler(Looper.getMainLooper()).postDelayed({
                    AjaxAccessibilityService.announce(after()) {
                        if (!reply.ends && shown == showCount) reopen()
                    }
                }, 450)
            }
            reply.listenAgain -> listenAfter(reply.result)
            reply.ends -> finishWith(reply.result)
            // A failed command only keeps an open conversation going; it can't extend it.
            !reply.result.ok && SystemClock.uptimeMillis() >= followUpUntil -> finishWith(reply.result)
            else -> listenAfter(reply.result, extend = reply.result.ok)
        }
    }

    /** Back from a command that needed the overlay out of the way: show it again, listening. */
    private fun reopen() {
        resuming = true
        followUpUntil = SystemClock.uptimeMillis() + FOLLOW_UP_MS
        show(null, 0)
    }

    private fun listenAfter(r: Result, extend: Boolean = false) {
        replyShown = true
        resultView.text = r.message
        speaker.speak(r.message) {
            if (active) {
                if (extend) followUpUntil = SystemClock.uptimeMillis() + FOLLOW_UP_MS
                speech.start()
            }
        }
    }

    override fun onError(message: String) {
        if (active) finishWith(Result(false, message))
    }

    override fun onNothingHeard() {
        if (!active) return
        when {
            followUpUntil == 0L -> finishWith(Result(false, "I didn't hear anything."))
            SystemClock.uptimeMillis() < followUpUntil -> main.postDelayed({ if (active) speech.start() }, 200)
            else -> close()
        }
    }

    private fun finishWith(r: Result) {
        resultView.text = r.message
        speaker.speak(r.message) { if (active) main.postDelayed({ close() }, 900) }
    }

    /** Tears the session down fully so the next side-key press starts clean. */
    private fun close() {
        active = false
        finish()
    }

    private companion object {
        const val FOLLOW_UP_MS = 12_000L
    }
}
