package android.graphics;

import java.awt.geom.Point2D;

public class LinearGradient extends Shader {
    private final float x0, y0, x1, y1;
    private final int[] colors;
    private final float[] positions;
    private final TileMode tile;

    public LinearGradient(float x0, float y0, float x1, float y1, int[] colors, float[] positions, TileMode tile) {
        this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
        this.colors = colors.clone(); this.positions = positions == null ? null : positions.clone(); this.tile = tile;
    }

    public LinearGradient(float x0, float y0, float x1, float y1, int color0, int color1, TileMode tile) {
        this(x0, y0, x1, y1, new int[] {color0, color1}, null, tile);
    }

    @Override
    public java.awt.Paint awt(int alpha) {
        java.awt.Color[] c = colors(colors, alpha);
        if (c.length == 1 || (x0 == x1 && y0 == y1)) return c[c.length - 1];
        if (c.length == 2 && positions == null) {
            // Dos colores: el caso común, más rápido.
        }
        return new java.awt.LinearGradientPaint(new Point2D.Float(x0, y0), new Point2D.Float(x1, y1), fractions(positions, c.length), c,
                cycle(tile), java.awt.MultipleGradientPaint.ColorSpaceType.SRGB, local.awt());
    }
}
