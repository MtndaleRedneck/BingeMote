package com.bingemote.stream

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Phone file-origin browsing for the Movies screen's approved PC/Phone selector.
 * Mirrors the PC side's folder behavior: subfolders are listed and navigable,
 * "up" works until standing on the assigned folder root (locked there, same as
 * the helper side). Uses ONLY the persisted folder-tree permission
 * (ACTION_OPEN_DOCUMENT_TREE); no broad storage access.
 *
 * Sidecar rule: same display name minus extension + .srt/.ass/.vtt in the SAME
 * folder as the movie.
 */
object PhoneMovies {

    data class Folder(val uri: Uri, val name: String)
    data class Movie(val uri: Uri, val name: String, val sizeBytes: Long, val subtitles: List<Uri>)
    data class Listing(val folders: List<Folder>, val movies: List<Movie>, val atRoot: Boolean)

    private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "ts")
    private val SUB_EXT = setOf("srt", "ass", "vtt")

    /** Document URI for a child folder inside the tree. */
    fun childUri(treeUri: Uri, docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    /** Pure + generic (JVM-testable without android.net.Uri): pair each video's
     *  name with the sidecar value whose key is its base name (case-insensitive). */
    internal fun <V, S> pairSidecars(
        videos: List<Triple<V, String, Long>>,
        subs: Map<String, S>,
    ): List<Pair<Triple<V, String, Long>, List<S>>> =
        videos.map { v -> v to listOfNotNull(subs[v.second.substringBeforeLast('.').lowercase()]) }

    /** Lists one folder (start: the tree root's document URI). Folders first,
     *  then movies, each name-sorted - the same presentation as the PC listing. */
    fun list(context: Context, treeUri: Uri, folderUri: Uri): Listing {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val folderDocId = DocumentsContract.getDocumentId(folderUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderDocId)
        val folders = mutableListOf<Folder>()
        val videos = mutableListOf<Triple<Uri, String, Long>>()
        val subs = mutableMapOf<String, Uri>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            ), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    folders.add(Folder(docUri, name))
                    continue
                }
                when (name.substringAfterLast('.', "").lowercase()) {
                    in VIDEO_EXT -> videos.add(Triple(docUri, name, c.getLong(3)))
                    in SUB_EXT -> subs[name.substringBeforeLast('.').lowercase()] = docUri
                }
            }
        }
        val movies = pairSidecars(videos, subs).map { (v, subList) -> Movie(v.first, v.second, v.third, subList) }
        return Listing(
            folders.sortedBy { it.name.lowercase() },
            movies.sortedBy { it.name.lowercase() },
            atRoot = folderDocId == rootDocId,
        )
    }

    /** Parent of a folder within the tree, or null when already at the root.
     *  SAF gives no parent lookup, so the UI keeps a path stack (same pattern as
     *  the PC side's scrollStack) and pops it for "up". */
    fun isRoot(treeUri: Uri, folderUri: Uri): Boolean =
        DocumentsContract.getDocumentId(folderUri) == DocumentsContract.getTreeDocumentId(treeUri)
}
