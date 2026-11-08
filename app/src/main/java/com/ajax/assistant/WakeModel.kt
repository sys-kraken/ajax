package com.ajax.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** The offline speech model behind the wake word: downloaded once, checked, unpacked into app storage. */
object WakeModel {
    private const val NAME = "vosk-model-small-en-us-0.15"
    private const val URL_STRING = "https://alphacephei.com/vosk/models/$NAME.zip"
    const val SIZE_BYTES = 41_205_931L
    private const val SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var downloading: ModelManager.State.Downloading? = null
    @Volatile private var lastError: String? = null

    /** Called on the main thread when the download moves on. One listener (the settings screen). */
    var listener: ((ModelManager.State) -> Unit)? = null

    private fun root(ctx: Context) = File(ctx.filesDir, "wake")
    fun path(ctx: Context) = File(root(ctx), NAME)
    private fun readyMark(ctx: Context) = File(path(ctx), ".ready")

    fun isReady(ctx: Context) = readyMark(ctx).exists()

    /** The files are there but won't load: delete them so they are downloaded again. */
    fun discard(ctx: Context) {
        root(ctx).deleteRecursively()
        lastError = "The speech model was damaged and has been removed. Turn listening on again to download it."
    }

    fun state(ctx: Context): ModelManager.State = when {
        isReady(ctx) -> ModelManager.State.Ready
        downloading != null -> downloading!!
        lastError != null -> ModelManager.State.Failed(lastError!!)
        else -> ModelManager.State.Missing
    }

    /** Downloads and unpacks the model; [done] runs on the main thread with whether it is ready. */
    fun download(ctx: Context, done: (Boolean) -> Unit = {}) {
        val app = ctx.applicationContext
        if (isReady(app)) { done(true); return }
        if (downloading != null) return
        lastError = null
        publish(ModelManager.State.Downloading(0, SIZE_BYTES))
        Thread {
            val ok = try {
                fetchAndUnpack(app)
                true
            } catch (e: Exception) {
                lastError = "Wake word download failed: ${e.message ?: e.javaClass.simpleName}. Try again on Wi-Fi."
                false
            }
            downloading = null
            main.post {
                listener?.invoke(state(app))
                done(ok)
            }
        }.start()
    }

    private fun publish(s: ModelManager.State.Downloading) {
        downloading = s
        main.post { listener?.invoke(s) }
    }

    private fun fetchAndUnpack(ctx: Context) {
        val dir = root(ctx).apply { deleteRecursively(); mkdirs() }
        val zip = File(dir, "$NAME.zip")
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = URL(URL_STRING).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("server said ${conn.responseCode}")
            conn.inputStream.use { input ->
                FileOutputStream(zip).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    var lastShown = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        total += n
                        if (total - lastShown > 1_000_000) {
                            lastShown = total
                            publish(ModelManager.State.Downloading(total, SIZE_BYTES))
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (hex != SHA256) { zip.delete(); throw IllegalStateException("the file didn't match its checksum") }
        val base = dir.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val target = File(dir, e.name)
                if (!target.canonicalPath.startsWith(base)) throw IllegalStateException("bad file name in the archive")
                if (e.isDirectory) target.mkdirs()
                else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { z.copyTo(it) }
                }
            }
        }
        zip.delete()
        readyMark(ctx).writeText("ok")
    }
}
