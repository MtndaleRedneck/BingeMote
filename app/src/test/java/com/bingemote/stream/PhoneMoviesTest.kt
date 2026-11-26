package com.bingemote.stream

import org.junit.Assert.assertEquals
import org.junit.Test

/** Sidecar pairing: a movie picks up same-folder subtitles whose base name
 *  matches the movie's, case-insensitively (.srt/.ass/.vtt filtering happens
 *  upstream in list()). Generic over ids so it runs on the JVM. */
class PhoneMoviesTest {
    @Test fun exactBaseMatch() {
        val videos = listOf(Triple("m1", "The Movie (2024).mkv", 10L))
        val subs = mapOf("the movie (2024)" to "s1")
        val out = PhoneMovies.pairSidecars(videos, subs)
        assertEquals(listOf("s1"), out[0].second)
        assertEquals("m1", out[0].first.first)
    }

    @Test fun caseInsensitive() {
        val videos = listOf(Triple("m1", "SAMPLE.mp4", 10L))
        val subs = mapOf("sample" to "s1")
        assertEquals(1, PhoneMovies.pairSidecars(videos, subs)[0].second.size)
    }

    @Test fun noSidecar_emptyList() {
        val videos = listOf(Triple("m1", "Alone.mkv", 10L))
        assertEquals(emptyList<String>(), PhoneMovies.pairSidecars(videos, emptyMap<String, String>())[0].second)
    }

    @Test fun differentBase_notPaired() {
        val videos = listOf(Triple("m1", "Movie.mkv", 10L))
        val subs = mapOf("other film" to "s1")
        assertEquals(0, PhoneMovies.pairSidecars(videos, subs)[0].second.size)
    }
}
