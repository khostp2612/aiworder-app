#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "SherpaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * Sherpa-ONNX JNI桥接层 (预留 / 未使用)
 *
 * 此文件为设计时为JNI方案预留的桥接层骨架。
 * 实际项目使用Sherpa-ONNX的Java API（通过AAR直接集成），
 * 无需使用C++ JNI桥接。STT/TTS/VAD功能均在Kotlin层通过Sherpa Java API实现。
 *
 * 保留此文件作为未来可能的性能优化方案参考（如需JNI直调降低延迟）。
 * 当前不在CMakeLists.txt中编译。
 */

extern "C" {

// ========== STT (语音识别) ==========

// 初始化STT引擎
JNIEXPORT jlong JNICALL
Java_com_aicustomer_engine_SttEngine_nativeInitStt(
        JNIEnv *env, jobject thiz,
        jstring model_dir) {
    const char *dir = env->GetStringUTFChars(model_dir, nullptr);
    LOGI("Initializing STT engine: %s", dir);

    // TODO: 调用Sherpa-ONNX初始化STT
    // auto recognizer = sherpa_onnx::OfflineRecognizer::Create(config);

    env->ReleaseStringUTFChars(model_dir, dir);
    return 0;
}

// 处理音频流数据
JNIEXPORT jstring JNICALL
Java_com_aicustomer_engine_SttEngine_nativeProcessAudio(
        JNIEnv *env, jobject thiz,
        jlong stt_ptr, jshortArray audio_data, jint sample_rate) {
    // TODO: 流式识别处理
    return env->NewStringUTF("");
}

// 释放STT引擎
JNIEXPORT void JNICALL
Java_com_aicustomer_engine_SttEngine_nativeDestroyStt(
        JNIEnv *env, jobject thiz, jlong stt_ptr) {
    LOGI("Destroying STT engine");
}

// ========== TTS (语音合成) ==========

// 初始化TTS引擎
JNIEXPORT jlong JNICALL
Java_com_aicustomer_engine_TtsEngine_nativeInitTts(
        JNIEnv *env, jobject thiz,
        jstring model_dir) {
    const char *dir = env->GetStringUTFChars(model_dir, nullptr);
    LOGI("Initializing TTS engine: %s", dir);

    // TODO: 调用Sherpa-ONNX初始化TTS

    env->ReleaseStringUTFChars(model_dir, dir);
    return 0;
}

// 合成音频
JNIEXPORT jbyteArray JNICALL
Java_com_aicustomer_engine_TtsEngine_nativeSynthesize(
        JNIEnv *env, jobject thiz,
        jlong tts_ptr, jstring text) {
    const char *text_str = env->GetStringUTFChars(text, nullptr);
    LOGI("Synthesizing: %s", text_str);

    // TODO: 调用Sherpa-ONNX TTS合成
    // 返回PCM音频数据

    env->ReleaseStringUTFChars(text, text_str);
    return env->NewByteArray(0);
}

// 释放TTS引擎
JNIEXPORT void JNICALL
Java_com_aicustomer_engine_TtsEngine_nativeDestroyTts(
        JNIEnv *env, jobject thiz, jlong tts_ptr) {
    LOGI("Destroying TTS engine");
}

// ========== VAD (语音活动检测) ==========

// 初始化VAD
JNIEXPORT jlong JNICALL
Java_com_aicustomer_voice_VadDetector_nativeInitVad(
        JNIEnv *env, jobject thiz) {
    LOGI("Initializing VAD");
    return 0;
}

// 检测音频中是否有语音活动
JNIEXPORT jboolean JNICALL
Java_com_aicustomer_voice_VadDetector_nativeDetect(
        JNIEnv *env, jobject thiz,
        jlong vad_ptr, jshortArray audio_data) {
    // TODO: VAD检测
    return JNI_FALSE;
}

// 释放VAD
JNIEXPORT void JNICALL
Java_com_aicustomer_voice_VadDetector_nativeDestroyVad(
        JNIEnv *env, jobject thiz, jlong vad_ptr) {
    LOGI("Destroying VAD");
}

} // extern "C"
