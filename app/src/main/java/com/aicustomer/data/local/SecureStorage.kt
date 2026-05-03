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
}
