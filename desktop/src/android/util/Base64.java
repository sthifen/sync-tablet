package android.util;

/** android.util.Base64 sobre java.util.Base64. */
public final class Base64 {
    public static final int DEFAULT = 0, NO_PADDING = 1, NO_WRAP = 2, CRLF = 4, URL_SAFE = 8, NO_CLOSE = 16;

    public static byte[] decode(String str, int flags) { return decode(str.getBytes(java.nio.charset.StandardCharsets.US_ASCII), flags); }

    public static byte[] decode(byte[] input, int flags) {
        String s = new String(input, java.nio.charset.StandardCharsets.US_ASCII).replaceAll("[\\s]", "");
        boolean url = (flags & URL_SAFE) != 0 || s.indexOf('-') >= 0 || s.indexOf('_') >= 0;
        try {
            return (url ? java.util.Base64.getUrlDecoder() : java.util.Base64.getDecoder()).decode(s);
        } catch (IllegalArgumentException e) {
            // Sin relleno.
            while (s.length() % 4 != 0) s += "=";
            return (url ? java.util.Base64.getUrlDecoder() : java.util.Base64.getDecoder()).decode(s);
        }
    }

    public static byte[] decode(byte[] input, int offset, int len, int flags) {
        return decode(java.util.Arrays.copyOfRange(input, offset, offset + len), flags);
    }

    public static String encodeToString(byte[] input, int flags) {
        return new String(encode(input, flags), java.nio.charset.StandardCharsets.US_ASCII);
    }

    public static String encodeToString(byte[] input, int offset, int len, int flags) {
        return encodeToString(java.util.Arrays.copyOfRange(input, offset, offset + len), flags);
    }

    public static byte[] encode(byte[] input, int flags) {
        java.util.Base64.Encoder enc = (flags & URL_SAFE) != 0 ? java.util.Base64.getUrlEncoder() : java.util.Base64.getEncoder();
        if ((flags & NO_PADDING) != 0) enc = enc.withoutPadding();
        byte[] out = enc.encode(input);
        if ((flags & NO_WRAP) != 0) return out;
        // Android parte en líneas de 76 y termina con salto.
        String nl = (flags & CRLF) != 0 ? "\r\n" : "\n";
        StringBuilder b = new StringBuilder();
        String s = new String(out, java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i < s.length(); i += 76) b.append(s, i, Math.min(s.length(), i + 76)).append(nl);
        return b.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private Base64() {}
}
