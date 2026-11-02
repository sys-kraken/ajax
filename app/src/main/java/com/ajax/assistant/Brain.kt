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

    /** Bumped on reset, so a slow model answer from an old conversation is dropped. */
    private var generation = 0

    private val yes = setOf("yes", "yeah", "yep", "yup", "confirm", "do it", "send it", "sure", "okay", "ok", "go ahead")
    private val no = setOf("no", "nope", "cancel", "stop", "dont", "don t", "never mind", "nevermind")

    /** Forget any unanswered yes/no question (new side-key press = new conversation). */
    fun reset() {
        pending = null
        heard = ""
        generation++
    }

    /**
     * Handles what was heard. [done] runs once, on the main thread: immediately for known phrasing,
     * after the on-device model for anything else. [onThinking] fires only when the model is used.
     */
    fun handle(candidates: List<String>, onThinking: () -> Unit = {}, done: (Reply) -> Unit) {
        heard = candidates.firstOrNull().orEmpty()
        pending?.let { p ->
            val words = candidates.map { CommandRouter.normalize(it) }
            done(
                when {
                    words.any { it in yes } -> { pending = null; Reply(p.onYes(ctx)) }
                    words.any { it in no } -> { pending = null; Reply(Result(true, "Cancelled.")) }
                    else -> Reply(Result(true, "Say yes or no."), listenAgain = true)
                },
            )
            return
        }

        // 1. Exact phrasing: the regex router, instant.
        candidates.firstNotNullOfOrNull { c -> CommandRouter.route(c)?.let { c to it } }?.let { (c, m) ->
            heard = c
            done(run(m, fromModel = false))
            return
        }

        // 2. Loose phrasing: the on-device model picks one command from the same table.
        val unknown = Reply(Result(false, "I don't know that command yet."))
        if (!IntentModel.available(ctx)) { done(unknown); return }
        onThinking()
        val gen = generation
        IntentModel.interpret(ctx, heard) { phrase ->
            if (gen != generation) return@interpret
            val m = phrase?.let { CommandRouter.route(it) }
            if (phrase == null || m == null) { done(unknown); return@interpret }
            heard = "$heard → $phrase"
            done(run(m, fromModel = true))
        }
    }

    private fun run(m: CommandRouter.Match, fromModel: Boolean): Reply {
        // A guessed phone call is too costly to get wrong: confirm it first.
        if (fromModel && m.id == "call") {
            val ask = Outcome.Ask("${heard.substringAfter("→ ").replaceFirstChar { it.uppercase() }}?") { c ->
                (m.start(c) as? Outcome.Done)?.result ?: Result(false, "Couldn't place the call.")
            }
            pending = ask
            return Reply(Result(true, ask.prompt), listenAgain = true)
        }
        return when (val o = m.start(ctx)) {
            is Outcome.Done -> Reply(o.result)
            is Outcome.Ask -> { pending = o; Reply(Result(true, o.prompt), listenAgain = true) }
            is Outcome.AfterHide -> Reply(Result(true, o.label), afterHide = { o.run(ctx) })
        }
    }
}
