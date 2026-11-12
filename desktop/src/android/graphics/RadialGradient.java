package android.graphics;

import java.awt.geom.Point2D;

public class RadialGradient extends Shader {
    private final float cx, cy, r;
    private final int[] colors;
    private final float[] positions;
    private final TileMode tile;

    public RadialGradient(float cx, float cy, float radius, int[] colors, float[] stops, TileMode tile) {
        this.cx = cx; this.cy = cy; this.r = radius;
        this.colors = colors.clone(); this.positions = stops == null ? null : stops.clone(); this.tile = tile;
    }

    public RadialGradient(float cx, float cy, float radius, int center, int edge, TileMode tile) {
        this(cx, cy, radius, new int[] {center, edge}, null, tile);
    }

    @Override
    public java.awt.Paint awt(int alpha) {
        java.awt.Color[] c = colors(colors, alpha);
        if (c.length == 1 || r <= 0) return c[c.length - 1];
        return new java.awt.RadialGradientPaint(new Point2D.Float(cx, cy), r, new Point2D.Float(cx, cy), fractions(positions, c.length), c,
                cycle(tile), java.awt.MultipleGradientPaint.ColorSpaceType.SRGB, local.awt());
    }
}
