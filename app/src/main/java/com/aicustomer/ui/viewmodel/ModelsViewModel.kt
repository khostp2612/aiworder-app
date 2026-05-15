package com.aicustomer.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aicustomer.App
import com.aicustomer.engine.ModelManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ModelsViewModel(application: Application) : ViewModel() {

    private val app = application as App
    private val modelManager = app.modelManager

    private val _modelStatuses = MutableStateFlow<List<Pair<ModelManager.ModelInfo, Boolean>>>(emptyList())
    val modelStatuses: StateFlow<List<Pair<ModelManager.ModelInfo, Boolean>>> = _modelStatuses.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _downloadingModel = MutableStateFlow<String?>(null)
    val downloadingModel: StateFlow<String?> = _downloadingModel.asStateFlow()

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

    init { refreshStatuses() }

    fun refreshStatuses() {
        _modelStatuses.value = modelManager.getModelStatuses()
    }

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
