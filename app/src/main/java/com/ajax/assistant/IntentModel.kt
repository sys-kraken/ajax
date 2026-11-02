package com.ajax.assistant

import android.content.Context

/**
 * Stage 3: understands loose phrasing ("could you flip the torch on") with the on-device model.
 *
 * Safety by construction: the model never acts. Its output is constrained by a grammar to exactly
 * one canonical command phrase from [Catalog] (or "none"), and that phrase then goes through the
 * normal regex [CommandRouter] like anything the user said. Words it fills in (names, places,
 * labels, text) must be words the user actually said.
 */
object IntentModel {

    fun available(ctx: Context): Boolean = ModelManager.isReady(ctx)

    /** [done] receives a canonical phrase the router accepts, or null. Main thread. */
    fun interpret(ctx: Context, heard: String, done: (String?) -> Unit) {
        val request = CommandRouter.normalize(heard)
        if (request.isEmpty()) { done(null); return }
        ModelManager.complete(ctx, Catalog.prefix, Catalog.suffix(request), Catalog.grammar, maxTokens = 48) { out ->
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
                "take a screenshot", "lock the phone", "scroll up", "scroll down",
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

        /** GBNF: the model can only emit one of these shapes. */
        val grammar: String by lazy {
            buildString {
                val alts = fixed.map { "\"$it\"" } + listOf(
                    "\"brightness \" num \" percent\"",
                    "\"volume \" num \" percent\"",
                    "\"set an alarm for \" hour (\" \" minute)? (\" am\" | \" pm\")?",
                    "\"timer \" num \" \" (\"seconds\" | \"minutes\" | \"hours\")",
                    "\"open \" words",
                    "\"app info for \" words",
                    "\"call \" words",
                    "\"text \" words \" saying \" text",
                    "\"search for \" words",
                    "\"navigate to \" words",
                    "\"tap \" words",
                    "\"type \" text",
                    "\"none\"",
                )
                append("root ::= ").append(alts.joinToString(" | ")).append('\n')
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
            Regex("^text (.+?) saying (.+)$"), Regex("^search for (.+)$"), Regex("^navigate to (.+)$"),
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
