package com.bingemote.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Range math for the HTTP streaming server (pure part; socket/SAF paths are
 *  emulator/hardware territory). RFC 7233: no Range -> 200 full; bytes=a-b ->
 *  206 with the resolved inclusive span clamped to the file. */
class StreamServerTest {
    // Exercise the same production helper used by StreamServer.
    private fun resolve(size: Long, range: LongRange?): Triple<Int, Long, Long> {
        return HttpRange.resolve(size, range)
    }

    @Test fun unsatisfiable() {
        assertEquals(416, resolve(1000, 1000L..Long.MAX_VALUE).first)
        assertEquals(416, resolve(1000, 100L..50L).first)
        assertEquals(416, resolve(0, 0L..10L).first)
        assertEquals(Triple(200, 0L, -1L), resolve(0, null))
        assertEquals(null, HttpRange.parse("items=0-9"))
        assertEquals(null, HttpRange.parse("bytes=0-9,20-30"))
        assertEquals(null, HttpRange.parse("bytes=-9"))
    }
    @Test fun noRange_fullFile() =
        assertEquals(Triple(200, 0L, 999L), resolve(1000, null))

    @Test fun explicitSpan() =
        assertEquals(Triple(206, 0L, 99L), resolve(1000, 0L..99L))

    @Test fun openEnded_clampsToEnd() =
        assertEquals(Triple(206, 500L, 999L), resolve(1000, 500L..Long.MAX_VALUE))

    @Test fun overlongEnd_clamps() =
        assertEquals(Triple(206, 0L, 999L), resolve(1000, 0L..999_999L))

    @Test fun midFileSeek() =
        assertEquals(Triple(206, 4_000_000L, 4_999_999L), resolve(10_000_000, 4_000_000L..4_999_999L))

    // The REAL request-line gate (round-6): garbage is refused in handle() before any
    // serving, which is what keeps servedAny false for invalid requests.
    @Test fun invalidRequestLines_refusedBeforeServing() {
        assertFalse(StreamServer.validRequestLine(""))
        assertFalse(StreamServer.validRequestLine("GARBAGE"))
        assertFalse(StreamServer.validRequestLine("GET"))
        assertFalse(StreamServer.validRequestLine("POST /s/tok/0 HTTP/1.1"))
        assertFalse(StreamServer.validRequestLine("PUT /s/tok/0"))
    }

    @Test fun validRequestLine_accepted() {
        assertTrue(StreamServer.validRequestLine("GET /s/tok/0 HTTP/1.1"))
        assertTrue(StreamServer.validRequestLine("GET /s/tok/0/sub/1 HTTP/1.1"))
    }
}
