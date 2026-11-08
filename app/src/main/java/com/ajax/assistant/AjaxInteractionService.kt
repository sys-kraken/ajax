package com.ajax.assistant

import android.service.voice.VoiceInteractionService

/** Registers Ajax as a digital assistant, so the side key long-press can start it. */
class AjaxInteractionService : VoiceInteractionService() {
    companion object {
        /** Set while Android has Ajax bound as the assistant. */
        @Volatile private var ready: AjaxInteractionService? = null

        /** Opens the listening overlay. False if Ajax isn't the active assistant. */
        fun listen(): Boolean {
            val service = ready ?: return false
            service.showSession(null, 0)
            return true
        }
    }

    override fun onReady() {
        super.onReady()
        ready = this
        // Android binds the assistant at startup: resume wake word listening if it was on.
        if (WakeService.enabled(this) && !WakeService.running && !WakeService.start(this)) WakeService.askToResume(this)
    }

    override fun onShutdown() {
        ready = null
        super.onShutdown()
    }

    /** Lock-screen voice affordance (declared in voice_interaction.xml). */
    override fun onLaunchVoiceAssistFromKeyguard() {
        showSession(null, 0)
    }
}
