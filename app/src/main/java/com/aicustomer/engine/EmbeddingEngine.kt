package com.aicustomer.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class EmbeddingEngine(private val context: Context) {

    companion object {
        private const val EMBEDDING_DIM = 384
    }

    private var loaded = false
    private var serverUrl: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun load(): Result<Unit> {
        val prefs = context.getSharedPreferences("asr_prefs", android.content.Context.MODE_PRIVATE)
        val cfUrl = prefs.getString("cf_url", "") ?: ""
        if (cfUrl.isNotBlank()) {
            serverUrl = cfUrl
                .replace("ws://", "http://")
                .replace("wss://", "https://")
                .replace("/v1/transcribe", "/v1/embed")
        }
        loaded = true
        return Result.success(Unit)
    }

    suspend fun embed(text: String): FloatArray {
        val url = serverUrl
        if (url == null) return hashBasedEmbedding(text)

        return withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("texts", JSONArray().apply { put(text) })
                }
                val body = RequestBody.create("application/json".toMediaType(), json.toString())
                val request = Request.Builder().url(url).post(body).build()
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: return@withContext hashBasedEmbedding(text)
                val result = JSONObject(responseBody)
                if (result.has("error")) return@withContext hashBasedEmbedding(text)
                val embeddings = result.getJSONArray("embeddings")
                val first = embeddings.getJSONArray(0)
                val arr = FloatArray(first.length())
                for (i in arr.indices) arr[i] = first.getDouble(i).toFloat()
                arr
            } catch (e: Exception) {
                hashBasedEmbedding(text)
            }
        }
    }

    suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        val url = serverUrl
        if (url == null) return texts.map { hashBasedEmbedding(it) }

        return withContext(Dispatchers.IO) {
            try {
                val arr = JSONArray()
                for (t in texts) arr.put(t)
                val json = JSONObject().apply { put("texts", arr) }
                val body = RequestBody.create("application/json".toMediaType(), json.toString())
                val request = Request.Builder().url(url).post(body).build()
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: return@withContext texts.map { hashBasedEmbedding(it) }
                val result = JSONObject(responseBody)
                if (result.has("error")) return@withContext texts.map { hashBasedEmbedding(it) }
                val embeddings = result.getJSONArray("embeddings")
                (0 until embeddings.length()).map { i ->
                    val vec = embeddings.getJSONArray(i)
                    FloatArray(vec.length()) { j -> vec.getDouble(j).toFloat() }
                }
            } catch (e: Exception) {
                texts.map { hashBasedEmbedding(it) }
            }
        }
    }

    private fun hashBasedEmbedding(text: String): FloatArray {
        val result = FloatArray(EMBEDDING_DIM)
        var h0 = text.hashCode()
        var h1 = (text + "salt1").hashCode()
        var h2 = (text + "salt2").hashCode()
        for (i in result.indices) {
            h0 = h0 * 31 + i + h1
            h1 = h1 * 17 + i + h2
            h2 = h2 * 13 + i
            val mixed = (h0 xor (h1 shl 8) xor (h2 shl 16)).toInt()
            result[i] = ((mixed and 0xFFFF) / 65535f - 0.5f) * 0.2f
        }
        var norm = 0f
        for (v in result) norm += v * v
        norm = kotlin.math.sqrt(norm)
        if (norm > 1e-8f) {
            for (i in result.indices) result[i] /= norm
        }
        return result
    }

    fun getDimension(): Int = EMBEDDING_DIM
    fun isLoaded(): Boolean = loaded
}
