package com.brewtech.decidr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.brewtech.decidr.ui.theme.Accent
import com.brewtech.decidr.ui.theme.Background
import com.brewtech.decidr.ui.theme.TextDim
import com.brewtech.decidr.ui.theme.TextSecondary

@Composable
fun HomeScreen(
    onCoinFlip: () -> Unit,
    onWheelSpin: () -> Unit,
    onDiceRoll: () -> Unit,
    onMagicBall: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .drawWithContent {
                drawContent()
                // Outer aura — glowing ring around the edge of the round screen
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r = size.minDimension / 2f

                // Soft outer glow (wide, faint)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            Accent.copy(alpha = 0.03f),
                            Accent.copy(alpha = 0.08f),
                            Accent.copy(alpha = 0.15f),
                            Accent.copy(alpha = 0.06f),
                        ),
                        center = Offset(cx, cy),
                        radius = r
                    ),
                    radius = r,
                    center = Offset(cx, cy)
                )

                // Crisp thin ring at the very edge
                drawCircle(
                    color = Accent.copy(alpha = 0.25f),
                    radius = r - 2f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1f)
                )

                // Second glow ring slightly inset
                drawCircle(
                    color = Accent.copy(alpha = 0.08f),
                    radius = r - 8f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2f)
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Title
        Text(
            text = "DECIDR",
            color = Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 3.sp,
            modifier = Modifier.offset(y = (-2).dp)
        )
        Text(
            text = "make a choice",
            color = TextDim,
            fontSize = 8.sp,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 1.sp,
            modifier = Modifier.offset(y = 12.dp)
        )

        // TOP — Coin
        OrbButton(
            label = "COIN",
            onClick = onCoinFlip,
            orbColor = Color(0xFFFFD740),
            modifier = Modifier.offset(y = (-68).dp),
            icon = { drawCoinIcon(it) }
        )

        // LEFT — Wheel
        OrbButton(
            label = "WHEEL",
            onClick = onWheelSpin,
            orbColor = Accent,
            modifier = Modifier.offset(x = (-64).dp, y = 8.dp),
            icon = { drawWheelIcon(it) }
        )

        // RIGHT — Dice
        OrbButton(
            label = "DICE",
            onClick = onDiceRoll,
            orbColor = Color.White,
            modifier = Modifier.offset(x = 64.dp, y = 8.dp),
            icon = { drawDiceIcon(it) }
        )

        // BOTTOM — 8-Ball
        OrbButton(
            label = "8-BALL",
            onClick = onMagicBall,
            orbColor = Color(0xFF7C4DFF),
            modifier = Modifier.offset(y = 76.dp),
            icon = { drawEightBallIcon(it) }
        )
    }
}

@Composable
private fun OrbButton(
    label: String,
    onClick: () -> Unit,
    orbColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    icon: DrawScope.(Float) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .drawBehind {
                    // Soft orb glow behind the button — no hard border
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                orbColor.copy(alpha = 0.20f),
                                orbColor.copy(alpha = 0.08f),
                                orbColor.copy(alpha = 0.02f),
                                Color.Transparent
                            ),
                            center = Offset(this.size.width / 2f, this.size.height / 2f),
                            radius = this.size.minDimension * 0.9f
                        ),
                        radius = this.size.minDimension * 0.9f
                    )
                    // Very subtle inner circle (not a hard border)
                    drawCircle(
                        color = orbColor.copy(alpha = 0.12f),
                        radius = this.size.minDimension / 2f,
                        style = Stroke(width = 0.75f)
                    )
                }
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            orbColor.copy(alpha = 0.08f),
                            Color(0xFF12121E)
                        ),
                        radius = size.value * 1.2f
                    )
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
        ) {
            Canvas(modifier = Modifier.size(22.dp)) {
                icon(this.size.minDimension)
            }
        }
        Text(
            text = label,
            color = TextSecondary.copy(alpha = 0.7f),
            fontSize = 7.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 1.5.sp
        )
    }
}

// ── Custom Canvas Icons ─────────────────────────────────────────────────────

private fun DrawScope.drawCoinIcon(s: Float) {
    val cx = s / 2f
    val cy = s / 2f
    val r = s * 0.4f
    val gold = Color(0xFFFFD740)
    drawCircle(gold, r, Offset(cx, cy), style = Stroke(width = s * 0.05f))
    drawCircle(gold, r * 0.65f, Offset(cx, cy), style = Stroke(width = s * 0.025f))
    // $ lines
    drawLine(gold, Offset(cx, cy - r * 0.4f), Offset(cx, cy + r * 0.4f), strokeWidth = s * 0.035f, cap = StrokeCap.Round)
    drawLine(gold, Offset(cx - r * 0.22f, cy - r * 0.12f), Offset(cx + r * 0.22f, cy - r * 0.22f), strokeWidth = s * 0.035f, cap = StrokeCap.Round)
    drawLine(gold, Offset(cx + r * 0.22f, cy + r * 0.12f), Offset(cx - r * 0.22f, cy + r * 0.22f), strokeWidth = s * 0.035f, cap = StrokeCap.Round)
}

private fun DrawScope.drawWheelIcon(s: Float) {
    val cx = s / 2f
    val cy = s / 2f
    val r = s * 0.38f
    val c = Accent
    drawCircle(c, r, Offset(cx, cy), style = Stroke(width = s * 0.035f))
    for (i in 0 until 6) {
        val angle = Math.toRadians((i * 60.0) - 90.0)
        drawLine(c, Offset(cx, cy),
            Offset(cx + (r * kotlin.math.cos(angle)).toFloat(), cy + (r * kotlin.math.sin(angle)).toFloat()),
            strokeWidth = s * 0.02f)
    }
    drawCircle(c, r * 0.12f, Offset(cx, cy))
    val tri = Path().apply {
        moveTo(cx - s * 0.05f, cy - r - s * 0.04f)
        lineTo(cx + s * 0.05f, cy - r - s * 0.04f)
        lineTo(cx, cy - r + s * 0.05f)
        close()
    }
    drawPath(tri, Color.White)
}

private fun DrawScope.drawDiceIcon(s: Float) {
    val pad = s * 0.18f
    val dieSize = s - pad * 2
    drawRoundRect(Color.White, Offset(pad, pad), Size(dieSize, dieSize),
        CornerRadius(s * 0.08f), style = Stroke(width = s * 0.035f))
    val dotR = s * 0.04f
    val cx = s / 2f
    val cy = s / 2f
    val off = dieSize * 0.24f
    drawCircle(Color.White, dotR, Offset(cx, cy))
    drawCircle(Color.White, dotR, Offset(cx - off, cy - off))
    drawCircle(Color.White, dotR, Offset(cx + off, cy - off))
    drawCircle(Color.White, dotR, Offset(cx - off, cy + off))
    drawCircle(Color.White, dotR, Offset(cx + off, cy + off))
}

private fun DrawScope.drawEightBallIcon(s: Float) {
    val cx = s / 2f
    val cy = s / 2f
    val r = s * 0.38f
    val purple = Color(0xFF7C4DFF)
    drawCircle(Color(0xFF222244), r, Offset(cx, cy))
    drawCircle(purple.copy(alpha = 0.6f), r, Offset(cx, cy), style = Stroke(width = s * 0.03f))
    drawCircle(Color(0xFF1565C0), r * 0.5f, Offset(cx, cy - r * 0.08f))
    // "8"
    drawCircle(Color.White, r * 0.17f, Offset(cx, cy - r * 0.22f), style = Stroke(width = s * 0.035f))
    drawCircle(Color.White, r * 0.17f, Offset(cx, cy + r * 0.08f), style = Stroke(width = s * 0.035f))
}
