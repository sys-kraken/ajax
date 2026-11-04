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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

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
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var grant: Button
    private lateinit var talk: Button
    private lateinit var heard: TextView
    private lateinit var result: TextView
    private lateinit var modelStatus: TextView
    private lateinit var modelButton: Button
    private val main = Handler(Looper.getMainLooper())
    private var openSettingsMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speech = SpeechInput(this, this)
        speaker = Speaker(this)
        brain = Brain(this)
        Actions.watchTorch(this)
        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun text(size: Float) = TextView(this).apply {
            textSize = size
            setPadding(0, pad / 2, 0, pad / 2)
        }

        root.addView(text(28f).apply { this.text = "Ajax" })
        root.addView(text(12f).apply {
            this.text = "Version " + packageManager.getPackageInfo(packageName, 0).versionName
        })
        status = text(15f).also { root.addView(it) }
        hint = text(13f).also { root.addView(it) }
        grant = Button(this).apply { setOnClickListener { onGrantClicked() } }
        root.addView(grant)
        root.addView(Switch(this).apply {
            text = "Speak replies"
            isChecked = prefs.getBoolean(Prefs.SPEAK, true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Prefs.SPEAK, on).apply() }
        })
        root.addView(Switch(this).apply {
            text = "Allow online speech recognition (audio may go to Google)"
            isChecked = prefs.getBoolean(Prefs.ONLINE, false)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Prefs.ONLINE, on).apply() }
        })
        root.addView(Button(this).apply {
            text = "Open voice settings"
            setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                catch (e: ActivityNotFoundException) { show(Result(false, "This phone has no voice settings page.")) }
            }
        })

        root.addView(text(17f).apply { text = "Smart understanding (optional)" })
        modelStatus = text(13f).also { root.addView(it) }
        modelButton = Button(this).apply { setOnClickListener { onModelClicked() } }
        root.addView(modelButton)

        talk = Button(this).apply {
            text = "Hold to talk"
            textSize = 20f
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { startListening(); true }
                    MotionEvent.ACTION_UP -> { v.performClick(); speech.stopListening(); true }
                    MotionEvent.ACTION_CANCEL -> { speech.stopListening(); true }
                    else -> false
                }
            }
        }
        root.addView(talk, LinearLayout.LayoutParams(MATCH_PARENT, 4 * pad))
        heard = text(16f).also { root.addView(it) }
        result = text(18f).also { root.addView(it) }
        root.addView(
            text(13f).apply {
                text = "Try: open YouTube • turn on Bluetooth • flashlight • volume 40 percent • play music • next song • " +
                    "brightness 50 percent • do not disturb on • auto rotate off • turn off location • " +
                    "call <name> • text <name> saying <message> • set an alarm for 7 30 am • timer 5 minutes • " +
                    "take a photo • search for <anything> • navigate to <place> • tap <button> • type <text> • " +
                    "go back • scroll down • take a screenshot • what's my battery • how much storage do I have • " +
                    "read my notifications. With smart understanding on, just say it your way."
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        setContentView(ScrollView(this).apply {
            addView(root)
            fitsSystemWindows = true
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
            modelStatus.text = "Not supported on this phone (needs a 64-bit ARM processor)."
            modelButton.visibility = View.GONE
            return
        }
        modelButton.isEnabled = s !is ModelManager.State.Downloading
        when (s) {
            is ModelManager.State.Ready -> {
                modelStatus.text = "On. Ajax understands loose phrasing like “could you flip the torch on”. " +
                    "Runs fully on the phone."
                modelButton.text = "Delete model (free ${mb(ModelManager.SIZE_BYTES)} MB)"
            }
            is ModelManager.State.Downloading -> {
                modelStatus.text = "Downloading… ${mb(s.done)} of ${mb(s.total)} MB. You can leave this screen."
                modelButton.text = "Downloading…"
            }
            is ModelManager.State.Failed -> {
                modelStatus.text = s.message
                modelButton.text = "Retry download"
            }
            is ModelManager.State.Missing -> {
                modelStatus.text = "Downloads a ${mb(ModelManager.SIZE_BYTES)} MB language model once (use Wi-Fi). " +
                    "After that it runs offline; your words never leave the phone."
                modelButton.text = "Download model"
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
        val engine = Recognizers.delegate(this)?.packageName ?: "none found — install the Google app"
        status.text = steps.joinToString("\n") { it.label + if (it.granted()) ": ready" else ": needs setup" } +
            "\nSpeech engine: $engine"
        val next = steps.firstOrNull { !it.granted() }
        grant.visibility = if (next == null) View.GONE else View.VISIBLE
        hint.visibility = if (next == null) View.GONE else View.VISIBLE
        if (next == null) openSettingsMode = false
        hint.text = next?.hint.orEmpty()
        grant.text = if (openSettingsMode) "Open app settings (permission was blocked)" else "Set up: ${next?.label}"
        talk.isEnabled = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun onGrantClicked() {
        if (openSettingsMode) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
            return
        }
        val next = steps.firstOrNull { !it.granted() } ?: return
        next.runtime?.let { requestPermissions(it, 1); return }
        try {
            startActivity(next.intent!!())
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
}
