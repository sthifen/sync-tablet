package android.opengl;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashMap;

/**
 * OpenGL ES 2 por software, sólo lo que usa el visor 3D: un programa con
 * luz de dos focos y brillo (el de ModelActivity), triángulos con buffer de
 * profundidad.
 */
public class GLES20 {
    public static final int GL_ARRAY_BUFFER = 0x8892, GL_ELEMENT_ARRAY_BUFFER = 0x8893, GL_STATIC_DRAW = 0x88E4, GL_DYNAMIC_DRAW = 0x88E8,
            GL_FLOAT = 0x1406, GL_UNSIGNED_SHORT = 0x1403, GL_UNSIGNED_INT = 0x1405, GL_TRIANGLES = 4, GL_LINES = 1, GL_POINTS = 0,
            GL_COLOR_BUFFER_BIT = 0x4000, GL_DEPTH_BUFFER_BIT = 0x100, GL_DEPTH_TEST = 0x0B71, GL_CULL_FACE = 0x0B44, GL_BLEND = 0x0BE2,
            GL_VERTEX_SHADER = 0x8B31, GL_FRAGMENT_SHADER = 0x8B30, GL_COMPILE_STATUS = 0x8B81, GL_LINK_STATUS = 0x8B82, GL_TRUE = 1, GL_FALSE = 0,
            GL_SRC_ALPHA = 0x302, GL_ONE_MINUS_SRC_ALPHA = 0x303, GL_LEQUAL = 0x203, GL_LESS = 0x201, GL_BACK = 0x405, GL_CCW = 0x901;

    static int width = 1, height = 1;
    static int[] color = new int[1];
    static float[] depth = new float[1];
    private static float cr, cg, cb, ca = 1;
    private static boolean depthTest;
    private static int next = 1, boundArray;
    private static final HashMap<Integer, float[]> buffers = new HashMap<>();
    private static final HashMap<String, Integer> uniformIds = new HashMap<>();
    private static final HashMap<Integer, float[]> uniforms = new HashMap<>();
    private static final int[][] attrib = new int[8][4];
    private static final int[] attribBuffer = new int[8];

    static synchronized void surface(int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
        color = new int[width * height];
        depth = new float[width * height];
    }

    public static void glViewport(int x, int y, int w, int h) { if (w != width || h != height) surface(w, h); }
    public static void glClearColor(float r, float g, float b, float a) { cr = r; cg = g; cb = b; ca = a; }
    public static void glClear(int mask) {
        if ((mask & GL_COLOR_BUFFER_BIT) != 0) java.util.Arrays.fill(color, rgb(cr, cg, cb));
        if ((mask & GL_DEPTH_BUFFER_BIT) != 0) java.util.Arrays.fill(depth, Float.MAX_VALUE);
    }
    public static void glEnable(int cap) { if (cap == GL_DEPTH_TEST) depthTest = true; }
    public static void glDisable(int cap) { if (cap == GL_DEPTH_TEST) depthTest = false; }
    public static void glDepthFunc(int f) {}
    public static void glBlendFunc(int s, int d) {}
    public static void glCullFace(int m) {}
    public static void glFrontFace(int m) {}
    public static void glLineWidth(float w) {}
    public static int glGetError() { return 0; }

    public static int glCreateShader(int type) { return next++; }
    public static void glShaderSource(int s, String src) {}
    public static void glCompileShader(int s) {}
    public static void glGetShaderiv(int s, int p, int[] out, int o) { out[o] = GL_TRUE; }
    public static String glGetShaderInfoLog(int s) { return ""; }
    public static void glDeleteShader(int s) {}
    public static int glCreateProgram() { return next++; }
    public static void glAttachShader(int p, int s) {}
    public static void glBindAttribLocation(int p, int index, String name) {}
    public static void glLinkProgram(int p) {}
    public static void glGetProgramiv(int p, int pname, int[] out, int o) { out[o] = GL_TRUE; }
    public static String glGetProgramInfoLog(int p) { return ""; }
    public static void glUseProgram(int p) {}
    public static void glDeleteProgram(int p) {}
    public static int glGetAttribLocation(int p, String name) { return "nor".equals(name) ? 1 : 0; }

    public static int glGetUniformLocation(int p, String name) {
        synchronized (uniformIds) { return uniformIds.computeIfAbsent(name, k -> uniformIds.size() + 1); }
    }

    private static String uniformName(int id) {
        synchronized (uniformIds) { for (java.util.Map.Entry<String, Integer> e : uniformIds.entrySet()) if (e.getValue() == id) return e.getKey(); }
        return "";
    }

    public static void glUniformMatrix4fv(int loc, int count, boolean transpose, float[] v, int o) { float[] m = new float[16]; System.arraycopy(v, o, m, 0, 16); uniforms.put(loc, m); }
    public static void glUniform3f(int loc, float x, float y, float z) { uniforms.put(loc, new float[] {x, y, z}); }
    public static void glUniform4f(int loc, float x, float y, float z, float w) { uniforms.put(loc, new float[] {x, y, z, w}); }
    public static void glUniform1f(int loc, float x) { uniforms.put(loc, new float[] {x}); }
    public static void glUniform1i(int loc, int x) { uniforms.put(loc, new float[] {x}); }

    public static void glGenBuffers(int n, int[] ids, int o) { for (int i = 0; i < n; i++) ids[o + i] = next++; }
    public static void glDeleteBuffers(int n, int[] ids, int o) { for (int i = 0; i < n; i++) buffers.remove(ids[o + i]); }
    public static void glBindBuffer(int target, int id) { if (target == GL_ARRAY_BUFFER) boundArray = id; }

    public static void glBufferData(int target, int size, Buffer data, int usage) {
        if (target != GL_ARRAY_BUFFER) return;
        float[] f = new float[size / 4];
        if (data instanceof FloatBuffer) { FloatBuffer fb = ((FloatBuffer) data).duplicate(); fb.position(0); fb.get(f, 0, Math.min(f.length, fb.remaining())); }
        else if (data instanceof ByteBuffer) { ByteBuffer bb = ((ByteBuffer) data).duplicate(); bb.position(0); bb.asFloatBuffer().get(f, 0, Math.min(f.length, bb.remaining() / 4)); }
        buffers.put(boundArray, f);
    }

    public static void glEnableVertexAttribArray(int i) {}
    public static void glDisableVertexAttribArray(int i) {}
    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, int offset) {
        attrib[index] = new int[] {size, stride / 4, offset / 4};
        attribBuffer[index] = boundArray;
    }

    private static float[] uniform(String name, float[] def) {
        Integer id;
        synchronized (uniformIds) { id = uniformIds.get(name); }
        float[] v = id == null ? null : uniforms.get(id);
        return v == null ? def : v;
    }

    private static int rgb(float r, float g, float b) {
        int R = Math.max(0, Math.min(255, Math.round(r * 255))), G = Math.max(0, Math.min(255, Math.round(g * 255))), B = Math.max(0, Math.min(255, Math.round(b * 255)));
        return 0xFF000000 | R << 16 | G << 8 | B;
    }

    public static void glDrawArrays(int mode, int first, int count) {
        if (mode != GL_TRIANGLES) return;
        float[] pos = buffers.get(attribBuffer[0]), nor = buffers.get(attribBuffer[1]);
        if (pos == null) return;
        float[] mvp = uniform("mvp", identity()), model = uniform("model", identity()), col = uniform("color", new float[] {0.7f, 0.7f, 0.7f});
        int ps = attrib[0][1], po = attrib[0][2], ns = attrib[1][1], no = attrib[1][2];
        float[] sx = new float[3], sy = new float[3], sz = new float[3], nx = new float[3], ny = new float[3], nz = new float[3];
        for (int t = first; t + 2 < first + count; t += 3) {
            boolean clipped = false;
            for (int k = 0; k < 3; k++) {
                int v = t + k;
                int pi = v * ps + po;
                if (pi + 2 >= pos.length) return;
                float x = pos[pi], y = pos[pi + 1], z = pos[pi + 2];
                float cx = mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12];
                float cy = mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13];
                float cz = mvp[2] * x + mvp[6] * y + mvp[10] * z + mvp[14];
                float cw = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15];
                if (cw <= 1e-6f) { clipped = true; break; }
                sx[k] = (cx / cw * 0.5f + 0.5f) * width;
                sy[k] = (1 - (cy / cw * 0.5f + 0.5f)) * height;
                sz[k] = cz / cw;
                if (nor != null) {
                    int ni = v * ns + no;
                    float a = nor[ni], b = nor[ni + 1], c = nor[ni + 2];
                    nx[k] = model[0] * a + model[4] * b + model[8] * c;
                    ny[k] = model[1] * a + model[5] * b + model[9] * c;
                    nz[k] = model[2] * a + model[6] * b + model[10] * c;
                }
            }
            if (clipped) continue;
            raster(sx, sy, sz, nx, ny, nz, col);
        }
    }

    private static float[] identity() { float[] m = new float[16]; Matrix.setIdentityM(m, 0); return m; }

    private static final float L1X, L1Y, L1Z, L2X, L2Y, L2Z, HX, HY, HZ;
    static {
        float a = (float) Math.sqrt(0.25 + 0.64 + 1);
        L1X = 0.5f / a; L1Y = 0.8f / a; L1Z = 1 / a;
        float b = (float) Math.sqrt(0.49 + 0.04 + 0.16);
        L2X = -0.7f / b; L2Y = 0.2f / b; L2Z = -0.4f / b;
        float hx = L1X, hy = L1Y, hz = L1Z + 1, h = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
        HX = hx / h; HY = hy / h; HZ = hz / h;
    }

    private static void raster(float[] x, float[] y, float[] z, float[] nx, float[] ny, float[] nz, float[] col) {
        float area = (x[1] - x[0]) * (y[2] - y[0]) - (x[2] - x[0]) * (y[1] - y[0]);
        if (area == 0) return;
        boolean front = area < 0;
        int minX = Math.max(0, (int) Math.floor(Math.min(x[0], Math.min(x[1], x[2]))));
        int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(x[0], Math.max(x[1], x[2]))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y[0], Math.min(y[1], y[2]))));
        int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(y[0], Math.max(y[1], y[2]))));
        if (minX > maxX || minY > maxY) return;
        float inv = 1f / area;
        for (int py = minY; py <= maxY; py++) {
            float fy = py + 0.5f;
            for (int px = minX; px <= maxX; px++) {
                float fx = px + 0.5f;
                float w0 = ((x[1] - fx) * (y[2] - fy) - (x[2] - fx) * (y[1] - fy)) * inv;
                float w1 = ((x[2] - fx) * (y[0] - fy) - (x[0] - fx) * (y[2] - fy)) * inv;
                float w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                float d = w0 * z[0] + w1 * z[1] + w2 * z[2];
                if (d < -1 || d > 1) continue;
                int i = py * width + px;
                if (depthTest && d >= depth[i]) continue;
                depth[i] = d;
                float a = w0 * nx[0] + w1 * nx[1] + w2 * nx[2], b = w0 * ny[0] + w1 * ny[1] + w2 * ny[2], c = w0 * nz[0] + w1 * nz[1] + w2 * nz[2];
                float len = (float) Math.sqrt(a * a + b * b + c * c);
                if (len > 0) { a /= len; b /= len; c /= len; }
                if (!front) { a = -a; b = -b; c = -c; }
                float diff = Math.max(a * L1X + b * L1Y + c * L1Z, 0) * 0.75f + Math.max(a * L2X + b * L2Y + c * L2Z, 0) * 0.25f + 0.22f;
                float sp = (float) Math.pow(Math.max(a * HX + b * HY + c * HZ, 0), 40) * 0.35f;
                color[i] = rgb(col[0] * diff + sp, col[1] * diff + sp, col[2] * diff + sp);
            }
        }
    }
}
