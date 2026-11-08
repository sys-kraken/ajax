// JNI bridge to llm_core. Every call runs on Ajax's single "ajax-llm" thread.
#include <jni.h>
#include <android/log.h>
#include <string>
#include "llm_core.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AjaxLlm", __VA_ARGS__)

static std::string utf8(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ajax_assistant_LlamaNative_load(JNIEnv *env, jobject, jstring path, jint threads) {
    ajax::Session *s = ajax::open(utf8(env, path).c_str(), threads);
    if (!s) LOGE("model load failed");
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ajax_assistant_LlamaNative_setCache(JNIEnv *env, jobject, jlong handle, jstring path) {
    auto *s = reinterpret_cast<ajax::Session *>(handle);
    if (s) s->cachePath = utf8(env, path);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ajax_assistant_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    ajax::close(reinterpret_cast<ajax::Session *>(handle));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ajax_assistant_LlamaNative_complete(JNIEnv *env, jobject, jlong handle, jstring prefix, jstring suffix,
                                             jstring grammar, jint maxTokens) {
    auto *s = reinterpret_cast<ajax::Session *>(handle);
    if (!s) return nullptr;
    std::string out;
    if (!ajax::complete(s, utf8(env, prefix), utf8(env, suffix), utf8(env, grammar), maxTokens, out)) {
        LOGE("completion failed");
        return nullptr;
    }
    return env->NewStringUTF(out.c_str());
}
