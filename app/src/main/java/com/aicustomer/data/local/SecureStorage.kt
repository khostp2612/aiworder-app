package com.aicustomer.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureStorage(context: Context) {

    private val plainPrefs: SharedPreferences =
        context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)

    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w("SecureStorage", "Failed to init EncryptedSharedPreferences, falling back to plain", e)
            context.getSharedPreferences("secure_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    private fun migrateIfNeeded(key: String) {
        val plainValue = plainPrefs.getString(key, null)
        if (plainValue != null && encryptedPrefs.getString(key, null) == null) {
            encryptedPrefs.edit().putString(key, plainValue).apply()
        }
    }

    fun getApiKey(): String {
        migrateIfNeeded("openai_key")
        return encryptedPrefs.getString("openai_key", "") ?: ""
    }

    fun setApiKey(value: String) {
        encryptedPrefs.edit().putString("openai_key", value).apply()
    }

    fun getEndpoint(): String {
        return plainPrefs.getString("openai_endpoint", "https://api.deepseek.com/v1") ?: ""
    }

    fun setEndpoint(value: String) {
        plainPrefs.edit().putString("openai_endpoint", value.ifBlank { "https://api.deepseek.com/v1" }).apply()
    }

    fun getModel(): String {
        return plainPrefs.getString("openai_model", "deepseek-chat") ?: "deepseek-chat"
    }

    fun setModel(value: String) {
        plainPrefs.edit().putString("openai_model", value.ifBlank { "deepseek-chat" }).apply()
    }

    fun getCfUrl(): String {
        return plainPrefs.getString("cf_url", "") ?: ""
    }

    fun setCfUrl(value: String) {
        plainPrefs.edit().putString("cf_url", value).apply()
    }

    fun setDefaultCfUrl(url: String) {
        if (getCfUrl().isBlank()) {
            setCfUrl(url)
        }
    }

    // === DeepSeek 独立凭证 ===
    fun getDeepseekKey(): String {
        migrateIfNeeded("deepseek_key")
        return encryptedPrefs.getString("deepseek_key", "") ?: ""
    }
    fun setDeepseekKey(value: String) {
        encryptedPrefs.edit().putString("deepseek_key", value).apply()
    }

    fun getDeepseekEndpoint(): String {
        return plainPrefs.getString("deepseek_endpoint", "https://api.deepseek.com/v1") ?: ""
    }
    fun setDeepseekEndpoint(value: String) {
        plainPrefs.edit().putString("deepseek_endpoint", value.ifBlank { "https://api.deepseek.com/v1" }).apply()
    }

    fun getDeepseekModel(): String {
        return plainPrefs.getString("deepseek_model", "deepseek-chat") ?: "deepseek-chat"
    }
    fun setDeepseekModel(value: String) {
        plainPrefs.edit().putString("deepseek_model", value.ifBlank { "deepseek-chat" }).apply()
    }

    // === 讯飞语音识别凭证 ===
    fun getXfyunAppId(): String {
        return plainPrefs.getString("xfyun_app_id", "") ?: ""
    }
    fun setXfyunAppId(value: String) {
        plainPrefs.edit().putString("xfyun_app_id", value).apply()
    }

    fun getXfyunApiKey(): String {
        migrateIfNeeded("xfyun_api_key")
        return encryptedPrefs.getString("xfyun_api_key", "") ?: ""
    }
    fun setXfyunApiKey(value: String) {
        encryptedPrefs.edit().putString("xfyun_api_key", value).apply()
    }

    fun getXfyunApiSecret(): String {
        migrateIfNeeded("xfyun_api_secret")
        return encryptedPrefs.getString("xfyun_api_secret", "") ?: ""
    }
    fun setXfyunApiSecret(value: String) {
        encryptedPrefs.edit().putString("xfyun_api_secret", value).apply()
    }

    // === ASR 提供商偏好 ===
    fun getAsrProvider(): String {
        return plainPrefs.getString("asr_provider", "cf") ?: "cf"
    }
    fun setAsrProvider(value: String) {
        plainPrefs.edit().putString("asr_provider", value).apply()
    }

    // === 云端模式 ===
    fun isCloudLlmEnabled(): Boolean {
        return getDeepseekKey().isNotBlank() || getApiKey().isNotBlank()
    }
}
