package com.ajax.assistant

import android.content.Context

/** One conversation: routes text to a command and handles yes/no confirmation. */
class Brain(private val ctx: Context) {

    class Reply(
        val result: Result,
        val listenAgain: Boolean = false,
        val afterHide: (() -> Result)? = null,
        /** Don't keep listening after this one. */
        val ends: Boolean = false,
    )

    /** The phrase that was understood (for display). */
    var heard: String = ""
        private set

    private var pending: Outcome.Ask? = null

    /** Bumped on reset, so a slow model answer from an old conversation is dropped. */
    private var generation = 0

    private val yes = setOf("yes", "yeah", "yep", "yup", "confirm", "do it", "send it", "sure", "okay", "ok", "go ahead")
    private val no = setOf("no", "nope", "cancel", "stop", "dont", "don t", "never mind", "nevermind")
    private val goodbye = setOf(
        "stop", "cancel", "never mind", "nevermind", "nothing", "no", "nope", "no thanks", "no thank you", "thanks", "thank you",
        "thats all", "thats it", "im done", "done", "bye", "goodbye", "close", "exit", "quit", "dismiss",
    )

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
                    words.any { it in yes } -> { pending = null; settle(p.onYes(ctx), p.endsConversation) }
                    words.any { it in no } -> { pending = null; Reply(Result(true, "Cancelled."), ends = true) }
                    else -> Reply(Result(true, "Say yes or no."), listenAgain = true)
                },
            )
            return
        }

        if (candidates.any { CommandRouter.normalize(it) in goodbye }) {
            done(Reply(Result(true, "Okay."), ends = true))
            return
        }

        // 1. Exact phrasing: the regex router, instant. A "sure" match wins outright.
        val hits = candidates.mapNotNull { c -> CommandRouter.route(c)?.let { c to it } }
        hits.firstOrNull { it.second.sure(ctx) }?.let { (c, m) ->
            heard = c
            done(run(m, fromModel = false))
            return
        }
        // Only a hint matched (e.g. "flashlight?", unknown app): keep it as the fallback answer.
        val fallback: () -> Reply = hits.firstOrNull()?.let { (c, m) -> { heard = c; run(m, fromModel = false) } }
            ?: { Reply(Result(false, "I don't know that command yet.")) }

        // 2. Loose phrasing: the on-device model picks one command from the same table.
        if (!IntentModel.available(ctx)) { done(questionOr(heard, fallback)); return }
        onThinking()
        val gen = generation
        val said = heard
        IntentModel.interpret(ctx, said) { phrase ->
            if (gen != generation) return@interpret
            val m = phrase?.let { CommandRouter.route(it) }?.takeIf { it.sure(ctx) }
            if (phrase == null || m == null) { done(questionOr(said, fallback)); return@interpret }
            heard = "$said (understood as: $phrase)"
            done(run(m, fromModel = true, phrase = phrase))
        }
    }

    /** Nothing matched: a real question ("how tall is everest") becomes a web search; else [fallback]. */
    private fun questionOr(said: String, fallback: () -> Reply): Reply {
        val t = CommandRouter.normalize(said)
        if (!Regex("^(?:who|what|whats|where|wheres|when|why|how|which|is|are|can|does|did)\\b").containsMatchIn(t)) {
            return fallback()
        }
        val search = CommandRouter.route("search for $t") ?: return fallback()
        return run(search, fromModel = false)
    }

    private fun run(m: CommandRouter.Match, fromModel: Boolean, phrase: String = ""): Reply {
        // A guessed phone call is too costly to get wrong: confirm it first.
        if (fromModel && m.id == "call") {
            val ask = Outcome.Ask("${phrase.replaceFirstChar { it.uppercase() }}?", endsConversation = true) { c ->
                m.start(c)
            }
            pending = ask
            return Reply(Result(true, ask.prompt), listenAgain = true)
        }
        return settle(m.start(ctx), m.endsConversation)
    }

    private fun settle(o: Outcome, ends: Boolean): Reply = when (o) {
        is Outcome.Done -> Reply(o.result, ends = ends && o.result.ok)
        is Outcome.Ask -> { pending = o; Reply(Result(true, o.prompt), listenAgain = true) }
        is Outcome.AfterHide -> Reply(Result(true, o.label), afterHide = { o.run(ctx) }, ends = ends)
    }
}
