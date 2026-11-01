package com.ajax.assistant

import android.content.Context
import android.media.AudioManager
import android.provider.Settings

/** Turns recognised text into one action. Keyword/regex matching only (stage 1). */
object CommandRouter {

    class Match(val id: String, val run: (Context) -> Result)

    private val pages: Map<String, String> = buildMap {
        fun add(action: String, vararg names: String) = names.forEach { put(it, action) }
        add(Settings.ACTION_WIFI_SETTINGS, "wifi")
        add(Settings.ACTION_BLUETOOTH_SETTINGS, "bluetooth")
        add(Settings.ACTION_BATTERY_SAVER_SETTINGS, "battery")
        add(Settings.ACTION_SOUND_SETTINGS, "sound", "sounds", "volume")
        add(Settings.ACTION_DISPLAY_SETTINGS, "display", "screen", "brightness")
        add(Settings.ACTION_LOCATION_SOURCE_SETTINGS, "location", "gps")
        add(Settings.ACTION_AIRPLANE_MODE_SETTINGS, "airplane mode", "airplane", "flight mode")
        add(Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "storage")
        add(Settings.ACTION_ACCESSIBILITY_SETTINGS, "accessibility")
        add(Settings.ACTION_APPLICATION_SETTINGS, "apps", "app", "applications")
        add(Settings.ACTION_DATE_SETTINGS, "date", "time", "date and time")
        add(Settings.ACTION_NFC_SETTINGS, "nfc")
    }

    fun normalize(raw: String): String {
        var s = raw.lowercase().trim()
        s = s.replace(Regex("wi[\\s-]?fi"), "wifi")
        s = s.replace(Regex("[^a-z0-9 ]"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex("^(hey |ok |okay )?(ajax )?(please )?(can you |could you )?(please )?"), "").trim()
        s = s.removeSuffix(" please").trim()
        return s
    }

    fun route(raw: String): Match? {
        val t = normalize(raw)
        if (t.isEmpty()) return null
        return volume(t) ?: torch(t) ?: settings(t) ?: call(t) ?: openApp(t)
    }

    private fun volume(t: String): Match? = when {
        Regex("^(volume up|turn (the )?volume up|turn it up|increase (the )?volume|raise (the )?volume|louder)$").matches(t) ->
            Match("volume-up") { Actions.volume(it, AudioManager.ADJUST_RAISE, "Volume up") }
        Regex("^(volume down|turn (the )?volume down|turn it down|decrease (the )?volume|lower (the )?volume|quieter)$").matches(t) ->
            Match("volume-down") { Actions.volume(it, AudioManager.ADJUST_LOWER, "Volume down") }
        Regex("^(mute|mute (the )?(sound|volume|phone))$").matches(t) ->
            Match("mute") { Actions.volume(it, AudioManager.ADJUST_MUTE, "Muted") }
        Regex("^(unmute|unmute (the )?(sound|volume|phone))$").matches(t) ->
            Match("unmute") { Actions.volume(it, AudioManager.ADJUST_UNMUTE, "Unmuted") }
        else -> null
    }

    private val torchWord = Regex("\\b(flashlight|flash light|torch)\\b")

    private fun torch(t: String): Match? {
        if (!torchWord.containsMatchIn(t)) return null
        val on = Regex("\\bon\\b").containsMatchIn(t)
        val off = Regex("\\boff\\b").containsMatchIn(t)
        return when {
            on && !off -> Match("torch-on") { Actions.torch(it, true) }
            off && !on -> Match("torch-off") { Actions.torch(it, false) }
            else -> Match("torch-unclear") { Result(false, "Say flashlight on, or flashlight off.") }
        }
    }

    private fun settings(t: String): Match? {
        if (Regex("^(open |show )?settings$").matches(t)) return page("main", Settings.ACTION_SETTINGS)
        val m = Regex("^(?:open |show |go to )?(?:the )?(.+) settings$").matchEntire(t) ?: return null
        val name = m.groupValues[1]
        val action = pages[name] ?: return null
        return page(name, action)
    }

    private fun page(name: String, action: String) =
        Match("settings:$name") { Actions.openSettings(it, action, name) }

    private fun call(t: String): Match? {
        val m = Regex("^(?:call|dial|phone|ring) (.+)$").matchEntire(t) ?: return null
        val who = m.groupValues[1].trim()
        if (Regex("^[0-9 ]+$").matches(who)) {
            return Match("call-number") { Result(false, "Calling phone numbers isn't supported yet. Say a contact name.") }
        }
        return Match("call") { Actions.call(it, who) }
    }

    private fun openApp(t: String): Match? {
        val m = Regex("^(?:open|launch|start|run) (?:the )?(.+?)(?: app)?$").matchEntire(t) ?: return null
        val name = m.groupValues[1].trim()
        return Match("open-app") { Actions.openApp(it, name) }
    }
}
