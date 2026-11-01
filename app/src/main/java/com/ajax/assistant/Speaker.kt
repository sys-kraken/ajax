package com.ajax.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** On-device text-to-speech for short confirmations. */
class Speaker(ctx: Context) : TextToSpeech.OnInitListener {
    var enabled = true
    private var ready = false
    private var pending: String? = null
    private val tts = TextToSpeech(ctx.applicationContext, this)

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.US
            pending?.let { speak(it) }
        }
        pending = null
    }

    fun speak(text: String) {
        if (!enabled) return
        if (!ready) { pending = text; return }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ajax")
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
