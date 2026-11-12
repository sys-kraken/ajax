package com.ajax.assistant

/**
 * Runs the keyword recogniser over the microphone audio and, only when it hears one of its keywords,
 * asks the trained detectors about that moment; hands back each utterance with both opinions on it.
 * The live listener and the recorded-speech check both go through here, so they hear alike.
 *
 * The detectors are the expensive part (two neural networks every 80 ms), so they don't follow the
 * sound as it happens. The last [RING_CHUNKS] of audio are kept instead, and when the recogniser
 * writes a keyword, the detectors score the utterance (with 2 s before it) in one go, a short moment after the utterance
 * ends (they answer a little after a word). Speech, music and noise that contain no keyword cost
 * only the recogniser. Audio thread only.
 */
class WakeListener(private val spotter: Spotter, private val detector: AjaxDetector?) {
    private companion object {
        /** Audio scored before the utterance starts: 2 s, the detectors' window. */
        const val LEAD_CHUNKS = 40

        /** Audio kept: the lead-in, the longest utterance (4 s) and the wait after it. */
        const val RING_CHUNKS = LEAD_CHUNKS + 80 + 12

        /** Audio after the utterance ends, before scoring it: 600 ms (the detectors answer late, later in music). */
        const val HOLD_CHUNKS = 12
    }

    private val ring = ArrayDeque<ByteArray>()

    // A keyword waiting for the moment of audio after it, before the detectors score it.
    private var held: Spotter.Heard? = null
    private var heldFor = 0
    private var heldLength = 0

    // Chunks in the utterance being heard so far.
    private var length = 0
    private val ready = ArrayDeque<Spotter.Heard>()

    /** Time the detectors have used, for measuring their cost. */
    var detectorNanos = 0L
        private set

    fun feed(buf: ByteArray, n: Int): Spotter.Heard? {
        val result = spotter.feed(buf, n)
        if (spotter.isSpeaking || result != null) length++ else length = 0
        if (detector != null) {
            ring.addLast(buf.copyOf(n))
            if (ring.size > RING_CHUNKS) ring.removeFirst()
        }
        if (result != null) {
            release() // something new was said: the held one is scored as it stands
            if (detector != null && detector.anyActive && mayBeKeyword(result.text)) {
                held = result
                heldFor = 0
                heldLength = length
            } else {
                result.scores = unscored()
                ready.addLast(result)
            }
            length = 0
        } else if (held != null && ++heldFor >= HOLD_CHUNKS) {
            release()
        }
        return ready.removeFirstOrNull()?.also { h ->
            h.wakeScore = h.scores?.get(WakeWords.Word.AJAX.ordinal)?.coerceAtLeast(0f) ?: 0f
        }
    }

    /** The recogniser wrote a keyword (alone or with other sound around it): worth asking the detectors. */
    private fun mayBeKeyword(text: String): Boolean {
        val core = WakeWords.normalize(text).split(" ").filter { it.isNotEmpty() && it != "unk" }.joinToString(" ")
        return WakeWords.match(core) != null
    }

    private fun release() {
        val h = held ?: return
        held = null
        h.scores = score(LEAD_CHUNKS + heldLength + heldFor)
        ready.addLast(h)
    }

    /** Scores the last [chunks] of audio from a fresh start and returns each word's highest score in it. */
    private fun score(chunks: Int): FloatArray? {
        val d = detector ?: return unscored()
        val t0 = System.nanoTime()
        d.reset()
        val peaks = unscored() ?: return null
        for (i in maxOf(0, ring.size - chunks) until ring.size) {
            val b = ring[i]
            d.feed(b, b.size)
            for (k in peaks.indices) if (d.scores[k] >= 0f) peaks[k] = maxOf(peaks[k], d.scores[k])
        }
        detectorNanos += System.nanoTime() - t0
        return peaks
    }

    /** No detector was asked (not a keyword): 0 for each word that has one, below zero otherwise. */
    private fun unscored(): FloatArray? {
        val d = detector ?: return null
        return FloatArray(d.scores.size) { if (d.has(WakeWords.Word.values()[it])) 0f else -1f }
    }

    fun reset() {
        spotter.reset()
        detector?.reset()
        ring.clear()
        held = null
        length = 0
        ready.clear()
    }
}
