package com.bingemote.stream;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory SAF provider for instrumented tests (hosted by the test APK, so no permission grant is needed).
 * Tree:
 *   test-root/
 *     Documentaries/            (folder)
 *     The Movie A (2024).mkv    + The Movie A (2024).srt  (paired sidecar)
 *     Other Film.mp4            (no sidecar)
 *     Unrelated.srt             (orphan sidecar)
 *
 * Plain Java on purpose: the system can start this provider in a standalone com.bingemote.test process whose
 * class path is the test APK alone (run 45774437800 crashes.txt: DexPathList is only the test base.apk), where
 * neither the Kotlin standard library nor Kotlin intrinsics are available. This file must use only the Android
 * framework and java.* so that process can load it. Data and behavior are identical to the former Kotlin version.
 */
public class TestDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.bingemote.test.documents";
    public static final String ROOT_ID = "test-root";

    private static final String[] DOC_PROJECTION = {
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
    };

    private static final class Doc {
        final String id;
        final String name;
        final String mime;
        final long size;

        Doc(String id, String name, String mime, long size) {
            this.id = id;
            this.name = name;
            this.mime = mime;
            this.size = size;
        }
    }

    private static final Map<String, List<Doc>> CHILDREN = new HashMap<>();
    private static final Map<String, Doc> ALL = new HashMap<>();

    static {
        List<Doc> root = Arrays.asList(
            new Doc("d1", "Documentaries", Document.MIME_TYPE_DIR, 0),
            new Doc("m1", "The Movie A (2024).mkv", "video/x-matroska", 1234),
            new Doc("s1", "The Movie A (2024).srt", "application/x-subrip", 100),
            new Doc("m2", "Other Film.mp4", "video/mp4", 5678),
            new Doc("s2", "Unrelated.srt", "application/x-subrip", 100));
        CHILDREN.put(ROOT_ID, root);
        CHILDREN.put("d1", Collections.<Doc>emptyList());
        for (Doc d : root) ALL.put(d.id, d);
        ALL.put(ROOT_ID, new Doc(ROOT_ID, "Test root", Document.MIME_TYPE_DIR, 0));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS });
        c.newRow().add(Root.COLUMN_ROOT_ID, ROOT_ID).add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
            .add(Root.COLUMN_TITLE, "Test").add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD);
        return c;
    }

    private static void addDoc(MatrixCursor c, Doc d) {
        c.newRow().add(Document.COLUMN_DOCUMENT_ID, d.id).add(Document.COLUMN_DISPLAY_NAME, d.name)
            .add(Document.COLUMN_MIME_TYPE, d.mime).add(Document.COLUMN_SIZE, d.size);
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) {
        MatrixCursor c = new MatrixCursor(DOC_PROJECTION);
        Doc d = ALL.get(documentId);
        if (d != null) addDoc(c, d);
        return c;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) {
        MatrixCursor c = new MatrixCursor(DOC_PROJECTION);
        List<Doc> kids = CHILDREN.get(parentDocumentId);
        if (kids != null) for (Doc d : kids) addDoc(c, d);
        return c;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) {
        throw new UnsupportedOperationException("content not served by the test provider");
    }
}
