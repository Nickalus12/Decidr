package com.brewtech.decidr.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import com.brewtech.decidr.haptic.HapticEngine
import com.brewtech.decidr.intelligence.NeuralResponse
import com.brewtech.decidr.profile.UserProfile
import com.brewtech.decidr.sensor.SensorHub
import com.brewtech.decidr.sensor.ShakeProfile
import com.brewtech.decidr.voice.VoiceProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ── Colors ───────────────────────────────────────────────────────────────────

private val BallBackground = Color(0xFF050508)
private val SphereCore = Color(0xFF1A0A2E)
private val SphereMid = Color(0xFF0D0520)
private val SphereEdge = Color(0xFF050510)
private val SphereDeep = Color(0xFF020204)
private val OracleAccent = Color(0xFF7C4DFF)
private val ParticleColor = Color(0xFF7C4DFF)
private val ParticleColorBright = Color(0xFFA67FFF)
private val HintDim = Color(0xFF444460)
private val AuraPurple = Color(0x307C4DFF)
private val SpecularWhite = Color(0x30FFFFFF)
private val SpecularCore = Color(0x18FFFFFF)
private val InnerFogColor = Color(0xFF261245)

// ── Particle Model ───────────────────────────────────────────────────────────

private data class Particle(
    val angle: Float,       // orbital angle in radians
    val radius: Float,      // orbital radius as fraction of sphere radius (0..1)
    val speed: Float,       // radians per second
    val baseSize: Float,    // base dot radius in px
    val baseAlpha: Float,   // base alpha (0.15..0.5)
    val depth: Float,       // 0 = far away (small, dim), 1 = close (big, bright)
    val phaseOffset: Float  // vertical wobble phase
)

// ── Pre-allocated Paint Objects ──────────────────────────────────────────────

private val answerPaint = android.graphics.Paint().apply {
    color = android.graphics.Color.WHITE
    textSize = 18f
    textAlign = android.graphics.Paint.Align.CENTER
    isFakeBoldText = true
    isAntiAlias = true
    setShadowLayer(10f, 0f, 0f, android.graphics.Color.WHITE)
}

private val hintPaint = android.graphics.Paint().apply {
    color = 0xFFAABBCC.toInt()
    textSize = 13f
    textAlign = android.graphics.Paint.Align.CENTER
    isAntiAlias = true
    letterSpacing = 0.15f
    setShadowLayer(6f, 0f, 0f, 0xFF7C4DFF.toInt())
}

private val questionPaint = android.graphics.Paint().apply {
    color = android.graphics.Color.argb(120, 220, 220, 230)
    textSize = 10f
    textAlign = android.graphics.Paint.Align.CENTER
    isAntiAlias = true
    textSkewX = -0.15f
}

private val consultingPaint = android.graphics.Paint().apply {
    color = 0xFF7C4DFF.toInt()
    textSize = 14f
    textAlign = android.graphics.Paint.Align.CENTER
    isAntiAlias = true
    alpha = 120
}

// ── Screen States ────────────────────────────────────────────────────────────

private enum class BallState {
    IDLE,
    WAITING_FOR_VOICE,
    QUESTION_RECEIVED,
    REVEALING,
    ANSWER_VISIBLE
}

// ── Main Composable ──────────────────────────────────────────────────────────

@Composable
fun MagicBallScreen(
    hapticEngine: HapticEngine,
    sensorHub: SensorHub,
    userProfile: UserProfile,
    onStartVoiceInput: () -> Unit,
    spokenQuestion: String?,
    voiceProfile: VoiceProfile?,
    shakeProfile: ShakeProfile?,
    onResponseGenerated: (question: String?, response: String) -> Unit
) {
    val neuralResponse = remember { NeuralResponse(sensorHub, userProfile) }
    val scope = rememberCoroutineScope()

    // State
    var ballState by remember { mutableStateOf(BallState.IDLE) }
    var answerText by remember { mutableStateOf("") }
    var displayedQuestion by remember { mutableStateOf<String?>(null) }

    // Sensor-driven tilt for liquid physics
    val tiltX by sensorHub.tiltAngleX.collectAsState()
    val tiltY by sensorHub.tiltAngleY.collectAsState()

    // Animation values
    val textScale = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }
    val textDepth = remember { Animatable(0f) } // 0 = deep inside, 1 = at surface
    val questionAlpha = remember { Animatable(0f) }
    val consultingAlpha = remember { Animatable(0f) }
    val darkenOverlay = remember { Animatable(0f) }
    val particleScatter = remember { Animatable(0f) } // 0 = normal, 1 = scattered to edges
    val particleSettle = remember { Animatable(0f) }  // 0 = scattered, 1 = settled around text
    var particleSpeedMultiplier by remember { mutableFloatStateOf(1f) }
    var particleChaos by remember { mutableFloatStateOf(0f) } // 0 = calm, 1 = chaotic
    var particleFrozen by remember { mutableStateOf(false) }

    // Breathing glow + sphere scale pulse
    val infiniteTransition = rememberInfiniteTransition(label = "breathe")
    val breathe by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breatheGlow"
    )

    // Subtle scale pulse (0.99 to 1.01 over 4 seconds)
    val sphereScale by infiniteTransition.animateFloat(
        initialValue = 0.99f,
        targetValue = 1.01f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sphereScale"
    )

    // Listening pulse (for WAITING_FOR_VOICE state)
    val listeningPulse by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "listeningPulse"
    )

    // Particle orbit time
    val orbitTime by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbitTime"
    )

    // Generate stable particles with depth variation
    val particles = remember {
        val rng = java.util.Random(42L)
        List(40) {
            val depth = rng.nextFloat() // 0 = far, 1 = near
            Particle(
                angle = rng.nextFloat() * 2f * PI.toFloat(),
                radius = 0.10f + rng.nextFloat() * 0.70f,
                speed = 0.2f + rng.nextFloat() * 0.6f,
                baseSize = 0.5f + depth * 2.5f,      // far particles smaller
                baseAlpha = 0.10f + depth * 0.35f,    // far particles dimmer
                depth = depth,
                phaseOffset = rng.nextFloat() * 2f * PI.toFloat()
            )
        }
    }

    // When spokenQuestion changes to non-null, show question briefly
    LaunchedEffect(spokenQuestion) {
        if (spokenQuestion != null && (ballState == BallState.WAITING_FOR_VOICE || ballState == BallState.QUESTION_RECEIVED)) {
            ballState = BallState.QUESTION_RECEIVED
            displayedQuestion = spokenQuestion
            particleFrozen = false

            // Fade in question text
            questionAlpha.snapTo(0f)
            questionAlpha.animateTo(1f, tween(300))

            // Hold for 2 seconds then fade out
            delay(2000)
            questionAlpha.animateTo(0f, tween(500))
            displayedQuestion = null
        }
    }

    // Auto-timeout: if stuck in WAITING_FOR_VOICE for 5 seconds, auto-advance
    LaunchedEffect(ballState) {
        if (ballState == BallState.WAITING_FOR_VOICE) {
            delay(5000)
            // If still waiting after 5 sec, move on — voice didn't work
            if (ballState == BallState.WAITING_FOR_VOICE) {
                ballState = BallState.QUESTION_RECEIVED
                particleFrozen = false
            }
        }
    }

    // Listen for shake via SensorHub — works in ANY active state
    LaunchedEffect(Unit) {
        var lastShakeState = false
        sensorHub.shakeDetected.collect { shaking ->
            if (shaking && !lastShakeState) {
                // Accept shake in IDLE, WAITING_FOR_VOICE, or QUESTION_RECEIVED
                if (ballState == BallState.IDLE || ballState == BallState.WAITING_FOR_VOICE || ballState == BallState.QUESTION_RECEIVED) {
                    if (ballState != BallState.REVEALING && ballState != BallState.ANSWER_VISIBLE) {
                        val intensity = sensorHub.shakeIntensity.value

                        scope.launch {
                            ballState = BallState.REVEALING
                            particleFrozen = false

                            // Phase 1: Particles go chaotic (500ms)
                            particleChaos = (intensity / 20f).coerceIn(0.5f, 1f)
                            particleSpeedMultiplier = 2f + intensity / 10f
                            launch { darkenOverlay.animateTo(0.35f, tween(400)) }
                            launch { consultingAlpha.animateTo(1f, tween(500)) }

                            // Rapid light ticks during shake
                            hapticEngine.lightTap()
                            delay(150)
                            hapticEngine.lightTap()

                            // Phase 2: 3 progressively stronger ticks (light -> medium -> heavy)
                            delay(300)
                            hapticEngine.lightTap()   // light
                            delay(300)
                            hapticEngine.tickTick()    // medium
                            delay(300)
                            hapticEngine.heavyThud()   // heavy

                            // Generate response with voice and shake intelligence
                            answerText = neuralResponse.respond(
                                question = displayedQuestion,
                                shakeIntensity = intensity,
                                voiceProfile = voiceProfile,
                                shakeProfile = shakeProfile
                            )

                            // Notify caller for profile updates
                            onResponseGenerated(displayedQuestion, answerText)

                            // Fade out consulting text
                            launch { consultingAlpha.animateTo(0f, tween(300)) }

                            // Phase 3: Particles scatter outward to edges, clearing center
                            particleChaos = 0f
                            particleSpeedMultiplier = 0.5f
                            launch { particleScatter.animateTo(1f, tween(600, easing = FastOutSlowInEasing)) }
                            delay(400)

                            // Phase 4: Text emerges from the deep
                            textScale.snapTo(0.3f)    // start small (deep inside)
                            textAlpha.snapTo(0.1f)    // barely visible
                            textDepth.snapTo(0f)      // deep inside
                            ballState = BallState.ANSWER_VISIBLE

                            // Text floats toward viewer: gets larger, sharper, brighter
                            launch {
                                textDepth.animateTo(1f, tween(1000, easing = FastOutSlowInEasing))
                            }
                            launch {
                                textAlpha.animateTo(1f, tween(800, easing = FastOutSlowInEasing))
                            }
                            launch {
                                textScale.animateTo(
                                    targetValue = 1f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessLow
                                    )
                                )
                            }

                            // Phase 5: Particles slowly drift back, settling around text
                            delay(400)
                            launch { particleScatter.animateTo(0f, tween(2000)) }
                            launch { particleSettle.animateTo(1f, tween(2000)) }

                            // Final answer haptic: single strong thud
                            delay(200)
                            hapticEngine.heavyThud()

                            // Return particles to calm
                            particleSpeedMultiplier = 0.6f
                            launch { darkenOverlay.animateTo(0f, tween(1500)) }

                            // Hold answer for 5 seconds, then fade to idle
                            delay(5000)
                            launch { textAlpha.animateTo(0f, tween(800)) }
                            launch { particleSettle.animateTo(0f, tween(1000)) }
                            delay(800)
                            ballState = BallState.IDLE
                            answerText = ""
                            particleSpeedMultiplier = 1f
                            particleChaos = 0f
                            particleScatter.snapTo(0f)
                        }
                    }
                }
            }
            lastShakeState = shaking
        }
    }

    val interactionSource = remember { MutableInteractionSource() }

    // ── Render ───────────────────────────────────────────────────────────────

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BallBackground)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                if (ballState == BallState.IDLE) {
                    ballState = BallState.WAITING_FOR_VOICE
                    particleFrozen = true
                    onStartVoiceInput()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val screenRadius = min(size.width, size.height) / 2f

            // Apply breathing scale to entire sphere
            scale(sphereScale, pivot = Offset(centerX, centerY)) {

                // ── Edge Aura ────────────────────────────────────────────
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            AuraPurple.copy(alpha = 0.15f * breathe),
                            AuraPurple.copy(alpha = 0.4f * breathe)
                        ),
                        center = Offset(centerX, centerY),
                        radius = screenRadius
                    ),
                    radius = screenRadius,
                    center = Offset(centerX, centerY)
                )

                val sphereRadius = screenRadius * 0.78f

                // ── Outer Shell: nearly black, the glass surface ─────────
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            SphereDeep,
                            SphereEdge,
                            Color(0xFF030306)
                        ),
                        center = Offset(centerX, centerY),
                        radius = sphereRadius
                    ),
                    radius = sphereRadius,
                    center = Offset(centerX, centerY)
                )

                // ── Mid Layer: deep purple/indigo murk ───────────────────
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            SphereMid.copy(alpha = 0.9f),
                            SphereEdge.copy(alpha = 0.6f),
                            Color.Transparent
                        ),
                        center = Offset(
                            centerX - sphereRadius * 0.05f,
                            centerY + sphereRadius * 0.05f
                        ),
                        radius = sphereRadius * 0.85f
                    ),
                    radius = sphereRadius * 0.85f,
                    center = Offset(
                        centerX - sphereRadius * 0.05f,
                        centerY + sphereRadius * 0.05f
                    )
                )

                // ── Inner Core: slightly lighter foggy depths ────────────
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            InnerFogColor.copy(alpha = 0.5f * breathe),
                            SphereCore.copy(alpha = 0.7f * breathe),
                            SphereMid.copy(alpha = 0.3f),
                            Color.Transparent
                        ),
                        center = Offset(
                            centerX - sphereRadius * 0.1f,
                            centerY - sphereRadius * 0.1f
                        ),
                        radius = sphereRadius * 0.55f
                    ),
                    radius = sphereRadius * 0.55f,
                    center = Offset(
                        centerX - sphereRadius * 0.1f,
                        centerY - sphereRadius * 0.1f
                    )
                )

                // ── Purple nebula wisps (atmospheric depth) ──────────────
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            OracleAccent.copy(alpha = 0.06f * breathe),
                            OracleAccent.copy(alpha = 0.02f),
                            Color.Transparent
                        ),
                        center = Offset(
                            centerX + sphereRadius * 0.15f,
                            centerY + sphereRadius * 0.2f
                        ),
                        radius = sphereRadius * 0.5f
                    ),
                    radius = sphereRadius * 0.5f,
                    center = Offset(
                        centerX + sphereRadius * 0.15f,
                        centerY + sphereRadius * 0.2f
                    )
                )

                // Second wisp (offset)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            OracleAccent.copy(alpha = 0.04f * breathe),
                            Color.Transparent
                        ),
                        center = Offset(
                            centerX - sphereRadius * 0.2f,
                            centerY + sphereRadius * 0.1f
                        ),
                        radius = sphereRadius * 0.35f
                    ),
                    radius = sphereRadius * 0.35f,
                    center = Offset(
                        centerX - sphereRadius * 0.2f,
                        centerY + sphereRadius * 0.1f
                    )
                )

                // ── Sphere rim edge highlight ────────────────────────────
                drawCircle(
                    color = OracleAccent.copy(alpha = 0.06f * breathe),
                    radius = sphereRadius,
                    center = Offset(centerX, centerY),
                    style = Stroke(width = 1.5f)
                )

                // ── Particles with liquid physics ────────────────────────
                // Compute tilt-based liquid offset (particles slosh opposite to tilt)
                val tiltOffsetX = -(tiltY / 90f).coerceIn(-1f, 1f) * sphereRadius * 0.25f
                val tiltOffsetY = (tiltX / 90f).coerceIn(-1f, 1f) * sphereRadius * 0.25f

                drawLiquidParticles(
                    particles = particles,
                    centerX = centerX,
                    centerY = centerY,
                    sphereRadius = sphereRadius,
                    time = orbitTime,
                    speedMultiplier = particleSpeedMultiplier,
                    breathe = breathe,
                    tiltOffsetX = tiltOffsetX,
                    tiltOffsetY = tiltOffsetY,
                    chaos = particleChaos,
                    frozen = particleFrozen,
                    listeningPulse = listeningPulse,
                    scatterAmount = particleScatter.value,
                    settleAmount = particleSettle.value
                )

                // ── Glossy Specular Reflection (top-left) ────────────────
                // Fixed on the glass surface, doesn't move with particles
                val specCenterX = centerX - sphereRadius * 0.35f
                val specCenterY = centerY - sphereRadius * 0.40f

                // Primary specular arc
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            SpecularWhite,
                            SpecularCore,
                            Color.Transparent
                        ),
                        center = Offset(specCenterX, specCenterY),
                        radius = sphereRadius * 0.30f
                    ),
                    radius = sphereRadius * 0.30f,
                    center = Offset(specCenterX, specCenterY)
                )

                // Tighter bright spot within the specular
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0x20FFFFFF),
                            Color.Transparent
                        ),
                        center = Offset(
                            specCenterX + sphereRadius * 0.02f,
                            specCenterY + sphereRadius * 0.02f
                        ),
                        radius = sphereRadius * 0.12f
                    ),
                    radius = sphereRadius * 0.12f,
                    center = Offset(
                        specCenterX + sphereRadius * 0.02f,
                        specCenterY + sphereRadius * 0.02f
                    )
                )

                // Thin specular arc stroke (crescent highlight)
                drawArc(
                    color = Color(0x20FFFFFF),
                    startAngle = 195f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(
                        centerX - sphereRadius * 0.85f,
                        centerY - sphereRadius * 0.85f
                    ),
                    size = Size(sphereRadius * 1.7f, sphereRadius * 1.7f),
                    style = Stroke(width = 3f)
                )

                // Rim edge highlight — makes the orb pop off the background
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            Color(0x08FFFFFF),
                            Color(0x18FFFFFF),
                            Color(0x06FFFFFF)
                        ),
                        center = Offset(centerX, centerY),
                        radius = sphereRadius
                    ),
                    radius = sphereRadius,
                    center = Offset(centerX, centerY)
                )

                // Subtle rim stroke for definition
                drawCircle(
                    color = Color(0x12FFFFFF),
                    radius = sphereRadius,
                    center = Offset(centerX, centerY),
                    style = Stroke(width = 1f)
                )

                // Bottom shadow reflection — grounds the orb
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0x15FFFFFF),
                            Color.Transparent
                        ),
                        center = Offset(centerX, centerY + sphereRadius * 0.75f),
                        radius = sphereRadius * 0.25f
                    ),
                    radius = sphereRadius * 0.25f,
                    center = Offset(centerX, centerY + sphereRadius * 0.75f)
                )

                // ── Darkening Overlay ────────────────────────────────────
                if (darkenOverlay.value > 0.01f) {
                    drawCircle(
                        color = Color.Black.copy(alpha = darkenOverlay.value),
                        radius = sphereRadius,
                        center = Offset(centerX, centerY)
                    )
                }

                // ── Question Text (above sphere) ────────────────────────
                val question = displayedQuestion
                if (question != null && questionAlpha.value > 0.01f) {
                    questionPaint.alpha = (questionAlpha.value * 140).toInt()
                    val questionY = centerY - sphereRadius - 12f
                    drawContext.canvas.nativeCanvas.drawText(
                        question,
                        centerX,
                        questionY,
                        questionPaint
                    )
                }

                // ── Consulting Text ──────────────────────────────────────
                if (consultingAlpha.value > 0.01f) {
                    consultingPaint.alpha = (consultingAlpha.value * 160).toInt()
                    drawContext.canvas.nativeCanvas.drawText(
                        "Consulting the cosmos...",
                        centerX,
                        centerY,
                        consultingPaint
                    )
                }

                // ── Answer Text: emerges from the deep ───────────────────
                if (ballState == BallState.ANSWER_VISIBLE && textAlpha.value > 0.01f) {
                    val depth = textDepth.value
                    val scale = textScale.value.coerceAtLeast(0.01f)
                    val alpha = textAlpha.value

                    // Text size grows as it approaches the surface
                    val emergingSize = 9f + depth * 6f // 9sp deep -> 15sp at surface
                    answerPaint.textSize = emergingSize * scale
                    answerPaint.alpha = (alpha * 255).toInt()
                    answerPaint.color = android.graphics.Color.WHITE

                    // Glow intensifies as text surfaces
                    val glowRadius = 6f + depth * 8f
                    val glowAlpha = (alpha * depth * 180).toInt().coerceIn(0, 255)
                    answerPaint.setShadowLayer(
                        glowRadius, 0f, 0f,
                        android.graphics.Color.argb(glowAlpha, 200, 180, 255)
                    )

                    // Subtle glow circle behind text for readability
                    if (depth > 0.5f) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    OracleAccent.copy(alpha = 0.08f * alpha * depth),
                                    Color.Transparent
                                ),
                                center = Offset(centerX, centerY),
                                radius = sphereRadius * 0.4f
                            ),
                            radius = sphereRadius * 0.4f,
                            center = Offset(centerX, centerY)
                        )
                    }

                    // Word-wrap text within the sphere
                    val maxWidth = sphereRadius * 1.1f
                    val words = answerText.split(" ")
                    val lines = mutableListOf<String>()
                    var currentLine = ""

                    for (word in words) {
                        val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                        val testWidth = answerPaint.measureText(testLine)
                        if (testWidth > maxWidth && currentLine.isNotEmpty()) {
                            lines.add(currentLine)
                            currentLine = word
                        } else {
                            currentLine = testLine
                        }
                    }
                    if (currentLine.isNotEmpty()) lines.add(currentLine)

                    // Draw centered multi-line text
                    val lineHeight = answerPaint.textSize * 1.3f
                    val totalHeight = lines.size * lineHeight
                    val startY = centerY - totalHeight / 2f + answerPaint.textSize * 0.8f

                    for ((idx, line) in lines.withIndex()) {
                        drawContext.canvas.nativeCanvas.drawText(
                            line,
                            centerX,
                            startY + idx * lineHeight,
                            answerPaint
                        )
                    }
                }

                // ── Hint Text + Mic Icon (idle state) ────────────────────
                if (ballState == BallState.IDLE) {
                    val hintY = centerY + sphereRadius * 0.55f
                    hintPaint.alpha = (150 + (breathe * 105).toInt()).coerceIn(150, 255)
                    drawContext.canvas.nativeCanvas.drawText(
                        "TAP TO ASK",
                        centerX,
                        hintY,
                        hintPaint
                    )

                    drawMicIcon(
                        centerX = centerX,
                        y = hintY + 14f,
                        alpha = breathe * 0.5f
                    )
                }

                // ── Listening indicator (WAITING_FOR_VOICE) ──────────────
                if (ballState == BallState.WAITING_FOR_VOICE) {
                    // Outer pulsing ring
                    drawCircle(
                        color = OracleAccent.copy(alpha = 0.25f * listeningPulse),
                        radius = sphereRadius * 0.35f * (0.8f + listeningPulse * 0.2f),
                        center = Offset(centerX, centerY),
                        style = Stroke(width = 2f)
                    )
                    // Inner pulsing ring
                    drawCircle(
                        color = OracleAccent.copy(alpha = 0.15f * listeningPulse),
                        radius = sphereRadius * 0.22f * (0.9f + listeningPulse * 0.1f),
                        center = Offset(centerX, centerY),
                        style = Stroke(width = 1.5f)
                    )

                    // Mic icon larger
                    drawMicIcon(
                        centerX = centerX,
                        y = centerY - 8f,
                        alpha = 0.5f + listeningPulse * 0.5f
                    )

                    // "LISTENING..." text
                    val listenPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(
                            (180 * listeningPulse).toInt().coerceIn(100, 255),
                            124, 77, 255
                        )
                        textSize = 11f
                        textAlign = android.graphics.Paint.Align.CENTER
                        isAntiAlias = true
                        letterSpacing = 0.2f
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        "LISTENING...",
                        centerX,
                        centerY + sphereRadius * 0.25f,
                        listenPaint
                    )

                    // "Shake when ready" hint below
                    val shakeHintPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(120, 170, 170, 190)
                        textSize = 9f
                        textAlign = android.graphics.Paint.Align.CENTER
                        isAntiAlias = true
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        "shake when ready",
                        centerX,
                        centerY + sphereRadius * 0.40f,
                        shakeHintPaint
                    )
                }
            }
        }
    }
}

// ── Liquid Particle Drawing ──────────────────────────────────────────────────

private fun DrawScope.drawLiquidParticles(
    particles: List<Particle>,
    centerX: Float,
    centerY: Float,
    sphereRadius: Float,
    time: Float,
    speedMultiplier: Float,
    breathe: Float,
    tiltOffsetX: Float,
    tiltOffsetY: Float,
    chaos: Float,
    frozen: Boolean,
    listeningPulse: Float,
    scatterAmount: Float,
    settleAmount: Float
) {
    for (p in particles) {
        // Base orbital position
        val effectiveSpeed = if (frozen) 0f else p.speed * speedMultiplier
        val currentAngle = p.angle + time * effectiveSpeed

        // Chaos: add random-looking perturbation proportional to chaos level
        val chaosX = if (chaos > 0.01f) {
            sin(time * 7f + p.phaseOffset * 3f) * sphereRadius * 0.3f * chaos
        } else 0f
        val chaosY = if (chaos > 0.01f) {
            cos(time * 5f + p.phaseOffset * 5f) * sphereRadius * 0.3f * chaos
        } else 0f

        val wobble = if (frozen) 0f else sin(time * 1.5f + p.phaseOffset) * sphereRadius * 0.05f

        // Scatter: push particles outward to edges
        val scatterRadius = p.radius + scatterAmount * (0.95f - p.radius)

        // Settle: draw particles slightly inward to frame center (text area)
        val settledRadius = if (settleAmount > 0.01f) {
            val frameRadius = 0.45f + p.depth * 0.45f // settle to a ring around center
            scatterRadius + settleAmount * (frameRadius - scatterRadius) * 0.3f
        } else scatterRadius

        val r = sphereRadius * settledRadius

        var px = centerX + cos(currentAngle) * r + chaosX
        var py = centerY + sin(currentAngle) * r + wobble + chaosY

        // Liquid physics: shift particle cloud based on tilt (opposite to gravity)
        px += tiltOffsetX * (0.5f + p.depth * 0.5f) // deeper particles shift more
        py += tiltOffsetY * (0.5f + p.depth * 0.5f)

        // Frozen pulse effect (particles pulse in place when listening)
        val sizeMultiplier = if (frozen) {
            0.8f + 0.4f * listeningPulse * (0.5f + p.depth * 0.5f)
        } else 1f

        // Clamp to sphere boundary with bounce-back
        val dx = px - centerX
        val dy = py - centerY
        val dist = sqrt(dx * dx + dy * dy)
        val maxDist = sphereRadius * 0.90f

        if (dist > maxDist) {
            val scale = maxDist / dist
            px = centerX + dx * scale
            py = centerY + dy * scale
        }

        // Depth-based parallax rendering
        val depthSize = p.baseSize * (0.6f + p.depth * 0.8f) * sizeMultiplier
        val depthAlpha = p.baseAlpha * (0.5f + p.depth * 0.8f) * breathe

        // Close particles get a brighter color tint
        val color = if (p.depth > 0.7f) {
            ParticleColorBright.copy(alpha = depthAlpha)
        } else {
            ParticleColor.copy(alpha = depthAlpha)
        }

        drawCircle(
            color = color,
            radius = depthSize,
            center = Offset(px, py)
        )

        // Close particles get a subtle glow halo
        if (p.depth > 0.75f && depthAlpha > 0.15f) {
            drawCircle(
                color = ParticleColor.copy(alpha = depthAlpha * 0.2f),
                radius = depthSize * 2.5f,
                center = Offset(px, py)
            )
        }
    }
}

// ── Mic Icon Drawing ─────────────────────────────────────────────────────────

private fun DrawScope.drawMicIcon(
    centerX: Float,
    y: Float,
    alpha: Float
) {
    val micColor = HintDim.copy(alpha = alpha)
    val micWidth = 3.5f
    val micHeight = 6f
    val arcRadius = 5.5f

    // Mic body (capsule shape)
    drawCircle(
        color = micColor,
        radius = micWidth,
        center = Offset(centerX, y)
    )
    drawCircle(
        color = micColor,
        radius = micWidth,
        center = Offset(centerX, y + micHeight)
    )
    drawRect(
        color = micColor,
        topLeft = Offset(centerX - micWidth, y),
        size = Size(micWidth * 2f, micHeight)
    )

    // Arc around mic
    drawArc(
        color = micColor,
        startAngle = 200f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(centerX - arcRadius, y - 2f),
        size = Size(arcRadius * 2f, micHeight + arcRadius),
        style = Stroke(width = 1.5f)
    )

    // Stem below
    drawLine(
        color = micColor,
        start = Offset(centerX, y + micHeight + arcRadius * 0.4f),
        end = Offset(centerX, y + micHeight + arcRadius * 0.4f + 3.5f),
        strokeWidth = 1.5f
    )
}
