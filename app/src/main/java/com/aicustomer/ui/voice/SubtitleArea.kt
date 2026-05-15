package com.aicustomer.ui.voice

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicustomer.ui.viewmodel.VoiceCallState

/**
 * 豆包风格字幕显示区域
 *
 * 包含：用户实时语音识别文字 + AI流式回复（毛玻璃卡片 + 底部渐隐）
 */
@Composable
fun SubtitleArea(
    callState: VoiceCallState,
    userText: String,
    aiText: String,
    statusHint: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 状态标签 — 始终可见，用于显示提示/错误信息
        AnimatedVisibility(
            visible = statusHint.isNotBlank(),
            enter = fadeIn(tween(200)) + expandVertically(tween(200)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(150))
        ) {
            Box(
                modifier = Modifier
                    .background(
                        color = when (callState) {
                            VoiceCallState.LISTENING -> Color(0xFF1565C0).copy(alpha = 0.15f)
                            VoiceCallState.THINKING -> Color(0xFF7B1FA2).copy(alpha = 0.15f)
                            VoiceCallState.SPEAKING -> Color(0xFF2E7D32).copy(alpha = 0.15f)
                            else -> Color.Transparent
                        },
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 18.dp, vertical = 6.dp)
            ) {
                Text(
                    statusHint,
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 用户实时语音识别文字
        AnimatedVisibility(
            visible = callState == VoiceCallState.LISTENING && userText.isNotBlank(),
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(150))
        ) {
            Text(
                userText,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Light,
                    letterSpacing = 1.sp
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }

        // AI 流式回复 — 毛玻璃卡片 + 底部渐隐
        AnimatedVisibility(
            visible = aiText.isNotBlank(),
            enter = fadeIn(tween(350)) + expandVertically(tween(350)),
            exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .heightIn(max = 180.dp)
                    .background(
                        color = Color.White.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .drawWithCache {
                        // 底部渐隐遮罩
                        val fadeBrush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.15f)),
                            startY = size.height * 0.6f,
                            endY = size.height
                        )
                        onDrawWithContent {
                            drawContent()
                            drawRect(brush = fadeBrush)
                        }
                    }
            ) {
                val scrollState = rememberScrollState()
                LaunchedEffect(aiText) {
                    scrollState.animateScrollTo(scrollState.maxValue)
                }
                Text(
                    aiText,
                    modifier = Modifier
                        .padding(14.dp)
                        .verticalScroll(scrollState),
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 24.sp
                )
            }
        }

        // 思考中指示器
        AnimatedVisibility(
            visible = callState == VoiceCallState.THINKING && aiText.isBlank(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(vertical = 12.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color.White.copy(alpha = 0.45f)
                )
                Text(
                    "思考中...",
                    color = Color.White.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
