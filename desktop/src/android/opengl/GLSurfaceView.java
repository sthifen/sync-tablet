package android.opengl;

import android.content.Context;
import android.graphics.Canvas;
import java.awt.image.BufferedImage;
import java.util.concurrent.ConcurrentLinkedQueue;

/** La superficie 3D: el renderizador dibuja con GLES20 por software y la imagen se pinta en la vista. */
public class GLSurfaceView extends android.view.View {
    public static final int RENDERMODE_WHEN_DIRTY = 0, RENDERMODE_CONTINUOUSLY = 1, DEBUG_CHECK_GL_ERROR = 1;

    public interface Renderer {
        void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl, javax.microedition.khronos.egl.EGLConfig config);
        void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl, int width, int height);
        void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl);
    }

    private Renderer renderer;
    private int mode = RENDERMODE_CONTINUOUSLY;
    private boolean created;
    private int lastW = -1, lastH = -1;
    private BufferedImage image;
    private final ConcurrentLinkedQueue<Runnable> events = new ConcurrentLinkedQueue<>();

    public GLSurfaceView(Context c) { super(c); }
    public GLSurfaceView(Context c, android.util.AttributeSet a) { super(c); }

    public void setEGLContextClientVersion(int v) {}
    public void setEGLConfigChooser(int r, int g, int b, int a, int depth, int stencil) {}
    public void setEGLConfigChooser(boolean depth) {}
    public void setPreserveEGLContextOnPause(boolean p) {}
    public void setDebugFlags(int f) {}
    public void setRenderer(Renderer r) { renderer = r; }
    public void setRenderMode(int m) { mode = m; invalidate(); }
    public int getRenderMode() { return mode; }
    public void requestRender() { postInvalidate(); }
    public void queueEvent(Runnable r) { events.add(r); postInvalidate(); }
    public void onPause() {}
    public void onResume() { invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        if (renderer == null) return;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        for (Runnable r; (r = events.poll()) != null; ) r.run();
        synchronized (GLES20.class) {
            if (!created) { created = true; GLES20.surface(w, h); renderer.onSurfaceCreated(null, null); }
            if (w != lastW || h != lastH) { lastW = w; lastH = h; GLES20.surface(w, h); renderer.onSurfaceChanged(null, w, h); }
            if (GLES20.width != w || GLES20.height != h) GLES20.surface(w, h);
            renderer.onDrawFrame(null);
            if (image == null || image.getWidth() != w || image.getHeight() != h) image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, w, h, GLES20.color, 0, w);
        }
        c.graphics().drawImage(image, 0, 0, null);
        if (mode == RENDERMODE_CONTINUOUSLY) postInvalidateOnAnimation();
    }
}
