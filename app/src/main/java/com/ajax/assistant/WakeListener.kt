package com.ajax.assistant

/**
 * Runs the keyword recogniser and the trained detectors side by side over the same audio, one
 * microphone chunk at a time, and hands back each utterance with both opinions on it. The live
 * listener and the recorded-speech check both go through here, so they hear alike.
 *
 * The detectors cost real CPU, so they run only while there is sound: when it starts they first
 * catch up on the last 2 s, then follow along until it stops. Audio thread only.
 */
class WakeListener(private val spotter: Spotter, private val detector: AjaxDetector?) {
    private companion object {
        /** How long a keyword waits for its detector to agree: 600 ms of 50 ms chunks. */
        const val HOLD_CHUNKS = 12
    }

    private val backlog = ArrayDeque<ByteArray>()     // the last 2 s of audio, for the detectors' context
    private val peaks = FloatArray(WakeWords.Word.values().size)
    private var detecting = false

    // The detectors answer a little after a word ends. Over music the recogniser often hands the
    // word over first, so a keyword whose detector hasn't agreed yet is held back for a moment.
    private var held: Spotter.Heard? = null
    private var heldFor = 0
    private val ready = ArrayDeque<Spotter.Heard>()

    /** Time the detectors have used, for measuring their cost. */
    var detectorNanos = 0L
        private set

    fun feed(buf: ByteArray, n: Int): Spotter.Heard? {
        val result = spotter.feed(buf, n)
        if (detector != null && !detector.anyActive) {
            // Every detector is switched off for now (keywords ignored while audio plays).
            detecting = false
        } else if (detector != null) {
            val t0 = System.nanoTime()
            if (spotter.isSpeaking || result != null || held != null) {
                if (!detecting) {
                    detecting = true
                    detector.reset()
                    peaks.fill(0f)
                    for (b in backlog) detector.feed(b, b.size)
                }
                detector.feed(buf, n)
                for (k in peaks.indices) peaks[k] = maxOf(peaks[k], detector.scores[k])
            } else {
                // Sound that carries straight on into the next utterance (music) keeps the detectors
                // going; only real quiet ends the run.
                detecting = false
            }
            backlog.addLast(buf.copyOf(n))
            if (backlog.size > 40) backlog.removeFirst()
            detectorNanos += System.nanoTime() - t0
        }
        if (result == null) {
            held?.let { h ->
                val s = h.scores
                if (detector != null && s != null) for (k in s.indices) if (s[k] >= 0f) s[k] = maxOf(s[k], detector.scores[k])
                heldFor++
                if (heldFor >= HOLD_CHUNKS || !WakeWords.waitsForDetector(h.text, s)) release()
            }
        } else {
            release() // something new was said: the held one is judged as it stands
            if (detector != null) {
                result.scores = FloatArray(peaks.size) { if (detector.scores[it] < 0f) -1f else peaks[it] }
                peaks.fill(0f)
            }
            if (WakeWords.waitsForDetector(result.text, result.scores)) {
                held = result
                heldFor = 0
            } else {
                ready.addLast(result)
            }
        }
        return ready.removeFirstOrNull()?.also { h ->
            h.wakeScore = h.scores?.get(WakeWords.Word.AJAX.ordinal)?.coerceAtLeast(0f) ?: 0f
        }
    }

    private fun release() {
        held?.let { ready.addLast(it) }
        held = null
    }

    fun reset() {
        spotter.reset()
        detector?.reset()
        peaks.fill(0f)
        backlog.clear()
        detecting = false
        held = null
        ready.clear()
    }
}
