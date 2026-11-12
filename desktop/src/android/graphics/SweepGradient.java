package android.graphics;

import java.awt.PaintContext;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Rectangle2D;
import java.awt.image.ColorModel;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/** Degradado angular: 0° a la derecha y en el sentido de las agujas del reloj, como en Android. */
public class SweepGradient extends Shader {
    private final float cx, cy;
    private final int[] colors;
    private final float[] positions;

    public SweepGradient(float cx, float cy, int[] colors, float[] positions) {
        this.cx = cx; this.cy = cy; this.colors = colors.clone(); this.positions = positions == null ? null : positions.clone();
    }

    public SweepGradient(float cx, float cy, int color0, int color1) { this(cx, cy, new int[] {color0, color1}, null); }

    @Override
    public java.awt.Paint awt(int alpha) {
        final int n = colors.length;
        final float[] f = new float[n];
        for (int i = 0; i < n; i++) f[i] = positions != null ? positions[i] : (n == 1 ? 0 : i / (float) (n - 1));
        final int[] cs = new int[n];
        for (int i = 0; i < n; i++) {
            int a = ((colors[i] >>> 24) * alpha + 127) / 255;
            cs[i] = (a << 24) | (colors[i] & 0xFFFFFF);
        }
        final AffineTransform lm = local.awt();
        return new java.awt.Paint() {
            @Override public int getTransparency() { return TRANSLUCENT; }
            @Override public PaintContext createContext(ColorModel cm, Rectangle db, Rectangle2D ub, AffineTransform xform, RenderingHints hints) {
                AffineTransform full = new AffineTransform(xform);
                full.concatenate(lm);
                AffineTransform inv;
                try { inv = full.createInverse(); } catch (NoninvertibleTransformException e) { inv = new AffineTransform(); }
                final AffineTransform inverse = inv;
                final ColorModel model = new DirectColorModel(32, 0xFF0000, 0xFF00, 0xFF, 0xFF000000);
                return new PaintContext() {
                    @Override public void dispose() {}
                    @Override public ColorModel getColorModel() { return model; }
                    @Override public Raster getRaster(int x, int y, int w, int h) {
                        WritableRaster r = model.createCompatibleWritableRaster(w, h);
                        int[] px = new int[w * h];
                        double[] pt = new double[2];
                        for (int j = 0; j < h; j++) {
                            for (int i = 0; i < w; i++) {
                                pt[0] = x + i + 0.5; pt[1] = y + j + 0.5;
                                inverse.transform(pt, 0, pt, 0, 1);
                                double a = Math.atan2(pt[1] - cy, pt[0] - cx);
                                float t = (float) (a < 0 ? a + 2 * Math.PI : a) / (float) (2 * Math.PI);
                                px[j * w + i] = sample(t);
                            }
                        }
                        r.setDataElements(0, 0, w, h, px);
                        return r;
                    }

                    private int sample(float t) {
                        if (n == 1 || t <= f[0]) return cs[0];
                        for (int k = 1; k < n; k++) {
                            if (t <= f[k]) {
                                float u = f[k] == f[k - 1] ? 0 : (t - f[k - 1]) / (f[k] - f[k - 1]);
                                return lerp(cs[k - 1], cs[k], u);
                            }
                        }
                        return cs[n - 1];
                    }
                };
            }
        };
    }

    static int lerp(int a, int b, float u) {
        int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * u) << 24) | ((int) (ar + (br - ar) * u) << 16) | ((int) (ag + (bg - ag) * u) << 8) | (int) (ab + (bb - ab) * u);
    }
}
