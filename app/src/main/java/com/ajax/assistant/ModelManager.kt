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
 * Owns the on-device language model: downloads it once (resumable), verifies its SHA-256, loads it
 * on first use and unloads it after [IDLE_MS] without use. All model work runs on one thread.
 */
object ModelManager {
    // Qwen2.5-0.5B-Instruct, 4-bit (Q4_0) GGUF. Apache-2.0, no login needed.
    const val FILE_NAME = "qwen2.5-0.5b-instruct-q4_0.gguf"
    const val URL_STRING =
        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf"
    const val SIZE_BYTES = 428_730_208L
    /** Checked in CI by .github/workflows/model-hash.yml against the real file. */
    const val SHA256 = "7671c0c304e6ce5a7fc577bcb12aba01e2c155cc2efd29b2213c95b18edaf6ed"

    private const val IDLE_MS = 60_000L

    sealed class State {
        object Missing : State()
        class Downloading(val done: Long, val total: Long) : State()
        class Failed(val message: String) : State()
        object Ready : State()
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker: Handler by lazy { Handler(HandlerThread("ajax-llm").apply { start() }.looper) }
    private var handle = 0L // touched only on the worker thread
    private val unload = Runnable { freeModel() }

    @Volatile private var downloading: State.Downloading? = null
    @Volatile private var lastError: String? = null

    /** Called on the main thread whenever the state changes. One listener (the setup screen). */
    var listener: ((State) -> Unit)? = null

    private fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    private fun file(ctx: Context) = File(dir(ctx), FILE_NAME)
    private fun part(ctx: Context) = File(dir(ctx), "$FILE_NAME.part")
    /** Written after the hash matched, so we don't re-hash 400 MB on every start. */
    private fun stamp(ctx: Context) = File(dir(ctx), "$FILE_NAME.sha256")

    fun state(ctx: Context): State = when {
        isReady(ctx) -> State.Ready
        downloading != null -> downloading!!
        lastError != null -> State.Failed(lastError!!)
        else -> State.Missing
    }

    fun isReady(ctx: Context): Boolean =
        LlamaNative.available && file(ctx).length() == SIZE_BYTES &&
            stamp(ctx).takeIf { it.exists() }?.readText()?.trim() == SHA256

    // ---- Download --------------------------------------------------------------------------

    fun download(ctx: Context) {
        if (downloading != null || isReady(ctx)) return
        val app = ctx.applicationContext
        lastError = null
        publish(State.Downloading(part(app).length(), SIZE_BYTES).also { downloading = it })
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
            publish(State.Missing)
        }
    }

    private fun fetchAndVerify(ctx: Context) {
        val part = part(ctx)
        if (part.length() > SIZE_BYTES) part.delete()
        val resumed = part.length() in 1 until SIZE_BYTES
        if (part.length() < SIZE_BYTES) fetch(part)
        if (part.length() != SIZE_BYTES) throw IllegalStateException("Download incomplete. Tap again to resume.")
        var actual = sha256(part)
        if (!actual.equals(SHA256, ignoreCase = true) && resumed) {
            // A resumed download is the only way a correctly sized file can differ: start clean once.
            part.delete()
            fetch(part)
            if (part.length() != SIZE_BYTES) throw IllegalStateException("Download incomplete. Tap again to resume.")
            actual = sha256(part)
        }
        if (!actual.equals(SHA256, ignoreCase = true)) {
            part.delete()
            throw IllegalStateException(
                "Downloaded file failed the integrity check and was deleted " +
                    "(expected ${SHA256.take(8)}, got ${actual.take(8)}).",
            )
        }
        if (!part.renameTo(file(ctx))) throw IllegalStateException("Couldn't save the model file.")
        stamp(ctx).writeText(SHA256)
    }

    /** Resumes from where [part] stopped, using an HTTP Range request. */
    private fun fetch(part: File) {
        val start = part.length()
        val conn = (URL(URL_STRING).openConnection() as HttpURLConnection).apply {
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
                            publish(State.Downloading(done, SIZE_BYTES).also { downloading = it })
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(f: File): String {
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

    private fun publish(s: State) = main.post { listener?.invoke(s) }

    // ---- Inference -------------------------------------------------------------------------

    /**
     * Runs a grammar-constrained completion on the model thread; [done] gets the text (or null) on
     * the main thread. Loads the model if needed and schedules unloading after [IDLE_MS].
     */
    fun complete(ctx: Context, prompt: String, grammar: String, maxTokens: Int, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        worker.post {
            worker.removeCallbacks(unload)
            val out = try {
                if (handle == 0L && isReady(app)) {
                    val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                    handle = LlamaNative.load(file(app).absolutePath, threads)
                }
                if (handle == 0L) null else LlamaNative.complete(handle, prompt, grammar, maxTokens)
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
