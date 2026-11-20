#include <jni.h>
#include <android/log.h>
#include <llama.h>
#include <algorithm>
#include <cstdio>
#include <unistd.h>
#include <sys/stat.h>
#include <atomic>
#include <memory>
#include <string>
#include <vector>

// Requests run on one Kotlin executor. Cancellation can arrive on another thread.
static std::atomic<bool> cancelled{false};
static std::atomic<int> work_threads{3};
static std::atomic<int> work_delay_ms{0};
static void apply_work_limits(llama_context *ctx, int &current_threads) {
    const int requested = std::clamp(work_threads.load(), 1, 4);
    if (requested != current_threads) {
        llama_set_n_threads(ctx, requested, requested);
        current_threads = requested;
    }
    int remaining = std::clamp(work_delay_ms.load(), 0, 250);
    while (remaining > 0 && !cancelled.load()) {
        const int chunk = std::min(remaining, 25);
        usleep(chunk * 1000);
        remaining -= chunk;
    }
}
// Accessed only by the single Kotlin inference executor. Each request still gets
// a fresh context and sampler, so conversation state cannot leak between calls.
static std::unique_ptr<llama_model, decltype(&llama_model_free)> cached_model(nullptr, llama_model_free);
static std::string cached_key;
static bool backend_ready = false;
static std::atomic<int> model_load_count{0};
static void release_model() {
    cached_model.reset();
    cached_key.clear();
    if (backend_ready) { llama_backend_free(); backend_ready = false; }
}
static bool abort_decode(void *) { return cancelled.load(); }
static void log_errors(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN)
        __android_log_write(ANDROID_LOG_WARN, "VoiceAgentLocal", text);
}
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
extern "C" JNIEXPORT void JNICALL
Java_com_voiceagent_oneplus13_local_LocalInference_nativeSetWorkLimits(JNIEnv *, jobject, jint threads, jint delay_ms) {
    work_threads = std::clamp(static_cast<int>(threads), 1, 4);
    work_delay_ms = std::clamp(static_cast<int>(delay_ms), 0, 250);
}
extern "C" JNIEXPORT void JNICALL
Java_com_voiceagent_oneplus13_local_LocalInference_nativeUnload(JNIEnv *, jobject) { release_model(); }
extern "C" JNIEXPORT jint JNICALL
Java_com_voiceagent_oneplus13_local_LocalInference_nativeModelLoadCount(JNIEnv *, jobject) { return model_load_count.load(); }
extern "C" JNIEXPORT jboolean JNICALL
Java_com_voiceagent_oneplus13_local_LocalInference_nativeHasCachedModel(JNIEnv *, jobject) { return cached_model != nullptr; }
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_voiceagent_oneplus13_local_LocalInference_nativeGenerate(JNIEnv *env, jobject, jstring jpath, jstring jkey, jint input_fd, jobjectArray jroles, jobjectArray jcontents, jint requested_tokens, jboolean thoughtful) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return nullptr;
    const std::string model_path(path);
    env->ReleaseStringUTFChars(jpath, path);
    const char *key_chars = env->GetStringUTFChars(jkey, nullptr);
    if (!key_chars) return nullptr;
    const std::string source_key(key_chars);
    env->ReleaseStringUTFChars(jkey, key_chars);

    llama_log_set(log_errors, nullptr);
    auto params = llama_model_default_params();
    params.n_gpu_layers = 0;
    std::unique_ptr<FILE, decltype(&fclose)> input(nullptr, fclose);
    if (input_fd >= 0) {
        int copied = dup(input_fd);
        if (copied < 0) { fail(env, "LOCAL_FILE_UNAVAILABLE"); return nullptr; }
        FILE *fp = fdopen(copied, "rb");
        if (!fp) { close(copied); fail(env, "LOCAL_FILE_UNAVAILABLE"); return nullptr; }
        input.reset(fp);
    }
    struct stat st{};
    const bool stable_file = (input ? fstat(fileno(input.get()), &st) : stat(model_path.c_str(), &st)) == 0 && S_ISREG(st.st_mode);
    const std::string identity = stable_file ? source_key + ":" + std::to_string(st.st_dev) + ":" +
        std::to_string(st.st_ino) + ":" + std::to_string(st.st_size) + ":" +
        std::to_string(st.st_mtim.tv_sec) + ":" + std::to_string(st.st_mtim.tv_nsec) : "";
    if (!cached_model || identity.empty() || identity != cached_key) {
        release_model();
        llama_backend_init();
        backend_ready = true;
        cached_model.reset(input ? llama_model_load_from_file_ptr(input.get(), params) : llama_model_load_from_file(model_path.c_str(), params));
        ++model_load_count;
        cached_key = identity;
    }
    auto &model = cached_model;
    if (!model) { fail(env, "LOCAL_MODEL_LOAD_FAILED"); return nullptr; }
    if (cancelled) { fail(env, "LOCAL_CANCELLED"); return nullptr; }
    const auto count = env->GetArrayLength(jroles);
    if (count <= 0 || count != env->GetArrayLength(jcontents) || count > 8) { fail(env, "LOCAL_INVALID_MESSAGES"); return nullptr; }
    std::vector<std::string> roles, contents;
    roles.reserve(count); contents.reserve(count);
    for (int i = 0; i < count; ++i) {
        auto role = static_cast<jbyteArray>(env->GetObjectArrayElement(jroles, i));
        auto content = static_cast<jbyteArray>(env->GetObjectArrayElement(jcontents, i));
        roles.push_back(bytes(env, role)); contents.push_back(bytes(env, content));
        env->DeleteLocalRef(role); env->DeleteLocalRef(content);
    }
    std::vector<llama_chat_message> messages;
    for (int i = 0; i < count; ++i) messages.push_back({roles[i].c_str(), contents[i].c_str()});
    const char *tmpl = llama_model_chat_template(model.get(), nullptr);
    if (!tmpl) tmpl = "chatml";
    int prompt_len = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, nullptr, 0);
    if (prompt_len <= 0 || prompt_len > 100000) { fail(env, "LOCAL_TEMPLATE_UNSUPPORTED"); return nullptr; }
    std::string prompt(prompt_len, '\0');
    if (llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, prompt.data(), prompt_len) != prompt_len) {
        fail(env, "LOCAL_TEMPLATE_UNSUPPORTED"); return nullptr;
    }
    // Qwen 3.5/3.8 supports explicit thinking/non-thinking chat prefixes.
    char arch[64] = {};
    llama_model_meta_val_str(model.get(), "general.architecture", arch, sizeof(arch));
    const bool qwen_reasoning = (std::string(arch) == "qwen35" || std::string(arch) == "qwen35moe") && thoughtful;
    if (std::string(arch) == "qwen35" || std::string(arch) == "qwen35moe") {
        prompt += qwen_reasoning ? "<think>\n" : "<think>\n\n</think>\n\n";
    }
    const auto *vocab = llama_model_get_vocab(model.get());
    const int n = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
    constexpr int context_size = 8192;
    const int max_new = std::clamp(static_cast<int>(requested_tokens), 1, 1536);
    if (n <= 0 || n > context_size - max_new) { fail(env, "LOCAL_PROMPT_TOO_LONG"); return nullptr; }
    std::vector<llama_token> tokens(n);
    if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), n, true, true) < 0) {
        fail(env, "LOCAL_TOKENIZE_FAILED"); return nullptr;
    }
    auto cp = llama_context_default_params();
    // Allocate only the context needed for this request, rounded to a batch.
    cp.n_ctx = std::max(512, ((n + max_new + 255) / 256) * 256);
    cp.n_batch = 256;
    cp.n_ubatch = 128;
    cp.n_threads = std::clamp(work_threads.load(), 1, 4);
    cp.n_threads_batch = cp.n_threads;
    cp.abort_callback = abort_decode;
    std::unique_ptr<llama_context, decltype(&llama_free)> ctx(llama_init_from_model(model.get(), cp), llama_free);
    if (!ctx) { fail(env, "LOCAL_CONTEXT_FAILED"); return nullptr; }
    auto sp = llama_sampler_chain_default_params();
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(sp), llama_sampler_free);
    if (!sampler) { fail(env, "LOCAL_SAMPLER_FAILED"); return nullptr; }
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
    int current_threads = cp.n_threads;
    for (int pos = 0; pos < n; pos += 256) {
        const int count = std::min(256, n - pos);
        apply_work_limits(ctx.get(), current_threads);
        if (cancelled || llama_decode(ctx.get(), llama_batch_get_one(tokens.data() + pos, count)) != 0) {
            fail(env, cancelled ? "LOCAL_CANCELLED" : "LOCAL_DECODE_FAILED"); return nullptr;
        }
    }
    std::string output = qwen_reasoning ? "<think>\n" : "";
    for (int i = 0; i < max_new && !cancelled; ++i) {
        apply_work_limits(ctx.get(), current_threads);
        if (cancelled) break;
        auto token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        char buf[256];
        const int length = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
        if (length < 0) { fail(env, "LOCAL_TOKEN_FAILED"); return nullptr; }
        output.append(buf, length);
        if (i + 1 == max_new) break; // No next token will be sampled from these logits.
        if (llama_decode(ctx.get(), llama_batch_get_one(&token, 1)) != 0) {
            fail(env, cancelled ? "LOCAL_CANCELLED" : "LOCAL_DECODE_FAILED"); return nullptr;
        }
    }
    if (cancelled) { fail(env, "LOCAL_CANCELLED"); return nullptr; }
    jbyteArray result = env->NewByteArray(output.size());
    if (result) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
    return result;
}
