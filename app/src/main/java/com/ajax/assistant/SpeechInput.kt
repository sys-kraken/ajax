package com.ajax.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Push-to-talk speech to text. Prefers the on-device recogniser (Android 13+). Main thread only. */
class SpeechInput(private val ctx: Context, private val cb: Callback) {

    interface Callback {
        fun onListening(mode: String)
        fun onPartial(text: String)
        fun onResults(candidates: List<String>)
        fun onError(message: String)
    }

    private var rec: SpeechRecognizer? = null
    private var onDevice = false
    private var forceStandard = false
    private var retriedStandard = false
    private var retriedOnline = false

    /** Off by default: audio stays on the phone. When on, the system recogniser may use the network. */
    var allowOnline = false

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !(allowOnline && !onDevice))
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    }

    fun start() {
        retriedOnline = false
        begin()
    }

    private fun begin() {
        release()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            cb.onError("This phone has no speech recognition service.")
            return
        }
        val r = create()
        r.setRecognitionListener(listener)
        rec = r
        r.startListening(intent())
        cb.onListening(if (onDevice) "on-device" else "system recogniser (may use the network)")
    }

    /** User released the button: finish and return what was heard. */
    fun stopListening() {
        rec?.stopListening()
    }

    fun destroy() = release()

    private fun create(): SpeechRecognizer {
        onDevice = !forceStandard && Build.VERSION.SDK_INT >= 33 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        return if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        else SpeechRecognizer.createSpeechRecognizer(ctx)
    }

    private fun release() {
        rec?.destroy()
        rec = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.let(cb::onPartial)
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (list.isEmpty()) cb.onError("I didn't catch that.") else cb.onResults(list)
        }

        override fun onError(error: Int) {
            // 12/13: language not supported / pack missing. Retry once with the system recogniser.
            if ((error == 12 || error == 13) && onDevice && !retriedStandard) {
                retriedStandard = true
                forceStandard = true
                begin()
                return
            }
            if ((error == 12 || error == 13) && !onDevice && allowOnline && !retriedOnline) {
                retriedOnline = true
                begin() // now without PREFER_OFFLINE
                return
            }
            cb.onError(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_CLIENT ->
                        "I didn't hear anything. Hold the button while you speak."
                    SpeechRecognizer.ERROR_AUDIO -> "Microphone problem."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        "Speech recognition needs the network, or an offline English pack."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recogniser is busy. Try again."
                    12, 13 -> "English isn't available offline. Google app > profile > Settings > Voice > Offline speech recognition > English (US). Or turn on online recognition below."
                    else -> "Speech recognition failed (code $error)."
                },
            )
        }
    }
}
