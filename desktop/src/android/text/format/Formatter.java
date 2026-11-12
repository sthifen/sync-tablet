package android.text.format;

public final class Formatter {
    public static String formatFileSize(android.content.Context c, long bytes) {
        if (bytes < 1000) return bytes + " B";
        String[] u = {"kB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        while (v >= 1000 && i < u.length - 1) { v /= 1000; i++; }
        return (v < 10 ? String.format("%.2f", v) : v < 100 ? String.format("%.1f", v) : String.format("%.0f", v)) + " " + u[i];
    }
    public static String formatShortFileSize(android.content.Context c, long bytes) { return formatFileSize(c, bytes); }
}
