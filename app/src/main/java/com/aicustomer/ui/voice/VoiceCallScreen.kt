package com.aicustomer.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicustomer.ui.viewmodel.VoiceCallState
import com.aicustomer.ui.viewmodel.VoiceCallViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCallScreen(
    onBack: () -> Unit,
    viewModel: VoiceCallViewModel = viewModel(
        factory = VoiceCallViewModel.Factory(
            LocalContext.current.applicationContext as android.app.Application
        )
    )
) {
    val callState by viewModel.callState.collectAsState()
    val callElapsed by viewModel.callElapsed.collectAsState()
    val userSpeechText by viewModel.userSpeechText.collectAsState()
    val aiResponseText by viewModel.aiResponseText.collectAsState()
    val statusHint by viewModel.statusHint.collectAsState()
    val amplitude by viewModel.amplitude.collectAsState()
    val isCallActive = callState != VoiceCallState.IDLE
    val context = LocalContext.current

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.startCall() }

    Box(modifier = Modifier.fillMaxSize()) {
        // === 氛围背景 ===
        AmbientBackground(
            callState = callState,
            amplitude = amplitude,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // === TopBar: 极简 ===
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 关闭按钮 — 半透明圆
                IconButton(
                    onClick = { viewModel.endCall(); onBack() },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "返回",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // 通话时长 — 小字
                if (isCallActive) {
                    Text(
                        formatDuration(callElapsed),
                        color = Color.White.copy(alpha = 0.35f),
                        style = MaterialTheme.typography.labelSmall.copy(
                            letterSpacing = 1.5.sp
                        )
                    )
                } else {
                    Spacer(modifier = Modifier.size(36.dp))
                }
            }

            // === 中央区: 光球 (占 50%) ===
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.5f)
            ) {
                if (callState == VoiceCallState.IDLE) {
                    // IDLE: 静态光球 + 点击开始
                    VoiceOrb(
                        callState = VoiceCallState.IDLE,
                        amplitude = 0f,
                        onTap = {
                            if (ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                            ) viewModel.startCall()
                            else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        modifier = Modifier.size(240.dp)
                    )
                } else {
                    // 活跃: 动态光球 + 点击打断
                    VoiceOrb(
                        callState = callState,
                        amplitude = amplitude,
                        onTap = {
                            if (callState == VoiceCallState.SPEAKING || callState == VoiceCallState.THINKING) {
                                viewModel.onOrbTapInterrupt()
                            }
                        },
                        modifier = Modifier.size(240.dp)
                    )
                }
            }

            // === 字幕区 (占 30%) ===
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.3f),
                contentAlignment = Alignment.TopCenter
            ) {
                SubtitleArea(
                    callState = callState,
                    userText = userSpeechText,
                    aiText = aiResponseText,
                    statusHint = statusHint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }

            // === 底部控制栏 (占 20%) ===
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.2f)
                    .padding(bottom = 12.dp)
            ) {
                if (isCallActive) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 文字输入切换
                        var showInput by remember { mutableStateOf(false) }
                        var input by remember { mutableStateOf(TextFieldValue("")) }

                        AnimatedContent(
                            targetState = showInput,
                            transitionSpec = {
                                fadeIn(tween(200)) + expandVertically(tween(250)) togetherWith
                                    fadeOut(tween(150)) + shrinkVertically(tween(200))
                            },
                            label = "inputToggle"
                        ) { showing ->
                            if (!showing) {
                                TextButton(
                                    onClick = { showInput = true }
                                ) {
                                    Icon(
                                        Icons.Default.Keyboard,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = Color.White.copy(alpha = 0.25f)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "文字输入",
                                        color = Color.White.copy(alpha = 0.25f),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedTextField(
                                        value = input,
                                        onValueChange = { input = it },
                                        modifier = Modifier.weight(1f),
                                        placeholder = {
                                            Text(
                                                "输入文字...",
                                                color = Color.White.copy(alpha = 0.3f)
                                            )
                                        },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White.copy(alpha = 0.8f),
                                            focusedBorderColor = Color(0xFF42A5F5).copy(alpha = 0.4f),
                                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                            focusedContainerColor = Color.White.copy(alpha = 0.04f),
                                            unfocusedContainerColor = Color.White.copy(alpha = 0.02f)
                                        ),
                                        shape = RoundedCornerShape(24.dp),
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium
                                    )
                                    FilledIconButton(
                                        onClick = {
                                            val t = input.text.trim()
                                            if (t.isNotBlank() && callState == VoiceCallState.LISTENING) {
                                                viewModel.sendTextMessage(t)
                                                input = TextFieldValue("")
                                                showInput = false
                                            }
                                        },
                                        modifier = Modifier.size(42.dp),
                                        enabled = input.text.isNotBlank(),
                                        shape = CircleShape
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Send,
                                            contentDescription = "发送",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // 底部控制栏 Pill
                        BottomControlBar(
                            isCallActive = isCallActive,
                            onEndCall = { viewModel.endCall(); onBack() }
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(s: Long): String {
    val m = s / 60
    val sec = s % 60
    return "%02d:%02d".format(m, sec)
}
