package com.aicustomer.asr

import android.util.Base64
import okhttp3.*
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class XfyunAsrProvider(
    private val appId: String,
    private val apiKey: String,
    private val apiSecret: String
) : AsrProvider {
    companion object {
        val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }
    }

    private var resultCallback: ((String) -> Unit)? = null
    private var interimCallback: ((String) -> Unit)? = null
    private var resultText = ""

    var onInterim: ((String) -> Unit)?
        get() = interimCallback
        set(v) { interimCallback = v }

    // 流式传输
    private var streamWs: WebSocket? = null
    private var streamFinalCallback: ((String) -> Unit)? = null
    private var streamRate = 16000
    private var streamResultText = ""

    override fun isAvailable() = appId.isNotBlank() && apiKey.isNotBlank()

    override fun transcribe(samples: FloatArray, sampleRate: Int, callback: (String) -> Unit) {
        if (samples.size < sampleRate / 4) { callback(""); return }

        resultCallback = callback
        resultText = ""
        val hostUrl = buildAuthUrl()
        if (hostUrl == null) { log("auth fail"); callback(""); return }
        val request = Request.Builder().url(hostUrl).build()

        sharedClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                sendFirstFrame(ws, sampleRate)
                sendAudioChunks(ws, samples, sampleRate, isEnd = true)
            }
            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text) { finalText -> ws.close(1000, ""); callback(finalText) }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                callback("")
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {}
        })
    }

    // ====== 流式模式：边说边传，减少延迟 ======

    fun beginStream(sampleRate: Int, onFinal: (String) -> Unit) {
        streamFinalCallback = onFinal
        streamResultText = ""
        streamRate = sampleRate
        val hostUrl = buildAuthUrl()
        if (hostUrl == null) { log("stream auth fail"); onFinal(""); return }
        val request = Request.Builder().url(hostUrl).build()

        sharedClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                streamWs = ws
                sendFirstFrame(ws, sampleRate)
            }
            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text) { finalText ->
                    ws.close(1000, ""); streamFinalCallback?.invoke(finalText); streamWs = null
                }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                streamFinalCallback?.invoke(""); streamWs = null
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                streamWs = null
            }
        })
    }

    fun sendStreamAudio(samples: FloatArray, isEnd: Boolean) {
        val ws = streamWs ?: return
        sendAudioChunks(ws, samples, streamRate, isEnd)
    }

    fun abortStream() {
        streamWs?.close(1000, ""); streamWs = null
        streamFinalCallback = null
    }

    // ====== 共享辅助方法 ======

    private fun sendFirstFrame(ws: WebSocket, sampleRate: Int) {
        val params = JSONObject().apply {
            put("common", JSONObject().put("app_id", appId))
            put("business", JSONObject().apply {
                put("language", "zh_cn"); put("domain", "iat"); put("accent", "mandarin"); put("vad_eos", 800)
            })
            put("data", JSONObject().apply {
                put("status", 0); put("format", "audio/L16;rate=$sampleRate"); put("encoding", "raw"); put("audio", "")
            })
        }
        ws.send(params.toString())
    }

    private fun sendAudioChunks(ws: WebSocket, samples: FloatArray, sampleRate: Int, isEnd: Boolean) {
        // 兜底：空数组 + isEnd → 发送纯结束帧
        if (samples.isEmpty() && isEnd) {
            val audio = JSONObject().apply {
                put("data", JSONObject().apply {
                    put("status", 2)
                    put("format", "audio/L16;rate=$sampleRate"); put("encoding", "raw")
                    put("audio", "")
                })
            }
            ws.send(audio.toString())
            return
        }
        val chunkSize = 3840; var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + chunkSize, samples.size)
            val pcm = ShortArray(end - offset) { (samples[offset + it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
            val isLast = isEnd && (offset + chunkSize >= samples.size)
            val audio = JSONObject().apply {
                put("data", JSONObject().apply {
                    put("status", if (isLast) 2 else 1)
                    put("format", "audio/L16;rate=$sampleRate"); put("encoding", "raw")
                    put("audio", Base64.encodeToString(shortArrayToByteArray(pcm), Base64.NO_WRAP))
                })
            }
            ws.send(audio.toString()); offset += chunkSize
        }
    }

    private fun handleMessage(text: String, onFinal: (String) -> Unit) {
        try {
            val json = JSONObject(text)
            val code = json.optInt("code", 0)
            if (code != 0) { onFinal(""); return }
            val data = json.optJSONObject("data") ?: return
            val status = data.optInt("status", 0)
            val result = data.optJSONObject("result") ?: return
            val wsArray = result.optJSONArray("ws")
            if (wsArray != null) {
                val sb = StringBuilder()
                for (i in 0 until wsArray.length()) {
                    val cw = wsArray.getJSONObject(i).optJSONArray("cw")
                    if (cw != null) for (j in 0 until cw.length()) sb.append(cw.getJSONObject(j).optString("w", ""))
                }
                val text = sb.toString()
                if (status == 2) onFinal(text)
                else if (status == 1) interimCallback?.invoke(text)
            }
        } catch (_: Exception) {}
    }

    private fun buildAuthUrl(): String? = try {
        val host = "iat-api.xfyun.cn"; val path = "/v2/iat"
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date())
        val signatureOrigin = "host: $host\ndate: $date\nGET $path HTTP/1.1"
        val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(apiSecret.toByteArray(), "HmacSHA256"))
        val signature = Base64.encodeToString(mac.doFinal(signatureOrigin.toByteArray()), Base64.NO_WRAP)
        val authorization = Base64.encodeToString("api_key=\"$apiKey\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"$signature\"".toByteArray(), Base64.NO_WRAP)
        "https://$host$path?authorization=$authorization&date=${URLEncoder.encode(date, "UTF-8").replace("+", "%20")}&host=$host"
    } catch (e: Exception) { null }

    private fun shortArrayToByteArray(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) { bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte(); bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte() }
        return bytes
    }

    private fun log(msg: String) {
        try { java.io.File("/data/data/com.aicustomer/files", "voice_log.txt").appendText("${System.currentTimeMillis() % 100000}: XF $msg\n") } catch (_: Exception) {}
    }

    override fun destroy() {
        abortStream()
    }
}
