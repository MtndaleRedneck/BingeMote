package com.bingemote

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF1B1B1E)
val Card = Color(0xFF26262D)
val Card2 = Color(0xFF2E2E36)
val HeaderBtn = Color(0xFF232328)
val TextC = Color(0xFFF5F5F7)
val Muted = Color(0xFF9696A0)
val Dimmed = Color(0xFF5C5C66)
val Orange = Color(0xFFF97C02)
val OrangeLight = Color(0xFFFB9E2E)
val ErrorRed = Color(0xFFE5484D)
val TrackGray = Color(0xFF4A4A52)

val OrangeGradient = Brush.verticalGradient(listOf(OrangeLight, Orange))
val CardGradient = Brush.verticalGradient(listOf(Card2, Card))

@Composable
fun BingeMoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Orange,
            onPrimary = Color(0xFF1B1B1E),
            background = Bg,
            onBackground = TextC,
            surface = Card,
            onSurface = TextC,
            surfaceVariant = Card2,
            onSurfaceVariant = Muted
        ),
        content = content
    )
}
