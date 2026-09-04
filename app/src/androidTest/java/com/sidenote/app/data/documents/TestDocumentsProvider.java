package com.sidenote.app.data.documents;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

public final class TestDocumentsProvider extends DocumentsProvider {
    private static final String AUTHORITY = "com.sidenote.app.test.documents";
    private static final String ROOT_ID = "root";
    private static final String FILE_PREFIX = "file:";
    private static final String METHOD_RESET = "reset";
    private static final String METHOD_CLEAR = "clear";
    private static final String METHOD_GRANT_TREE = "grant-tree";
    private static final String METHOD_DENY_ACCESS = "deny-access";
    private static final String[] ROOT_COLUMNS = {
        DocumentsContract.Root.COLUMN_ROOT_ID,
        DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        DocumentsContract.Root.COLUMN_TITLE,
        DocumentsContract.Root.COLUMN_FLAGS,
    };
    private static final String[] DOCUMENT_COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    };

    private static volatile boolean denyAccess;
    private static volatile boolean failWrites;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        switch (method) {
            case METHOD_RESET:
                denyAccess = false;
                failWrites = false;
                clearFixture();
                File directory = fixtureDirectory();
                if (!directory.mkdirs() && !directory.isDirectory()) {
                    throw new IllegalStateException("Could not create fixture directory");
                }
                break;
            case METHOD_CLEAR:
                denyAccess = false;
                failWrites = false;
                clearFixture();
                break;
            case METHOD_GRANT_TREE:
                Objects.requireNonNull(getContext()).grantUriPermission(
                    Objects.requireNonNull(arg),
                    treeUri(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                );
                break;
            case METHOD_DENY_ACCESS:
                denyAccess = true;
                break;
            case "fail-writes":
                failWrites = Boolean.parseBoolean(arg);
                break;
            default:
                return super.call(method, arg, extras);
        }
        return Bundle.EMPTY;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        enforceAllowed();
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : ROOT_COLUMNS);
        cursor.newRow()
            .add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID)
            .add(DocumentsContract.Root.COLUMN_TITLE, "SideNote test tree")
            .add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE);
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) {
        enforceAllowed();
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        addDocumentRow(cursor, documentId);
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) {
        enforceAllowed();
        if (!ROOT_ID.equals(parentDocumentId)) {
            throw new IllegalArgumentException("Unknown parent: " + parentDocumentId);
        }
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        File[] files = fixtureDirectory().listFiles();
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File file : files) {
                addDocumentRow(cursor, fileDocumentId(file.getName()));
            }
        }
        return cursor;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return ROOT_ID.equals(parentDocumentId)
            && (ROOT_ID.equals(documentId) || documentId.startsWith(FILE_PREFIX));
    }

    @Override
    public ParcelFileDescriptor openDocument(
        String documentId,
        String mode,
        CancellationSignal signal
    ) throws FileNotFoundException {
        enforceAllowed();
        if (failWrites && mode.contains("w")) {
            throw new FileNotFoundException("Injected provider write failure before truncation");
        }
        return ParcelFileDescriptor.open(fileForDocumentId(documentId), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
        throws FileNotFoundException {
        enforceAllowed();
        if (!ROOT_ID.equals(parentDocumentId) || displayName.contains("/") || displayName.contains("\\")) {
            throw new FileNotFoundException("Invalid document name or parent");
        }
        File file = new File(fixtureDirectory(), displayName);
        try {
            if (!file.createNewFile()) {
                throw new FileNotFoundException("Document already exists: " + displayName);
            }
        } catch (IOException error) {
            FileNotFoundException failure = new FileNotFoundException("Could not create: " + displayName);
            failure.initCause(error);
            throw failure;
        }
        return fileDocumentId(displayName);
    }

    public static Uri treeUri() {
        return DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID);
    }

    public static void reset(ContentResolver contentResolver) {
        control(contentResolver, METHOD_RESET);
    }

    public static void clear(ContentResolver contentResolver) {
        control(contentResolver, METHOD_CLEAR);
    }

    public static void grantTree(ContentResolver contentResolver, String targetPackage) {
        control(contentResolver, METHOD_GRANT_TREE, targetPackage);
    }

    public static void denyAccess(ContentResolver contentResolver) {
        control(contentResolver, METHOD_DENY_ACCESS);
    }

    public static void failWrites(ContentResolver contentResolver, boolean fail) {
        control(contentResolver, "fail-writes", Boolean.toString(fail));
    }

    private static void control(ContentResolver contentResolver, String method) {
        control(contentResolver, method, null);
    }

    private static void control(ContentResolver contentResolver, String method, String arg) {
        Uri providerUri = new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(AUTHORITY)
            .build();
        Objects.requireNonNull(contentResolver.call(providerUri, method, arg, null));
    }

    private void addDocumentRow(MatrixCursor cursor, String documentId) {
        if (ROOT_ID.equals(documentId)) {
            cursor.newRow()
                .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, ROOT_ID)
                .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "SideNote test tree")
                .add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                .add(
                    DocumentsContract.Document.COLUMN_FLAGS,
                    DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                );
            return;
        }
        File file = fileForDocumentId(documentId);
        cursor.newRow()
            .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
            .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.getName())
            .add(DocumentsContract.Document.COLUMN_MIME_TYPE, "text/plain")
            .add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_SUPPORTS_WRITE)
            .add(DocumentsContract.Document.COLUMN_SIZE, file.length())
            .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }

    private File fixtureDirectory() {
        Context context = Objects.requireNonNull(getContext());
        return new File(context.getCacheDir(), "saf-text-document-store");
    }

    private File fileForDocumentId(String documentId) {
        if (!documentId.startsWith(FILE_PREFIX)) {
            throw new IllegalArgumentException("Unknown document: " + documentId);
        }
        String name = documentId.substring(FILE_PREFIX.length());
        if (name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("Invalid document: " + documentId);
        }
        return new File(fixtureDirectory(), name);
    }

    private void clearFixture() {
        File fixture = fixtureDirectory();
        if (fixture.exists() && !deleteRecursively(fixture)) {
            throw new IllegalStateException("Could not clear fixture directory");
        }
    }

    private static boolean deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!deleteRecursively(child)) {
                    return false;
                }
            }
        }
        return file.delete();
    }

    private static String fileDocumentId(String name) {
        return FILE_PREFIX + name;
    }

    private static void enforceAllowed() {
        if (denyAccess) {
            throw new SecurityException("Test provider access denied");
        }
    }
}
