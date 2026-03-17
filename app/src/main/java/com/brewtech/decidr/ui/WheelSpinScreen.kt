package com.brewtech.decidr.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.brewtech.decidr.haptic.HapticEngine
import com.brewtech.decidr.ui.theme.Background
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

// ── Segment colors: rich, deep, saturated ────────────────────────────────────
private val segmentColors = listOf(
    Color(0xFF0D5A6E), // Deep Teal
    Color(0xFF5A1A7E), // Royal Purple
    Color(0xFF0D6A3E), // Emerald
    Color(0xFF6E4A0D), // Amber
    Color(0xFF1A4D7E), // Sapphire
    Color(0xFF7E1A3E), // Crimson
    Color(0xFF3E6A0D), // Forest
    Color(0xFF5A0D6E), // Violet
)

// Lighter variant for gradient outer edge
private val segmentColorsLight = listOf(
    Color(0xFF1A8CA8),
    Color(0xFF8A30B8),
    Color(0xFF1AA060),
    Color(0xFFA87218),
    Color(0xFF2A78B8),
    Color(0xFFB82858),
    Color(0xFF60A018),
    Color(0xFF8A1AA8),
)

// Bright text colors per segment
private val segmentTextColors = listOf(
    Color(0xFF00E5FF), // Cyan
    Color(0xFFD580FF), // Light Purple
    Color(0xFF00E676), // Green
    Color(0xFFFFBB33), // Gold
    Color(0xFF64B5F6), // Light Blue
    Color(0xFFFF6B8A), // Pink
    Color(0xFF9CFF57), // Lime
    Color(0xFFE040FB), // Magenta
)

// Gold for dividers and rim
private val GoldBright = Color(0xFFFFD740)
private val GoldDark = Color(0xFFB28900)
private val GoldMid = Color(0xFFDAAF20)

// ── Pre-allocated Paint objects ──────────────────────────────────────────────
private fun createTextPaint(color: Color, size: Float): android.graphics.Paint {
    return android.graphics.Paint().apply {
        this.color = android.graphics.Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt()
        )
        textSize = size
        textAlign = android.graphics.Paint.Align.CENTER
        isFakeBoldText = true
        isAntiAlias = true
        setShadowLayer(4f, 1f, 1f, android.graphics.Color.BLACK)
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.SANS_SERIF,
            android.graphics.Typeface.BOLD
        )
    }
}

// ── Particle data class ─────────────────────────────────────────────────────
private data class WheelParticle(
    val angle: Float,
    val speed: Float,
    val size: Float,
    val color: Color,
    val decay: Float
)

@Composable
fun WheelSpinScreen(hapticEngine: HapticEngine) {
    val options = listOf("YES", "NO", "MAYBE", "AGAIN", "DO IT", "NOPE", "GO!", "WAIT")
    val segmentCount = options.size
    val segmentAngle = 360f / segmentCount

    var isSpinning by remember { mutableStateOf(false) }
    var hasResult by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }
    var winnerIndex by remember { mutableIntStateOf(-1) }
    var lastTickSegment by remember { mutableIntStateOf(-1) }
    var pointerBounce by remember { mutableFloatStateOf(0f) }

    // Particles for celebration burst
    var particles by remember { mutableStateOf(emptyList<WheelParticle>()) }
    val particleProgress = remember { Animatable(0f) }

    val rotation = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // Result scale animation (spring: 0 -> 1.2 -> 1.0)
    val resultScale = remember { Animatable(0f) }

    // Winning segment pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    // Edge aura glow alpha
    val edgeGlowAlpha by animateFloatAsState(
        targetValue = if (hasResult) 0.35f else 0f,
        animationSpec = tween(800),
        label = "edgeGlow"
    )

    // Spinning dot rotation
    val spinDotAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spinDot"
    )

    // Hint text fade
    val hintAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hintAlpha"
    )

    // Pointer bounce animation
    val pointerBounceAnim by animateFloatAsState(
        targetValue = pointerBounce,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "pointerBounce"
    )

    // Pre-allocate text paints (once)
    val textPaints = remember {
        segmentTextColors.map { color -> createTextPaint(color, 17f) }
    }
    val resultPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 28f
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
            isAntiAlias = true
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF,
                android.graphics.Typeface.BOLD
            )
            letterSpacing = 0.15f
        }
    }
    val hintPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb(180, 100, 100, 120)
            textSize = 13f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF,
                android.graphics.Typeface.NORMAL
            )
            letterSpacing = 0.2f
        }
    }
    val questionPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb(130, 0, 229, 255)
            textSize = 22f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF,
                android.graphics.Typeface.BOLD
            )
        }
    }

    // Haptic tick on segment boundary crossing
    LaunchedEffect(Unit) {
        snapshotFlow { rotation.value }.collect { angle ->
            if (!isSpinning) return@collect
            val normalizedAngle = ((angle % 360f) + 360f) % 360f
            val currentSegment = floor(normalizedAngle / segmentAngle).toInt()
            if (currentSegment != lastTickSegment) {
                lastTickSegment = currentSegment
                hapticEngine.tickTick()
                // Trigger pointer bounce
                pointerBounce = 6f
                // Reset bounce target after a moment
                scope.launch {
                    delay(60)
                    pointerBounce = 0f
                }
            }
        }
    }

    fun spin() {
        if (isSpinning) return
        scope.launch {
            isSpinning = true
            hasResult = false
            resultText = ""
            winnerIndex = -1
            hapticEngine.lightTap()

            // Reset result scale
            resultScale.snapTo(0f)

            val extraRotations = (5..8).random() * 360f
            val randomOffset = (0 until 360).random().toFloat()
            val startValue = rotation.value

            rotation.animateTo(
                targetValue = startValue + extraRotations + randomOffset,
                animationSpec = tween(
                    durationMillis = 4500,
                    easing = CubicBezierEasing(0.15f, 0.6f, 0.05f, 1.0f)
                )
            )

            // Determine winner
            val finalAngle = ((rotation.value % 360f) + 360f) % 360f
            val pointerAngle = ((360f - finalAngle) % 360f + 360f) % 360f
            val idx = floor(pointerAngle / segmentAngle).toInt() % segmentCount
            winnerIndex = idx
            resultText = options[idx]
            hasResult = true
            isSpinning = false

            // Haptic celebration
            hapticEngine.heavyThud()
            delay(80)
            hapticEngine.celebrationBuzz()

            // Spawn particles
            val burstParticles = (0 until 6).map {
                WheelParticle(
                    angle = (it * 60f) + Random.nextFloat() * 30f,
                    speed = 60f + Random.nextFloat() * 40f,
                    size = 3f + Random.nextFloat() * 3f,
                    color = segmentTextColors[idx],
                    decay = 0.7f + Random.nextFloat() * 0.3f
                )
            }
            particles = burstParticles
            particleProgress.snapTo(0f)
            particleProgress.animateTo(
                1f,
                animationSpec = tween(700, easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f))
            )

            // Result text spring animation
            resultScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .clickable(interactionSource = interactionSource, indication = null) { spin() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(240.dp)) {
            val outerRadius = size.minDimension / 2f
            val innerRadius = outerRadius * 0.38f
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val center = Offset(centerX, centerY)

            // ── Edge aura glow (winning color after result) ──────────────
            if (hasResult && winnerIndex >= 0) {
                val auraColor = segmentTextColors[winnerIndex]
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            auraColor.copy(alpha = edgeGlowAlpha * 0.15f),
                            auraColor.copy(alpha = edgeGlowAlpha * 0.5f),
                            auraColor.copy(alpha = edgeGlowAlpha)
                        ),
                        center = center,
                        radius = outerRadius + 30f
                    ),
                    radius = outerRadius + 30f,
                    center = center
                )
            }

            // ── Outer metallic rim ───────────────────────────────────────
            // Rim background ring
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        GoldDark, GoldBright, GoldMid, GoldDark, GoldBright, GoldMid, GoldDark
                    ),
                    center = center
                ),
                radius = outerRadius + 3f,
                center = center,
                style = Stroke(width = 5f)
            )

            // ── Draw segments as donut arcs with gradient fill ───────────
            for (i in 0 until segmentCount) {
                val startAngleDeg = i * segmentAngle + rotation.value - 90f

                // Determine alpha for pulsing winner
                val segAlpha = if (hasResult && i == winnerIndex) {
                    pulseAlpha.coerceIn(0.3f, 1.0f)
                } else {
                    1f
                }

                val baseColor = segmentColors[i].copy(alpha = segAlpha)
                val lightColor = segmentColorsLight[i].copy(alpha = segAlpha)

                // Gradient fill: lighter at outer edge, darker toward center
                drawArc(
                    brush = Brush.radialGradient(
                        colors = listOf(baseColor, baseColor, lightColor),
                        center = center,
                        radius = outerRadius
                    ),
                    startAngle = startAngleDeg,
                    sweepAngle = segmentAngle,
                    useCenter = true,
                    size = Size(outerRadius * 2, outerRadius * 2),
                    topLeft = Offset(centerX - outerRadius, centerY - outerRadius)
                )
            }

            // ── Gold divider lines between segments ──────────────────────
            for (i in 0 until segmentCount) {
                val angleDeg = i * segmentAngle + rotation.value - 90f
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val innerX = centerX + (innerRadius * cos(angleRad)).toFloat()
                val innerY = centerY + (innerRadius * sin(angleRad)).toFloat()
                val outerX = centerX + (outerRadius * cos(angleRad)).toFloat()
                val outerY = centerY + (outerRadius * sin(angleRad)).toFloat()

                // Shadow line for depth
                drawLine(
                    color = Color.Black.copy(alpha = 0.5f),
                    start = Offset(innerX + 1f, innerY + 1f),
                    end = Offset(outerX + 1f, outerY + 1f),
                    strokeWidth = 2.5f,
                    cap = StrokeCap.Butt
                )
                // Gold divider
                drawLine(
                    color = GoldMid.copy(alpha = 0.8f),
                    start = Offset(innerX, innerY),
                    end = Offset(outerX, outerY),
                    strokeWidth = 1.8f,
                    cap = StrokeCap.Butt
                )
            }

            // ── Tick marks on outer rim at segment boundaries ────────────
            for (i in 0 until segmentCount) {
                val angleDeg = i * segmentAngle + rotation.value - 90f
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val tickInner = outerRadius + 0.5f
                val tickOuter = outerRadius + 6f
                val tx1 = centerX + (tickInner * cos(angleRad)).toFloat()
                val ty1 = centerY + (tickInner * sin(angleRad)).toFloat()
                val tx2 = centerX + (tickOuter * cos(angleRad)).toFloat()
                val ty2 = centerY + (tickOuter * sin(angleRad)).toFloat()

                drawLine(
                    color = GoldBright,
                    start = Offset(tx1, ty1),
                    end = Offset(tx2, ty2),
                    strokeWidth = 2.5f,
                    cap = StrokeCap.Round
                )
            }

            // ── Text labels in the donut ring ────────────────────────────
            val textRadius = (outerRadius + innerRadius) / 2f
            for (i in 0 until segmentCount) {
                val midAngleDeg = i * segmentAngle + segmentAngle / 2f + rotation.value - 90f
                val midAngleRad = Math.toRadians(midAngleDeg.toDouble())
                val textX = centerX + (textRadius * cos(midAngleRad)).toFloat()
                val textY = centerY + (textRadius * sin(midAngleRad)).toFloat()

                drawContext.canvas.nativeCanvas.drawText(
                    options[i], textX, textY + 6f, textPaints[i]
                )
            }

            // ── Clear center circle (dark hub with gradient) ─────────────
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF0C0C18),
                        Color(0xFF08080F),
                        Background
                    ),
                    center = center,
                    radius = innerRadius
                ),
                radius = innerRadius,
                center = center
            )

            // Inner accent ring
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color(0xFF00E5FF).copy(alpha = 0.4f),
                        Color(0xFF00E5FF).copy(alpha = 0.1f),
                        Color(0xFF00E5FF).copy(alpha = 0.3f),
                        Color(0xFF00E5FF).copy(alpha = 0.1f),
                        Color(0xFF00E5FF).copy(alpha = 0.4f)
                    ),
                    center = center
                ),
                radius = innerRadius,
                center = center,
                style = Stroke(width = 2f)
            )

            // ── Center content ───────────────────────────────────────────
            if (hasResult && resultText.isNotEmpty() && winnerIndex >= 0) {
                // Result text with scale animation
                val scale = resultScale.value.coerceIn(0f, 1.5f)
                val accentColor = segmentTextColors[winnerIndex]
                resultPaint.color = android.graphics.Color.argb(
                    255,
                    (accentColor.red * 255).toInt(),
                    (accentColor.green * 255).toInt(),
                    (accentColor.blue * 255).toInt()
                )
                resultPaint.textSize = 28f * scale
                resultPaint.setShadowLayer(8f, 0f, 0f, android.graphics.Color.argb(
                    180,
                    (accentColor.red * 255).toInt(),
                    (accentColor.green * 255).toInt(),
                    (accentColor.blue * 255).toInt()
                ))
                drawContext.canvas.nativeCanvas.drawText(
                    resultText, centerX, centerY + 10f, resultPaint
                )
            } else if (isSpinning) {
                // Spinning dot indicator
                val dotAngleRad = Math.toRadians(spinDotAngle.toDouble())
                val dotRadius = 8f
                val dotX = centerX + (dotRadius * cos(dotAngleRad)).toFloat()
                val dotY = centerY + (dotRadius * sin(dotAngleRad)).toFloat()
                drawCircle(
                    color = Color(0xFF00E5FF).copy(alpha = 0.6f),
                    radius = 3f,
                    center = Offset(dotX, dotY)
                )
                // Second dot opposite
                val dot2X = centerX - (dotRadius * cos(dotAngleRad)).toFloat()
                val dot2Y = centerY - (dotRadius * sin(dotAngleRad)).toFloat()
                drawCircle(
                    color = Color(0xFF00E5FF).copy(alpha = 0.3f),
                    radius = 2f,
                    center = Offset(dot2X, dot2Y)
                )
            } else if (!hasResult) {
                // "TAP TO SPIN" hint
                hintPaint.alpha = (hintAlpha * 255).toInt()
                drawContext.canvas.nativeCanvas.drawText(
                    "TAP TO", centerX, centerY - 2f, hintPaint
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "SPIN", centerX, centerY + 14f, hintPaint
                )
            }

            // ── Small center dot ─────────────────────────────────────────
            if (!hasResult) {
                drawCircle(
                    color = Color(0xFF00E5FF).copy(alpha = 0.4f),
                    radius = 3f,
                    center = center
                )
            }

            // ── Celebration particles ────────────────────────────────────
            if (hasResult && particles.isNotEmpty()) {
                val progress = particleProgress.value
                for (p in particles) {
                    val pAngleRad = Math.toRadians(p.angle.toDouble())
                    val dist = p.speed * progress
                    val px = centerX + (dist * cos(pAngleRad)).toFloat()
                    val py = centerY + (dist * sin(pAngleRad)).toFloat()
                    val alpha = ((1f - progress) * p.decay).coerceIn(0f, 1f)
                    val pSize = p.size * (1f - progress * 0.5f)
                    drawCircle(
                        color = p.color.copy(alpha = alpha),
                        radius = pSize,
                        center = Offset(px, py)
                    )
                }
            }

            // ── Pointer at 12 o'clock ────────────────────────────────────
            val pointerOffset = pointerBounceAnim
            val pointerPath = Path().apply {
                moveTo(centerX - 12f, centerY - outerRadius - 8f + pointerOffset)
                lineTo(centerX + 12f, centerY - outerRadius - 8f + pointerOffset)
                lineTo(centerX, centerY - outerRadius + 14f + pointerOffset)
                close()
            }
            // Shadow
            val shadowPath = Path().apply {
                moveTo(centerX - 12f + 1f, centerY - outerRadius - 8f + pointerOffset + 2f)
                lineTo(centerX + 12f + 1f, centerY - outerRadius - 8f + pointerOffset + 2f)
                lineTo(centerX + 1f, centerY - outerRadius + 14f + pointerOffset + 2f)
                close()
            }
            drawPath(shadowPath, color = Color.Black.copy(alpha = 0.4f))
            drawPath(pointerPath, color = Color.White)
            drawPath(
                pointerPath,
                color = Color(0xFF00E5FF),
                style = Stroke(width = 1.5f)
            )
        }
    }
}
