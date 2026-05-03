package com.aicustomer.voice

import android.content.Context
import android.content.SharedPreferences
import android.media.*
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.aicustomer.asr.CfAsrProvider
import com.aicustomer.engine.LlmEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.Locale

class VoicePipeline(
    private val context: Context,
    private val llmEngine: LlmEngine
) {
    private var systemTts: TextToSpeech? = null
    private var ttsInitDone = false
    @Volatile private var isActive = false
    @Volatile var isProcessing = false
    private var audioRecord: AudioRecord? = null
    private var cloudProvider: CfAsrProvider? = null
    private var recordScope: CoroutineScope? = null
    private var ttsJob: Job? = null

    private val preprocessor = AudioPreprocessor()
    private val prefs: SharedPreferences = context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    enum class State { IDLE, LISTENING, PROCESSING, SPEAKING }
    var onStateChanged: ((State) -> Unit)? = null
    var onResponseText: ((String) -> Unit)? = null
    var onPartialText: ((String) -> Unit)? = null
    var onFinalText: ((String) -> Unit)? = null
    private var state = State.IDLE
        set(v) { field = v; mainHandler.post { onStateChanged?.invoke(v) } }

    private fun log(msg: String) { Log.i("VoicePipeline", msg) }

    private val cloudReady: Boolean get() {
        if (cloudProvider != null && cloudProvider!!.isAvailable()) return true
        val wsUrl = prefs.getString("cf_url", "") ?: ""
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

    fun startVoiceCall() {
        log("START cloud=$cloudReady")
        isActive = true; isProcessing = false; state = State.LISTENING
        recordScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preprocessor.reset()

        if (cloudReady && cloudProvider != null) {
            cloudProvider!!.onListening = {
                if (isActive && !isProcessing) mainHandler.post { state = State.LISTENING }
            }
            cloudProvider!!.onFinalText = { text ->
                if (isActive && !isProcessing) {
                    isProcessing = true
                    state = State.PROCESSING
                    mainHandler.post { onFinalText?.invoke(text) }
                }
            }
            cloudProvider!!.onError = { err ->
                log("ASR error: $err")
                isProcessing = false
                if (isActive) recordScope?.launch { startListening() }
            }
            cloudProvider!!.connect()
        }

        recordScope?.launch { startListening() }
    }

    private fun startListening() {
        if (!isActive || isProcessing) return
        state = State.LISTENING
        recordScope?.launch {
            try { recordStreamLoop() } catch (_: CancellationException) {}
        }
    }

    private suspend fun recordStreamLoop() {
        val rate = 16000
        val bufSz = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
        audioRecord?.release()
        audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSz)
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

    fun onProcessingComplete() {
        isProcessing = false
        if (isActive) { state = State.LISTENING; startListening() }
    }

    suspend fun voiceChat(prompt: String) {
        state = State.PROCESSING
        val ttsChannel = kotlinx.coroutines.channels.Channel<String>(Channel.UNLIMITED)

        ttsJob = recordScope?.launch {
            for (sentence in ttsChannel) {
                try { speakSentence(sentence) } catch (_: Exception) {}
            }
        }

        try {
            val ft = StringBuilder()
            var lastSpeakIdx = 0

            llmEngine.generateStream(prompt).collect { t ->
                if (t in listOf("<|im_end|>", "</s>", "__GEN_START__")) return@collect
                onResponseText?.invoke(t)
                ft.append(t)

                val current = ft.toString()
                for (sep in listOf("。", "！", "？", "；", "\n")) {
                    val idx = current.indexOf(sep, lastSpeakIdx)
                    if (idx >= 0) {
                        val sentence = current.substring(lastSpeakIdx, idx + 1).trim()
                        if (sentence.isNotBlank() && sentence.length > 1) {
                            ttsChannel.trySend(sentence)
                        }
                        lastSpeakIdx = idx + 1
                    }
                }
            }

            val remaining = ft.toString().substring(lastSpeakIdx)
                .replace(Regex("<[^>]*>"), "").trim()
            if (remaining.isNotBlank()) ttsChannel.trySend(remaining)
        } catch (e: Exception) {
            log("LLM err: ${e.message}")
        } finally {
            ttsChannel.close()
            ttsJob?.join()
            ttsJob = null
        }
    }

    suspend fun speakResponse(text: String) {
        val t = text.replace(Regex("<[^>]*>"), "").trim()
        if (t.isBlank()) return
        state = State.SPEAKING
        if (!initTts()) return

        val sentences = t.split(Regex("(?<=[。！？；\\n])"))
        for (s in sentences) {
            val trimmed = s.trim().replace(Regex("[*#_~`|\\\\]"), "").trim()
            if (trimmed.isNotBlank()) {
                speakSentence(trimmed)
            }
        }
    }

    private suspend fun speakSentence(text: String) {
        state = State.SPEAKING
        if (!initTts()) return
        val t = text.replace(Regex("[*#_~`|\\\\]"), "").trim()
        if (t.isBlank()) return
        suspendCancellableCoroutine<Unit> { cont ->
            val id = "tts_${System.currentTimeMillis()}"
            systemTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (id == id && cont.isActive) cont.resume(Unit) {} }
                override fun onError(id: String?) { if (id == id && cont.isActive) cont.resume(Unit) {} }
                @Deprecated("Deprecated in Java") override fun onError(id: String?, code: Int) { if (id == id && cont.isActive) cont.resume(Unit) {} }
            })
            systemTts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    fun endCall() {
        isActive = false
        isProcessing = false

        try { audioRecord?.stop() } catch (_: Exception) {}
        recordScope?.cancel()
        recordScope = null
        ttsJob?.cancel()
        ttsJob = null
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        try { cloudProvider?.disconnect() } catch (_: Exception) {}
        cloudProvider = null

        try { systemTts?.stop() } catch (_: Exception) {}
        try { systemTts?.shutdown() } catch (_: Exception) {}
        systemTts = null
        ttsInitDone = false
        state = State.IDLE
    }

    fun destroy() { endCall() }
}
