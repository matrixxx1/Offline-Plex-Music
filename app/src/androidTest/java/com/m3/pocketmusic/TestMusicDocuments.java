package com.m3.pocketmusic;

import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Test-only SAF provider. Java keeps this standalone test process independent of app Kotlin classes. */
public class TestMusicDocuments extends DocumentsProvider {
    private File root() { File f = new File(getContext().getFilesDir(), "test-music-documents"); f.mkdirs(); return f; }
    private File file(String id) {
        if (id.equals("root")) return root();
        if (id.contains("/") || id.contains("\\") || id.equals(".") || id.equals("..")) throw new IllegalArgumentException();
        return new File(root(), id);
    }
    @Override public boolean onCreate() {
        getContext().grantUriPermission("com.m3.pocketmusic", DocumentsContract.buildTreeDocumentUri("com.m3.pocketmusic.test.documents", "root"),
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return true;
    }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(new String[]{Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS});
        c.addRow(new Object[]{"root", "root", "Test music", Root.FLAG_SUPPORTS_CREATE}); return c;
    }
    private Cursor documents(File[] files, String[] projection) {
        String[] columns = projection != null ? projection : new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE};
        MatrixCursor c = new MatrixCursor(columns);
        if (files != null) for (File f : files) if (f.exists()) {
            MatrixCursor.RowBuilder row = c.newRow();
            for (String column : columns) {
                switch (column) {
                    case Document.COLUMN_DOCUMENT_ID: row.add(f.equals(root()) ? "root" : f.getName()); break;
                    case Document.COLUMN_DISPLAY_NAME: row.add(f.getName()); break;
                    case Document.COLUMN_MIME_TYPE: row.add(f.isDirectory() ? Document.MIME_TYPE_DIR : "audio/wav"); break;
                    case Document.COLUMN_FLAGS: row.add(f.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME); break;
                    case Document.COLUMN_SIZE: row.add(f.length()); break;
                    default: row.add(null);
                }
            }
        }
        return c;
    }
    @Override public Cursor queryDocument(String id, String[] projection) { return documents(new File[]{file(id)}, projection); }
    @Override public Cursor queryChildDocuments(String id, String[] projection, String sort) { return documents(file(id).listFiles(), projection); }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (!parent.equals("root")) throw new FileNotFoundException();
        try { if (!file(name).createNewFile()) throw new IOException(); return name; }
        catch (IOException e) { throw new FileNotFoundException(name); }
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException { if (!file(id).delete()) throw new FileNotFoundException(id); }
    @Override public String renameDocument(String id, String name) throws FileNotFoundException {
        if (!file(id).renameTo(file(name))) throw new FileNotFoundException(id); return name;
    }
    @Override public boolean isChildDocument(String parent, String id) { return parent.equals("root") && root().equals(file(id).getParentFile()); }
}
