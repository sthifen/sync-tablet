package dev.synctablet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/** Portada de la página: manchas de color suaves que se mueven despacio, en claro u oscuro. */
final class AuroraView extends View {
    private static final float[][] BLOBS = {
        // x, y (0..1), radio (fracción del lado mayor), velocidad, fase
        {0.18f, 0.22f, 0.55f, 0.00011f, 0.0f},
        {0.82f, 0.30f, 0.50f, 0.00009f, 1.7f},
        {0.50f, 0.85f, 0.60f, 0.00007f, 3.1f},
    };
    private static final int[] LIGHT = {0x998FC6F2, 0x88F5C9A6, 0x80C3B5F2};
    private static final int[] DARK = {0x8834628F, 0x77704C33, 0x774A3C7A};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long start = SystemClock.uptimeMillis();
    private final boolean dark;
    private boolean running = true;

    AuroraView(Context c, NoteTheme t) {
        super(c);
        dark = t.dark;
    }

    void setRunning(boolean r) {
        running = r;
        if (r) postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.clipRect(0, 0, w, h);
        canvas.drawColor(dark ? 0xFF22262B : 0xFFE9F1F8);
        float t = SystemClock.uptimeMillis() - start;
        float side = Math.max(w, h);
        int[] colors = dark ? DARK : LIGHT;
        for (int i = 0; i < BLOBS.length; i++) {
            float[] b = BLOBS[i];
            float a = t * b[3] * 6.2832f + b[4];
            float cx = w * (b[0] + 0.06f * (float) Math.cos(a));
            float cy = h * (b[1] + 0.05f * (float) Math.sin(a * 1.3f));
            float r = side * b[2];
            paint.setShader(new RadialGradient(cx, cy, r, colors[i], colors[i] & 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, paint);
        }
        paint.setShader(null);
        if (running) postInvalidateDelayed(33);
    }
}
