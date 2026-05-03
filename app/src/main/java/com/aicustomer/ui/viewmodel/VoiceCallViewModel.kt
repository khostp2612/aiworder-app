package com.aicustomer.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aicustomer.App
import com.aicustomer.data.model.Message
import com.aicustomer.engine.InferenceService
import com.aicustomer.identity.PromptBuilder
import com.aicustomer.voice.VoicePipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class VoiceCallState { IDLE, LISTENING, THINKING, SPEAKING }

class VoiceCallViewModel(application: Application) : ViewModel() {

    private val app = application as App
    init { app.voiceCallViewModel = this }
    private val llmEngine = app.llmEngine
    private val memoryManager = app.memoryManager
    private val identityManager = app.identityManager
    private val promptBuilder = PromptBuilder()

    val voicePipeline = VoicePipeline(
        context = application,
        llmEngine = llmEngine
    )

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

    // Local accumulator for voice response text (bypasses async StateFlow race)
    private var voiceResponseAccum = ""

    init {
        voicePipeline.onStateChanged = { s ->
            viewModelScope.launch {
                _callState.value = when (s) {
                    VoicePipeline.State.PROCESSING -> VoiceCallState.THINKING
                    VoicePipeline.State.SPEAKING -> VoiceCallState.SPEAKING
                    VoicePipeline.State.IDLE -> VoiceCallState.IDLE
                    else -> VoiceCallState.LISTENING
                }
            }
        }
        voicePipeline.onPartialText = { t -> viewModelScope.launch { _userSpeechText.value = t } }
        voicePipeline.onFinalText = { t ->
            viewModelScope.launch {
                _userSpeechText.value = t
                if (t.isNotBlank()) processUserSpeech(t)
            }
        }
        voicePipeline.onResponseText = { t ->
            voiceResponseAccum += t
            viewModelScope.launch { _aiResponseText.value = voiceResponseAccum }
        }
    }

    fun startCall() {
        viewModelScope.launch {
            if (!llmEngine.isLoaded()) { _statusHint.value = "模型未加载"; return@launch }
            callEnded = false
            isCallActive = true; callStartTime = System.currentTimeMillis()
            _callState.value = VoiceCallState.LISTENING; _statusHint.value = "正在聆听..."
            _userSpeechText.value = ""; _aiResponseText.value = ""; voiceResponseAccum = ""
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
        llmEngine.abort()
        voicePipeline.endCall()
        InferenceService.stop(app)
        viewModelScope.launch(Dispatchers.IO) {
            try { memoryManager.onConversationEnd() } catch (_: Exception) {}
            try { memoryManager.startNewConversation() } catch (_: Exception) {}
        }
    }

    private suspend fun processUserSpeech(text: String) {
        _statusHint.value = "思考中..."; _callState.value = VoiceCallState.THINKING; _aiResponseText.value = ""; voiceResponseAccum = ""
        val userMessage = Message(role = "user", content = text, conversationId = "voice_call", isVoice = true)

        val identity = identityManager.getActiveIdentity()
        val memCtx = memoryManager.buildMemoryContext(text)
        val sysPrompt = promptBuilder.build(identity, memCtx)

        // 检查云端大模型
        val prefs = app.getSharedPreferences("asr_prefs", android.content.Context.MODE_PRIVATE)
        val cloudKey = prefs.getString("openai_key", "") ?: ""
        if (cloudKey.isNotBlank()) {
            val endpoint = prefs.getString("openai_endpoint", "https://api.deepseek.com/v1") ?: ""
            val model = prefs.getString("openai_model", "deepseek-chat") ?: "deepseek-chat"
            val client = com.aicustomer.engine.OpenAiClient(cloudKey, endpoint, model)
            InferenceService.start(app)
            try {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    client.chatStream(sysPrompt, text).collect { token ->
                        if (token.startsWith("[云端请求失败")) {
                            voiceResponseAccum = token
                        } else {
                            voiceResponseAccum += token
                            _aiResponseText.value = voiceResponseAccum
                        }
                    }
                }
                if (voiceResponseAccum.isNotBlank() && !voiceResponseAccum.startsWith("[云端")) {
                    voicePipeline.voiceChat(voiceResponseAccum)
                } else {
                    _callState.value = VoiceCallState.LISTENING; _statusHint.value = "正在聆听..."
                }
            } finally {
                InferenceService.stop(app)
            }
            _userSpeechText.value = ""
            return
        }

        // 本地模型 — 使用身份角色系统提示
        llmEngine.setSystemPrompt(sysPrompt)

        InferenceService.start(app)
        try { voicePipeline.voiceChat(text) }
        finally {
            InferenceService.stop(app)
            if (isCallActive) {
                _callState.value = VoiceCallState.LISTENING; _statusHint.value = "正在聆听..."
                _aiResponseText.value = voiceResponseAccum
                if (voiceResponseAccum.isNotBlank()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        memoryManager.onAssistantMessage(Message(role = "assistant", content = voiceResponseAccum, conversationId = "voice_call", isVoice = true))
                    }
                }
                _userSpeechText.value = ""
            }
            voicePipeline.onProcessingComplete()
        }
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
