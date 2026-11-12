package android.graphics;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Map;
import java.util.WeakHashMap;

/** Copias de mapas de bits con un filtro de color o encogidas, guardadas mientras no cambie el original. */
final class Filtered {
    private static final class Entry {
        final Object key;
        final int generation;
        final BufferedImage img;
        Entry(Object key, int generation, BufferedImage img) { this.key = key; this.generation = generation; this.img = img; }
    }

    private static final Map<Bitmap, Entry> FILTERED = new WeakHashMap<>();
    private static final Map<BufferedImage, BufferedImage[]> SMALL = new WeakHashMap<>();

    static synchronized BufferedImage apply(Bitmap b, ColorFilter f) {
        Entry e = FILTERED.get(b);
        Object key = f instanceof PorterDuffColorFilter ? f : f;
        if (e != null && e.key == key && e.generation == b.getGenerationId()) return e.img;
        int w = b.getWidth(), h = b.getHeight();
        int[] src = new int[w * h];
        b.image.getRGB(0, 0, w, h, src, 0, w);
        for (int i = 0; i < src.length; i++) src[i] = f.filter(src[i]);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, w, h, src, 0, w);
        FILTERED.put(b, new Entry(key, b.getGenerationId(), out));
        return out;
    }

    /** Una versión encogida a la mitad tantas veces como haga falta para `scale`. */
    static synchronized BufferedImage downscaled(BufferedImage img, double scale) {
        int halvings = 0;
        while (scale < 0.5 && halvings < 6) { scale *= 2; halvings++; }
        if (halvings == 0) return img;
        BufferedImage[] chain = SMALL.get(img);
        if (chain == null || chain.length < halvings) {
            BufferedImage[] next = new BufferedImage[halvings];
            if (chain != null) System.arraycopy(chain, 0, next, 0, chain.length);
            chain = next;
            SMALL.put(img, chain);
        }
        BufferedImage cur = img;
        for (int i = 0; i < halvings; i++) {
            if (chain[i] == null) {
                int w = Math.max(1, cur.getWidth() / 2), h = Math.max(1, cur.getHeight() / 2);
                BufferedImage half = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
                Graphics2D g = half.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(cur, 0, 0, w, h, null);
                g.dispose();
                chain[i] = half;
            }
            cur = chain[i];
        }
        return cur;
    }

    private Filtered() {}
}
