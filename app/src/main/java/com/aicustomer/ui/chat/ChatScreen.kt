package com.aicustomer.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicustomer.data.model.Message
import com.aicustomer.ui.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateToIdentity: () -> Unit,
    onNavigateToMemory: () -> Unit,
    onNavigateToModels: () -> Unit = {},
    onNavigateToVoice: () -> Unit = {},
    viewModel: ChatViewModel = viewModel(factory = ChatViewModel.Factory(LocalContext.current.applicationContext as android.app.Application))
) {
    val messages by viewModel.messages.collectAsState()
    val isVoiceMode by viewModel.isVoiceMode.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val aiStatus by viewModel.aiStatus.collectAsState()
    val isModelLoaded by viewModel.isModelLoaded.collectAsState()
    val modelLoadError by viewModel.modelLoadError.collectAsState()
    val activeIdentityName by viewModel.activeIdentityName.collectAsState()
    val useCloudLlm by viewModel.useCloudLlm.collectAsState()
    val cloudAvailable by viewModel.cloudAvailable.collectAsState()

    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // 刷新身份名称（每次重组都调用，轻量操作）
    viewModel.refreshIdentityName()

    // 麦克风权限状态
    var showMicPermissionDialog by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.toggleVoiceMode()
        }
    }

    fun requestMicPermission() {
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> {
                viewModel.toggleVoiceMode()
            }
            else -> {
                showMicPermissionDialog = true
            }
        }
    }

    // 麦克风权限说明对话框
    if (showMicPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showMicPermissionDialog = false },
            icon = { Icon(Icons.Default.Mic, contentDescription = null) },
            title = { Text("需要麦克风权限") },
            text = {
                Text("语音对话功能需要访问麦克风以进行语音识别。\n\n所有音频数据仅在您的设备本地处理，不会上传到任何服务器。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showMicPermissionDialog = false
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) {
                    Text("授权")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMicPermissionDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 模型未加载时显示提示横幅（不阻塞界面）

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = when (aiStatus) {
                                "就绪" -> MaterialTheme.colorScheme.primaryContainer
                                "思考中" -> MaterialTheme.colorScheme.tertiaryContainer
                                "回复中" -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ) {
                            Text(
                                aiStatus,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                color = when (aiStatus) {
                                    "就绪" -> MaterialTheme.colorScheme.onPrimaryContainer
                                    "思考中" -> MaterialTheme.colorScheme.onTertiaryContainer
                                    "回复中" -> MaterialTheme.colorScheme.onSecondaryContainer
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        if (cloudAvailable) {
                            TextButton(
                                onClick = { viewModel.toggleCloudLlm() },
                                modifier = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 28.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Icon(Icons.Default.Cloud, null, Modifier.size(12.dp), tint = if (useCloudLlm) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(2.dp))
                                Text(if (useCloudLlm) "云端" else "本地", style = MaterialTheme.typography.labelSmall, color = if (useCloudLlm) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToVoice) {
                        Icon(Icons.Default.Call, contentDescription = "语音通话", tint = MaterialTheme.colorScheme.primary)
                    }
                    // +号菜单：新对话 / 导入文档
                    var showAddMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showAddMenu = true }) {
                            Icon(Icons.Default.Add, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("新对话") },
                                onClick = { viewModel.startNewConversation(); showAddMenu = false },
                                leadingIcon = { Icon(Icons.Default.Chat, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("导入文档") },
                                onClick = { viewModel.showImportDialog = true; showAddMenu = false },
                                leadingIcon = { Icon(Icons.Default.UploadFile, null) }
                            )
                        }
                    }
                    IconButton(onClick = onNavigateToIdentity) {
                        Icon(Icons.Default.Person, contentDescription = "身份设置")
                    }
                    IconButton(onClick = onNavigateToMemory) {
                        Icon(Icons.Default.Psychology, contentDescription = "记忆管理")
                    }
                    IconButton(onClick = onNavigateToModels) {
                        Icon(Icons.Default.Storage, contentDescription = "模型管理")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            BottomBar(
                isVoiceMode = isVoiceMode,
                isListening = isListening,
                isGenerating = isGenerating,
                inputText = inputText,
                onInputTextChange = { inputText = it },
                onVoiceModeToggle = { requestMicPermission() },
                onSwitchToText = { viewModel.toggleVoiceMode() },
                onVoiceToggle = {
                    if (isListening) viewModel.stopListening()
                    else viewModel.startListening()
                },
                onSend = {
                    if (inputText.text.isNotBlank()) {
                        viewModel.sendMessage(inputText.text)
                        inputText = TextFieldValue("")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 身份名称
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                Text(
                    activeIdentityName.ifBlank { "AI客服" },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // 模型加载失败时才显示错误横幅
            if (!isModelLoaded && modelLoadError != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(modelLoadError ?: "模型未就绪", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { viewModel.loadModel() }) { Text("重试加载") }
                    }
                }
            }

            // 消息列表
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(messages, key = { System.identityHashCode(it) }) { message ->
                    MessageBubble(message = message)
                }

                if (isGenerating) {
                    item {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                "正在思考...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = { viewModel.abortGeneration() }) {
                                Text("停止", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }  // Column
    }  // Scaffold content

    val lastMessageContent = messages.lastOrNull()?.content ?: ""
    LaunchedEffect(messages.size, lastMessageContent) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    if (viewModel.showImportDialog) {
        var docTitle by remember { mutableStateOf("") }
        var docContent by remember { mutableStateOf("") }
        val context = LocalContext.current
        val filePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let {
                try {
                    val inputStream = context.contentResolver.openInputStream(it)
                    val text = inputStream?.bufferedReader()?.readText() ?: ""
                    inputStream?.close()
                    docContent = text
                    // 用文件名做标题
                    val name = uri.lastPathSegment ?: "导入文档"
                    if (docTitle.isBlank()) docTitle = name.removeSuffix(".txt").removeSuffix(".TXT")
                } catch (_: Exception) {}
            }
        }
        AlertDialog(
            onDismissRequest = { viewModel.showImportDialog = false },
            title = { Text("导入知识文档") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = docTitle, onValueChange = { docTitle = it },
                        label = { Text("文档标题") }, singleLine = true,
                        placeholder = { Text("例如：客服工作流程") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = docContent, onValueChange = { docContent = it },
                        label = { Text("文档内容") }, minLines = 5, maxLines = 10,
                        placeholder = { Text("粘贴或选择TXT文件...") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = { filePicker.launch("text/plain") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("选择 TXT 文件") }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.importDocument(docTitle, docContent) },
                    enabled = docTitle.isNotBlank() && docContent.isNotBlank()
                ) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showImportDialog = false }) { Text("取消") }
            }
        )
    }
    if (viewModel.importDone) {
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(2000); viewModel.importDone = false }
        Snackbar(modifier = Modifier.padding(16.dp)) { Text("文档已导入") }
    }
}  // ChatScreen

@Composable
private fun BottomBar(
    isVoiceMode: Boolean,
    isListening: Boolean,
    isGenerating: Boolean,
    inputText: TextFieldValue,
    onInputTextChange: (TextFieldValue) -> Unit,
    onVoiceModeToggle: () -> Unit,
    onSwitchToText: () -> Unit,
    onVoiceToggle: () -> Unit,
    onSend: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        AnimatedContent(
            targetState = isVoiceMode,
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            }
        ) { voiceMode ->
            if (voiceMode) {
                // 语音模式
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    FilledIconButton(
                        onClick = onVoiceToggle,
                        modifier = Modifier.size(80.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isListening)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (isListening) "停止录音" else "开始录音",
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (isListening) "正在聆听..." else "点击开始说话",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    TextButton(onClick = onSwitchToText) {
                        Icon(Icons.Default.Keyboard, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("切换文字输入")
                    }
                }
            } else {
                // 文字输入模式
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onVoiceModeToggle) {
                        Icon(Icons.Default.Mic, contentDescription = "语音模式", tint = MaterialTheme.colorScheme.primary)
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = onInputTextChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入消息...") },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    FilledIconButton(
                        onClick = onSend,
                        enabled = inputText.text.isNotBlank() && !isGenerating,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
