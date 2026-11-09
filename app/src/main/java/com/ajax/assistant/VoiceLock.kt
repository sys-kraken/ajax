package com.ajax.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.SpeakerModel
import java.io.File
import kotlin.math.sqrt

/**
 * Voice lock: "Ajax", lumos, nox and flashlight count only in your voice, so a video, song or
 * another person saying them does nothing. Your voice print is the average fingerprint of your
 * "Teach Ajax your voice" recordings, made on the phone and kept in its private storage.
 */
object VoiceLock {
    /**
     * How alike a phrase must sound to your recordings (cosine similarity of voice fingerprints,
     * -1 to 1). Set from measurements of your recordings against other voices.
     */
    const val THRESHOLD = 0.5f

    private const val PRINT_VERSION = 3

    /** Below this much speech (10 ms frames) a fingerprint is too rough to judge. */
    const val MIN_FRAMES = 20

    fun enabled(ctx: Context) =
        ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).getBoolean(Prefs.VOICE_LOCK, false)

    private fun printFile(ctx: Context) = File(ctx.filesDir, "voiceprint.json")

    /** Your voice print(s), from your recordings. */
    @Volatile private var prints: List<FloatArray>? = null
    @Volatile private var printFrom = -1

    fun hasPrint(ctx: Context): Boolean = load(ctx) != null

    /** How many recordings the current voice print was made from (0 = none yet). */
    fun printedFrom(ctx: Context): Int { load(ctx); return maxOf(0, printFrom) }

    private fun load(ctx: Context): List<FloatArray>? {
        prints?.let { return it }
        val f = printFile(ctx)
        if (!f.exists()) return null
        return try {
            val j = JSONObject(f.readText())
            // A print made a different way is made again.
            if (j.optInt("version", 1) != PRINT_VERSION) return null
            val arr = j.getJSONArray("prints")
            printFrom = j.optInt("from", 0)
            List(arr.length()) { i -> arr.getJSONArray(i).let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } } }
                .takeIf { it.isNotEmpty() }?.also { prints = it }
        } catch (e: Exception) {
            null
        }
    }

    /** The recordings changed since the voice print was made (or there is none yet). */
    fun needsPrint(ctx: Context): Boolean {
        val n = VoiceSamples.count(ctx)
        return n > 0 && (load(ctx) == null || printFrom != n)
    }

    fun forget(ctx: Context) {
        printFile(ctx).delete()
        prints = null
        printFrom = -1
    }

    /**
     * Makes the voice prints from the recordings: each one goes through the same listener the wake
     * word uses, so the fingerprints match what it hears later. (A second print from the recordings
     * with simulated room echo was tried: echo makes every voice alike, so it let other voices in
     * from across a room. Only real recordings make prints.) Audio thread only. Returns how many
     * recordings were usable.
     */
    fun makePrint(ctx: Context, model: Model, speakers: SpeakerModel): Int {
        val files = VoiceSamples.dir(ctx).listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name } ?: return 0
        val close = mutableListOf<FloatArray>()
        Spotter(model, WakeWords.phrases, speakers).use { s ->
            for (f in files) {
                val pcm = f.readBytes().let { it.copyOfRange(minOf(44, it.size), it.size) }
                fingerprint(s, pcm)?.let { close += it }
            }
        }
        if (close.isEmpty()) return 0
        val result = listOf(centroid(close))
        printFile(ctx).writeText(JSONObject().apply {
            put("prints", JSONArray().apply { result.forEach { p -> put(JSONArray(p.map { it.toDouble() })) } })
            put("version", PRINT_VERSION)
            put("from", files.size)
            put("usable", close.size)
        }.toString())
        prints = result
        printFrom = files.size
        return close.size
    }

    /** One recording through the listener: the fingerprint of its longest phrase, if long enough. */
    private fun fingerprint(s: Spotter, pcm: ByteArray): FloatArray? {
        var best: Spotter.Heard? = null
        val feed = { buf: ByteArray, n: Int ->
            s.feed(buf, n)?.let { h -> if (h.voice != null && h.voiceFrames > (best?.voiceFrames ?: -1)) best = h }
        }
        var i = 0
        while (i < pcm.size) {
            val n = minOf(Spotter.CHUNK_BYTES, pcm.size - i)
            feed(pcm.copyOfRange(i, i + n), n)
            i += n
        }
        val silence = ByteArray(Spotter.CHUNK_BYTES)
        repeat(20) { feed(silence, silence.size) }
        s.reset()
        return best?.takeIf { it.voiceFrames >= MIN_FRAMES }?.voice?.let { normalized(it) }
    }

    private fun centroid(vs: List<FloatArray>): FloatArray {
        val mean = FloatArray(vs[0].size)
        for (v in vs) for (k in mean.indices) mean[k] += v[k] / vs.size
        return normalized(mean)
    }

    /** How much [voice] sounds like you, -1 to 1 (the closer of your prints); null without either. */
    fun similarity(ctx: Context, voice: FloatArray?): Float? {
        val ps = load(ctx) ?: return null
        if (voice == null) return null
        val v = normalized(voice)
        var best: Float? = null
        for (p in ps) {
            if (p.size != v.size) continue
            var dot = 0f
            for (k in p.indices) dot += p[k] * v[k]
            if (best == null || dot > best) best = dot
        }
        return best
    }

    /**
     * Whether a keyword phrase may act. With the lock off, or no voice print yet, always. With it
     * on: only when the voice clearly sounds like yours.
     */
    fun accepts(ctx: Context, h: Spotter.Heard): Boolean {
        if (!enabled(ctx) || !hasPrint(ctx)) return true
        if (h.voiceFrames < MIN_FRAMES) return false
        val s = similarity(ctx, h.voice) ?: return false
        return s >= THRESHOLD
    }

    private fun normalized(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        val len = sqrt(n).toFloat().takeIf { it > 0f } ?: return v
        return FloatArray(v.size) { v[it] / len }
    }
}
