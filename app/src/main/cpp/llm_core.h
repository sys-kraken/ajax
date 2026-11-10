// Shared by the Android JNI bridge and the host-side eval tool, so CI tests the exact phone code.
#pragma once
#include <string>
#include <vector>
#include "llama.h"

namespace ajax {

/**
 * A loaded model plus one context. The fixed prompt prefix (instructions + examples) is evaluated
 * once and kept in the KV cache; each request only evaluates its short suffix.
 */
struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    std::vector<llama_token> prefix; // tokens currently cached at positions [0, prefix.size())
    std::string cachePath;           // where the evaluated prefix is saved between model loads; empty = don't
};

/** Loads a GGUF model (CPU only). Returns nullptr on failure; free with close(). */
Session *open(const char *path, int threads);
void close(Session *s);

/**
 * Greedy completion of [prefix]+[suffix]. With a GBNF [grammar] (root rule "root") the output is
 * restricted to it; with an empty one the model writes freely until it ends its turn or reaches
 * [maxTokens]. Returns false on failure. Output is ASCII only.
 */
bool complete(Session *s, const std::string &prefix, const std::string &suffix, const std::string &grammar,
              int maxTokens, std::string &out);

} // namespace ajax
