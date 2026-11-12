package android.opengl;

/** Matrices 4x4 por columnas, como android.opengl.Matrix. */
public class Matrix {
    public static void multiplyMM(float[] r, int ro, float[] a, int ao, float[] b, int bo) {
        float[] t = new float[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                float s = 0;
                for (int k = 0; k < 4; k++) s += a[ao + k * 4 + i] * b[bo + j * 4 + k];
                t[j * 4 + i] = s;
            }
        }
        System.arraycopy(t, 0, r, ro, 16);
    }

    public static void multiplyMV(float[] r, int ro, float[] m, int mo, float[] v, int vo) {
        float x = v[vo], y = v[vo + 1], z = v[vo + 2], w = v[vo + 3];
        for (int i = 0; i < 4; i++) r[ro + i] = m[mo + i] * x + m[mo + 4 + i] * y + m[mo + 8 + i] * z + m[mo + 12 + i] * w;
    }

    public static void setIdentityM(float[] m, int o) { for (int i = 0; i < 16; i++) m[o + i] = 0; for (int i = 0; i < 16; i += 5) m[o + i] = 1; }

    public static void translateM(float[] m, int o, float x, float y, float z) {
        for (int i = 0; i < 4; i++) m[o + 12 + i] += m[o + i] * x + m[o + 4 + i] * y + m[o + 8 + i] * z;
    }

    public static void scaleM(float[] m, int o, float x, float y, float z) {
        for (int i = 0; i < 4; i++) { m[o + i] *= x; m[o + 4 + i] *= y; m[o + 8 + i] *= z; }
    }

    public static void setRotateM(float[] rm, int o, float a, float x, float y, float z) {
        setIdentityM(rm, o);
        a = (float) Math.toRadians(a);
        float s = (float) Math.sin(a), c = (float) Math.cos(a);
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len == 0) return;
        x /= len; y /= len; z /= len;
        float nc = 1 - c, xy = x * y, yz = y * z, zx = z * x, xs = x * s, ys = y * s, zs = z * s;
        rm[o] = x * x * nc + c; rm[o + 4] = xy * nc - zs; rm[o + 8] = zx * nc + ys;
        rm[o + 1] = xy * nc + zs; rm[o + 5] = y * y * nc + c; rm[o + 9] = yz * nc - xs;
        rm[o + 2] = zx * nc - ys; rm[o + 6] = yz * nc + xs; rm[o + 10] = z * z * nc + c;
    }

    public static void rotateM(float[] m, int o, float a, float x, float y, float z) {
        float[] r = new float[16], t = new float[16];
        setRotateM(r, 0, a, x, y, z);
        multiplyMM(t, 0, m, o, r, 0);
        System.arraycopy(t, 0, m, o, 16);
    }

    public static void perspectiveM(float[] m, int o, float fovy, float aspect, float zNear, float zFar) {
        float f = 1.0f / (float) Math.tan(fovy * (Math.PI / 360.0));
        float range = 1.0f / (zNear - zFar);
        for (int i = 0; i < 16; i++) m[o + i] = 0;
        m[o] = f / aspect;
        m[o + 5] = f;
        m[o + 10] = (zFar + zNear) * range;
        m[o + 11] = -1.0f;
        m[o + 14] = 2.0f * zFar * zNear * range;
    }

    public static void frustumM(float[] m, int o, float l, float r, float b, float t, float n, float f) {
        for (int i = 0; i < 16; i++) m[o + i] = 0;
        m[o] = 2 * n / (r - l); m[o + 5] = 2 * n / (t - b); m[o + 8] = (r + l) / (r - l); m[o + 9] = (t + b) / (t - b);
        m[o + 10] = -(f + n) / (f - n); m[o + 11] = -1; m[o + 14] = -2 * f * n / (f - n);
    }

    public static void orthoM(float[] m, int o, float l, float r, float b, float t, float n, float f) {
        for (int i = 0; i < 16; i++) m[o + i] = 0;
        m[o] = 2 / (r - l); m[o + 5] = 2 / (t - b); m[o + 10] = -2 / (f - n);
        m[o + 12] = -(r + l) / (r - l); m[o + 13] = -(t + b) / (t - b); m[o + 14] = -(f + n) / (f - n); m[o + 15] = 1;
    }

    public static void setLookAtM(float[] rm, int o, float ex, float ey, float ez, float cx, float cy, float cz, float ux, float uy, float uz) {
        float fx = cx - ex, fy = cy - ey, fz = cz - ez;
        float rlf = 1.0f / (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx *= rlf; fy *= rlf; fz *= rlf;
        float sx = fy * uz - fz * uy, sy = fz * ux - fx * uz, sz = fx * uy - fy * ux;
        float rls = 1.0f / (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        sx *= rls; sy *= rls; sz *= rls;
        float ux2 = sy * fz - sz * fy, uy2 = sz * fx - sx * fz, uz2 = sx * fy - sy * fx;
        rm[o] = sx; rm[o + 1] = ux2; rm[o + 2] = -fx; rm[o + 3] = 0;
        rm[o + 4] = sy; rm[o + 5] = uy2; rm[o + 6] = -fy; rm[o + 7] = 0;
        rm[o + 8] = sz; rm[o + 9] = uz2; rm[o + 10] = -fz; rm[o + 11] = 0;
        rm[o + 12] = 0; rm[o + 13] = 0; rm[o + 14] = 0; rm[o + 15] = 1;
        translateM(rm, o, -ex, -ey, -ez);
    }

    public static boolean invertM(float[] inv, int io, float[] m, int mo) {
        float[] a = new float[16];
        System.arraycopy(m, mo, a, 0, 16);
        float[] r = new float[16];
        r[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10];
        r[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10];
        r[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9];
        r[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9];
        r[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10];
        r[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10];
        r[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9];
        r[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9];
        r[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6];
        r[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6];
        r[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5];
        r[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5];
        r[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6];
        r[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6];
        r[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5];
        r[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5];
        float det = a[0] * r[0] + a[1] * r[4] + a[2] * r[8] + a[3] * r[12];
        if (det == 0) return false;
        for (int i = 0; i < 16; i++) inv[io + i] = r[i] / det;
        return true;
    }

    public static void transposeM(float[] t, int to, float[] m, int mo) {
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) t[to + i * 4 + j] = m[mo + j * 4 + i];
    }
}
