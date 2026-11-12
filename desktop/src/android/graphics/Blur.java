package android.graphics;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/** Desenfoque barato: se encoge, se pasa una caja tres veces y se vuelve a estirar. */
public final class Blur {
    public static BufferedImage blur(BufferedImage src, float radius) {
        int w = src.getWidth(), h = src.getHeight();
        int down = Math.max(1, Math.round(radius / 3f));
        int sw = Math.max(1, w / down), sh = Math.max(1, h / down);
        BufferedImage small = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, sw, sh, null);
        g.dispose();
        int[] px = ((DataBufferInt) small.getRaster().getDataBuffer()).getData();
        int r = Math.max(1, Math.round(radius / down / 2f));
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < 3; pass++) {
            box(px, tmp, sw, sh, r, true);
            box(tmp, px, sw, sh, r, false);
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(small, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        int div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int v = in[idx(Math.max(0, Math.min(len - 1, i)), l, w, horizontal)];
                sa += v >>> 24; sr += (v >> 16) & 0xFF; sg += (v >> 8) & 0xFF; sb += v & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[idx(i, l, w, horizontal)] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = in[idx(Math.min(len - 1, i + r + 1), l, w, horizontal)];
                int rem = in[idx(Math.max(0, i - r), l, w, horizontal)];
                sa += (add >>> 24) - (rem >>> 24);
                sr += ((add >> 16) & 0xFF) - ((rem >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((rem >> 8) & 0xFF);
                sb += (add & 0xFF) - (rem & 0xFF);
            }
        }
    }

    private static int idx(int i, int line, int w, boolean horizontal) { return horizontal ? line * w + i : i * w + line; }

    private Blur() {}
}
