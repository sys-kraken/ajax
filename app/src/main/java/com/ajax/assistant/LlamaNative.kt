package com.ajax.assistant

/** Thin JNI binding to llama.cpp (see src/main/cpp). Call only from ModelManager's thread. */
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

    /** Greedy completion restricted to [grammar] (GBNF, root rule "root"). Null on failure. */
    external fun complete(handle: Long, prompt: String, grammar: String, maxTokens: Int): String?
}
