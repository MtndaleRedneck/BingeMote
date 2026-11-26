package com.bingemote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure ordering logic only. The real loader/effect call sites are covered by the instrumented seam tests. */
class LoadGateTest {
    @Test fun newestGenerationIsCurrentOlderAreStale() {
        val gate = LoadGate()
        val a = gate.bump()
        assertTrue(gate.isCurrent(a))
        val b = gate.bump()
        assertFalse("late result of the first load is dropped", gate.isCurrent(a))
        assertTrue(gate.isCurrent(b))
    }

    @Test fun currentDoesNotSupersede() {
        val gate = LoadGate()
        val a = gate.bump()
        val captured = gate.current()
        assertEquals(a, captured)
        assertTrue("capturing before an await keeps the load valid", gate.isCurrent(a))
        gate.bump() // an early-return bump or a newer load while the await runs
        assertFalse("the effect that captured before its await sees it was superseded", gate.isCurrent(captured))
    }

    @Test fun generationsStrictlyIncrease() {
        val gate = LoadGate()
        var last = gate.current()
        repeat(100) { val g = gate.bump(); assertTrue(g > last); last = g }
    }

    @Test fun concurrentBumpsNeverReuseAGeneration() {
        val gate = LoadGate()
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val threads = (1..8).map { Thread { repeat(500) { assertTrue(seen.add(gate.bump())) } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(4000, seen.size)
    }
}
