package com.ajax.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** The small overlay shown when the side key is held: listens once, acts, then closes. */
class AjaxSession(private val ctx: Context) : VoiceInteractionSession(ctx), SpeechInput.Callback {

    private val main = Handler(Looper.getMainLooper())
    private val brain = Brain(ctx)
    private val speech = SpeechInput(ctx, this)
    private val speaker = Speaker(ctx)
    private lateinit var heardView: TextView
    private lateinit var resultView: TextView

    override fun onCreateContentView(): View {
        val dp = ctx.resources.displayMetrics.density
        fun pad(v: Int) = (v * dp).toInt()
        heardView = TextView(ctx).apply { textSize = 16f; setTextColor(Color.LTGRAY) }
        resultView = TextView(ctx).apply { textSize = 20f; setTextColor(Color.WHITE) }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
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
            setOnClickListener { hide() }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        heardView.text = ""
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finishWith(Result(false, "Open Ajax and allow the microphone first."))
            return
        }
        resultView.text = "Listening…"
        speech.start()
    }

    override fun onHide() {
        speech.destroy()
        super.onHide()
    }

    override fun onDestroy() {
        speech.destroy()
        speaker.shutdown()
        super.onDestroy()
    }

    override fun onListening(mode: String) {
        resultView.text = "Listening…"
    }

    override fun onPartial(text: String) {
        heardView.text = "“$text”"
    }

    override fun onResults(candidates: List<String>) {
        val reply = brain.handle(candidates)
        heardView.text = "“${brain.heard}”"
        val after = reply.afterHide
        when {
            after != null -> {
                // Accessibility acts on the window underneath, so close the overlay first.
                resultView.text = reply.result.message
                hide()
                main.postDelayed({ AjaxAccessibilityService.announce(after()) }, 450)
            }
            reply.listenAgain -> {
                resultView.text = (if (reply.result.ok) "" else "✗ ") + reply.result.message
                speaker.speak(reply.result.message) { speech.start() }
            }
            else -> finishWith(reply.result)
        }
    }

    override fun onError(message: String) {
        finishWith(Result(false, message))
    }

    private fun finishWith(r: Result) {
        resultView.text = (if (r.ok) "✓ " else "✗ ") + r.message
        speaker.speak(r.message) { main.postDelayed({ hide() }, 900) }
    }
}
