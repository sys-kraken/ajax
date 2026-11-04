// Host-side model check (CI). Runs the phone's exact inference code over exported test cases.
// Usage: ajax_eval <model.gguf> <eval-dir>
//   <eval-dir>/prefix.txt, <eval-dir>/cases.tsv (id \t request \t accepted answers joined by '|'),
//   <eval-dir>/suffix_<id>.txt, <eval-dir>/grammar_<id>.gbnf (the phone builds the grammar per request)
// Prints one line per case and a summary; exit code 0 always (the threshold is checked by CI).
#include "llm_core.h"
#include <chrono>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

static std::string slurp(const std::string &p) {
    std::ifstream f(p, std::ios::binary);
    std::stringstream s;
    s << f.rdbuf();
    return s.str();
}

static std::vector<std::string> split(const std::string &s, char d) {
    std::vector<std::string> out;
    std::string cur;
    for (char c : s) {
        if (c == d) { out.push_back(cur); cur.clear(); } else cur.push_back(c);
    }
    out.push_back(cur);
    return out;
}

int main(int argc, char **argv) {
    if (argc < 3) { std::fprintf(stderr, "usage: %s model.gguf eval-dir\n", argv[0]); return 2; }
    const std::string dir = argv[2];
    ajax::Session *session = ajax::open(argv[1], 4); // 4 threads, like the phone
    if (!session) { std::fprintf(stderr, "model load failed\n"); return 2; }
    const std::string prefix = slurp(dir + "/prefix.txt");

    std::ifstream cases(dir + "/cases.tsv");
    std::string line;
    int total = 0, pass = 0;
    double totalMs = 0, maxMs = 0;
    while (std::getline(cases, line)) {
        if (line.empty()) continue;
        auto cols = split(line, '\t');
        if (cols.size() < 3) continue;
        const std::string &id = cols[0], &request = cols[1];
        auto accepted = split(cols[2], '|');
        std::string out;
        auto t0 = std::chrono::steady_clock::now();
        bool ok = ajax::complete(session, prefix, slurp(dir + "/suffix_" + id + ".txt"),
                                slurp(dir + "/grammar_" + id + ".gbnf"), 48, out);
        double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        totalMs += ms;
        if (ms > maxMs) maxMs = ms;
        while (!out.empty() && out.back() == ' ') out.pop_back();
        bool good = false;
        for (auto &a : accepted) good |= ok && out == a;
        total++;
        if (good) pass++;
        std::printf("%s  %-45s -> %-35s (want %s) %.0f ms\n", good ? "PASS" : "FAIL", request.c_str(),
                    ok ? out.c_str() : "<error>", cols[2].c_str(), ms);
    }
    std::printf("RESULT pass=%d total=%d accuracy=%.1f%% avg_ms=%.0f max_ms=%.0f\n", pass, total,
                total ? 100.0 * pass / total : 0.0, total ? totalMs / total : 0.0, maxMs);
    ajax::close(session);
    return 0;
}
