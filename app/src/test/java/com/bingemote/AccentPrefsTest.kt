package com.bingemote

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

class AccentPrefsTest {
    @Test fun tenSwatchesOrangeFirst() {
        assertEquals(10, AccentSwatches.size)
        assertEquals(0xFF349E96.toInt(), AccentSwatches[9].toArgb())
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

class AccentContrastTest {
    @Test fun subInkOnlyWhereItPasses() {
        for ((i, s) in AccentSwatches.withIndex()) {
            val ink = onAccentSubFor(s); val top = accentTop(s)
            assert(cr(ink, s) >= 4.5f && cr(ink, top) >= 4.5f) { "sub ink ${AccentNames[i]}" }
        }
        assertEquals(Color(0xFF3D2A12), onAccentSubFor(AccentSwatches[0]))
    }
    private fun cr(a: Color, b: Color): Float {
        val x = a.luminance(); val y = b.luminance()
        return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
    }
    @Test fun everySwatchHasReadableLabelAndText() {
        for ((i, s) in AccentSwatches.withIndex()) {
            val lab = onAccentFor(s)
            val top = accentTop(s)
            assert(cr(lab, s) >= 4.5f) { "label on ${AccentNames[i]} base ${cr(lab, s)}" }
            assert(cr(lab, top) >= 4.5f) { "label on ${AccentNames[i]} top ${cr(lab, top)}" }
            val txt = accentTextFor(s)
            for (bg in listOf(Card, Card2, Bg)) assert(cr(txt, bg) >= 4.5f) { "text ${AccentNames[i]} on bg ${cr(txt, bg)}" }
            println("ACCENTROW ${AccentNames[i]} label=${lab.toArgb().toUInt().toString(16)} text=${txt.toArgb().toUInt().toString(16)} labelBase=${cr(lab, s)} labelTop=${cr(lab, top)} textCard=${cr(txt, Card)} textCard2=${cr(txt, Card2)} textBg=${cr(txt, Bg)}")
        }
    }
    @Test fun orangeDefaultUnchanged() {
        assertEquals(Color(0xFF1B1B1E), onAccentFor(AccentSwatches[0]))
        assertEquals(AccentSwatches[0], accentTextFor(AccentSwatches[0]))
    }
}
