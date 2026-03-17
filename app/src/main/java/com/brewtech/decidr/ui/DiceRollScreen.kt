package com.brewtech.decidr.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.brewtech.decidr.haptic.HapticEngine
import com.brewtech.decidr.sensor.ShakeDetector
import com.brewtech.decidr.ui.theme.Accent
import com.brewtech.decidr.ui.theme.Background
import com.brewtech.decidr.ui.theme.TextDim
import com.brewtech.decidr.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Pre-allocated die colors
private val DieFrontWhite = Color(0xFFFFFFFF)
private val DieFrontGray = Color(0xFFD0D0D8)
private val DieRightEdge = Color(0xFFA0A0A8)
private val DieBottomEdge = Color(0xFF808088)
private val DiePipColor = Color(0xFF1E1E2E)
private val DiePipHighlight = Color(0xCCFFFFFF)
private val DieShadowColor = Color(0x60000000)
private val DieBackground = Color(0xFF0A0A14)

@Composable
fun DiceRollScreen(
    shakeDetector: ShakeDetector,
    hapticEngine: HapticEngine
) {
    var diceCount by remember { mutableIntStateOf(1) }
    var die1 by remember { mutableIntStateOf(1) }
    var die2 by remember { mutableIntStateOf(1) }
    var isRolling by remember { mutableStateOf(false) }
    var hasRolled by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val scaleAnim = remember { Animatable(1f) }

    fun roll() {
        if (isRolling) return
        scope.launch {
            isRolling = true
            hasRolled = true
            hapticEngine.lightTap()

            // Rapidly cycle 14 random values with decreasing delay
            val steps = 14
            repeat(steps) { i ->
                die1 = (1..6).random()
                if (diceCount == 2) die2 = (1..6).random()

                // Scale bounce on each cycle: quick compress and release
                val progress = i.toFloat() / steps
                val bounceScale = 0.9f + 0.1f * progress
                scaleAnim.snapTo(bounceScale)

                delay(25L + i * 12L)
            }

            // Final values
            die1 = (1..6).random()
            if (diceCount == 2) die2 = (1..6).random()

            // Landing: compress then spring back
            scaleAnim.snapTo(0.88f)
            hapticEngine.heavyThud()

            scaleAnim.animateTo(
                targetValue = 1.05f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessHigh
                )
            )
            scaleAnim.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )

            delay(40)
            hapticEngine.celebrationBuzz()
            isRolling = false
        }
    }

    LaunchedEffect(Unit) {
        shakeDetector.shakeFlow.collect {
            roll()
        }
    }

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DieBackground)
            .drawWithContent {
                drawContent()

                // Cyan edge aura
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r = size.minDimension / 2f

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            Accent.copy(alpha = 0.02f),
                            Accent.copy(alpha = 0.06f),
                            Accent.copy(alpha = 0.12f),
                            Accent.copy(alpha = 0.04f),
                        ),
                        center = Offset(cx, cy),
                        radius = r
                    ),
                    radius = r,
                    center = Offset(cx, cy)
                )

                drawCircle(
                    color = Accent.copy(alpha = 0.20f),
                    radius = r - 2f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1f)
                )

                drawCircle(
                    color = Accent.copy(alpha = 0.06f),
                    radius = r - 8f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2f)
                )
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { roll() },
        contentAlignment = Alignment.Center
    ) {
        val scale = scaleAnim.value

        if (diceCount == 1) {
            // Single die: 120dp canvas, ~90dp front face, centered
            Canvas(
                modifier = Modifier
                    .size(120.dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale)
            ) {
                drawSimple3DDie(die1, 90.dp.toPx(), size.width / 2f, size.height / 2f)
            }
        } else {
            // Two dice: 90dp canvas each, offset left and right
            Canvas(
                modifier = Modifier
                    .size(90.dp)
                    .offset(x = (-52).dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale)
            ) {
                drawSimple3DDie(die1, 65.dp.toPx(), size.width / 2f, size.height / 2f)
            }
            Canvas(
                modifier = Modifier
                    .size(90.dp)
                    .offset(x = 52.dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale)
            ) {
                drawSimple3DDie(die2, 65.dp.toPx(), size.width / 2f, size.height / 2f)
            }
        }

        // Total when 2 dice
        if (hasRolled && diceCount == 2) {
            Text(
                text = "${die1 + die2}",
                color = Accent,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 1.sp,
                modifier = Modifier.offset(y = 65.dp)
            )
        }

        // Dice count toggle at bottom
        Text(
            text = if (diceCount == 1) "1" else "2",
            color = TextDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.SansSerif,
            modifier = Modifier
                .offset(y = 80.dp)
                .clickable {
                    diceCount = if (diceCount == 1) 2 else 1
                    if (diceCount == 2) die2 = (1..6).random()
                    hapticEngine.lightTap()
                }
        )

        // Shake hint before first roll
        if (!isRolling && !hasRolled) {
            Text(
                text = "SHAKE TO ROLL",
                color = TextDim,
                fontSize = 9.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 1.sp,
                modifier = Modifier.offset(y = 80.dp)
            )
        }
    }
}

/**
 * Draws a simple 3D die centered at (cx, cy) with the given front face size.
 * 3D effect: right edge strip (8px) and bottom edge strip (8px) plus drop shadow.
 */
private fun DrawScope.drawSimple3DDie(value: Int, faceSize: Float, cx: Float, cy: Float) {
    val cornerR = faceSize * 0.14f
    val edgeWidth = 8.dp.toPx()

    // Front face bounds (centered)
    val fLeft = cx - faceSize / 2f
    val fTop = cy - faceSize / 2f
    val fRight = fLeft + faceSize
    val fBottom = fTop + faceSize

    // Drop shadow: dark oval below the die
    drawOval(
        color = DieShadowColor,
        topLeft = Offset(fLeft + faceSize * 0.1f, fBottom + 2.dp.toPx()),
        size = Size(faceSize * 0.8f, edgeWidth * 1.2f)
    )

    // Bottom edge strip
    drawRoundRect(
        color = DieBottomEdge,
        topLeft = Offset(fLeft, fBottom - cornerR),
        size = Size(faceSize, cornerR + edgeWidth),
        cornerRadius = CornerRadius(cornerR)
    )

    // Right edge strip
    drawRoundRect(
        color = DieRightEdge,
        topLeft = Offset(fRight - cornerR, fTop),
        size = Size(cornerR + edgeWidth, faceSize),
        cornerRadius = CornerRadius(cornerR)
    )

    // Corner overlap (bottom-right) to fill the junction
    drawRoundRect(
        color = DieBottomEdge,
        topLeft = Offset(fRight - cornerR, fBottom - cornerR),
        size = Size(cornerR + edgeWidth, cornerR + edgeWidth),
        cornerRadius = CornerRadius(cornerR)
    )

    // Front face with linear gradient
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(DieFrontWhite, DieFrontGray),
            start = Offset(fLeft, fTop),
            end = Offset(fRight, fBottom)
        ),
        topLeft = Offset(fLeft, fTop),
        size = Size(faceSize, faceSize),
        cornerRadius = CornerRadius(cornerR)
    )

    // Draw pips
    val dotRadius = faceSize * 0.075f
    val padding = faceSize * 0.24f
    val pLeft = fLeft + padding
    val pCenterH = cx
    val pRight = fRight - padding
    val pTop = fTop + padding
    val pCenterV = cy
    val pBottom = fBottom - padding

    drawPips(value, dotRadius, pLeft, pCenterH, pRight, pTop, pCenterV, pBottom)
}

/**
 * Draws pips (dots) for a given die value.
 * Each pip: dark charcoal circle with a tiny white highlight offset 1px up-left.
 */
private fun DrawScope.drawPips(
    value: Int,
    dotRadius: Float,
    left: Float, centerH: Float, right: Float,
    top: Float, centerV: Float, bottom: Float
) {
    fun pip(x: Float, y: Float) {
        // Main pip
        drawCircle(
            color = DiePipColor,
            radius = dotRadius,
            center = Offset(x, y)
        )
        // Tiny white highlight, offset 1px up-left
        drawCircle(
            color = DiePipHighlight,
            radius = dotRadius * 0.28f,
            center = Offset(x - 1.dp.toPx(), y - 1.dp.toPx())
        )
    }

    when (value) {
        1 -> {
            pip(centerH, centerV)
        }
        2 -> {
            pip(right, top)
            pip(left, bottom)
        }
        3 -> {
            pip(right, top)
            pip(centerH, centerV)
            pip(left, bottom)
        }
        4 -> {
            pip(left, top)
            pip(right, top)
            pip(left, bottom)
            pip(right, bottom)
        }
        5 -> {
            pip(left, top)
            pip(right, top)
            pip(centerH, centerV)
            pip(left, bottom)
            pip(right, bottom)
        }
        6 -> {
            pip(left, top)
            pip(right, top)
            pip(left, centerV)
            pip(right, centerV)
            pip(left, bottom)
            pip(right, bottom)
        }
    }
}
