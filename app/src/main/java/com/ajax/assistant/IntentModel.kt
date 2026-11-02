package com.ajax.assistant

import android.content.Context

/**
 * Stage 3: understands loose phrasing ("could you flip the torch on") with the on-device model.
 *
 * Safety by construction: the model never acts. Its output is constrained by a grammar to exactly
 * one canonical command phrase from [Catalog] (or "none"), and that phrase then goes through the
 * normal regex [CommandRouter] like anything the user said. Names it fills in must appear in what
 * the user actually said.
 */
object IntentModel {

    fun available(ctx: Context): Boolean = ModelManager.isReady(ctx)

    /** [done] receives a canonical phrase the router accepts, or null. Main thread. */
    fun interpret(ctx: Context, heard: String, done: (String?) -> Unit) {
        val request = CommandRouter.normalize(heard)
        if (request.isEmpty()) { done(null); return }
        ModelManager.complete(ctx, Catalog.prompt(request), Catalog.grammar(), maxTokens = 48) { out ->
            done(out?.let { Catalog.accept(it, request) })
        }
    }

    /** The command table the model chooses from. Pure Kotlin so it is unit-tested on the JVM. */
    object Catalog {
        /** Fixed commands, each exactly as [CommandRouter] understands it. */
        val fixed: List<String> by lazy {
            listOf(
                "volume up", "volume down", "mute", "unmute",
                "vibrate mode", "silent mode", "sound mode",
                "flashlight on", "flashlight off",
                "turn on wifi", "turn off wifi", "turn on bluetooth", "turn off bluetooth",
                "turn on mobile data", "turn off mobile data", "turn on airplane mode", "turn off airplane mode",
                "brightness max", "brightness min",
                "do not disturb on", "do not disturb off",
                "go back", "go home", "recent apps", "open notifications", "quick settings",
                "take a screenshot", "lock the phone", "scroll up", "scroll down",
                "whats my battery", "what time is it", "whats the date", "read my notifications",
                "open settings",
            ) + CommandRouter.settingsPageNames.sorted().map { "$it settings" }
        }

        /** Commands with a slot. <name>/<message> are copied from the request; <n> are numbers. */
        val slotted = listOf(
            "brightness <n> percent",
            "set an alarm for <hour> <minute> am|pm",
            "timer <n> seconds|minutes|hours",
            "open <app>",
            "app info for <app>",
            "call <contact>",
            "text <contact> saying <message>",
        )

        private val examples = listOf(
            "could you flip the torch on" to "flashlight on",
            "its too dark i cant see the screen" to "brightness max",
            "kill the wifi" to "turn off wifi",
            "wake me up tomorrow at half past six" to "set an alarm for 6 30 am",
            "give mom a ring" to "call mom",
            "fire up spotify" to "open spotify",
            "remind me in ten minutes" to "timer 10 minutes",
            "whats the weather like" to "none",
        )

        fun prompt(request: String): String = buildString {
            append("<|im_start|>system\n")
            append("You turn a phone user's request into exactly one command from this list, ")
            append("copying names and numbers from the request. If nothing fits, answer none.\n")
            (fixed + slotted).forEach { append("- ").append(it).append('\n') }
            append("<|im_end|>\n")
            for ((q, a) in examples) {
                append("<|im_start|>user\n").append(q).append("<|im_end|>\n")
                append("<|im_start|>assistant\n").append(a).append("<|im_end|>\n")
            }
            append("<|im_start|>user\n").append(request).append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }

        /** GBNF: the model can only emit one of these shapes. */
        fun grammar(): String = buildString {
            val alts = fixed.map { "\"$it\"" } + listOf(
                "\"brightness \" num \" percent\"",
                "\"set an alarm for \" hour (\" \" minute)? (\" am\" | \" pm\")?",
                "\"timer \" num \" \" (\"seconds\" | \"minutes\" | \"hours\")",
                "\"open \" name",
                "\"app info for \" name",
                "\"call \" name",
                "\"text \" name \" saying \" message",
                "\"none\"",
            )
            append("root ::= ").append(alts.joinToString(" | ")).append('\n')
            append("num ::= [0-9] [0-9]? [0-9]?\n")
            append("hour ::= [0-9] [0-9]?\n")
            append("minute ::= [0-9] [0-9]\n")
            append("name ::= [a-z0-9] [a-z0-9 ]{0,29}\n")
            append("message ::= [a-z0-9 ,.?!]{1,160}\n")
        }

        private val nameSlot = listOf(
            Regex("^open (.+)$"), Regex("^app info for (.+)$"),
            Regex("^call (.+)$"), Regex("^text (.+?) saying .+$"),
        )

        /**
         * Final gate on model output: must be a real command, and any name it filled in must be
         * words the user actually said (a small model can otherwise invent a contact or app).
         */
        fun accept(output: String, request: String): String? {
            val phrase = output.trim().replace(Regex("\\s+"), " ")
            if (phrase.isEmpty() || phrase == "none") return null
            val said = request.split(' ').toSet()
            if (phrase !in fixed) for (r in nameSlot) {
                val name = r.matchEntire(phrase)?.groupValues?.get(1) ?: continue
                if (!name.split(' ').filter { it.isNotEmpty() }.all { it in said }) return null
                break
            }
            return phrase.takeIf { CommandRouter.route(it) != null }
        }
    }
}
