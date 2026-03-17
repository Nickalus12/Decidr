package com.brewtech.decidr.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

// ─── Color Palette ───────────────────────────────────────────────────────────

val Background = Color(0xFF0A0A14)
val BackgroundLight = Color(0xFF12121E)
val Surface = Color(0xFF1A1A2E)
val SurfaceLight = Color(0xFF252540)

val Accent = Color(0xFF00E5FF)
val AccentDim = Color(0xFF007A8A)
val AccentGlow = Color(0x4000E5FF)

val TextPrimary = Color(0xFFEEEEF0)
val TextSecondary = Color(0xFF8888A0)
val TextDim = Color(0xFF555570)

val CoinGold = Color(0xFFFFD740)
val CoinGoldDark = Color(0xFFB28900)

val WheelCyan = Color(0xFF00E5FF)
val WheelOrange = Color(0xFFFF9100)
val WheelGreen = Color(0xFF69F0AE)
val WheelRed = Color(0xFFFF5252)
val WheelPurple = Color(0xFFE040FB)
val WheelYellow = Color(0xFFFFFF00)
val WheelPink = Color(0xFFFF4081)
val WheelTeal = Color(0xFF1DE9B6)

val WheelColors = listOf(
    WheelCyan, WheelOrange, WheelGreen, WheelRed,
    WheelPurple, WheelYellow, WheelPink, WheelTeal
)

val DiceWhite = Color(0xFFF5F5F5)
val DiceDot = Color(0xFF1A1A2E)

val EightBallBlue = Color(0xFF1565C0)
val EightBallTriangle = Color(0xFF1E88E5)

// ─── Theme ───────────────────────────────────────────────────────────────────

@Composable
fun DecidrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = MaterialTheme.colors.copy(
            primary = Accent,
            onPrimary = Background,
            surface = Surface,
            onSurface = TextPrimary,
            background = Background,
            onBackground = TextPrimary
        ),
        content = content
    )
}

// ─── Reusable Composables ────────────────────────────────────────────────────

@Composable
fun AccentText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: Int = 14
) {
    Text(
        text = text,
        color = Accent,
        fontSize = fontSize.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.SansSerif,
        letterSpacing = 1.sp,
        modifier = modifier
    )
}

@Composable
fun SubtleText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: Int = 11
) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = fontSize.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.SansSerif,
        modifier = modifier
    )
}

@Composable
fun GlowButton(
    label: String,
    icon: String,
    size: Dp = 50.dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
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
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(SurfaceLight, Surface),
                        radius = size.value * 1.5f
                    )
                )
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(Accent, AccentDim)
                    ),
                    shape = CircleShape
                )
                .drawBehind {
                    drawCircle(
                        color = AccentGlow,
                        radius = this.size.minDimension / 1.8f
                    )
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
        ) {
            Text(
                text = icon,
                fontSize = 20.sp,
                textAlign = TextAlign.Center
            )
        }
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 1.5.sp,
            modifier = Modifier
        )
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Accent,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.SansSerif,
        letterSpacing = 2.sp,
        style = TextStyle(
            shadow = Shadow(
                color = AccentGlow,
                offset = Offset(0f, 0f),
                blurRadius = 12f
            )
        ),
        modifier = modifier
    )
}

@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = TextDim,
        fontSize = 10.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.SansSerif,
        letterSpacing = 0.5.sp,
        modifier = modifier
    )
}
