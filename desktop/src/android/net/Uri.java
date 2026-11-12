package android.net;

import java.io.File;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public abstract class Uri implements Comparable<Uri>, android.os.Parcelable {
    public static final Uri EMPTY = new Simple("");

    private static final class Simple extends Uri {
        final String s;
        Simple(String s) { this.s = s; }
        @Override public String toString() { return s; }
    }

    public static Uri parse(String s) { return new Simple(s); }
    public static Uri fromFile(File f) { return new Simple(f.toURI().toString().replaceFirst("^file:/(?!/)", "file:///")); }
    public static Uri withAppendedPath(Uri base, String seg) { String b = base.toString(); return parse(b.endsWith("/") ? b + encode(seg) : b + "/" + encode(seg)); }
    public static Uri fromParts(String scheme, String ssp, String fragment) { return parse(scheme + ":" + ssp + (fragment == null ? "" : "#" + fragment)); }

    public static String encode(String s) { return encode(s, null); }
    public static String encode(String s, String allow) {
        if (s == null) return null;
        StringBuilder b = new StringBuilder();
        for (byte by : s.getBytes(StandardCharsets.UTF_8)) {
            int c = by & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "_-!.~'()*".indexOf(c) >= 0 || (allow != null && allow.indexOf(c) >= 0)) b.append((char) c);
            else b.append('%').append(String.format("%02X", c));
        }
        return b.toString();
    }
    public static String decode(String s) { if (s == null) return null; try { return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"); } catch (Exception e) { return s; } }

    public String getScheme() { String s = toString(); int i = s.indexOf(':'); int sl = s.indexOf('/'); return i > 0 && (sl < 0 || i < sl) ? s.substring(0, i) : null; }
    public boolean isAbsolute() { return getScheme() != null; }
    public boolean isRelative() { return !isAbsolute(); }
    public boolean isHierarchical() { String s = toString(); String sc = getScheme(); return sc == null || s.startsWith(sc + ":/"); }
    public boolean isOpaque() { return !isHierarchical(); }

    private URI uri() { try { return new URI(toString().replace(" ", "%20")); } catch (Exception e) { return null; } }

    public String getPath() {
        URI u = uri();
        if (u != null) return u.getPath();
        String s = toString();
        int i = s.indexOf("://");
        if (i >= 0) { int j = s.indexOf('/', i + 3); return j < 0 ? "" : decode(s.substring(j).replaceAll("[?#].*$", "")); }
        return s;
    }
    public String getEncodedPath() { URI u = uri(); return u == null ? null : u.getRawPath(); }
    public String getAuthority() { URI u = uri(); return u == null ? null : u.getAuthority(); }
    public String getHost() { URI u = uri(); return u == null ? null : u.getHost(); }
    public int getPort() { URI u = uri(); return u == null ? -1 : u.getPort(); }
    public String getQuery() { URI u = uri(); return u == null ? null : u.getQuery(); }
    public String getFragment() { URI u = uri(); return u == null ? null : u.getFragment(); }
    public String getSchemeSpecificPart() { URI u = uri(); return u == null ? toString() : u.getSchemeSpecificPart(); }
    public String getUserInfo() { URI u = uri(); return u == null ? null : u.getUserInfo(); }

    public List<String> getPathSegments() {
        ArrayList<String> out = new ArrayList<>();
        String p = getPath();
        if (p == null) return out;
        for (String s : p.split("/")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    public String getLastPathSegment() { List<String> s = getPathSegments(); return s.isEmpty() ? null : s.get(s.size() - 1); }

    public String getQueryParameter(String key) {
        String q = getQuery();
        if (q == null) return null;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i);
            if (k.equals(key)) return i < 0 ? "" : kv.substring(i + 1);
        }
        return null;
    }

    public java.util.Set<String> getQueryParameterNames() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        String q = getQuery();
        if (q != null) for (String kv : q.split("&")) { int i = kv.indexOf('='); out.add(i < 0 ? kv : kv.substring(0, i)); }
        return out;
    }

    public Builder buildUpon() { return new Builder().raw(toString()); }

    /** Para el escritorio: el archivo de un file://, o null. */
    public File file() {
        String sc = getScheme();
        if (sc == null) return new File(toString());
        if (sc.equals("file")) return new File(getPath());
        return null;
    }

    @Override public int compareTo(Uri o) { return toString().compareTo(o.toString()); }
    @Override public boolean equals(Object o) { return o instanceof Uri && o.toString().equals(toString()); }
    @Override public int hashCode() { return toString().hashCode(); }

    public static final class Builder {
        private String scheme, authority, path = "", query, fragment, raw;
        Builder raw(String r) { raw = r; return this; }
        public Builder scheme(String s) { scheme = s; raw = null; return this; }
        public Builder authority(String a) { authority = a; raw = null; return this; }
        public Builder encodedAuthority(String a) { return authority(a); }
        public Builder path(String p) { path = p; raw = null; return this; }
        public Builder encodedPath(String p) { return path(p); }
        public Builder appendPath(String p) { if (raw != null) { raw = raw + (raw.endsWith("/") ? "" : "/") + encode(p); return this; } path = path + (path.endsWith("/") ? "" : "/") + encode(p); return this; }
        public Builder appendEncodedPath(String p) { if (raw != null) { raw = raw + "/" + p; return this; } path = path + "/" + p; return this; }
        public Builder appendQueryParameter(String k, String v) {
            String kv = encode(k) + "=" + encode(v);
            if (raw != null) { raw = raw + (raw.contains("?") ? "&" : "?") + kv; return this; }
            query = query == null ? kv : query + "&" + kv;
            return this;
        }
        public Builder clearQuery() { query = null; return this; }
        public Builder fragment(String f) { fragment = f; return this; }
        public Builder encodedQuery(String q) { query = q; return this; }
        public Uri build() {
            if (raw != null) return parse(raw);
            StringBuilder b = new StringBuilder();
            if (scheme != null) b.append(scheme).append(':');
            if (authority != null) b.append("//").append(authority);
            b.append(path);
            if (query != null) b.append('?').append(query);
            if (fragment != null) b.append('#').append(fragment);
            return parse(b.toString());
        }
    }
}
