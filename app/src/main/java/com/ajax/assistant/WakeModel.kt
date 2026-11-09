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

/** The offline speech model behind the wake word. */
val WakeModel = ZipModel(
    dirName = "wake",
    name = "vosk-model-small-en-us-0.15",
    what = "Wake word",
    sizeBytes = 41_205_931L,
    sha256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498",
)

/** The offline model that tells voices apart, for the voice lock. */
val VoiceModel = ZipModel(
    dirName = "voiceprint",
    name = "vosk-model-spk-0.4",
    what = "Voice lock",
    sizeBytes = 13_869_103L,
    sha256 = "a74d8f51144484813e16af689bb0f916b7a111e2347f467c4933c1166097b5a7",
)

/** A Vosk model zip: downloaded once from alphacephei.com, checked against its SHA-256, unpacked into app storage. */
class ZipModel(
    private val dirName: String,
    private val name: String,
    private val what: String,
    val sizeBytes: Long,
    private val sha256: String,
) {
    private val url = "https://alphacephei.com/vosk/models/$name.zip"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var downloading: ModelManager.State.Downloading? = null
    @Volatile private var lastError: String? = null

    /** Called on the main thread when the download moves on. One listener (the settings screen). */
    var listener: ((ModelManager.State) -> Unit)? = null

    private fun root(ctx: Context) = File(ctx.filesDir, dirName)
    fun path(ctx: Context) = File(root(ctx), name)
    private fun readyMark(ctx: Context) = File(path(ctx), ".ready")

    fun isReady(ctx: Context) = readyMark(ctx).exists()

    /** The files are there but won't load: delete them so they are downloaded again. */
    fun discard(ctx: Context) {
        root(ctx).deleteRecursively()
        lastError = "The $what model was damaged and has been removed. Turn it on again to download it."
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
        publish(ModelManager.State.Downloading(0, sizeBytes))
        Thread {
            val ok = try {
                fetchAndUnpack(app)
                true
            } catch (e: Exception) {
                lastError = "$what download failed: ${e.message ?: e.javaClass.simpleName}. Try again on Wi-Fi."
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
        val zip = File(dir, "$name.zip")
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = URL(url).openConnection() as HttpURLConnection
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
                            publish(ModelManager.State.Downloading(total, sizeBytes))
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (hex != sha256) { zip.delete(); throw IllegalStateException("the file didn't match its checksum") }
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
