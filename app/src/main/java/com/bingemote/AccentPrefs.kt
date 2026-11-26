package com.bingemote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

internal fun lightenAccent(c: Color, f: Float) = Color(c.red + (1f - c.red) * f, c.green + (1f - c.green) * f, c.blue + (1f - c.blue) * f)

/** Orange (default) first, then the eight Listen Later accent swatches in its order, copied from its Theme.kt. Shown 5 on top, 4 below. */
val AccentSwatches: List<Color> = listOf(
    ORANGE_ARGB,
    0xFF6F9C7E, // sage
    0xFFCC5500, // burnt orange
    0xFF7BAFDB, // dusty blue
    0xFF83A2A4, // gray-green
    0xFFB8735C, // terracotta
    0xFFB3838F, // mauve
    0xFF8C8FC0, // indigo
    0xFFA89A8C, // taupe
).map { Color(it) }

val AccentNames: List<String> = listOf("Orange", "Sage", "Burnt orange", "Dusty blue", "Gray-green", "Terracotta", "Mauve", "Indigo", "Taupe")

/** Saved accent. Plain preferences so reading it is instant. A saved colour the picker no longer offers falls back to orange. */
object AccentPrefs {
    private var prefs: android.content.SharedPreferences? = null
    var accent: Color by mutableStateOf(Color(ORANGE_ARGB))
        private set

    internal fun resetForTest() { prefs = null; accent = Color(ORANGE_ARGB) }

    fun init(context: android.content.Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("accent_prefs", android.content.Context.MODE_PRIVATE)
        prefs = p
        accent = resolve(if (p.contains("accent")) p.getInt("accent", 0) else null)
    }

    fun resolve(argb: Int?): Color =
        AccentSwatches.firstOrNull { argb != null && it.toArgb() == argb } ?: Color(ORANGE_ARGB)

    fun choose(c: Color) { accent = c; prefs?.edit()?.putInt("accent", c.toArgb())?.apply() }
}
