package com.ajax.assistant

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * One on-device language model file: downloads it once (resumable), verifies its SHA-256, loads it
 * on first use and unloads it after [IDLE_MS] without use. Every model shares one thread, so only
 * one of them ever computes at a time.
 */
class GgufModel(
    private val fileName: String,
    private val url: String,
    val sizeBytes: Long,
    private val sha256: String,
) {
    private companion object {
        // Kept loaded for a while after use: loading costs seconds, keeping it costs only memory.
        const val IDLE_MS = 5 * 60_000L

        val main = Handler(Looper.getMainLooper())
        val worker: Handler by lazy { Handler(HandlerThread("ajax-llm").apply { start() }.looper) }
    }

    private var handle = 0L // touched only on the worker thread
    private val unload = Runnable { freeModel() }

    @Volatile private var downloading: ModelManager.State.Downloading? = null
    @Volatile private var lastError: String? = null

    /** Called on the main thread whenever the state changes. One listener (the settings screen). */
    var listener: ((ModelManager.State) -> Unit)? = null

    private fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    private fun file(ctx: Context) = File(dir(ctx), fileName)
    private fun part(ctx: Context) = File(dir(ctx), "$fileName.part")
    /** Written after the hash matched, so the whole file isn't hashed again on every start. */
    private fun stamp(ctx: Context) = File(dir(ctx), "$fileName.sha256")

    fun state(ctx: Context): ModelManager.State = when {
        isReady(ctx) -> ModelManager.State.Ready
        downloading != null -> downloading!!
        lastError != null -> ModelManager.State.Failed(lastError!!)
        else -> ModelManager.State.Missing
    }

    fun isReady(ctx: Context): Boolean =
        LlamaNative.available && file(ctx).length() == sizeBytes &&
            stamp(ctx).takeIf { it.exists() }?.readText()?.trim() == sha256

    // ---- Download --------------------------------------------------------------------------

    fun download(ctx: Context) {
        if (downloading != null || isReady(ctx)) return
        val app = ctx.applicationContext
        lastError = null
        publish(ModelManager.State.Downloading(part(app).length(), sizeBytes).also { downloading = it })
        Thread({
            val err = try { fetchAndVerify(app); null } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            downloading = null
            lastError = err
            publish(state(app))
        }, "ajax-model-download").start()
    }

    fun delete(ctx: Context) {
        if (downloading != null) return
        worker.post {
            freeModel()
            listOf(file(ctx), part(ctx), stamp(ctx)).forEach { it.delete() }
            lastError = null
            publish(ModelManager.State.Missing)
        }
    }

    private fun fetchAndVerify(ctx: Context) {
        val part = part(ctx)
        if (part.length() > sizeBytes) part.delete()
        val resumed = part.length() in 1 until sizeBytes
        if (part.length() < sizeBytes) fetch(part)
        if (part.length() != sizeBytes) throw IllegalStateException("Download incomplete. Tap again to resume.")
        var actual = hash(part)
        if (!actual.equals(sha256, ignoreCase = true) && resumed) {
            // A resumed download is the only way a correctly sized file can differ: start clean once.
            part.delete()
            fetch(part)
            if (part.length() != sizeBytes) throw IllegalStateException("Download incomplete. Tap again to resume.")
            actual = hash(part)
        }
        if (!actual.equals(sha256, ignoreCase = true)) {
            part.delete()
            throw IllegalStateException(
                "Downloaded file failed the integrity check and was deleted " +
                    "(expected ${sha256.take(8)}, got ${actual.take(8)}).",
            )
        }
        if (!part.renameTo(file(ctx))) throw IllegalStateException("Couldn't save the model file.")
        stamp(ctx).writeText(sha256)
    }

    /** Resumes from where [part] stopped, using an HTTP Range request. */
    private fun fetch(part: File) {
        val start = part.length()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept-Encoding", "identity") // raw bytes only, never re-encoded
            if (start > 0) setRequestProperty("Range", "bytes=$start-")
        }
        try {
            val code = conn.responseCode
            val append = when (code) {
                // Only append if the server resumed exactly where our file ends.
                HttpURLConnection.HTTP_PARTIAL -> {
                    if (conn.getHeaderField("Content-Range")?.startsWith("bytes $start-") != true) {
                        part.delete()
                        throw IllegalStateException("Server resumed at the wrong place. Tap again to restart.")
                    }
                    true
                }
                HttpURLConnection.HTTP_OK -> false // server ignored Range: start over
                else -> throw IllegalStateException("Download failed (HTTP $code).")
            }
            var done = if (append) start else 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        if (done - lastReport >= 2_000_000) {
                            lastReport = done
                            publish(ModelManager.State.Downloading(done, sizeBytes).also { downloading = it })
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun hash(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun publish(s: ModelManager.State) = main.post { listener?.invoke(s) }

    // ---- Inference -------------------------------------------------------------------------

    /**
     * Runs a completion on the model thread; [done] gets the text (or null) on the main thread.
     * With a [grammar] the output is restricted to it; with an empty one the model writes freely.
     * Loads the model if needed and schedules unloading after [IDLE_MS].
     */
    fun complete(ctx: Context, prefix: String, suffix: String, grammar: String, maxTokens: Int, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        worker.post {
            worker.removeCallbacks(unload)
            val out = try {
                val started = android.os.SystemClock.uptimeMillis()
                if (handle == 0L && isReady(app)) {
                    val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                    handle = LlamaNative.load(file(app).absolutePath, threads)
                    if (handle != 0L) LlamaNative.setCache(handle, File(app.cacheDir, "prompt-$sha256.kv").absolutePath)
                }
                val loaded = android.os.SystemClock.uptimeMillis()
                val result = if (handle == 0L) null else LlamaNative.complete(handle, prefix, suffix, grammar, maxTokens)
                android.util.Log.i("AjaxLlm", "$fileName: load ${loaded - started} ms, answer ${android.os.SystemClock.uptimeMillis() - loaded} ms")
                result
            } catch (e: Throwable) {
                null
            }
            worker.postDelayed(unload, IDLE_MS)
            main.post { done(out) }
        }
    }

    private fun freeModel() {
        if (handle != 0L) LlamaNative.free(handle)
        handle = 0L
    }
}
