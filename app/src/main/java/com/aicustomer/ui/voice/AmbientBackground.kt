package com.aicustomer.ui.voice

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.aicustomer.ui.viewmodel.VoiceCallState
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 豆包风格氛围背景 — 流体渐变 + 散景光斑 + 浮游粒子
 *
 * 3个独立漂移的椭圆形渐变团块，创造有机的流体感
 * Bokeh 散景光斑散布于屏幕边缘
 * 60+ 浮游光点自下而上缓慢升起（彗星粒子雨）
 * 所有元素随通话状态平滑迁移色温
 */
@Composable
fun AmbientBackground(
    callState: VoiceCallState,
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    // === 状态色板 ===
    val bgBase = when (callState) {
        VoiceCallState.IDLE -> BackgroundPalette(
            blobColors = listOf(Color(0xFF1A2A4E), Color(0xFF162A50), Color(0xFF0F3460)),
            bokehColor = Color(0xFF4A90D9),
            particleColor = Color(0xFF90CAF9)
        )
        VoiceCallState.LISTENING -> BackgroundPalette(
            blobColors = listOf(Color(0xFF1A1048), Color(0xFF2D1B69), Color(0xFF1A0C3C)),
            bokehColor = Color(0xFF7B68EE),
            particleColor = Color(0xFFB39DDB)
        )
        VoiceCallState.THINKING -> BackgroundPalette(
            blobColors = listOf(Color(0xFF2D1B4E), Color(0xFF4A2875), Color(0xFF5C3283)),
            bokehColor = Color(0xFFBB86FC),
            particleColor = Color(0xFFCE93D8)
        )
        VoiceCallState.SPEAKING -> BackgroundPalette(
            blobColors = listOf(Color(0xFF3D1F2D), Color(0xFF5C2D4A), Color(0xFF2D1B3D)),
            bokehColor = Color(0xFFFF8A80),
            particleColor = Color(0xFFFFAB91)
        )
    }

    // 平滑过渡色板
    val animBlob0 by animateColorAsState(bgBase.blobColors[0], tween(1200), label = "blob0")
    val animBlob1 by animateColorAsState(bgBase.blobColors[1], tween(1200), label = "blob1")
    val animBlob2 by animateColorAsState(bgBase.blobColors[2], tween(1200), label = "blob2")

    // === 渐变团块漂移 ===
    val infiniteTransition = rememberInfiniteTransition(label = "bgInfinite")

    val blob1OffsetX by infiniteTransition.animateFloat(
        initialValue = -0.15f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(12000, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "blob1x"
    )
    val blob1OffsetY by infiniteTransition.animateFloat(
        initialValue = -0.05f, targetValue = 0.15f,
        animationSpec = infiniteRepeatable(tween(9000, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "blob1y"
    )
    val blob2OffsetX by infiniteTransition.animateFloat(
        initialValue = 0.1f, targetValue = -0.2f,
        animationSpec = infiniteRepeatable(tween(14000, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "blob2x"
    )
    val blob2OffsetY by infiniteTransition.animateFloat(
        initialValue = 0.1f, targetValue = -0.12f,
        animationSpec = infiniteRepeatable(tween(10000, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "blob2y"
    )
    val blob3OffsetX by infiniteTransition.animateFloat(
        initialValue = 0.05f, targetValue = -0.15f,
        animationSpec = infiniteRepeatable(tween(16000, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "blob3x"
    )
    val blob3OffsetY by infiniteTransition.animateFloat(
        initialValue = -0.1f, targetValue = 0.1f,
        animationSpec = infiniteRepeatable(tween(11000, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "blob3y"
    )

    // === 粒子系统 ===
    val particles = remember { List(70) { BgParticle() } }
    LaunchedEffect(Unit) {
        while (true) {
            particles.forEach { it.update(0.06f) }
            kotlinx.coroutines.delay(60)
        }
    }

    val sprungAmp by animateFloatAsState(
        targetValue = amplitude,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "bgAmp"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // 1. 基底暗色
        drawRect(Color(0xFF0A0A14))

        // 2. 3个流体渐变团块
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(animBlob0.copy(alpha = 0.55f), animBlob0.copy(alpha = 0.1f), Color.Transparent),
                center = Offset(w * (0.3f + blob1OffsetX), h * (0.25f + blob1OffsetY)),
                radius = w * 0.65f
            ),
            topLeft = Offset(w * (-0.15f + blob1OffsetX), h * (-0.2f + blob1OffsetY)),
            size = androidx.compose.ui.geometry.Size(w * 0.95f, h * 0.85f)
        )
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(animBlob1.copy(alpha = 0.4f), animBlob1.copy(alpha = 0.08f), Color.Transparent),
                center = Offset(w * (0.65f + blob2OffsetX), h * (0.5f + blob2OffsetY)),
                radius = w * 0.5f
            ),
            topLeft = Offset(w * (0.25f + blob2OffsetX), h * (0.15f + blob2OffsetY)),
            size = androidx.compose.ui.geometry.Size(w * 0.75f, h * 0.7f)
        )
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(animBlob2.copy(alpha = 0.35f), animBlob2.copy(alpha = 0.05f), Color.Transparent),
                center = Offset(w * (0.4f + blob3OffsetX), h * (0.7f + blob3OffsetY)),
                radius = w * 0.45f
            ),
            topLeft = Offset(w * (0.05f + blob3OffsetX), h * (0.4f + blob3OffsetY)),
            size = androidx.compose.ui.geometry.Size(w * 0.7f, h * 0.6f)
        )

        // 3. 散景光斑 (bokeh)
        val bokehAlpha = 0.03f + sprungAmp * 0.05f
        val bokehPositions = listOf(
            0.15f to 0.18f, 0.78f to 0.12f, 0.08f to 0.75f,
            0.88f to 0.8f, 0.5f to 0.88f, 0.22f to 0.55f
        )
        bokehPositions.forEach { (px, py) ->
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(bgBase.bokehColor.copy(alpha = bokehAlpha), Color.Transparent),
                    center = Offset(w * px, h * py),
                    radius = w * 0.12f
                ),
                radius = w * 0.12f,
                center = Offset(w * px, h * py)
            )
        }

        // 4. 浮游粒子
        particles.forEach { p ->
            val x = p.x * w
            val y = p.y * h
            val r = p.radius * density
            val alpha = p.alpha * when (callState) {
                VoiceCallState.IDLE -> 0.12f
                VoiceCallState.LISTENING -> 0.18f + sprungAmp * 0.12f
                VoiceCallState.THINKING -> 0.14f
                VoiceCallState.SPEAKING -> 0.16f + sprungAmp * 0.08f
            }
            drawCircle(
                color = bgBase.particleColor.copy(alpha = alpha.coerceIn(0f, 0.5f)),
                radius = r,
                center = Offset(x, y),
                style = Stroke(width = 1.2f * density)
            )
            // 彗星拖尾
            if (p.alpha > 0.3f) {
                drawCircle(
                    color = bgBase.particleColor.copy(alpha = alpha * 0.25f),
                    radius = r * 1.8f,
                    center = Offset(x, y - r * 2f)
                )
            }
        }
    }
}

private data class BackgroundPalette(
    val blobColors: List<Color>,
    val bokehColor: Color,
    val particleColor: Color
)

private class BgParticle {
    var x: Float = Random.nextFloat()
    var y: Float = Random.nextFloat()
    var radius: Float = 1.2f + Random.nextFloat() * 2.5f
    var alpha: Float = 0.1f + Random.nextFloat() * 0.35f
    private val speedY: Float = -(0.003f + Random.nextFloat() * 0.012f) // 上升
    private val speedX: Float = (Random.nextFloat() - 0.5f) * 0.004f
    private val phase: Float = Random.nextFloat() * 6.28f
    private var life: Float = Random.nextFloat()

    fun update(dt: Float) {
        life -= dt * 0.06f
        if (life <= 0f) {
            // 重生在底部
            x = Random.nextFloat()
            y = 1.05f
            alpha = 0.1f + Random.nextFloat() * 0.35f
            life = 0.8f + Random.nextFloat() * 0.2f
        }
        y += speedY + sin(System.currentTimeMillis() / 6000.0 + phase).toFloat() * 0.0008f
        x += speedX + sin(System.currentTimeMillis() / 8000.0 + phase * 0.7f).toFloat() * 0.0006f
        if (y < -0.05f) y = 1.05f
        if (x < -0.05f) x = 1.05f
        if (x > 1.05f) x = -0.05f
        alpha = (life * 0.5f).coerceIn(0.02f, 0.5f)
    }
}
