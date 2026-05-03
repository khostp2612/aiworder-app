package com.aicustomer.asr

import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CfAsrProvider(
    private val wsUrl: String
) : AsrProvider {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    @Volatile private var connected = false

    var onListening: (() -> Unit)? = null
    var onFinalText: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    override fun isAvailable() = wsUrl.isNotBlank()

    fun connect() {
        if (connected) return
        val request = Request.Builder().url(wsUrl).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type", "")

                    if (type == "listening") {
                        onListening?.invoke()
                        return
                    }

                    if (type == "segment" || json.optBoolean("final", false)) {
                        val t = json.optString("text", "")
                            .ifBlank { json.optString("transcript", "") }
                            .ifBlank { json.optString("result", "") }
                        if (t.isNotBlank()) {
                            onFinalText?.invoke(t.trim())
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                onError?.invoke(t.message ?: "WebSocket disconnected")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
            }
        })
    }

    fun sendAudioShorts(samples: ShortArray) {
        if (!connected || ws == null) return
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = samples[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = (s shr 8 and 0xFF).toByte()
        }
        ws?.send(ByteString.of(*bytes))
    }

    fun sendAudioFloat(samples: FloatArray) {
        if (!connected || ws == null) return
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = (s shr 8 and 0xFF).toByte()
        }
        ws?.send(ByteString.of(*bytes))
    }

    fun disconnect() {
        connected = false
        ws?.close(1000, "")
        ws = null
        onListening = null
        onFinalText = null
        onError = null
    }

    override fun transcribe(samples: FloatArray, sampleRate: Int, callback: (String) -> Unit) {
        callback("")
    }

    override fun destroy() {
        disconnect()
    }
}
