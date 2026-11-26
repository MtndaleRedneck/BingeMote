package com.bingemote.stream

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider

/** In-memory SAF provider for instrumented tests (hosted by the test APK, so no
 *  permission grant is needed). Tree:
 *    test-root/
 *      Documentaries/            (folder)
 *      The Movie A (2024).mkv    + The Movie A (2024).srt  (paired sidecar)
 *      Other Film.mp4            (no sidecar)
 *      Unrelated.srt             (orphan sidecar)                                        */
class TestDocumentsProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "com.bingemote.test.documents"
        const val ROOT_ID = "test-root"
        private val DOC_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
        )
        private data class Doc(val id: String, val name: String, val mime: String, val size: Long)
        private val CHILDREN = mapOf(
            ROOT_ID to listOf(
                Doc("d1", "Documentaries", Document.MIME_TYPE_DIR, 0),
                Doc("m1", "The Movie A (2024).mkv", "video/x-matroska", 1234),
                Doc("s1", "The Movie A (2024).srt", "application/x-subrip", 100),
                Doc("m2", "Other Film.mp4", "video/mp4", 5678),
                Doc("s2", "Unrelated.srt", "application/x-subrip", 100),
            ),
            "d1" to emptyList(),
        )
        private val ALL = CHILDREN.values.flatten().associateBy { it.id } +
            (ROOT_ID to Doc(ROOT_ID, "Test root", Document.MIME_TYPE_DIR, 0))
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)).apply {
            newRow().add(Root.COLUMN_ROOT_ID, ROOT_ID).add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
                .add(Root.COLUMN_TITLE, "Test").add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(DOC_PROJECTION).apply {
            ALL[documentId]?.let { d ->
                newRow().add(Document.COLUMN_DOCUMENT_ID, d.id).add(Document.COLUMN_DISPLAY_NAME, d.name)
                    .add(Document.COLUMN_MIME_TYPE, d.mime).add(Document.COLUMN_SIZE, d.size)
            }
        }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(DOC_PROJECTION).apply {
            CHILDREN[parentDocumentId]?.forEach { d ->
                newRow().add(Document.COLUMN_DOCUMENT_ID, d.id).add(Document.COLUMN_DISPLAY_NAME, d.name)
                    .add(Document.COLUMN_MIME_TYPE, d.mime).add(Document.COLUMN_SIZE, d.size)
            }
        }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        throw UnsupportedOperationException("content not served by the test provider")
}
