package com.aicustomer.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aicustomer.App
import com.aicustomer.data.model.Message
import com.aicustomer.engine.InferenceService
import com.aicustomer.identity.VoicePromptBuilder
import com.aicustomer.util.VoiceTextCleaner
import com.aicustomer.voice.VoicePipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class VoiceCallState { IDLE, LISTENING, THINKING, SPEAKING }

class VoiceCallViewModel(application: Application) : ViewModel() {

    private val app = application as App
    init { app.voiceCallViewModel = this }
    private val llmEngine = app.llmEngine
    private val memoryManager = app.memoryManager
    private val identityManager = app.identityManager
    private val voicePromptBuilder = VoicePromptBuilder()

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
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()
    private var isCallActive = false
    private var callStartTime = 0L
    private var callEnded = false

    private var aiResponseAccum = ""
    private var lastSpeakIdx = 0
    private val voiceTranscripts = mutableListOf<Message>()
    private var inferenceRefCount = 0

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
        voicePipeline.onFinalText = { t ->
            viewModelScope.launch {
                Log.d("VoiceCallVM", "onFinalText: ${t.length} chars")
                _userSpeechText.value = t
                if (t.isNotBlank()) processUserText(t)
            }
        }
        voicePipeline.onAiToken = { t ->
            viewModelScope.launch { _aiResponseText.value = aiResponseAccum }
        }
        voicePipeline.onAiInterrupted = {
            viewModelScope.launch { handleAiInterrupted() }
        }
        voicePipeline.onAmplitude = { a ->
            _amplitude.value = a
        }
        voicePipeline.onEmptyEndpoint = {
            viewModelScope.launch {
                _statusHint.value = "未检测到语音，请再说一次"
                _callState.value = VoiceCallState.LISTENING
            }
        }
    }

    fun startCall() {
        viewModelScope.launch {
            if (!llmEngine.isLoaded()) {
                _statusHint.value = "请先在聊天页加载本地模型"
                return@launch
            }

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
        viewModelScope.launch { processUserText(text) }
    }

    fun onOrbTapInterrupt() {
        voicePipeline.interruptAi()
    }

    fun endCall() {
        if (callEnded) return
        callEnded = true
        isCallActive = false
        _callState.value = VoiceCallState.IDLE
        llmEngine.abort()
        voicePipeline.endCall()
        inferenceRefCount = 0
        InferenceService.stop(app)

        if (voiceTranscripts.isNotEmpty()) {
            app.pendingVoiceTranscripts.value = voiceTranscripts.toList()
            voiceTranscripts.clear()
        }

        viewModelScope.launch(Dispatchers.IO) {
            try { memoryManager.onConversationEnd() } catch (e: Exception) { Log.w("VoiceCallVM", "convEnd failed", e) }
            try { memoryManager.startNewConversation() } catch (e: Exception) { Log.w("VoiceCallVM", "newConv failed", e) }
        }
    }

    private var generationId = 0L
    private var turnTranscriptSaved = false

    private fun handleAiInterrupted() {
        saveTurnTranscriptIfNeeded(isAborted = true)
        llmEngine.abort()
        voicePipeline.isProcessing = false
        generationId++
        aiResponseAccum = ""
        lastSpeakIdx = 0
        _aiResponseText.value = ""
        _statusHint.value = "正在聆听..."
        _callState.value = VoiceCallState.LISTENING
    }

    private fun saveTurnTranscriptIfNeeded(isAborted: Boolean) {
        if (turnTranscriptSaved) return
        turnTranscriptSaved = true

        val userText = _userSpeechText.value
        val aiText = aiResponseAccum

        if (userText.isBlank() && aiText.isBlank()) return

        if (userText.isNotBlank()) {
            val userMsg = Message(
                role = "user",
                content = userText,
                conversationId = "current",
                isVoice = true
            )
            voiceTranscripts.add(userMsg)
            viewModelScope.launch(Dispatchers.IO) {
                app.database.messageDao().insert(userMsg)
            }
        }

        if (aiText.isNotBlank()) {
            val cleanAi = VoiceTextCleaner.cleanForSpeech(aiText)
            val content = if (isAborted) "$cleanAi\n(已打断)" else cleanAi
            val assistantMsg = Message(
                role = "assistant",
                content = content,
                conversationId = "current",
                isVoice = true
            )
            voiceTranscripts.add(assistantMsg)
            viewModelScope.launch(Dispatchers.IO) {
                app.database.messageDao().insert(assistantMsg)
            }
            if (isCallActive) {
                viewModelScope.launch(Dispatchers.IO) {
                    memoryManager.onAssistantMessage(
                        Message(
                            role = "assistant",
                            content = aiText,
                            conversationId = "voice_call",
                            isVoice = true
                        )
                    )
                }
            }
        }

        _userSpeechText.value = ""
    }

    private suspend fun processUserText(text: String) {
        turnTranscriptSaved = false
        _statusHint.value = "思考中..."; _callState.value = VoiceCallState.THINKING
        _aiResponseText.value = ""; aiResponseAccum = ""; lastSpeakIdx = 0
        generationId++
        val myGenId = generationId

        // Start foreground service early, overlap with memory context building
        InferenceService.start(app)
        inferenceRefCount++

        // Build memory context + system prompt on IO thread to avoid blocking Main
        withContext(Dispatchers.IO) {
            val systemPrompt = voicePromptBuilder.build(
                identityManager.getActiveIdentity(),
                memoryManager.buildMemoryContext(text)
            )
            llmEngine.setSystemPrompt(systemPrompt)
        }

        generateAndStream(text, myGenId)
    }

    private suspend fun generateAndStream(text: String, myGenId: Long) {
        if (!llmEngine.isLoaded()) {
            voicePipeline.onProcessingComplete()
            _statusHint.value = "本地模型未加载"
            return
        }

        var wasAborted = false
        try {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(90_000L) {
                    llmEngine.generateStream(text).collect { token ->
                        if (generationId != myGenId) { wasAborted = true; throw kotlinx.coroutines.CancellationException("interrupted") }
                        if (token == "__GEN_START__" || token.startsWith("[")) return@collect
                        if (token == "<|im_end|>" || token == "</s>") return@collect

                        aiResponseAccum += token
                        _aiResponseText.value = aiResponseAccum

                        val current = aiResponseAccum
                        for (sep in listOf("。", "！", "？", "；", "\n")) {
                            val idx = current.indexOf(sep, lastSpeakIdx)
                            if (idx >= 0) {
                                val sentence = current.substring(lastSpeakIdx, idx + 1).trim()
                                if (sentence.isNotBlank() && sentence.length > 1) {
                                    voicePipeline.queueTtsSentence(VoiceTextCleaner.cleanForSpeech(sentence))
                                }
                                lastSpeakIdx = idx + 1
                            }
                        }
                    }
                }
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            wasAborted = true
        } catch (e: Exception) {
            Log.w("VoiceCallVM", "LLM generate error", e)
            wasAborted = true
        } finally {
            if (inferenceRefCount > 0) {
                inferenceRefCount--
                if (inferenceRefCount <= 0) {
                    InferenceService.stop(app)
                    inferenceRefCount = 0
                }
            }
        }

        finishResponse(wasAborted, myGenId)
    }

    private fun finishResponse(wasAborted: Boolean, myGenId: Long) {
        if (wasAborted || generationId != myGenId) {
            saveTurnTranscriptIfNeeded(isAborted = true)
            _userSpeechText.value = ""
            voicePipeline.onProcessingComplete()
            return
        }

        turnTranscriptSaved = true

        if (aiResponseAccum.isNotBlank()) {
            val remaining = aiResponseAccum.substring(lastSpeakIdx)
                .replace(Regex("<[^>]*>"), "").trim()
            if (remaining.isNotBlank()) {
                voicePipeline.queueTtsSentence(VoiceTextCleaner.injectSpice(VoiceTextCleaner.cleanForSpeech(remaining)))
            }
        }

        voicePipeline.queueTurnEnd()
        voicePipeline.onProcessingComplete()

        val cleanText = VoiceTextCleaner.cleanForSpeech(aiResponseAccum)
        val assistantMsg = Message(role = "assistant", content = cleanText, conversationId = "current", isVoice = true)
        val voiceUserMsg = Message(role = "user", content = _userSpeechText.value.ifBlank { "(语音)" }, conversationId = "current", isVoice = true)
        voiceTranscripts.add(voiceUserMsg)
        voiceTranscripts.add(assistantMsg)

        viewModelScope.launch(Dispatchers.IO) {
            app.database.messageDao().insert(voiceUserMsg)
            app.database.messageDao().insert(assistantMsg)
        }

        if (isCallActive && aiResponseAccum.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                memoryManager.onAssistantMessage(Message(role = "assistant", content = aiResponseAccum, conversationId = "voice_call", isVoice = true))
            }
        }

        _userSpeechText.value = ""
    }

    override fun onCleared() {
        super.onCleared()
        endCall()
        app.voiceCallViewModel = null
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return VoiceCallViewModel(application) as T
        }
    }
}
