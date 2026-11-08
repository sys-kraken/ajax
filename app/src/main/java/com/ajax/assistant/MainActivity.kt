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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ajax.assistant.Ui.dp

class MainActivity : Activity(), SpeechInput.Callback {

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

    private lateinit var speech: SpeechInput
    private lateinit var speaker: Speaker
    private lateinit var brain: Brain
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
    private lateinit var callsSwitch: android.widget.Switch
    private lateinit var hint: TextView
    private lateinit var grant: Ui.Row
    private lateinit var talk: Button
    private lateinit var heard: TextView
    private lateinit var result: TextView
    private lateinit var modelStatus: Ui.Row
    private lateinit var modelButton: Ui.Row
    private val main = Handler(Looper.getMainLooper())
    private var openSettingsMode = false
    private var showDetails = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speech = SpeechInput(this, this)
        speaker = Speaker(this)
        brain = Brain(this)
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
                text = "Ajax"
                setPadding(dp(8), 0, 0, 0)
            })
        }, Ui.below(this, 12))

        talk = Ui.button(this, TALK_LABEL).apply {
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; text = "Listening"; startListening(); true }
                    MotionEvent.ACTION_UP -> { v.isPressed = false; text = TALK_LABEL; v.performClick(); speech.stopListening(); true }
                    MotionEvent.ACTION_CANCEL -> { v.isPressed = false; text = TALK_LABEL; speech.stopListening(); true }
                    else -> false
                }
            }
        }
        root.addView(talk, LinearLayout.LayoutParams(MATCH_PARENT, dp(72)).apply { bottomMargin = dp(12) })

        root.addView(Ui.card(this).apply {
            heard = Ui.text(this@MainActivity, 14f, Ui.MUTED).also { addView(it) }
            result = Ui.text(this@MainActivity, 17f).apply {
                hint = "Hold the button or the side key, then speak."
                setHintTextColor(Ui.MUTED)
            }.also { addView(it) }
        }, Ui.below(this, 12))

        root.addView(Ui.group(this).apply {
            setupRow = Ui.row(this@MainActivity, "Setup").also {
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
        }, Ui.below(this, 12))

        root.addView(Ui.group(this).apply {
            modelStatus = Ui.row(this@MainActivity, "Smart understanding").also { Ui.addRow(this, it.view) }
            modelButton = Ui.row(this@MainActivity, "").also {
                it.title.setTextColor(Ui.ACCENT)
                it.onClick { onModelClicked() }
                Ui.addRow(this, it.view)
            }
        }, Ui.below(this, 12))

        root.addView(Ui.group(this).apply {
            wakeSwitch = Ui.switch(this@MainActivity).apply {
                isChecked = WakeService.enabled(this@MainActivity)
                setOnCheckedChangeListener { _, on -> setWake(on) }
            }
            wakeRow = Ui.row(this@MainActivity, "Listen for “Ajax”", end = wakeSwitch).also {
                it.onClick { wakeSwitch.toggle() }
                Ui.addRow(this, it.view)
            }
            heardRow = Ui.row(this@MainActivity, "Last heard", "Say “Ajax” or “lumos” to test").also { Ui.addRow(this, it.view) }
            callsSwitch = Ui.switch(this@MainActivity).apply {
                isChecked = prefs.getBoolean(Prefs.CALLS, false)
                setOnCheckedChangeListener { _, on -> setCalls(on) }
            }
            callsRow = Ui.row(this@MainActivity, "Announce callers", end = callsSwitch).also {
                it.onClick { callsSwitch.toggle() }
                Ui.addRow(this, it.view)
            }
            teachRow = Ui.row(this@MainActivity, "Teach Ajax your voice").also {
                it.onClick { teachVoice() }
                Ui.addRow(this, it.view)
            }
            batteryRow = Ui.row(this@MainActivity, "Run in the background").also {
                it.onClick { allowBackground() }
                Ui.addRow(this, it.view)
            }
        }, Ui.below(this, 12))

        root.addView(Ui.group(this).apply {
            fun option(label: String, sub: String, key: String, default: Boolean) {
                val toggle = Ui.switch(this@MainActivity).apply {
                    isChecked = prefs.getBoolean(key, default)
                    setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
                }
                Ui.addRow(this, Ui.row(this@MainActivity, label, sub, end = toggle).also { r -> r.onClick { toggle.toggle() } }.view)
            }
            option("Speak replies", "", Prefs.SPEAK, true)
            option("Online speech recognition", "Audio may go to Google", Prefs.ONLINE, false)
            engine = Ui.row(this@MainActivity, "Speech engine").also { Ui.addRow(this, it.view) }
            accessMode = Ui.row(this@MainActivity, "Accessibility").also { Ui.addRow(this, it.view) }
            Ui.addRow(this, Ui.row(this@MainActivity, "Voice settings").also {
                it.onClick {
                    try { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                    catch (e: ActivityNotFoundException) { show(Result(false, "This phone has no voice settings page.")) }
                }
            }.view)
        }, Ui.below(this, 12))

        root.addView(Ui.group(this).apply {
            val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            Ui.addRow(this, Ui.row(this@MainActivity, "Version", version).view)
        })

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

    /** Fallback path: some phones send the side key as ACTION_ASSIST to this activity. Listen at once. */
    private fun handleAssist(i: Intent?) {
        if (i?.action == Intent.ACTION_ASSIST) main.post { startListening() }
    }

    override fun onResume() {
        super.onResume()
        Access.settle(this)
        WakeModel.listener = { showWake() }
        WakeService.onHeard = { heardRow.sub("“$it”") }
        if (WakeService.enabled(this) && !WakeService.running) WakeService.start(this)
        main.postDelayed({ showWake() }, 300)
        ModelManager.listener = ::showModel
        showModel(ModelManager.state(this))
        refreshSteps()
    }

    private fun onModelClicked() {
        when (ModelManager.state(this)) {
            is ModelManager.State.Ready -> ModelManager.delete(this)
            is ModelManager.State.Downloading -> {}
            else -> ModelManager.download(this)
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
        WakeModel.listener = null
        WakeService.onHeard = null
        // Leaving the screen (e.g. a command opened Settings) must not turn into an error.
        speech.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        speech.destroy()
        speaker.shutdown()
        super.onDestroy()
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
                !on -> "Off. Turning it on downloads a ${mb(WakeModel.SIZE_BYTES)} MB speech model once."
                WakeService.running -> "On, even with the screen off. The green dot stays on. Alerts you when another app uses the microphone."
                else -> "Starting…"
            },
        )
        heardRow.view.visibility = if (on && WakeService.running) View.VISIBLE else View.GONE
        callsRow.view.visibility = if (on) View.VISIBLE else View.GONE
        teachRow.view.visibility = if (on) View.VISIBLE else View.GONE
        if (!teaching) {
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

    private var teaching = false

    private fun teachVoice() {
        if (teaching) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        teaching = true
        VoiceSamples.record(this, progress = { teachRow.sub(it, Ui.ACCENT) }) {
            teaching = false
            showWake()
        }
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
            show(Result(false, "Set Ajax to Unrestricted in Settings > Apps > Ajax > Battery."))
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
        talk.isEnabled = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
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
            show(Result(false, "This phone has no settings page for that. Open it from Settings by hand."))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val denied = permissions.filterIndexed { i, _ -> grantResults[i] != PackageManager.PERMISSION_GRANTED }
        // Denied and Android will not show the dialog again: only app settings can fix it.
        openSettingsMode = denied.isNotEmpty() && denied.none { shouldShowRequestPermissionRationale(it) }
        refreshSteps()
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        heard.text = ""
        result.text = ""
        speech.start()
    }

    override fun onListening(mode: String) {
        heard.text = "Listening ($mode)..."
    }

    override fun onPartial(text: String) {
        heard.text = "“$text”"
    }

    override fun onResults(candidates: List<String>) {
        heard.text = "“${candidates.first()}”"
        brain.handle(candidates, onThinking = { result.text = "Thinking…" }) { act(it) }
    }

    private fun act(reply: Brain.Reply) {
        if (isFinishing || isDestroyed) return
        heard.text = "“${brain.heard}”"
        val after = reply.afterHide
        when {
            after != null -> {
                result.text = reply.result.message
                main.postDelayed({ Access.run(this, after) }, 450)
            }
            reply.listenAgain -> {
                result.text = reply.result.message
                speaker.speak(reply.result.message) { speech.start() }
            }
            else -> show(reply.result)
        }
    }

    override fun onError(message: String) {
        show(Result(false, message))
    }

    private fun show(r: Result) {
        result.text = r.message
        speaker.speak(r.message)
    }

    private companion object {
        const val TALK_LABEL = "Hold to talk"
    }
}
