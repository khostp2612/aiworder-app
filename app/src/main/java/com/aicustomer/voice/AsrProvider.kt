package com.aicustomer.voice

interface AsrProvider {
    data class ProcessingResult(
        val isSpeech: Boolean,
        val isEndpoint: Boolean,
        val speechConfidence: Float = 0f
    ) {
        companion object {
            val SILENCE = ProcessingResult(false, false, 0f)
            fun speech(conf: Float = 0.5f) = ProcessingResult(true, false, conf)
        }
    }

    fun init(): Boolean
    fun process(rawChunk: FloatArray, preprocessedChunk: FloatArray): ProcessingResult
    fun finalize(): String
    fun reset()
    fun destroy()
    fun isReady(): Boolean
    fun checkVad(audio: ShortArray): VadDetector.VadState
    fun checkVadQuick(audio: ShortArray): VadDetector.VadState
    fun getSpeechConfidence(): Float
    fun isSpectralGateEnabled(): Boolean
}
