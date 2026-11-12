package android.text;

import java.util.ArrayList;
import java.util.Iterator;

public class TextUtils {
    public enum TruncateAt { START, MIDDLE, END, MARQUEE, END_SMALL }

    public interface EllipsizeCallback { void ellipsized(int start, int end); }

    public static final int CAP_MODE_CHARACTERS = 0x1000, CAP_MODE_WORDS = 0x2000, CAP_MODE_SENTENCES = 0x4000;

    public static boolean isEmpty(CharSequence s) { return s == null || s.length() == 0; }
    public static boolean equals(CharSequence a, CharSequence b) {
        if (a == b) return true;
        if (a == null || b == null || a.length() != b.length()) return false;
        if (a instanceof String && b instanceof String) return a.equals(b);
        for (int i = 0; i < a.length(); i++) if (a.charAt(i) != b.charAt(i)) return false;
        return true;
    }
    public static String join(CharSequence delim, Object[] tokens) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) { if (i > 0) b.append(delim); b.append(tokens[i]); }
        return b.toString();
    }
    public static String join(CharSequence delim, Iterable<?> tokens) {
        StringBuilder b = new StringBuilder();
        Iterator<?> it = tokens.iterator();
        if (it.hasNext()) { b.append(it.next()); while (it.hasNext()) { b.append(delim); b.append(it.next()); } }
        return b.toString();
    }
    public static String[] split(String text, String expression) { return text.isEmpty() ? new String[0] : text.split(expression, -1); }
    public static int getTrimmedLength(CharSequence s) {
        int len = s.length(), start = 0;
        while (start < len && s.charAt(start) <= ' ') start++;
        int end = len;
        while (end > start && s.charAt(end - 1) <= ' ') end--;
        return end - start;
    }
    public static boolean isDigitsOnly(CharSequence s) { for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false; return true; }
    public static boolean isGraphic(CharSequence s) { for (int i = 0; i < s.length(); i++) if (!Character.isWhitespace(s.charAt(i))) return true; return false; }
    public static String htmlEncode(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '&': b.append("&amp;"); break;
                case '\'': b.append("&#39;"); break;
                case '"': b.append("&quot;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }
    public static CharSequence concat(CharSequence... text) {
        boolean spanned = false;
        for (CharSequence t : text) if (t instanceof Spanned) { spanned = true; break; }
        if (!spanned) { StringBuilder b = new StringBuilder(); for (CharSequence t : text) b.append(t); return b.toString(); }
        SpannableStringBuilder b = new SpannableStringBuilder();
        for (CharSequence t : text) b.append(t);
        return new SpannedString(b);
    }
    public static int indexOf(CharSequence s, char ch) { return s.toString().indexOf(ch); }
    public static int indexOf(CharSequence s, char ch, int start) { return s.toString().indexOf(ch, start); }
    public static int indexOf(CharSequence s, CharSequence needle) { return s.toString().indexOf(needle.toString()); }
    public static int lastIndexOf(CharSequence s, char ch) { return s.toString().lastIndexOf(ch); }
    public static CharSequence stringOrSpannedString(CharSequence s) { if (s == null) return null; if (s instanceof SpannedString) return s; if (s instanceof Spanned) return new SpannedString(s); return s.toString(); }
    public static String substring(CharSequence s, int start, int end) { return s.subSequence(start, end).toString(); }
    public static void getChars(CharSequence s, int start, int end, char[] dest, int off) { for (int i = start; i < end; i++) dest[off++] = s.charAt(i); }
    public static CharSequence commaEllipsize(CharSequence text, TextPaint p, float avail, String oneMore, String more) { return ellipsize(text, p, avail, TruncateAt.END); }
    public static int getCapsMode(CharSequence cs, int off, int reqModes) { return 0; }
    public static String nullIfEmpty(String s) { return isEmpty(s) ? null : s; }
    public static int getLayoutDirectionFromLocale(java.util.Locale l) { return 0; }
    public static CharSequence listEllipsize(Object ctx, java.util.List<CharSequence> elements, String sep, TextPaint p, float avail, int more) { return join(sep, elements); }

    public static CharSequence ellipsize(CharSequence text, TextPaint p, float avail, TruncateAt where) { return ellipsize(text, p, avail, where, false, null); }

    public static CharSequence ellipsize(CharSequence text, TextPaint p, float avail, TruncateAt where, boolean preserveLength, EllipsizeCallback cb) {
        String s = text.toString();
        if (p.measureText(s) <= avail) { if (cb != null) cb.ellipsized(0, 0); return text; }
        String dots = "…";
        float dw = p.measureText(dots);
        float room = avail - dw;
        if (room <= 0) { if (cb != null) cb.ellipsized(0, s.length()); return ""; }
        int n = s.length();
        if (where == TruncateAt.START) {
            int keep = p.breakText(s, false, room, null);
            if (cb != null) cb.ellipsized(0, n - keep);
            return dots + s.substring(n - keep);
        }
        if (where == TruncateAt.MIDDLE) {
            int left = p.breakText(s, true, room / 2, null);
            int right = p.breakText(s, false, room / 2, null);
            if (cb != null) cb.ellipsized(left, n - right);
            return s.substring(0, left) + dots + s.substring(n - right);
        }
        int keep = p.breakText(s, true, room, null);
        if (keep > 0 && Character.isHighSurrogate(s.charAt(keep - 1))) keep--;
        if (cb != null) cb.ellipsized(keep, n);
        if (text instanceof Spanned) {
            SpannableStringBuilder b = new SpannableStringBuilder(text, 0, keep);
            b.append(dots);
            return b;
        }
        return s.substring(0, keep) + dots;
    }

    public static ArrayList<String> words(String s) { ArrayList<String> w = new ArrayList<>(); for (String x : s.split("\\s+")) if (!x.isEmpty()) w.add(x); return w; }

    public static class SimpleStringSplitter implements Iterable<String>, Iterator<String> {
        private final char delim;
        private String s;
        private int pos;
        public SimpleStringSplitter(char d) { delim = d; }
        public void setString(String s) { this.s = s; pos = 0; }
        @Override public Iterator<String> iterator() { return this; }
        @Override public boolean hasNext() { return s != null && pos <= s.length() && !(pos == s.length() && s.isEmpty()); }
        @Override public String next() {
            int end = s.indexOf(delim, pos);
            if (end < 0) end = s.length();
            String r = s.substring(pos, end);
            pos = end + 1;
            return r;
        }
    }
}
