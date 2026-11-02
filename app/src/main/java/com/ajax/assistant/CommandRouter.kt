package com.ajax.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings

/** Turns recognised text into one action. Keyword/regex matching only. */
object CommandRouter {

    class Match(val id: String, val start: (Context) -> Outcome)

    private val needsAccessibility =
        Result(false, "This needs Accessibility. Open Ajax and turn it on.")

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
        s = s.replace(Regex("['’]"), "")
        s = s.replace(Regex("wi[\\s-]?fi"), "wifi")
        s = s.replace(Regex("[^a-z0-9 ]"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex("\\b([ap]) m\\b"), "$1m")
        s = s.replace(Regex("screen shot"), "screenshot")
        s = s.replace(Regex("^(hey |ok |okay )?(ajax )?(please )?(can you |could you )?(please )?"), "").trim()
        s = s.removeSuffix(" please").trim()
        return s
    }

    fun route(raw: String): Match? {
        val t = normalize(raw)
        if (t.isEmpty()) return null
        return volume(t) ?: ringer(t) ?: torch(t) ?: toggle(t) ?: brightness(t) ?: dnd(t) ?: nav(t)
            ?: info(t) ?: alarm(t) ?: timer(t) ?: sms(raw) ?: appInfo(t) ?: settings(t) ?: call(t) ?: openApp(t)
    }

    private fun done(id: String, f: (Context) -> Result) = Match(id) { Outcome.Done(f(it)) }

    // ---- Sound -------------------------------------------------------------------------------

    private fun volume(t: String): Match? = when {
        Regex("^(volume up|turn (the )?volume up|turn it up|increase (the )?volume|raise (the )?volume|louder)$").matches(t) ->
            done("volume-up") { Actions.volume(it, AudioManager.ADJUST_RAISE, "Volume up") }
        Regex("^(volume down|turn (the )?volume down|turn it down|decrease (the )?volume|lower (the )?volume|quieter)$").matches(t) ->
            done("volume-down") { Actions.volume(it, AudioManager.ADJUST_LOWER, "Volume down") }
        Regex("^(mute|mute (the )?(sound|volume|phone))$").matches(t) ->
            done("mute") { Actions.volume(it, AudioManager.ADJUST_MUTE, "Muted") }
        Regex("^(unmute|unmute (the )?(sound|volume|phone))$").matches(t) ->
            done("unmute") { Actions.volume(it, AudioManager.ADJUST_UNMUTE, "Unmuted") }
        else -> null
    }

    private fun ringer(t: String): Match? = when {
        Regex("^(vibrate|vibrate mode|vibration mode|set to vibrate|put (the )?phone on vibrate)$").matches(t) ->
            done("ringer-vibrate") { Actions.ringer(it, AudioManager.RINGER_MODE_VIBRATE, "Vibrate mode") }
        Regex("^(silent|silent mode|silence (the )?phone)$").matches(t) ->
            done("ringer-silent") { Actions.ringer(it, AudioManager.RINGER_MODE_SILENT, "Silent mode") }
        Regex("^(sound mode|normal mode|ring mode|ringer on|unsilence|unsilence (the )?phone)$").matches(t) ->
            done("ringer-normal") { Actions.ringer(it, AudioManager.RINGER_MODE_NORMAL, "Sound mode") }
        else -> null
    }

    // ---- Hardware ----------------------------------------------------------------------------

    private val torchWord = Regex("\\b(flashlight|flash light|torch)\\b")

    private fun torch(t: String): Match? {
        if (!torchWord.containsMatchIn(t)) return null
        val on = Regex("\\bon\\b").containsMatchIn(t)
        val off = Regex("\\boff\\b").containsMatchIn(t)
        return when {
            on && !off -> done("torch-on") { Actions.torch(it, true) }
            off && !on -> done("torch-off") { Actions.torch(it, false) }
            else -> done("torch-unclear") { Result(false, "Say flashlight on, or flashlight off.") }
        }
    }

    private class Toggle(
        val action: String,
        val labels: List<String>,
        val firstSwitchFallback: Boolean,
        val pretty: String,
        /** Real system state, when readable; trusted over what the screen shows. */
        val state: ((Context) -> Boolean?)? = null,
    )

    private fun toggleFor(name: String): Toggle? = when (name) {
        "wifi" -> Toggle(Settings.ACTION_WIFI_SETTINGS, listOf("Wi-Fi", "Wifi", "WLAN"), true, "Wi-Fi", SwitchState::wifi)
        "bluetooth" -> Toggle(Settings.ACTION_BLUETOOTH_SETTINGS, listOf("Bluetooth"), true, "Bluetooth", SwitchState::bluetooth)
        "mobile data", "cellular data", "data" -> Toggle(
            if (Build.VERSION.SDK_INT >= 29) "android.settings.panel.action.INTERNET_CONNECTIVITY"
            else Settings.ACTION_DATA_ROAMING_SETTINGS,
            listOf("Mobile data"), false, "Mobile data",
        )
        "airplane mode", "airplane", "flight mode" -> Toggle(
            Settings.ACTION_AIRPLANE_MODE_SETTINGS, listOf("Airplane mode", "Flight mode"), true, "Airplane mode", SwitchState::airplane,
        )
        else -> null
    }

    private const val TOGGLE_NAMES = "wifi|bluetooth|mobile data|cellular data|data|airplane mode|airplane|flight mode"

    /** Switches Android won't let apps flip: open the page, then tap the switch with Accessibility. */
    private fun toggle(t: String): Match? {
        val m = Regex("^(?:turn|switch) (on|off)(?: the)? ($TOGGLE_NAMES)$").matchEntire(t)?.let { it.groupValues[2] to it.groupValues[1] }
            ?: Regex("^(?:turn|switch) (?:the )?($TOGGLE_NAMES) (on|off)$").matchEntire(t)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: Regex("^($TOGGLE_NAMES) (on|off)$").matchEntire(t)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: Regex("^(enable|disable) (?:the )?($TOGGLE_NAMES)$").matchEntire(t)?.let {
                it.groupValues[2] to if (it.groupValues[1] == "enable") "on" else "off"
            }
            ?: return null
        val tg = toggleFor(m.first) ?: return null
        val wantOn = m.second == "on"
        return Match("toggle:${tg.pretty}:${m.second}") {
            val now = tg.state?.invoke(it)
            if (now == wantOn) {
                Outcome.Done(Result(true, "${tg.pretty} is already ${m.second}"))
            } else if (AjaxAccessibilityService.instance == null) {
                Outcome.Done(needsAccessibility)
            } else {
                Outcome.AfterHide("Turning ${tg.pretty} ${m.second}") { ctx ->
                    val opened = try {
                        ctx.startActivity(Intent(tg.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    } catch (e: android.content.ActivityNotFoundException) {
                        false
                    }
                    if (!opened) Result(false, "This phone has no ${tg.pretty} page.")
                    else {
                        AjaxAccessibilityService.instance?.setSwitch(tg.labels, wantOn, tg.firstSwitchFallback, tg.pretty, tg.state)
                        Result(true, "")
                    }
                }
            }
        }
    }

    private fun brightness(t: String): Match? {
        val prefix = "^(?:set )?(?:the )?(?:screen )?brightness (?:to |at )?"
        Regex("$prefix(\\d{1,3})(?: percent)?$").matchEntire(t)?.let { m ->
            val p = m.groupValues[1].toInt()
            if (p in 1..100) return done("brightness") { Actions.brightness(it, p) }
            return done("brightness-invalid") { Result(false, "Say a brightness from 1 to 100 percent.") }
        }
        if (Regex("$prefix(?:max|maximum|full)$").matches(t)) return done("brightness") { Actions.brightness(it, 100) }
        if (Regex("$prefix(?:min|minimum|lowest)$").matches(t)) return done("brightness") { Actions.brightness(it, 1) }
        return null
    }

    private const val DND = "(?:do not disturb|dont disturb|dnd)(?: mode)?"

    private fun dnd(t: String): Match? = when {
        Regex("^(?:turn on|enable|switch on|start|activate) (?:the )?$DND$|^$DND on$").matches(t) ->
            done("dnd-on") { Actions.dnd(it, true) }
        Regex("^(?:turn off|disable|switch off|stop|deactivate) (?:the )?$DND$|^$DND off$").matches(t) ->
            done("dnd-off") { Actions.dnd(it, false) }
        else -> null
    }

    // ---- Navigating the UI (Accessibility) ---------------------------------------------------

    private fun nav(id: String, label: String, f: (AjaxAccessibilityService) -> Result) = Match(id) {
        if (AjaxAccessibilityService.instance == null) Outcome.Done(needsAccessibility)
        else Outcome.AfterHide(label) { AjaxAccessibilityService.instance?.let(f) ?: needsAccessibility }
    }

    private fun nav(t: String): Match? = when {
        Regex("^(?:go )?back$").matches(t) ->
            nav("nav-back", "Going back") { it.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK, "Back") }
        Regex("^(?:go )?home$|^go to (?:the )?home(?: screen)?$|^home screen$").matches(t) ->
            nav("nav-home", "Going home") { it.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME, "Home") }
        Regex("^(?:recents|recent apps|show recent apps|open recent apps|show recents|open recents)$").matches(t) ->
            nav("nav-recents", "Recent apps") { it.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS, "Recent apps") }
        Regex("^(?:open |show |pull down )?(?:the )?notifications(?: panel| shade| bar)?$|^notification (?:panel|shade)$").matches(t) ->
            nav("nav-notifications", "Notifications") { it.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notifications") }
        Regex("^(?:open |show |pull down )?(?:the )?quick settings(?: panel)?$").matches(t) ->
            nav("nav-quick-settings", "Quick settings") { it.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings") }
        Regex("^(?:take (?:a )?)?screenshot$").matches(t) ->
            nav("nav-screenshot", "Taking a screenshot") { it.globalApi28(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Screenshot taken") }
        Regex("^lock (?:the |my )?(?:phone|screen|device)$").matches(t) ->
            nav("nav-lock", "Locking the phone") { it.globalApi28(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Locked") }
        Regex("^scroll (up|down)$").matches(t) -> {
            val down = t.endsWith("down")
            nav("nav-scroll-${if (down) "down" else "up"}", "Scrolling") { it.scroll(down) }
        }
        else -> null
    }

    // ---- Info --------------------------------------------------------------------------------

    private fun info(t: String): Match? = when {
        Regex("^(?:whats|what is|how much is|how is)(?: my| the)? battery(?: level| life| percentage)?$|^battery (?:level|status|percentage)$|^how much battery(?: do i have| is left| left)?$").matches(t) ->
            done("info-battery") { Actions.battery(it) }
        Regex("^(?:whats|what is) the time$|^what time is it(?: now)?$|^time$|^tell me the time$|^current time$").matches(t) ->
            done("info-time") { Actions.time() }
        Regex("^(?:whats|what is) (?:the |todays )?date(?: today)?$|^what day is it(?: today)?$").matches(t) ->
            done("info-date") { Actions.date() }
        Regex("^(?:read|check) (?:my )?notifications$").matches(t) ->
            done("info-notifications") { Actions.readNotifications(it) }
        else -> null
    }

    // ---- Time --------------------------------------------------------------------------------

    private fun alarm(t: String): Match? {
        val m = Regex("^(?:(?:set|create|make)(?: an?)? alarm (?:for|at)|wake me(?: up)? at) (\\d{1,2})(?: (\\d{2}))?(?: (am|pm))?$")
            .matchEntire(t) ?: return null
        val hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        val mer = m.groupValues[3].ifEmpty { null }
        return done("alarm") { Actions.alarm(it, hour, minute, mer) }
    }

    private fun timer(t: String): Match? {
        val unit = "(second|seconds|minute|minutes|hour|hours)"
        val m = Regex("^(?:set (?:a |an )?)?timer(?: for)? (\\d+) $unit$").matchEntire(t)
            ?: Regex("^(?:set (?:a |an )?)?(\\d+) $unit timer$").matchEntire(t)
            ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        val u = m.groupValues[2]
        return done("timer") { Actions.timer(it, n, u) }
    }

    // ---- Phone -------------------------------------------------------------------------------

    /** Uses the raw text so the message keeps its wording. Always asks before sending. */
    private fun sms(raw: String): Match? {
        val lite = raw.trim().replace(Regex("\\s+"), " ")
            .replace(Regex("^(hey |ok |okay )?(ajax )?(please )?(can you |could you )?(please )?", RegexOption.IGNORE_CASE), "")
        val m = Regex(
            "^(?:text|message|sms|send (?:a )?(?:text|message) to) (.+?) (?:saying|that says|that|say) (.+)$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(lite) ?: return null
        val name = m.groupValues[1].trim().trim('.', ',')
        val message = m.groupValues[2].trim()
        if (name.isEmpty() || message.isEmpty()) return null
        return Match("text") { Actions.prepareText(it, name, message) }
    }

    private fun call(t: String): Match? {
        val m = Regex("^(?:call|dial|phone|ring) (.+)$").matchEntire(t) ?: return null
        val who = m.groupValues[1].trim()
        if (Regex("^[0-9 ]+$").matches(who)) {
            return done("call-number") { Result(false, "Calling phone numbers isn't supported yet. Say a contact name.") }
        }
        return done("call") { Actions.call(it, who) }
    }

    // ---- Settings pages and apps -------------------------------------------------------------

    private fun appInfo(t: String): Match? {
        val m = Regex("^(?:open |show )?app info (?:for|of) (.+)$").matchEntire(t)
            ?: Regex("^(?:open |show )?(.+) app info$").matchEntire(t)
            ?: return null
        val name = m.groupValues[1].trim()
        return done("app-info") { Actions.appInfo(it, name) }
    }

    private fun settings(t: String): Match? {
        if (Regex("^(open |show )?settings$").matches(t)) return page("main", Settings.ACTION_SETTINGS)
        val m = Regex("^(?:open |show |go to )?(?:the )?(.+) settings$").matchEntire(t) ?: return null
        val name = m.groupValues[1]
        val action = pages[name] ?: return null
        return page(name, action)
    }

    private fun page(name: String, action: String) =
        done("settings:$name") { Actions.openSettings(it, action, name) }

    private fun openApp(t: String): Match? {
        val m = Regex("^(?:open|launch|start|run) (?:the )?(.+?)(?: app)?$").matchEntire(t) ?: return null
        val name = m.groupValues[1].trim()
        return done("open-app") { Actions.openApp(it, name) }
    }
}
