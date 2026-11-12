package android.webkit;

public class MimeTypeMap {
    private static final MimeTypeMap INSTANCE = new MimeTypeMap();
    public static MimeTypeMap getSingleton() { return INSTANCE; }
    public static String getFileExtensionFromUrl(String url) { int q = url.indexOf('?'); if (q >= 0) url = url.substring(0, q); int d = url.lastIndexOf('.'); int s = url.lastIndexOf('/'); return d > s ? url.substring(d + 1) : ""; }
    public String getMimeTypeFromExtension(String ext) { return ext == null ? null : android.content.ContentResolver.mimeOf("x." + ext); }
    public String getExtensionFromMimeType(String mime) {
        if (mime == null) return null;
        switch (mime) {
            case "application/pdf": return "pdf";
            case "image/png": return "png";
            case "image/jpeg": return "jpg";
            case "image/webp": return "webp";
            case "text/plain": return "txt";
            case "text/markdown": return "md";
            case "text/csv": return "csv";
            case "application/zip": return "zip";
            case "application/json": return "json";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document": return "docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": return "xlsx";
            case "application/vnd.oasis.opendocument.spreadsheet": return "ods";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation": return "pptx";
            default: return null;
        }
    }
    public boolean hasExtension(String e) { return getMimeTypeFromExtension(e) != null; }
}
