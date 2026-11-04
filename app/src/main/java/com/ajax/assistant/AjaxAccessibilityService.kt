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

        /**
         * Shows and speaks a result from a command that ran after the overlay closed. [then] runs once
         * the command has fully finished (a blank result means it is still running and reports later).
         */
        fun announce(r: Result, then: () -> Unit = {}) {
            instance?.say(r, then) ?: then()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var speaker: Speaker? = null
    private var whenFinished: (() -> Unit)? = null

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

    fun say(r: Result, then: (() -> Unit)? = null) {
        if (r.message.isBlank()) {
            whenFinished = then
            return
        }
        val next = then ?: whenFinished
        whenFinished = null
        Toast.makeText(this, r.message, Toast.LENGTH_SHORT).show()
        speaker?.speak(r.message, next) ?: next?.invoke()
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
     * Taps the on-screen control labelled [label]. Strict: an exact label match wins; otherwise a
     * single partial match; several candidates means we ask instead of guessing.
     */
    fun tap(label: String): Result {
        val root = rootInActiveWindow?.takeIf { it.packageName?.toString() != packageName }
            ?: return Result(false, "I can't see the screen right now.")
        val want = label.lowercase()
        val all = root.findAccessibilityNodeInfosByText(label).filter { it.isVisibleToUser }
        fun text(n: AccessibilityNodeInfo) = listOfNotNull(n.text, n.contentDescription).joinToString(" ").trim().lowercase()
        val exact = all.filter { text(it) == want }
        val pick = exact.singleOrNull() ?: if (exact.isEmpty()) all.singleOrNull() else null
        if (pick == null) {
            return Result(false, if (all.isEmpty()) "I don't see “$label” on the screen." else "I see several “$label”. Say the full name.")
        }
        var target: AccessibilityNodeInfo? = pick
        while (target != null && !target.isClickable) target = target.parent
        return if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) Result(true, "Tapped $label")
        else Result(false, "“$label” can't be tapped.")
    }

    /** Types [text] at the end of the focused text field. */
    fun type(text: String): Result {
        val field = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: return Result(false, "Tap a text box first, then ask me to type.")
        val current = field.text?.toString().orEmpty().takeUnless { field.isShowingHintText }.orEmpty()
        val joined = if (current.isEmpty() || current.endsWith(" ")) current + text else "$current $text"
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, joined)
        }
        return if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) Result(true, "Typed")
        else Result(false, "That text box won't accept typing.")
    }

    /**
     * Finds the on/off switch for one of [labels] on the current screen and sets it. Polls for a few
     * seconds because the settings page needs time to appear. Reports the outcome itself.
     */
    fun setSwitch(
        labels: List<String>,
        wantOn: Boolean,
        allowFirstSwitch: Boolean,
        pretty: String,
        state: ((android.content.Context) -> Boolean?)? = null,
    ) {
        handler.removeCallbacksAndMessages(null) // a newer command replaces any still-running one
        attempt(labels, wantOn, allowFirstSwitch, pretty, state, 0)
    }

    private fun attempt(
        labels: List<String>,
        wantOn: Boolean,
        fallback: Boolean,
        pretty: String,
        state: ((android.content.Context) -> Boolean?)?,
        n: Int,
    ) {
        // Ajax's own screen (hint text, "Speak replies" switch) is still in front for a moment:
        // never search it, or we read/tap our own switch.
        val root = rootInActiveWindow?.takeIf { it.packageName?.toString() != packageName }
        val sw = root?.let { findSwitch(it, labels, fallback) }
        if (sw == null) {
            if (n >= 15) say(Result(false, "I couldn't find the $pretty switch on the screen."))
            else handler.postDelayed({ attempt(labels, wantOn, fallback, pretty, state, n + 1) }, 400)
            return
        }
        val word = if (wantOn) "on" else "off"
        if ((state?.invoke(this) ?: sw.isChecked) == wantOn) {
            say(Result(true, "$pretty is already $word"))
            return
        }
        var target: AccessibilityNodeInfo? = sw
        while (target != null && !target.isClickable) target = target.parent
        if (target == null || !target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            say(Result(false, "I couldn't tap the $pretty switch."))
            return
        }
        verify(sw, wantOn, pretty, label = word, real = state, n = 0)
    }

    /** Radios take a few seconds to switch: poll the real state (or the switch) for up to ~4 s. */
    private fun verify(
        sw: AccessibilityNodeInfo,
        wantOn: Boolean,
        pretty: String,
        label: String,
        real: ((android.content.Context) -> Boolean?)?,
        n: Int,
    ) {
        handler.postDelayed({
            val now = real?.invoke(this) ?: run { sw.refresh(); sw.isChecked }
            when {
                now == wantOn -> say(Result(true, "$pretty $label"))
                n >= 7 -> say(Result(false, "Tried, but $pretty did not turn $label."))
                else -> verify(sw, wantOn, pretty, label, real, n + 1)
            }
        }, 500)
    }

    /**
     * The switch for [labels], or null. Strict on purpose — tapping the wrong switch (e.g. Wi-Fi for
     * Bluetooth on a list page) is worse than doing nothing:
     *  - a label must match a node's whole text/description, not a substring;
     *  - climbing from the label, accept a switch only if it is the ONLY one in that container;
     *    a container with several switches is a list, so stop there;
     *  - [fallback] takes the screen's switch only if the screen has exactly one.
     */
    private fun findSwitch(root: AccessibilityNodeInfo, labels: List<String>, fallback: Boolean): AccessibilityNodeInfo? {
        val wanted = labels.flatMap { listOf(it, "Use $it") }.map { it.lowercase() }.toSet()
        for (label in labels) {
            for (node in root.findAccessibilityNodeInfosByText(label)) {
                if (!isLabel(node, wanted)) continue
                var p: AccessibilityNodeInfo? = node
                for (level in 0..4) {
                    val n = p ?: break
                    val inside = checkables(n, 2)
                    if (inside.size == 1) return inside[0]
                    if (inside.size > 1) break
                    p = n.parent
                }
            }
        }
        if (!fallback) return null
        return checkables(root, 2).singleOrNull()
    }

    private fun isLabel(n: AccessibilityNodeInfo, wanted: Set<String>): Boolean =
        listOfNotNull(n.text, n.contentDescription).any { it.toString().trim().lowercase() in wanted }

    /** Checkable nodes under [n], stopping once [limit] are found (we only need 0, 1 or "many"). */
    private fun checkables(n: AccessibilityNodeInfo, limit: Int, out: MutableList<AccessibilityNodeInfo> = ArrayList()): List<AccessibilityNodeInfo> {
        if (out.size >= limit) return out
        if (n.isCheckable) out.add(n)
        for (i in 0 until n.childCount) {
            if (out.size >= limit) break
            n.getChild(i)?.let { checkables(it, limit, out) }
        }
        return out
    }
}
