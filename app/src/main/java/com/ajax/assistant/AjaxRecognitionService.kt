package com.ajax.assistant

import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Required by Android for a valid digital assistant (without it the side key never reaches Ajax).
 * Android also makes it the phone-wide recogniser, so it forwards to the real engine to keep voice
 * typing working in other apps.
 */
class AjaxRecognitionService : RecognitionService() {

    private var rec: SpeechRecognizer? = null

    override fun onStartListening(intent: Intent, cb: Callback) {
        release()
        val target = Recognizers.delegate(this)
        if (target == null) {
            send { cb.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        rec = SpeechRecognizer.createSpeechRecognizer(this, target).also {
            it.setRecognitionListener(Forward(cb))
            it.startListening(intent)
        }
    }

    override fun onStopListening(cb: RecognitionService.Callback) {
        rec?.stopListening()
    }

    override fun onCancel(cb: RecognitionService.Callback) = release()

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        rec?.destroy()
        rec = null
    }

    private fun send(block: () -> Unit) {
        try { block() } catch (_: RemoteException) { release() } // caller went away
    }

    private inner class Forward(private val cb: RecognitionService.Callback) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = send { cb.readyForSpeech(params ?: Bundle()) }
        override fun onBeginningOfSpeech() = send { cb.beginningOfSpeech() }
        override fun onRmsChanged(rmsdB: Float) = send { cb.rmsChanged(rmsdB) }
        override fun onBufferReceived(buffer: ByteArray?) = send { buffer?.let(cb::bufferReceived) }
        override fun onEndOfSpeech() = send { cb.endOfSpeech() }
        override fun onPartialResults(partialResults: Bundle?) = send { cb.partialResults(partialResults ?: Bundle()) }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onResults(results: Bundle?) {
            send { cb.results(results ?: Bundle()) }
            release()
        }
        override fun onError(error: Int) {
            send { cb.error(error) }
            release()
        }
    }
}
