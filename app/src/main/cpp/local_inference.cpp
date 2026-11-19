#include <jni.h>
#include <llama.h>
#include <algorithm>
#include <atomic>
#include <memory>
#include <string>
#include <vector>

// Requests run on one Kotlin executor. Cancellation can arrive on another thread.
static std::atomic<bool> cancelled{false};
static bool abort_decode(void *) { return cancelled.load(); }
static void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
static std::string bytes(JNIEnv *env, jbyteArray arr) {
    const auto size = env->GetArrayLength(arr);
    std::string result(size, '\0');
    env->GetByteArrayRegion(arr, 0, size, reinterpret_cast<jbyte *>(result.data()));
    return result;
}
extern "C" JNIEXPORT void JNICALL
Java_com_voiceagent_oneplus13_local_LocalInference_nativeCancel(JNIEnv *, jobject) { cancelled = true; }
extern "C" JNIEXPORT void JNICALL
Java_com_voiceagent_oneplus13_local_LocalInference_nativeReset(JNIEnv *, jobject) { cancelled = false; }
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_voiceagent_oneplus13_local_LocalInference_nativeGenerate(JNIEnv *env, jobject, jstring jpath, jbyteArray input) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return nullptr;
    const std::string model_path(path);
    env->ReleaseStringUTFChars(jpath, path);
    const std::string prompt = bytes(env, input);
    struct BackendGuard { BackendGuard() { llama_backend_init(); } ~BackendGuard() { llama_backend_free(); } } backend;
    auto params = llama_model_default_params();
    params.n_gpu_layers = 0;
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
        llama_model_load_from_file(model_path.c_str(), params), llama_model_free);
    if (!model) { fail(env, "LOCAL_MODEL_LOAD_FAILED"); return nullptr; }
    if (cancelled) { fail(env, "LOCAL_CANCELLED"); return nullptr; }
    const auto *vocab = llama_model_get_vocab(model.get());
    const int n = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
    constexpr int context_size = 8192;
    constexpr int max_new = 384;
    if (n <= 0 || n > context_size - max_new) { fail(env, "LOCAL_PROMPT_TOO_LONG"); return nullptr; }
    std::vector<llama_token> tokens(n);
    if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), n, true, true) < 0) {
        fail(env, "LOCAL_TOKENIZE_FAILED"); return nullptr;
    }
    auto cp = llama_context_default_params();
    cp.n_ctx = context_size;
    cp.n_batch = 256;
    cp.n_ubatch = 128;
    cp.n_threads = 4;
    cp.n_threads_batch = 4;
    cp.abort_callback = abort_decode;
    std::unique_ptr<llama_context, decltype(&llama_free)> ctx(llama_init_from_model(model.get(), cp), llama_free);
    if (!ctx) { fail(env, "LOCAL_CONTEXT_FAILED"); return nullptr; }
    auto sp = llama_sampler_chain_default_params();
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(sp), llama_sampler_free);
    if (!sampler) { fail(env, "LOCAL_SAMPLER_FAILED"); return nullptr; }
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
    for (int pos = 0; pos < n; pos += 256) {
        const int count = std::min(256, n - pos);
        if (cancelled || llama_decode(ctx.get(), llama_batch_get_one(tokens.data() + pos, count)) != 0) {
            fail(env, cancelled ? "LOCAL_CANCELLED" : "LOCAL_DECODE_FAILED"); return nullptr;
        }
    }
    std::string output;
    for (int i = 0; i < max_new && !cancelled; ++i) {
        auto token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        char buf[256];
        const int length = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, false);
        if (length < 0) { fail(env, "LOCAL_TOKEN_FAILED"); return nullptr; }
        output.append(buf, length);
        if (llama_decode(ctx.get(), llama_batch_get_one(&token, 1)) != 0) {
            fail(env, cancelled ? "LOCAL_CANCELLED" : "LOCAL_DECODE_FAILED"); return nullptr;
        }
    }
    if (cancelled) { fail(env, "LOCAL_CANCELLED"); return nullptr; }
    jbyteArray result = env->NewByteArray(output.size());
    if (result) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
    return result;
}
