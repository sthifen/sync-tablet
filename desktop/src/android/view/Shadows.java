package android.view;

import android.graphics.Canvas;
import android.graphics.Outline;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/** Las sombras de la elevación (Material): una difusa alrededor y otra que cae hacia abajo. */
final class Shadows {
    private static final Map<String, BufferedImage> CACHE = new LinkedHashMap<String, BufferedImage>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) { return size() > 96; }
    };

    static void draw(Canvas c, View v, float z) {
        Outline o = v.outline();
        if (o == null || o.alpha <= 0) return;
        int w = o.rect.width(), h = o.rect.height();
        if (w <= 0 || h <= 0) return;
        Graphics2D g = c.graphics();
        AffineTransform t = g.getTransform();
        double scale = Math.sqrt(Math.abs(t.getDeterminant()));
        float blur = Math.min(z, 48 * v.getResources().getDisplayMetrics().density) * 0.9f;
        int pad = (int) Math.ceil(blur * 2) + 2;
        float alpha = Math.min(1f, o.alpha);
        float radius = o.path != null ? 0 : o.radius;
        String key = w + "x" + h + "r" + radius + "b" + Math.round(blur) + "a" + Math.round(alpha * 20) + "s" + Math.round(scale * 4);
        BufferedImage img;
        synchronized (CACHE) { img = CACHE.get(key); }
        if (img == null) {
            int s = (int) Math.max(1, Math.round(scale));
            int iw = (w + pad * 2) * s, ih = (h + pad * 2) * s;
            if ((long) iw * ih > 8_000_000L) return;
            BufferedImage base = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D bg = base.createGraphics();
            bg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            bg.scale(s, s);
            bg.setColor(new java.awt.Color(0, 0, 0, Math.round(255 * 0.30f * alpha)));
            bg.fill(new RoundRectangle2D.Float(pad, pad, w, h, radius * 2, radius * 2));
            bg.dispose();
            img = blur > 0.5f ? blurImage(base, blur * s) : base;
            synchronized (CACHE) { CACHE.put(key, img); }
        }
        AffineTransform keep = g.getTransform();
        float dy = z * 0.45f;
        g.translate(o.rect.left - pad, o.rect.top - pad + dy);
        double s = (double) (w + pad * 2) / img.getWidth();
        g.scale(s, s);
        g.drawImage(img, 0, 0, null);
        g.setTransform(keep);
    }

    private static BufferedImage blurImage(BufferedImage src, float r) { return android.graphics.Blur.blur(src, r); }

    private Shadows() {}
}
