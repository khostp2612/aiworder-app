package com.aicustomer.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicustomer.ui.viewmodel.VoiceCallState
import com.aicustomer.ui.viewmodel.VoiceCallViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCallScreen(
    onBack: () -> Unit,
    viewModel: VoiceCallViewModel = viewModel(factory = VoiceCallViewModel.Factory(LocalContext.current.applicationContext as android.app.Application))
) {
    val callState by viewModel.callState.collectAsState()
    val callElapsed by viewModel.callElapsed.collectAsState()
    val userSpeechText by viewModel.userSpeechText.collectAsState()
    val aiResponseText by viewModel.aiResponseText.collectAsState()
    val statusHint by viewModel.statusHint.collectAsState()
    val isCallActive = callState != VoiceCallState.IDLE
    val context = LocalContext.current

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startCall()
    }

    Column(
        modifier = Modifier.fillMaxSize().background(
            Brush.radialGradient(colors = when (callState) {
                VoiceCallState.LISTENING -> listOf(Color(0xFF0D253F), Color(0xFF141218))
                VoiceCallState.THINKING -> listOf(Color(0xFF1F0D3F), Color(0xFF141218))
                VoiceCallState.SPEAKING -> listOf(Color(0xFF0D3F1F), Color(0xFF141218))
                VoiceCallState.IDLE -> listOf(Color(0xFF1C1B1F), Color(0xFF141218))
            })
        )
    ) {
        // Top bar
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { viewModel.endCall(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White) }
            Text("语音通话", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Color.White)
            Spacer(modifier = Modifier.size(48.dp))
        }

        if (isCallActive) {
            Text(formatDuration(callElapsed), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Light), color = Color.White.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Status badge
        Surface(shape = RoundedCornerShape(20.dp), color = when (callState) {
            VoiceCallState.LISTENING -> Color(0xFF1565C0).copy(alpha = 0.25f)
            VoiceCallState.THINKING -> Color(0xFF7B1FA2).copy(alpha = 0.25f)
            VoiceCallState.SPEAKING -> Color(0xFF2E7D32).copy(alpha = 0.25f)
            else -> Color.Transparent
        }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(statusHint, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = Color.White, style = MaterialTheme.typography.bodyLarge)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Center: main animated button
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().weight(0.4f)) {
            if (callState == VoiceCallState.IDLE) {
                // Start button
                FilledIconButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                            viewModel.startCall()
                        else
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    modifier = Modifier.size(100.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF4CAF50)),
                    shape = CircleShape
                ) { Icon(Icons.Default.Call, "开始", modifier = Modifier.size(48.dp), tint = Color.White) }
            } else if (callState == VoiceCallState.LISTENING) {
                // Listening: pulsing mic with ring
                PulsingMicButton()
            } else {
                AnimatedStatusButton(callState)
            }
        }

        // Recognition / Response text area
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).weight(0.6f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (callState == VoiceCallState.LISTENING && userSpeechText.isNotBlank()) {
                Text(userSpeechText, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Light), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            if (aiResponseText.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
                    Text(aiResponseText.takeLast(250), modifier = Modifier.padding(14.dp), color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp)
                }
            }
        }

        // Bottom: text input (accessibility fallback) + end call
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
            if (isCallActive) {
                var showInput by remember { mutableStateOf(false) }
                if (!showInput) {
                    TextButton(onClick = { showInput = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Icon(Icons.Default.Keyboard, null, modifier = Modifier.size(14.dp), tint = Color.White.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("文字输入", color = Color.White.copy(alpha = 0.3f), style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    var input by remember { mutableStateOf(TextFieldValue("")) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                            placeholder = { Text("输入文字...", color = Color.White.copy(alpha = 0.4f)) },
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFF42A5F5), unfocusedBorderColor = Color.White.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(24.dp), singleLine = true)
                        FilledIconButton(onClick = {
                            val t = input.text.trim()
                            if (t.isNotBlank() && callState == VoiceCallState.LISTENING) { viewModel.sendTextMessage(t); input = TextFieldValue("") }
                        }, modifier = Modifier.size(44.dp), enabled = input.text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "发", Modifier.size(18.dp)) }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                FilledIconButton(
                    onClick = { viewModel.endCall(); onBack() },
                    modifier = Modifier.size(56.dp).align(Alignment.CenterHorizontally),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFF44336)),
                    shape = CircleShape
                ) { Icon(Icons.Default.CallEnd, "挂断", modifier = Modifier.size(28.dp), tint = Color.White) }
            }
        }
    }
}

@Composable
private fun PulsingMicButton() {
    val infiniteTransition = rememberInfiniteTransition(label = "mic")
    val coreScale by infiniteTransition.animateFloat(1f, 1.08f, infiniteRepeatable(tween(800, easing = EaseInOutCubic), RepeatMode.Reverse), label = "core")
    val ring1Scale by infiniteTransition.animateFloat(1f, 1.4f, infiniteRepeatable(tween(1200, easing = EaseOutCubic), RepeatMode.Restart), label = "r1")
    val ring1Alpha by infiniteTransition.animateFloat(0.35f, 0f, infiniteRepeatable(tween(1200, easing = EaseOutCubic), RepeatMode.Restart), label = "r1a")
    val ring2Scale by infiniteTransition.animateFloat(1f, 1.7f, infiniteRepeatable(tween(1200, 400, easing = EaseOutCubic), RepeatMode.Restart), label = "r2")
    val ring2Alpha by infiniteTransition.animateFloat(0.2f, 0f, infiniteRepeatable(tween(1200, 400, easing = EaseOutCubic), RepeatMode.Restart), label = "r2a")

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(140.dp)) {
        // Rings
        Box(Modifier.size(100.dp).scale(ring1Scale).background(Color(0xFF42A5F5).copy(alpha = ring1Alpha), CircleShape))
        Box(Modifier.size(100.dp).scale(ring2Scale).background(Color(0xFF42A5F5).copy(alpha = ring2Alpha), CircleShape))
        // Core button
        FilledIconButton(onClick = {}, modifier = Modifier.size(90.dp).scale(coreScale), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF1565C0)), shape = CircleShape) {
            Icon(Icons.Default.Mic, null, modifier = Modifier.size(40.dp), tint = Color.White)
        }
    }
}

@Composable
private fun AnimatedStatusButton(callState: VoiceCallState) {
    val pulseScale by rememberInfiniteTransition(label = "s").animateFloat(1f, 1.12f, infiniteRepeatable(tween(500, easing = EaseInOutCubic), RepeatMode.Reverse), label = "p")
    FilledIconButton(onClick = {}, modifier = Modifier.size(80.dp).scale(pulseScale),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = when (callState) {
            VoiceCallState.THINKING -> Color(0xFF7B1FA2); VoiceCallState.SPEAKING -> Color(0xFF2E7D32); else -> Color.Gray
        }), shape = CircleShape
    ) {
        Icon(when (callState) {
            VoiceCallState.THINKING -> Icons.Default.Psychology; VoiceCallState.SPEAKING -> Icons.Default.VolumeUp; else -> Icons.Default.Mic
        }, null, modifier = Modifier.size(36.dp), tint = Color.White)
    }
}

private fun formatDuration(s: Long): String { val m = s / 60; val sec = s % 60; return "%02d:%02d".format(m, sec) }
