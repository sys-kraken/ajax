package com.ajax.assistant

import android.content.Context

/**
 * The small on-device model that turns loose phrasing into one of Ajax's commands (see
 * [IntentModel]). The file handling and loading live in [GgufModel].
 */
object ModelManager {
    // Qwen2.5-0.5B-Instruct, 4-bit (Q4_0) GGUF. Apache-2.0, no login needed.
    const val FILE_NAME = "qwen2.5-0.5b-instruct-q4_0.gguf"
    const val URL_STRING =
        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf"
    const val SIZE_BYTES = 428_730_208L
    /** Checked in CI by .github/workflows/model-hash.yml against the real file. */
    const val SHA256 = "7671c0c304e6ce5a7fc577bcb12aba01e2c155cc2efd29b2213c95b18edaf6ed"

    sealed class State {
        object Missing : State()
        class Downloading(val done: Long, val total: Long) : State()
        class Failed(val message: String) : State()
        object Ready : State()
    }

    private val model = GgufModel(FILE_NAME, URL_STRING, SIZE_BYTES, SHA256)

    /** Called on the main thread whenever the state changes. One listener (the settings screen). */
    var listener: ((State) -> Unit)?
        get() = model.listener
        set(value) { model.listener = value }

    fun state(ctx: Context): State = model.state(ctx)
    fun isReady(ctx: Context): Boolean = model.isReady(ctx)
    fun download(ctx: Context) = model.download(ctx)
    fun delete(ctx: Context) = model.delete(ctx)

    /** A grammar-constrained completion; [done] gets the text (or null) on the main thread. */
    fun complete(ctx: Context, prefix: String, suffix: String, grammar: String, maxTokens: Int, done: (String?) -> Unit) =
        model.complete(ctx, prefix, suffix, grammar, maxTokens, done)
}
