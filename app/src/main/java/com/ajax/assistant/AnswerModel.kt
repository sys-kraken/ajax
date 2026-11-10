package com.ajax.assistant

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The larger on-device model that answers general questions, fully offline (see [Answers]).
 * Optional: without it, questions fall back to a web search. Loading and files: [GgufModel].
 */
object AnswerModel {
    // Qwen2.5-1.5B-Instruct, 4-bit (Q4_K_M) GGUF. Apache-2.0, no login needed.
    const val FILE_NAME = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
    const val URL_STRING =
        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
    const val SIZE_BYTES = 1_117_320_736L
    /** Checked in CI by .github/workflows/model-hash.yml against the real file. */
    const val SHA256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"

    private val model = GgufModel(FILE_NAME, URL_STRING, SIZE_BYTES, SHA256)

    var listener: ((ModelManager.State) -> Unit)?
        get() = model.listener
        set(value) { model.listener = value }

    fun state(ctx: Context): ModelManager.State = model.state(ctx)
    fun isReady(ctx: Context): Boolean = model.isReady(ctx)
    fun download(ctx: Context) = model.download(ctx)
    fun delete(ctx: Context) = model.delete(ctx)

    /** A free completion (no grammar); [done] gets the text (or null) on the main thread. */
    fun complete(ctx: Context, prefix: String, suffix: String, maxTokens: Int, done: (String?) -> Unit) =
        model.complete(ctx, prefix, suffix, "", maxTokens, done)
}

/**
 * Answers to general questions ("why is the sky blue", "how do I boil an egg") from the on-device
 * model. It only talks: it can't act on the phone, and it is told so, so it never claims to have
 * done something. Answers are kept short because they are spoken aloud.
 */
object Answers {
    /** About 90 words: long enough for a real answer, short enough to listen to. */
    private const val MAX_TOKENS = 140

    /** A follow-up ("and how far is it?") makes sense for this long after the last answer. */
    private const val MEMORY_MS = 3 * 60_000L
    private const val MEMORY_TURNS = 2

    private class Turn(val question: String, val answer: String, val at: Long)

    private val turns = ArrayDeque<Turn>() // main thread only

    fun available(ctx: Context): Boolean = AnswerModel.isReady(ctx)

    /**
     * The fixed instructions. Kept identical between questions, so the model reads them once and
     * reuses that work (the date and the conversation go in the changing part).
     */
    val prefix: String = "<|im_start|>system\n" +
        "You are Ajax, a voice assistant that runs offline on a phone. Your answer is read aloud. " +
        "Answer in one to three short sentences of plain spoken English. No lists, no markdown, no emoji. " +
        "You have no internet: for news, weather, prices, scores or anything that changes, say you can't check that offline and suggest saying \"search for\" it. " +
        "You can't control the phone from here: if asked to do something on the phone, say you don't know that command yet. Never say you did something. " +
        "If you are not sure of a fact, say so instead of guessing.<|im_end|>\n"

    /** The changing part of the prompt: today's date, the recent conversation, and the question. */
    fun suffix(question: String, now: Long, history: List<Pair<String, String>>): String {
        val date = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.US).format(Date(now))
        val out = StringBuilder()
        for ((q, a) in history) {
            out.append("<|im_start|>user\n").append(plain(q)).append("<|im_end|>\n")
            out.append("<|im_start|>assistant\n").append(plain(a)).append("<|im_end|>\n")
        }
        out.append("<|im_start|>user\n(It is ").append(date).append(".)\n").append(plain(question)).append("<|im_end|>\n")
        out.append("<|im_start|>assistant\n")
        return out.toString()
    }

    /** What was said can never carry the model's own control marks. */
    fun plain(text: String): String = text.replace("<|", "<").replace("|>", ">").trim()

    /**
     * Makes the model's text fit to speak: one paragraph, no markdown marks, and no sentence left
     * half finished when the length limit cut it off. Null when nothing usable is left.
     */
    fun clean(raw: String?): String? {
        var t = raw.orEmpty().replace(Regex("[*#`_]+"), "").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        if (t.last() !in ".!?\"") {
            val end = t.indexOfLast { it in ".!?" }
            // Cut back to the last whole sentence; a single unfinished sentence just gets a full stop.
            t = if (end >= 20) t.substring(0, end + 1) else "$t."
        }
        return t
    }

    /** [done] gets the answer, or null if the model isn't there or failed. Main thread. */
    fun ask(ctx: Context, question: String, done: (String?) -> Unit) {
        if (!available(ctx) || question.isBlank()) { done(null); return }
        val now = System.currentTimeMillis()
        while (turns.isNotEmpty() && now - turns.first().at > MEMORY_MS) turns.removeFirst()
        val history = turns.map { it.question to it.answer }
        AnswerModel.complete(ctx, prefix, suffix(question, now, history), MAX_TOKENS) { raw ->
            val answer = clean(raw)
            if (answer != null) {
                turns.addLast(Turn(question, answer, System.currentTimeMillis()))
                while (turns.size > MEMORY_TURNS) turns.removeFirst()
            }
            done(answer)
        }
    }

    fun forget() = turns.clear()
}
