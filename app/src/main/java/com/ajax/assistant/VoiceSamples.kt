package com.ajax.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * "Teach Ajax your voice": records you saying "Ajax" [TAKES] times, 2 seconds each after a beep,
 * into Ajax's private storage (files/voice). These train a version of the detector that knows your
 * voice; they never leave the phone except when you connect it to your PC for that training.
 * "lumos" and "flashlight" are recorded the same way into their own folders (files/voice-lumos,
 * files/voice-flashlight), for the detectors of those words; only the "Ajax" ones make the voice print.
 */
object VoiceSamples {
    const val TAKES = 25
    private const val RATE = 16_000
    private const val SECONDS = 2

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    fun dir(ctx: Context, word: WakeWords.Word = WakeWords.Word.AJAX) =
        File(ctx.filesDir, if (word == WakeWords.Word.AJAX) "voice" else "voice-${word.file}")

    fun count(ctx: Context, word: WakeWords.Word = WakeWords.Word.AJAX) =
        dir(ctx, word).listFiles { f -> f.name.endsWith(".wav") }?.size ?: 0

    fun clear(ctx: Context) { dir(ctx).deleteRecursively() }

    /** [progress] gets "Say Ajax (3 of 25)" and similar on the main thread; [done] runs at the end. */
    fun record(ctx: Context, word: WakeWords.Word = WakeWords.Word.AJAX, progress: (String) -> Unit, done: () -> Unit) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        MicGuard.hold(this, maxMs = 5 * 60_000)
        Thread {
            val out = dir(app, word).apply { mkdirs() }
            val start = count(app, word)
            val beep = try { ToneGenerator(AudioManager.STREAM_MUSIC, 60) } catch (e: RuntimeException) { null }
            try {
                Thread.sleep(1_000) // let the wake word listener let go of the microphone
                for (i in 1..TAKES) {
                    main.post { progress("Say “${word.say}” after the beep ($i of $TAKES)") }
                    Thread.sleep(700)
                    beep?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                    Thread.sleep(150)
                    val pcm = capture() ?: break
                    writeWav(File(out, "${word.file}_${start + i}.wav"), pcm)
                    Thread.sleep(400)
                }
            } catch (e: InterruptedException) {
            } finally {
                beep?.release()
                running = false
                main.post {
                    MicGuard.release(this)
                    done()
                }
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun capture(): ByteArray? {
        val size = RATE * SECONDS * 2
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, size))
        } catch (e: Exception) { return null }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return null }
        val buf = ByteArray(size)
        try {
            r.startRecording()
            var got = 0
            while (got < size) {
                val n = r.read(buf, got, size - got)
                if (n <= 0) break
                got += n
            }
        } finally {
            try { r.stop() } catch (e: IllegalStateException) {}
            r.release()
        }
        return buf
    }

    private fun writeWav(f: File, pcm: ByteArray) {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        FileOutputStream(f).use { it.write(h.array()); it.write(pcm) }
    }
}
