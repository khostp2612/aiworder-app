package com.aicustomer.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aicustomer.App
import com.aicustomer.data.model.Memory
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MemoryViewModel(application: Application) : ViewModel() {

    private val app = application as App
    private val memoryManager = app.memoryManager
    private val database = app.database
    private val gson = Gson()

    private val _longTermMemories = MutableStateFlow<List<Memory>>(emptyList())
    val longTermMemories: StateFlow<List<Memory>> = _longTermMemories.asStateFlow()

    private val _shortTermMemories = MutableStateFlow<List<Memory>>(emptyList())
    val shortTermMemories: StateFlow<List<Memory>> = _shortTermMemories.asStateFlow()

    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus: StateFlow<String?> = _exportStatus.asStateFlow()

    init {
        loadMemories()
    }

    fun loadMemories() {
        viewModelScope.launch {
            _longTermMemories.value = database.memoryDao().getByType("long_term")
            _shortTermMemories.value = database.memoryDao().getByType("short_term")
        }
    }

    /** 通过 MemoryManager 删除（同步清理向量索引） */
    fun deleteMemory(memory: Memory) {
        viewModelScope.launch {
            memoryManager.deleteMemory(memory)
            loadMemories()
        }
    }

    fun clearAllShortTermMemories() {
        viewModelScope.launch {
            database.memoryDao().deleteAllByType("short_term")
            loadMemories()
        }
    }

    fun exportMemoriesToJson(uri: Uri) {
        viewModelScope.launch {
            try {
                var exportedCount = 0
                withContext(Dispatchers.IO) {
                    val allMemories = database.memoryDao().getByType("long_term")
                    exportedCount = allMemories.size
                    val exportList = allMemories.map { mem ->
                        mapOf(
                            "type" to mem.type,
                            "content" to mem.content,
                            "source" to mem.source,
                            "importanceScore" to mem.importanceScore,
                            "tags" to mem.tags,
                            "createdAt" to mem.createdAt,
                            "accessedAt" to mem.accessedAt,
                            "accessCount" to mem.accessCount
                        )
                    }
                    val json = gson.toJson(exportList)
                    app.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(json.toByteArray(Charsets.UTF_8))
                    }
                }
                _exportStatus.value = "导出成功：${exportedCount} 条长期记忆"
            } catch (e: Exception) {
                Log.e("MemoryViewModel", "Export failed", e)
                _exportStatus.value = "导出失败：${e.message}"
            }
        }
    }

    /** 通过 MemoryManager 导入（自动生成向量嵌入） */
    fun importMemoriesFromJson(uri: Uri) {
        viewModelScope.launch {
            try {
                var importedCount = 0
                val existingHashes = mutableSetOf<Int>()
                withContext(Dispatchers.IO) {
                    val json = app.contentResolver.openInputStream(uri)?.bufferedReader()
                        ?.readText() ?: throw IllegalStateException("无法读取文件")
                    val type = object : TypeToken<List<Map<String, Any>>>() {}.type
                    val importedData: List<Map<String, Any>> = gson.fromJson(json, type)

                    // 收集已有记忆的哈希用于去重
                    val dao = database.memoryDao()
                    val existingMemories = dao.getByType("long_term")
                    existingHashes.addAll(existingMemories.map { it.content.hashCode() })

                    for (data in importedData) {
                        val content = data["content"] as? String ?: continue
                        val hash = content.hashCode()
                        if (hash in existingHashes) continue
                        existingHashes.add(hash)

                        memoryManager.importMemory(
                            content = content,
                            source = data["source"] as? String ?: "",
                            importanceScore = (data["importanceScore"] as? Double)?.toFloat() ?: 3f,
                            tags = data["tags"] as? String ?: ""
                        )
                        importedCount++
                    }
                }
                loadMemories()
                _exportStatus.value = "导入成功：${importedCount} 条记忆"
            } catch (e: Exception) {
                Log.e("MemoryViewModel", "Import failed", e)
                _exportStatus.value = "导入失败：${e.message}"
            }
        }
    }

    fun dismissStatus() {
        _exportStatus.value = null
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return MemoryViewModel(application) as T
        }
    }
}
