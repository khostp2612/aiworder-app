package com.aicustomer.voice

import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import com.aicustomer.engine.SherpaTtsEngine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class VoicePipeline(private val context: Context) {

    private var systemTts: TextToSpeech? = null
    private var sherpaTts: SherpaTtsEngine? = null
    private var ttsInitDone = false
    private var ttsInitFailed = false
    @Volatile private var isActive = false
    @Volatile var isProcessing = false
    private var audioRecord: AudioRecord? = null
    private var recordScope: CoroutineScope? = null
    private var ttsConsumerJob: Job? = null
    private var recordJob: Job? = null

    private val asrProvider: AsrProvider = LocalAsrProvider(context)
    private val audioPreprocessor = AudioPreprocessor()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioRecordRetries = 0

    // Energy-based barge-in with percentile ceiling to prevent self-interrupt
    private var bargeTtsFrameCount = 0
    private var lastBargeInterruptTime = 0L
    private val BARGE_CONSEC_FRAMES = 5
    private val BARGE_COOLDOWN_MS = 1500L
    private val TTS_GRACE_MS = 200L
    private var bargeConsecAbove = 0
    private var bargeEnergyCeiling = 0.0
    private var bargeCeilingCooldown = 0
    private val BARGE_CEILING_RATIO = 2.0
    private var ttsStartTime = 0L
    private val turnEpoch = AtomicLong(0)
    @Volatile private var ttsInSilenceGap = false
    @Volatile private var queuingAllowed = true

    enum class State { IDLE, LISTENING, THINKING, SPEAKING }
    var onStateChanged: ((State) -> Unit)? = null
    var onFinalText: ((String) -> Unit)? = null
    var onAiToken: ((String) -> Unit)? = null
    var onAmplitude: ((Float) -> Unit)? = null
    var onAiInterrupted: (() -> Unit)? = null
    var onEmptyEndpoint: (() -> Unit)? = null

    private var state = State.IDLE
        set(v) { field = v; mainHandler.post { onStateChanged?.invoke(v) } }

    private val ttsSentenceQueue = Channel<String>(Channel.UNLIMITED)
    @Volatile var ttsSpeaking = false
        private set

    // File-based diagnostic log for OnePlus/ColorOS which suppresses logcat
    private val diagFile = File("/sdcard/Download/aiworker_barge_diag.txt")
    private val diagFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var diagLogCount = 0
    private val DIAG_MAX_LINES = 200

    private fun diagLog(msg: String) {
        try {
            if (diagLogCount >= 800) return
            diagLogCount++
            val ts = diagFmt.format(Date())
            diagFile.appendText("$ts $msg\n")
        } catch (_: Exception) {}
    }

    private val SAMPLE_RATE = 16000
    private val CHUNK_MS = 32
    private val MAX_SPEECH_MS = 15000L
    private val LISTENING_TIMEOUT_MS = 30000L
    private val THINKING_TIMEOUT_MS = 25000L
    private val SPEAKING_TIMEOUT_MS = 30000L

    private var speechStartTimeMs = 0L
    private var listeningStartTimeMs = 0L
    private var thinkingStartTimeMs = 0L
    private var speakingStartTimeMs = 0L
    private var consecutiveBlankEndpoints = 0
    private val MAX_BLANK_ENDPOINTS = 4

    private var speechActive = false
    private var interruptStartedAt = 0L
    private var recoverySilenceFrames = 0
    private val energyWindow = ArrayDeque<Double>(20)

    private fun log(msg: String) { Log.i("VoicePipeline", msg) }

    suspend fun startVoiceCall() {
        log("START full-duplex (local ASR)")
        diagFile.delete()
        diagLogCount = 0
        diagLog("===== Voice call STARTED =====")

        isActive = true; isProcessing = false; state = State.LISTENING
        ttsInitFailed = false
        audioRecordRetries = 0
        consecutiveBlankEndpoints = 0
        turnEpoch.incrementAndGet()
        queuingAllowed = true

        ttsInSilenceGap = false
        bargeTtsFrameCount = 0
        bargeConsecAbove = 0
        bargeEnergyCeiling = 0.0
        bargeCeilingCooldown = 0
        energyWindow.clear()
        lastBargeInterruptTime = 0L
        listeningStartTimeMs = System.currentTimeMillis()
        thinkingStartTimeMs = 0L
        speakingStartTimeMs = 0L
        audioPreprocessor.reset()

        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = true

        recordScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Start audio recording immediately — don't wait for ASR model loading
        startListening()

        // Load ASR models in background, enable ASR when ready
        recordScope?.launch(Dispatchers.IO) {
            try {
                val ok = asrProvider.init()
                diagLog("asrInit=$ok ready=${asrProvider.isReady()}")
                log("ASR init result=$ok")
            } catch (e: Exception) {
                log("ASR init failed: ${e.message}")
                this@VoicePipeline.isActive = false
                state = State.IDLE
                return@launch
            }
        }

        recordScope?.launch(Dispatchers.IO) {
            try {
                val st = SherpaTtsEngine(context)
                st.loadBlocking()
                if (st.isLoaded()) {
                    sherpaTts = st
                    log("Sherpa TTS loaded (local)")
                } else {
                    log("Sherpa TTS not available, will try system TTS")
                }
            } catch (e: Exception) {
                log("Sherpa TTS init error: ${e.message}")
            }
        }

        ttsConsumerJob = recordScope?.launch { ttsConsumerLoop() }
    }

    private suspend fun ttsConsumerLoop() {
        initTts()
        val myEpoch = turnEpoch.get()
        for (sentence in ttsSentenceQueue) {
            if (!isActive) break
            if (turnEpoch.get() != myEpoch) break
            if (sentence == "__TURN_END__") {
                ttsSpeaking = false
        ttsInSilenceGap = false; bargeConsecAbove = 0; bargeTtsFrameCount = 0; bargeEnergyCeiling = 0.0; bargeCeilingCooldown = 0
                speakingStartTimeMs = 0L
                delay(800L)
                isProcessing = false
                listeningStartTimeMs = System.currentTimeMillis()
                if (isActive) state = State.LISTENING
                continue
            }
            try {
                ttsSpeaking = true
                ttsStartTime = System.currentTimeMillis()
                diagLog("TTS sentence START, bargeConsec=${bargeConsecAbove}")
                if (speakingStartTimeMs == 0L) speakingStartTimeMs = ttsStartTime
                state = State.SPEAKING
                speakSentenceInternal(sentence)
            } catch (e: Exception) {
                Log.w("VoicePipeline", "TTS sentence failed: ${e.message}")
            }
        }
        ttsSpeaking = false
        ttsInSilenceGap = false
        speakingStartTimeMs = 0L
        speakingStartTimeMs = 0L
        isProcessing = false
        if (isActive) state = State.LISTENING
    }

    fun queueTtsSentence(text: String) {
        if (!queuingAllowed) return
        val t = text.replace(Regex("<[^>]*>"), "").replace(Regex("[*#_~`|\\\\]"), "").trim()
        if (t.isNotBlank()) ttsSentenceQueue.trySend(t)
    }

    fun queueTurnEnd() { ttsSentenceQueue.trySend("__TURN_END__") }

    private suspend fun speakSentenceInternal(text: String) {
        if (initTts()) {
            try {
                speakWithSystemTts(text)
                return
            } catch (e: Exception) {
                Log.w("VoicePipeline", "System TTS speak failed: ${e.message}")
            }
        }

        val st = sherpaTts
        if (st != null && st.isLoaded()) {
            try {
                withContext(Dispatchers.IO) { st.playBlocking(text) }
                return
            } catch (e: Exception) {
                Log.w("VoicePipeline", "Sherpa TTS failed: ${e.message}")
            }
        }
    }

    private suspend fun speakWithSystemTts(text: String) {
        val tts = systemTts ?: return
        ttsInSilenceGap = false
        suspendCancellableCoroutine<Unit> { cont ->
            val id = "tts_${System.currentTimeMillis()}"
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { ttsInSilenceGap = false }
                override fun onDone(id: String?) { ttsInSilenceGap = true; if (cont.isActive) cont.resume(Unit, onCancellation = {}) }
                override fun onError(id: String?) { ttsInSilenceGap = true; Log.w("VoicePipeline", "TTS onError id=$id"); if (cont.isActive) cont.resume(Unit, onCancellation = {}) }
                @Deprecated("Deprecated in Java") override fun onError(id: String?, code: Int) { ttsInSilenceGap = true; Log.w("VoicePipeline", "TTS onError id=$id code=$code"); if (cont.isActive) cont.resume(Unit, onCancellation = {}) }
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    private suspend fun initTts(): Boolean {
        if (ttsInitFailed) return false
        if (systemTts != null && ttsInitDone) return true
        val ok = initSystemTts()
        if (!ok) {
            ttsInitFailed = true
            log("TTS init failed, will skip all TTS playback")
        }
        return ok
    }

    private suspend fun initSystemTts(): Boolean = withTimeoutOrNull(5000L) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                systemTts?.stop(); systemTts?.shutdown()
                systemTts = TextToSpeech(context) { status ->
                    ttsInitDone = (status == TextToSpeech.SUCCESS)
                    log("TTS init status=$status")
                    if (ttsInitDone) {
                        val t = systemTts
                        if (t != null) {
                            var r = t.setLanguage(Locale.CHINESE)
                            if (r < 0) r = t.setLanguage(Locale.SIMPLIFIED_CHINESE)
                            if (r < 0) r = t.setLanguage(Locale("zh"))
                            if (r < 0) {
                                val zh = t.voices?.find { it.locale.language == "zh" }
                                if (zh != null) r = t.setVoice(zh)
                            }
                            ttsInitDone = r >= 0
                            if (ttsInitDone) {
                                t.setSpeechRate(1.5f)
                                t.setPitch(1.05f)
                            } else {
                                log("TTS no Chinese voice. Available: ${t.voices?.map{it.locale}?.distinct()}")
                            }
                        }
                    }
                    if (cont.isActive) cont.resume(ttsInitDone, onCancellation = {})
                }
                if (ttsInitDone) {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
                    systemTts?.setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                }
            }
        }
    } ?: false

    fun interruptAi() {
        log("interruptAi")
        turnEpoch.incrementAndGet()
        queuingAllowed = false
        isProcessing = false
        thinkingStartTimeMs = 0L

        systemTts?.stop()
        sherpaTts?.stop()
        try { audioManager.abandonAudioFocus(null) } catch (_: Exception) {}

        ttsConsumerJob?.cancel()
        ttsConsumerJob = null
        ttsSpeaking = false
        ttsInSilenceGap = false
        bargeConsecAbove = 0
        bargeTtsFrameCount = 0
        bargeEnergyCeiling = 0.0
        bargeCeilingCooldown = 0
        energyWindow.clear()
        lastBargeInterruptTime = System.currentTimeMillis()
        while (ttsSentenceQueue.tryReceive().isSuccess) { /* drain */ }

        asrProvider.reset()
        audioPreprocessor.reset()

        interruptStartedAt = System.currentTimeMillis()
        recoverySilenceFrames = 0
        speakingStartTimeMs = 0L
        consecutiveBlankEndpoints = 0
        listeningStartTimeMs = System.currentTimeMillis()

        state = State.LISTENING

        recordScope?.launch {
            delay(30)
            queuingAllowed = true
            ttsConsumerJob = launch { ttsConsumerLoop() }
        }

        startListening()
        mainHandler.post { onAiInterrupted?.invoke() }
    }

    private fun createAudioRecord(rate: Int, bufSz: Int): AudioRecord {
        audioRecord?.release()
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSz
        )
        if (rec.state == AudioRecord.STATE_INITIALIZED) {
            // AEC/NoiseSuppressor not used with MIC source — ColorOS handles
            // echo via hw routing. Keeping effects off avoids mic muting.
        }
        return rec
    }

    private fun startListening() {
        if (!isActive) return
        state = State.LISTENING
        listeningStartTimeMs = System.currentTimeMillis()
        recordJob?.cancel()
        val scope = recordScope ?: run {
            log("startListening: recordScope is null, abort")
            return
        }
        recordJob = scope.launch {
            try {
                recordLoop()
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                log("Record loop crashed: ${e.message}")
                this@VoicePipeline.isActive = false
                state = State.IDLE
            }
        }
    }

    private suspend fun recordLoop() {
        val chunkSize = SAMPLE_RATE * CHUNK_MS / 1000
        val bufSz = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
        audioRecord = createAudioRecord(SAMPLE_RATE, bufSz.coerceAtLeast(chunkSize * 4))
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            log("AudioRecord init failed, retrying (attempt $audioRecordRetries)...")
            audioRecordRetries++
            if (audioRecordRetries > 3) {
                log("AudioRecord failed after 3 retries, giving up")
                isActive = false; state = State.IDLE
                return
            }
            delay(2000); if (isActive) startListening(); return
        }
        audioRecord?.startRecording()

        val buf = ShortArray(chunkSize)
        val energyHistory = ArrayDeque<Double>(6)
        var lastHeartbeat = 0L
        speechActive = false

        try {
            while (isActive) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: 0
                if (n <= 0) {
                    if (ttsSpeaking && bargeTtsFrameCount < 5) {
                        diagLog("AudioRecord.read=$n during TTS!")
                    }
                    delay(5); continue
                }

                val now = System.currentTimeMillis()
                if (now - lastHeartbeat > 5000) {
                    lastHeartbeat = now
                    log("stream heartbeat proc=$isProcessing speak=$ttsSpeaking speech=$speechActive state=$state")
                }

                val chunk = buf.copyOf(n)

                val rawFloat = FloatArray(chunk.size) { chunk[it] / 32768.0f }

                if (ttsSpeaking) {
                    val frameEnergy = chunk.map { it.toDouble() * it.toDouble() }.average()
                    bargeTtsFrameCount++

                    val graceOver = (System.currentTimeMillis() - ttsStartTime) > TTS_GRACE_MS

                    // Energy-based barge-in: only active AFTER ceiling is calibrated.
                    // Ceiling = 95th percentile of recent mic energy (TTS echo through MIC).
                    // During calibration: no barge-in possible — prevents self-interrupt from
                    // initial TTS burst.
                    if (graceOver) {
                        energyWindow.addLast(frameEnergy)
                        if (energyWindow.size > 60) energyWindow.removeFirst()

                        val calibrated = bargeEnergyCeiling > 0 && energyWindow.size >= 15

                        // Update ceiling every 5 frames when not in active detection
                        if (bargeCeilingCooldown > 0) bargeCeilingCooldown--
                        if (calibrated && bargeConsecAbove == 0 && bargeCeilingCooldown == 0 && bargeTtsFrameCount % 5 == 1) {
                            val sorted = energyWindow.sorted()
                            bargeEnergyCeiling = sorted[(sorted.size * 0.95).toInt().coerceIn(0, sorted.size - 1)]
                        }

                        // First calibration: compute initial ceiling when window fills
                        if (!calibrated && energyWindow.size >= 15) {
                            val sorted = energyWindow.sorted()
                            bargeEnergyCeiling = sorted[(sorted.size * 0.95).toInt().coerceIn(0, sorted.size - 1)]
                            diagLog("barge ceiling calibrated: ${bargeEnergyCeiling.toInt()}")
                        }

                        // Only count barge-in frames when ceiling is calibrated
                        if (calibrated) {
                            val threshold = (bargeEnergyCeiling * BARGE_CEILING_RATIO).coerceAtLeast(500.0)
                            if (frameEnergy > threshold) {
                                bargeConsecAbove++
                                if (bargeConsecAbove >= 2) {
                                    diagLog("barge f=$bargeTtsFrameCount fE=${frameEnergy.toInt()} ceil=${bargeEnergyCeiling.toInt()} thr=${threshold.toInt()} cons=$bargeConsecAbove")
                                }
                            } else {
                                if (bargeConsecAbove > 0) {
                                    bargeConsecAbove--
                                    if (bargeConsecAbove == 0) {
                                        bargeCeilingCooldown = 20
                                        diagLog("barge reset, cooldown 20 frames")
                                    }
                                }
                            }
                        }
                    }

                    val cooldownOver = (System.currentTimeMillis() - lastBargeInterruptTime) > BARGE_COOLDOWN_MS

                    if (bargeConsecAbove >= BARGE_CONSEC_FRAMES && cooldownOver) {
                        diagLog("!!! BARGE-IN TRIGGERED (energy ceiling ratio) !!!")
                        interruptAi()
                        isProcessing = false
                        speechActive = false
                    }

                    if (speakingStartTimeMs > 0 && (now - speakingStartTimeMs) > SPEAKING_TIMEOUT_MS) {
                        diagLog("SPEAKING timeout")
                        interruptAi()
                        consecutiveBlankEndpoints = 0
                    }
                    continue
                }

                val preprocessedFloat = audioPreprocessor.process(rawFloat)
                val preprocessedShort = ShortArray(preprocessedFloat.size) {
                    (preprocessedFloat[it] * 32768f).toInt().coerceIn(-32768, 32767).toShort()
                }

                val energy = preprocessedShort.map { it.toDouble() * it.toDouble() }.average()
                energyHistory.addLast(energy)
                if (energyHistory.size > 6) energyHistory.removeFirst()
                val avgEnergy = energyHistory.average()

                val amp = (Math.sqrt(avgEnergy) / 2000.0).coerceIn(0.0, 1.0)
                onAmplitude?.invoke(amp.toFloat())

                if (thinkingStartTimeMs > 0 && (now - thinkingStartTimeMs) > THINKING_TIMEOUT_MS) {
                    log("THINKING timeout, force abort")
                    isProcessing = false
                    thinkingStartTimeMs = 0L
                    listeningStartTimeMs = System.currentTimeMillis()
                    consecutiveBlankEndpoints++
                    state = State.LISTENING
                    mainHandler.post { onAiInterrupted?.invoke() }
                }

                if (!isProcessing && isRecoveryComplete(now, energy)) {
                    val result = asrProvider.process(rawFloat, preprocessedFloat)

                    if (result.isSpeech) {
                        if (!speechActive) {
                            log("SPEECH start")
                            speechActive = true
                            speechStartTimeMs = now
                            listeningStartTimeMs = 0L
                        }
                    }

                    if (speechActive && result.isEndpoint) {
                        speechActive = false
                        audioPreprocessor.reset()
                        val final = asrProvider.finalize()
                        log("STT final='$final' len=${final.length}")
                        val meaningful = final.replace(Regex("""[\s。，！？；：、""」』）\)〕\}\]\[「『（\(〔\[]"""), "")
                        if (meaningful.length >= 2) {
                            consecutiveBlankEndpoints = 0
                            isProcessing = true
                            thinkingStartTimeMs = System.currentTimeMillis()
                            state = State.THINKING
                            mainHandler.post { onFinalText?.invoke(final) }
                        } else {
                            log("STT too short meaningful='$meaningful'")
                            consecutiveBlankEndpoints++
                            if (consecutiveBlankEndpoints >= MAX_BLANK_ENDPOINTS) {
                                log("Too many blank/short endpoints, resetting")
                                consecutiveBlankEndpoints = 0
                                listeningStartTimeMs = System.currentTimeMillis()
                                mainHandler.post { onEmptyEndpoint?.invoke() }
                            }
                        }
                    }

                    if (speechActive && (now - speechStartTimeMs) > MAX_SPEECH_MS) {
                        speechActive = false
                        audioPreprocessor.reset()
                        val final = asrProvider.finalize()
                        val meaningful = final.replace(Regex("""[\s。，！？；：、""」』）\)〕\}\]\[「『（\(〔\[]"""), "")
                        log("STT max-duration final='$final' meaningful len=${meaningful.length}")
                        if (meaningful.length >= 2) {
                            consecutiveBlankEndpoints = 0
                            isProcessing = true
                            thinkingStartTimeMs = System.currentTimeMillis()
                            state = State.THINKING
                            mainHandler.post { onFinalText?.invoke(final) }
                        }
                    }

                    if (!speechActive && !isProcessing && listeningStartTimeMs > 0 &&
                        (now - listeningStartTimeMs) > LISTENING_TIMEOUT_MS) {
                        log("LISTENING timeout")
                        listeningStartTimeMs = 0L
                        consecutiveBlankEndpoints = 0
                        mainHandler.post { onEmptyEndpoint?.invoke() }
                    }
                }
            }
        } finally {
            try { audioRecord?.stop() } catch (_: Exception) {}
            try { audioRecord?.release() } catch (_: Exception) {}
            audioRecord = null
        }
    }

    private fun isRecoveryComplete(now: Long, preprocessedEnergy: Double): Boolean {
        if (interruptStartedAt == 0L) return true
        val elapsed = now - interruptStartedAt
        if (elapsed > 2000L) {
            interruptStartedAt = 0L; recoverySilenceFrames = 0; return true
        }
        val threshold = 250.0
        if (preprocessedEnergy < threshold) {
            recoverySilenceFrames++
            if (recoverySilenceFrames >= 5) {
                interruptStartedAt = 0L; recoverySilenceFrames = 0; return true
            }
        } else {
            recoverySilenceFrames = 0
        }
        return false
    }

    fun onProcessingComplete() {
        isProcessing = false
        thinkingStartTimeMs = 0L
        listeningStartTimeMs = System.currentTimeMillis()
        if (isActive && state != State.SPEAKING) state = State.LISTENING
    }

    fun endCall() {
        isActive = false; isProcessing = false
        thinkingStartTimeMs = 0L
        onAmplitude?.invoke(0f)

        try { audioManager.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
        try { audioManager.isSpeakerphoneOn = false } catch (_: Exception) {}
        audioRecord?.apply {
            // AEC/NS not used with MIC source
        }
        recordJob?.cancel(); recordJob = null
        ttsConsumerJob?.cancel(); ttsConsumerJob = null
        ttsSentenceQueue.cancel()
        recordScope?.cancel(); recordScope = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        asrProvider.destroy()

        try { sherpaTts?.unload() } catch (_: Exception) {}
        sherpaTts = null

        try { systemTts?.stop() } catch (_: Exception) {}
        try { systemTts?.shutdown() } catch (_: Exception) {}
        systemTts = null; ttsInitDone = false; ttsInitFailed = false; ttsSpeaking = false
        state = State.IDLE
    }

    fun destroy() { endCall() }
}
