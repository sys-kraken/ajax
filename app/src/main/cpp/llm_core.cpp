#include "llm_core.h"
#include <algorithm>

namespace ajax {

static constexpr uint32_t kCtx = 2048;   // prefix (~1k tokens) + request + answer
static constexpr uint32_t kBatch = 512;

static bool tokenize(const llama_vocab *vocab, const std::string &text, bool addBos, std::vector<llama_token> &out) {
    // parse_special: the prompt carries the chat template's control tokens.
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, addBos, true);
    if (n < 0) return false;
    out.resize(n);
    return n == 0 || llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), out.data(), n, addBos, true) >= 0;
}

static bool decode(llama_context *ctx, std::vector<llama_token> &tokens) {
    for (size_t i = 0; i < tokens.size(); i += kBatch) {
        int32_t n = (int32_t) std::min<size_t>(kBatch, tokens.size() - i);
        if (llama_decode(ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) return false;
    }
    return true;
}

Session *open(const char *path, int threads) {
    static bool backendReady = false;
    if (!backendReady) { llama_backend_init(); backendReady = true; }

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // CPU only: predictable on every phone
    llama_model *model = llama_model_load_from_file(path, mp);
    if (!model) return nullptr;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = kCtx;
    cp.n_batch = kBatch;
    cp.n_ubatch = kBatch;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); return nullptr; }

    auto *s = new Session();
    s->model = model;
    s->ctx = ctx;
    return s;
}

void close(Session *s) {
    if (!s) return;
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}

bool complete(Session *s, const std::string &prefix, const std::string &suffix, const std::string &grammar,
              int maxTokens, std::string &out) {
    out.clear();
    const llama_vocab *vocab = llama_model_get_vocab(s->model);
    llama_memory_t mem = llama_get_memory(s->ctx);

    std::vector<llama_token> pre, suf;
    if (!tokenize(vocab, prefix, true, pre) || !tokenize(vocab, suffix, false, suf) || suf.empty()) return false;
    if (pre.size() + suf.size() + maxTokens + 1 > kCtx) return false;

    if (pre == s->prefix) {
        // Same instructions as last time: drop only the previous request and answer.
        if (!llama_memory_seq_rm(mem, 0, (llama_pos) pre.size(), -1)) {
            llama_memory_clear(mem, true);
            s->prefix.clear();
        }
    } else {
        llama_memory_clear(mem, true);
        s->prefix.clear();
    }
    if (s->prefix.empty() && !s->cachePath.empty()) {
        // Reading the saved prefix back takes a fraction of a second; evaluating it takes several.
        std::vector<llama_token> saved(pre.size() + 16);
        size_t n = 0;
        if (llama_state_seq_load_file(s->ctx, s->cachePath.c_str(), 0, saved.data(), saved.size(), &n) > 0 &&
            n == pre.size() && std::equal(pre.begin(), pre.end(), saved.begin())) {
            s->prefix = pre;
        } else {
            llama_memory_clear(mem, true);
        }
    }
    if (s->prefix.empty()) {
        if (!decode(s->ctx, pre)) { llama_memory_clear(mem, true); return false; }
        s->prefix = pre;
        if (!s->cachePath.empty()) llama_state_seq_save_file(s->ctx, s->cachePath.c_str(), 0, pre.data(), pre.size());
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!grammar.empty()) {
        llama_sampler *g = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (!g) { llama_sampler_free(smpl); return false; }
        llama_sampler_chain_add(smpl, g);                       // only grammar-legal tokens survive
    } else {
        // Free text: greedy alone can fall into repeating itself, so recent tokens cost a little.
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.1f, 0.0f, 0.0f));
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy()); // deterministic

    bool ok = decode(s->ctx, suf);
    for (int i = 0; ok && i < maxTokens; i++) {
        llama_token t = llama_sampler_sample(smpl, s->ctx, -1); // also accepts t into the grammar
        if (llama_vocab_is_eog(vocab, t)) break;
        char buf[128];
        int len = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, false);
        for (int k = 0; k < len; k++) {
            unsigned char c = (unsigned char) buf[k];
            if (c > 0 && c < 0x7f) out.push_back((char) c); // never hand JNI invalid UTF-8
        }
        ok = llama_decode(s->ctx, llama_batch_get_one(&t, 1)) == 0;
    }
    llama_sampler_free(smpl);
    if (!ok) { llama_memory_clear(mem, true); s->prefix.clear(); } // start clean next time
    return ok;
}

} // namespace ajax
