package com.brewtech.decidr.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.brewtech.decidr.haptic.HapticEngine
import com.brewtech.decidr.sensor.ShakeDetector
import com.brewtech.decidr.ui.theme.TextDim
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// Deep dark background
private val CoinBackground = Color(0xFF050508)

// Gold palette
private val CoinGoldBright = Color(0xFFFFE082)
private val CoinGoldMid = Color(0xFFFFD740)
private val CoinGoldDark = Color(0xFFB28900)
private val CoinGoldDeep = Color(0xFF8A6A00)
private val CoinRimDark = Color(0xFF6A4E00)
private val CoinRimShadow = Color(0xFF3A2800)
private val CoinGoldGlow = Color(0x50FFD740)

// Pre-allocated Paint objects for Canvas text rendering
private val textPaintBase = android.graphics.Paint().apply {
    textAlign = android.graphics.Paint.Align.CENTER
    typeface = android.graphics.Typeface.create(
        android.graphics.Typeface.DEFAULT,
        android.graphics.Typeface.BOLD
    )
    isAntiAlias = true
    letterSpacing = 0.18f
}

private val shadowPaint = android.graphics.Paint(textPaintBase).apply {
    color = android.graphics.Color.argb(160, 30, 20, 0)
}

private val highlightPaint = android.graphics.Paint(textPaintBase).apply {
    color = android.graphics.Color.argb(130, 255, 245, 200)
}

private val mainTextPaint = android.graphics.Paint(textPaintBase).apply {
    color = android.graphics.Color.argb(220, 120, 88, 0)
}

// Data class for sparkle particles
private data class Sparkle(
    val x: Float,
    val y: Float,
    val createdAt: Long
)

@Composable
fun CoinFlipScreen(
    shakeDetector: ShakeDetector,
    hapticEngine: HapticEngine
) {
    var result by remember { mutableStateOf("HEADS") }
    var isFlipping by remember { mutableStateOf(false) }
    var headsCount by remember { mutableIntStateOf(0) }
    var tailsCount by remember { mutableIntStateOf(0) }
    var hasFlipped by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val flipProgress = remember { Animatable(0f) }
    val bounceAnim = remember { Animatable(0f) }

    val flipPhases = 7
    val displayedFace = if (isFlipping) {
        val phase = (flipProgress.value * flipPhases).toInt()
        if (phase % 2 == 0) result else if (result == "HEADS") "TAILS" else "HEADS"
    } else {
        result
    }

    // ScaleX compression for horizontal rotation illusion
    val scaleX = if (isFlipping) {
        val angle = flipProgress.value * flipPhases * Math.PI
        abs(cos(angle)).toFloat().coerceAtLeast(0.04f)
    } else {
        1f
    }

    // Vertical bounce offset during flip
    val bounceOffset = if (isFlipping) {
        val t = flipProgress.value
        (-80f * (1f - (2f * t - 1f) * (2f * t - 1f)))
    } else {
        bounceAnim.value
    }

    // === Enhancement 1: Idle sparkle particles ===
    val sparkles = remember { mutableStateListOf<Sparkle>() }

    LaunchedEffect(isFlipping) {
        if (!isFlipping) {
            while (true) {
                delay(800L)
                if (!isFlipping) {
                    val now = System.currentTimeMillis()
                    // Remove expired sparkles (older than 300ms)
                    sparkles.removeAll { now - it.createdAt > 300L }
                    // Add 2-3 new sparkles at random positions within coin radius
                    val count = Random.nextInt(2, 4)
                    for (i in 0 until count) {
                        val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
                        val dist = Random.nextFloat() * 0.7f // within 70% of radius
                        sparkles.add(
                            Sparkle(
                                x = cos(angle.toDouble()).toFloat() * dist,
                                y = sin(angle.toDouble()).toFloat() * dist,
                                createdAt = now
                            )
                        )
                    }
                }
            }
        } else {
            sparkles.clear()
        }
    }

    // === Enhancement 2: Idle wobble ===
    val infiniteTransition = rememberInfiniteTransition(label = "idleAnims")
    val idleWobbleScale by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idleWobble"
    )

    // === Enhancement 3: Idle glow pulse ===
    val glowPulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.30f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse"
    )

    // === Enhancement 4: Landing impact ring ===
    val impactRingProgress = remember { Animatable(0f) }
    var showImpactRing by remember { mutableStateOf(false) }

    // === Enhancement 5: Landing rotation tilt ===
    var landingTiltTarget by remember { mutableFloatStateOf(0f) }
    val landingTilt by animateFloatAsState(
        targetValue = landingTiltTarget,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "landingTilt"
    )

    // === Enhancement 6: Flip trail - track previous positions ===
    // We store the previous scaleX values for trail rendering during flip

    fun doFlip() {
        if (isFlipping) return
        scope.launch {
            isFlipping = true
            hasFlipped = true
            showImpactRing = false
            landingTiltTarget = 0f
            hapticEngine.lightTap()

            result = if ((0..1).random() == 0) "HEADS" else "TAILS"

            flipProgress.snapTo(0f)
            bounceAnim.snapTo(0f)

            flipProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 1400,
                    easing = FastOutSlowInEasing
                )
            )

            if (result == "HEADS") headsCount++ else tailsCount++
            hapticEngine.heavyThud()

            // Trigger landing impact ring
            showImpactRing = true
            scope.launch {
                impactRingProgress.snapTo(0f)
                impactRingProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(500, easing = FastOutSlowInEasing)
                )
                showImpactRing = false
            }

            // Trigger landing tilt
            landingTiltTarget = Random.nextFloat() * 6f - 3f // -3 to +3 degrees
            scope.launch {
                delay(300)
                landingTiltTarget = 0f
            }

            // Settlement bounce
            bounceAnim.snapTo(-14f)
            bounceAnim.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )

            delay(30)
            hapticEngine.celebrationBuzz()
            isFlipping = false
        }
    }

    LaunchedEffect(Unit) {
        shakeDetector.shakeFlow.collect {
            doFlip()
        }
    }

    val interactionSource = remember { MutableInteractionSource() }

    // Effective scale: idle wobble when not flipping, 1.0 when flipping
    val effectiveScale = if (isFlipping) 1f else idleWobbleScale

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CoinBackground)
            .drawWithContent {
                drawContent()

                // Edge aura rings (gold-tinted around the watch bezel)
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r = size.minDimension / 2f

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            Color.Transparent,
                            CoinGoldMid.copy(alpha = 0.02f),
                            CoinGoldMid.copy(alpha = 0.05f),
                            CoinGoldMid.copy(alpha = 0.10f),
                            CoinGoldMid.copy(alpha = 0.16f),
                            CoinGoldMid.copy(alpha = 0.06f),
                        ),
                        center = Offset(cx, cy),
                        radius = r
                    ),
                    radius = r,
                    center = Offset(cx, cy)
                )

                // Outer ring
                drawCircle(
                    color = CoinGoldMid.copy(alpha = 0.22f),
                    radius = r - 1.5f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.2f)
                )

                // Second ring
                drawCircle(
                    color = CoinGoldMid.copy(alpha = 0.10f),
                    radius = r - 6f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.5f)
                )

                // Third ring
                drawCircle(
                    color = CoinGoldMid.copy(alpha = 0.04f),
                    radius = r - 14f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1f)
                )
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { doFlip() },
        contentAlignment = Alignment.Center
    ) {
        // Warm gold radial glow behind the coin (with pulse)
        Canvas(
            modifier = Modifier
                .size(280.dp)
                .offset(y = bounceOffset.dp)
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val glowRadius = size.minDimension / 2f

            // Use pulsing alpha when idle, fixed when flipping
            val effectiveGlowAlpha = if (isFlipping) 0.22f else glowPulseAlpha

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        CoinGoldMid.copy(alpha = effectiveGlowAlpha * 1.5f),
                        CoinGoldMid.copy(alpha = effectiveGlowAlpha),
                        CoinGoldMid.copy(alpha = effectiveGlowAlpha * 0.4f),
                        CoinGoldMid.copy(alpha = effectiveGlowAlpha * 0.1f),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = glowRadius
                ),
                radius = glowRadius,
                center = Offset(cx, cy)
            )
        }

        // === Enhancement 4: Landing impact ring ===
        if (showImpactRing) {
            Canvas(
                modifier = Modifier
                    .size(280.dp)
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val coinRadius = size.minDimension / 2f * (150f / 280f) // approximate coin radius in this canvas
                val expandAmount = 60.dp.toPx() * impactRingProgress.value
                val ringRadius = coinRadius + expandAmount
                val ringAlpha = 0.5f * (1f - impactRingProgress.value)

                drawCircle(
                    color = CoinGoldMid.copy(alpha = ringAlpha),
                    radius = ringRadius,
                    center = Offset(cx, cy),
                    style = Stroke(width = 3f * (1f - impactRingProgress.value))
                )
            }
        }

        // === Enhancement 6: Flip trail (afterimages) ===
        if (isFlipping) {
            val currentProgress = flipProgress.value
            // Trail 1: slightly behind (0.15 alpha)
            val trail1Progress = (currentProgress - 0.06f).coerceAtLeast(0f)
            val trail1Angle = trail1Progress * flipPhases * Math.PI
            val trail1ScaleX = abs(cos(trail1Angle)).toFloat().coerceAtLeast(0.04f)
            val trail1Bounce = -80f * (1f - (2f * trail1Progress - 1f) * (2f * trail1Progress - 1f))

            Canvas(
                modifier = Modifier
                    .size(150.dp)
                    .offset(y = trail1Bounce.dp)
                    .graphicsLayer(
                        scaleX = trail1ScaleX,
                        alpha = 0.15f
                    )
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val radius = size.minDimension / 2f - 4f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(CoinGoldBright, CoinGoldMid, CoinGoldDark),
                        center = Offset(cx - radius * 0.35f, cy - radius * 0.35f),
                        radius = radius * 1.5f
                    ),
                    radius = radius,
                    center = Offset(cx, cy)
                )
            }

            // Trail 2: further behind (0.08 alpha)
            val trail2Progress = (currentProgress - 0.12f).coerceAtLeast(0f)
            val trail2Angle = trail2Progress * flipPhases * Math.PI
            val trail2ScaleX = abs(cos(trail2Angle)).toFloat().coerceAtLeast(0.04f)
            val trail2Bounce = -80f * (1f - (2f * trail2Progress - 1f) * (2f * trail2Progress - 1f))

            Canvas(
                modifier = Modifier
                    .size(150.dp)
                    .offset(y = trail2Bounce.dp)
                    .graphicsLayer(
                        scaleX = trail2ScaleX,
                        alpha = 0.08f
                    )
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val radius = size.minDimension / 2f - 4f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(CoinGoldBright, CoinGoldMid, CoinGoldDark),
                        center = Offset(cx - radius * 0.35f, cy - radius * 0.35f),
                        radius = radius * 1.5f
                    ),
                    radius = radius,
                    center = Offset(cx, cy)
                )
            }
        }

        // The 3D metallic coin - 150dp
        Canvas(
            modifier = Modifier
                .size(150.dp)
                .offset(y = bounceOffset.dp)
                .graphicsLayer(
                    scaleX = scaleX * effectiveScale,
                    scaleY = effectiveScale,
                    rotationZ = if (!isFlipping) landingTilt else 0f
                )
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = size.minDimension / 2f - 4f

            // === Layer 1: Rim shadow (offset down-right for 3D thickness) ===
            drawCircle(
                color = CoinRimShadow,
                radius = radius + 4f,
                center = Offset(cx + 3f, cy + 3f)
            )

            // === Layer 2: Rim edge (slightly larger, darker gold) ===
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        CoinRimDark,
                        CoinRimShadow,
                    ),
                    center = Offset(cx - radius * 0.2f, cy - radius * 0.2f),
                    radius = radius * 1.8f
                ),
                radius = radius + 2.5f,
                center = Offset(cx, cy)
            )

            // === Layer 3: Base metallic gradient (dark gold to medium gold) ===
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        CoinGoldDark,
                        CoinGoldDeep,
                    ),
                    center = Offset(cx + radius * 0.4f, cy + radius * 0.4f),
                    radius = radius * 1.4f
                ),
                radius = radius,
                center = Offset(cx, cy)
            )

            // === Layer 4: Primary metallic gradient (light source top-left) ===
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        CoinGoldBright,
                        CoinGoldMid,
                        CoinGoldDark,
                    ),
                    center = Offset(cx - radius * 0.35f, cy - radius * 0.35f),
                    radius = radius * 1.5f
                ),
                radius = radius,
                center = Offset(cx, cy)
            )

            // === Layer 5: Specular highlight (bright white at top-left quadrant) ===
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.35f),
                        Color.White.copy(alpha = 0.15f),
                        Color.White.copy(alpha = 0.03f),
                        Color.Transparent,
                    ),
                    center = Offset(cx - radius * 0.4f, cy - radius * 0.4f),
                    radius = radius * 0.55f
                ),
                radius = radius,
                center = Offset(cx, cy)
            )

            // === Layer 6: Bottom-right shadow region ===
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.08f),
                        Color.Black.copy(alpha = 0.18f),
                    ),
                    center = Offset(cx - radius * 0.3f, cy - radius * 0.3f),
                    radius = radius * 1.6f
                ),
                radius = radius,
                center = Offset(cx, cy)
            )

            // === Inner detail ring 1 (outer engraving) ===
            drawCircle(
                color = CoinGoldDeep.copy(alpha = 0.55f),
                radius = radius * 0.90f,
                center = Offset(cx, cy),
                style = Stroke(width = 1.8f)
            )

            // === Inner detail ring 1 highlight ===
            drawCircle(
                color = CoinGoldBright.copy(alpha = 0.25f),
                radius = radius * 0.895f,
                center = Offset(cx - 0.5f, cy - 0.5f),
                style = Stroke(width = 0.6f)
            )

            // === Inner detail ring 2 ===
            drawCircle(
                color = CoinGoldDeep.copy(alpha = 0.35f),
                radius = radius * 0.85f,
                center = Offset(cx, cy),
                style = Stroke(width = 1.2f)
            )

            // === Inner detail ring 3 (innermost) ===
            drawCircle(
                color = CoinGoldDeep.copy(alpha = 0.20f),
                radius = radius * 0.55f,
                center = Offset(cx, cy),
                style = Stroke(width = 0.8f)
            )

            // === Subtle concentric texture circles ===
            for (i in 1..8) {
                val r = radius * (0.12f + i * 0.09f)
                drawCircle(
                    color = CoinGoldDeep.copy(alpha = 0.04f),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 0.5f)
                )
            }

            // === Secondary specular sheen (subtle lower-right reflection) ===
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.06f),
                        Color.Transparent,
                    ),
                    center = Offset(cx + radius * 0.3f, cy + radius * 0.25f),
                    radius = radius * 0.35f
                ),
                radius = radius,
                center = Offset(cx, cy)
            )

            // === Enhancement 1: Idle sparkle particles ===
            if (!isFlipping) {
                val now = System.currentTimeMillis()
                for (sparkle in sparkles) {
                    val age = now - sparkle.createdAt
                    if (age < 300L) {
                        val sparkleAlpha = 1f - (age / 300f)
                        val sx = cx + sparkle.x * radius
                        val sy = cy + sparkle.y * radius
                        drawCircle(
                            color = Color.White.copy(alpha = sparkleAlpha * 0.9f),
                            radius = 2.5f,
                            center = Offset(sx, sy)
                        )
                        // Tiny glow around sparkle
                        drawCircle(
                            color = CoinGoldBright.copy(alpha = sparkleAlpha * 0.4f),
                            radius = 5f,
                            center = Offset(sx, sy)
                        )
                    }
                }
            }

            // === Embossed text rendering ===
            val textSize = radius * 0.32f

            // Update pre-allocated paint sizes
            shadowPaint.textSize = textSize
            highlightPaint.textSize = textSize
            mainTextPaint.textSize = textSize

            val textY = cy + (textSize * 0.35f)

            // Layer 1: Dark shadow (offset down-right for depth)
            drawContext.canvas.nativeCanvas.drawText(
                displayedFace,
                cx + 2f,
                textY + 2.5f,
                shadowPaint
            )

            // Layer 2: Bright highlight (offset up-left for emboss)
            drawContext.canvas.nativeCanvas.drawText(
                displayedFace,
                cx - 1f,
                textY - 1.2f,
                highlightPaint
            )

            // Layer 3: Main gold text on top
            drawContext.canvas.nativeCanvas.drawText(
                displayedFace,
                cx,
                textY,
                mainTextPaint
            )
        }

        // Result counter at very bottom - only after first flip
        if (hasFlipped && !isFlipping) {
            Text(
                text = "H:$headsCount  |  T:$tailsCount",
                color = TextDim,
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 1.sp,
                modifier = Modifier.offset(y = 105.dp)
            )
        }

        // Hint text - only when idle and never flipped
        if (!isFlipping && !hasFlipped) {
            Text(
                text = "Shake or Tap",
                color = TextDim,
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 0.5.sp,
                modifier = Modifier.offset(y = 105.dp)
            )
        }
    }
}
