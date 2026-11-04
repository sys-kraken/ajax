package com.ajax.assistant

import android.service.voice.VoiceInteractionService

/** Registers Ajax as a digital assistant, so the side key long-press can start it. */
class AjaxInteractionService : VoiceInteractionService() {
    /** Lock-screen voice affordance (declared in voice_interaction.xml). */
    override fun onLaunchVoiceAssistFromKeyguard() {
        showSession(null, 0)
    }
}
