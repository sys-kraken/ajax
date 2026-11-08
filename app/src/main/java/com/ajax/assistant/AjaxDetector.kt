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
 * The detector trained on "Ajax" (openWakeWord style): audio -> mel spectrogram -> Google's speech
 * embedding, every 80 ms -> a small network over the last 16 embeddings (about 2 s) -> a score from
 * 0 to 1. The two feature models ship in the app; the small network is ajax.json, which a version
 * trained with your own recordings (files/ajax.json) replaces. Audio thread only.
 */
class AjaxDetector private constructor(
    private val mel: Interpreter,
    private val emb: Interpreter,
    private val net: Net,
) : AutoCloseable {

    companion object {
        private const val CHUNK = 1280           // 80 ms
        private const val CONTEXT = 480          // the mel model needs 3 frames of audio before the chunk
        private const val MEL_FRAMES = 76
        private const val MEL_BINS = 32
        private const val FRAMES = 16
        private const val DIMS = 96

        /** Null if the models are missing or won't load; Ajax then falls back to the keyword recogniser. */
        fun open(ctx: Context): AjaxDetector? = try {
            val opts = Interpreter.Options().setNumThreads(1)
            val mel = Interpreter(asset(ctx, "wake/melspectrogram.tflite"), opts)
            mel.resizeInput(0, intArrayOf(1, CHUNK + CONTEXT))
            mel.allocateTensors()
            val emb = Interpreter(asset(ctx, "wake/embedding_model.tflite"), opts)
            val personal = File(ctx.filesDir, "wake/ajax.json")
            val json = if (personal.exists()) personal.readText() else ctx.assets.open("wake/ajax.json").bufferedReader().readText()
            AjaxDetector(mel, emb, Net(JSONObject(json)))
        } catch (e: Exception) {
            null
        }

        private fun asset(ctx: Context, name: String): ByteBuffer {
            val bytes = ctx.assets.open(name).use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        }
    }

    /** The small network from ajax.json: (16 x 96) -> hidden -> hidden -> 1. */
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

        fun score(x: FloatArray): Float {
            for (i in x.indices) x[i] = (x[i] - mean[i]) / std[i]
            val h1 = layer(x, w1, b1)
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

    val threshold: Float get() = net.threshold

    private val audio = FloatArray(CHUNK + CONTEXT)
    private var filled = 0                                  // new samples in the current chunk
    private val melFrames = ArrayDeque<FloatArray>().apply { repeat(MEL_FRAMES) { add(FloatArray(MEL_BINS) { 1f }) } }
    private val features = ArrayDeque<FloatArray>()
    private val melOut = Array(1) { Array(1) { Array(8) { FloatArray(MEL_BINS) } } }
    private val embIn = Array(1) { Array(MEL_FRAMES) { Array(MEL_BINS) { FloatArray(1) } } }
    private val embOut = Array(1) { Array(1) { Array(1) { FloatArray(DIMS) } } }

    /** Latest score; 0 until 2 s of audio have been heard. */
    var score = 0f
        private set

    /** Feeds 16-bit little-endian audio. Updates [score] every 80 ms. */
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
            val x = FloatArray(FRAMES * DIMS)
            for ((t, f) in features.withIndex()) System.arraycopy(f, 0, x, t * DIMS, DIMS)
            score = net.score(x)
        }
        // Keep the last 480 samples as context for the next chunk.
        System.arraycopy(audio, CHUNK, audio, 0, CONTEXT)
        filled = 0
    }

    private val melIn = Array(1) { FloatArray(CHUNK + CONTEXT) }
    private fun audio2d(a: FloatArray): Array<FloatArray> { System.arraycopy(a, 0, melIn[0], 0, a.size); return melIn }

    fun reset() {
        filled = 0
        audio.fill(0f)
        features.clear()
        score = 0f
    }

    override fun close() {
        mel.close()
        emb.close()
    }
}
