package com.ajax.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.sqrt

/**
 * Turns microphone audio into short utterances, cheaply. Silence never reaches the recogniser: a
 * level check opens the gate when someone speaks (with a little audio from just before) and closes
 * it shortly after they stop, which ends the utterance at once instead of waiting for the
 * recogniser's own pause detection. The recogniser only knows the keywords, so it is fast and turns
 * other speech into "[unk]". Audio thread only.
 */
class Spotter(model: Model, phrases: Collection<String>) : AutoCloseable {
    companion object {
        const val RATE = 16_000
        /** 50 ms of 16-bit mono audio. */
        const val CHUNK_BYTES = RATE / 20 * 2
        private const val PRE_CHUNKS = 6      // 300 ms kept from before the speech started
        private const val HANG_CHUNKS = 7     // 350 ms of quiet ends an utterance
        private const val MAX_CHUNKS = 80     // 4 s: keywords are short, long talk is not for us
        // Low enough for a whisper close to the phone; the noise estimate keeps a loud room from
        // holding the gate open all the time.
        private const val MIN_LEVEL = 60.0
        private const val ABOVE_NOISE = 2.2

        fun level(buf: ByteArray, n: Int): Double {
            var sum = 0.0
            var i = 0
            while (i + 1 < n) {
                val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toDouble()
                sum += s * s
                i += 2
            }
            val count = n / 2
            return if (count == 0) 0.0 else sqrt(sum / count)
        }
    }

    /** One utterance: the words, and how sure the recogniser was of its least certain word (0 to 1). */
    class Heard(val text: String, val confidence: Double)

    private val rec = Recognizer(model, RATE.toFloat(), JSONArray(phrases.toList() + "[unk]").toString())
        .apply { setWords(true) }
    private val before = ArrayDeque<ByteArray>()
    private var noise = 100.0
    private var speaking = false
    private var quiet = 0
    private var length = 0

    /** Feeds one chunk; returns the utterance that just ended, if any. */
    fun feed(buf: ByteArray, n: Int): Heard? {
        val lvl = level(buf, n)
        val loud = lvl > maxOf(MIN_LEVEL, noise * ABOVE_NOISE)
        // A room that gets louder for good (a fan, rain) slowly raises the bar, so the recogniser
        // isn't kept running on noise. Short speech barely moves it.
        if (loud) noise = noise * 0.997 + lvl * 0.003
        if (!speaking) {
            if (!loud) {
                noise = noise * 0.95 + lvl * 0.05
                before.addLast(buf.copyOf(n))
                if (before.size > PRE_CHUNKS) before.removeFirst()
                return null
            }
            speaking = true
            quiet = 0
            length = 0
            for (b in before) rec.acceptWaveForm(b, b.size)
            before.clear()
        }
        length++
        quiet = if (loud) 0 else quiet + 1
        val done = rec.acceptWaveForm(buf, n)
        if (done || quiet >= HANG_CHUNKS || length >= MAX_CHUNKS) {
            speaking = false
            val json = JSONObject(if (done) rec.result else rec.finalResult)
            if (done) rec.reset()
            // "[unk]" stays in: a keyword with other speech around it must not count.
            val text = json.optString("text").trim()
            if (text.isEmpty()) return null
            val words = json.optJSONArray("result")
            var conf = if (words == null || words.length() == 0) 0.0 else 1.0
            if (words != null) for (i in 0 until words.length()) conf = minOf(conf, words.getJSONObject(i).optDouble("conf", 0.0))
            return Heard(text, conf)
        }
        return null
    }

    fun reset() {
        rec.reset()
        before.clear()
        speaking = false
    }

    override fun close() = rec.close()
}
