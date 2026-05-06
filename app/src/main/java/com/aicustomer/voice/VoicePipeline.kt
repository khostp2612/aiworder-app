package com.aicustomer.voice

import android.content.Context
import android.content.SharedPreferences
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.aicustomer.asr.CfAsrProvider
import com.aicustomer.asr.XfyunAsrProvider
import com.aicustomer.data.local.SecureStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.Locale

class VoicePipeline(
    private val context: Context
) {
    private var systemTts: TextToSpeech? = null
    private var ttsInitDone = false
    @Volatile private var isActive = false
    @Volatile var isProcessing = false
    private var audioRecord: AudioRecord? = null
    private var cloudProvider: CfAsrProvider? = null
    private var xfyunProvider: XfyunAsrProvider? = null
    private var recordScope: CoroutineScope? = null
    private var ttsConsumerJob: Job? = null
    private var recordJob: Job? = null

    private val preprocessor = AudioPreprocessor()
    private val prefs: SharedPreferences = context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)
    private val secureStorage = SecureStorage(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // 自适应回声基线：EMA 追踪 TTS 播放时麦克风捕获的能量级别
    private var ttsEnergyBaseline = 0.0
    private var ttsBaselineCount = 0
    private var consecutiveAbove = 0
    private val TTS_BASELINE_SAMPLES = 20
    private val TTS_INTERRUPT_MULTIPLIER = 2.5
    private val CONSECUTIVE_FRAMES = 5

    enum class State { IDLE, LISTENING, THINKING, SPEAKING }
    var onStateChanged: ((State) -> Unit)? = null
    var onInterimText: ((String) -> Unit)? = null
    var onFinalText: ((String) -> Unit)? = null
    var onAiToken: ((String) -> Unit)? = null
    private var state = State.IDLE
        set(v) { field = v; mainHandler.post { onStateChanged?.invoke(v) } }

    // TTS 队列 — 流式消费 LLM 句子
    private val ttsSentenceQueue = Channel<String>(Channel.UNLIMITED)
    @Volatile var ttsSpeaking = false
        private set

    private fun log(msg: String) { Log.i("VoicePipeline", msg) }

    private val asrProviderType: String get() = secureStorage.getAsrProvider()

    private val cloudReady: Boolean get() {
        if (asrProviderType == "xfyun") {
            val appId = secureStorage.getXfyunAppId()
            val apiKey = secureStorage.getXfyunApiKey()
            val apiSecret = secureStorage.getXfyunApiSecret()
            if (appId.isNotBlank() && apiKey.isNotBlank() && apiSecret.isNotBlank()) {
                xfyunProvider = XfyunAsrProvider(appId, apiKey, apiSecret)
                xfyunProvider?.onInterim = { text ->
                    mainHandler.post { onInterimText?.invoke(text) }
                }
                return true
            }
            return false
        }
        if (cloudProvider != null && cloudProvider!!.isAvailable()) return true
        val wsUrl = secureStorage.getCfUrl().ifBlank { prefs.getString("cf_url", "") ?: "" }
        if (wsUrl.isNotBlank()) {
            cloudProvider = CfAsrProvider(wsUrl)
            return true
        }
        return false
    }

    private suspend fun initTts(): Boolean {
        if (systemTts != null && ttsInitDone) return true
        return suspendCancellableCoroutine { cont ->
            systemTts?.stop(); systemTts?.shutdown()
            systemTts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val t = systemTts
                    if (t != null) {
                        var r = t.setLanguage(Locale.CHINESE)
                        if (r !in listOf(TextToSpeech.LANG_AVAILABLE, 0)) r = t.setLanguage(Locale.SIMPLIFIED_CHINESE)
                        ttsInitDone = r !in listOf(TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED)
                        if (ttsInitDone) {
                            t.setSpeechRate(1.5f); t.setPitch(1.05f)
                            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
                            t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        }
                    }
                }
                if (cont.isActive) cont.resume(ttsInitDone) {}
            }
        }
    }

    // ====== 全双工入口 ======

    fun startVoiceCall() {
        log("START full-duplex asr=$asrProviderType cloud=$cloudReady")
        isActive = true; isProcessing = false; state = State.LISTENING
        recordScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preprocessor.reset()
        ttsEnergyBaseline = 0.0
        ttsBaselineCount = 0
        consecutiveAbove = 0

        // 扬声器模式 + 硬件回声消除（由 VOICE_COMMUNICATION source 提供）
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = true

        if (asrProviderType == "cf" && cloudReady && cloudProvider != null) {
            cloudProvider!!.onListening = {
                if (isActive && !isProcessing) mainHandler.post { state = State.LISTENING }
            }
            cloudProvider!!.onFinalText = { text ->
                if (isActive && !isProcessing) {
                    isProcessing = true
                    state = State.THINKING
                    mainHandler.post { onFinalText?.invoke(text) }
                }
            }
            cloudProvider!!.onError = { err ->
                log("ASR error: $err")
                isProcessing = false
                if (isActive) startListening()
            }
            cloudProvider!!.connect()
        }

        // 启动 TTS 队列消费者
        ttsConsumerJob = recordScope?.launch { ttsConsumerLoop() }

        // 启动连续录音
        startListening()
    }

    // ====== TTS 流式播放 ======

    private suspend fun ttsConsumerLoop() {
        initTts()
        for (sentence in ttsSentenceQueue) {
            if (!isActive) break
            try {
                ttsSpeaking = true
                state = State.SPEAKING
                speakSentenceInternal(sentence)
            } catch (e: Exception) {
                Log.w("VoicePipeline", "TTS sentence failed: ${e.message}")
            }
        }
        ttsSpeaking = false
        ttsEnergyBaseline = 0.0
        ttsBaselineCount = 0
        consecutiveAbove = 0
        isProcessing = false
        if (isActive) {
            state = State.LISTENING
        }
    }

    /** 由 ViewModel 调用，将 LLM 流式输出的句子送入 TTS 队列 */
    fun queueTtsSentence(text: String) {
        val t = text.replace(Regex("<[^>]*>"), "").replace(Regex("[*#_~`|\\\\]"), "").trim()
        if (t.isNotBlank()) {
            ttsSentenceQueue.trySend(t)
        }
    }

    private suspend fun speakSentenceInternal(text: String) {
        if (!initTts()) return
        suspendCancellableCoroutine<Unit> { cont ->
            val id = "tts_${System.currentTimeMillis()}"
            systemTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (id == id && cont.isActive) cont.resume(Unit) {} }
                override fun onError(id: String?) { if (id == id && cont.isActive) cont.resume(Unit) {} }
                @Deprecated("Deprecated in Java") override fun onError(id: String?, code: Int) { if (id == id && cont.isActive) cont.resume(Unit) {} }
            })
            systemTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    // ====== 中断 ======

    /** 中断 AI 说话：停止 TTS，排空队列（不关闭 channel，可复用） */
    fun interruptAi() {
        log("interruptAi")
        systemTts?.stop()
        ttsConsumerJob?.cancel()
        ttsConsumerJob = null
        ttsSpeaking = false
        ttsEnergyBaseline = 0.0
        ttsBaselineCount = 0
        consecutiveAbove = 0
        // 排空但不关闭 channel
        while (ttsSentenceQueue.tryReceive().isSuccess) { /* drain */ }
        // 重建消费者
        recordScope?.launch {
            ttsConsumerJob = launch { ttsConsumerLoop() }
        }
    }

    // ====== 连续录音 (Xfyun) ======

    private var xfyunTranscribing = false

    /** 创建启用硬件 AEC + NS 的 AudioRecord */
    private fun createAudioRecord(rate: Int, bufSz: Int): AudioRecord {
        audioRecord?.release()
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSz
        )
        if (rec.state == AudioRecord.STATE_INITIALIZED) {
            try { AcousticEchoCanceler.create(rec.audioSessionId)?.let { it.enabled = true } } catch (_: Exception) {}
            try { NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = true } } catch (_: Exception) {}
        }
        return rec
    }

    private fun startListening() {
        if (!isActive) return
        state = State.LISTENING
        recordJob?.cancel()
        recordJob = recordScope?.launch {
            try {
                when (asrProviderType) {
                    "xfyun" -> xfyunContinuousLoop()
                    else -> cfRecordLoop()
                }
            } catch (_: CancellationException) {}
        }
    }

    private suspend fun xfyunContinuousLoop() {
        val rate = 16000
        val bufSz = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
        audioRecord = createAudioRecord(rate, bufSz)
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            delay(2000); if (isActive) startListening(); return
        }
        audioRecord?.startRecording()

        val buf = ShortArray(bufSz / 2)
        val allSamples = mutableListOf<Float>()
        var silenceStart = 0L
        var speechStarted = false
        var speechStartTime = 0L
        val SILENCE_TIMEOUT = 600L
        val MAX_SPEECH_MS = 15000L
        val ENERGY_WINDOW = 6
        val energyHistory = ArrayDeque<Double>()
        val BASE_SPEECH_ENERGY = 300.0
        var interrupted = false
        var lastHeartbeat = 0L
        val startupChunks = mutableListOf<FloatArray>()
        val INITIAL_BUFFER_FRAMES = 5  // ~200ms 预热缓冲

        try {
            while (isActive) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: 0
                if (n <= 0) { delay(10); continue }

                val now = System.currentTimeMillis()
                if (now - lastHeartbeat > 5000) {
                    lastHeartbeat = now
                    log("heartbeat proc=$isProcessing speaking=$ttsSpeaking buf=${allSamples.size} speech=$speechStarted trans=$xfyunTranscribing")
                }

                val chunk = buf.copyOf(n)
                val energy = chunk.map { it.toDouble() * it.toDouble() }.average()
                energyHistory.addLast(energy)
                if (energyHistory.size > ENERGY_WINDOW) energyHistory.removeFirst()
                val avgEnergy = energyHistory.average()

                // 自适应回声基准：EMA 慢速追踪 TTS 回声能量
                if (ttsSpeaking) {
                    ttsEnergyBaseline = ttsEnergyBaseline * 0.95 + avgEnergy * 0.05
                    ttsBaselineCount++
                }

                // 打断检测：能量连续超出 EMA 基线 × 2.5 → 用户说话 → 打断
                if (ttsSpeaking && ttsBaselineCount >= TTS_BASELINE_SAMPLES) {
                    if (avgEnergy > ttsEnergyBaseline * TTS_INTERRUPT_MULTIPLIER) {
                        consecutiveAbove++
                        if (consecutiveAbove >= CONSECUTIVE_FRAMES) {
                            interruptAi()
                            isProcessing = false
                            interrupted = true
                            consecutiveAbove = 0
                            mainHandler.post { onAiInterrupted?.invoke() }
                            allSamples.clear()
                            xfyunProvider?.abortStream()
                            xfyunTranscribing = false
                            silenceStart = 0L
                            speechStarted = false
                            startupChunks.clear()
                            delay(50)
                        }
                    } else {
                        consecutiveAbove = 0
                    }
                }

                // 语音累积 + 静音检测：仅在聆听状态
                if (!isProcessing) {
                    if (avgEnergy > BASE_SPEECH_ENERGY) {
                        if (!speechStarted) {
                            speechStarted = true
                            speechStartTime = now
                            startupChunks.clear()
                        }
                        silenceStart = 0L
                        val processed = preprocessor.processShortToFloat(chunk)

                        if (xfyunTranscribing) {
                            // 已在流式中，直接发送
                            xfyunProvider?.sendStreamAudio(processed, isEnd = false)
                        } else if (startupChunks.size < INITIAL_BUFFER_FRAMES) {
                            // 预热缓冲：先攒 200ms 音频让讯飞拿到完整起音
                            startupChunks.add(processed)
                            if (startupChunks.size >= INITIAL_BUFFER_FRAMES) {
                                xfyunTranscribing = true
                                xfyunProvider?.beginStream(rate) { text ->
                                    xfyunTranscribing = false
                                    startupChunks.clear()
                                    Log.i("VoicePipeline", "xfyun stream: '${text.take(50)}'")
                                    if (text.isNotBlank() && isActive && !isProcessing) {
                                        isProcessing = true
                                        state = State.THINKING
                                        mainHandler.post { onFinalText?.invoke(text) }
                                    }
                                }
                                // 一次性发送积压的预热音频
                                startupChunks.forEach { xfyunProvider?.sendStreamAudio(it, isEnd = false) }
                                startupChunks.clear()
                            }
                        }
                    } else if (speechStarted) {
                        val processed = preprocessor.processShortToFloat(chunk)
                        if (xfyunTranscribing) {
                            xfyunProvider?.sendStreamAudio(processed, isEnd = false)
                        } else if (startupChunks.size < INITIAL_BUFFER_FRAMES) {
                            startupChunks.add(processed)
                            if (startupChunks.size >= INITIAL_BUFFER_FRAMES) {
                                xfyunTranscribing = true
                                xfyunProvider?.beginStream(rate) { text ->
                                    xfyunTranscribing = false
                                    startupChunks.clear()
                                    Log.i("VoicePipeline", "xfyun stream: '${text.take(50)}'")
                                    if (text.isNotBlank() && isActive && !isProcessing) {
                                        isProcessing = true
                                        state = State.THINKING
                                        mainHandler.post { onFinalText?.invoke(text) }
                                    }
                                }
                                startupChunks.forEach { xfyunProvider?.sendStreamAudio(it, isEnd = false) }
                                startupChunks.clear()
                            }
                        }
                        if (silenceStart == 0L) silenceStart = now
                    }

                    val silenceDone = speechStarted && silenceStart > 0 && (now - silenceStart) > SILENCE_TIMEOUT
                    val exceededMax = speechStarted && (now - speechStartTime) > MAX_SPEECH_MS

                    if ((silenceDone || exceededMax) && xfyunTranscribing) {
                        log("xfyun stream end silence=$silenceDone max=$exceededMax")
                        speechStarted = false
                        silenceStart = 0L
                        energyHistory.clear()
                        interrupted = false
                        startupChunks.clear()
                        // 发送结束帧，Xfyun 已在实时处理，结果即刻返回
                        xfyunProvider?.sendStreamAudio(FloatArray(0), isEnd = true)
                    }
                }
            }
        } finally {
            try { audioRecord?.stop() } catch (_: Exception) {}
            try { audioRecord?.release() } catch (_: Exception) {}
            audioRecord = null
        }
    }

    /** AI 被打断通知 ViewModel */
    var onAiInterrupted: (() -> Unit)? = null

    // ====== CfAsr 录音循环 ======

    private suspend fun cfRecordLoop() {
        val rate = 16000
        val bufSz = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
        audioRecord = createAudioRecord(rate, bufSz)
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            delay(2000); if (isActive && !isProcessing) startListening(); return
        }
        audioRecord?.startRecording()

        val buf = ShortArray(bufSz / 2)
        try {
            while (isActive && !isProcessing) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: 0
                if (n <= 0) { delay(10); continue }
                val chunk = buf.copyOf(n)
                val processed = preprocessor.processShortToFloat(chunk)
                cloudProvider?.sendAudioFloat(processed)
            }
        } finally {
            try { audioRecord?.stop() } catch (_: Exception) {}
            try { audioRecord?.release() } catch (_: Exception) {}
            audioRecord = null
        }
    }

    // ====== 状态重置 ======

    fun onProcessingComplete() {
        isProcessing = false
        // 循环自己还在跑，不需要重启
        if (isActive && state != State.SPEAKING) {
            state = State.LISTENING
        }
    }

    // ====== 结束通话 ======

    fun endCall() {
        isActive = false
        isProcessing = false

        // 恢复音频模式
        try { audioManager.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
        try { audioManager.isSpeakerphoneOn = false } catch (_: Exception) {}
        audioRecord?.apply {
            try { AcousticEchoCanceler.create(audioSessionId)?.release() } catch (_: Exception) {}
            try { NoiseSuppressor.create(audioSessionId)?.release() } catch (_: Exception) {}
        }
        recordJob?.cancel()
        recordJob = null
        ttsConsumerJob?.cancel()
        ttsConsumerJob = null
        ttsSentenceQueue.cancel()
        recordScope?.cancel()
        recordScope = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        try { cloudProvider?.disconnect() } catch (_: Exception) {}
        cloudProvider = null

        try { xfyunProvider?.destroy() } catch (_: Exception) {}
        xfyunProvider = null

        try { systemTts?.stop() } catch (_: Exception) {}
        try { systemTts?.shutdown() } catch (_: Exception) {}
        systemTts = null
        ttsInitDone = false
        ttsSpeaking = false
        state = State.IDLE
    }

    fun destroy() { endCall() }
}
