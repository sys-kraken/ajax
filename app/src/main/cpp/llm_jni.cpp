// JNI bridge to llama.cpp. One job: given a prompt and a GBNF grammar, return the greedy completion
// that the grammar allows. Every call runs on Ajax's single "ajax-llm" thread.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "llama.h"

#define TAG "AjaxLlm"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *model;
    int threads;
};

std::string utf8(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Grammar output is plain ASCII, but never hand JNI's NewStringUTF invalid modified UTF-8.
std::string asciiOnly(const std::string &s) {
    std::string out;
    for (char c : s) if (c > 0 && c < 0x7f) out.push_back(c);
    return out;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_ajax_assistant_LlamaNative_load(JNIEnv *env, jobject, jstring path, jint threads) {
    static bool backendReady = false;
    if (!backendReady) { llama_backend_init(); backendReady = true; }

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // CPU only: predictable on every phone
    llama_model *model = llama_model_load_from_file(utf8(env, path).c_str(), mp);
    if (!model) { LOGE("model load failed"); return 0; }
    return reinterpret_cast<jlong>(new Engine{model, threads});
}

extern "C" JNIEXPORT void JNICALL
Java_com_ajax_assistant_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    llama_model_free(e->model);
    delete e;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ajax_assistant_LlamaNative_complete(JNIEnv *env, jobject, jlong handle, jstring jprompt,
                                             jstring jgrammar, jint maxTokens) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return nullptr;
    const llama_vocab *vocab = llama_model_get_vocab(e->model);
    const std::string prompt = utf8(env, jprompt);
    const std::string grammar = utf8(env, jgrammar);

    // Tokenize. parse_special: the prompt carries the chat template's control tokens.
    int n = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    if (n <= 0) return nullptr;
    std::vector<llama_token> tokens(n);
    if (llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), n, true, true) < 0) {
        return nullptr;
    }

    // Fresh context per call: no KV-cache bookkeeping, nothing leaks between requests.
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) (n + maxTokens + 8);
    cp.n_batch = (uint32_t) n;
    cp.n_ubatch = (uint32_t) n;
    cp.n_threads = e->threads;
    cp.n_threads_batch = e->threads;
    cp.no_perf = true;
    llama_context *ctx = llama_init_from_model(e->model, cp);
    if (!ctx) { LOGE("context init failed"); return nullptr; }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler *g = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
    if (!g) {
        LOGE("grammar parse failed");
        llama_sampler_free(smpl);
        llama_free(ctx);
        return nullptr;
    }
    llama_sampler_chain_add(smpl, g);                           // only grammar-legal tokens survive
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy()); // deterministic: same words, same answer

    std::string out;
    bool ok = llama_decode(ctx, llama_batch_get_one(tokens.data(), n)) == 0;
    for (int i = 0; ok && i < maxTokens; i++) {
        llama_token t = llama_sampler_sample(smpl, ctx, -1); // also accepts t into the grammar
        if (llama_vocab_is_eog(vocab, t)) break;
        char buf[128];
        int len = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, false);
        if (len > 0) out.append(buf, len);
        ok = llama_decode(ctx, llama_batch_get_one(&t, 1)) == 0;
    }

    llama_sampler_free(smpl);
    llama_free(ctx);
    if (!ok) { LOGE("decode failed"); return nullptr; }
    return env->NewStringUTF(asciiOnly(out).c_str());
}
