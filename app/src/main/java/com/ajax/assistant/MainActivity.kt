package com.ajax.assistant

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ajax.assistant.Ui.dp

/**
 * Ajax's settings, opened from Settings > Apps > Ajax. Grouped by what each setting is about:
 * setup, wake words, your voice, calls, speech, understanding, system, advanced, about.
 */
class MainActivity : Activity() {

    private class Step(
        val label: String,
        val hint: String,
        val granted: () -> Boolean,
        val runtime: Array<String>? = null,
        val intent: (() -> Intent)? = null,
    )

    private val steps: List<Step> by lazy {
        fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val restricted = "If Android says “Restricted setting”: Settings > Apps > Ajax > three-dot menu > Allow restricted settings, then try again."
        listOf(
            Step("Microphone", "Needed to hear you.", { has(Manifest.permission.RECORD_AUDIO) },
                runtime = arrayOf(Manifest.permission.RECORD_AUDIO)),
            Step("Side key (default assistant)",
                "Pick Ajax under Default apps > Digital assistant app. Then hold the side key (power button). " +
                    "Samsung: also set Settings > Advanced features > Side button > Press and hold to Digital assistant, not Bixby. " +
                    "Pixel: Settings > System > Gestures > Press and hold power button > Digital assistant.",
                { isDefaultAssistant() },
                intent = { Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS) }),
            Step("Contacts and calling", "Needed for “call …” and “text …”.",
                { has(Manifest.permission.READ_CONTACTS) && has(Manifest.permission.CALL_PHONE) },
                runtime = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)),
            Step("Texting", "Needed for “text …”. Ajax always asks before sending. $restricted",
                { has(Manifest.permission.SEND_SMS) },
                runtime = arrayOf(Manifest.permission.SEND_SMS)),
            Step("Accessibility", "Needed for Back, Home, scroll, screenshot, lock, and Wi-Fi/Bluetooth/data/airplane switches. Find Ajax in the list and turn it on. $restricted",
                { AjaxAccessibilityService.instance != null || Access.onDemand(this) },
                intent = { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) }),
            Step("Change brightness", "Needed for “brightness 50 percent”.",
                { Settings.System.canWrite(this) },
                intent = { Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")) }),
            Step("Do Not Disturb access", "Needed for Do Not Disturb and silent mode.",
                { getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted },
                intent = { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }),
            Step("Notification access", "Needed for “read my notifications”. $restricted",
                {
                    Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
                        ?.contains(packageName) == true
                },
                intent = { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }),
        )
    }

    private lateinit var setupRow: Ui.Row
    private lateinit var stepRows: LinearLayout
    private lateinit var engine: Ui.Row
    private lateinit var accessMode: Ui.Row
    private lateinit var wakeRow: Ui.Row
    private lateinit var wakeSwitch: android.widget.Switch
    private lateinit var heardRow: Ui.Row
    private lateinit var batteryRow: Ui.Row
    private lateinit var callsRow: Ui.Row
    private lateinit var teachRow: Ui.Row
    private lateinit var lockRow: Ui.Row
    private lateinit var quietTorchRow: Ui.Row
    private lateinit var quietWakeRow: Ui.Row
    private val wordRows = LinkedHashMap<WakeWords.Word, Ui.Row>()
    private lateinit var lockSwitch: android.widget.Switch
    private lateinit var callsSwitch: android.widget.Switch
    private lateinit var hint: TextView
    private lateinit var grant: Ui.Row
    private lateinit var agreementRow: Ui.Row
    private lateinit var detectorsRow: Ui.Row
    private lateinit var logRow: Ui.Row
    /** Sections that only matter while Ajax listens for its wake words. */
    private val wakeOnly = mutableListOf<View>()
    private lateinit var modelStatus: Ui.Row
    private lateinit var modelButton: Ui.Row
    private lateinit var answerStatus: Ui.Row
    private lateinit var answerButton: Ui.Row
    private val main = Handler(Looper.getMainLooper())
    private var openSettingsMode = false
    private var showDetails = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Actions.watchTorch(this)
        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(24))
        }

        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_back)
                contentDescription = "Back"
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { finish() }
            })
            addView(Ui.text(this@MainActivity, 22f, bold = true).apply {
                text = "Settings"
                setPadding(dp(8), 0, 0, 0)
            })
        })

        // The wordmark: block letters, drawn on a grid (see LogoView for why it isn't text).
        root.addView(LogoView(this, LOGO, LOGO_GREEN).apply {
            contentDescription = "Ajax"
            setPadding(0, dp(12), 0, dp(8))
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(Ui.text(this, 14f, Ui.MUTED).apply {
            text = "Hold the side key, or say “Ajax”."
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(12))
        })

        /** A titled group of rows; returns the whole section so it can be hidden as one. */
        fun section(title: String, build: LinearLayout.() -> Unit): View = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.header(this@MainActivity, title))
            addView(Ui.group(this@MainActivity).apply(build))
            root.addView(this, Ui.below(this@MainActivity, 4))
        }

        fun toggle(group: LinearLayout, label: String, sub: String, key: String, default: Boolean, changed: () -> Unit = {}): Ui.Row {
            val switch = Ui.switch(this).apply {
                isChecked = prefs.getBoolean(key, default)
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply(); changed() }
            }
            return Ui.row(this, label, sub, end = switch).also { r ->
                r.onClick { switch.toggle() }
                Ui.addRow(group, r.view)
            }
        }

        section("Setup") {
            setupRow = Ui.row(this@MainActivity, "Permissions").also {
                it.onClick { showDetails = !showDetails; refreshSteps() }
                Ui.addRow(this, it.view)
            }
            stepRows = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(stepRows)
            hint = Ui.text(this@MainActivity, 14f, Ui.MUTED).apply { setPadding(dp(20), dp(12), dp(20), 0) }.also { addView(it) }
            grant = Ui.row(this@MainActivity, "").also {
                it.title.setTextColor(Ui.ACCENT)
                it.onClick { onGrantClicked() }
                addView(it.view)
            }
        }

        section("Wake words") {
            wakeSwitch = Ui.switch(this@MainActivity).apply {
                isChecked = WakeService.enabled(this@MainActivity)
                setOnCheckedChangeListener { _, on -> setWake(on) }
            }
            wakeRow = Ui.row(this@MainActivity, "Listen for “Ajax”", end = wakeSwitch).also {
                it.onClick { wakeSwitch.toggle() }
                Ui.addRow(this, it.view)
            }
            heardRow = Ui.row(this@MainActivity, "Last heard", "Say “Ajax” or “lumos” to test").also { Ui.addRow(this, it.view) }
            quietTorchRow = toggle(this, "Ignore flashlight words during audio", "", Prefs.QUIET_TORCH, false) { showWake() }
            quietWakeRow = toggle(this, "Ignore “Ajax” during audio", "", Prefs.QUIET_WAKE, false) { showWake() }
        }

        wakeOnly += section("Your voice") {
            teachRow = Ui.row(this@MainActivity, "Teach Ajax your voice").also {
                it.onClick { teachVoice() }
                Ui.addRow(this, it.view)
            }
            for (w in listOf(WakeWords.Word.LUMOS, WakeWords.Word.FLASHLIGHT)) {
                wordRows[w] = Ui.row(this@MainActivity, "Teach Ajax “${w.say}”").also {
                    it.onClick { teachVoice(w) }
                    Ui.addRow(this, it.view)
                }
            }
            lockSwitch = Ui.switch(this@MainActivity).apply {
                isChecked = prefs.getBoolean(Prefs.VOICE_LOCK, false)
                setOnCheckedChangeListener { _, on -> setVoiceLock(on) }
            }
            lockRow = Ui.row(this@MainActivity, "Voice lock", end = lockSwitch).also {
                it.onClick { lockSwitch.toggle() }
                Ui.addRow(this, it.view)
            }
        }

        wakeOnly += section("Calls") {
            callsSwitch = Ui.switch(this@MainActivity).apply {
                isChecked = prefs.getBoolean(Prefs.CALLS, false)
                setOnCheckedChangeListener { _, on -> setCalls(on) }
            }
            callsRow = Ui.row(this@MainActivity, "Announce callers", end = callsSwitch).also {
                it.onClick { callsSwitch.toggle() }
                Ui.addRow(this, it.view)
            }
        }

        section("Speech") {
            toggle(this, "Speak replies", "", Prefs.SPEAK, true)
            toggle(this, "Online speech recognition", "Audio may go to Google", Prefs.ONLINE, false)
            engine = Ui.row(this@MainActivity, "Speech engine").also { Ui.addRow(this, it.view) }
            Ui.addRow(this, Ui.row(this@MainActivity, "Voice settings", "The phone's voice input page").also {
                it.onClick {
                    try { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                    catch (e: ActivityNotFoundException) { say("This phone has no voice settings page.") }
                }
            }.view)
        }

        section("Understanding") {
            modelStatus = Ui.row(this@MainActivity, "Smart understanding").also { Ui.addRow(this, it.view) }
            modelButton = Ui.row(this@MainActivity, "").also {
                it.title.setTextColor(Ui.ACCENT)
                it.onClick { onModelClicked() }
                Ui.addRow(this, it.view)
            }
            answerStatus = Ui.row(this@MainActivity, "Offline answers").also { Ui.addRow(this, it.view) }
            answerButton = Ui.row(this@MainActivity, "").also {
                it.title.setTextColor(Ui.ACCENT)
                it.onClick { onAnswerModelClicked() }
                Ui.addRow(this, it.view)
            }
        }

        section("System") {
            batteryRow = Ui.row(this@MainActivity, "Run in the background").also {
                it.onClick { allowBackground() }
                Ui.addRow(this, it.view)
            }
            accessMode = Ui.row(this@MainActivity, "Accessibility").also { Ui.addRow(this, it.view) }
        }

        wakeOnly += section("Advanced") {
            agreementRow = Ui.row(this@MainActivity, "Detector agreement").also {
                it.onClick { chooseAgreement() }
                Ui.addRow(this, it.view)
            }
            detectorsRow = Ui.row(this@MainActivity, "Trained detectors").also { Ui.addRow(this, it.view) }
            logRow = Ui.row(this@MainActivity, "Wake log").also {
                it.onClick { clearLog() }
                Ui.addRow(this, it.view)
            }
        }

        section("About") {
            val info = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            val build = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            val debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
            Ui.addRow(this, Ui.row(this@MainActivity, "Version", info.versionName.orEmpty()).view)
            Ui.addRow(this, Ui.row(this@MainActivity, "Build", "$build, ${if (debug) "debug" else "release"}").view)
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BACKGROUND)
            addView(root)
            clipToPadding = false
            // Take focus first, or the rebuilt setup rows make the page open scrolled down.
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            // Android 15 draws behind the system bars: keep the content clear of them.
            @Suppress("DEPRECATION")
            setOnApplyWindowInsetsListener { v, i ->
                v.setPadding(i.systemWindowInsetLeft, i.systemWindowInsetTop, i.systemWindowInsetRight, i.systemWindowInsetBottom)
                i
            }
        })
        if (savedInstanceState == null) handleAssist(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAssist(intent)
    }

    /**
     * Some phones send the side key here as ACTION_ASSIST when Ajax isn't the digital assistant.
     * Talking happens in the overlay, which only the digital assistant gets: open it, or say how.
     */
    private fun handleAssist(i: Intent?) {
        if (i?.action != Intent.ACTION_ASSIST) return
        if (!AjaxInteractionService.listen()) say("Set Ajax as the digital assistant under Setup to talk to it.")
    }

    private fun say(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        Access.settle(this)
        WakeModel.listener = { showWake() }
        VoiceModel.listener = { showWake() }
        WakeService.onHeard = { heardRow.sub("“$it”") }
        if (WakeService.enabled(this) && !WakeService.running) WakeService.start(this)
        main.postDelayed({ showWake() }, 300)
        ModelManager.listener = ::showModel
        showModel(ModelManager.state(this))
        AnswerModel.listener = ::showAnswerModel
        showAnswerModel(AnswerModel.state(this))
        refreshSteps()
    }

    private fun onModelClicked() {
        when (ModelManager.state(this)) {
            is ModelManager.State.Ready -> ModelManager.delete(this)
            is ModelManager.State.Downloading -> {}
            else -> ModelManager.download(this)
        }
    }

    private fun onAnswerModelClicked() {
        when (AnswerModel.state(this)) {
            is ModelManager.State.Ready -> dialog().setTitle("Delete the answer model?")
                .setMessage("Ajax goes back to a web search for questions. You can download it again later.")
                .setPositiveButton("Delete") { _, _ -> AnswerModel.delete(this); Answers.forget() }
                .setNegativeButton("Cancel", null)
                .show()
            is ModelManager.State.Downloading -> {}
            else -> AnswerModel.download(this)
        }
    }

    private fun showAnswerModel(s: ModelManager.State) {
        val mb = { b: Long -> b / 1_000_000 }
        if (!LlamaNative.available) {
            answerStatus.sub("Not supported on this phone (needs a 64-bit ARM processor).")
            answerButton.view.visibility = View.GONE
            return
        }
        answerButton.view.isEnabled = s !is ModelManager.State.Downloading
        when (s) {
            is ModelManager.State.Ready -> {
                answerStatus.sub("On. Ajax answers general questions itself, with no internet. It can be wrong, and it knows nothing recent.")
                answerButton.title.text = "Delete model (free ${mb(AnswerModel.SIZE_BYTES)} MB)"
            }
            is ModelManager.State.Downloading -> {
                answerStatus.sub("Downloading… ${mb(s.done)} of ${mb(s.total)} MB. You can leave this screen.")
                answerButton.title.text = "Downloading…"
            }
            is ModelManager.State.Failed -> {
                answerStatus.sub(s.message)
                answerButton.title.text = "Retry download"
            }
            is ModelManager.State.Missing -> {
                answerStatus.sub(
                    "Off. Questions open a web search. Downloading a ${mb(AnswerModel.SIZE_BYTES)} MB model once (use Wi-Fi) " +
                        "lets Ajax answer them itself, offline.",
                )
                answerButton.title.text = "Download model"
            }
        }
    }

    private fun showModel(s: ModelManager.State) {
        val mb = { b: Long -> b / 1_000_000 }
        if (!LlamaNative.available) {
            modelStatus.sub("Not supported on this phone (needs a 64-bit ARM processor).")
            modelButton.view.visibility = View.GONE
            return
        }
        modelButton.view.isEnabled = s !is ModelManager.State.Downloading
        when (s) {
            is ModelManager.State.Ready -> {
                modelStatus.sub("On. Understands loose phrasing like “could you flip the torch on”. Runs fully on the phone.")
                modelButton.title.text = "Delete model (free ${mb(ModelManager.SIZE_BYTES)} MB)"
            }
            is ModelManager.State.Downloading -> {
                modelStatus.sub("Downloading… ${mb(s.done)} of ${mb(s.total)} MB. You can leave this screen.")
                modelButton.title.text = "Downloading…"
            }
            is ModelManager.State.Failed -> {
                modelStatus.sub(s.message)
                modelButton.title.text = "Retry download"
            }
            is ModelManager.State.Missing -> {
                modelStatus.sub(
                    "Downloads a ${mb(ModelManager.SIZE_BYTES)} MB language model once (use Wi-Fi). " +
                        "After that it runs offline; your words never leave the phone.",
                )
                modelButton.title.text = "Download model"
            }
        }
    }

    override fun onPause() {
        ModelManager.listener = null
        AnswerModel.listener = null
        WakeModel.listener = null
        VoiceModel.listener = null
        WakeService.onHeard = null
        super.onPause()
    }

    private fun setWake(on: Boolean) {
        getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit().putBoolean(Prefs.WAKE, on).apply()
        if (!on) {
            WakeService.stop(this)
            main.postDelayed({ showWake() }, 300)
            return
        }
        val missing = listOfNotNull(
            Manifest.permission.RECORD_AUDIO.takeIf { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED },
            Manifest.permission.POST_NOTIFICATIONS.takeIf {
                Build.VERSION.SDK_INT >= 33 && checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            },
        )
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 2)
        WakeModel.download(this) { ok ->
            if (ok && WakeService.enabled(this)) WakeService.start(this)
            main.postDelayed({ showWake() }, 500)
        }
        showWake()
    }

    private fun showWake() {
        if (isFinishing || isDestroyed) return
        val on = WakeService.enabled(this)
        if (wakeSwitch.isChecked != on) wakeSwitch.isChecked = on
        val mb = { b: Long -> b / 1_000_000 }
        val s = WakeModel.state(this)
        wakeRow.sub(
            when {
                s is ModelManager.State.Downloading -> "Downloading the speech model… ${mb(s.done)} of ${mb(s.total)} MB"
                s is ModelManager.State.Failed -> s.message
                !on -> "Off. Turning it on downloads a ${mb(WakeModel.sizeBytes)} MB speech model once."
                WakeService.running -> "On, even with the screen off. The green dot stays on. Alerts you when another app uses the microphone."
                else -> "Starting…"
            },
        )
        heardRow.view.visibility = if (on && WakeService.running) View.VISIBLE else View.GONE
        for (v in wakeOnly) v.visibility = if (on) View.VISIBLE else View.GONE
        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        val quietTorch = prefs.getBoolean(Prefs.QUIET_TORCH, false)
        val quietWake = prefs.getBoolean(Prefs.QUIET_WAKE, false)
        quietTorchRow.view.visibility = if (on) View.VISIBLE else View.GONE
        quietWakeRow.view.visibility = if (on) View.VISIBLE else View.GONE
        // With both on, nothing analyses sound while audio plays; with one, only that word's detector rests.
        val rests = if (quietTorch && quietWake) " Ajax stops analysing sound while audio plays, which saves battery." else ""
        quietTorchRow.sub(
            if (quietTorch) "On. While music or a video plays on the phone, lumos, nox and flashlight do nothing and their detectors rest.$rests"
            else "Off. Lumos, nox and flashlight also work while music or a video plays on the phone.",
        )
        quietWakeRow.sub(
            if (quietWake) "On. While music or a video plays on the phone, saying Ajax does nothing and its detector rests. Use the side key.$rests"
            else "Off. Saying Ajax also works while music or a video plays on the phone.",
        )
        val (name, score) = WakeWords.AGREEMENT.getOrElse(prefs.getInt(Prefs.AGREEMENT, WakeWords.DEFAULT_AGREEMENT)) { WakeWords.AGREEMENT[WakeWords.DEFAULT_AGREEMENT] }
        agreementRow.sub("$name. A keyword counts when its trained detector scores $score or more and the recogniser hears the same word.")
        val loaded = WakeService.detectors
        detectorsRow.sub(
            when {
                loaded == null -> "Not listening"
                loaded.isEmpty() -> "None loaded. The recogniser decides alone."
                else -> loaded.joinToString(", ") { it.say } + " loaded. Others rest on the recogniser alone."
                    .let { if (loaded.size == WakeWords.Word.values().size) " loaded" else it }
            },
        )
        val lines = try { logFile().takeIf { it.exists() }?.useLines { it.count() } ?: 0 } catch (e: java.io.IOException) { 0 }
        logRow.sub(
            if (lines == 0) "Empty. Each phrase heard is logged as a keyword or unknown, with scores, never audio."
            else "$lines phrases logged as keyword or unknown, with scores, never audio. Tap to clear.",
        )
        for ((w, row) in wordRows) {
            if (teaching != w) {
                val n = VoiceSamples.count(this, w)
                row.sub(
                    if (n == 0) "Say “${w.say}” ${VoiceSamples.TAKES} times, so Ajax can tell your ${w.say} from other sounds."
                    else "$n recordings saved. Tap to record more.",
                )
            }
        }
        val vm = VoiceModel.state(this)
        lockRow.sub(
            when {
                !lockSwitch.isChecked -> "Off. When on, only your voice can say Ajax, lumos, nox or flashlight."
                vm is ModelManager.State.Downloading -> "Downloading the voice model… ${mb(vm.done)} of ${mb(vm.total)} MB"
                vm is ModelManager.State.Failed -> vm.message
                VoiceSamples.count(this) == 0 -> "Record your voice first: tap Teach Ajax your voice."
                VoiceLock.needsPrint(this) -> "Learning your voice from your recordings…"
                else -> "On. Videos, music and other people can't wake Ajax. Learned from ${VoiceLock.printedFrom(this)} recordings."
            },
            if (lockSwitch.isChecked && VoiceSamples.count(this) == 0) Ui.ACCENT else Ui.MUTED,
        )
        if (teaching != WakeWords.Word.AJAX) {
            val n = VoiceSamples.count(this)
            teachRow.sub(if (n == 0) "Say \u201cAjax\u201d ${VoiceSamples.TAKES} times so it learns your voice." else "$n recordings saved. Tap to record more.")
        }
        callsRow.sub(
            when {
                !callsSwitch.isChecked -> "Off"
                CallVoice.enabled(this) -> "Says who is calling, then answer or decline by voice."
                else -> "Needs phone and call log permission. Tap to allow."
            },
        )
        val pm = getSystemService(android.os.PowerManager::class.java)
        val free = pm?.isIgnoringBatteryOptimizations(packageName) == true
        batteryRow.view.visibility = if (on) View.VISIBLE else View.GONE
        batteryRow.sub(if (free) "Allowed" else "Tap so the phone doesn't stop Ajax listening", if (free) Ui.MUTED else Ui.ACCENT)
    }

    private fun logFile() = java.io.File(filesDir, "wake-scores.tsv")

    private fun dialog() = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun chooseAgreement() {
        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        val labels = WakeWords.AGREEMENT.map { (name, score) ->
            when (name) {
                "Strict" -> "Strict ($score): fewest false triggers, may miss quiet or far speech"
                "Relaxed" -> "Relaxed ($score): catches more quiet speech, more false triggers"
                else -> "$name ($score): recommended"
            }
        }.toTypedArray()
        dialog().setTitle("Detector agreement")
            .setSingleChoiceItems(labels, prefs.getInt(Prefs.AGREEMENT, WakeWords.DEFAULT_AGREEMENT)) { d, which ->
                prefs.edit().putInt(Prefs.AGREEMENT, which).apply()
                d.dismiss()
                showWake()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearLog() {
        if (!logFile().exists()) return
        dialog().setTitle("Clear the wake log?")
            .setMessage("The log is what false triggers are traced with. This cannot be undone.")
            .setPositiveButton("Clear") { _, _ -> logFile().delete(); showWake() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The word being recorded right now, if any. */
    private var teaching: WakeWords.Word? = null

    private fun teachVoice(word: WakeWords.Word = WakeWords.Word.AJAX) {
        if (teaching != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        teaching = word
        val row = wordRows[word] ?: teachRow
        VoiceSamples.record(this, word, progress = { row.sub(it, Ui.ACCENT) }) {
            teaching = null
            showWake()
        }
    }

    private fun setVoiceLock(on: Boolean) {
        getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit().putBoolean(Prefs.VOICE_LOCK, on).apply()
        if (on && !VoiceModel.isReady(this)) {
            VoiceModel.download(this) { ok ->
                // The listener loads the voice model when it starts: restart it to pick it up.
                if (ok && WakeService.running) { WakeService.stop(this); main.postDelayed({ WakeService.start(this) }, 600) }
                main.postDelayed({ showWake() }, 800)
            }
        }
        showWake()
    }

    private fun setCalls(on: Boolean) {
        getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit().putBoolean(Prefs.CALLS, on).apply()
        if (on) {
            val missing = CallVoice.permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 3)
        }
        showWake()
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun allowBackground() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            say("Set Ajax to Unrestricted in Settings > Apps > Ajax > Battery.")
        }
    }

    private fun isDefaultAssistant(): Boolean {
        if (Build.VERSION.SDK_INT >= 29 &&
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        ) return true
        return Settings.Secure.getString(contentResolver, "assistant")?.startsWith("$packageName/") == true
    }

    private fun refreshSteps() {
        engine.sub(Recognizers.delegate(this)?.packageName ?: "None found, install the Google app")
        accessMode.sub(
            when {
                !Access.onDemand(this) -> "Controlled in Settings. On-demand mode needs one setup step from a PC."
                Access.canSeeForeground(this) -> "On only while a command runs. Refused while GCash is open."
                else -> "On only while a command runs."
            },
        )
        val ready = steps.count { it.granted() }
        val next = steps.firstOrNull { !it.granted() }
        setupRow.sub(if (next == null) "All ${steps.size} ready" else "$ready of ${steps.size} ready", Ui.ACCENT)
        val expanded = next != null || showDetails
        stepRows.visibility = if (expanded) View.VISIBLE else View.GONE
        stepRows.removeAllViews()
        if (expanded) steps.forEach { step ->
            val ok = step.granted()
            val row = Ui.row(this, step.label, if (ok) "Ready" else "Needs setup", if (ok) Ui.MUTED else Ui.ACCENT)
            if (!ok) row.onClick { open(step) }
            stepRows.addView(View(this).apply { setBackgroundColor(Ui.HAIRLINE) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1).coerceAtLeast(1)).apply {
                marginStart = dp(20)
                marginEnd = dp(20)
            })
            stepRows.addView(row.view)
        }
        grant.view.visibility = if (next == null) View.GONE else View.VISIBLE
        hint.visibility = if (next == null) View.GONE else View.VISIBLE
        if (next == null) openSettingsMode = false
        hint.text = next?.hint.orEmpty()
        grant.title.text = if (openSettingsMode) "Open app settings (permission was blocked)" else "Set up: ${next?.label}"
    }

    private fun onGrantClicked() {
        if (openSettingsMode) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
            return
        }
        open(steps.firstOrNull { !it.granted() } ?: return)
    }

    private fun open(step: Step) {
        step.runtime?.let { requestPermissions(it, 1); return }
        try {
            startActivity(step.intent!!())
        } catch (e: ActivityNotFoundException) {
            say("This phone has no settings page for that. Open it from Settings by hand.")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val denied = permissions.filterIndexed { i, _ -> grantResults[i] != PackageManager.PERMISSION_GRANTED }
        // Denied and Android will not show the dialog again: only app settings can fix it.
        openSettingsMode = denied.isNotEmpty() && denied.none { shouldShowRequestPermissionRationale(it) }
        refreshSteps()
    }

    private companion object {
        /** The secondary logo: the wordmark in block letters with a drawn shadow. */
        val LOGO = """
 █████╗      ██╗ █████╗ ██╗  ██╗
██╔══██╗     ██║██╔══██╗╚██╗██╔╝
███████║     ██║███████║ ╚███╔╝
██╔══██║██   ██║██╔══██║ ██╔██╗
██║  ██║╚█████╔╝██║  ██║██╔╝ ██╗
╚═╝  ╚═╝ ╚════╝ ╚═╝  ╚═╝╚═╝  ╚═╝
""".trim('\n')
        /** The green of the app icon. */
        const val LOGO_GREEN = 0xFFA5C736.toInt()
    }
}
