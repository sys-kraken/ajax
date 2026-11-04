package com.ajax.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** On-device text-to-speech. Respects the "Speak replies" preference. Main thread only. */
class Speaker(ctx: Context) : TextToSpeech.OnInitListener {
    private val appCtx = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = HashMap<String, () -> Unit>()
    private var ready = false
    private var failed = false
    private var counter = 0
    private var pending: Pair<String, (() -> Unit)?>? = null
    private val tts = TextToSpeech(appCtx, this)

    private val enabled: Boolean
        get() = appCtx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).getBoolean(Prefs.SPEAK, true)

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        failed = !ready
        if (ready) {
            tts.language = Locale.US
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = fire(utteranceId)

                @Deprecated("Required override")
                override fun onError(utteranceId: String?) = fire(utteranceId)
            })
        }
        pending?.let { (t, cb) -> speak(t, cb) }
        pending = null
    }

    /** Speaks [text]; [onDone] runs afterwards (immediately if speech is off). */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank() || !enabled || failed) {
            onDone?.let { main.post(it) }
            return
        }
        if (!ready) { pending = text to onDone; return }
        val id = "u${counter++}"
        onDone?.let { callbacks[id] = it }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun fire(id: String?) {
        main.post { callbacks.remove(id)?.invoke() }
    }

    /** Stops talking and drops pending callbacks (overlay closed mid-sentence). */
    fun stop() {
        pending = null
        callbacks.clear()
        if (ready) tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
