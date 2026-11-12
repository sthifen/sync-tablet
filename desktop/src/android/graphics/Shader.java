package android.graphics;

public class Shader {
    public enum TileMode { CLAMP, REPEAT, MIRROR, DECAL }

    final Matrix local = new Matrix();

    public void setLocalMatrix(Matrix m) { local.set(m); }
    public boolean getLocalMatrix(Matrix m) { m.set(local); return !local.isIdentity(); }

    /** Lo que pinta Java2D con este degradado; `alpha` de 0 a 255 multiplica los colores. */
    public java.awt.Paint awt(int alpha) { return new java.awt.Color(0, 0, 0, 0); }

    static java.awt.Color[] colors(int[] cs, int alpha) {
        java.awt.Color[] out = new java.awt.Color[cs.length];
        for (int i = 0; i < cs.length; i++) {
            int a = ((cs[i] >>> 24) * alpha + 127) / 255;
            out[i] = new java.awt.Color((cs[i] >> 16) & 0xFF, (cs[i] >> 8) & 0xFF, cs[i] & 0xFF, a);
        }
        return out;
    }

    /** Fracciones crecientes como pide Java2D. */
    static float[] fractions(float[] pos, int n) {
        float[] f = new float[n];
        for (int i = 0; i < n; i++) f[i] = pos != null && i < pos.length ? pos[i] : (n == 1 ? 0 : i / (float) (n - 1));
        for (int i = 0; i < n; i++) {
            f[i] = Math.max(0, Math.min(1, f[i]));
            if (i > 0 && f[i] <= f[i - 1]) f[i] = Math.min(1, f[i - 1] + 1e-5f);
        }
        // Si se pegaron al 1, se abren hacia atrás.
        for (int i = n - 2; i >= 0; i--) if (f[i] >= f[i + 1]) f[i] = f[i + 1] - 1e-5f;
        return f;
    }

    static java.awt.MultipleGradientPaint.CycleMethod cycle(TileMode t) {
        switch (t) {
            case REPEAT: return java.awt.MultipleGradientPaint.CycleMethod.REPEAT;
            case MIRROR: return java.awt.MultipleGradientPaint.CycleMethod.REFLECT;
            default: return java.awt.MultipleGradientPaint.CycleMethod.NO_CYCLE;
        }
    }
}
