package android.content;

import android.database.Cursor;
import android.net.Uri;

/** En el escritorio no hay otras apps que lean de aquí; sólo para compilar. */
public abstract class ContentProvider {
    private Context context;
    public final Context getContext() { return context != null ? context : android.app.Application.get(); }
    public abstract boolean onCreate();
    public android.os.ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException { throw new java.io.FileNotFoundException(); }
    public abstract String getType(Uri uri);
    public abstract Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort);
    public abstract Uri insert(Uri uri, ContentValues values);
    public abstract int update(Uri uri, ContentValues values, String selection, String[] args);
    public abstract int delete(Uri uri, String selection, String[] args);
}
