package com.aicustomer.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File

class SherpaTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "SherpaTtsEngine"
        private const val MODEL_DIR = "tts-vits-zh"
    }

    private var tts: OfflineTts? = null
    private var loaded = false
    private var sampleRate = 22050
    @Volatile private var playing = false
    private var activeTrack: AudioTrack? = null

    fun loadBlocking() {
        if (loaded) return
        val modelDir = File(context.filesDir, "models/$MODEL_DIR")
        val modelFile = File(modelDir, "model.onnx")
        if (!modelFile.exists()) {
            Log.w(TAG, "TTS model not found at ${modelFile.absolutePath}")
            return
        }

        try {
            val base = modelDir.absolutePath
            val espeakDir = File(modelDir, "espeak-ng-data")
            val hasPhontab = File(espeakDir, "phontab").exists()

            val vitsConfig = OfflineTtsVitsModelConfig(
                model = "$base/model.onnx",
                tokens = "$base/tokens.txt",
                lexicon = "",
                dataDir = if (hasPhontab) espeakDir.absolutePath else base,
                dictDir = ""
            )
            val modelConfig = OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = 2,
                provider = "cpu"
            )
            val config = OfflineTtsConfig(
                model = modelConfig,
                ruleFsts = "",
                ruleFars = "",
                maxNumSentences = 1,
                silenceScale = 0.15f
            )
            tts = OfflineTts(null, config)
            sampleRate = 22050
            loaded = tts != null
            Log.i(TAG, "Sherpa TTS loaded sr=$sampleRate")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Sherpa TTS", e)
            loaded = false
        }
    }

    fun synthesize(text: String, sid: Int = 0, speed: Float = 1.0f): FloatArray? {
        val t = tts ?: return null
        if (!loaded || text.isBlank()) return null
        return try {
            val audio = t.generate(text, sid, speed)
            audio.samples
        } catch (e: Exception) {
            Log.e(TAG, "Synthesis failed: ${e.message}")
            null
        }
    }

    fun playBlocking(text: String, speed: Float = 1.0f) {
        val samples = synthesize(text, 0, speed) ?: return
        if (samples.isEmpty()) return

        try {
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val bufSize = if (minBuf > 0) (minBuf * 2).coerceAtMost(samples.size * 2) else samples.size * 2

            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build(),
                bufSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "AudioTrack init failed, state=${track.state}")
                try { track.release() } catch (_: Exception) {}
                return
            }

            activeTrack = track
            playing = true
            track.play()
            var offset = 0
            while (offset < samples.size && playing) {
                val chunk = minOf(bufSize / 2, samples.size - offset)
                val written = track.write(samples, offset, chunk, AudioTrack.WRITE_BLOCKING)
                if (written < 0) break
                offset += written
            }

            if (playing) {
                val durationMs = (samples.size * 1000L) / sampleRate
                Thread.sleep(durationMs + 200)
            }
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
            activeTrack = null
            playing = false
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack playback error: ${e.message}")
            playing = false; activeTrack = null
        }
    }

    fun stop() {
        playing = false
        activeTrack?.let {
            try { it.pause() } catch (_: Exception) {}
            try { it.flush() } catch (_: Exception) {}
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        activeTrack = null
    }

    fun isPlaying(): Boolean = playing

    fun isLoaded(): Boolean = loaded
    fun getSampleRate(): Int = sampleRate

    fun unload() {
        tts?.release()
        tts = null
        loaded = false
    }
}
