package com.aicustomer.ui.voice

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.aicustomer.ui.viewmodel.VoiceCallState
import kotlin.math.cos
import kotlin.math.sin

/**
 * 豆包风格玻璃态光球 — 多层半透明 + 内反射光斑 + 有机形变
 *
 * 结构: 核心(Core) → 内层(Inner) → 玻璃壳(Outer) → 外层光晕(Halo)
 * 4个独立光斑旋转 (specular highlights)
 * 呼吸动画分3层独立速度
 * Listening: XY轴非对称形变 + 声波纹扩散
 * Thinking: 色相游走 + 光斑加速旋转
 * Speaking: 音量条内环动画
 */
@Composable
fun VoiceOrb(
    callState: VoiceCallState,
    amplitude: Float,
    onTap: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")

    // 多层呼吸 (不同速度)
    val breathCore by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(1500, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "breathCore"
    )
    val breathMiddle by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(2200, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "breathMiddle"
    )
    val breathOuter by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(3200, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "breathOuter"
    )

    // 色相游走 (THINKING/SPEAKING)
    val hueShift by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(10000, easing = LinearEasing), RepeatMode.Restart),
        label = "hueShift"
    )

    // 光斑旋转
    val spotRotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(when (callState) { VoiceCallState.THINKING -> 5000; VoiceCallState.SPEAKING -> 7000; else -> 12000 }, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "spotRot"
    )

    // Spring 振幅
    val sprungAmp by animateFloatAsState(
        targetValue = amplitude,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 300f),
        label = "orbAmp"
    )

    // === 状态色板 ===
    val orbColor by animateColorAsState(
        when (callState) {
            VoiceCallState.IDLE -> Color(0xFF4A90D9)
            VoiceCallState.LISTENING -> Color(0xFF7B68EE)
            VoiceCallState.THINKING -> Color(0xFF9B59B6)
            VoiceCallState.SPEAKING -> Color(0xFFE57373)
        },
        tween(800), label = "orbColor"
    )
    val orbColorWarm by animateColorAsState(
        when (callState) {
            VoiceCallState.IDLE -> Color(0xFF5C9CE6)
            VoiceCallState.LISTENING -> Color(0xFF8884FF)
            VoiceCallState.THINKING -> Color(0xFFB87FD9)
            VoiceCallState.SPEAKING -> Color(0xFFFF8A65)
        },
        tween(800), label = "orbWarm"
    )

    // === 形变 ===
    val scaleBase = when (callState) {
        VoiceCallState.IDLE -> breathCore
        VoiceCallState.LISTENING -> 1f + sprungAmp * 0.25f
        VoiceCallState.THINKING -> breathMiddle
        VoiceCallState.SPEAKING -> 1f + sprungAmp * 0.06f
    }
    val scaleX by animateFloatAsState(1f + sprungAmp * 0.05f, spring(0.3f), label = "sx")
    val scaleY by animateFloatAsState(1f + sprungAmp * 0.09f, spring(0.3f), label = "sy")

    // 声波纹
    val ripples = remember { mutableStateListOf<SoundRipple>() }
    LaunchedEffect(callState, amplitude) {
        if (callState == VoiceCallState.LISTENING && amplitude > 0.25f) {
            if (ripples.size < 5 && (ripples.isEmpty() || ripples.last().progress > 0.25f)) {
                ripples.add(SoundRipple(startAmp = amplitude))
            }
        }
    }
    LaunchedEffect(callState) {
        if (callState == VoiceCallState.IDLE) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(16)
            val iter = ripples.iterator()
            while (iter.hasNext()) {
                val r = iter.next()
                r.progress += 0.015f
                if (r.progress >= 1f) iter.remove()
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(240.dp)
            .pointerInput(Unit) { detectTapGestures { onTap() } }
    ) {
        // 声波纹
        ripples.forEach { ripple ->
            RippleLayer(ripple.progress, ripple.startAmp, orbColor)
        }

        // 波形环 (包在光球外围)
        WaveformRing(
            callState = callState,
            amplitude = amplitude,
            color = orbColor,
            modifier = Modifier.size(240.dp)
        )

        // 光球本体
        Canvas(modifier = Modifier.size(165.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val baseR = size.minDimension / 2f
            val density = density

            // === Layer 4: 外层光晕 (Halo) ===
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(orbColor.copy(alpha = 0.06f), orbColor.copy(alpha = 0.02f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = baseR * 1.25f * breathOuter
                ),
                radius = baseR * 1.25f * breathOuter,
                center = Offset(cx, cy)
            )

            // === Layer 3: 玻璃壳 (Outer Glass) ===
            val outerR = baseR * 0.92f * breathMiddle * scaleBase
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        orbColor.copy(alpha = 0.18f),
                        orbColor.copy(alpha = 0.08f),
                        orbColor.copy(alpha = 0.02f),
                        Color.Transparent
                    ),
                    center = Offset(cx - outerR * 0.15f, cy - outerR * 0.15f),
                    radius = outerR
                ),
                radius = outerR,
                center = Offset(cx, cy)
            )
            // 玻璃壳边缘高光 (rim light)
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.06f), Color.Transparent, Color.Transparent),
                    center = Offset(cx, cy)
                ),
                radius = outerR,
                center = Offset(cx, cy),
                style = Stroke(width = 2.5f * density)
            )

            // === Layer 2: 内层 (Inner Glow) ===
            val innerR = baseR * 0.7f * breathCore * scaleBase
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        orbColorWarm.copy(alpha = 0.3f),
                        orbColor.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    center = Offset(cx + innerR * 0.1f, cy - innerR * 0.15f),
                    radius = innerR
                ),
                radius = innerR,
                center = Offset(cx, cy)
            )

            // === Layer 1: 核心 (Core) ===
            val coreR = baseR * 0.5f * breathCore * scaleBase
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.5f),
                        orbColorWarm.copy(alpha = 0.3f),
                        orbColor.copy(alpha = 0.1f),
                        Color.Transparent
                    ),
                    center = Offset(cx - coreR * 0.2f, cy - coreR * 0.25f),
                    radius = coreR
                ),
                radius = coreR,
                center = Offset(cx, cy)
            )

            // === 内反射光斑 (3个) ===
            val spotAngles = listOf(0f, 120f, 240f)
            for ((i, angle) in spotAngles.withIndex()) {
                val a = Math.toRadians((spotRotation + angle).toDouble())
                val dist = coreR * (0.35f + i * 0.15f)
                val sx = cx + (cos(a) * dist).toFloat()
                val sy = cy + (sin(a) * dist).toFloat()
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.25f),
                            Color.White.copy(alpha = 0.05f),
                            Color.Transparent
                        ),
                        center = Offset(sx, sy),
                        radius = coreR * 0.25f
                    ),
                    radius = coreR * 0.25f,
                    center = Offset(sx, sy)
                )
            }

            // === 频谱环：聆听和说话都显示 ===
            if (callState == VoiceCallState.LISTENING || callState == VoiceCallState.SPEAKING) {
                drawSpectrumRing(cx, cy, outerR * 0.85f, sprungAmp, density)
            }
        }
    }
}

/** SPEAKING 状态 — 光球内部环形频谱细线 */
private fun DrawScope.drawSpectrumRing(
    cx: Float, cy: Float, radius: Float, amplitude: Float, density: Float
) {
    val bars = 28
    val barWidth = 2.5f * density
    val angleStep = 360f / bars
    for (i in 0 until bars) {
        val angle = Math.toRadians((i * angleStep).toDouble()).toFloat()
        val phaseOffset = i * 0.7
        val barAmp = (amplitude * (0.4f + 0.6f * sin(System.currentTimeMillis() / 120.0 + phaseOffset).toFloat()))
            .coerceIn(0f, 1f)
        val barLen = (6f + barAmp * 16f) * density
        val midR = radius + barLen * 0.5f
        val startR = midR - barLen * 0.5f
        val endR = midR + barLen * 0.5f

        val sx = cx + cos(angle) * startR
        val sy = cy + sin(angle) * startR
        val ex = cx + cos(angle) * endR
        val ey = cy + sin(angle) * endR

        drawLine(
            color = Color.White.copy(alpha = (0.5f + barAmp * 0.5f).coerceIn(0f, 1f)),
            start = Offset(sx, sy),
            end = Offset(ex, ey),
            strokeWidth = barWidth,
            cap = StrokeCap.Round
        )
    }
}

/** 声波纹扩散层 */
@Composable
private fun RippleLayer(progress: Float, startAmp: Float, color: Color) {
    Canvas(modifier = Modifier.size(240.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val startR = 52f * density
        val endR = 130f * density
        val r = startR + (endR - startR) * progress
        val alpha = (1f - progress) * 0.3f * startAmp
        drawCircle(
            color = color.copy(alpha = alpha.coerceIn(0f, 1f)),
            radius = r,
            center = Offset(cx, cy),
            style = Stroke(width = 2f * density * (1f - progress * 0.7f))
        )
    }
}

private class SoundRipple(val startAmp: Float) { var progress: Float = 0f }
