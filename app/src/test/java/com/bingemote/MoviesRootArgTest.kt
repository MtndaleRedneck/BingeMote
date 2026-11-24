package com.bingemote

import org.junit.Assert.*
import org.junit.Test

/** Regression for the changed-root reconnect fix. Sequence that broke: helper
 *  listing active -> outage -> the PC's movies folder changed -> reconnect must
 *  reload the new root. The helper jail rejects rooted paths, so the reload has
 *  to pass the mode-correct root argument (relative "" for the helper, absolute
 *  for VLC); passing the absolute folder while viaHelper stayed true made the
 *  post-reconnect listing fail. */
class MoviesRootArgTest {
    @Test fun helperModeRootIsRelativeEmpty() {
        assertEquals("", rootArgForMode(helperMode = true, absRoot = "D:\\Films"))
    }

    @Test fun vlcModeRootIsAbsolute() {
        assertEquals("D:\\Films", rootArgForMode(helperMode = false, absRoot = "D:\\Films"))
    }

    @Test fun vlcModeBlankRootStaysBlank() {
        assertEquals("", rootArgForMode(helperMode = false, absRoot = ""))
    }
}
