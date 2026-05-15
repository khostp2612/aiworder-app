package com.aicustomer.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

class ModelManager(private val context: Context) {

    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class ModelInfo(
        val name: String,
        val dir: String,
        val description: String,
        val sizeBytes: Long,
        val downloadUrls: List<String>,
        val requiredFiles: List<String>,
        val isBundled: Boolean = false
    ) {
        constructor(
            name: String,
            dir: String,
            description: String,
            sizeBytes: Long,
            downloadUrl: String,
            requiredFiles: List<String>,
            isBundled: Boolean = false
        ) : this(name, dir, description, sizeBytes, listOf(downloadUrl), requiredFiles, isBundled)
    }

    companion object {
        private const val TAG = "ModelManager"
        private const val PREFS_NAME = "model_prefs"
        private const val KEY_ACTIVE_LLM = "active_llm_dir"

        val LLM_DIR = "qwen3-1.7b"
        val EMBED_DIR = "embed-gte-small-zh"
        val STT_DIR = "sense-voice"
        val VAD_DIR = "vad-silero"
        val TTS_DIR = "tts-vits-zh"

        val LLM_FILE_Q4 = "Qwen3-1.7B-Q4_K_M.gguf"
    }

    fun resolveModelFile(): String = LLM_FILE_Q4

    val models = listOf(
        ModelInfo(
            name = "Qwen3-1.7B (Q4_K_M)",
            dir = LLM_DIR,
            description = "Qwen3 1.7B 端侧对话，约1.05GB，快速流畅",
            sizeBytes = 1_056_000_000L,
            downloadUrls = listOf(
                "https://modelscope.cn/unsloth/Qwen3-1.7B-GGUF/resolve/master/Qwen3-1.7B-Q4_K_M.gguf"
            ),
            requiredFiles = listOf(LLM_FILE_Q4),
            isBundled = false
        ),
        ModelInfo(
            name = "SenseVoice 中文 STT (int8)",
            dir = STT_DIR,
            description = "ali-damo SenseVoice 离线语音识别，228MB，支持中英日韩粤",
            sizeBytes = 228_000_000L,
            downloadUrls = listOf(
                "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/resolve/main/model.int8.onnx",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
            ),
            requiredFiles = listOf(
                "model.int8.onnx",
                "tokens.txt"
            ),
            isBundled = true
        ),
        ModelInfo(
            name = "Silero VAD",
            dir = VAD_DIR,
            description = "语音端点检测，开箱即用",
            sizeBytes = 2_200_000L,
            downloadUrls = listOf(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
            ),
            requiredFiles = listOf("silero_vad.onnx"),
            isBundled = true
        )
    )

    private val _activeLlmDir = MutableStateFlow<String?>(null)
    val activeLlmDir: StateFlow<String?> = _activeLlmDir.asStateFlow()

    private val _upgradeProgress = MutableStateFlow(-1f)
    val upgradeProgress: StateFlow<Float> = _upgradeProgress.asStateFlow()

    fun isModelDownloaded(modelInfo: ModelInfo): Boolean {
        val modelDir = File(context.filesDir, "models/${modelInfo.dir}")
        if (!modelDir.exists()) return false
        return modelInfo.requiredFiles.all { fileName ->
            File(modelDir, fileName).exists()
        }
    }

    fun getDownloadedSize(): Long {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) return 0L
        return modelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun downloadModel(modelInfo: ModelInfo): Flow<Float> = channelFlow {
        val modelDir = File(context.filesDir, "models/${modelInfo.dir}")
        modelDir.mkdirs()

        if (isModelDownloaded(modelInfo)) {
            send(1.0f)
            return@channelFlow
        }

        val progressHolder = AtomicReference(0f)

        val progressJob = launch {
            var lastSent = -1f
            while (true) {
                val current = progressHolder.get()
                if (current != lastSent) {
                    send(current)
                    lastSent = current
                }
                if (current >= 1.0f) break
                kotlinx.coroutines.delay(200)
            }
        }

        try {
            if (modelInfo.requiredFiles.size == 1) {
                val fileName = modelInfo.requiredFiles[0]
                val targetFile = File(modelDir, fileName)
                if (!targetFile.exists()) {
                    downloadFileWithMirrors(modelInfo, targetFile) { progress ->
                        progressHolder.set(progress)
                    }
                }
            } else {
                if (!isModelDownloaded(modelInfo)) {
                    val tempFile = File(context.cacheDir, "${modelInfo.dir}.tar.bz2")
                    try {
                        downloadFileWithMirrors(modelInfo, tempFile) { progress ->
                            progressHolder.set(progress * 0.9f)
                        }
                        send(0.9f)
                        progressHolder.set(0.9f)
                        extractTarBz2(tempFile, modelDir)
                        moveFilesFromSubdir(modelDir, modelInfo.requiredFiles)
                    } finally {
                        tempFile.delete()
                    }
                }
            }
            progressHolder.set(1.0f)
            send(1.0f)
        } finally {
            progressJob.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun moveFilesFromSubdir(modelDir: File, requiredFiles: List<String>) {
        if (requiredFiles.all { File(modelDir, it).exists() }) return
        val subDirs = modelDir.listFiles()?.filter { it.isDirectory } ?: return
        for (subDir in subDirs) {
            for (fileName in requiredFiles) {
                val src = File(subDir, fileName)
                if (src.exists()) {
                    src.copyTo(File(modelDir, fileName), overwrite = true)
                }
            }
        }
    }

    private fun downloadFileWithMirrors(
        modelInfo: ModelInfo,
        targetFile: File,
        onProgress: (Float) -> Unit
    ) {
        val urls = modelInfo.downloadUrls.ifEmpty {
            throw RuntimeException("模型 ${modelInfo.name} 没有可用的下载地址")
        }

        var lastException: Exception? = null

        for ((index, url) in urls.withIndex()) {
            try {
                Log.i(TAG, "Trying mirror ${index + 1}/${urls.size}: $url")
                downloadFileWithProgress(url, targetFile, onProgress)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Mirror ${index + 1} failed: ${e.message}")
                lastException = e
                File(targetFile.parent, "${targetFile.name}.downloading").delete()
                if (targetFile.exists()) targetFile.delete()
            }
        }

        throw lastException ?: RuntimeException("所有镜像下载失败: ${modelInfo.name}")
    }

    private fun downloadFileWithProgress(
        urlStr: String,
        targetFile: File,
        onProgress: (Float) -> Unit
    ) {
        var lastException: Exception? = null

        for (attempt in 1..3) {
            try {
                downloadFileOnce(urlStr, targetFile, onProgress)
                return
            } catch (e: Exception) {
                lastException = e
                File(targetFile.parent, "${targetFile.name}.downloading").delete()
                if (targetFile.exists()) targetFile.delete()
                Thread.sleep(2000L * attempt)
            }
        }

        throw lastException ?: RuntimeException("下载失败: $urlStr")
    }

    private fun downloadFileOnce(
        urlStr: String,
        targetFile: File,
        onProgress: (Float) -> Unit
    ) {
        var currentUrl = urlStr
        var redirectCount = 0

        while (redirectCount < 10) {
            val url = URL(currentUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30000
            connection.readTimeout = 600000
            connection.setRequestProperty("User-Agent", "AIWORK/1.0")

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == HttpURLConnection.HTTP_SEE_OTHER
            ) {
                val newUrl = connection.getHeaderField("Location")
                connection.disconnect()
                if (newUrl.isNullOrEmpty()) throw RuntimeException("重定向但无Location头")
                currentUrl = newUrl
                redirectCount++
                continue
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw RuntimeException("HTTP $responseCode 下载失败: $currentUrl")
            }

            val totalSize = connection.contentLengthLong
            val tempTarget = File(targetFile.parent, "${targetFile.name}.downloading")

            try {
                connection.inputStream.use { input ->
                    FileOutputStream(tempTarget).use { output ->
                        val buffer = ByteArray(8192)
                        var downloadedSize = 0L
                        var lastProgressUpdate = 0L

                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloadedSize += read

                            val now = System.currentTimeMillis()
                            if (totalSize > 0 && (downloadedSize - lastProgressUpdate > 65536 || now - lastProgressUpdate > 500)) {
                                onProgress(downloadedSize.toFloat() / totalSize)
                                lastProgressUpdate = downloadedSize
                            }
                        }

                        if (totalSize > 0) {
                            onProgress(downloadedSize.toFloat() / totalSize)
                        } else {
                            onProgress(1.0f)
                        }
                    }
                }

                if (tempTarget.exists()) {
                    tempTarget.renameTo(targetFile)
                }
            } catch (e: Exception) {
                tempTarget.delete()
                throw e
            } finally {
                connection.disconnect()
            }
            return
        }

        throw RuntimeException("重定向次数过多: $urlStr")
    }

    private fun extractTarBz2(archiveFile: File, targetDir: File) {
        val process = ProcessBuilder(
            "tar", "xjf", archiveFile.absolutePath,
            "-C", targetDir.absolutePath
        )
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw RuntimeException("解压失败 (exit=$exitCode): $output")
        }
    }

    fun deleteModel(modelInfo: ModelInfo) {
        val modelDir = File(context.filesDir, "models/${modelInfo.dir}")
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }
    }

    fun getModelStatuses(): List<Pair<ModelManager.ModelInfo, Boolean>> {
        return models.map { it to isModelDownloaded(it) }
    }

    fun areCoreModelsReady(): Boolean {
        return models.any { it.dir == LLM_DIR && isModelDownloaded(it) }
    }

    fun getActiveLlmModel(): ModelInfo? {
        return models.find { it.dir == LLM_DIR && isModelDownloaded(it) }
    }

    fun getActiveLlmModelPath(): String? {
        val model = getActiveLlmModel() ?: return null
        val modelDir = File(context.filesDir, "models/${model.dir}")
        val modelFile = File(modelDir, model.requiredFiles[0])
        return if (modelFile.exists()) modelFile.absolutePath else null
    }

    fun isUsingEnhancedModel(): Boolean = getActiveLlmModel()?.name?.contains("Q4_K_M") == true

    suspend fun extractBundledModel(): Boolean {
        var allOk = true
        for (bundledModel in models.filter { it.isBundled }) {
            val modelDir = File(context.filesDir, "models/${bundledModel.dir}")
            val allExtracted = bundledModel.requiredFiles.all { File(modelDir, it).exists() }

            if (allExtracted) {
                Log.i(TAG, "Bundled model already extracted: ${bundledModel.dir}")
                continue
            }

            try {
                modelDir.mkdirs()
                Log.i(TAG, "Extracting bundled model from assets: ${bundledModel.dir}")

                withContext(Dispatchers.IO) {
                    for (fileName in bundledModel.requiredFiles) {
                        val assetPath = "models/${bundledModel.dir}/$fileName"
                        val targetFile = File(modelDir, fileName)
                        context.assets.open(assetPath).use { input ->
                            FileOutputStream(targetFile).use { output ->
                                val buffer = ByteArray(8192)
                                var totalRead = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    output.write(buffer, 0, read)
                                    totalRead += read
                                }
                                Log.i(TAG, "Extracted: $fileName (${totalRead / 1024}KB)")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract bundled model: ${bundledModel.dir}", e)
                allOk = false
            }
        }
        return allOk
    }

    fun startManualDownload(modelInfo: ModelInfo, onComplete: (() -> Unit)? = null) {
        if (isModelDownloaded(modelInfo)) {
            _upgradeProgress.value = 1f
            _activeLlmDir.value = modelInfo.dir
            return
        }

        if (_upgradeProgress.value in 0f..0.99f) {
            return
        }

        modelScope.launch {
            try {
                _upgradeProgress.value = 0f
                Log.i(TAG, "Starting download of ${modelInfo.name}...")

                downloadModel(modelInfo).collect { progress ->
                    _upgradeProgress.value = progress
                }

                if (isModelDownloaded(modelInfo)) {
                    _activeLlmDir.value = modelInfo.dir
                    Log.i(TAG, "${modelInfo.name} downloaded, ready to switch")
                    onComplete?.invoke()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _upgradeProgress.value = -1f
            }
        }
    }

    fun destroy() {
        modelScope.cancel()
    }
}