package com.aicustomer.ui.voice

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 豆包风格毛玻璃圆形按钮
 *
 * 半透明玻璃背景 + 白色图标，按下时缩放 + 高亮
 */
@Composable
fun GlassButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    tint: Color = Color.White,
    glassAlpha: Float = 0.12f,
    enabled: Boolean = true,
    isActive: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.08f else 1f,
        animationSpec = tween(200),
        label = "glassScale"
    )
    val bgAlpha by animateFloatAsState(
        targetValue = if (isActive) glassAlpha + 0.1f else glassAlpha,
        animationSpec = tween(300),
        label = "glassAlpha"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = rememberRipple(
                    bounded = true,
                    radius = size / 2,
                    color = Color.White.copy(alpha = 0.25f)
                ),
                enabled = enabled,
                onClick = onClick
            )
    ) {
        // 毛玻璃背景由外部容器提供 (BottomControlBar 或直接背景色)
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.3f),
            modifier = Modifier.size((size.value * 0.52f).dp)
        )
    }
}
