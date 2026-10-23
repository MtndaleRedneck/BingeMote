package com.bingemote

import org.junit.Assert.assertEquals
import org.junit.Test

/** The 1.9 mouse policy: a stalled link delivers the full accumulated motion,
 * never a shrunk fraction (the 1.7-1.8 25% stall shrink is what felt jerky). */
class MouseSendPolicyTest {
    @Test
    fun stall300msDeliversFullMotion() {
        val (dx, dy) = MouseSendPolicy.adjust(40f, -20f, 300)
        assertEquals(40f, dx, 0.001f)
        assertEquals(-20f, dy, 0.001f)
    }

    @Test
    fun stall2sDeliversFullSumInOneBatch() {
        val (dx, dy) = MouseSendPolicy.adjust(123.5f, 456.25f, 2000)
        assertEquals(123.5f, dx, 0.001f)
        assertEquals(456.25f, dy, 0.001f)
    }
}
