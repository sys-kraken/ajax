package com.ajax.assistant

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model

/**
 * Always-on listening for "Ajax" and the flashlight words, fully offline. Runs as a foreground
 * service, so Android shows the green microphone dot the whole time, and keeps going with the
 * screen off. Steps aside whenever Ajax itself is listening or speaking.
 */
class WakeService : Service() {

    companion object {
        private const val CHANNEL = "listening"
        private const val NOTIFICATION_ID = 6
        private const val ACTION_STOP = "com.ajax.assistant.STOP_WAKE"
        private const val RATE = Spotter.RATE
        /**
         * Apps that refuse to record while anyone else is (Samsung's Voice Recorder says "Other
         * application already recording"): the microphone is left free while one is on screen.
         */
        private val recorderApps = setOf(
            "com.sec.android.app.voicenote", "com.sec.android.app.camera", "com.samsung.android.app.smartcapture",
            "com.google.android.apps.recorder", "com.google.android.GoogleCamera", "com.android.camera",
            "com.android.soundrecorder",
        )
        private const val FRONT_CHECK_MS = 1_000L

        /** Wait after Ajax stops talking, so the end of its own voice isn't heard as a command. */
        private const val RESUME_AFTER_MS = 800L

        @Volatile var running = false
            private set

        /** The last thing heard, for the settings screen (kept in memory only). */
        var onHeard: ((String) -> Unit)? = null

        fun enabled(ctx: Context) =
            ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).getBoolean(Prefs.WAKE, false)

        /** Starts listening; false if Android refused (only allowed while Ajax is on screen). */
        fun start(ctx: Context): Boolean {
            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
            if (!WakeModel.isReady(ctx)) return false
            return try {
                val i = Intent(ctx, WakeService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                true
            } catch (e: Exception) {
                false
            }
        }

        /** Shown when Android wouldn't let listening start in the background (after a restart). */
        fun askToResume(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Listening for Ajax", NotificationManager.IMPORTANCE_LOW))
            }
            val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            @Suppress("DEPRECATION")
            val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else Notification.Builder(ctx))
                .setSmallIcon(R.drawable.ic_stat_ajax)
                .setColor(0xFFA5C736.toInt())
                .setContentTitle("Tap to listen for “Ajax” again")
                .setContentText("Android stops it after a restart until Ajax is opened once.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try { nm.notify(NOTIFICATION_ID + 10, n) } catch (e: SecurityException) {}
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WakeService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var worker: Thread? = null
    @Volatile private var alive = false
    @Volatile private var paused = false
    private val sync = Runnable { syncPause() }
    private var recorderInFront = false
    private val frontCheck = object : Runnable {
        override fun run() {
            val screenOn = getSystemService(PowerManager::class.java)?.isInteractive == true
            val now = screenOn && Foreground.app(this@WakeService) in recorderApps
            if (now != recorderInFront) {
                recorderInFront = now
                syncPause()
            }
            main.postDelayed(this, FRONT_CHECK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Android 14+ only lets a microphone service start while Ajax is on screen (or just was).
        try {
            startInForeground()
        } catch (e: Exception) {
            askToResume(this)
            stopSelf()
            return
        }
        running = true
        // Held only while listening: no reason to keep the phone awake during a call or a recording.
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ajax:wake")
            ?.apply { setReferenceCounted(false) }
        Actions.watchTorch(this)
        MicGuard.onChange = { main.removeCallbacks(sync); syncPause() }
        MicGuard.expected = { recorderInFront }
        MicGuard.watch(this)
        alive = true
        syncPause()
        if (!paused) wakeLock?.acquire()
        main.post(frontCheck)
        worker = Thread({ listen() }, "ajax-wake").apply { start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit().putBoolean(Prefs.WAKE, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (!running) { super.onDestroy(); return }
        running = false
        alive = false
        worker?.interrupt()
        main.removeCallbacksAndMessages(null)
        MicGuard.onChange = null
        MicGuard.unwatch()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Listening for Ajax", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this))
            .setSmallIcon(R.drawable.ic_stat_ajax)
            .setColor(0xFFA5C736.toInt())
            .setLargeIcon(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notification_large))
            .setContentTitle("Ajax on service")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION_ID, n)
    }

    /** Pause while Ajax is listening or talking; resume a little after it goes quiet. */
    private fun syncPause() {
        if (MicGuard.busy || MicGuard.othersNeedMic || recorderInFront) {
            main.removeCallbacks(sync)
            setPaused(true)
        } else if (MicGuard.quietFor(RESUME_AFTER_MS)) {
            setPaused(false)
        } else {
            setPaused(true)
            main.postDelayed(sync, RESUME_AFTER_MS)
        }
    }

    private fun setPaused(p: Boolean) {
        paused = p
        val lock = wakeLock ?: return
        if (p && lock.isHeld) lock.release() else if (!p && !lock.isHeld && alive) lock.acquire()
    }

    /** The audio thread: owns the model, the recogniser and the microphone. */
    private fun listen() {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val model = try { Model(WakeModel.path(this).absolutePath) } catch (e: Exception) {
            // Damaged model files: forget them, so the settings screen offers the download again.
            WakeModel.discard(this)
            main.post { stopSelf() }
            return
        }
        val spotter = Spotter(model, WakeWords.phrases)
        val buf = ByteArray(Spotter.CHUNK_BYTES)
        var mic: AudioRecord? = null
        var nextTestCheck = 0L
        try {
            while (alive) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now >= nextTestCheck) {
                    nextTestCheck = now + 3_000
                    runRecordedTests(model)
                }
                if (paused) {
                    if (mic != null) {
                        closeMic(mic); mic = null
                        spotter.reset()
                    }
                    Thread.sleep(100)
                    continue
                }
                if (mic == null) {
                    mic = openMic() ?: run { Thread.sleep(1_000); null } ?: continue
                }
                val n = mic.read(buf, 0, buf.size)
                if (n <= 0) continue
                spotter.feed(buf, n)?.let { h -> main.post { heard(h) } }
            }
        } catch (e: InterruptedException) {
            // stopping
        } finally {
            mic?.let { closeMic(it) }
            spotter.close()
            model.close()
        }
    }

    /**
     * Development check: 16 kHz mono WAV files placed in files/wake-test (only adb can put them there)
     * go through the same detector, and what it heard is written to results.txt.
     */
    private fun runRecordedTests(model: Model) {
        val dir = java.io.File(filesDir, "wake-test")
        val wavs = dir.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name } ?: return
        if (wavs.isEmpty()) return
        val out = StringBuilder()
        Spotter(model, WakeWords.phrases).use { s ->
            for (f in wavs) {
                val audio = f.readBytes().let { it.copyOfRange(minOf(44, it.size), it.size) }
                val heard = mutableListOf<String>()
                var i = 0
                while (i < audio.size) {
                    val n = minOf(Spotter.CHUNK_BYTES, audio.size - i)
                    s.feed(audio.copyOfRange(i, i + n), n)?.let { heard += describe(it) }
                    i += n
                }
                val silence = ByteArray(Spotter.CHUNK_BYTES)
                repeat(20) { s.feed(silence, silence.size)?.let { heard += describe(it) } }
                out.append(f.name).append(" = ").append(heard.joinToString(" | "))
                    .append(" -> ").append(heard.map { t ->
                        val kind = WakeWords.match(t.substringBefore(" ("))
                        val conf = t.substringAfterLast("(").removeSuffix(")").toDouble()
                        if (kind == null) "none" else if (conf >= WakeWords.minConfidence(kind, false)) kind.name else "$kind-rejected"
                    }).appendLine()
                f.delete()
            }
        }
        java.io.File(dir, "results.txt").writeText(out.toString())
    }

    private fun describe(h: Spotter.Heard) = h.text + " (" + "%.2f".format(h.confidence) + ")"

    @Suppress("MissingPermission")
    private fun openMic(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE * 2))
        } catch (e: Exception) { return null }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return null }
        // Known before recording starts, so Ajax never mistakes its own recording for another app's.
        MicGuard.ownRecording(r.audioSessionId, open = true)
        try { r.startRecording() } catch (e: IllegalStateException) { closeMic(r); return null }
        return r
    }

    private fun closeMic(r: AudioRecord) {
        try { r.stop() } catch (e: IllegalStateException) {}
        r.release()
        MicGuard.ownRecording(r.audioSessionId, open = false)
    }

    private fun heard(h: Spotter.Heard) {
        onHeard?.invoke(h.text)
        // Speech from the phone's own speaker (a video saying "flashlight") must be very clear to count.
        if (paused) return
        val kind = WakeWords.match(h.text) ?: return
        val playing = getSystemService(android.media.AudioManager::class.java)?.isMusicActive == true
        if (h.confidence < WakeWords.minConfidence(kind, playing)) return
        when (kind) {
            WakeWords.Kind.TORCH_ON -> Actions.torch(this, true)
            WakeWords.Kind.TORCH_OFF -> Actions.torch(this, false)
            WakeWords.Kind.WAKE -> wake()
        }
    }

    private fun wake() {
        val screenOn = getSystemService(PowerManager::class.java)?.isInteractive == true
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (screenOn && !locked && AjaxInteractionService.listen()) return
        HandsFree.start(this, locked)
    }
}
