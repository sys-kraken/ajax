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
        private const val ACTION_REPOST = "com.ajax.assistant.REPOST_WAKE"
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

        /** The words with a trained detector loaded, for the settings screen; null while not listening. */
        @Volatile var detectors: List<WakeWords.Word>? = null
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
        CallVoice.watch(this) { MicGuard.refresh(); syncPause() }
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
        if (intent?.action == ACTION_REPOST && running) {
            // Swiped away: Android 14+ lets you dismiss even a running service's notification. Put it
            // straight back, so you can always see Ajax is listening and stop it from there.
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
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
        CallVoice.unwatch()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Listening for Ajax", NotificationManager.IMPORTANCE_LOW))
        }
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION_ID, n)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        val repost = PendingIntent.getService(
            this, 3, Intent(this, WakeService::class.java).setAction(ACTION_REPOST), PendingIntent.FLAG_IMMUTABLE,
        )
        return (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this))
            .setSmallIcon(R.drawable.ic_stat_ajax)
            .setColor(0xFFA5C736.toInt())
            .setLargeIcon(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notification_large))
            .setContentTitle("Ajax on service")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .setDeleteIntent(repost)
            .build()
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
        // The voice model, for the voice lock; without it phrases carry no voice fingerprint.
        speakers = if (VoiceModel.isReady(this)) {
            try { org.vosk.SpeakerModel(VoiceModel.path(this).absolutePath) } catch (e: Exception) { VoiceModel.discard(this); null }
        } else null
        val spotter = Spotter(model, WakeWords.phrases, speakers)
        // The detectors trained on each word; a keyword needs both them and the recogniser to agree.
        // Without them the recogniser decides alone.
        val detector = AjaxDetector.open(this)
        val ears = WakeListener(spotter, detector)
        main.post { usingDetector = detector != null }
        detectors = WakeWords.Word.values().filter { detector?.has(it) == true }
        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        val audio = getSystemService(android.media.AudioManager::class.java)
        var nextQuietCheck = 0L
        var resting = false   // every keyword is ignored while audio plays: nothing listens
        val callSpotter = Spotter(model, CallVoice.phrases) // only while the phone rings
        var forCall = false
        val buf = ByteArray(Spotter.CHUNK_BYTES)
        var mic: AudioRecord? = null
        var nextTestCheck = 0L
        try {
            while (alive) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now >= nextTestCheck) {
                    nextTestCheck = now + 3_000
                    // Your recordings changed (or the voice model just arrived): make the voice print.
                    try {
                        speakers?.let { sp -> if (VoiceLock.needsPrint(this)) VoiceLock.makePrint(this, model, sp) }
                    } catch (e: Exception) {
                        android.util.Log.w("AjaxWake", "voice print failed", e)
                    }
                    // The development checks must never take listening down with them.
                    try {
                        runRecordedTests(model)
                    } catch (e: Exception) {
                        android.util.Log.w("AjaxWake", "recorded test failed", e)
                        java.io.File(filesDir, "wake-test").deleteRecursively()
                    }
                }
                if (paused) {
                    if (mic != null) {
                        closeMic(mic); mic = null
                        ears.reset()
                    }
                    Thread.sleep(100)
                    continue
                }
                // (Echo cancellation was tried for audio playing on the phone: on Samsung it only works
                // during calls and made the phone's own sound louder, so the normal microphone stays.)
                if (mic == null) {
                    mic = openMic() ?: run { Thread.sleep(1_000); null } ?: continue
                }
                val n = mic.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (CallVoice.listening != forCall) {
                    forCall = CallVoice.listening
                    ears.reset(); callSpotter.reset()
                }
                // The "ignore during audio" settings also spare the work: a word that is ignored
                // gets no detector, and with every keyword ignored nothing analyses the sound.
                if (now >= nextQuietCheck) {
                    nextQuietCheck = now + 1_000
                    val playing = audio?.isMusicActive == true
                    val quietTorch = prefs.getBoolean(Prefs.QUIET_TORCH, false)
                    val quietWake = prefs.getBoolean(Prefs.QUIET_WAKE, false)
                    detector?.let { WakeWords.wanted(playing, quietTorch, quietWake).copyInto(it.active) }
                    val rest = WakeWords.nothingToHear(playing, quietTorch, quietWake)
                    if (rest != resting) { resting = rest; ears.reset() }
                }
                if (resting && !forCall) continue
                val result = if (forCall) callSpotter.feed(buf, n) else ears.feed(buf, n)
                result?.let { h ->
                    val threshold = detector?.threshold ?: 1f
                    main.post { heard(h, threshold) }
                }
            }
        } catch (e: InterruptedException) {
            // stopping
        } finally {
            mic?.let { closeMic(it) }
            spotter.close()
            callSpotter.close()
            detector?.close()
            speakers?.close()
            speakers = null
            detectors = null
            model.close()
        }
    }

    /**
     * Development check: 16 kHz mono WAV files placed in files/wake-test (only adb can put them there)
     * go through the same detector, and what it heard is written to results.txt.
     */
    /**
     * Development check: with files/aec-probe present, records 3 s through each microphone setting
     * and writes the level of each to files/aec-result.txt (play audio on the phone meanwhile).
     */
    private fun runEchoProbe() {
        val trigger = java.io.File(filesDir, "aec-probe")
        if (!trigger.exists()) return
        trigger.delete()
        val out = StringBuilder()
        // The same sound for both measurements: Ajax plays files/aec-play.wav itself, if present.
        val clip = java.io.File(filesDir, "aec-play.wav")
        val player = if (clip.exists()) android.media.MediaPlayer().apply {
            setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            setDataSource(clip.absolutePath); isLooping = true; prepare(); start()
        } else null
        Thread.sleep(800)
        for (cancel in listOf(false, true)) {
            val r = openMic(cancel) ?: continue
            val buf = ByteArray(Spotter.CHUNK_BYTES)
            var sum = 0.0; var count = 0
            val end = android.os.SystemClock.uptimeMillis() + 3_000
            while (android.os.SystemClock.uptimeMillis() < end) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) { val l = Spotter.level(buf, n); sum += l * l; count++ }
            }
            closeMic(r)
            out.append(if (cancel) "echo cancelled" else "normal").append(": level ")
                .append("%.0f".format(Math.sqrt(sum / maxOf(1, count)))).append(", effects ")
                .append(android.media.audiofx.AcousticEchoCanceler.isAvailable()).appendLine()
        }
        player?.run { stop(); release() }
        out.append("music playing: ").append(getSystemService(android.media.AudioManager::class.java)?.isMusicActive).appendLine()
        java.io.File(filesDir, "aec-result.txt").writeText(out.toString())
    }

    private fun runRecordedTests(model: Model) {
        runEchoProbe()
        val dir = java.io.File(filesDir, "wake-test")
        val wavs = dir.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name } ?: return
        if (wavs.isEmpty()) return
        val out = StringBuilder()
        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        val quietTorch = prefs.getBoolean(Prefs.QUIET_TORCH, false)
        val quietWake = prefs.getBoolean(Prefs.QUIET_WAKE, false)
        val agree = WakeWords.agreement(prefs.getInt(Prefs.AGREEMENT, WakeWords.DEFAULT_AGREEMENT))
        Spotter(model, WakeWords.phrases, speakers).use { s ->
            val d = AjaxDetector.open(this)
            try {
                val ears = WakeListener(s, d)
                for (f in wavs) {
                    val audio = f.readBytes().let { it.copyOfRange(minOf(44, it.size), it.size) }
                    val heard = mutableListOf<String>()
                    ears.reset()
                    val nanos0 = ears.detectorNanos
                    val cpu0 = android.os.SystemClock.currentThreadTimeMillis()
                    // Each phrase as the listener would judge it: in a quiet room, and with audio playing.
                    val note = { h: Spotter.Heard ->
                        val quiet = WakeWords.decide(h.text, h.confidence, false, h.scores, quietTorch, quietWake, agree)
                        val playing = WakeWords.decide(h.text, h.confidence, true, h.scores, quietTorch, quietWake, agree)
                        heard += describe(h) + " quiet=" + (quiet?.name ?: "none") + " playing=" + (playing?.name ?: "none")
                    }
                    var i = 0
                    while (i < audio.size) {
                        val n = minOf(Spotter.CHUNK_BYTES, audio.size - i)
                        ears.feed(audio.copyOfRange(i, i + n), n)?.let(note)
                        i += n
                    }
                    val silence = ByteArray(Spotter.CHUNK_BYTES)
                    repeat(20) { ears.feed(silence, silence.size)?.let(note) }
                    val seconds = audio.size / (RATE * 2.0)
                    out.append(f.name).append(" = ").append(heard.joinToString(" | "))
                        .append(" # ").append("%.1f".format(seconds)).append(" s audio, detectors ")
                        .append((ears.detectorNanos - nanos0) / 1_000_000).append(" ms, all ")
                        .append(android.os.SystemClock.currentThreadTimeMillis() - cpu0).append(" ms cpu")
                        .appendLine()
                    f.delete()
                }
            } finally {
                d?.close()
            }
        }
        java.io.File(dir, "results.txt").writeText(out.toString())
    }

    private fun scoreText(scores: FloatArray?): String? = scores?.let { s ->
        WakeWords.Word.values().filter { s[it.ordinal] >= 0f }
            .joinToString(", ") { it.say + " " + "%.2f".format(s[it.ordinal]) }.takeIf { it.isNotEmpty() }
    }

    private fun describe(h: Spotter.Heard): String {
        val sim = VoiceLock.similarity(this, h.voice)
        val voice = if (sim == null) "" else " voice " + "%.2f".format(sim) + "/" + h.voiceFrames
        return h.text + " (" + "%.2f".format(h.confidence) + ")" + voice + " [" + (scoreText(h.scores) ?: "no detector") + "]"
    }

    @Suppress("MissingPermission")
    private val effects = HashMap<Int, List<android.media.audiofx.AudioEffect>>()

    private fun openMic(echoCancel: Boolean = false): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // The communication source is the one phones run their call echo canceller on.
        val source = if (echoCancel) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val r = try {
            AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE * 2))
        } catch (e: Exception) { return null }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return null }
        if (echoCancel) {
            val fx = mutableListOf<android.media.audiofx.AudioEffect>()
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                android.media.audiofx.AcousticEchoCanceler.create(r.audioSessionId)?.let { it.enabled = true; fx += it }
            }
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                android.media.audiofx.NoiseSuppressor.create(r.audioSessionId)?.let { it.enabled = true; fx += it }
            }
            effects[r.audioSessionId] = fx
        }
        // Known before recording starts, so Ajax never mistakes its own recording for another app's.
        MicGuard.ownRecording(r.audioSessionId, open = true)
        try { r.startRecording() } catch (e: IllegalStateException) { closeMic(r); return null }
        return r
    }

    private fun closeMic(r: AudioRecord) {
        effects.remove(r.audioSessionId)?.forEach { it.release() }
        try { r.stop() } catch (e: IllegalStateException) {}
        r.release()
        MicGuard.ownRecording(r.audioSessionId, open = false)
    }

    /** True when the trained detectors are running (their scores are shown in settings). */
    private var usingDetector = false

    /** Loaded on the audio thread when the voice model is downloaded. */
    @Volatile private var speakers: org.vosk.SpeakerModel? = null

    private fun heard(h: Spotter.Heard, threshold: Float = 1f) {
        val sim = VoiceLock.similarity(this, h.voice)
        val notes = listOfNotNull(scoreText(h.scores), sim?.let { "your voice ${"%.2f".format(it)}" })
        val shown = WakeWords.shown(h.text)
        onHeard?.invoke(if (notes.isEmpty()) shown else "$shown  (${notes.joinToString(", ")})")
        if (paused) return
        if (CallVoice.listening) { CallVoice.heard(this, h.text); return }
        val playing = getSystemService(android.media.AudioManager::class.java)?.isMusicActive == true
        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        // The keyword recogniser and the word's trained detector must agree (see WakeWords.decide).
        val kind = WakeWords.decide(
            h.text, h.confidence, playing, h.scores,
            quietTorch = prefs.getBoolean(Prefs.QUIET_TORCH, false),
            quietWake = prefs.getBoolean(Prefs.QUIET_WAKE, false),
            agree = WakeWords.agreement(prefs.getInt(Prefs.AGREEMENT, WakeWords.DEFAULT_AGREEMENT)),
        )
        // Voice lock: keywords count only in your voice (a video or song saying them does nothing).
        val allowed = kind == null || VoiceLock.accepts(this, h)
        logScore(h, threshold, playing, if (kind == null) "-" else if (allowed) kind.name else "not your voice")
        if (!allowed) return
        when (kind) {
            WakeWords.Kind.TORCH_ON -> Actions.torch(this, true)
            WakeWords.Kind.TORCH_OFF -> Actions.torch(this, false)
            WakeWords.Kind.WAKE -> wake()
            null -> {}
        }
    }

    /**
     * One line per phrase: time, what the keyword recogniser wrote (a keyword or [unk], never other
     * words), its confidence, the "Ajax" detector's score, the phrase length, that detector's own
     * threshold, how much the voice sounds like yours, whether the phone was playing audio, the
     * lumos and flashlight detectors' scores (-1 without one), and what Ajax did. No audio. Kept in
     * Ajax's private storage, last 2,000 lines, so false triggers can be traced afterwards.
     */
    private fun logScore(h: Spotter.Heard, threshold: Float, playing: Boolean, did: String) {
        try {
            val f = java.io.File(filesDir, "wake-scores.tsv")
            val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val score = { w: WakeWords.Word -> "%.3f".format(h.scores?.get(w.ordinal) ?: -1f) }
            f.appendText(
                "$time\t${h.text}\t${"%.2f".format(h.confidence)}\t${"%.3f".format(h.wakeScore)}\t${h.chunks}\t$threshold" +
                    "\t${VoiceLock.similarity(this, h.voice)?.let { "%.2f".format(it) } ?: "-"}\t${h.voiceFrames}" +
                    "\t${if (playing) "playing" else "quiet"}\t${score(WakeWords.Word.LUMOS)}\t${score(WakeWords.Word.FLASHLIGHT)}\t$did\n",
            )
            if (f.length() > 200_000) f.writeText(f.readLines().takeLast(2_000).joinToString("\n", postfix = "\n"))
        } catch (e: java.io.IOException) {}
    }

    private fun wake() {
        val screenOn = getSystemService(PowerManager::class.java)?.isInteractive == true
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (screenOn && !locked && AjaxInteractionService.listen()) return
        HandsFree.start(this, locked)
    }
}
