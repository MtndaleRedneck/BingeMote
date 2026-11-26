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

/** SAF listing against the in-memory test provider: folder navigation shape,
 *  video/sub separation, sidecar pairing, root lock. */
@RunWith(AndroidJUnit4::class)
class PhoneMoviesAndroidTest {

    // Cross-package SAF grants cannot be minted programmatically - Android grants
    // tree access only through the ACTION_OPEN_DOCUMENT_TREE picker (verified in CI:
    // grantUriPermission throws SecurityException even from the provider's own uid,
    // and test code always executes in the APP process, so no "owning package"
    // shortcut exists). These listing assertions therefore run as the APP uid behind
    // the REAL persisted picker grant that PhonePickerGrantTest establishes
    // (instrumented.sh orders that class first; the grant survives across sessions).
    private fun appContextWithGrant(): android.content.Context {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val has = context.contentResolver.persistedUriPermissions
            .any { it.uri.toString().contains(TestDocumentsProvider.AUTHORITY) && it.isReadPermission }
        if (!has) org.junit.Assume.assumeTrue(
            "picker grant not established yet (PhonePickerGrantTest runs first)", false)
        return context
    }

    @Test
    fun listsFoldersThenMoviesWithSidecars() {
        val context = appContextWithGrant()
        val tree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT_ID)
        val root = PhoneMovies.childUri(tree, TestDocumentsProvider.ROOT_ID)
        val listing = PhoneMovies.list(context, tree, root)

        assertTrue(listing.atRoot)
        assertEquals(listOf("Documentaries"), listing.folders.map { it.name })

        assertEquals(2, listing.movies.size)
        val movieA = listing.movies.first { it.name == "The Movie A (2024).mkv" }
        assertEquals(1234, movieA.sizeBytes)
        assertEquals(1, movieA.subtitles.size)
        assertTrue(movieA.subtitles[0].toString().contains("s1"))
        val other = listing.movies.first { it.name == "Other Film.mp4" }
        assertEquals(0, other.subtitles.size) // Unrelated.srt must NOT pair
    }

    @Test
    fun subfolderIsNotRoot() {
        val context = appContextWithGrant()
        val tree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT_ID)
        val sub = PhoneMovies.childUri(tree, "d1")
        val listing = PhoneMovies.list(context, tree, sub)
        assertTrue(!listing.atRoot)
        assertEquals(0, listing.folders.size)
        assertEquals(0, listing.movies.size)
    }
}
