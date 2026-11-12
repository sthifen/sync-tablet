package android.graphics;

import java.awt.image.BufferedImage;

/**
 * En Android, una lista de dibujo en la GPU; aquí, una imagen del tamaño del
 * nodo que se graba una vez y se dibuja tal cual (lo mismo que la textura).
 */
public final class RenderNode {
    private final String name;
    private int left, top, right, bottom;
    private Bitmap bitmap;
    private Canvas recording;
    private boolean has;
    private RenderEffect effect;
    private float alpha = 1, tx, ty;

    public RenderNode(String name) { this.name = name; }

    public boolean setPosition(int l, int t, int r, int b) { left = l; top = t; right = r; bottom = b; return true; }
    public boolean setPosition(Rect r) { return setPosition(r.left, r.top, r.right, r.bottom); }
    public int getWidth() { return right - left; }
    public int getHeight() { return bottom - top; }
    public boolean setUseCompositingLayer(boolean force, Paint p) { return true; }
    public boolean setRenderEffect(RenderEffect e) { effect = e; return true; }
    public boolean setAlpha(float a) { alpha = a; return true; }
    public boolean setTranslationX(float x) { tx = x; return true; }
    public boolean setTranslationY(float y) { ty = y; return true; }
    public boolean hasDisplayList() { return has; }
    public void discardDisplayList() { has = false; bitmap = null; }

    public Canvas beginRecording(int w, int h) {
        w = Math.max(1, w); h = Math.max(1, h);
        if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        else bitmap.eraseColor(0);
        recording = new Canvas(bitmap);
        recording.hardware = true;
        return recording;
    }

    public Canvas beginRecording() { return beginRecording(getWidth(), getHeight()); }

    public void endRecording() {
        if (recording != null) recording.release();
        recording = null;
        has = true;
    }

    void drawOn(Canvas c) {
        if (!has || bitmap == null) return;
        BufferedImage img = bitmap.image;
        if (effect != null && (effect.blurX > 0 || effect.blurY > 0)) img = Blur.blur(img, Math.max(effect.blurX, effect.blurY));
        c.drawImage(img, left + tx, top + ty, alpha);
    }
}
