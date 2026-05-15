package com.aicustomer.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 豆包风格底部控制栏
 *
 * 毛玻璃 pill 容器内排列 [静音] [扬声器] [键盘] [挂断]
 */
@Composable
fun BottomControlBar(
    isCallActive: Boolean,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isCallActive) return

    var isMuted by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 静音
        IconButton(
            onClick = { isMuted = !isMuted },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = if (isMuted) "取消静音" else "静音",
                tint = if (isMuted) Color(0xFFEF9A9A) else Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(22.dp)
            )
        }

        // 扬声器
        IconButton(
            onClick = { },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.VolumeUp,
                contentDescription = "扬声器",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(22.dp)
            )
        }

        // 键盘切换
        IconButton(
            onClick = { },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Keyboard,
                contentDescription = "文字输入",
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }

        // 挂断 — 红色实体按钮
        IconButton(
            onClick = onEndCall,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = Color(0xFFE53935)
            )
        ) {
            Icon(
                imageVector = Icons.Default.CallEnd,
                contentDescription = "挂断",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
