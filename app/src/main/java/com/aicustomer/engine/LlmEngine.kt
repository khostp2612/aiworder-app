package com.aicustomer.engine

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.aicustomer.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LLM推理引擎 - 纯本地llama.cpp推理，支持 GPU 加速
 */
class LlmEngine(private val context: Context) {

    private var isLoaded = false
    private val isGeneratingFlag = AtomicBoolean(false)
    private val debugLogFile = File(context.filesDir, "llm_debug.log")

    init {
        if (nativeLibLoaded) {
            nativeSetDebugLogPath(debugLogFile.absolutePath)
        }
    }

    // Token 回调
    private var onTokenCallback: ((String) -> Unit)? = null

    // JNI Native 方法
    private external fun nativeSetDebugLogPath(path: String)
    private external fun nativeInit(modelPath: String, nGpuLayers: Int, nCtx: Int): Boolean
    private external fun nativeGenerateStreamCallback(enginePtr: Long, prompt: String, temperature: Float, maxTokens: Int)
    private external fun nativeGenerateSingle(enginePtr: Long, prompt: String, temperature: Float, maxTokens: Int): String
    private external fun nativeDestroy(enginePtr: Long)
    private external fun nativeAbort(enginePtr: Long)
    private external fun nativeGetContextSize(enginePtr: Long): Int
    private external fun nativeSetSystemPrompt(enginePtr: Long, systemPrompt: String)
    private external fun nativeInitAudio(mmprojPath: String): Boolean
    private external fun nativeGenerateWithAudio(prompt: String, pcm: FloatArray, nSamples: Int, temperature: Float, maxTokens: Int)
    private external fun nativeIsAudioLoaded(): Boolean
    private external fun nativeAudioDestroy()

    companion object {
        private var nativeLibLoaded = false

        init {
            try {
                System.loadLibrary("llm_bridge")
                nativeLibLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                Log.w("LlmEngine", "Native library llm_bridge not found")
            }
        }

        private val defaultGpuLayers: Int by lazy {
            val tier = DeviceTier.detect(App.instance)
            if (tier.hasVulkan) 99 else 0
        }

        private const val DEFAULT_MAX_TOKENS = 1024
        private const val DEFAULT_TEMPERATURE = 0.7f
        private const val ENGINE_PTR_DUMMY = 1L

        private const val TAG = "LlmEngine"
    }

    /**
     * JNI回调方法 - token到达
     */
    fun onNativeToken(token: String) {
        onTokenCallback?.invoke(token)
    }

    /**
     * JNI回调方法 - 生成完成
     */
    fun onNativeComplete() {
        // handled in generateStream flow closure
    }

    /**
     * JNI回调方法 - 生成出错
     */
    fun onNativeError(error: String) {
        onTokenCallback?.invoke("[错误：$error]")
    }

    fun hasEnoughMemory(requiredMB: Int = 800): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return true
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val availableMB = memInfo.availMem / (1024 * 1024)
        val tier = DeviceTier.detect(context)
        val required = when {
            tier.ramGB >= 12 -> 3000
            tier.ramGB >= 8 -> 2500
            tier.ramGB >= 6 -> 1500
            else -> requiredMB
        }
        Log.i(TAG, "Device tier=${tier.name}, ram=${tier.ramGB}GB, vulkan=${tier.hasVulkan}, availableMB=$availableMB, requiredMB=$required")
        return availableMB >= required
    }

    suspend fun loadModel(): Result<Unit> = runCatching {
        if (isLoaded) return Result.success(Unit)

        if (!nativeLibLoaded) {
            throw IllegalStateException("llama.cpp native库未加载")
        }

        val modelPath = (context.applicationContext as App).modelManager.getActiveLlmModelPath()
        if (modelPath == null) {
            throw IllegalStateException("本地模型文件不存在。\n请在「模型管理」中下载模型后重试。")
        }

        if (!hasEnoughMemory()) {
            throw IllegalStateException("可用内存不足，请关闭其他应用后重试。")
        }

        Log.i(TAG, "Loading model: $modelPath")

        val contextSize = DeviceTier.detect(context).maxContextSize
        val success = withContext(Dispatchers.IO) {
            nativeInit(modelPath, defaultGpuLayers, contextSize)
        }

        if (!success) {
            throw IllegalStateException("模型加载失败，请重试。")
        }

        isLoaded = true
        Log.i(TAG, "Model loaded successfully! GPU layers: $defaultGpuLayers")
    }

    suspend fun loadModelFromPath(modelPath: String): Result<Unit> = runCatching {
        unload()

        if (!nativeLibLoaded) {
            throw IllegalStateException("llama.cpp native库未加载")
        }

        Log.i(TAG, "Switching to model: $modelPath")

        val contextSize = DeviceTier.detect(context).maxContextSize
        val success = withContext(Dispatchers.IO) {
            nativeInit(modelPath, defaultGpuLayers, contextSize)
        }

        if (!success) {
            throw IllegalStateException("模型切换失败，请重试。")
        }

        isLoaded = true
    }

    /**
     * 流式生成 - 实时逐token输出
     * native层阻塞式生成，运行在后台线程
     */
    fun generateStream(
        prompt: String,
        temperature: Float = DEFAULT_TEMPERATURE,
        maxTokens: Int = DEFAULT_MAX_TOKENS
    ): Flow<String> {
        if (!isLoaded) {
            return kotlinx.coroutines.flow.flowOf("[模型未加载]")
        }

        val channel = Channel<String>(Channel.UNLIMITED)

        onTokenCallback = { token ->
            channel.trySend(token)
        }

        isGeneratingFlag.set(true)

        Thread {
            try {
                nativeGenerateStreamCallback(ENGINE_PTR_DUMMY, prompt, temperature, maxTokens)
            } catch (e: Exception) {
                Log.e(TAG, "Stream generation failed", e)
                channel.trySend("[生成出错：${e.message}]")
            } finally {
                isGeneratingFlag.set(false)
                onTokenCallback = null
                channel.close()
            }
        }.start()

        return channel.receiveAsFlow()
    }

    /**
     * 单次生成（非流式）- 用于记忆评分/压缩
     * 使用全局 mutex 保护，与流式生成互斥
     */
    suspend fun generate(
        prompt: String,
        temperature: Float = 0.3f,
        maxTokens: Int = 64
    ): String {
        if (!isLoaded) return "[模型未加载]"

        return withContext(Dispatchers.IO) {
            try {
                nativeGenerateSingle(ENGINE_PTR_DUMMY, prompt, temperature, maxTokens)
            } catch (e: Exception) {
                Log.e(TAG, "Generation failed", e)
                "[生成出错：${e.message}]"
            }
        }
    }

    fun setSystemPrompt(systemPrompt: String) {
        if (isLoaded) {
            nativeSetSystemPrompt(ENGINE_PTR_DUMMY, systemPrompt)
        }
    }

    fun abort() {
        if (isGeneratingFlag.get()) {
            nativeAbort(ENGINE_PTR_DUMMY)
        }
    }

    fun unload() {
        if (isLoaded) {
            nativeDestroy(ENGINE_PTR_DUMMY)
        }
        isLoaded = false
    }

    fun getContextSize(): Int = DeviceTier.detect(context).maxContextSize

    fun isLoaded(): Boolean = isLoaded
    fun isGenerating(): Boolean = isGeneratingFlag.get()
}
