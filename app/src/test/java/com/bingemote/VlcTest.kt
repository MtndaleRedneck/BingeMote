package com.bingemote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcTest {
    @Test
    fun status_parses_playing_with_subtitles() {
        val o = JSONObject(
            """
            {
              "state": "playing",
              "time": 734,
              "length": 5340,
              "volume": 256,
              "fullscreen": true,
              "information": {
                "category": {
                  "meta": {"title": "", "filename": "Movie.Name.2024.mkv"},
                  "Stream 0": {"Type": "Video"},
                  "Stream 2": {"Type": "Subtitle", "Language": "English", "Codec": "SubRip"}
                }
              }
            }
            """.trimIndent(),
        )
        val s = Vlc.parseStatus(o)
        assertTrue(s.playing)
        assertEquals("Movie.Name.2024.mkv", s.title)
        assertEquals(734, s.timeSec)
        assertEquals(5340, s.lengthSec)
        assertEquals(256, s.volumeRaw)
        assertEquals(100, s.volumePct)
        assertTrue(s.fullscreen)
        assertEquals(4606, s.remainingSec)
    }

    @Test
    fun status_falls_back_to_no_title() {
        val s = Vlc.parseStatus(JSONObject("""{"state":"stopped","time":0,"length":0,"volume":0}"""))
        assertFalse(s.playing)
        assertEquals("", s.title)
        assertEquals(0, s.volumePct)
        assertEquals(0, s.remainingSec)
    }

    @Test
    fun volume_percent_uses_256_scale() {
        assertEquals(50, Vlc.parseStatus(JSONObject("""{"state":"paused","volume":128}""")).volumePct)
        assertEquals(1, Vlc.parseStatus(JSONObject("""{"state":"paused","volume":3}""")).volumePct)
    }

    @Test
    fun format_time() {
        assertEquals("0:00", formatTime(0))
        assertEquals("0:05", formatTime(5))
        assertEquals("12:34", formatTime(754))
        assertEquals("1:00:00", formatTime(3600))
        assertEquals("1:29:00", formatTime(5340))
    }

    @Test
    fun format_skip() {
        assertEquals("5s", formatSkip(5))
        assertEquals("30s", formatSkip(30))
        assertEquals("1m", formatSkip(60))
        assertEquals("2m", formatSkip(120))
        assertEquals("5m", formatSkip(300))
    }

    @Test
    fun video_file_detection() {
        assertTrue(isVideoFile("movie.mkv"))
        assertTrue(isVideoFile("clip.MP4"))
        assertTrue(isVideoFile("old.avi"))
        assertTrue(isVideoFile("home.mov"))
        assertFalse(isVideoFile("notes.txt"))
        assertFalse(isVideoFile("cover.jpg"))
        assertFalse(isVideoFile("subtitle.srt"))
    }

    @Test
    fun format_size() {
        assertEquals("0 KB", formatSize(0))
        assertEquals("2 KB", formatSize(2048))
        assertEquals("1 MB", formatSize(1_500_000))
        assertEquals("1.9 GB", formatSize(2_000_000_000))
    }
}

class UpdaterTest {
    @Test
    fun version_parsing() {
        assertEquals(listOf(1, 0, 0), Updater.parseVersion("v1.0.0"))
        assertEquals(listOf(1, 0, 0), Updater.parseVersion("1.0.0"))
        assertEquals(listOf(2, 14), Updater.parseVersion("V2.14"))
        assertEquals(null, Updater.parseVersion("latest"))
        assertEquals(null, Updater.parseVersion(""))
    }

    @Test
    fun only_android_releases_count() {
        val apk = "https://github.com/MtndaleRedneck/BingeMote/releases/download/android-v1.0.1/BingeMote-Android-1.0.1.apk"
        val appRel = """{"tag_name":"android-v1.0.1","draft":false,"prerelease":false,"body":"- newer app","assets":[{"name":"BingeMote-Android-1.0.1.apk","browser_download_url":"$apk","size":1000}]}"""
        val helperRel = """{"tag_name":"pc-v1.0.2","draft":false,"prerelease":false,"body":"- newer PC","assets":[{"name":"BingeMote-PC-1.0.2.exe","browser_download_url":"https://github.com/MtndaleRedneck/BingeMote/releases/download/pc-v1.0.2/BingeMote-PC-1.0.2.exe","size":1000}]}"""
        // A PC release never prompts the app, even with a higher number.
        assertEquals(null, Updater.parseRelease(helperRel, "1.0.0"))
        // An app release with an APK does.
        val info = Updater.parseRelease(appRel, "1.0.0")
        assertEquals("1.0.1", info?.versionName)
        assertEquals(null, Updater.parseRelease(appRel, "1.0.1"))
    }

    @Test
    fun display_name_strips_only_known_video_extensions() {
        assertEquals("Avatar The Way Of Water", displayName("Avatar The Way Of Water.mkv"))
        assertEquals("Film.Name.With.Dots", displayName("Film.Name.With.Dots.mp4"))
        assertEquals("notes.txt", displayName("notes.txt"))
        assertEquals("noextension", displayName("noextension"))
        assertEquals(".mkv", displayName(".mkv"))
        assertEquals("UPPER", displayName("UPPER.MKV"))
    }

    @Test
    fun newer_comparison() {
        assertTrue(Updater.isNewer("1.0.1", "1.0.0"))
        assertTrue(Updater.isNewer("2.0", "1.9.9"))
        assertTrue(Updater.isNewer("v1.1", "1.0.9"))
        assertFalse(Updater.isNewer("1.0.0", "1.0.0"))
        assertFalse(Updater.isNewer("1.0.0", "1.0.1"))
        assertFalse(Updater.isNewer("junk", "1.0.0"))
    }
}
