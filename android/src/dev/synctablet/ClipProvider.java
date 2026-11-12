package dev.synctablet;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * La imagen copiada en el PC. Android guarda las imágenes del portapapeles
 * como URI content://, así que se deja aquí (sólo la última) y cualquier app
 * donde pegues la lee, como cualquier cosa del portapapeles.
 */
public final class ClipProvider extends ContentProvider {
    static Uri save(Context c, byte[] png) throws IOException {
        File dir = dir(c);
        File[] old = dir.listFiles();
        if (old != null) for (File o : old) o.delete();
        String name = "clip-" + System.currentTimeMillis() + ".png";
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            out.write(png);
        }
        return Uri.parse("content://" + c.getPackageName() + ".clip/" + name);
    }

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "clip");
        d.mkdirs();
        return d;
    }

    private File file(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("clip-[0-9]+\\.png")) throw new FileNotFoundException(uri.toString());
        return new File(dir(getContext()), name);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode.contains("w")) throw new SecurityException("sólo lectura");
        File f = file(uri);
        if (!f.exists()) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f;
        try {
            f = file(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        MatrixCursor c = new MatrixCursor(cols, 1);
        c.addRow(row);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }
}
