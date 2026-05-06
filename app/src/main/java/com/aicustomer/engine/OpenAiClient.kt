package com.aicustomer.engine

import android.util.Base64
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.util.concurrent.atomic.AtomicBoolean

class OpenAiClient(
    private val apiKey: String,
    private val endpoint: String = "https://api.deepseek.com/v1",
    private val model: String = "deepseek-chat"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val abortFlag = AtomicBoolean(false)
    @Volatile private var activeCall: Call? = null

    fun isAvailable() = apiKey.isNotBlank()

    fun abort() {
        abortFlag.set(true)
        activeCall?.cancel()
    }

    fun testConnection(): Boolean {
        return try {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "test")
                    })
                })
                put("max_tokens", 1)
            }
            val request = Request.Builder()
                .url("$endpoint/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            response.code == 200
        } catch (_: Exception) {
            false
        }
    }

    fun chatStream(
        systemPrompt: String,
        userMessage: String
    ): Flow<String> {
        val channel = Channel<String>(Channel.UNLIMITED)
        abortFlag.set(false)

        Thread {
            try {
                if (abortFlag.get()) { channel.close(); return@Thread }

                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userMessage)
                    })
                }

                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("stream", true)
                    put("temperature", 0.7)
                    put("max_tokens", 512)
                }

                val request = Request.Builder()
                    .url("$endpoint/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val call = client.newCall(request)
                activeCall = call
                val response = call.execute()

                if (response.code != 200) {
                    channel.trySend("[云端请求失败: HTTP ${response.code}]")
                    channel.close()
                    return@Thread
                }

                response.body?.charStream()?.use { reader ->
                    val br = BufferedReader(reader)
                    var line: String? = null
                    while (!abortFlag.get() && br.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        if (l.startsWith("data: ")) {
                            val data = l.removePrefix("data: ")
                            if (data == "[DONE]") break
                            try {
                                val json = JSONObject(data)
                                val choices = json.optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    val delta = choices.getJSONObject(0).optJSONObject("delta")
                                    val content = delta?.optString("content", "") ?: ""
                                    if (content.isNotEmpty()) {
                                        channel.trySend(content)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                if (!abortFlag.get()) {
                    channel.trySend("[云端请求失败: ${e.message}]")
                }
            } finally {
                activeCall = null
                channel.close()
            }
        }.start()

        return channel.receiveAsFlow()
    }
}
