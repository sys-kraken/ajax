package com.ajax.assistant

import android.content.Context

/** One conversation: routes text to a command and handles yes/no confirmation. */
class Brain(private val ctx: Context) {

    class Reply(
        val result: Result,
        val listenAgain: Boolean = false,
        val afterHide: (() -> Result)? = null,
    )

    /** The phrase that was understood (for display). */
    var heard: String = ""
        private set

    private var pending: Outcome.Ask? = null

    private val yes = setOf("yes", "yeah", "yep", "yup", "confirm", "do it", "send it", "sure", "okay", "ok", "go ahead")
    private val no = setOf("no", "nope", "cancel", "stop", "dont", "don t", "never mind", "nevermind")

    /** Forget any unanswered yes/no question (new side-key press = new conversation). */
    fun reset() {
        pending = null
        heard = ""
    }

    fun handle(candidates: List<String>): Reply {
        heard = candidates.firstOrNull().orEmpty()
        pending?.let { p ->
            val words = candidates.map { CommandRouter.normalize(it) }
            return when {
                words.any { it in yes } -> { pending = null; Reply(p.onYes(ctx)) }
                words.any { it in no } -> { pending = null; Reply(Result(true, "Cancelled.")) }
                else -> Reply(Result(true, "Say yes or no."), listenAgain = true)
            }
        }
        val hit = candidates.firstNotNullOfOrNull { c -> CommandRouter.route(c)?.let { c to it } }
            ?: return Reply(Result(false, "I don't know that command yet."))
        heard = hit.first
        return when (val o = hit.second.start(ctx)) {
            is Outcome.Done -> Reply(o.result)
            is Outcome.Ask -> { pending = o; Reply(Result(true, o.prompt), listenAgain = true) }
            is Outcome.AfterHide -> Reply(Result(true, o.label), afterHide = { o.run(ctx) })
        }
    }
}
