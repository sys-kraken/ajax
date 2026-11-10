package com.ajax.assistant

/** Thin JNI binding to llama.cpp (see src/main/cpp). Call only from GgufModel's thread. */
object LlamaNative {
    /** False on phones without the native library (e.g. 32-bit or x86 devices). */
    val available: Boolean = try {
        System.loadLibrary("ajax_llm")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    /** Returns a handle, or 0 on failure. */
    external fun load(path: String, threads: Int): Long
    external fun free(handle: Long)

    /** Saves the evaluated prompt prefix to [path] and reuses it after the model is reloaded. */
    external fun setCache(handle: Long, path: String)

    /**
     * Greedy completion of [prefix]+[suffix], restricted to [grammar] (GBNF, root "root") unless it
     * is empty, in which case the model writes freely. The prefix is cached between calls, so keep
     * it identical. Null on failure.
     */
    external fun complete(handle: Long, prefix: String, suffix: String, grammar: String, maxTokens: Int): String?
}
