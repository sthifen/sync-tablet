package android.media;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Sólo lo que hace falta: la orientación de una foto JPEG. */
public class ExifInterface {
    public static final String TAG_ORIENTATION = "Orientation";
    public static final int ORIENTATION_UNDEFINED = 0, ORIENTATION_NORMAL = 1, ORIENTATION_FLIP_HORIZONTAL = 2, ORIENTATION_ROTATE_180 = 3,
            ORIENTATION_FLIP_VERTICAL = 4, ORIENTATION_TRANSPOSE = 5, ORIENTATION_ROTATE_90 = 6, ORIENTATION_TRANSVERSE = 7, ORIENTATION_ROTATE_270 = 8;

    private int orientation = ORIENTATION_UNDEFINED;

    public ExifInterface(InputStream in) throws IOException { parse(in); }
    public ExifInterface(String path) throws IOException { try (InputStream in = new java.io.FileInputStream(path)) { parse(in); } }
    public ExifInterface(java.io.File f) throws IOException { this(f.getPath()); }
    public ExifInterface(java.io.FileDescriptor fd) throws IOException {}

    private void parse(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        if (in.readUnsignedShort() != 0xFFD8) return;
        while (true) {
            int marker;
            try { marker = in.readUnsignedShort(); } catch (IOException e) { return; }
            if ((marker & 0xFF00) != 0xFF00) return;
            int len = in.readUnsignedShort() - 2;
            if (len < 0) return;
            if (marker == 0xFFE1) {
                byte[] seg = new byte[len];
                in.readFully(seg);
                readExif(seg);
                return;
            }
            if (marker == 0xFFDA) return;
            in.skipBytes(len);
        }
    }

    private void readExif(byte[] b) {
        if (b.length < 14 || b[0] != 'E' || b[1] != 'x' || b[2] != 'i' || b[3] != 'f') return;
        int t = 6;
        boolean le = b[t] == 'I';
        int ifd = u32(b, t + 4, le);
        int p = t + ifd;
        if (p + 2 > b.length) return;
        int n = u16(b, p, le);
        for (int i = 0; i < n; i++) {
            int e = p + 2 + i * 12;
            if (e + 12 > b.length) return;
            if (u16(b, e, le) == 0x0112) { orientation = u16(b, e + 8, le); return; }
        }
    }

    private static int u16(byte[] b, int o, boolean le) { return le ? (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 : (b[o] & 0xFF) << 8 | (b[o + 1] & 0xFF); }
    private static int u32(byte[] b, int o, boolean le) { return le ? u16(b, o, true) | u16(b, o + 2, true) << 16 : u16(b, o, false) << 16 | u16(b, o + 2, false); }

    public int getAttributeInt(String tag, int def) { return TAG_ORIENTATION.equals(tag) && orientation != ORIENTATION_UNDEFINED ? orientation : def; }
    public String getAttribute(String tag) { return TAG_ORIENTATION.equals(tag) ? String.valueOf(orientation) : null; }
    public int getRotationDegrees() { return orientation == ORIENTATION_ROTATE_90 ? 90 : orientation == ORIENTATION_ROTATE_180 ? 180 : orientation == ORIENTATION_ROTATE_270 ? 270 : 0; }
}
