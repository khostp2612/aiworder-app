package com.aicustomer.voice

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.debugDataStore by preferencesDataStore(name = "voice_debug_config")

class DebugConfig(private val context: Context) {

    companion object {
        val DEBUG_ENABLED = booleanPreferencesKey("debug_enabled")

        val SPECTRAL_START_THRESHOLD = floatPreferencesKey("spectral_start_threshold")
        val SPECTRAL_CONTINUE_THRESHOLD = floatPreferencesKey("spectral_continue_threshold")

        val VAD_SPEECH_START = floatPreferencesKey("vad_speech_start")
        val VAD_SPEECH_CONTINUE = floatPreferencesKey("vad_speech_continue")
        val VAD_CONFIRM_FRAMES = floatPreferencesKey("vad_confirm_frames")

        val ENERGY_MULTIPLIER = floatPreferencesKey("energy_multiplier")
        val CREST_FACTOR_MAX = floatPreferencesKey("crest_factor_max")

        val BARGE_MIN_SPEECH_MS = floatPreferencesKey("barge_min_speech_ms")
        val BARGE_COOLDOWN_MS = floatPreferencesKey("barge_cooldown_ms")
        val BARGE_REQUIRE_SPECTRAL = booleanPreferencesKey("barge_require_spectral")

        val TEXT_MIN_CHINESE_RATIO = floatPreferencesKey("text_min_chinese_ratio")

        // Default values
        const val DEF_SPECTRAL_START = 0.42f
        const val DEF_SPECTRAL_CONTINUE = 0.30f
        const val DEF_VAD_SPEECH_START = 0.70f
        const val DEF_VAD_SPEECH_CONTINUE = 0.50f
        const val DEF_VAD_CONFIRM_FRAMES = 5f
        const val DEF_ENERGY_MULTIPLIER = 3.5f
        const val DEF_CREST_FACTOR_MAX = 22f
        const val DEF_BARGE_MIN_SPEECH_MS = 300f
        const val DEF_BARGE_COOLDOWN_MS = 800f
        const val DEF_BARGE_REQUIRE_SPECTRAL = true
        const val DEF_TEXT_MIN_CHINESE_RATIO = 0.40f
    }

    val isDebugEnabled: Flow<Boolean> = context.debugDataStore.data.map { prefs ->
        prefs[DEBUG_ENABLED] ?: false
    }

    val spectralStartThreshold: Flow<Float> = context.debugDataStore.data.map { prefs ->
        prefs[SPECTRAL_START_THRESHOLD] ?: DEF_SPECTRAL_START
    }

    val spectralContinueThreshold: Flow<Float> = context.debugDataStore.data.map { prefs ->
        prefs[SPECTRAL_CONTINUE_THRESHOLD] ?: DEF_SPECTRAL_CONTINUE
    }

    val vadSpeechStart: Flow<Float> = context.debugDataStore.data.map { prefs ->
        prefs[VAD_SPEECH_START] ?: DEF_VAD_SPEECH_START
    }

    val vadSpeechContinue: Flow<Float> = context.debugDataStore.data.map { prefs ->
        prefs[VAD_SPEECH_CONTINUE] ?: DEF_VAD_SPEECH_CONTINUE
    }

    val vadConfirmFrames: Flow<Float> = context.debugDataStore.data.map { prefs ->
        prefs[VAD_CONFIRM_FRAMES] ?: DEF_VAD_CONFIRM_FRAMES
    }

    val energyMultiplier: Flow<Float> = context.debugDataStore.data.map { prefs ->
        prefs[ENERGY_MULTIPLIER] ?: DEF_ENERGY_MULTIPLIER
    }

    val crestFactorMax: Flow<Float> = context.debugDataStore.data.map { prefs ->
        prefs[CREST_FACTOR_MAX] ?: DEF_CREST_FACTOR_MAX
    }

    val bargeMinSpeechMs: Flow<Float> = context.debugDataStore.data.map { prefs ->
        prefs[BARGE_MIN_SPEECH_MS] ?: DEF_BARGE_MIN_SPEECH_MS
    }

    val bargeCooldownMs: Flow<Float> = context.debugDataStore.data.map { prefs ->
        prefs[BARGE_COOLDOWN_MS] ?: DEF_BARGE_COOLDOWN_MS
    }

    val bargeRequireSpectral: Flow<Boolean> = context.debugDataStore.data.map { prefs ->
        prefs[BARGE_REQUIRE_SPECTRAL] ?: DEF_BARGE_REQUIRE_SPECTRAL
    }

    val textMinChineseRatio: Flow<Float> = context.debugDataStore.data.map { prefs ->
        prefs[TEXT_MIN_CHINESE_RATIO] ?: DEF_TEXT_MIN_CHINESE_RATIO
    }

    suspend fun setDebugEnabled(enabled: Boolean) {
        context.debugDataStore.edit { prefs ->
            prefs[DEBUG_ENABLED] = enabled
        }
    }

    suspend fun setSpectralStartThreshold(value: Float) {
        context.debugDataStore.edit { prefs ->
            prefs[SPECTRAL_START_THRESHOLD] = value
        }
    }

    suspend fun setSpectralContinueThreshold(value: Float) {
        context.debugDataStore.edit { prefs ->
            prefs[SPECTRAL_CONTINUE_THRESHOLD] = value
        }
    }

    suspend fun setVadSpeechStart(value: Float) {
        context.debugDataStore.edit { prefs ->
            prefs[VAD_SPEECH_START] = value
        }
    }

    suspend fun setVadSpeechContinue(value: Float) {
        context.debugDataStore.edit { prefs ->
            prefs[VAD_SPEECH_CONTINUE] = value
        }
    }

    suspend fun setVadConfirmFrames(value: Int) {
        context.debugDataStore.edit { prefs ->
            prefs[VAD_CONFIRM_FRAMES] = value.toFloat()
        }
    }

    suspend fun setEnergyMultiplier(value: Float) {
        context.debugDataStore.edit { prefs ->
            prefs[ENERGY_MULTIPLIER] = value
        }
    }

    suspend fun setCrestFactorMax(value: Float) {
        context.debugDataStore.edit { prefs ->
            prefs[CREST_FACTOR_MAX] = value
        }
    }

    suspend fun setBargeMinSpeechMs(value: Int) {
        context.debugDataStore.edit { prefs ->
            prefs[BARGE_MIN_SPEECH_MS] = value.toFloat()
        }
    }

    suspend fun setBargeCooldownMs(value: Int) {
        context.debugDataStore.edit { prefs ->
            prefs[BARGE_COOLDOWN_MS] = value.toFloat()
        }
    }

    suspend fun setBargeRequireSpectral(require: Boolean) {
        context.debugDataStore.edit { prefs ->
            prefs[BARGE_REQUIRE_SPECTRAL] = require
        }
    }

    suspend fun setTextMinChineseRatio(value: Float) {
        context.debugDataStore.edit { prefs ->
            prefs[TEXT_MIN_CHINESE_RATIO] = value
        }
    }

    /**
     * Reset all thresholds to defaults.
     */
    suspend fun resetAll() {
        context.debugDataStore.edit { it.clear() }
    }
}
