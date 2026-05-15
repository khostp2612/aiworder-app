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
            val tier = com.aicustomer.engine.DeviceTier.detect(LocalContext.current)
            Text("设备: ${tier.name} (${tier.ramGB}GB${if (tier.hasVulkan) ", Vulkan" else ""}) | 推荐: ${tier.recommendedQuant}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(4.dp))

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
