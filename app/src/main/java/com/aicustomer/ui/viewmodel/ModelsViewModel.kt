package com.aicustomer.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aicustomer.App
import com.aicustomer.data.local.SecureStorage
import com.aicustomer.engine.ModelManager
import com.aicustomer.engine.OpenAiClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ModelsViewModel(application: Application) : ViewModel() {

    private val app = application as App
    private val modelManager = app.modelManager
    private val secureStorage = app.secureStorage

    private val _modelStatuses = MutableStateFlow<List<Pair<ModelManager.ModelInfo, Boolean>>>(emptyList())
    val modelStatuses: StateFlow<List<Pair<ModelManager.ModelInfo, Boolean>>> = _modelStatuses.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _downloadingModel = MutableStateFlow<String?>(null)
    val downloadingModel: StateFlow<String?> = _downloadingModel.asStateFlow()

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

    // API Key state
    private val _openAiKey = MutableStateFlow("")
    val openAiKey: StateFlow<String> = _openAiKey.asStateFlow()
    private val _openAiEndpoint = MutableStateFlow("")
    val openAiEndpoint: StateFlow<String> = _openAiEndpoint.asStateFlow()
    private val _openAiModel = MutableStateFlow("")
    val openAiModel: StateFlow<String> = _openAiModel.asStateFlow()

    private val _saveStatus = MutableStateFlow<String?>(null)
    val saveStatus: StateFlow<String?> = _saveStatus.asStateFlow()

    // CF ASR URL state
    private val _cfUrl = MutableStateFlow("")
    val cfUrl: StateFlow<String> = _cfUrl.asStateFlow()

    private val _useCloudLlm = MutableStateFlow(false)
    val useCloudLlm: StateFlow<Boolean> = _useCloudLlm.asStateFlow()

    sealed class ConnectionState {
        data object Idle : ConnectionState()
        data object Connecting : ConnectionState()
        data object Connected : ConnectionState()
        data class Failed(val message: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    fun validateApiKey(key: String): String? {
        if (key.isBlank()) return "API Key 不能为空"
        if (key.length < 8) return "API Key 长度至少8位"
        if (!key.matches(Regex("^[A-Za-z0-9._\\-/]+$"))) return "API Key 包含无效字符"
        return null
    }

    fun testConnection(key: String, endpoint: String) {
        val keyTrimmed = key.trim()
        val endpointTrimmed = endpoint.ifBlank { "https://api.deepseek.com/v1" }
        val validationError = validateApiKey(keyTrimmed)
        if (validationError != null) {
            _connectionState.value = ConnectionState.Failed(validationError)
            return
        }
        _connectionState.value = ConnectionState.Connecting
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = OpenAiClient(keyTrimmed, endpointTrimmed, "deepseek-chat")
                val result = withTimeoutOrNull(15_000L) {
                    client.testConnection()
                }
                if (result == true) {
                    _connectionState.value = ConnectionState.Connected
                } else {
                    _connectionState.value = ConnectionState.Failed("连接失败，请检查API Key和Endpoint")
                }
            } catch (_: Exception) {
                _connectionState.value = ConnectionState.Failed("连接超时或网络不可达")
            }
        }
    }

    fun dismissConnectionState() {
        _connectionState.value = ConnectionState.Idle
    }

    fun getOpenAiClient(): OpenAiClient? {
        val key = secureStorage.getApiKey()
        val endpoint = secureStorage.getEndpoint()
        if (key.isBlank()) return null
        return OpenAiClient(key, endpoint)
    }

    fun toggleCloudLlm() {
        _useCloudLlm.value = !_useCloudLlm.value
    }

    init {
        refreshStatuses()
        loadApiKeys()
    }

    fun refreshStatuses() {
        _modelStatuses.value = modelManager.getModelStatuses()
    }

    fun loadApiKeys() {
        _openAiKey.value = secureStorage.getApiKey()
        _openAiEndpoint.value = secureStorage.getEndpoint()
        _openAiModel.value = secureStorage.getModel()
        _cfUrl.value = secureStorage.getCfUrl()
    }

    fun saveOpenAiKey(key: String, endpoint: String, model: String) {
        secureStorage.setApiKey(key)
        secureStorage.setEndpoint(endpoint)
        secureStorage.setModel(model)
        loadApiKeys()
        _saveStatus.value = "OpenAI 凭证已保存"
    }

    fun saveCfUrl(url: String) {
        secureStorage.setCfUrl(url)
        loadApiKeys()
        _saveStatus.value = "ASR WebSocket 地址已保存"
    }

    fun dismissSaveStatus() { _saveStatus.value = null }

    fun downloadModel(modelInfo: ModelManager.ModelInfo) {
        viewModelScope.launch {
            _downloadingModel.value = modelInfo.name
            _downloadProgress.value = 0f
            _downloadError.value = null
            try {
                modelManager.downloadModel(modelInfo).collect { progress ->
                    _downloadProgress.value = progress
                }
            } catch (e: Exception) {
                _downloadError.value = "下载失败: ${e.message}"
            } finally {
                _downloadingModel.value = null
                _downloadProgress.value = 0f
                refreshStatuses()
            }
        }
    }

    fun clearError() { _downloadError.value = null }

    fun deleteModel(modelInfo: ModelManager.ModelInfo) {
        modelManager.deleteModel(modelInfo)
        refreshStatuses()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ModelsViewModel(application) as T
        }
    }
}
