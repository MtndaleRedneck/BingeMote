package com.bingemote.stream

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.DocumentsContract
import androidx.test.runner.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** SAF listing behind the REAL persisted picker grant of the shared-storage fixture folder that
 *  PhonePickerGrantTest created: folder navigation shape, video/sub separation, sidecar pairing, root lock. */
@RunWith(AndroidJUnit4::class)
class PhoneMoviesAndroidTest {

    // Tree access comes only from the ACTION_OPEN_DOCUMENT_TREE picker, so these listing assertions run as
    // the APP uid behind the grant PhonePickerGrantTest establishes (the parity and instrumented scripts run
    // that class first; the grant survives across sessions). A missing grant is a FAILURE, never a skip.
    private fun tree(): android.net.Uri {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val g = context.contentResolver.persistedUriPermissions
            .firstOrNull { it.uri.toString().contains("ParityMoviesSaf") && it.isReadPermission }
        assertTrue("picker grant for the fixture folder missing: PhonePickerGrantTest must run first and pass", g != null)
        return g!!.uri
    }
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun listsFoldersThenMoviesWithSidecars() {
        val tree = tree()
        val root = PhoneMovies.childUri(tree, PhonePickerGrantTest.DOC_ID)
        val listing = PhoneMovies.list(ctx, tree, root)

        assertTrue(listing.atRoot)
        assertEquals(listOf("Documentaries"), listing.folders.map { it.name })

        assertEquals(2, listing.movies.size)
        val movieA = listing.movies.first { it.name == "The_Movie_A_2024.mkv" }
        assertEquals(1234, movieA.sizeBytes)
        assertEquals(1, movieA.subtitles.size)
        assertTrue(movieA.subtitles[0].toString().contains("The_Movie_A_2024.srt"))
        val other = listing.movies.first { it.name == "Other_Film.mp4" }
        assertEquals(0, other.subtitles.size) // Unrelated.srt must NOT pair
    }

    @Test
    fun subfolderIsNotRoot() {
        val tree = tree()
        val sub = PhoneMovies.childUri(tree, PhonePickerGrantTest.DOC_ID + "/Documentaries")
        val listing = PhoneMovies.list(ctx, tree, sub)
        assertTrue(!listing.atRoot)
        assertEquals(0, listing.folders.size)
        assertEquals(0, listing.movies.size)
    }
}
