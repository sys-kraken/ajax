package com.ajax.assistant

import android.content.Context

/**
 * Stage 3: understands loose phrasing ("could you flip the torch on") with the on-device model.
 *
 * Safety by construction: the model never acts. Its output is constrained by a grammar to exactly
 * one canonical command phrase from [Catalog] (or "none"), and that phrase then goes through the
 * normal regex [CommandRouter] like anything the user said. Words it fills in (names, places,
 * labels, text) must be words the user actually said, and the grammar only offers commands the
 * request gives some clue for, so it can't answer "restart the phone" with "turn on battery saver".
 */
object IntentModel {

    fun available(ctx: Context): Boolean = ModelManager.isReady(ctx)

    /** [done] receives a canonical phrase the router accepts, or null. Main thread. */
    fun interpret(ctx: Context, heard: String, done: (String?) -> Unit) {
        val request = CommandRouter.normalize(heard)
        if (request.isEmpty()) { done(null); return }
        ModelManager.complete(ctx, Catalog.prefix, Catalog.suffix(request), Catalog.grammar(request), maxTokens = 48) { out ->
            done(out?.let { Catalog.accept(it, request) })
        }
    }

    /** The command table the model chooses from. Pure Kotlin so it is unit-tested on the JVM. */
    object Catalog {
        /** Fixed commands, each exactly as [CommandRouter] understands it. */
        val fixed: List<String> by lazy {
            listOf(
                "volume up", "volume down", "volume max", "mute", "unmute",
                "play music", "pause music", "next song", "previous song",
                "vibrate mode", "silent mode", "sound mode",
                "flashlight on", "flashlight off",
                "turn on wifi", "turn off wifi", "turn on bluetooth", "turn off bluetooth",
                "turn on mobile data", "turn off mobile data", "turn on airplane mode", "turn off airplane mode",
                "turn on location", "turn off location", "turn on battery saver", "turn off battery saver",
                "turn on nfc", "turn off nfc", "auto rotate on", "auto rotate off",
                "brightness max", "brightness min",
                "do not disturb on", "do not disturb off",
                "go back", "go home", "recent apps", "open notifications", "quick settings",
                "take a screenshot", "lock the phone", "restart the phone", "turn off the phone", "scroll up", "scroll down",
                "take a photo", "record a video",
                "whats my battery", "what time is it", "whats the date", "read my notifications",
                "how much storage do i have",
                "open settings",
            ) + CommandRouter.settingsPageNames.sorted().map { "$it settings" }
        }

        /** Commands with a slot, as shown to the model. */
        private val slotted = listOf(
            "brightness <number> percent",
            "volume <number> percent",
            "set an alarm for <hour> <minute> am|pm",
            "timer <number> seconds|minutes|hours",
            "open <app>",
            "app info for <app>",
            "call <contact>",
            "text <contact> saying <message>",
            "draft a message to <contact> saying <message>",
            "search for <words>",
            "navigate to <place>",
            "tap <button label on screen>",
            "type <text>",
        )

        private val examples = listOf(
            "could you flip the torch on" to "flashlight on",
            "its too dark i cant see the screen" to "brightness max",
            "kill the wifi" to "turn off wifi",
            "wake me up tomorrow at half past six" to "set an alarm for 6 30 am",
            "remind me in ten minutes" to "timer 10 minutes",
            "give mom a ring" to "call mom",
            "fire up spotify" to "open spotify",
            "do you know what time it is" to "what time is it",
            "how much juice is left on my phone" to "whats my battery",
            "where do i change my wifi network" to "wifi settings",
            "i want to hear the next track" to "next song",
            "how do i get to the airport" to "navigate to the airport",
            "find a pizza place near me" to "search for pizza place near me",
            "hit the send button" to "tap send",
            "whats the weather like" to "none",
        )

        /** Fixed part of the prompt: identical every call, so the native side caches it. */
        val prefix: String by lazy {
            buildString {
                append("<|im_start|>system\n")
                append("You turn a phone user's request into exactly one command from this list, ")
                append("copying names, places and numbers from the request. Questions about the time, date, ")
                append("battery or storage get the matching question command, not a settings page. ")
                append("If nothing fits, answer none.\n")
                (fixed + slotted).forEach { append("- ").append(it).append('\n') }
                append("<|im_end|>\n")
                for ((q, a) in examples) {
                    append("<|im_start|>user\n").append(q).append("<|im_end|>\n")
                    append("<|im_start|>assistant\n").append(a).append("<|im_end|>\n")
                }
                append("<|im_start|>user\n")
            }
        }

        fun suffix(request: String) = "$request<|im_end|>\n<|im_start|>assistant\n"

        /**
         * Words a request must contain (as word starts) before a fixed command is offered to the model:
         * the first entry whose key is in the command applies. Generous on purpose; it only has to
         * rule out commands that have nothing to do with the request.
         */
        private val fixedClues = listOf(
            "flashlight" to "flash|torch|light|lamp|dark|see",
            "quick settings" to "quick|settings|panel|toggles",
            "open notifications" to "notif|alert|came in|messages",
            "read my notifications" to "notif|alert|came in|messages|read",
            " settings" to "setting|change|manage|configure|adjust",
            "volume" to "volume|sound|loud|quiet|soft|hear|audio|crank",
            "mute" to "mute|unmute|quiet|silen|shut|sound",
            "music" to "music|song|track|tune|play|album|pause|hold|resume|stop|audio",
            " song" to "song|track|tune|skip|next|previous|last|back",
            "vibrate" to "vibrat|buzz",
            "silent" to "silen|quiet|ring|mute",
            "sound mode" to "sound|ring|normal|unsilence",
            "wifi" to "wifi|wlan|internet|online|network|connect",
            "bluetooth" to "bluetooth|headphone|earbud|headset|airpod|speaker|pair",
            "mobile data" to "data|mobile|cellular|internet|online|network|4g|5g|lte",
            "airplane" to "airplane|plane|flight|fly",
            "location" to "location|gps|track",
            "battery saver" to "battery|power|saver|saving|juice|dying|charge",
            "nfc" to "nfc|contactless",
            "rotate" to "rotat|sideways|landscape|portrait",
            "brightness" to "bright|dim|dark|screen|see|glare|blind|display",
            "do not disturb" to "disturb|dnd|interrupt|focus|bother|peace",
            "go back" to "back|previous|return",
            "go home" to "home|launcher|main screen",
            "recent apps" to "recent|open apps|switcher|multitask",
            "scroll" to "scroll|down|up|further|page|more",
            "screenshot" to "screenshot|screen|capture|grab",
            "lock the phone" to "lock|sleep",
            "restart" to "restart|reboot",
            "turn off the phone" to "power|shut|turn off|switch off",
            "take a photo" to "photo|picture|pic|selfie|camera|snap|shot",
            "record a video" to "video|record|film|clip|movie",
            "battery" to "battery|charge|juice|power",
            "what time" to "time|clock|hour|late|oclock",
            "whats the date" to "date|day|today|month|year",
            "storage" to "storage|space|memory|room|full",
        ).map { (key, clue) -> key to Regex("\\b(?:$clue)") }

        private class Slot(val rule: String, clue: String?) {
            val clue = clue?.let { Regex("\\b(?:$it)") }
        }

        private val slotRules = listOf(
            Slot("\"brightness \" num \" percent\"", "bright|dim|screen|display"),
            Slot("\"volume \" num \" percent\"", "volume|sound|loud|quiet|audio"),
            Slot("\"set an alarm for \" hour (\" \" minute)? (\" am\" | \" pm\")?", "alarm|wake|get up|morning|clock"),
            Slot("\"timer \" num \" \" (\"seconds\" | \"minutes\" | \"hours\")", "timer|remind|countdown|count down|second|minute|hour"),
            Slot("\"open \" words", "open|launch|start|fire|run|watch|use|show|get me|go to|app|play|load"),
            Slot("\"app info for \" words", "info|details|about"),
            Slot("\"call \" words", "call|ring|phone|dial"),
            Slot("\"text \" words \" saying \" text", "text|message|sms|send|tell|say"),
            Slot("\"draft a message to \" words \" saying \" text", "draft|write|compose|prepare"),
            Slot("\"search for \" words", null),
            Slot("\"navigate to \" words", "navigat|direction|get to|get home|take me|drive|route|map|way|go to"),
            Slot("\"tap \" words", "tap|click|press|hit|select|push|button"),
            Slot("\"type \" text", "type|write|enter|input"),
            Slot("\"none\"", null),
        )

        /** GBNF for this [request]: the model can only emit one of these shapes. */
        fun grammar(request: String): String {
            val fixedAlts = fixed.filter { phrase ->
                val clue = fixedClues.firstOrNull { it.first in phrase }?.second
                clue == null || clue.containsMatchIn(request)
            }.map { "\"$it\"" }
            val slotAlts = slotRules.filter { it.clue == null || it.clue.containsMatchIn(request) }.map { it.rule }
            return buildString {
                append("root ::= ").append((fixedAlts + slotAlts).joinToString(" | ")).append('\n')
                append("num ::= [0-9] [0-9]? [0-9]?\n")
                append("hour ::= [0-9] [0-9]?\n")
                append("minute ::= [0-9] [0-9]\n")
                append("words ::= [a-z0-9] [a-z0-9 ]{0,39}\n")
                append("text ::= [a-z0-9] [a-z0-9 ,.?!]{0,159}\n")
            }
        }

        /** Slots whose words must come from the request (group 1, and group 2 if present). */
        private val copied = listOf(
            Regex("^open (.+)$"), Regex("^app info for (.+)$"), Regex("^call (.+)$"),
            Regex("^text (.+?) saying (.+)$"), Regex("^draft a message to (.+?) saying (.+)$"), Regex("^search for (.+)$"), Regex("^navigate to (.+)$"),
            Regex("^tap (.+)$"), Regex("^type (.+)$"),
        )

        private fun words(s: String) = s.split(' ').map { it.filter(Char::isLetterOrDigit) }.filter { it.isNotEmpty() }

        /**
         * Final gate on model output: must be a real command, and anything it filled in must be words
         * the user actually said (a small model can otherwise invent a contact, app, place or text).
         */
        fun accept(output: String, request: String): String? {
            val phrase = output.trim().replace(Regex("\\s+"), " ")
            if (phrase.isEmpty() || phrase == "none") return null
            if (phrase !in fixed) {
                val said = words(request).toSet()
                for (r in copied) {
                    val m = r.matchEntire(phrase) ?: continue
                    val filled = m.groupValues.drop(1).flatMap(::words)
                    if (!filled.all { it in said }) return null
                    break
                }
            }
            return phrase.takeIf { CommandRouter.route(it) != null }
        }
    }
}
