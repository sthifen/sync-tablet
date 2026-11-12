package android.graphics;

public class ColorMatrix {
    final float[] m = new float[20];

    public ColorMatrix() { reset(); }
    public ColorMatrix(float[] src) { System.arraycopy(src, 0, m, 0, 20); }
    public ColorMatrix(ColorMatrix src) { System.arraycopy(src.m, 0, m, 0, 20); }

    public void reset() { java.util.Arrays.fill(m, 0); m[0] = m[6] = m[12] = m[18] = 1; }
    public void set(float[] src) { System.arraycopy(src, 0, m, 0, 20); }
    public void set(ColorMatrix src) { System.arraycopy(src.m, 0, m, 0, 20); }
    public final float[] getArray() { return m; }

    public void setScale(float r, float g, float b, float a) { java.util.Arrays.fill(m, 0); m[0] = r; m[6] = g; m[12] = b; m[18] = a; }

    public void setSaturation(float sat) {
        reset();
        float invSat = 1 - sat, R = 0.213f * invSat, G = 0.715f * invSat, B = 0.072f * invSat;
        m[0] = R + sat; m[1] = G; m[2] = B;
        m[5] = R; m[6] = G + sat; m[7] = B;
        m[10] = R; m[11] = G; m[12] = B + sat;
    }

    public void setConcat(ColorMatrix a, ColorMatrix b) {
        float[] t = new float[20];
        int index = 0;
        for (int j = 0; j < 20; j += 5) {
            for (int i = 0; i < 4; i++) {
                t[index++] = a.m[j] * b.m[i] + a.m[j + 1] * b.m[i + 5] + a.m[j + 2] * b.m[i + 10] + a.m[j + 3] * b.m[i + 15];
            }
            t[index++] = a.m[j] * b.m[4] + a.m[j + 1] * b.m[9] + a.m[j + 2] * b.m[14] + a.m[j + 3] * b.m[19] + a.m[j + 4];
        }
        System.arraycopy(t, 0, m, 0, 20);
    }

    public void preConcat(ColorMatrix pre) { setConcat(this, pre); }
    public void postConcat(ColorMatrix post) { setConcat(post, this); }
}
