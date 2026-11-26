package com.bingemote

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

class AccentPrefsTest {
    @Test fun nineSwatchesOrangeFirst() {
        assertEquals(9, AccentSwatches.size)
        assertEquals(AccentNames.size, AccentSwatches.size)
        assertEquals(0xFFF97C02.toInt(), AccentSwatches[0].toArgb())
        assertEquals(AccentSwatches.size, AccentSwatches.map { it.toArgb() }.toSet().size)
    }
    @Test fun unknownOrMissingFallsBackToOrange() {
        assertEquals(Color(ORANGE_ARGB), AccentPrefs.resolve(null))
        assertEquals(Color(ORANGE_ARGB), AccentPrefs.resolve(0x12345678))
        assertEquals(AccentSwatches[3], AccentPrefs.resolve(AccentSwatches[3].toArgb()))
    }
}
