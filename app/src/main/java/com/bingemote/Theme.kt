package com.bingemote

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

val Bg = Color(0xFF1B1B1E)
val Card = Color(0xFF26262D)
val Card2 = Color(0xFF2E2E36)
val HeaderBtn = Color(0xFF232328)
val TextC = Color(0xFFF5F5F7)
val Muted = Color(0xFF9696A0)
val Dimmed = Color(0xFF5C5C66)
const val ORANGE_ARGB: Long = 0xFFF97C02
/** The accent: the user's pick from [AccentSwatches] (see [AccentPrefs]); orange unless changed. Read at draw time so a pick applies at once. */
val Orange: Color get() = AccentPrefs.accent
val OrangeLight: Color get() = if (AccentPrefs.accent.toArgb() == ORANGE_ARGB.toInt()) Color(0xFFFB9E2E) else lightenAccent(AccentPrefs.accent, 0.20f)
val ErrorRed = Color(0xFFE5484D)
val OkGreen = Color(0xFF4CAF50)
val TrackGray = Color(0xFF4A4A52)

val OrangeGradient: Brush get() = Brush.verticalGradient(listOf(OrangeLight, Orange))
val CardGradient = Brush.verticalGradient(listOf(Card2, Card))

@Composable
fun BingeMoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Orange,
            onPrimary = OnAccent,
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
