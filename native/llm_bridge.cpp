#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdio>
#include <cstdarg>
#include <atomic>
#include <thread>
#include <mutex>
#include <fstream>
#include <unistd.h>
#include <algorithm>

#include "llama.h"
#include "common.h"
#include "chat.h"
#include "sampling.h"

#define TAG "LLMBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::string debug_log_path;
static void debug_log(const char *fmt, ...) {
    char buf[4096];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    __android_log_print(ANDROID_LOG_WARN, TAG, "%s", buf);
    if (!debug_log_path.empty()) {
        std::ofstream f(debug_log_path, std::ios::app);
        if (f.is_open()) f << buf << std::endl;
    }
}

static jstring safeNewStringUTF(JNIEnv *env, const std::string &str) {
    std::string clean;
    clean.reserve(str.size());
    for (size_t i = 0; i < str.size(); ) {
        unsigned char c = str[i];
        int len = 1;
        if (c < 0x80) len = 1;
        else if ((c & 0xE0) == 0xC0) len = 2;
        else if ((c & 0xF0) == 0xE0) len = 3;
        else if ((c & 0xF8) == 0xF0) len = 4;
        else { clean += '?'; i++; continue; }
        if (i + len > str.size()) { clean += '?'; break; }
        bool valid = true;
        for (int j = 1; j < len; j++) {
            if ((str[i+j] & 0xC0) != 0x80) { valid = false; break; }
        }
        if (valid) clean.append(str, i, len);
        else clean += '?';
        i += len;
    }
    return env->NewStringUTF(clean.c_str());
}

struct GenerationContext {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    const llama_vocab *vocab = nullptr;
    common_chat_templates_ptr templates = nullptr;
    llama_batch batch;
    std::vector<llama_token> tokens;
    std::atomic<bool> stop_flag{false};
    std::string system_prompt;

    jobject jcallback = nullptr;
    jmethodID on_token_method = nullptr;
    jmethodID on_complete_method = nullptr;
    jmethodID on_error_method = nullptr;

    int n_batch = 512;
    int n_len = 512;
    float temperature = 0.3f;
    float top_p = 0.8f;
    int top_k = 40;
};

static GenerationContext g_ctx;
static std::mutex g_mutex;

static void log_callback(ggml_log_level level, const char *text, void *user_data) {
    if (strstr(text, "tensor[")) return;
    if (level <= GGML_LOG_LEVEL_WARN) {
        LOGE("%s", text);
    } else {
        LOGI("%s", text);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aicustomer_engine_LlmEngine_nativeSetDebugLogPath(
    JNIEnv *env, jobject thiz, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (path) {
        debug_log_path = path;
        std::ofstream f(debug_log_path, std::ios::trunc);
        f << "=== AI Customer LLM Debug Log ===" << std::endl;
        f.close();
        env->ReleaseStringUTFChars(jpath, path);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aicustomer_engine_LlmEngine_nativeInit(
    JNIEnv *env, jobject thiz, jstring jpath,
    jint nGpuLayers, jint nCtx) {

    std::lock_guard<std::mutex> lock(g_mutex);

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return JNI_FALSE;

    LOGI("Loading model from: %s", path);
    debug_log("nativeInit: model loaded");

    ggml_log_set(log_callback, nullptr);

    llama_backend_init();
    llama_numa_init(GGML_NUMA_STRATEGY_DISABLED);

    g_ctx.n_len = 512;

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = nGpuLayers;

    LOGI("GPU layers: %d, context size: %d", nGpuLayers, nCtx);
    debug_log("GPU layers: %d, context: %d", nGpuLayers, nCtx);

    g_ctx.model = llama_model_load_from_file(path, model_params);
    if (!g_ctx.model) {
        LOGE("Failed to load model");
        env->ReleaseStringUTFChars(jpath, path);
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = nCtx;
    ctx_params.n_batch = g_ctx.n_batch;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;

    // 自适应线程数：留1核给UI/音频，其余用于推理
    int cores = sysconf(_SC_NPROCESSORS_CONF);
    if (cores <= 0) cores = 4;
    int inference_threads = std::min(cores - 1, 8);
    if (inference_threads < 1) inference_threads = 1;
    int batch_threads = std::min(inference_threads, cores);

    ctx_params.n_threads = inference_threads;
    ctx_params.n_threads_batch = batch_threads;

    LOGI("Using %d/%d cores for inference, %d for batch",
         inference_threads, cores, batch_threads);
    debug_log("Threads: infer=%d batch=%d cores=%d",
              inference_threads, batch_threads, cores);

    g_ctx.ctx = llama_init_from_model(g_ctx.model, ctx_params);
    if (!g_ctx.ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_ctx.model);
        g_ctx.model = nullptr;
        env->ReleaseStringUTFChars(jpath, path);
        return JNI_FALSE;
    }

    g_ctx.vocab = llama_model_get_vocab(g_ctx.model);
    g_ctx.batch = llama_batch_init(g_ctx.n_batch, 0, 1);
    g_ctx.templates = common_chat_templates_init(g_ctx.model, "");

    // Sampler is built per-generation in generation_thread() with current params
    g_ctx.system_prompt = "You are Qwen, an AI assistant created by Alibaba Cloud. You are helpful, honest, and harmless.";

    env->ReleaseStringUTFChars(jpath, path);
    LOGI("Model loaded successfully");
    debug_log("Model loaded OK");
    return JNI_TRUE;
}

static void send_token_to_java(JNIEnv *env, const std::string &text) {
    if (!env || !g_ctx.jcallback || !g_ctx.on_token_method) return;

    jstring jtext = safeNewStringUTF(env, text);
    if (jtext) {
        env->CallVoidMethod(g_ctx.jcallback, g_ctx.on_token_method, jtext);
        env->DeleteLocalRef(jtext);
    }
}

static void send_complete_to_java(JNIEnv *env) {
    if (!env || !g_ctx.jcallback || !g_ctx.on_complete_method) return;
    env->CallVoidMethod(g_ctx.jcallback, g_ctx.on_complete_method);
}

static void send_error_to_java(JNIEnv *env, const std::string &error) {
    if (!env || !g_ctx.jcallback || !g_ctx.on_error_method) return;

    jstring jerror = safeNewStringUTF(env, error);
    if (jerror) {
        env->CallVoidMethod(g_ctx.jcallback, g_ctx.on_error_method, jerror);
        env->DeleteLocalRef(jerror);
    }
}

static std::string build_prompt(const std::string &user_input) {
    std::string prompt;
    prompt += "<|im_start|>system\n";
    prompt += g_ctx.system_prompt;
    prompt += "<|im_end|>\n";
    prompt += "<|im_start|>user\n";
    prompt += user_input;
    prompt += "<|im_end|>\n";
    prompt += "<|im_start|>assistant\n";
    return prompt;
}

static void generation_thread(JNIEnv *env, const std::string &user_input) {
    if (!g_ctx.model || !g_ctx.ctx || !g_ctx.vocab) {
        send_error_to_java(env, "Model not loaded");
        return;
    }

    g_ctx.stop_flag = false;
    g_ctx.tokens.clear();

    debug_log("generation_thread: starting, prompt length=%zu", user_input.size());

    // Clear KV cache for a fresh conversation turn
    llama_memory_clear(llama_get_memory(g_ctx.ctx), true);

    // Use the model's built-in chat template with thinking DISABLED
    common_chat_templates_inputs inputs;
    inputs.messages = {
        {"system", g_ctx.system_prompt},
        {"user", user_input}
    };
    inputs.enable_thinking = false;
    inputs.use_jinja = true;
    inputs.add_generation_prompt = true;

    auto params = common_chat_templates_apply(g_ctx.templates.get(), inputs);
    std::string prompt = params.prompt;

    // Security: do not log full prompt content

    // Tokenize the prompt
    std::vector<llama_token> prompt_tokens;
    prompt_tokens.resize(prompt.size() + 256); // generous upper bound
    int n_tokens = llama_tokenize(g_ctx.vocab, prompt.c_str(), prompt.size(),
                                   prompt_tokens.data(), prompt_tokens.size(), true, true);
    prompt_tokens.resize(n_tokens);
    debug_log("Tokenized: %d tokens", n_tokens);

    // Build sampler chain with current parameters
    {
        llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
        g_ctx.sampler = llama_sampler_chain_init(sparams);
        if (!g_ctx.sampler) {
            send_error_to_java(env, "Sampler chain init failed");
            return;
        }
        llama_sampler_chain_add(g_ctx.sampler, llama_sampler_init_temp(g_ctx.temperature));
        llama_sampler_chain_add(g_ctx.sampler, llama_sampler_init_top_p(g_ctx.top_p, 1));
        llama_sampler_chain_add(g_ctx.sampler, llama_sampler_init_top_k(g_ctx.top_k));
        llama_sampler_chain_add(g_ctx.sampler, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(g_ctx.sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    // Process prompt in batches
    int n_batch = g_ctx.n_batch;
    int n_all = (int)prompt_tokens.size();
    for (int i = 0; i < n_all; i += n_batch) {
        int n_tokens_in_batch = std::min(n_batch, n_all - i);
        common_batch_clear(g_ctx.batch);
        for (int j = 0; j < n_tokens_in_batch; j++) {
            int pos = i + j;
            bool need_logits = (pos == n_all - 1);
            common_batch_add(g_ctx.batch, prompt_tokens[pos], pos, {0}, need_logits);
        }
        if (llama_decode(g_ctx.ctx, g_ctx.batch) != 0) {
            send_error_to_java(env, "Decode failed during prompt processing");
            if (g_ctx.sampler) {
                llama_sampler_free(g_ctx.sampler);
                g_ctx.sampler = nullptr;
            }
            return;
        }
    }

    // Generate tokens
    std::string full_response;
    llama_token new_token_id;

    send_token_to_java(env, "__GEN_START__");

    for (int i = 0; i < g_ctx.n_len; i++) {
        if (g_ctx.stop_flag) break;

        new_token_id = llama_sampler_sample(g_ctx.sampler, g_ctx.ctx, -1);

        debug_log("Token %d: id=%d eog=%d", i, new_token_id,
                  llama_vocab_is_eog(g_ctx.vocab, new_token_id));

        if (llama_vocab_is_eog(g_ctx.vocab, new_token_id)) {
            break;
        }

        char buf[1024];
        int n_chars = llama_token_to_piece(g_ctx.vocab, new_token_id, buf, sizeof(buf), 0, true);
        std::string token_text(buf, n_chars);

        full_response += token_text;
        send_token_to_java(env, token_text);

        common_batch_clear(g_ctx.batch);
        common_batch_add(g_ctx.batch, new_token_id, prompt_tokens.size() + i, {0}, true);

        if (llama_decode(g_ctx.ctx, g_ctx.batch) != 0) {
            send_error_to_java(env, "Decode failed during generation");
            if (g_ctx.sampler) {
                llama_sampler_free(g_ctx.sampler);
                g_ctx.sampler = nullptr;
            }
            return;
        }
    }

    // Cleanup sampler
    if (g_ctx.sampler) {
        llama_sampler_free(g_ctx.sampler);
        g_ctx.sampler = nullptr;
    }

    send_complete_to_java(env);
    LOGI("Generation complete, length: %zu", full_response.size());
}

extern "C" JNIEXPORT void JNICALL
Java_com_aicustomer_engine_LlmEngine_nativeGenerateStreamCallback(
    JNIEnv *env, jobject thiz, jlong engine_ptr, jstring jinput,
    jfloat temperature, jint maxTokens) {

    std::lock_guard<std::mutex> lock(g_mutex);

    const char *input = env->GetStringUTFChars(jinput, nullptr);
    if (!input) return;
    std::string input_str(input);
    env->ReleaseStringUTFChars(jinput, input);

    g_ctx.temperature = temperature;
    g_ctx.n_len = maxTokens;
    g_ctx.jcallback = env->NewGlobalRef(thiz);

    jclass callback_class = env->GetObjectClass(thiz);
    g_ctx.on_token_method = env->GetMethodID(callback_class, "onNativeToken", "(Ljava/lang/String;)V");
    g_ctx.on_complete_method = env->GetMethodID(callback_class, "onNativeComplete", "()V");
    g_ctx.on_error_method = env->GetMethodID(callback_class, "onNativeError", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(callback_class);

    // Run generation directly on this thread (no detach!)
    generation_thread(env, input_str);

    if (g_ctx.jcallback) {
        env->DeleteGlobalRef(g_ctx.jcallback);
        g_ctx.jcallback = nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aicustomer_engine_LlmEngine_nativeAbort(JNIEnv *env, jobject thiz, jlong engine_ptr) {
    g_ctx.stop_flag = true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aicustomer_engine_LlmEngine_nativeIsLoaded(JNIEnv *env, jobject thiz) {
    return g_ctx.model != nullptr && g_ctx.ctx != nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aicustomer_engine_LlmEngine_nativeDestroy(JNIEnv *env, jobject thiz, jlong engine_ptr) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_ctx.stop_flag = true;

    // Sampler is per-generation, freed in generation_thread / audio_generation_thread
    if (g_ctx.batch.n_tokens >= 0) {
        llama_batch_free(g_ctx.batch);
    }
    if (g_ctx.ctx) {
        llama_free(g_ctx.ctx);
        g_ctx.ctx = nullptr;
    }
    if (g_ctx.model) {
        llama_model_free(g_ctx.model);
        g_ctx.model = nullptr;
    }

    if (g_ctx.jcallback) {
        env->DeleteGlobalRef(g_ctx.jcallback);
        g_ctx.jcallback = nullptr;
    }

    if (g_ctx.templates) {
        g_ctx.templates.reset();
    }

    llama_backend_free();
    LOGI("Model unloaded");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aicustomer_engine_LlmEngine_nativeGenerateSingle(
    JNIEnv *env, jobject thiz, jlong engine_ptr, jstring jinput,
    jfloat temperature, jint maxTokens) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_ctx.model || !g_ctx.ctx || !g_ctx.vocab) {
        return env->NewStringUTF("[Model not loaded]");
    }

    const char *input = env->GetStringUTFChars(jinput, nullptr);
    if (!input) return env->NewStringUTF("");
    std::string input_str(input);
    env->ReleaseStringUTFChars(jinput, input);

    llama_memory_clear(llama_get_memory(g_ctx.ctx), true);

    common_chat_templates_inputs inputs;
    inputs.messages = {{"user", input_str}};
    inputs.enable_thinking = false;
    inputs.use_jinja = true;
    inputs.add_generation_prompt = true;

    auto params = common_chat_templates_apply(g_ctx.templates.get(), inputs);
    std::string prompt = params.prompt;

    std::vector<llama_token> prompt_tokens;
    prompt_tokens.resize(prompt.size() + 256);
    int n_tokens = llama_tokenize(g_ctx.vocab, prompt.c_str(), prompt.size(),
                                   prompt_tokens.data(), prompt_tokens.size(), true, true);
    prompt_tokens.resize(n_tokens);

    common_batch_clear(g_ctx.batch);
    for (int j = 0; j < n_tokens; j++) {
        common_batch_add(g_ctx.batch, prompt_tokens[j], j, {0}, j == n_tokens - 1);
    }
    if (llama_decode(g_ctx.ctx, g_ctx.batch) != 0) {
        return env->NewStringUTF("[Decode failed]");
    }

    std::string result;
    for (int i = 0; i < maxTokens && !g_ctx.stop_flag; i++) {
        float *logits = llama_get_logits_ith(g_ctx.ctx, -1);
        if (!logits) break;
        llama_token new_token_id = 0;
        float max_logit = -1e38f;
        int n_voc = llama_vocab_n_tokens(g_ctx.vocab);
        for (int j = 0; j < n_voc; j++) {
            if (logits[j] > max_logit) { max_logit = logits[j]; new_token_id = j; }
        }
        if (llama_vocab_is_eog(g_ctx.vocab, new_token_id)) break;
        char buf[256];
        int n = llama_token_to_piece(g_ctx.vocab, new_token_id, buf, sizeof(buf), 0, true);
        result.append(buf, n);
        common_batch_clear(g_ctx.batch);
        common_batch_add(g_ctx.batch, new_token_id, n_tokens + i, {0}, true);
        if (llama_decode(g_ctx.ctx, g_ctx.batch) != 0) break;
    }

    return safeNewStringUTF(env, result);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aicustomer_engine_LlmEngine_nativeGetContextSize(
    JNIEnv *env, jobject thiz, jlong engine_ptr) {
    return g_ctx.ctx ? llama_n_ctx(g_ctx.ctx) : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aicustomer_engine_LlmEngine_nativeSetSystemPrompt(
    JNIEnv *env, jobject thiz, jlong engine_ptr, jstring system_prompt) {
    const char *sp = env->GetStringUTFChars(system_prompt, nullptr);
    if (sp) {
        g_ctx.system_prompt = sp;
        env->ReleaseStringUTFChars(system_prompt, sp);
    }
}

