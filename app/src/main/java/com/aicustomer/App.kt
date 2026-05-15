package com.aicustomer

import android.app.Application
import android.content.Intent
import android.os.Process
import android.util.Log
import com.aicustomer.data.local.AppDatabase
import com.aicustomer.data.model.Message
import com.aicustomer.engine.EmbeddingEngine
import com.aicustomer.engine.LlmEngine
import com.aicustomer.engine.ModelManager
import com.aicustomer.identity.IdentityManager
import com.aicustomer.memory.MemoryManager
import com.aicustomer.util.HarmonyCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class App : Application() {

    lateinit var llmEngine: LlmEngine
        private set
    lateinit var embeddingEngine: EmbeddingEngine
        private set
    lateinit var memoryManager: MemoryManager
        private set
    lateinit var identityManager: IdentityManager
        private set
    lateinit var modelManager: ModelManager
        private set

    var voiceCallViewModel: com.aicustomer.ui.viewmodel.VoiceCallViewModel? = null

    val pendingVoiceTranscripts = MutableStateFlow<List<Message>>(emptyList())

    lateinit var database: AppDatabase
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 模型提取锁 - 确保loadModel等待extractBundledModel完成
    private val modelExtractionMutex = Mutex()
    private var modelExtractionDone = false

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 全局异常捕获 - 捕获后重启Activity防止"屡次停止运行"
        // 崩溃重启保护：鸿蒙限制最多1次，标准Android允许多次
        val maxCrashes = if (HarmonyCompat.isHarmonyOS()) 1 else 3
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("App", "Uncaught exception on thread: ${thread.name}", throwable)
            val prefs = getSharedPreferences("crash_prefs", MODE_PRIVATE)
            val crashCount = prefs.getInt("crash_count", 0) + 1
            val lastCrashTime = prefs.getLong("last_crash_time", 0)
            val now = System.currentTimeMillis()

            if (crashCount <= maxCrashes && (crashCount == 1 || (now - lastCrashTime) > 30000)) {
                prefs.edit()
                    .putInt("crash_count", crashCount)
                    .putLong("last_crash_time", now)
                    .apply()
                try {
                    val intent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("crash", true)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("App", "Failed to restart activity", e)
                }
            } else {
                prefs.edit().putInt("crash_count", 0).apply()
                Log.w("App", "Too many crashes, giving up restart")
            }
            Process.killProcess(Process.myPid())
        }

        database = AppDatabase.getInstance(this)

        embeddingEngine = EmbeddingEngine(this)
        llmEngine = LlmEngine(this)
        memoryManager = MemoryManager(this, embeddingEngine, llmEngine, database)
        identityManager = IdentityManager(database)
        modelManager = ModelManager(this)

        appScope.launch {
            try {
                identityManager.initFromPresetsIfNeeded()
            } catch (e: Exception) {
                Log.e("App", "Failed to init identity presets", e)
            }
        }

        appScope.launch {
            modelExtractionMutex.withLock {
                try {
                    val extracted = modelManager.extractBundledModel()
                    if (!extracted) {
                        Log.w("App", "Bundled model extraction failed, app will need manual model install")
                    }
                } catch (e: Exception) {
                    Log.e("App", "Failed to extract bundled model", e)
                }
                modelExtractionDone = true
            }

            try {
                embeddingEngine.load()
                memoryManager.initVectorIndex()
            } catch (e: Exception) {
                Log.w("App", "Memory init failed", e)
            }

            try {
                // init placeholder - no default values to avoid overriding user config
            } catch (e: Exception) {
                Log.w("App", "Init placeholder failed", e)
            }

            // 加载知识文档
            try {
                val kbText = assets.open("knowledge_base.txt").bufferedReader().readText()
                memoryManager.clearDocuments()
                memoryManager.addDocument("客服工作流程", kbText)
                Log.i("App", "Knowledge base loaded: ${kbText.length} chars")
            } catch (e: Exception) {
                Log.w("App", "Failed to load knowledge base", e)
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        memoryManager.destroy()
    }

    /**
     * 等待模型提取完成 - ChatViewModel.loadModel调用此方法确保时序正确
     */
    suspend fun awaitModelExtraction() {
        if (modelExtractionDone) return
        modelExtractionMutex.withLock {
            // 获取锁即表示提取已完成
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
