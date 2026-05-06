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
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private var resultCallback: ((String) -> Unit)? = null
    private var interimCallback: ((String) -> Unit)? = null
    private var resultText = ""

    var onInterim: ((String) -> Unit)?
        get() = interimCallback
        set(v) { interimCallback = v }

    override fun isAvailable() = appId.isNotBlank() && apiKey.isNotBlank()

    override fun transcribe(samples: FloatArray, sampleRate: Int, callback: (String) -> Unit) {
        if (samples.size < sampleRate / 4) { callback(""); return }

        resultCallback = callback
        resultText = ""
        val hostUrl = buildAuthUrl()
        if (hostUrl == null) { log("auth fail"); callback(""); return }
        val request = Request.Builder().url(hostUrl).build()

        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
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
                val chunkSize = 3840; var offset = 0
                while (offset < samples.size) {
                    val end = minOf(offset + chunkSize, samples.size)
                    val pcm = ShortArray(end - offset) { (samples[offset + it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
                    val audio = JSONObject().apply {
                        put("data", JSONObject().apply {
                            put("status", if (offset + chunkSize >= samples.size) 2 else 1)
                            put("format", "audio/L16;rate=$sampleRate"); put("encoding", "raw")
                            put("audio", Base64.encodeToString(shortArrayToByteArray(pcm), Base64.NO_WRAP))
                        })
                    }
                    ws.send(audio.toString()); offset += chunkSize
                }
            }
            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val code = json.optInt("code", 0)
                    if (code != 0) { ws.close(1000, ""); callback(""); return }
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
                        resultText = sb.toString()
                        if (status == 2) { ws.close(1000, ""); callback(resultText) }
                        else if (status == 1) { interimCallback?.invoke(resultText) }
                    }
                } catch (_: Exception) {}
            }
            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                callback("")
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {}
        })
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

    override fun destroy() {}
}
