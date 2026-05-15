package com.aicustomer.ui.voice

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import com.aicustomer.ui.viewmodel.VoiceCallState
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * 豆包风格波形环 — 围绕球体的环形频谱线
 *
 * 3层独立波环，每层120°相位差，产生旋转感
 * 每层48个采样点组成环形路径
 * 采样点的径向偏移随 amplitude + 时间动态变化
 */
@Composable
fun WaveformRing(
    callState: VoiceCallState,
    amplitude: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    val sprungAmplitude by animateFloatAsState(
        targetValue = amplitude,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "waveAmp"
    )

    val rings = remember { List(3) { index -> WaveRingState(phaseOffset = index * PI * 2 / 3) } }

    // 驱动旋转相位
    val rotation by animateFloatAsState(
        targetValue = when (callState) {
            VoiceCallState.THINKING -> 1.5f
            VoiceCallState.SPEAKING -> 0.8f
            VoiceCallState.LISTENING -> 0.3f
            else -> 0.15f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "ringRotation"
    )

    LaunchedEffect(callState) {
        if (callState == VoiceCallState.IDLE) return@LaunchedEffect
        var phase = 0f
        while (true) {
            phase = (phase + rotation * 0.6f) % 360f
            rings.forEach { it.globalPhase = phase + it.phaseOffset.toFloat() }
            kotlinx.coroutines.delay(16)
        }
    }

    val visible = callState != VoiceCallState.IDLE

    Canvas(modifier = modifier) {
        if (!visible) return@Canvas

        val cx = size.width / 2f
        val cy = size.height / 2f
        val baseRadius = size.minDimension / 2f - 6f * density

        val samples = 48

        for ((ringIdx, ring) in rings.withIndex()) {
            val ringRadius = baseRadius * (1f + ringIdx * 0.12f)
            val strokeWidth = (1.8f - ringIdx * 0.4f) * density
            val alpha = (0.35f - ringIdx * 0.1f) * (0.4f + sprungAmplitude * 0.6f)

            val path = androidx.compose.ui.graphics.Path()

            for (i in 0..samples) {
                val angle = (i.toFloat() / samples) * PI.toFloat() * 2f
                val phaseRad = (ring.globalPhase * PI.toFloat() / 180f)
                val waveOffset = sin(angle * 3f + phaseRad) * 15f * sprungAmplitude * density
                val r = ringRadius + waveOffset + sin(angle * 7f + phaseRad * 1.7f) * 3f * sprungAmplitude * density

                val x = cx + cos(angle) * r
                val y = cy + sin(angle) * r

                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()

            drawPath(
                path = path,
                color = color.copy(alpha = alpha.coerceIn(0f, 1f)),
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}

private class WaveRingState(val phaseOffset: Double) {
    var globalPhase: Float = 0f
}

private fun Float.toRad(): Double = this * PI / 180.0
