package com.ajax.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings

/** Turns recognised text into one action. Keyword/regex matching only. */
object CommandRouter {

    /**
     * [sure] = false means this match can only give a hint or error (e.g. "say on or off", app not
     * installed). The Brain then lets the on-device model try first, keeping this as the fallback.
     */
    class Match(
        val id: String,
        val sure: (Context) -> Boolean = { true },
        /** Only a hint/error; never the real answer (lets tests classify matches without a phone). */
        val hintOnly: Boolean = false,
        val start: (Context) -> Outcome,
    ) {
        /** The user's attention moves elsewhere (another app, a call, music): stop listening afterwards. */
        val endsConversation: Boolean get() = id in handOffs || id.startsWith("settings:")
    }

    private val handOffs = setOf(
        "open-app", "app-info", "camera-photo", "camera-video", "search", "navigate", "call", "draft",
        "media-playing", "media-next-track", "media-previous-track",
        "nav-recents", "nav-notifications", "nav-quick-settings", "nav-lock", "nav-restart", "nav-power-off",
    )

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

    /** Settings pages Ajax can open by name (used by the model's command table). */
    val settingsPageNames: Set<String> get() = pages.keys

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
        // Trailing filler never changes a command: "what is the time right now", "wifi off now".
        s = s.replace(Regex("\\s+(?:right now|now|for me|please)$"), "").trim()
        return s
    }

    private val smallNumbers = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val tensNumbers = listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    /**
     * Number words to digits, for commands that take a number ("brightness zero percent"). Not part of
     * [normalize]: names and search words must keep their wording ("search for one direction").
     */
    private fun digits(t: String): String {
        val units = smallNumbers.subList(1, 10).joinToString("|")
        var s = Regex("\\b(?:a|one) hundred\\b").replace(t, "100")
        s = Regex("\\b(${tensNumbers.joinToString("|")})(?: ($units))?\\b").replace(s) { m ->
            val unit = smallNumbers.indexOf(m.groupValues[2]).coerceAtLeast(0)
            ((tensNumbers.indexOf(m.groupValues[1]) + 2) * 10 + unit).toString()
        }
        return Regex("\\b(${smallNumbers.joinToString("|")})\\b").replace(s) { smallNumbers.indexOf(it.value).toString() }
    }

    fun route(raw: String): Match? {
        val t = normalize(raw)
        if (t.isEmpty()) return null
        return sms(raw) ?: draft(raw) ?: volume(t) ?: media(t) ?: ringer(t) ?: torch(t) ?: rotate(t) ?: toggle(t) ?: brightness(t) ?: dnd(t)
            ?: nav(t) ?: screen(t) ?: info(t) ?: alarm(t) ?: timer(t) ?: camera(t) ?: search(t)
            ?: navigate(t) ?: appInfo(t) ?: settings(t) ?: call(t) ?: openApp(t)
    }

    private fun done(id: String, f: (Context) -> Result) = Match(id) { Outcome.Done(f(it)) }

    /** A hint-only answer: shown only if the model can't do better. */
    private fun hint(id: String, message: String) =
        Match(id, sure = { false }, hintOnly = true) { Outcome.Done(Result(false, message)) }

    // ---- Sound -------------------------------------------------------------------------------

    private fun volume(t: String): Match? = when {
        Regex("^(?:volume up|louder|make it louder|(?:turn|crank|pump) (?:it|the volume|the sound|the music) up|" +
            "turn up (?:the )?(?:volume|sound|music)|(?:increase|raise|boost) (?:the )?(?:volume|sound)|" +
            "i cant hear(?: anything| it)?)$").matches(t) ->
            done("volume-up") { Actions.volume(it, AudioManager.ADJUST_RAISE, "Volume up") }
        Regex("^(?:volume down|quieter|softer|make it (?:quieter|softer)|turn (?:it|the volume|the sound|the music) down|" +
            "turn down (?:the )?(?:volume|sound|music)|(?:decrease|lower|reduce) (?:the )?(?:volume|sound)(?: a (?:little|bit))?)$").matches(t) ->
            done("volume-down") { Actions.volume(it, AudioManager.ADJUST_LOWER, "Volume down") }
        Regex("^(?:mute|mute (?:the )?(?:sound|volume|phone|it)|(?:turn|switch) (?:the )?sound off|" +
            "(?:turn|switch) off (?:the )?sound|sound off|shut up|be quiet)$").matches(t) ->
            done("mute") { Actions.volume(it, AudioManager.ADJUST_MUTE, "Muted") }
        Regex("^(unmute|unmute (the )?(sound|volume|phone))$").matches(t) ->
            done("unmute") { Actions.volume(it, AudioManager.ADJUST_UNMUTE, "Unmuted") }
        Regex("^(?:volume (?:max|maximum|full)|max(?:imum)? volume|full volume)$").matches(t) ->
            done("volume-level") { Actions.volumeLevel(it, 100) }
        else -> Regex("^(?:set |change |put |turn )?(?:the )?volume (?:to |at )?(\\d{1,3})(?: percent)?$").matchEntire(digits(t))?.let { m ->
            val p = m.groupValues[1].toInt()
            if (p in 0..100) done("volume-level") { Actions.volumeLevel(it, p) }
            else hint("volume-invalid", "Say a volume from 0 to 100 percent.")
        }
    }

    private fun media(t: String): Match? {
        val key = when {
            Regex("^(?:play|resume|play (?:some |the )?music|play something|(?:put|turn) (?:some |the )?music on|" +
                "start the music|(?:resume|continue) (?:the )?(?:music|song|playback))$").matches(t) ->
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY to "Playing"
            Regex("^(?:pause|pause (?:the )?(?:music|song|it)|(?:stop|hold) (?:the )?(?:music|song))$").matches(t) ->
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE to "Paused"
            Regex("^(?:next|skip|next (?:one|song|track)|skip (?:this one|it|ahead)|skip (?:this |the )?(?:song|track)|" +
                "play the next (?:song|track))$").matches(t) ->
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT to "Next track"
            Regex("^(?:previous|previous (?:one|song|track)|last (?:song|track)|go back a (?:song|track)|" +
                "play the previous (?:song|track))$").matches(t) ->
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS to "Previous track"
            else -> return null
        }
        return done("media-${key.second.lowercase().replace(' ', '-')}") { Actions.media(it, key.first, key.second) }
    }

    private fun rotate(t: String): Match? = when {
        Regex("^(?:turn on |enable )?auto ?rotat(?:e|ion)(?: on)?$|^unlock (?:the )?rotation$|^rotation lock off$|" +
            "^let the screen rotate$|^(?:allow|enable) (?:screen )?rotation$").matches(t) ->
            done("rotate-on") { Actions.autoRotate(it, true) }
        Regex("^(?:turn off |disable )auto ?rotat(?:e|ion)$|^auto ?rotat(?:e|ion) off$|^lock (?:the )?rotation$|^rotation lock on$|" +
            "^(?:stop|dont let) the screen (?:from )?rotat(?:e|ing)$").matches(t) ->
            done("rotate-off") { Actions.autoRotate(it, false) }
        else -> null
    }

    private fun camera(t: String): Match? = when {
        Regex("^(?:take|snap) (?:a )?(?:photo|picture|pic|selfie)$|^(?:open|get me to|go to) (?:the )?camera(?: app)?$|^camera$").matches(t) ->
            done("camera-photo") { Actions.camera(it, false) }
        Regex("^(?:record|take|shoot|film) (?:a )?(?:video|something)$|^start recording$").matches(t) ->
            done("camera-video") { Actions.camera(it, true) }
        else -> null
    }

    private fun search(t: String): Match? {
        val m = Regex("^(?:search|google|look up|search the web) (?:for )?(.+)$").matchEntire(t) ?: return null
        val q = m.groupValues[1].trim()
        return done("search") { Actions.webSearch(it, q) }
    }

    private fun navigate(t: String): Match? {
        val m = Regex("^(?:navigate|directions|get directions|drive|take me|how do i get) to (.+)$").matchEntire(t)
            ?: Regex("^(?:navigate|take me|how do i get|drive) (home)$").matchEntire(t)
            ?: return null
        val place = m.groupValues[1].trim()
        if (place.endsWith(" settings")) return null // "take me to wifi settings" is a settings page
        return done("navigate") { Actions.navigate(it, place) }
    }

    /** Acts on whatever app is on screen: tap a button by its label, or type into the focused field. */
    private fun screen(t: String): Match? {
        Regex("^(?:tap|click|press|hit|select)(?: on)? (?:the )?(.+?)(?: button)?$").matchEntire(t)?.let { m ->
            val label = m.groupValues[1].trim()
            return nav("tap", "Tapping $label") { it.tap(label) }
        }
        Regex("^type (.+)$").matchEntire(t)?.let { m ->
            val text = m.groupValues[1].trim()
            return nav("type", "Typing") { it.type(text) }
        }
        return null
    }

    private fun ringer(t: String): Match? = when {
        Regex("^(?:vibrate|vibrate mode|vibration mode|vibrate only|set to vibrate|" +
            "(?:put|set) (?:it|the phone|my phone) (?:on|to) vibrate)$").matches(t) ->
            done("ringer-vibrate") { Actions.ringer(it, AudioManager.RINGER_MODE_VIBRATE, "Vibrate mode") }
        Regex("^(?:silent|silent mode|silence (?:the |my )?phone|(?:put|set) (?:it|the phone|my phone) (?:on|to) silent|" +
            "no ringing|dont ring|stop ringing)$").matches(t) ->
            done("ringer-silent") { Actions.ringer(it, AudioManager.RINGER_MODE_SILENT, "Silent mode") }
        Regex("^(sound mode|normal mode|ring mode|ringer on|unsilence|unsilence (the )?phone)$").matches(t) ->
            done("ringer-normal") { Actions.ringer(it, AudioManager.RINGER_MODE_NORMAL, "Sound mode") }
        else -> null
    }

    // ---- Hardware ----------------------------------------------------------------------------

    private val torchWord = Regex("\\b(flashlight|flash light|torch|light)\\b")

    private fun torch(t: String): Match? {
        if (!torchWord.containsMatchIn(t)) return null
        if (Regex("\\blight (?:mode|theme)\\b").containsMatchIn(t)) return null // display theme, not the torch
        val on = Regex("\\b(?:on|need|want)\\b").containsMatchIn(t)
        val off = Regex("\\b(?:off|kill|stop|cut)\\b").containsMatchIn(t)
        return when {
            on && !off -> done("torch-on") { Actions.torch(it, true) }
            off && !on -> done("torch-off") { Actions.torch(it, false) }
            Regex("^(?:toggle |switch )?(?:the )?(?:flashlight|flash light|torch)$").matches(t) ->
                done("torch-toggle") { Actions.torchToggle(it) }
            else -> hint("torch-unclear", "Say flashlight on, or flashlight off.")
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

    private const val INTERNET_PANEL = "android.settings.panel.action.INTERNET_CONNECTIVITY"

    private fun toggleFor(name: String): Toggle? = when (name) {
        "wifi" -> Toggle(Settings.ACTION_WIFI_SETTINGS, listOf("Wi-Fi", "Wifi", "WLAN"), true, "Wi-Fi", SwitchState::wifi)
        "bluetooth" -> Toggle(Settings.ACTION_BLUETOOTH_SETTINGS, listOf("Bluetooth"), true, "Bluetooth", SwitchState::bluetooth)
        "mobile data", "cellular data", "data" -> Toggle(
            INTERNET_PANEL,
            listOf("Mobile data"), false, "Mobile data",
        )
        "airplane mode", "airplane", "flight mode" -> Toggle(
            Settings.ACTION_AIRPLANE_MODE_SETTINGS, listOf("Airplane mode", "Flight mode"), true, "Airplane mode", SwitchState::airplane,
        )
        "location", "gps" -> Toggle(
            Settings.ACTION_LOCATION_SOURCE_SETTINGS, listOf("Use location", "Location"), true, "Location", SwitchState::location,
        )
        "battery saver", "power saving", "power saving mode" -> Toggle(
            Settings.ACTION_BATTERY_SAVER_SETTINGS, listOf("Use Battery Saver", "Battery Saver", "Power saving"), true,
            "Battery saver", SwitchState::batterySaver,
        )
        "nfc" -> Toggle(Settings.ACTION_NFC_SETTINGS, listOf("NFC", "Use NFC", "NFC and contactless payments"), true, "NFC", SwitchState::nfc)
        else -> null
    }

    private const val TOGGLE_NAMES = "wifi|bluetooth|mobile data|cellular data|data|airplane mode|airplane|flight mode|" +
        "location|gps|battery saver|power saving mode|power saving|nfc"

    /** Switches Android won't let apps flip: open the page, then tap the switch with Accessibility. */
    private fun toggle(t: String): Match? {
        val on = "turn on|switch on|turn back on|enable|activate|connect to|start using"
        val off = "turn off|switch off|disable|deactivate|disconnect from|disconnect|stop using|kill|cut"
        val m = Regex("^(?:$on) (?:the |my )?($TOGGLE_NAMES)$").matchEntire(t)?.let { it.groupValues[1] to "on" }
            ?: Regex("^(?:$off) (?:the |my )?($TOGGLE_NAMES)$").matchEntire(t)?.let { it.groupValues[1] to "off" }
            ?: Regex("^(?:turn|switch) (?:the |my )?($TOGGLE_NAMES)(?: back)? (on|off)$").matchEntire(t)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: Regex("^($TOGGLE_NAMES)(?: back)? (on|off)$").matchEntire(t)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: Regex("\\bsav(?:e|ing) (?:the )?(?:battery|power)$").find(t)?.let { "battery saver" to "on" }
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
                        // The internet panel exists from Android 10; older phones get the mobile network page.
                        val action = if (tg.action == INTERNET_PANEL && Build.VERSION.SDK_INT < 29) {
                            Settings.ACTION_DATA_ROAMING_SETTINGS
                        } else {
                            tg.action
                        }
                        ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
        val prefix = "^(?:set |change |put |turn )?(?:the |my )?(?:screen )?brightness (?:to |at )?"
        Regex("$prefix(\\d{1,3})(?: percent)?$").matchEntire(digits(t))?.let { m ->
            val p = m.groupValues[1].toInt()
            if (p in 0..100) return done("brightness") { Actions.brightness(it, p) }
            return hint("brightness-invalid", "Say a brightness from 0 to 100 percent.")
        }
        if (Regex("$prefix(?:max|maximum|full)$").matches(t)) return done("brightness") { Actions.brightness(it, 100) }
        if (Regex("$prefix(?:min|minimum|lowest)$").matches(t)) return done("brightness") { Actions.brightness(it, 0) }
        return null
    }

    private const val DND = "(?:do not disturb|dont disturb|dnd)(?: mode)?"

    private fun dnd(t: String): Match? = when {
        Regex("^(?:turn on|enable|switch on|start|activate) (?:the )?$DND$|^$DND on$|^dont disturb me$|" +
            "^(?:no|i dont want any) interruptions$").matches(t) ->
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

    /** Android lets no ordinary app restart the phone, so Ajax presses the system power menu, after a yes. */
    private fun powerMenu(id: String, question: String, label: String, button: String) = Match(id) {
        if (AjaxAccessibilityService.instance == null) Outcome.Done(needsAccessibility)
        else Outcome.Ask(question, endsConversation = true) {
            Outcome.AfterHide(label) { AjaxAccessibilityService.instance?.pressPowerMenu(button) ?: needsAccessibility }
        }
    }

    private fun nav(t: String): Match? = when {
        Regex("^(?:go |press )?back$").matches(t) ->
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
        Regex("^lock (?:the |my )?(?:phone|screen|device|it)$|^lock it$|^(?:turn|switch) off (?:the |my )?(?:screen|display)$|" +
            "^(?:turn|switch) (?:the |my )?(?:screen|display) off$|^screen off$|^(?:put (?:the |my )?(?:phone|screen) to )?sleep$").matches(t) ->
            nav("nav-lock", "Locking the phone") { it.globalApi28(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Locked") }
        Regex("^(?:restart|reboot)(?: (?:the |my )?(?:phone|device)| it)?$").matches(t) ->
            powerMenu("nav-restart", "Restart the phone?", "Restarting", "Restart")
        Regex("^(?:power off|shut down|shutdown)(?: (?:the |my )?(?:phone|device))?$|^(?:power menu|power options)$|" +
            "^(?:turn|switch) off (?:the |my )?(?:phone|device)$|^(?:turn|switch) (?:the |my )?(?:phone|device) off$").matches(t) ->
            powerMenu("nav-power-off", "Power off the phone?", "Powering off", "Power off")
        Regex("^scroll (?:a bit |a little )?(up|down|further|more)$").matches(t) -> {
            val down = !t.endsWith("up")
            nav("nav-scroll-${if (down) "down" else "up"}", "Scrolling") { it.scroll(down) }
        }
        else -> null
    }

    // ---- Info --------------------------------------------------------------------------------

    /** A question ("what's…", "how much…", "is my…", "do you…"), as opposed to a command. */
    private val question = Regex("^(?:how|hows|what|whats|which|when|is|are|am|check|tell me|do you|do i|any)\\b")

    private fun has(t: String, words: String) = Regex("\\b(?:$words)\\b").containsMatchIn(t)

    private fun info(t: String): Match? {
        val q = question.containsMatchIn(t)
        return when {
            Regex("^battery(?: level| status| percentage)?$").matches(t) ||
                q && has(t, "battery|charged|charge") && !has(t, "saver|saving|save|settings") ->
                done("info-battery") { Actions.battery(it) }
            Regex("^(?:time|current time)$").matches(t) ||
                q && has(t, "time") && !has(t, "timer|settings|zone|alarm") ->
                done("info-time") { Actions.time() }
            Regex("^(?:date|todays date)$").matches(t) ||
                q && has(t, "date|day") && !has(t, "settings|update|alarm|weather") ->
                done("info-date") { Actions.date() }
            Regex("^(?:read|check) (?:my )?(?:notifications|messages)$").matches(t) ||
                q && has(t, "notifications|new messages") ->
                done("info-notifications") { Actions.readNotifications(it) }
            q && has(t, "storage|space") || Regex("running out of (?:space|storage)").containsMatchIn(t) ->
                done("info-storage") { Actions.storage() }
            else -> null
        }
    }

    // ---- Time --------------------------------------------------------------------------------

    private fun alarm(t: String): Match? {
        val m = Regex("^(?:(?:set|create|make)(?: an?)? alarm (?:for|at)|wake me(?: up)? at) (\\d{1,2})(?: (\\d{2}))?(?: (am|pm))?$")
            .matchEntire(digits(t)) ?: return null
        val hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        val mer = m.groupValues[3].ifEmpty { null }
        return done("alarm") { Actions.alarm(it, hour, minute, mer) }
    }

    private fun timer(t: String): Match? {
        val unit = "(second|seconds|minute|minutes|hour|hours)"
        val d = digits(t)
        val m = Regex("^(?:set (?:a |an )?)?timer(?: for)? (\\d+) $unit$").matchEntire(d)
            ?: Regex("^(?:set (?:a |an )?)?(\\d+) $unit timer$").matchEntire(d)
            ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        val u = m.groupValues[2]
        return done("timer") { Actions.timer(it, n, u) }
    }

    // ---- Phone -------------------------------------------------------------------------------

    private fun lite(raw: String) = raw.trim().replace(Regex("\\s+"), " ")
        .replace(Regex("^(hey |ok |okay )?(ajax )?(please )?(can you |could you )?(please )?", RegexOption.IGNORE_CASE), "")

    /** Uses the raw text so the message keeps its wording. Always asks before sending. */
    private fun sms(raw: String): Match? {
        val lite = lite(raw)
        val m = Regex(
            "^(?:text|message|sms|send (?:a )?(?:text|message) to) (.+?) (?:saying|that says|that|say) (.+)$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(lite) ?: return null
        val name = m.groupValues[1].trim().trim('.', ',')
        val message = m.groupValues[2].trim()
        if (name.isEmpty() || message.isEmpty()) return null
        return Match("text") { Actions.prepareText(it, name, message) }
    }

    /** Opens the messaging app with the message filled in; the user sends it. The body is optional. */
    private fun draft(raw: String): Match? {
        val m = Regex(
            "^(?:draft|write|compose|prepare)(?: me)?(?: an?)?(?: (?:text|sms|message))?(?: message)? to (.+?)(?: (?:saying|that says|that|say) (.+))?$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(lite(raw)) ?: return null
        val name = m.groupValues[1].trim().trim('.', ',')
        val message = m.groupValues[2].trim()
        if (name.isEmpty()) return null
        return done("draft") { Actions.draftText(it, name, message) }
    }

    private fun call(t: String): Match? {
        val m = Regex("^(?:call|dial|phone|ring) (.+)$").matchEntire(t) ?: return null
        val who = m.groupValues[1].trim()
        if (Regex("^[0-9 ]+$").matches(who)) {
            return hint("call-number", "Calling phone numbers isn't supported yet. Say a contact name.")
        }
        return done("call") { Actions.call(it, who) }
    }

    // ---- Settings pages and apps -------------------------------------------------------------

    private fun appInfo(t: String): Match? {
        val m = Regex("^(?:open |show )?app info (?:for|of) (.+)$").matchEntire(t)
            ?: Regex("^(?:open |show )?(.+) app info$").matchEntire(t)
            ?: return null
        val name = m.groupValues[1].trim()
        return Match("app-info", sure = { Actions.hasApp(it, name) }) { Outcome.Done(Actions.appInfo(it, name)) }
    }

    private fun settings(t: String): Match? {
        if (Regex("^(?:open |show )?(?:the )?(?:phone |system |device )?settings$").matches(t)) {
            return page("main", Settings.ACTION_SETTINGS)
        }
        val m = Regex("^(?:open |show |go to |take me to |change )?(?:the |my )?(.+) settings$").matchEntire(t) ?: return null
        val name = m.groupValues[1]
        val action = pages[name] ?: return null
        return page(name, action)
    }

    private fun page(name: String, action: String) =
        done("settings:$name") { Actions.openSettings(it, action, name) }

    private fun openApp(t: String): Match? {
        val m = Regex("^(?:open|launch|start|run) (?:the )?(.+?)(?: app)?$").matchEntire(t) ?: return null
        val name = m.groupValues[1].trim()
        // "start a timer for five minutes" also matches here: only sure if such an app exists.
        return Match("open-app", sure = { Actions.hasApp(it, name) }) { Outcome.Done(Actions.openApp(it, name)) }
    }
}
