package android.content;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;

/** Archivos por su Uri: file:// (lo que devuelve el selector) y content://desktop/... (lo que se guarda en Descargas o Imágenes). */
public class ContentResolver {
    static final ContentResolver INSTANCE = new ContentResolver();
    public static final String SCHEME_CONTENT = "content", SCHEME_FILE = "file", SCHEME_ANDROID_RESOURCE = "android.resource";

    /** El archivo detrás de un Uri, o null. */
    public static File fileOf(Uri u) {
        if (u == null) return null;
        String s = u.getScheme();
        if (s == null || s.equals("file")) return u.file();
        if (s.equals("content") && "desktop".equals(u.getAuthority())) return new File(u.getPath());
        return null;
    }

    public final InputStream openInputStream(Uri u) throws FileNotFoundException {
        File f = fileOf(u);
        if (f != null) return new FileInputStream(f);
        String s = u.getScheme();
        if ("http".equals(s) || "https".equals(s)) {
            try { return new URL(u.toString()).openStream(); } catch (Exception e) { throw new FileNotFoundException(u.toString()); }
        }
        throw new FileNotFoundException("No se puede abrir " + u);
    }

    public final OutputStream openOutputStream(Uri u) throws FileNotFoundException { return openOutputStream(u, "w"); }

    public final OutputStream openOutputStream(Uri u, String mode) throws FileNotFoundException {
        File f = fileOf(u);
        if (f == null) throw new FileNotFoundException("No se puede escribir " + u);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        return new FileOutputStream(f, mode != null && mode.contains("a"));
    }

    public final android.os.ParcelFileDescriptor openFileDescriptor(Uri u, String mode) throws FileNotFoundException {
        File f = fileOf(u);
        if (f == null) {
            try (InputStream in = openInputStream(u)) { return new android.os.ParcelFileDescriptor(in.readAllBytes()); }
            catch (java.io.IOException e) { throw new FileNotFoundException(u.toString()); }
        }
        if (mode != null && mode.contains("w") && f.getParentFile() != null) f.getParentFile().mkdirs();
        if ((mode == null || !mode.contains("w")) && !f.exists()) throw new FileNotFoundException(f.getPath());
        return new android.os.ParcelFileDescriptor(f);
    }

    public final android.content.res.AssetFileDescriptor openAssetFileDescriptor(Uri u, String mode) throws FileNotFoundException { return new android.content.res.AssetFileDescriptor(openFileDescriptor(u, mode)); }

    public final String getType(Uri u) {
        File f = fileOf(u);
        String name = f != null ? f.getName() : u.getLastPathSegment();
        return mimeOf(name);
    }

    public static String mimeOf(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(java.util.Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot < 0 ? "" : n.substring(dot + 1);
        switch (ext) {
            case "pdf": return "application/pdf";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "heic": return "image/heic";
            case "svg": return "image/svg+xml";
            case "txt": return "text/plain";
            case "md": return "text/markdown";
            case "csv": return "text/csv";
            case "html": case "htm": return "text/html";
            case "json": return "application/json";
            case "zip": return "application/zip";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "doc": return "application/msword";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xls": return "application/vnd.ms-excel";
            case "ods": return "application/vnd.oasis.opendocument.spreadsheet";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "ppt": return "application/vnd.ms-powerpoint";
            case "stl": return "model/stl";
            case "obj": return "model/obj";
            case "xopp": return "application/x-xopp";
            case "mp4": return "video/mp4";
            default: return "application/octet-stream";
        }
    }

    public final Cursor query(Uri u, String[] projection, String selection, String[] args, String sort) {
        File f = fileOf(u);
        if (f == null) return null;
        String[] cols = projection != null ? projection : new String[] {android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            String col = cols[i];
            if (col.equals(android.provider.OpenableColumns.DISPLAY_NAME)) row[i] = f.getName();
            else if (col.equals(android.provider.OpenableColumns.SIZE)) row[i] = f.length();
            else if (col.equals("_data")) row[i] = f.getPath();
            else if (col.equals("mime_type")) row[i] = mimeOf(f.getName());
            else if (col.equals("date_modified")) row[i] = f.lastModified() / 1000;
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    public final Cursor query(Uri u, String[] projection, android.os.Bundle args, android.os.CancellationSignal cancel) { return query(u, projection, null, null, null); }

    /** MediaStore: el archivo va a Descargas, Imágenes o Documentos con su nombre (sin pisar uno que ya exista). */
    public final Uri insert(Uri collection, ContentValues v) {
        String name = v.getAsString(android.provider.MediaStore.MediaColumns.DISPLAY_NAME);
        if (name == null) name = "archivo";
        String mime = v.getAsString(android.provider.MediaStore.MediaColumns.MIME_TYPE);
        String rel = v.getAsString(android.provider.MediaStore.MediaColumns.RELATIVE_PATH);
        String type;
        String c = collection.toString();
        if (c.contains("images") || (rel != null && (rel.startsWith("Pictures") || rel.startsWith("DCIM")))) type = Environment.DIRECTORY_PICTURES;
        else if (rel != null && rel.startsWith("Documents")) type = Environment.DIRECTORY_DOCUMENTS;
        else type = Environment.DIRECTORY_DOWNLOADS;
        File dir = new File(Environment.xdg(type));
        if (rel != null) {
            // "Download/SyncTablet" → la subcarpeta, dentro de la carpeta del usuario.
            int slash = rel.indexOf('/');
            String sub = slash >= 0 ? rel.substring(slash + 1).replaceAll("/+$", "") : "";
            if (!sub.isEmpty()) dir = new File(dir, sub);
        }
        dir.mkdirs();
        if (mime != null && name.indexOf('.') < 0) {
            String ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (ext != null) name = name + "." + ext;
        }
        File f = new File(dir, name);
        int n = 1;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        while (f.exists()) f = new File(dir, base + " (" + (n++) + ")" + ext);
        return new Uri.Builder().scheme("content").authority("desktop").path(f.getPath()).build();
    }

    public final int update(Uri u, ContentValues v, String where, String[] args) { return 1; }

    public final int delete(Uri u, String where, String[] args) {
        File f = fileOf(u);
        return f != null && f.delete() ? 1 : 0;
    }

    public void takePersistableUriPermission(Uri u, int flags) {}
    public void releasePersistableUriPermission(Uri u, int flags) {}
    public java.util.List<UriPermission> getPersistedUriPermissions() { return new java.util.ArrayList<>(); }
    public void notifyChange(Uri u, Object observer) {}
    public final void registerContentObserver(Uri u, boolean descendants, Object observer) {}

    public static class UriPermission {
        public Uri getUri() { return null; }
    }
}
