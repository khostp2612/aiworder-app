package com.aicustomer.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aicustomer.App
import com.aicustomer.data.model.Identity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class IdentityViewModel(application: Application) : ViewModel() {

    private val app = application as App
    private val identityManager = app.identityManager

    private val _identities = MutableStateFlow<List<Identity>>(emptyList())
    val identities: StateFlow<List<Identity>> = _identities.asStateFlow()

    private val _activeIdentity = MutableStateFlow<Identity?>(null)
    val activeIdentity: StateFlow<Identity?> = _activeIdentity.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val presets: List<Identity> = identityManager.presets

    init {
        loadIdentities()
    }

    fun loadIdentities() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                identityManager.initFromPresetsIfNeeded()
                _identities.value = identityManager.getAllIdentities()
                _activeIdentity.value = identityManager.getActiveIdentity()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** 激活后刷新完整状态 */
    fun activateIdentity(identity: Identity) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                identityManager.activateIdentity(identity)
                _identities.value = identityManager.getAllIdentities()
                _activeIdentity.value = identityManager.getActiveIdentity()
                Log.d("IdentityVM", "Activated: ${identity.name}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** 创建并自动激活新身份 */
    fun createCustomIdentity(
        name: String, personality: String, speakingStyle: String,
        expertise: String, greeting: String, taboos: String = ""
    ) {
        if (name.isBlank() || name.length > 20) return

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val identity = Identity(
                    name = name.take(20),
                    personality = personality.take(50).ifBlank { "友好专业" },
                    speakingStyle = speakingStyle.take(50).ifBlank { "亲切自然" },
                    expertise = expertise.take(50).ifBlank { "通用问答" },
                    greeting = greeting.take(100).ifBlank { "您好，有什么可以帮您的？" },
                    taboos = taboos.take(50)
                )
                val id = identityManager.createIdentity(identity)
                identityManager.activateIdentity(identity.copy(id = id))
                _identities.value = identityManager.getAllIdentities()
                _activeIdentity.value = identityManager.getActiveIdentity()
                Log.d("IdentityVM", "Created and activated custom identity: $name")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteIdentity(identity: Identity) {
        viewModelScope.launch {
            identityManager.deleteIdentity(identity)
            loadIdentities()
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return IdentityViewModel(application) as T
        }
    }
}
