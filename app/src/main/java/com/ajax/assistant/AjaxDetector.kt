package com.ajax.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * The detectors trained on single words (openWakeWord style): audio -> mel spectrogram -> Google's
 * speech embedding, every 80 ms -> one small network per word over the last 16 embeddings (about
 * 2 s) -> a score from 0 to 1 for that word. The two feature models ship in the app and are shared;
 * each word's network is <word>.json (ajax, lumos, flashlight), which a version trained with your
 * own recordings (files/wake/<word>.json) replaces. A word without a network has no detector.
 * Audio thread only.
 */
class AjaxDetector private constructor(
    private val mel: Interpreter,
    private val emb: Interpreter,
    private val nets: Array<Net?>,
) : AutoCloseable {

    companion object {
        private const val CHUNK = 1280           // 80 ms
        private const val CONTEXT = 480          // the mel model needs 3 frames of audio before the chunk
        private const val MEL_FRAMES = 76
        private const val MEL_BINS = 32
        private const val FRAMES = 16
        private const val DIMS = 96

        /** Null if the models are missing or won't load; the keyword recogniser then decides alone. */
        fun open(ctx: Context): AjaxDetector? = try {
            val nets = Array(WakeWords.Word.values().size) { net(ctx, WakeWords.Word.values()[it].file) }
            if (nets.all { it == null }) null else {
                val opts = Interpreter.Options().setNumThreads(1)
                val mel = Interpreter(asset(ctx, "wake/melspectrogram.tflite"), opts)
                mel.resizeInput(0, intArrayOf(1, CHUNK + CONTEXT))
                mel.allocateTensors()
                val emb = Interpreter(asset(ctx, "wake/embedding_model.tflite"), opts)
                AjaxDetector(mel, emb, nets).apply { learnSilence() }
            }
        } catch (e: Exception) {
            null
        }

        /** The network for one word: yours if there is one, else the one in the app, else none. */
        private fun net(ctx: Context, name: String): Net? {
            val personal = File(ctx.filesDir, "wake/$name.json")
            if (personal.exists()) try { return Net(JSONObject(personal.readText())) } catch (e: Exception) {}
            return try {
                Net(JSONObject(ctx.assets.open("wake/$name.json").bufferedReader().use { it.readText() }))
            } catch (e: Exception) {
                null
            }
        }

        private fun asset(ctx: Context, name: String): ByteBuffer {
            val bytes = ctx.assets.open(name).use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        }
    }

    /** One word's small network: (16 x 96) -> hidden -> hidden -> 1. */
    private class Net(j: JSONObject) {
        val threshold = j.getDouble("threshold").toFloat()
        val mean = floats(j.getJSONArray("mean"))
        val std = floats(j.getJSONArray("std"))
        val w1 = matrix(j.getJSONArray("W1"))
        val b1 = floats(j.getJSONArray("b1"))
        val w2 = matrix(j.getJSONArray("W2"))
        val b2 = floats(j.getJSONArray("b2"))
        val w3 = floats(j.getJSONArray("W3"))
        val b3 = j.getDouble("b3").toFloat()
        private val scaled = FloatArray(mean.size)

        fun score(x: FloatArray): Float {
            for (i in x.indices) scaled[i] = (x[i] - mean[i]) / std[i]
            val h1 = layer(scaled, w1, b1)
            val h2 = layer(h1, w2, b2)
            var z = b3
            for (i in h2.indices) z += h2[i] * w3[i]
            return (1 / (1 + exp(-z.toDouble()))).toFloat()
        }

        private fun layer(x: FloatArray, w: Array<FloatArray>, b: FloatArray): FloatArray {
            val out = b.copyOf()
            for (i in x.indices) {
                val xi = x[i]
                if (xi == 0f) continue
                val row = w[i]
                for (k in out.indices) out[k] += xi * row[k]
            }
            for (k in out.indices) if (out[k] < 0f) out[k] = 0f
            return out
        }

        private fun floats(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }
        private fun matrix(a: JSONArray) = Array(a.length()) { floats(a.getJSONArray(it)) }
    }

    /** The score the "Ajax" detector would need if it decided alone (kept in the log). */
    val threshold: Float get() = nets[WakeWords.Word.AJAX.ordinal]?.threshold ?: 1f

    fun has(word: WakeWords.Word): Boolean = nets[word.ordinal] != null

    private val audio = FloatArray(CHUNK + CONTEXT)
    private var filled = 0                                  // new samples in the current chunk
    private val melFrames = ArrayDeque<FloatArray>().apply { repeat(MEL_FRAMES) { add(FloatArray(MEL_BINS) { 1f }) } }
    private val features = ArrayDeque<FloatArray>()
    private val melOut = Array(1) { Array(1) { Array(8) { FloatArray(MEL_BINS) } } }
    private val embIn = Array(1) { Array(MEL_FRAMES) { Array(MEL_BINS) { FloatArray(1) } } }
    private val embOut = Array(1) { Array(1) { Array(1) { FloatArray(DIMS) } } }
    private val window = FloatArray(FRAMES * DIMS)

    // What silence looks like to the feature models. A fresh start is filled with it, so a word said
    // right after listening resumes still gets a score (otherwise nothing is scored for 1.3 s).
    private var silentMel = FloatArray(MEL_BINS) { 1f }
    private var silentFeature: FloatArray? = null

    /** Latest score per [WakeWords.Word], 0 to 1; below zero for a word without a detector. */
    val scores = FloatArray(nets.size) { if (nets[it] == null) -1f else 0f }

    /**
     * Which words to score, one flag per [WakeWords.Word]. A word switched off keeps a score of 0;
     * with all of them off the listener skips the detector altogether.
     */
    val active = BooleanArray(nets.size) { true }

    val anyActive: Boolean get() = nets.indices.any { nets[it] != null && active[it] }

    /** Latest "Ajax" score. */
    val score: Float get() = maxOf(0f, scores[WakeWords.Word.AJAX.ordinal])

    /** Feeds 16-bit little-endian audio. Updates [scores] every 80 ms. */
    fun feed(buf: ByteArray, n: Int) {
        var i = 0
        while (i + 1 < n) {
            audio[CONTEXT + filled] = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toFloat()
            filled++
            i += 2
            if (filled == CHUNK) step()
        }
    }

    private fun step() {
        mel.run(arrayOf(audio).let { audio2d(it[0]) }, melOut)
        for (f in melOut[0][0]) {
            melFrames.removeFirst()
            melFrames.addLast(FloatArray(MEL_BINS) { f[it] / 10f + 2f })
        }
        for ((t, frame) in melFrames.withIndex()) for (b in 0 until MEL_BINS) embIn[0][t][b][0] = frame[b]
        emb.run(embIn, embOut)
        features.addLast(embOut[0][0][0].copyOf())
        if (features.size > FRAMES) features.removeFirst()
        if (features.size == FRAMES) {
            for ((t, f) in features.withIndex()) System.arraycopy(f, 0, window, t * DIMS, DIMS)
            for (k in nets.indices) nets[k]?.let { scores[k] = if (active[k]) it.score(window) else 0f }
        }
        // Keep the last 480 samples as context for the next chunk.
        System.arraycopy(audio, CHUNK, audio, 0, CONTEXT)
        filled = 0
    }

    private val melIn = Array(1) { FloatArray(CHUNK + CONTEXT) }
    private fun audio2d(a: FloatArray): Array<FloatArray> { System.arraycopy(a, 0, melIn[0], 0, a.size); return melIn }

    private fun learnSilence() {
        val quiet = ByteArray(CHUNK * 2)
        repeat(12) { feed(quiet, quiet.size) }
        silentMel = melFrames.last().copyOf()
        silentFeature = features.last().copyOf()
        reset()
    }

    fun reset() {
        filled = 0
        audio.fill(0f)
        // Entries are replaced, never changed, so sharing one array between them is safe.
        melFrames.clear()
        repeat(MEL_FRAMES) { melFrames.addLast(silentMel) }
        features.clear()
        silentFeature?.let { s -> repeat(FRAMES) { features.addLast(s) } }
        for (k in nets.indices) scores[k] = if (nets[k] == null) -1f else 0f
    }

    override fun close() {
        mel.close()
        emb.close()
    }
}
