package com.bingemote.stream

import com.bingemote.stream.StreamIdlePolicy
import com.bingemote.stream.StreamIdlePolicy.PlayerState.PAUSED
import com.bingemote.stream.StreamIdlePolicy.PlayerState.PLAYING
import com.bingemote.stream.StreamIdlePolicy.PlayerState.STOPPED_OR_ENDED
import com.bingemote.stream.StreamIdlePolicy.PlayerState.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Teardown C1, settled: one 5-minute rule for BOTH targets (VLC);
 *  open connections are life; unknown/unreachable always cancels. */
class StreamIdlePolicyTest {

    private fun ticks(p: StreamIdlePolicy, n: Int, state: StreamIdlePolicy.PlayerState, open: Int = 0): Boolean {
        var stop = false
        repeat(n) { stop = p.tick(true, open, state) }
        return stop
    }

    @Test fun neverServedWithNoConnectionStopsAfterFiveTicks() {
        // L2: a failed handoff (never served a byte, nothing connected) must not
        // hold the wake and Wi-Fi locks forever.
        val p = StreamIdlePolicy()
        repeat(4) { assertFalse(p.tick(servedAny = false, openStreams = 0, playerState = STOPPED_OR_ENDED)) }
        assertTrue(p.tick(servedAny = false, openStreams = 0, playerState = STOPPED_OR_ENDED))
    }

    @Test fun neverServedWithOpenConnectionStaysAlive() {
        // An open connection is life even before the first byte is served.
        val p = StreamIdlePolicy()
        repeat(30) { assertFalse(p.tick(servedAny = false, openStreams = 1, playerState = UNKNOWN)) }
    }

    @Test fun neverServedStreakResetsWhenAConnectionOpens() {
        val p = StreamIdlePolicy()
        repeat(4) { assertFalse(p.tick(servedAny = false, openStreams = 0, playerState = UNKNOWN)) }
        assertFalse(p.tick(servedAny = false, openStreams = 1, playerState = UNKNOWN)) // a connection opens
        repeat(4) { assertFalse(p.tick(servedAny = false, openStreams = 0, playerState = UNKNOWN)) } // streak restarted
        assertTrue(p.tick(servedAny = false, openStreams = 0, playerState = UNKNOWN))
    }

    @Test fun stopsAfterFiveContinuousStoppedMinutes() {
        val p = StreamIdlePolicy()
        assertFalse(ticks(p, 4, STOPPED_OR_ENDED))
        assertTrue(p.tick(true, 0, STOPPED_OR_ENDED)) // the 5th
    }

    @Test fun pausedNeverStops() { assertFalse(ticks(StreamIdlePolicy(), 30, PAUSED)) }

    @Test fun playingNeverStops() { assertFalse(ticks(StreamIdlePolicy(), 30, PLAYING)) }

    @Test fun unknownNeverStops() { assertFalse(ticks(StreamIdlePolicy(), 30, UNKNOWN)) } // unreachable target included

    @Test fun openConnectionCancelsEvenWhenStopped() {
        val p = StreamIdlePolicy()
        assertFalse(ticks(p, 4, STOPPED_OR_ENDED))
        assertFalse(p.tick(true, openStreams = 1, playerState = STOPPED_OR_ENDED)) // stream alive again
        assertFalse(ticks(p, 4, STOPPED_OR_ENDED)) // streak restarted from zero
        assertTrue(p.tick(true, 0, STOPPED_OR_ENDED))
    }

    @Test fun playingResetsTheStreak() {
        val p = StreamIdlePolicy()
        assertFalse(ticks(p, 4, STOPPED_OR_ENDED))
        assertFalse(p.tick(true, 0, PLAYING))
        assertFalse(ticks(p, 4, STOPPED_OR_ENDED))
        assertTrue(p.tick(true, 0, STOPPED_OR_ENDED))
    }

    @Test fun stateVocabularyCoversBothPlayers() {
        // VLC status share the same words.
        assertEquals(PLAYING, StreamIdlePolicy.mapState("playing"))
        assertEquals(PAUSED, StreamIdlePolicy.mapState("paused"))
        assertEquals(STOPPED_OR_ENDED, StreamIdlePolicy.mapState("stopped"))
        assertEquals(STOPPED_OR_ENDED, StreamIdlePolicy.mapState("Ended")) // case-insensitive
        assertEquals(UNKNOWN, StreamIdlePolicy.mapState(""))
        assertEquals(UNKNOWN, StreamIdlePolicy.mapState(null))
        assertEquals(UNKNOWN, StreamIdlePolicy.mapState("opening"))
    }
}
