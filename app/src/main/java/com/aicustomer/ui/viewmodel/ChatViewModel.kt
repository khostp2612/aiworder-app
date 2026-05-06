package com.aicustomer.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aicustomer.App
import com.aicustomer.data.model.Message
import com.aicustomer.engine.InferenceService
import com.aicustomer.identity.PromptBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 聊天ViewModel - 连接UI和引擎层
 */
class ChatViewModel(application: Application) : ViewModel() {

    private val app = application as App
    private val llmEngine = app.llmEngine
    private val memoryManager = app.memoryManager
    private val identityManager = app.identityManager
    private val promptBuilder = PromptBuilder()

    // UI状态
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isVoiceMode = MutableStateFlow(false)
    val isVoiceMode: StateFlow<Boolean> = _isVoiceMode.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _aiStatus = MutableStateFlow("就绪")
    val aiStatus: StateFlow<String> = _aiStatus.asStateFlow()

    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    private val _modelLoadError = MutableStateFlow<String?>(null)
    val modelLoadError: StateFlow<String?> = _modelLoadError.asStateFlow()

    private val _activeIdentityName = MutableStateFlow("")
    val activeIdentityName: StateFlow<String> = _activeIdentityName.asStateFlow()

    private val _useCloudLlm = MutableStateFlow(true)
    val useCloudLlm: StateFlow<Boolean> = _useCloudLlm.asStateFlow()
    private val _cloudAvailable = MutableStateFlow(false)
    val cloudAvailable: StateFlow<Boolean> = _cloudAvailable.asStateFlow()

    // 文档导入对话框
    var showImportDialog by mutableStateOf(false)
    var importDocTitle by mutableStateOf("")
    var importDocContent by mutableStateOf("")
    var importDone by mutableStateOf(false)

    // thinking内容过滤状态
    private var inThinkBlock = false
    private var thinkBuffer = StringBuilder()

    init {
        checkCloudAvailability()
        if (!cloudAvailable.value) {
            loadModel()
        } else {
            _aiStatus.value = "云端就绪"
            _isModelLoaded.value = true
        }
    }

    fun toggleCloudLlm() { _useCloudLlm.value = !_useCloudLlm.value }

    private fun checkCloudAvailability() {
        _cloudAvailable.value = app.secureStorage.isCloudLlmEnabled()
    }

    fun loadModel() {
        viewModelScope.launch {
            _aiStatus.value = "准备模型中..."
            app.awaitModelExtraction()
            _aiStatus.value = "加载模型中..."
            val result = llmEngine.loadModel()
            result.onSuccess {
                _isModelLoaded.value = true
                _aiStatus.value = "就绪"
                memoryManager.initVectorIndex()
                refreshIdentityName()
            }.onFailure { e ->
                _modelLoadError.value = e.message
                _aiStatus.value = "模型加载失败"
            }
        }
    }

    private var identityRefreshing = false

    /** 刷新当前身份名称（从设置页返回时调用） */
    fun refreshIdentityName() {
        if (identityRefreshing) return
        identityRefreshing = true
        viewModelScope.launch {
            try {
                val identity = identityManager.getActiveIdentity()
                _activeIdentityName.value = identity?.name ?: "AI客服"
            } finally {
                identityRefreshing = false
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _isGenerating.value) return
        viewModelScope.launch {
            sendUserMessage(text, isVoice = false)
        }
    }

    private suspend fun sendUserMessage(text: String, isVoice: Boolean) {
        val userMessage = Message(
            role = "user",
            content = text,
            conversationId = "current",
            isVoice = isVoice
        )
        _messages.value = _messages.value + userMessage

        // 先生成回复，完成后再做记忆评分
        // 这样避免记忆评分与流式生成并发（虽然现在用独立context不冲突，但顺序执行更可靠）
        generateResponse()

        // 回复生成完成后，异步执行记忆评分（不阻塞UI）
        viewModelScope.launch(Dispatchers.IO) {
            // 等待流式生成完全结束再评分，避免与generate()竞争
            if (_isGenerating.value) return@launch
            memoryManager.onUserMessage(userMessage)
        }
    }

    /**
     * 过滤thinking标签内容（防御性措施）
     * Qwen2.5本身不会输出thinking，但为防止误用Qwen3模型或未来兼容，保留过滤
     * 同时过滤 <think>...</think> 和可能的其他变体
     */
    private fun filterThinkingContent(token: String): String? {
        thinkBuffer.append(token)
        val current = thinkBuffer.toString()

        // 检查是否进入thinking块 - 支持多种标签变体
        if (!inThinkBlock) {
            // 查找thinking开始标签: <think>, <think\n>
            val thinkStart = findThinkStart(current)
            if (thinkStart.first != -1) {
                inThinkBlock = true
                val before = current.substring(0, thinkStart.first)
                // 继续查找结束标签
                val thinkEnd = current.indexOf("</think>", thinkStart.second)
                if (thinkEnd != -1) {
                    inThinkBlock = false
                    val after = current.substring(thinkEnd + "</think>".length)
                    thinkBuffer.clear()
                    thinkBuffer.append(after)
                    return before.ifBlank { null }
                }
                thinkBuffer.clear()
                thinkBuffer.append(current)
                return before.ifBlank { null }
            }

            // 检查是否正在累积可能的<think>标签前缀
            if (isPossibleThinkPrefix(current)) {
                return null
            }

            // 不可能是thinking标签了，输出所有内容
            thinkBuffer.clear()
            return token
        }

        // 在thinking块中，查找</think>
        val thinkEnd = current.indexOf("</think>")
        if (thinkEnd != -1) {
            inThinkBlock = false
            val after = current.substring(thinkEnd + "</think>".length)
            thinkBuffer.clear()
            thinkBuffer.append(after)
            if (after.isNotBlank()) {
                return after
            }
            return null
        }

        // 还在thinking块中，不输出
        return null
    }

    /**
     * 查找thinking开始标签，返回 (startIndex, tagEndIndex)
     * 支持 <think> 和 <think\n> 变体
     */
    private fun findThinkStart(text: String): Pair<Int, Int> {
        val tags = listOf("<think>", "<think\n", "<think ")
        for (tag in tags) {
            val idx = text.indexOf(tag)
            if (idx != -1) {
                return Pair(idx, idx + tag.length)
            }
        }
        return Pair(-1, -1)
    }

    /**
     * 检查当前buffer是否可能是<think>标签的前缀
     * 修复：只有在真正是前缀时才返回true，避免误过滤
     */
    private fun isPossibleThinkPrefix(current: String): Boolean {
        if (!current.startsWith("<")) return false
        if (current.contains(">")) return false
        val prefixes = listOf("<", "<t", "<th", "<thi", "<thin", "<think", "<think>")
        for (prefix in prefixes) {
            if (current == prefix) return true
        }
        return false
    }

    private suspend fun generateResponse() {
        val useCloud = _useCloudLlm.value && app.secureStorage.isCloudLlmEnabled()
        if (!useCloud && !llmEngine.isLoaded()) {
            _messages.value = _messages.value + Message(
                role = "assistant",
                content = "模型尚未加载，请在模型管理中确认模型已下载。",
                conversationId = "current"
            )
            return
        }

        _isGenerating.value = true
        _aiStatus.value = "解析中..."
        inThinkBlock = false
        thinkBuffer.clear()

        // 启动前台Service，获取游戏级CPU调度优先级
        InferenceService.start(app)

        try {
            val identity = identityManager.getActiveIdentity()
            val memoryContext = memoryManager.buildMemoryContext(
                _messages.value.lastOrNull { it.role == "user" }?.content ?: ""
            )

            // 使用 PromptBuilder 构建完整 System Prompt（身份 + 记忆）
            val systemPrompt = promptBuilder.build(identity, memoryContext)
            val userMsg = _messages.value.lastOrNull { it.role == "user" }?.content ?: ""

            // 检查是否启用云端大模型（优先DeepSeek，其次OpenAI兼容）
            if (useCloud) {
                val key = app.secureStorage.getDeepseekKey().ifBlank { app.secureStorage.getApiKey() }
                val endpoint = if (app.secureStorage.getDeepseekKey().isNotBlank()) app.secureStorage.getDeepseekEndpoint() else app.secureStorage.getEndpoint()
                val model = if (app.secureStorage.getDeepseekKey().isNotBlank()) app.secureStorage.getDeepseekModel() else app.secureStorage.getModel()
                generateCloudResponse(systemPrompt, userMsg, key, endpoint, model)
                return
            }

            // 本地模型
            llmEngine.setSystemPrompt(systemPrompt)
            val prompt = userMsg

            val responseBuilder = StringBuilder()
            var lastUpdateTime = 0L
            val UPDATE_INTERVAL_MS = 50L  // 每50ms最多更新一次UI，减少Compose重组
            var firstTokenReceived = false

            // 90秒总超时保护，防止永久阻塞在"思考中"
            val generationResult = withTimeoutOrNull(90_000L) {
                llmEngine.generateStream(prompt).collect { token ->
                    if (token == "<|im_end|>" || token == "</s>") return@collect

                    // 原生层信号：prompt解码完成，token生成开始
                    if (token == "__GEN_START__") {
                        _aiStatus.value = "生成中..."
                        return@collect
                    }

                    // 首token标记
                    if (!firstTokenReceived) {
                        firstTokenReceived = true
                    }

                    // 过滤thinking内容
                    val filtered = filterThinkingContent(token)
                    if (filtered != null && filtered.isNotBlank()) {
                        responseBuilder.append(filtered)

                        // 节流UI更新：避免每个token都触发Compose重组
                        val now = System.currentTimeMillis()
                        if (now - lastUpdateTime >= UPDATE_INTERVAL_MS) {
                            lastUpdateTime = now
                            updateAssistantMessage(responseBuilder.toString())
                        }
                    }
                }
            }

            // 首token超时检查：如果90秒内没收到任何token，报错
            if (!firstTokenReceived) {
                llmEngine.abort()
                _messages.value = _messages.value + Message(
                    role = "assistant",
                    content = "AI未能生成回复，请检查模型是否正确加载后重试。",
                    conversationId = "current"
                )
                return
            }

            // 超时处理
            if (generationResult == null) {
                llmEngine.abort()
                if (responseBuilder.isEmpty()) {
                    _messages.value = _messages.value + Message(
                        role = "assistant",
                        content = "回复生成超时，请重试。",
                        conversationId = "current"
                    )
                }
            }

            // 最终更新确保完整内容显示
            if (responseBuilder.isNotEmpty()) {
                updateAssistantMessage(responseBuilder.toString())
            }

            // 生成完成后，flush thinkBuffer中可能残留的非thinking内容
            // 如果仍在think块中（未闭合的<think>），直接丢弃避免泄露内部内容
            if (thinkBuffer.isNotEmpty() && !inThinkBlock) {
                val remaining = thinkBuffer.toString()
                thinkBuffer.clear()
                if (remaining.isNotBlank()) {
                    responseBuilder.append(remaining)
                    val currentMessages = _messages.value.toMutableList()
                    val lastMsg = currentMessages.lastOrNull()
                    if (lastMsg?.role == "assistant") {
                        currentMessages[currentMessages.lastIndex] = lastMsg.copy(
                            content = responseBuilder.toString()
                        )
                    }
                    _messages.value = currentMessages
                }
            }
            thinkBuffer.clear()
            inThinkBlock = false

            val finalContent = responseBuilder.toString()
            if (finalContent.isNotBlank()) {
                val assistantMessage = Message(
                    role = "assistant",
                    content = finalContent,
                    conversationId = "current"
                )
                // 记忆处理异步执行，不阻塞UI
                viewModelScope.launch(Dispatchers.IO) {
                    memoryManager.onAssistantMessage(assistantMessage)
                }
            }

        } catch (e: Exception) {
            Log.e("ChatViewModel", "Generation error", e)
            _messages.value = _messages.value + Message(
                role = "assistant",
                content = "[生成出错：${e.message}]",
                conversationId = "current"
            )
        } finally {
            _isGenerating.value = false
            _aiStatus.value = "就绪"
            // 推理结束，停止前台Service释放资源
            InferenceService.stop(app)
        }
    }

    fun toggleVoiceMode() { _isVoiceMode.value = !_isVoiceMode.value }
    fun startListening() {}
    fun stopListening() {}

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun startNewConversation() {
        viewModelScope.launch {
            memoryManager.onConversationEnd()
            memoryManager.startNewConversation()
            _messages.value = emptyList()
            // 注入身份开场白
            val identity = identityManager.getActiveIdentity()
            _activeIdentityName.value = identity?.name ?: "AI客服"
            if (identity != null && identity.greeting.isNotBlank()) {
                _messages.value = listOf(Message(
                    role = "assistant",
                    content = identity.greeting,
                    conversationId = "current"
                ))
            }
            // 重建system prompt（身份可能已变化）
            val memoryContext = memoryManager.buildMemoryContext("")
            val systemPrompt = promptBuilder.build(identity, memoryContext)
            llmEngine.setSystemPrompt(systemPrompt)
        }
    }

    fun importDocument(title: String, content: String) {
        viewModelScope.launch {
            try {
                memoryManager.addDocument(title, content)
                importDone = true
                importDocTitle = ""
                importDocContent = ""
                showImportDialog = false
            } catch (e: Exception) {
                Log.e("ChatVM", "Failed to import document", e)
            }
        }
    }

    fun abortGeneration() {
        llmEngine.abort()
    }

    /**
     * 更新助手消息到消息列表（优化版：直接操作列表，减少中间对象创建）
     */
    private fun updateAssistantMessage(content: String) {
        val currentMessages = _messages.value.toMutableList()
        val lastMsg = currentMessages.lastOrNull()
        if (lastMsg?.role == "assistant") {
            currentMessages[currentMessages.lastIndex] = lastMsg.copy(content = content)
        } else {
            currentMessages.add(Message(
                role = "assistant",
                content = content,
                conversationId = "current"
            ))
        }
        _messages.value = currentMessages
    }

    private suspend fun generateCloudResponse(systemPrompt: String, userMsg: String, key: String, endpoint: String, model: String) {
        _isGenerating.value = true
        _aiStatus.value = "云端思考中..."
        val client = com.aicustomer.engine.OpenAiClient(key, endpoint, model)

        val responseBuilder = StringBuilder()
        var lastUpdateTime = 0L
        val UPDATE_INTERVAL_MS = 50L

        try {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                client.chatStream(systemPrompt, userMsg).collect { token ->
                    if (token.startsWith("[云端请求失败")) {
                        responseBuilder.append(token)
                        return@collect
                    }
                    responseBuilder.append(token)
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime >= UPDATE_INTERVAL_MS) {
                        lastUpdateTime = now
                        updateAssistantMessage(responseBuilder.toString())
                    }
                }
            }
            if (responseBuilder.isNotEmpty()) {
                updateAssistantMessage(responseBuilder.toString())
            }
        } catch (e: Exception) {
            _messages.value = _messages.value + Message(
                role = "assistant", content = "[云端请求失败: ${e.message}]", conversationId = "current"
            )
        } finally {
            _isGenerating.value = false
            _aiStatus.value = "就绪"
        }
    }

    override fun onCleared() {
        super.onCleared()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(application) as T
        }
    }
}
