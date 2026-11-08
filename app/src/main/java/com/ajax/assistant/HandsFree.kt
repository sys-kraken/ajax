package com.ajax.assistant

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * "Ajax" heard while the screen is off or locked: no overlay, just a beep, one command and a spoken
 * reply. Only commands that are harmless for anyone in the room, and work without the screen, run
 * here; everything else asks for the phone to be unlocked. Main thread only.
 */
object HandsFree : SpeechInput.Callback {
    private val main = Handler(Looper.getMainLooper())
    private var active = false
    private var locked = false
    private var speech: SpeechInput? = null
    private var speaker: Speaker? = null
    private var brain: Brain? = null

    fun start(ctx: Context, locked: Boolean) {
        if (active) return
        val app = ctx.applicationContext
        active = true
        this.locked = locked
        MicGuard.hold(this, maxMs = 120_000)
        speaker = Speaker(app)
        speech = SpeechInput(app, this)
        brain = Brain(app).apply {
            gate = { m ->
                if (WakeWords.allowedHandsFree(m.id, m.hintOnly)) null
                else Result(false, if (this@HandsFree.locked) "Unlock your phone for that." else "Turn the screen on for that.")
            }
        }
        IntentModel.warm(app)
        beep()
        main.postDelayed({ if (active) speech?.start() }, 300)
        // Never left half-open: a speech service that stops answering would block the next "Ajax".
        main.postDelayed(giveUp, 60_000)
    }

    private val giveUp = Runnable { finish() }

    private fun beep() {
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 70).apply {
                startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                main.postDelayed({ release() }, 400)
            }
        } catch (e: RuntimeException) {}
    }

    override fun onListening(mode: String) {}
    override fun onPartial(text: String) {}

    override fun onResults(candidates: List<String>) {
        if (!active) return
        brain?.handle(candidates) { reply -> if (active) act(reply) }
    }

    private fun act(reply: Brain.Reply) {
        when {
            // Never reached with the gate in place; kept so nothing acts on a screen nobody can see.
            reply.afterHide != null -> say(Result(false, "Unlock your phone for that."), again = false)
            reply.listenAgain -> say(reply.result, again = true)
            else -> say(reply.result, again = false)
        }
    }

    private fun say(r: Result, again: Boolean) {
        speaker?.speak(r.message) { if (active) { if (again) speech?.start() else finish() } }
            ?: finish()
    }

    override fun onError(message: String) {
        if (active) say(Result(false, message), again = false)
    }

    override fun onNothingHeard() {
        if (active) finish()
    }

    private fun finish() {
        if (!active) return
        active = false
        main.removeCallbacks(giveUp)
        speech?.destroy(); speech = null
        speaker?.shutdown(); speaker = null
        brain = null
        MicGuard.release(this)
    }
}
