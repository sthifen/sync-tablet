package android.graphics;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.HashMap;

/**
 * Emoji en color. Java no dibuja las letras de emoji en color, así que se
 * leen los dibujos (PNG) de una letra CBDT del sistema (Twemoji, Noto Color
 * Emoji antigua…) y se pintan como imágenes.
 */
public final class Emoji {
    /** Letra «marcador» de los tramos de emoji en TextKit. */
    static final String MARKER = "SyncTabletEmoji";

    private static boolean loaded;
    private static ByteBuffer cbdt;
    private static final HashMap<Integer, Integer> cmap = new HashMap<>();
    private static final HashMap<Integer, int[]> glyphs = new HashMap<>(); // glifo → {offset, len, format, bearingX, bearingY, advance}
    private static int ppem = 109;
    private static final HashMap<Integer, BufferedImage> images = new HashMap<>();

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        for (String path : candidates()) {
            try {
                if (read(Files.readAllBytes(new File(path).toPath()))) return;
            } catch (Exception e) {
                android.util.Log.w("SyncTablet", "emoji: " + path, e);
            }
            cmap.clear();
            glyphs.clear();
        }
    }

    private static java.util.List<String> candidates() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        String env = System.getenv("SYNCTABLET_EMOJI_FONT");
        if (env != null) out.add(env);
        try {
            Process p = new ProcessBuilder("fc-list", ":charset=1f600", "file").redirectErrorStream(true).start();
            for (String l : new String(p.getInputStream().readAllBytes()).split("\n")) {
                String f = l.trim();
                if (f.endsWith(":")) f = f.substring(0, f.length() - 1);
                if (!f.isEmpty()) out.add(f);
            }
        } catch (Exception ignored) {}
        for (String f : new String[] {"/usr/share/fonts/twemoji/Twemoji.ttf", "/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf", "/usr/share/fonts/noto/NotoColorEmoji.ttf"})
            if (new File(f).exists()) out.add(f);
        return out;
    }

    private static int u16(ByteBuffer b, int o) { return b.getShort(o) & 0xFFFF; }
    private static long u32(ByteBuffer b, int o) { return b.getInt(o) & 0xFFFFFFFFL; }

    private static boolean read(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data);
        int numTables = u16(b, 4);
        int cblc = -1, cbdtOff = -1, cmapOff = -1, cbdtLen = 0;
        for (int i = 0; i < numTables; i++) {
            int r = 12 + i * 16;
            String tag = new String(data, r, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            int off = (int) u32(b, r + 8), len = (int) u32(b, r + 12);
            if (tag.equals("CBLC")) cblc = off;
            else if (tag.equals("CBDT")) { cbdtOff = off; cbdtLen = len; }
            else if (tag.equals("cmap")) cmapOff = off;
        }
        if (cblc < 0 || cbdtOff < 0 || cmapOff < 0) return false;
        readCmap(b, cmapOff);
        if (cmap.isEmpty()) return false;
        // La serie de dibujos más grande.
        int numSizes = (int) u32(b, cblc + 4), best = -1, bestPpem = 0;
        for (int i = 0; i < numSizes; i++) {
            int s = cblc + 8 + i * 48;
            int p = b.get(s + 44) & 0xFF;
            if (p > bestPpem) { bestPpem = p; best = s; }
        }
        if (best < 0) return false;
        ppem = bestPpem;
        int arr = cblc + (int) u32(b, best);
        int count = (int) u32(b, best + 8);
        for (int i = 0; i < count; i++) {
            int e = arr + i * 8;
            int first = u16(b, e), last = u16(b, e + 2);
            int sub = arr + (int) u32(b, e + 4);
            int indexFormat = u16(b, sub), imageFormat = u16(b, sub + 2);
            int imageOffset = cbdtOff + (int) u32(b, sub + 4);
            int h = sub + 8;
            switch (indexFormat) {
                case 1:
                    for (int g = first; g <= last; g++) {
                        int a = (int) u32(b, h + (g - first) * 4), z = (int) u32(b, h + (g - first + 1) * 4);
                        if (z > a) glyphs.put(g, new int[] {imageOffset + a, z - a, imageFormat});
                    }
                    break;
                case 3:
                    for (int g = first; g <= last; g++) {
                        int a = u16(b, h + (g - first) * 2), z = u16(b, h + (g - first + 1) * 2);
                        if (z > a) glyphs.put(g, new int[] {imageOffset + a, z - a, imageFormat});
                    }
                    break;
                case 4: {
                    int n = (int) u32(b, h);
                    for (int k = 0; k < n; k++) {
                        int g = u16(b, h + 4 + k * 4), a = u16(b, h + 6 + k * 4), z = u16(b, h + 10 + k * 4);
                        if (z > a) glyphs.put(g, new int[] {imageOffset + a, z - a, imageFormat});
                    }
                    break;
                }
                case 2: case 5: {
                    int size = (int) u32(b, h);
                    if (indexFormat == 2) {
                        for (int g = first; g <= last; g++) glyphs.put(g, new int[] {imageOffset + (g - first) * size, size, imageFormat});
                    } else {
                        int n = (int) u32(b, h + 12);
                        for (int k = 0; k < n; k++) glyphs.put(u16(b, h + 16 + k * 2), new int[] {imageOffset + k * size, size, imageFormat});
                    }
                    break;
                }
                default:
            }
        }
        cbdt = b;
        return !glyphs.isEmpty();
    }

    private static void readCmap(ByteBuffer b, int cmapOff) {
        int n = u16(b, cmapOff + 2);
        int best = -1, bestFormat = 0;
        for (int i = 0; i < n; i++) {
            int r = cmapOff + 4 + i * 8;
            int sub = cmapOff + (int) u32(b, r + 4);
            int format = u16(b, sub);
            if (format == 12 && bestFormat != 12) { best = sub; bestFormat = 12; }
            else if (format == 4 && bestFormat == 0) { best = sub; bestFormat = 4; }
        }
        if (best < 0) return;
        if (bestFormat == 12) {
            int groups = (int) u32(b, best + 12);
            for (int i = 0; i < groups; i++) {
                int g = best + 16 + i * 12;
                long start = u32(b, g), end = u32(b, g + 4), glyph = u32(b, g + 8);
                for (long c = start; c <= end && c - start < 0x10000; c++) cmap.put((int) c, (int) (glyph + c - start));
            }
        } else {
            int segX2 = u16(b, best + 6), segs = segX2 / 2;
            int ends = best + 14, starts = ends + segX2 + 2, deltas = starts + segX2, ranges = deltas + segX2;
            for (int s = 0; s < segs; s++) {
                int end = u16(b, ends + s * 2), start = u16(b, starts + s * 2), delta = b.getShort(deltas + s * 2), ro = u16(b, ranges + s * 2);
                for (int c = start; c <= end && c != 0xFFFF; c++) {
                    int g;
                    if (ro == 0) g = (c + delta) & 0xFFFF;
                    else {
                        int addr = ranges + s * 2 + ro + (c - start) * 2;
                        g = u16(b, addr);
                        if (g != 0) g = (g + delta) & 0xFFFF;
                    }
                    if (g != 0) cmap.put(c, g);
                }
            }
        }
    }

    /** ¿Hay dibujo en color para este carácter? */
    public static boolean has(int cp) {
        load();
        Integer g = cmap.get(cp);
        return g != null && glyphs.containsKey(g) && cp > 0x7F;
    }

    /** Caracteres que no ocupan sitio: uniones, selectores de variante y tonos de piel tras un emoji. */
    static boolean invisible(int cp) { return cp == 0x200D || (cp >= 0xFE00 && cp <= 0xFE0F) || (cp >= 0x1F3FB && cp <= 0x1F3FF) || (cp >= 0xE0020 && cp <= 0xE007F); }

    public static boolean isMarker(Font f) { return MARKER.equals(f.getName()); }

    static Font marker(float size) { return new Font(MARKER, Font.PLAIN, 1).deriveFont(size); }

    /** Avance de un carácter de emoji a ese tamaño. */
    public static float advance(int cp, float size) {
        if (invisible(cp)) return 0;
        return size * 1.18f;
    }

    private static synchronized BufferedImage image(int cp) {
        BufferedImage img = images.get(cp);
        if (img != null || images.containsKey(cp)) return img;
        Integer g = cmap.get(cp);
        int[] e = g == null ? null : glyphs.get(g);
        if (e != null) {
            int at = e[0], len = e[1];
            int skip = e[2] == 17 ? 5 + 4 : e[2] == 18 ? 8 + 4 : e[2] == 19 ? 4 : -1;
            if (skip >= 0 && len > skip) {
                byte[] png = new byte[len - skip];
                for (int i = 0; i < png.length; i++) png[i] = cbdt.get(at + skip + i);
                try { img = javax.imageio.ImageIO.read(new ByteArrayInputStream(png)); } catch (Exception ignored) {}
            }
        }
        images.put(cp, img);
        return img;
    }

    /** Dibuja los emoji de `s` en la línea base; devuelve el avance. */
    static float draw(Graphics2D g, String s, float x, float y, float size) {
        float start = x;
        Object interp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            float adv = advance(cp, size);
            if (adv == 0) continue;
            BufferedImage img = image(cp);
            if (img != null) {
                // El dibujo ocupa el cuadro de un «em», un poco por debajo de la línea base.
                float box = size * 1.12f;
                float k = box / Math.max(img.getWidth(), img.getHeight());
                AffineTransform t = AffineTransform.getTranslateInstance(x + (adv - img.getWidth() * k) / 2, y - box * 0.86f);
                t.scale(k, k);
                g.drawImage(img, t, null);
            }
            x += adv;
        }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interp == null ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : interp);
        return x - start;
    }

    static float width(String s, float size) {
        float w = 0;
        for (int i = 0; i < s.length(); ) { int cp = s.codePointAt(i); i += Character.charCount(cp); w += advance(cp, size); }
        return w;
    }

    private Emoji() {}
}
