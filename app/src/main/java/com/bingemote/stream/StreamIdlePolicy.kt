package com.bingemote.stream

/**
 * Pure-JVM decision core for the stream auto-stop (teardown C1, settled):
 * the SAME 5-minute rule covers every player target - the watchdog
 * polls whichever player the stream was actually handed to.
 *
 *  - an open connection is always life (a long pause holds its socket);
 *  - after the first served stream, stops only on 5 continuous idle ticks
 *    with the target player in Stopped/Ended; playing, paused, unknown and
 *    unreachable all cancel (settled: unknown/unreachable NEVER stops);
 *  - a service that NEVER served anything (a failed handoff, a PC that never
 *    connected) still took the wake and Wi-Fi locks: with no open connection
 *    it stops after 5 ticks (~5 min) so the locks are released (lifecycle L2).
 *    That is the phone's own state - nothing was ever served - not a guess
 *    about the PC, so it is separate from the settled unknown/unreachable rule.
 */
class StreamIdlePolicy {

    enum class PlayerState { PLAYING, PAUSED, STOPPED_OR_ENDED, UNKNOWN }

    private var stoppedStreak = 0
    private var neverServedStreak = 0

    /** One watchdog tick (~60s). True exactly when the service should stop. */
    fun tick(servedAny: Boolean, openStreams: Int, playerState: PlayerState): Boolean {
        if (!servedAny) {
            // L2: nothing was ever served. An open connection is still life;
            // with none, release the locks after 5 ticks.
            if (openStreams > 0) { neverServedStreak = 0; return false }
            return ++neverServedStreak >= 5
        }
        neverServedStreak = 0
        if (openStreams > 0) { stoppedStreak = 0; return false }
        return if (playerState == PlayerState.STOPPED_OR_ENDED) {
            ++stoppedStreak >= 5
        } else {
            stoppedStreak = 0
            false
        }
    }

    companion object {
        /** Both players speak the same states (VLC status);
         *  anything blank or unrecognized is UNKNOWN, which cancels the countdown. */
        fun mapState(raw: String?): PlayerState = when (raw?.lowercase()) {
            "playing" -> PlayerState.PLAYING
            "paused" -> PlayerState.PAUSED
            "stopped", "ended" -> PlayerState.STOPPED_OR_ENDED
            else -> PlayerState.UNKNOWN
        }
    }
}
