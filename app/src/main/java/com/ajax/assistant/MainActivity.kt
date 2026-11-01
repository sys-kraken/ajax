package com.ajax.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity(), SpeechInput.Callback {

    private class Step(val label: String, val permissions: Array<String>)

    private val steps = listOf(
        Step("Microphone (required for voice)", arrayOf(Manifest.permission.RECORD_AUDIO)),
        Step(
            "Contacts and phone (needed for \"call ...\")",
            arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE),
        ),
    )

    private lateinit var speech: SpeechInput
    private lateinit var speaker: Speaker
    private lateinit var status: TextView
    private lateinit var grant: Button
    private lateinit var talk: Button
    private lateinit var heard: TextView
    private lateinit var result: TextView
    private var openSettingsMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speech = SpeechInput(this, this)
        speaker = Speaker(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            fitsSystemWindows = true
        }
        fun text(size: Float) = TextView(this).apply {
            textSize = size
            setPadding(0, pad / 2, 0, pad / 2)
        }

        root.addView(text(28f).apply { this.text = "Ajax" })
        status = text(16f).also { root.addView(it) }
        grant = Button(this).apply { setOnClickListener { onGrantClicked() } }
        root.addView(grant)
        root.addView(Switch(this).apply {
            text = "Speak replies"
            isChecked = true
            setOnCheckedChangeListener { _, on -> speaker.enabled = on }
        })

        root.addView(Switch(this).apply {
            text = "Allow online speech recognition (audio may go to Google)"
            isChecked = false
            setOnCheckedChangeListener { _, on -> speech.allowOnline = on }
        })
        root.addView(Button(this).apply {
            text = "Open voice settings"
            setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                catch (e: android.content.ActivityNotFoundException) { show(Result(false, "This phone has no voice settings page.")) }
            }
        })

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
                text = "Try: open YouTube, open Wi-Fi settings, flashlight on, volume up, mute, call <contact name>"
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    override fun onDestroy() {
        speech.destroy()
        speaker.shutdown()
        super.onDestroy()
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun refreshPermissions() {
        status.text = steps.joinToString("\n") { s ->
            (if (s.permissions.all(::granted)) "✓ " else "✗ ") + s.label
        }
        val next = steps.firstOrNull { s -> !s.permissions.all(::granted) }
        grant.visibility = if (next == null) android.view.View.GONE else android.view.View.VISIBLE
        if (next == null) openSettingsMode = false
        grant.text = if (openSettingsMode) "Open app settings (permission was blocked)" else "Allow: ${next?.label}"
        talk.isEnabled = granted(Manifest.permission.RECORD_AUDIO)
    }

    private fun onGrantClicked() {
        if (openSettingsMode) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
            return
        }
        val next = steps.firstOrNull { s -> !s.permissions.all(::granted) } ?: return
        requestPermissions(next.permissions.filter { !granted(it) }.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val denied = permissions.filterIndexed { i, _ -> grantResults[i] != PackageManager.PERMISSION_GRANTED }
        // Denied and Android will not show the dialog again: only app settings can fix it.
        openSettingsMode = denied.isNotEmpty() && denied.none { shouldShowRequestPermissionRationale(it) }
        refreshPermissions()
    }

    private fun startListening() {
        if (!granted(Manifest.permission.RECORD_AUDIO)) return
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
        // Try the recogniser's alternatives in order until one is a known command.
        val hit = candidates.firstNotNullOfOrNull { c -> CommandRouter.route(c)?.let { c to it } }
        if (hit == null) {
            heard.text = "“${candidates.first()}”"
            show(Result(false, "I don't know that command yet."))
            return
        }
        heard.text = "“${hit.first}”"
        show(hit.second.run(this))
    }

    override fun onError(message: String) {
        show(Result(false, message))
    }

    private fun show(r: Result) {
        result.text = (if (r.ok) "✓ " else "✗ ") + r.message
        speaker.speak(r.message)
    }
}
