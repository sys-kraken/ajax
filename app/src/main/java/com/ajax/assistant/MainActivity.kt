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
                { AjaxAccessibilityService.instance != null },
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
        // Leaving the screen (e.g. a command opened Settings) must not turn into an error.
        speech.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        speech.destroy()
        speaker.shutdown()
        super.onDestroy()
    }

    private fun isDefaultAssistant(): Boolean {
        if (Build.VERSION.SDK_INT >= 29 &&
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        ) return true
        return Settings.Secure.getString(contentResolver, "assistant")?.startsWith("$packageName/") == true
    }

    private fun refreshSteps() {
        engine.sub(Recognizers.delegate(this)?.packageName ?: "None found, install the Google app")
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
                main.postDelayed({ AjaxAccessibilityService.announce(after()) }, 450)
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
