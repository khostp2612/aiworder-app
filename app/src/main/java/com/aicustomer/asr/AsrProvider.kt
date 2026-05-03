package com.aicustomer.asr

interface AsrProvider {
    fun isAvailable(): Boolean
    fun transcribe(samples: FloatArray, sampleRate: Int, callback: (String) -> Unit)
    fun destroy()
}
