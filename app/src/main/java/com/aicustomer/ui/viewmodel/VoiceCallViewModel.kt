package com.aicustomer.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aicustomer.App
import com.aicustomer.data.model.Message
import com.aicustomer.engine.InferenceService
import com.aicustomer.engine.OpenAiClient
import com.aicustomer.identity.PromptBuilder
import com.aicustomer.voice.VoicePipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class VoiceCallState { IDLE, LISTENING, THINKING, SPEAKING }

class VoiceCallViewModel(application: Application) : ViewModel() {

    private val app = application as App
    init { app.voiceCallViewModel = this }
    private val memoryManager = app.memoryManager
    private val identityManager = app.identityManager
    private val promptBuilder = PromptBuilder()

    val voicePipeline = VoicePipeline(context = application)

    private val _callState = MutableStateFlow(VoiceCallState.IDLE)
    val callState: StateFlow<VoiceCallState> = _callState.asStateFlow()
    private val _callElapsed = MutableStateFlow(0L)
    val callElapsed: StateFlow<Long> = _callElapsed.asStateFlow()
    private val _userSpeechText = MutableStateFlow("")
    val userSpeechText: StateFlow<String> = _userSpeechText.asStateFlow()
    private val _aiResponseText = MutableStateFlow("")
    val aiResponseText: StateFlow<String> = _aiResponseText.asStateFlow()
    private val _statusHint = MutableStateFlow("点击开始通话")
    val statusHint: StateFlow<String> = _statusHint.asStateFlow()
    private var isCallActive = false
    private var callStartTime = 0L
    private var callEnded = false

    // 流式累积
    private var aiResponseAccum = ""
    private var lastSpeakIdx = 0

    // 活跃的 LLM 客户端（用于中断）
    @Volatile private var activeClient: OpenAiClient? = null

    init {
        voicePipeline.onStateChanged = { s ->
            viewModelScope.launch {
                _callState.value = when (s) {
                    VoicePipeline.State.THINKING -> VoiceCallState.THINKING
                    VoicePipeline.State.SPEAKING -> VoiceCallState.SPEAKING
                    VoicePipeline.State.IDLE -> VoiceCallState.IDLE
                    else -> VoiceCallState.LISTENING
                }
            }
        }
        voicePipeline.onInterimText = { t ->
            viewModelScope.launch { _userSpeechText.value = t }
        }
        voicePipeline.onFinalText = { t ->
            viewModelScope.launch {
                android.util.Log.i("VoiceCallVM", "onFinalText: '${t.take(50)}'")
                _userSpeechText.value = t
                if (t.isNotBlank()) processUserSpeech(t)
            }
        }
        voicePipeline.onAiToken = { t ->
            viewModelScope.launch { _aiResponseText.value = aiResponseAccum }
        }
        voicePipeline.onAiInterrupted = {
            viewModelScope.launch { handleAiInterrupted() }
        }
    }

    fun startCall() {
        viewModelScope.launch {
            val useCloud = app.secureStorage.isCloudLlmEnabled()
            if (!useCloud) { _statusHint.value = "请先配置云端大模型"; return@launch }

            callEnded = false
            isCallActive = true; callStartTime = System.currentTimeMillis()
            _callState.value = VoiceCallState.LISTENING; _statusHint.value = "正在聆听..."
            _userSpeechText.value = ""; _aiResponseText.value = ""
            aiResponseAccum = ""; lastSpeakIdx = 0; generationId = 0L

            launch { while (isCallActive) { _callElapsed.value = (System.currentTimeMillis() - callStartTime) / 1000; kotlinx.coroutines.delay(1000) } }
            voicePipeline.startVoiceCall()
        }
    }

    fun sendTextMessage(text: String) {
        viewModelScope.launch { processUserSpeech(text) }
    }

    fun endCall() {
        if (callEnded) return
        callEnded = true
        isCallActive = false
        _callState.value = VoiceCallState.IDLE
        activeClient?.abort()
        activeClient = null
        voicePipeline.endCall()
        InferenceService.stop(app)
        viewModelScope.launch(Dispatchers.IO) {
            try { memoryManager.onConversationEnd() } catch (_: Exception) {}
            try { memoryManager.startNewConversation() } catch (_: Exception) {}
        }
    }

    private var generationId = 0L

    private fun handleAiInterrupted() {
        activeClient?.abort()
        activeClient = null
        generationId++
        aiResponseAccum = ""
        lastSpeakIdx = 0
        _aiResponseText.value = ""
        _statusHint.value = "正在聆听..."
        _callState.value = VoiceCallState.LISTENING
    }

    private suspend fun processUserSpeech(text: String) {
        _statusHint.value = "思考中..."; _callState.value = VoiceCallState.THINKING
        _aiResponseText.value = ""; aiResponseAccum = ""; lastSpeakIdx = 0
        generationId++
        val myGenId = generationId

        val userMessage = Message(role = "user", content = text, conversationId = "voice_call", isVoice = true)

        val identity = identityManager.getActiveIdentity()
        val memCtx = memoryManager.buildMemoryContext(text)
        val sysPrompt = promptBuilder.build(identity, memCtx)

        val cloudKey = app.secureStorage.getDeepseekKey().ifBlank { app.secureStorage.getApiKey() }
        if (cloudKey.isNotBlank()) {
            val endpoint = if (app.secureStorage.getDeepseekKey().isNotBlank()) app.secureStorage.getDeepseekEndpoint() else app.secureStorage.getEndpoint()
            val model = if (app.secureStorage.getDeepseekKey().isNotBlank()) app.secureStorage.getDeepseekModel() else app.secureStorage.getModel()
            val client = OpenAiClient(cloudKey, endpoint, model)
            activeClient = client
            InferenceService.start(app)

            var wasAborted = false
            try {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    client.chatStream(sysPrompt, text).collect { token ->
                        if (generationId != myGenId) { wasAborted = true; throw kotlinx.coroutines.CancellationException("interrupted") }
                        if (token.startsWith("[云端请求失败")) {
                            aiResponseAccum = token
                            return@collect
                        }
                        aiResponseAccum += token
                        _aiResponseText.value = aiResponseAccum

                        val current = aiResponseAccum
                        for (sep in listOf("。", "！", "？", "；", "\n")) {
                            val idx = current.indexOf(sep, lastSpeakIdx)
                            if (idx >= 0) {
                                val sentence = current.substring(lastSpeakIdx, idx + 1).trim()
                                if (sentence.isNotBlank() && sentence.length > 1) {
                                    voicePipeline.queueTtsSentence(sentence)
                                }
                                lastSpeakIdx = idx + 1
                            }
                        }
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                wasAborted = true
            } catch (e: Exception) {
                if (generationId != myGenId) wasAborted = true
            } finally {
                activeClient = null
                InferenceService.stop(app)
            }

            // 被打断则跳过后续处理
            if (wasAborted || generationId != myGenId) {
                _userSpeechText.value = ""
                voicePipeline.onProcessingComplete()
                return
            }

            // 剩余文本
            if (aiResponseAccum.isNotBlank() && !aiResponseAccum.startsWith("[云端")) {
                val remaining = aiResponseAccum.substring(lastSpeakIdx)
                    .replace(Regex("<[^>]*>"), "").trim()
                if (remaining.isNotBlank()) {
                    voicePipeline.queueTtsSentence(remaining)
                }
            }

            if (isCallActive && aiResponseAccum.isNotBlank()) {
                viewModelScope.launch(Dispatchers.IO) {
                    memoryManager.onAssistantMessage(Message(role = "assistant", content = aiResponseAccum, conversationId = "voice_call", isVoice = true))
                }
            }

            _userSpeechText.value = ""
            viewModelScope.launch(Dispatchers.IO) { memoryManager.onUserMessage(userMessage) }
            return
        }

        _userSpeechText.value = ""
        voicePipeline.onProcessingComplete()
        _statusHint.value = "请先配置云端大模型"
        viewModelScope.launch(Dispatchers.IO) { memoryManager.onUserMessage(userMessage) }
    }

    override fun onCleared() { super.onCleared(); endCall() }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return VoiceCallViewModel(application) as T
        }
    }
}
