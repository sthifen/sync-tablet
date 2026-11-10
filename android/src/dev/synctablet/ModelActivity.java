package dev.synctablet;

import android.app.Activity;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Visor de modelos 3D (STL binario o de texto, y OBJ): un dedo gira, dos
 * mueven, pellizcar acerca, doble toque centra. Todo en la tablet, con la GPU.
 */
public final class ModelActivity extends Activity {
    private GLSurfaceView view;
    private Renderer renderer;
    private TextView info;
    private NoteTheme t;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        t = new NoteTheme(this);
        FrameLayout root = new FrameLayout(this);
        view = new GLSurfaceView(this);
        view.setEGLContextClientVersion(2);
        view.setEGLConfigChooser(8, 8, 8, 8, 16, 4);
        renderer = new Renderer(t.dark);
        view.setRenderer(renderer);
        view.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 16), 0);
        bar.setBackground(Ui.round(t.dark ? 0xE6262626 : 0xF2FFFFFF, Ui.dp(this, 26)));
        bar.setElevation(Ui.dp(this, 8));
        IconView back = new IconView(this, IconView.BACK, t.icon, "Volver");
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        info = Ui.text(this, "Abriendo…", 15, t.text, false);
        bar.addView(info);
        IconView reset = new IconView(this, IconView.EXPAND, t.icon, "Centrar");
        reset.setOnClickListener(v -> {
            renderer.reset();
            view.requestRender();
        });
        bar.addView(reset);
        IconView help = new IconView(this, IconView.HELP, t.icon, "Cómo se usa");
        bar.addView(help);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, Ui.dp(this, 52), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        blp.topMargin = Ui.dp(this, 16);
        root.addView(bar, blp);
        OptionDock dock = new OptionDock(this, root, null, t.dark ? 0xF7262626 : 0xF7FFFFFF, t.dark);
        help.setOnClickListener(v -> Tutorial.show(this, dock, "model"));
        Ui.fitSystemBars(root, 0, 0, 0, 0);
        setContentView(root);
        gestures();
        Uri uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }
        String name = name(uri);
        new Thread(() -> {
            float[] tris = null;
            String error = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16];
                for (int k; (k = in.read(buf)) > 0; ) bytes.write(buf, 0, k);
                byte[] data = bytes.toByteArray();
                tris = name.toLowerCase(Locale.ROOT).endsWith(".obj") ? obj(data) : stl(data);
                smooth(tris, 35);
            } catch (java.io.IOException | RuntimeException | OutOfMemoryError e) {
                error = e.getMessage();
            }
            float[] mesh = tris;
            String err = error;
            runOnUiThread(() -> {
                if (mesh == null || mesh.length == 0) {
                    info.setText("No se pudo leer el modelo" + (err == null ? "" : ": " + err));
                    return;
                }
                info.setText(name + " · " + String.format(Locale.ROOT, "%,d", mesh.length / 18) + " triángulos");
                view.queueEvent(() -> renderer.load(mesh));
                view.requestRender();
                Tutorial.offerOnce(this, dock, "model");
            });
        }, "modelo").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        view.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) Ui.applyFullscreen(this);
    }

    private String name(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, new String[] {android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
        }
        String p = uri.getLastPathSegment();
        return p == null ? "modelo" : p;
    }

    private void gestures() {
        ScaleGestureDetector pinch = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                renderer.zoom = Math.max(0.2f, Math.min(8f, renderer.zoom * d.getScaleFactor()));
                view.requestRender();
                return true;
            }
        });
        GestureDetector taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) {
                renderer.reset();
                view.requestRender();
                return true;
            }
        });
        float[] last = new float[3];
        view.setOnTouchListener((v, e) -> {
            pinch.onTouchEvent(e);
            taps.onTouchEvent(e);
            int n = e.getPointerCount();
            float x = 0, y = 0;
            for (int i = 0; i < n; i++) {
                x += e.getX(i) / n;
                y += e.getY(i) / n;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_POINTER_UP:
                    last[0] = x;
                    last[1] = y;
                    last[2] = n;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (n != (int) last[2]) {
                        last[0] = x;
                        last[1] = y;
                        last[2] = n;
                        break;
                    }
                    float dx = x - last[0], dy = y - last[1];
                    if (n == 1) {
                        renderer.yaw += dx * 0.4f;
                        renderer.pitch = Math.max(-89, Math.min(89, renderer.pitch + dy * 0.4f));
                    } else {
                        float k = 2.4f / (renderer.zoom * v.getHeight());
                        renderer.panX += dx * k;
                        renderer.panY -= dy * k;
                    }
                    last[0] = x;
                    last[1] = y;
                    view.requestRender();
                    break;
                default:
                    break;
            }
            return true;
        });
    }

    // ---------------------------------------------------------------- archivos

    /** STL: triángulos como x,y,z,nx,ny,nz por vértice. */
    static float[] stl(byte[] d) {
        boolean ascii = d.length > 5 && new String(d, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equalsIgnoreCase("solid");
        if (ascii && d.length > 84) {
            // Algunos STL binarios empiezan por "solid": se comprueba el tamaño.
            long count = ByteBuffer.wrap(d, 80, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
            if (84 + count * 50 == d.length) ascii = false;
        }
        if (!ascii) {
            ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
            int count = b.getInt(80);
            if (count < 0 || 84L + count * 50L > d.length) throw new IllegalArgumentException("STL incompleto");
            float[] out = new float[count * 18];
            for (int i = 0; i < count; i++) {
                int at = 84 + i * 50;
                float[] v = new float[9];
                for (int k = 0; k < 9; k++) v[k] = b.getFloat(at + 12 + k * 4);
                tri(out, i * 18, v);
            }
            return out;
        }
        ArrayList<Float> vs = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new StringReader(new String(d, java.nio.charset.StandardCharsets.US_ASCII)))) {
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                if (!line.startsWith("vertex")) continue;
                String[] p = line.split("\\s+");
                for (int k = 1; k <= 3; k++) vs.add(Float.parseFloat(p[k]));
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException(e);
        }
        int count = vs.size() / 9;
        float[] out = new float[count * 18];
        float[] v = new float[9];
        for (int i = 0; i < count; i++) {
            for (int k = 0; k < 9; k++) v[k] = vs.get(i * 9 + k);
            tri(out, i * 18, v);
        }
        return out;
    }

    /** OBJ: vértices y caras (los polígonos se parten en triángulos). */
    static float[] obj(byte[] d) throws java.io.IOException {
        ArrayList<float[]> verts = new ArrayList<>();
        ArrayList<Float> tris = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.ByteArrayInputStream(d), java.nio.charset.StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                if (line.startsWith("v ")) {
                    String[] p = line.split("\\s+");
                    verts.add(new float[] {Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
                } else if (line.startsWith("f ")) {
                    String[] p = line.split("\\s+");
                    int[] idx = new int[p.length - 1];
                    for (int k = 1; k < p.length; k++) {
                        int i = Integer.parseInt(p[k].split("/")[0]);
                        idx[k - 1] = i < 0 ? verts.size() + i : i - 1;
                    }
                    for (int k = 1; k + 1 < idx.length; k++) {
                        for (int j : new int[] {idx[0], idx[k], idx[k + 1]}) {
                            float[] v = verts.get(j);
                            tris.add(v[0]);
                            tris.add(v[1]);
                            tris.add(v[2]);
                        }
                    }
                }
            }
        }
        int count = tris.size() / 9;
        float[] out = new float[count * 18];
        float[] v = new float[9];
        for (int i = 0; i < count; i++) {
            for (int k = 0; k < 9; k++) v[k] = tris.get(i * 9 + k);
            tri(out, i * 18, v);
        }
        return out;
    }

    /**
     * Normales suaves: en cada vértice se promedian las caras que forman menos de
     * `maxDeg` grados con la suya; las aristas vivas de una pieza siguen marcadas.
     */
    static void smooth(float[] m, float maxDeg) {
        int n = m.length / 6;
        if (n == 0 || n > 3_000_000) return;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        for (int i = 0; i < m.length; i += 6) {
            minX = Math.min(minX, Math.min(m[i], Math.min(m[i + 1], m[i + 2])));
            maxX = Math.max(maxX, Math.max(m[i], Math.max(m[i + 1], m[i + 2])));
        }
        float q = Math.max(1e-6f, (maxX - minX) * 1e-5f);
        java.util.HashMap<Long, java.util.ArrayList<Integer>> at = new java.util.HashMap<>();
        for (int v = 0; v < n; v++) {
            long key = (Math.round(m[v * 6] / q) * 73856093L) ^ (Math.round(m[v * 6 + 1] / q) * 19349663L) ^ (Math.round(m[v * 6 + 2] / q) * 83492791L);
            at.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(v);
        }
        float cos = (float) Math.cos(Math.toRadians(maxDeg));
        float[] face = new float[n * 3];
        for (int v = 0; v < n; v++) System.arraycopy(m, v * 6 + 3, face, v * 3, 3);
        for (java.util.ArrayList<Integer> same : at.values()) {
            if (same.size() < 2) continue;
            for (int v : same) {
                float sx = 0, sy = 0, sz = 0;
                for (int o : same) {
                    float d = face[v * 3] * face[o * 3] + face[v * 3 + 1] * face[o * 3 + 1] + face[v * 3 + 2] * face[o * 3 + 2];
                    if (d >= cos) {
                        sx += face[o * 3];
                        sy += face[o * 3 + 1];
                        sz += face[o * 3 + 2];
                    }
                }
                float len = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
                if (len > 0) {
                    m[v * 6 + 3] = sx / len;
                    m[v * 6 + 4] = sy / len;
                    m[v * 6 + 5] = sz / len;
                }
            }
        }
    }

    private static void tri(float[] out, int at, float[] v) {
        float ux = v[3] - v[0], uy = v[4] - v[1], uz = v[5] - v[2], wx = v[6] - v[0], wy = v[7] - v[1], wz = v[8] - v[2];
        float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 0) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        for (int k = 0; k < 3; k++) {
            out[at + k * 6] = v[k * 3];
            out[at + k * 6 + 1] = v[k * 3 + 1];
            out[at + k * 6 + 2] = v[k * 3 + 2];
            out[at + k * 6 + 3] = nx;
            out[at + k * 6 + 4] = ny;
            out[at + k * 6 + 5] = nz;
        }
    }

    // ---------------------------------------------------------------- GPU

    private static final class Renderer implements GLSurfaceView.Renderer {
        private static final String VS = "uniform mat4 mvp; uniform mat4 model; attribute vec3 pos; attribute vec3 nor; varying vec3 n; varying vec3 p;"
                + "void main(){ n = mat3(model) * nor; p = (model * vec4(pos,1.0)).xyz; gl_Position = mvp * vec4(pos,1.0); }";
        private static final String FS = "precision mediump float; varying vec3 n; varying vec3 p; uniform vec3 color;"
                + "void main(){ vec3 nn = normalize(n); if (!gl_FrontFacing) nn = -nn;"
                + " vec3 l1 = normalize(vec3(0.5,0.8,1.0)); vec3 l2 = normalize(vec3(-0.7,0.2,-0.4));"
                + " float d = max(dot(nn,l1),0.0)*0.75 + max(dot(nn,l2),0.0)*0.25 + 0.22;"
                + " vec3 h = normalize(l1 + vec3(0.0,0.0,1.0)); float s = pow(max(dot(nn,h),0.0), 40.0)*0.35;"
                + " gl_FragColor = vec4(color*d + vec3(s), 1.0); }";
        private final boolean dark;
        private int program, count, vbo;
        private float[] pending, loaded;
        private final float[] proj = new float[16], viewM = new float[16], model = new float[16], mvp = new float[16], tmp = new float[16];
        private float cx, cy, cz, scale = 1;
        volatile float yaw = 30, pitch = 20, zoom = 1, panX, panY;
        private float aspect = 1;

        Renderer(boolean dark) {
            this.dark = dark;
        }

        void reset() {
            yaw = 30;
            pitch = 20;
            zoom = 1;
            panX = panY = 0;
        }

        void load(float[] mesh) {
            loaded = mesh;
            pending = mesh;
        }

        private static int shader(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            return s;
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, VS));
            GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, FS));
            GLES20.glBindAttribLocation(program, 0, "pos");
            GLES20.glBindAttribLocation(program, 1, "nor");
            GLES20.glLinkProgram(program);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            vbo = 0;
            count = 0;
            // Al volver de otra app la GPU olvidó el modelo: se sube otra vez.
            if (loaded != null) pending = loaded;
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int w, int h) {
            GLES20.glViewport(0, 0, w, h);
            aspect = w / (float) Math.max(1, h);
        }

        private void upload(float[] mesh) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int i = 0; i < mesh.length; i += 6) {
                minX = Math.min(minX, mesh[i]);
                maxX = Math.max(maxX, mesh[i]);
                minY = Math.min(minY, mesh[i + 1]);
                maxY = Math.max(maxY, mesh[i + 1]);
                minZ = Math.min(minZ, mesh[i + 2]);
                maxZ = Math.max(maxZ, mesh[i + 2]);
            }
            cx = (minX + maxX) / 2;
            cy = (minY + maxY) / 2;
            cz = (minZ + maxZ) / 2;
            float size = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
            scale = size > 0 ? 2f / size : 1;
            FloatBuffer fb = ByteBuffer.allocateDirect(mesh.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            fb.put(mesh).position(0);
            int[] ids = new int[1];
            if (vbo != 0) GLES20.glDeleteBuffers(1, new int[] {vbo}, 0);
            GLES20.glGenBuffers(1, ids, 0);
            vbo = ids[0];
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mesh.length * 4, fb, GLES20.GL_STATIC_DRAW);
            count = mesh.length / 6;
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            if (pending != null) {
                upload(pending);
                pending = null;
            }
            if (dark) GLES20.glClearColor(0.10f, 0.11f, 0.12f, 1);
            else GLES20.glClearColor(0.95f, 0.95f, 0.94f, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            if (count == 0) return;
            Matrix.perspectiveM(proj, 0, 40, aspect, 0.05f, 100);
            Matrix.setLookAtM(viewM, 0, 0, 0, 4.2f / zoom, 0, 0, 0, 0, 1, 0);
            Matrix.translateM(viewM, 0, panX, panY, 0);
            Matrix.setIdentityM(model, 0);
            Matrix.rotateM(model, 0, pitch, 1, 0, 0);
            Matrix.rotateM(model, 0, yaw, 0, 1, 0);
            // Los modelos de CAD suelen venir con Z hacia arriba.
            Matrix.rotateM(model, 0, -90, 1, 0, 0);
            Matrix.scaleM(model, 0, scale, scale, scale);
            Matrix.translateM(model, 0, -cx, -cy, -cz);
            Matrix.multiplyMM(tmp, 0, viewM, 0, model, 0);
            Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0);
            GLES20.glUseProgram(program);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "mvp"), 1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "model"), 1, false, tmp, 0);
            GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "color"), 0.18f, 0.62f, 0.58f);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
            GLES20.glEnableVertexAttribArray(0);
            GLES20.glEnableVertexAttribArray(1);
            GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 24, 0);
            GLES20.glVertexAttribPointer(1, 3, GLES20.GL_FLOAT, false, 24, 12);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count);
        }
    }
}
