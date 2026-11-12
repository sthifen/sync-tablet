package android.content;

public class ClipDescription {
    public static final String MIMETYPE_TEXT_PLAIN = "text/plain", MIMETYPE_TEXT_HTML = "text/html", MIMETYPE_TEXT_URILIST = "text/uri-list";
    private final CharSequence label;
    private final String[] mimes;
    public ClipDescription(CharSequence label, String[] mimes) { this.label = label; this.mimes = mimes; }
    public CharSequence getLabel() { return label; }
    public boolean hasMimeType(String m) { for (String s : mimes) if (s.equals(m) || (m.endsWith("/*") && s.startsWith(m.substring(0, m.length() - 1)))) return true; return false; }
    public int getMimeTypeCount() { return mimes.length; }
    public String getMimeType(int i) { return mimes[i]; }
}
