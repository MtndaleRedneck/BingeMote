package com.bingemote

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ezzy's bar measured uneven edge gaps between the five glyphs (65/97/68/63 px at
 * his resolution) because each glyph had its own size and internal margins. The
 * slots are equal-weight, so even gaps follow iff every glyph's CONTENT has the
 * same width and sits centered in its bitmap. This pins both properties for all
 * five buttons (play and pause variants), at a real device density.
 */
class BarSpacingTest {
    @Test fun barGlyphsShareOneCenteredContentBox() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dm = ctx.resources.displayMetrics.density
        fun px(dp: Int) = (dp * dm + 0.5f).toInt()
        val out = px(36); val big = px(48)
        val icons = listOf(
            NotificationArt.forBar(NotificationArt.skip(big, false, "10"), out),
            NotificationArt.forBar(NotificationArt.volumeDown(big), out),
            NotificationArt.forBar(NotificationArt.pause(big), out),
            NotificationArt.forBar(NotificationArt.play(big), out),
            NotificationArt.forBar(NotificationArt.volumeUp(big), out),
            NotificationArt.forBar(NotificationArt.skip(big, true, "10"), out))
        val bounds = icons.map { NotificationArt.contentBounds(it) }
        assertTrue("every glyph drew something", bounds.all { it.width() > 0 && it.height() > 0 })
        // Equal content widths across glyphs: equal slots then yield equal edge gaps.
        val w0 = bounds[0].width()
        bounds.forEachIndexed { i, r ->
            assertEquals("glyph $i content width", w0.toDouble(), r.width().toDouble(), 1.0)
            assertEquals("glyph $i horizontally centered", (out - r.width()) / 2.0, r.left.toDouble(), 1.5)
        }
    }
}
