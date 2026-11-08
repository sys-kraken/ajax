package com.ajax.assistant

/**
 * What "it", "that", "him", "her" and "them" refer to: the app, switch and contact from the last
 * commands that used one. "Turn it back on" after "turn off Wi-Fi" becomes "turn on wifi". Kept in
 * memory only, and forgotten after [KEEP_MS].
 */
object Memory {
    private const val KEEP_MS = 10 * 60_000L

    /** Swappable for tests. */
    var now: () -> Long = { System.currentTimeMillis() }

    private class Slot(val value: String, val at: Long)

    private var app: Slot? = null
    private var toggle: Slot? = null
    private var contact: Slot? = null

    fun rememberApp(name: String) { app = Slot(name, now()) }
    fun rememberToggle(name: String) { toggle = Slot(name, now()) }
    fun rememberContact(name: String) { contact = Slot(name, now()) }

    fun forget() { app = null; toggle = null; contact = null }

    private fun Slot?.fresh(): String? = this?.takeIf { now() - it.at < KEEP_MS }?.value

    private const val IT = "(?:it|that|this)"
    private const val PERSON = "(?:him|her|them|that person)"

    /**
     * [t] (already normalized) with the pronoun replaced, or null when it has none Ajax can fill in.
     * [appsJustListed]: "close it" right after a list of apps means those apps, which another command handles.
     */
    fun resolve(t: String, appsJustListed: Boolean = false): String? {
        Regex("^(?:turn|switch) $IT (?:back )?(on|off)$").matchEntire(t)
            ?.let { m -> return toggle.fresh()?.let { switchPhrase(it, m.groupValues[1]) } }
        Regex("^(?:turn|switch) (?:back )?(on|off) $IT$").matchEntire(t)
            ?.let { m -> return toggle.fresh()?.let { switchPhrase(it, m.groupValues[1]) } }
        Regex("^(enable|disable) $IT$").matchEntire(t)
            ?.let { m -> return toggle.fresh()?.let { switchPhrase(it, if (m.groupValues[1] == "enable") "on" else "off") } }

        if (Regex("^(?:open|launch|start|reopen) $IT(?: again| back up| back)?$").matches(t)) {
            return app.fresh()?.let { "open $it" }
        }
        if (!appsJustListed && Regex("^(?:close|force stop|force close|kill|quit|terminate) $IT$").matches(t)) {
            return app.fresh()?.let { "force stop $it" }
        }
        if (Regex("^(?:show |open )?(?:app )?info (?:for |of |about )?$IT$").matches(t)) {
            return app.fresh()?.let { "app info for $it" }
        }

        if (Regex("^(?:call|ring|phone|dial) $PERSON(?: back| again)?$").matches(t)) {
            return contact.fresh()?.let { "call $it" }
        }
        Regex("^(?:text|message) $PERSON (?:saying |that says |that )?(.+)$").matchEntire(t)
            ?.let { m -> return contact.fresh()?.let { "text $it saying ${m.groupValues[1]}" } }
        return null
    }

    private fun switchPhrase(name: String, state: String) =
        if (name == "flashlight") "flashlight $state" else "turn $state $name"
}
