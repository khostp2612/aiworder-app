package com.aicustomer.ui.memory

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicustomer.data.model.Memory
import com.aicustomer.ui.viewmodel.MemoryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryBrowserScreen(
    onBack: () -> Unit,
    viewModel: MemoryViewModel = viewModel(factory = MemoryViewModel.Factory(LocalContext.current.applicationContext as android.app.Application))
) {
    var searchQuery by remember { mutableStateOf(TextFieldValue("")) }
    var selectedTab by remember { mutableIntStateOf(0) }

    val longTermMemories by viewModel.longTermMemories.collectAsState()
    val shortTermMemories by viewModel.shortTermMemories.collectAsState()
    val exportStatus by viewModel.exportStatus.collectAsState()

    // 导出文档选择器
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportMemoriesToJson(it) }
    }

    // 导入文档选择器
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importMemoriesFromJson(it) }
    }

    // 导出状态提示
    exportStatus?.let { status ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissStatus() },
            confirmButton = { TextButton(onClick = { viewModel.dismissStatus() }) { Text("确定") } },
            text = { Text(status) }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记忆管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                        Icon(Icons.Default.FileUpload, contentDescription = "导入记忆")
                    }
                    IconButton(onClick = {
                        val filename = "ai_customer_memories_${System.currentTimeMillis()}.json"
                        exportLauncher.launch(filename)
                    }) {
                        Icon(Icons.Default.FileDownload, contentDescription = "导出记忆")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 记忆概览
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MemoryStatCard("长期记忆", longTermMemories.size, MaterialTheme.colorScheme.primaryContainer)
                MemoryStatCard("短期记忆", shortTermMemories.size, MaterialTheme.colorScheme.secondaryContainer)
            }

            // 搜索栏
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("搜索记忆") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = true
            )

            // Tab切换
            TabRow(selectedTabIndex = selectedTab, containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("长期记忆 (${longTermMemories.size})") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("短期记忆 (${shortTermMemories.size})") })
            }

            // 记忆列表
            val currentMemories = if (selectedTab == 0) longTermMemories else shortTermMemories
            val filteredMemories = if (searchQuery.text.isBlank()) {
                currentMemories
            } else {
                currentMemories.filter { it.content.contains(searchQuery.text, ignoreCase = true) }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filteredMemories, key = { it.id }) { memory ->
                    MemoryItem(
                        memory = memory,
                        onDelete = { viewModel.deleteMemory(memory) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.MemoryStatCard(label: String, count: Int, containerColor: androidx.compose.ui.graphics.Color) {
    Card(
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(count.toString(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MemoryItem(memory: Memory, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(memory.content, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (memory.tags.isNotBlank()) {
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(memory.tags, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Text("重要性：${"%.1f".format(memory.importanceScore)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
            }
        }
    }
}
