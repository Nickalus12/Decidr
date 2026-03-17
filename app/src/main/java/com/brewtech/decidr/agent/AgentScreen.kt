package com.brewtech.decidr.agent

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// AgentState and TranscriptEntry are defined in AgentController.kt

// ── Colors ───────────────────────────────────────────────────────────────────

private val DeepBackground = Color(0xFF050508)
private val CyanAccent = Color(0xFF00E5FF)
private val PurpleAccent = Color(0xFFBB86FC)
private val IdleGray = Color(0xFF555570)
private val ErrorRed = Color(0xFFFF5252)
private val TextPrimary = Color(0xFFEEEEF0)
private val TextSecondary = Color(0xFF8888A0)
private val TextDim = Color(0xFF555570)
private val TranscriptUser = Color(0xFFAADDFF)
private val TranscriptLumina = Color(0xFFDDBBFF)

// ── Pre-allocated Paint Values ───────────────────────────────────────────────

private const val BAR_COUNT = 32
private const val TWO_PI = 2f * PI.toFloat()

// ── Main Composable ──────────────────────────────────────────────────────────

@Composable
fun AgentScreen(
    state: AgentState,
    micLevel: Float,
    speakerLevel: Float,
    transcript: List<TranscriptEntry>,
    onTapToTalk: () -> Unit,
    onStop: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    val infiniteTransition = rememberInfiniteTransition(label = "agent")

    // Phase animation for waveform movement
    val phase = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    // Pulse animation for connecting / thinking states
    val pulse = infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Dot rotation for connecting / thinking
    val dotAngle = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dotAngle"
    )

    val accentColor = when (state) {
        AgentState.LISTENING -> CyanAccent
        AgentState.SPEAKING -> PurpleAccent
        AgentState.ERROR -> ErrorRed
        AgentState.CONNECTING, AgentState.THINKING -> CyanAccent
        AgentState.IDLE -> IdleGray
    }

    val level = when (state) {
        AgentState.LISTENING -> micLevel
        AgentState.SPEAKING -> speakerLevel
        else -> 0f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepBackground)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                when (state) {
                    AgentState.IDLE, AgentState.ERROR -> onTapToTalk()
                    AgentState.LISTENING, AgentState.SPEAKING -> onStop()
                    else -> {} // no-op during CONNECTING / THINKING
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // ── Edge Aura ────────────────────────────────────────────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = size.minDimension / 2f
            val auraAlpha = when (state) {
                AgentState.LISTENING, AgentState.SPEAKING -> 0.12f + level * 0.08f
                AgentState.CONNECTING, AgentState.THINKING -> 0.06f * pulse.value
                AgentState.ERROR -> 0.10f
                AgentState.IDLE -> 0.04f
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Transparent,
                        accentColor.copy(alpha = auraAlpha * 0.3f),
                        accentColor.copy(alpha = auraAlpha * 0.7f),
                        accentColor.copy(alpha = auraAlpha),
                        accentColor.copy(alpha = auraAlpha * 0.4f)
                    ),
                    center = Offset(cx, cy),
                    radius = r
                ),
                radius = r,
                center = Offset(cx, cy)
            )
            drawCircle(
                color = accentColor.copy(alpha = auraAlpha * 0.6f),
                radius = r - 2f,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5f)
            )
        }

        // ── Title ────────────────────────────────────────────────────────
        Text(
            text = "LUMINA",
            color = accentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 3.sp,
            modifier = Modifier.offset(y = (-90).dp)
        )

        // ── Central Visualizer ───────────────────────────────────────────
        Canvas(
            modifier = Modifier.size(160.dp)
        ) {
            when (state) {
                AgentState.LISTENING -> drawListeningVisualizer(level, phase.value, accentColor)
                AgentState.SPEAKING -> drawSpeakingVisualizer(level, phase.value, accentColor)
                AgentState.CONNECTING -> drawConnectingDots(dotAngle.value, pulse.value, accentColor)
                AgentState.THINKING -> drawThinkingSpinner(dotAngle.value, pulse.value, accentColor)
                AgentState.IDLE -> drawIdleRing(pulse.value, accentColor)
                AgentState.ERROR -> drawErrorIndicator(pulse.value)
            }
        }

        // ── Status Text ──────────────────────────────────────────────────
        val statusText = when (state) {
            AgentState.CONNECTING -> "Connecting to Lumina..."
            AgentState.LISTENING -> "Listening"
            AgentState.THINKING -> "Lumina is thinking..."
            AgentState.SPEAKING -> "Speaking"
            AgentState.IDLE -> "Tap to talk"
            AgentState.ERROR -> "Connection error. Tap to retry."
        }
        Text(
            text = statusText,
            color = if (state == AgentState.ERROR) ErrorRed.copy(alpha = 0.8f) else TextSecondary,
            fontSize = 10.sp,
            fontFamily = FontFamily.SansSerif,
            modifier = Modifier.offset(y = 68.dp)
        )

        // ── Transcript ───────────────────────────────────────────────────
        val recentTranscript = transcript.takeLast(3)
        if (recentTranscript.isNotEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp, start = 32.dp, end = 32.dp)
            ) {
                recentTranscript.forEachIndexed { index, entry ->
                    val alpha = 0.4f + (index.toFloat() / recentTranscript.size.coerceAtLeast(1)) * 0.6f
                    val color = if (entry.isUser) TranscriptUser else TranscriptLumina
                    Text(
                        text = if (entry.isUser) "You: ${entry.text}" else "Lumina: ${entry.text}",
                        color = color.copy(alpha = alpha),
                        fontSize = 8.sp,
                        fontFamily = FontFamily.SansSerif,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (index < recentTranscript.lastIndex) {
                        Spacer(modifier = Modifier.height(1.dp))
                    }
                }
            }
        }
    }
}

// ── Visualizer Drawing Functions ─────────────────────────────────────────────

/**
 * LISTENING: jagged, responsive circular bars reacting to mic volume.
 */
private fun DrawScope.drawListeningVisualizer(level: Float, phase: Float, accent: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val baseRadius = size.minDimension * 0.22f
    val maxBarHeight = size.minDimension * 0.18f

    // Accent ring
    drawCircle(
        color = accent.copy(alpha = 0.15f + level * 0.15f),
        radius = baseRadius - 4f,
        center = Offset(cx, cy),
        style = Stroke(width = 2f)
    )

    // Draw radial bars
    for (i in 0 until BAR_COUNT) {
        val angle = (i.toFloat() / BAR_COUNT) * TWO_PI
        val noiseOffset = sin(angle * 3f + phase * 2f) * 0.4f +
                cos(angle * 5f + phase * 1.3f) * 0.3f +
                sin(angle * 7f + phase * 3.7f) * 0.2f
        val barLevel = (level * 0.7f + abs(noiseOffset) * level * 0.8f).coerceIn(0.05f, 1f)
        val barHeight = maxBarHeight * barLevel

        val cosA = cos(angle)
        val sinA = sin(angle)

        val startX = cx + cosA * baseRadius
        val startY = cy + sinA * baseRadius
        val endX = cx + cosA * (baseRadius + barHeight)
        val endY = cy + sinA * (baseRadius + barHeight)

        val barAlpha = 0.4f + barLevel * 0.6f
        drawLine(
            color = accent.copy(alpha = barAlpha),
            start = Offset(startX, startY),
            end = Offset(endX, endY),
            strokeWidth = 3f,
            cap = StrokeCap.Round
        )
    }

    // Inner glow dot
    drawCircle(
        color = accent.copy(alpha = 0.3f + level * 0.3f),
        radius = 6f + level * 4f,
        center = Offset(cx, cy)
    )
}

/**
 * SPEAKING: smoother, flowing wave reacting to playback audio.
 */
private fun DrawScope.drawSpeakingVisualizer(level: Float, phase: Float, accent: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val baseRadius = size.minDimension * 0.24f
    val waveAmplitude = size.minDimension * 0.12f * level.coerceAtLeast(0.1f)

    // Draw multiple smooth rings
    for (ring in 0 until 3) {
        val ringOffset = ring * 8f
        val ringAlpha = (0.5f - ring * 0.15f).coerceAtLeast(0.1f)
        val ringRadius = baseRadius + ringOffset
        val points = 64
        var prevX = 0f
        var prevY = 0f

        for (i in 0..points) {
            val angle = (i.toFloat() / points) * TWO_PI
            val wave = sin(angle * 3f + phase + ring * 0.8f) * waveAmplitude * 0.6f +
                    sin(angle * 5f - phase * 0.7f + ring * 1.2f) * waveAmplitude * 0.3f
            val r = ringRadius + wave
            val x = cx + cos(angle) * r
            val y = cy + sin(angle) * r

            if (i > 0) {
                drawLine(
                    color = accent.copy(alpha = ringAlpha),
                    start = Offset(prevX, prevY),
                    end = Offset(x, y),
                    strokeWidth = 2f - ring * 0.5f,
                    cap = StrokeCap.Round
                )
            }
            prevX = x
            prevY = y
        }
    }

    // Inner glow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                accent.copy(alpha = 0.25f + level * 0.15f),
                accent.copy(alpha = 0.05f),
                Color.Transparent
            ),
            center = Offset(cx, cy),
            radius = baseRadius * 0.6f
        ),
        radius = baseRadius * 0.6f,
        center = Offset(cx, cy)
    )
}

/**
 * CONNECTING: rotating dots in a circle, pulsing.
 */
private fun DrawScope.drawConnectingDots(angle: Float, pulse: Float, accent: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val orbitRadius = size.minDimension * 0.18f
    val dotCount = 5

    for (i in 0 until dotCount) {
        val dotAngle = Math.toRadians((angle + i * (360.0 / dotCount)).toDouble())
        val dotAlpha = (1f - i * 0.15f) * pulse
        val dotRadius = (5f - i * 0.6f).coerceAtLeast(2f)
        val x = cx + cos(dotAngle).toFloat() * orbitRadius
        val y = cy + sin(dotAngle).toFloat() * orbitRadius

        drawCircle(
            color = accent.copy(alpha = dotAlpha.coerceIn(0.1f, 1f)),
            radius = dotRadius,
            center = Offset(x, y)
        )
    }
}

/**
 * THINKING: rotating arc spinner with pulsing inner dot.
 */
private fun DrawScope.drawThinkingSpinner(angle: Float, pulse: Float, accent: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val spinnerRadius = size.minDimension * 0.2f

    // Arc spinner
    drawArc(
        color = accent.copy(alpha = 0.7f),
        startAngle = angle,
        sweepAngle = 90f,
        useCenter = false,
        topLeft = Offset(cx - spinnerRadius, cy - spinnerRadius),
        size = androidx.compose.ui.geometry.Size(spinnerRadius * 2f, spinnerRadius * 2f),
        style = Stroke(width = 3f, cap = StrokeCap.Round)
    )
    drawArc(
        color = accent.copy(alpha = 0.3f),
        startAngle = angle + 180f,
        sweepAngle = 60f,
        useCenter = false,
        topLeft = Offset(cx - spinnerRadius, cy - spinnerRadius),
        size = androidx.compose.ui.geometry.Size(spinnerRadius * 2f, spinnerRadius * 2f),
        style = Stroke(width = 2f, cap = StrokeCap.Round)
    )

    // Pulsing center dot
    drawCircle(
        color = accent.copy(alpha = pulse * 0.5f),
        radius = 4f + pulse * 2f,
        center = Offset(cx, cy)
    )
}

/**
 * IDLE: subtle pulsing ring with center dot.
 */
private fun DrawScope.drawIdleRing(pulse: Float, accent: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = size.minDimension * 0.2f

    drawCircle(
        color = accent.copy(alpha = 0.1f + pulse * 0.08f),
        radius = radius,
        center = Offset(cx, cy),
        style = Stroke(width = 1.5f)
    )
    drawCircle(
        color = accent.copy(alpha = 0.15f + pulse * 0.1f),
        radius = 5f,
        center = Offset(cx, cy)
    )
}

/**
 * ERROR: pulsing red ring with X indicator.
 */
private fun DrawScope.drawErrorIndicator(pulse: Float) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = size.minDimension * 0.18f
    val alpha = 0.3f + pulse * 0.4f

    drawCircle(
        color = ErrorRed.copy(alpha = alpha),
        radius = radius,
        center = Offset(cx, cy),
        style = Stroke(width = 2f)
    )

    val armLen = radius * 0.35f
    drawLine(
        color = ErrorRed.copy(alpha = alpha),
        start = Offset(cx - armLen, cy - armLen),
        end = Offset(cx + armLen, cy + armLen),
        strokeWidth = 3f,
        cap = StrokeCap.Round
    )
    drawLine(
        color = ErrorRed.copy(alpha = alpha),
        start = Offset(cx + armLen, cy - armLen),
        end = Offset(cx - armLen, cy + armLen),
        strokeWidth = 3f,
        cap = StrokeCap.Round
    )
}
