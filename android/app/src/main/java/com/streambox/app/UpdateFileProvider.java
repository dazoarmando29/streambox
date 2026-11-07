package com.streambox.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves downloaded update APKs to the system package installer as
 * content:// URIs (never file:// - that is refused on API 24+).
 *
 * Deliberately NOT androidx FileProvider: this app has zero dependencies,
 * and the contract is tiny. Trust rules:
 * - exported=false, grantUriPermissions=true (the install intent grants
 *   read to the installer only, for the duration of the install)
 * - openFile serves READ-ONLY files from the app's private updates/ dir
 *   and NOTHING else (path traversal is rejected on the canonical path)
 */
public class UpdateFileProvider extends ContentProvider {
    private static final String AUTH_SUFFIX = ".updates";

    static String authority(android.content.Context ctx) {
        return ctx.getPackageName() + AUTH_SUFFIX;
    }

    private File updatesDir() {
        return new File(getContext().getFilesDir(), "updates");
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty() || name.contains("..") || name.contains("/")) {
            throw new FileNotFoundException("bad path");
        }
        File dir = updatesDir();
        File f = new File(dir, name);
        try {
            String base = dir.getCanonicalPath();
            String path = f.getCanonicalPath();
            if (!path.startsWith(base + File.separator)) throw new FileNotFoundException("outside updates dir");
        } catch (java.io.IOException e) {
            throw new FileNotFoundException("unresolvable path");
        }
        if (!f.isFile()) throw new FileNotFoundException("no such file");
        return f;
    }

    @Override
    public boolean onCreate() { return true; }

    @Override
    public String getType(Uri uri) { return "application/vnd.android.package-archive"; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read-only");
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] proj, String sel, String[] selArgs, String sort) { return null; }

    @Override
    public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read-only"); }

    @Override
    public int delete(Uri uri, String sel, String[] selArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String sel, String[] selArgs) { return 0; }
}
