package com.bingemote

/** The command-queue timing policy, kept tiny and pure so a unit test pins it down. */
object CmdPolicy {
    /** A queued play/pause/skip/seek/subtitle tap older than this is dropped, not sent. */
    const val EXPIRY_MS = 2000L
    /** While VLC is closed, it is re-checked every Nth poll instead of every poll. */
    const val CLOSED_POLL_EVERY = 4
}
