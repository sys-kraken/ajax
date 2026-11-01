package com.ajax.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Global actions, scrolling and switch tapping. It reads the screen only while a command is running
 * and never acts on text it finds there.
 */
class AjaxAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: AjaxAccessibilityService? = null

        /** Shows and speaks a result from a command that ran after the overlay closed. */
        fun announce(r: Result) {
            instance?.say(r)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var speaker: Speaker? = null

    override fun onServiceConnected() {
        instance = this
        speaker = Speaker(this)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        speaker?.shutdown()
        speaker = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun say(r: Result) {
        if (r.message.isBlank()) return
        Toast.makeText(this, r.message, Toast.LENGTH_SHORT).show()
        speaker?.speak(r.message)
    }

    fun global(action: Int, label: String): Result =
        if (performGlobalAction(action)) Result(true, label) else Result(false, "Android refused: $label")

    fun globalApi28(action: Int, label: String): Result =
        if (Build.VERSION.SDK_INT < 28) Result(false, "$label needs Android 9 or newer.") else global(action, label)

    fun scroll(down: Boolean): Result {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val from = dm.heightPixels * if (down) 0.7f else 0.3f
        val to = dm.heightPixels * if (down) 0.3f else 0.7f
        val path = Path().apply { moveTo(x, from); lineTo(x, to) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        return if (dispatchGesture(gesture, null, null)) {
            Result(true, if (down) "Scrolling down" else "Scrolling up")
        } else {
            Result(false, "Couldn't scroll.")
        }
    }

    /**
     * Finds the on/off switch for one of [labels] on the current screen and sets it. Polls for a few
     * seconds because the settings page needs time to appear. Reports the outcome itself.
     */
    fun setSwitch(labels: List<String>, wantOn: Boolean, allowFirstSwitch: Boolean, pretty: String) {
        attempt(labels, wantOn, allowFirstSwitch, pretty, 0)
    }

    private fun attempt(labels: List<String>, wantOn: Boolean, fallback: Boolean, pretty: String, n: Int) {
        val root = rootInActiveWindow
        val sw = root?.let { findSwitch(it, labels, fallback) }
        if (sw == null) {
            if (n >= 15) say(Result(false, "I couldn't find the $pretty switch on the screen."))
            else handler.postDelayed({ attempt(labels, wantOn, fallback, pretty, n + 1) }, 400)
            return
        }
        val state = if (wantOn) "on" else "off"
        if (sw.isChecked == wantOn) {
            say(Result(true, "$pretty is already $state"))
            return
        }
        var target: AccessibilityNodeInfo? = sw
        while (target != null && !target.isClickable) target = target.parent
        if (target == null || !target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            say(Result(false, "I couldn't tap the $pretty switch."))
            return
        }
        handler.postDelayed({
            sw.refresh()
            say(
                if (sw.isChecked == wantOn) Result(true, "$pretty $state")
                else Result(false, "Tried, but $pretty did not turn $state."),
            )
        }, 800)
    }

    private fun findSwitch(root: AccessibilityNodeInfo, labels: List<String>, fallback: Boolean): AccessibilityNodeInfo? {
        for (label in labels) {
            for (node in root.findAccessibilityNodeInfosByText(label)) {
                var p: AccessibilityNodeInfo? = node
                repeat(3) {
                    p?.let { n -> firstCheckable(n)?.let { return it } }
                    p = p?.parent
                }
            }
        }
        return if (fallback) firstCheckable(root) else null
    }

    private fun firstCheckable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isCheckable) return n
        for (i in 0 until n.childCount) {
            n.getChild(i)?.let { c -> firstCheckable(c)?.let { return it } }
        }
        return null
    }
}
