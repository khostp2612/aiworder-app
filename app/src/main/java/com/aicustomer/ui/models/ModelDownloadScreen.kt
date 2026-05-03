package com.aicustomer.ui.models

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicustomer.engine.ModelManager
import com.aicustomer.ui.viewmodel.ModelsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDownloadScreen(
    onBack: () -> Unit,
    viewModel: ModelsViewModel = viewModel(factory = ModelsViewModel.Factory(LocalContext.current.applicationContext as android.app.Application))
) {
    val modelStatuses by viewModel.modelStatuses.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val downloadingModel by viewModel.downloadingModel.collectAsState()
    val downloadError by viewModel.downloadError.collectAsState()

    val openAiKey by viewModel.openAiKey.collectAsState()
    val openAiEndpoint by viewModel.openAiEndpoint.collectAsState()
    val openAiModel by viewModel.openAiModel.collectAsState()
    val cfUrl by viewModel.cfUrl.collectAsState()
    val saveStatus by viewModel.saveStatus.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    var showOpenAiForm by remember { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }

    var editOpenAiKey by remember { mutableStateOf(openAiKey) }
    var editOpenAiEndpoint by remember { mutableStateOf(openAiEndpoint) }
    var editOpenAiModel by remember { mutableStateOf(openAiModel) }
    var editCfUrl by remember { mutableStateOf(cfUrl) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型管理") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("本地模型", style = MaterialTheme.typography.titleMedium)

            downloadError?.let { error ->
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { viewModel.clearError() }) { Text("关闭") }
                    }
                }
            }

            modelStatuses.forEach { (modelInfo, isDownloaded) ->
                ModelCard(modelInfo, isDownloaded, downloadingModel == modelInfo.name, if (downloadingModel == modelInfo.name) downloadProgress else 0f, { viewModel.downloadModel(modelInfo) }, { viewModel.deleteModel(modelInfo) })
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // === 云端大模型 API ===
            Text("云端大模型", style = MaterialTheme.typography.titleMedium)
            Text("配置后可调用云端大模型增强回答", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            saveStatus?.let {
                Surface(Modifier.fillMaxWidth(), RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.width(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { viewModel.dismissSaveStatus() }) { Text("关闭") }
                    }
                }
            }

            // OpenAI 兼容
            Card(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Cloud, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("OpenAI 兼容接口", style = MaterialTheme.typography.titleSmall)
                        }
                        Surface(shape = RoundedCornerShape(8.dp), color = if (openAiKey.isNotBlank()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer) {
                            Text(if (openAiKey.isNotBlank()) "已配置" else "未配置", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = if (openAiKey.isNotBlank()) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("用于调用云端大模型，如 GPT-4、DeepSeek 等", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    if (showOpenAiForm) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(editOpenAiEndpoint, { editOpenAiEndpoint = it }, Modifier.fillMaxWidth(), label = { Text("API 地址") }, shape = RoundedCornerShape(12.dp), singleLine = true)
                        OutlinedTextField(editOpenAiModel, { editOpenAiModel = it }, Modifier.fillMaxWidth(), label = { Text("模型名称") }, placeholder = { Text("deepseek-chat") }, shape = RoundedCornerShape(12.dp), singleLine = true)
                        OutlinedTextField(
                            editOpenAiKey, { editOpenAiKey = it },
                            Modifier.fillMaxWidth(),
                            label = { Text("API Key") },
                            shape = RoundedCornerShape(12.dp), singleLine = true,
                            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { keyVisible = !keyVisible }) {
                                    Icon(
                                        if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (keyVisible) "隐藏" else "显示"
                                    )
                                }
                            },
                            isError = viewModel.validateApiKey(editOpenAiKey) != null && editOpenAiKey.isNotEmpty()
                        )
                        val keyError = if (editOpenAiKey.isNotEmpty()) viewModel.validateApiKey(editOpenAiKey) else null
                        if (keyError != null) {
                            Text(keyError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }

                        when (val state = connectionState) {
                            is ModelsViewModel.ConnectionState.Connecting -> {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text("正在测试连接...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            is ModelsViewModel.ConnectionState.Connected -> {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text("连接成功", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    TextButton(onClick = { viewModel.dismissConnectionState() }) { Text("关闭") }
                                }
                            }
                            is ModelsViewModel.ConnectionState.Failed -> {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.Error, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    TextButton(onClick = { viewModel.dismissConnectionState() }) { Text("关闭") }
                                }
                            }
                            is ModelsViewModel.ConnectionState.Idle -> {}
                        }

                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            OutlinedButton(
                                onClick = { viewModel.testConnection(editOpenAiKey, editOpenAiEndpoint) },
                                enabled = connectionState !is ModelsViewModel.ConnectionState.Connecting && editOpenAiKey.isNotBlank(),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("测试连接") }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { showOpenAiForm = false; viewModel.dismissConnectionState() }) { Text("取消") }
                                Button(onClick = { viewModel.saveOpenAiKey(editOpenAiKey, editOpenAiEndpoint, editOpenAiModel); showOpenAiForm = false; viewModel.dismissConnectionState() }, enabled = viewModel.validateApiKey(editOpenAiKey) == null) { Text("保存") }
                            }
                        }
                    } else {
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = { showOpenAiForm = true; editOpenAiKey = openAiKey; editOpenAiEndpoint = openAiEndpoint; editOpenAiModel = openAiModel }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Default.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(if (openAiKey.isNotBlank()) "修改凭证" else "配置凭证")
                        }
                    }
                }
            }

            // === Cloudflare Tunnel ASR WebSocket ===
            Text("语音识别", style = MaterialTheme.typography.titleMedium)
            Text("配置 Cloudflare Tunnel WebSocket 地址进行语音转文字", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Card(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Mic, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("ASR WebSocket", style = MaterialTheme.typography.titleSmall)
                        }
                        Surface(shape = RoundedCornerShape(8.dp), color = if (cfUrl.isNotBlank()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer) {
                            Text(if (cfUrl.isNotBlank()) "已配置" else "未配置", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = if (cfUrl.isNotBlank()) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("OkHttp WebSocket 连接到 Cloudflare Tunnel 进行实时语音转文字", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(editCfUrl, { editCfUrl = it }, Modifier.fillMaxWidth(), label = { Text("WebSocket 地址") }, placeholder = { Text("wss://xxx.trycloudflare.com/v1/transcribe") }, shape = RoundedCornerShape(12.dp), singleLine = true)
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = { viewModel.saveCfUrl(editCfUrl) }) { Text("保存") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    modelInfo: ModelManager.ModelInfo, isDownloaded: Boolean, isDownloading: Boolean,
    progress: Float, onDownload: () -> Unit, onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = if (isDownloaded) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(modelInfo.name, style = MaterialTheme.typography.titleSmall)
                        if (modelInfo.isBundled) Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.tertiaryContainer) { Text("内置", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer) }
                    }
                    Text(modelInfo.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("大小：${"%.0f".format(modelInfo.sizeBytes / 1_000_000.0)}MB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            if (isDownloading) {
                LinearProgressIndicator(progress, Modifier.fillMaxWidth().height(4.dp))
                Text("下载中... ${"%.0f".format(progress * 100)}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (isDownloaded && !modelInfo.isBundled)
                    OutlinedButton(onClick = onDelete, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") }
                else if (!isDownloaded && !isDownloading && !modelInfo.isBundled)
                    Button(onClick = onDownload) { Text("下载") }
            }
        }
    }
}
