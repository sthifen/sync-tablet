package android.graphics.drawable;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;

public abstract class Drawable {
    public interface Callback {
        void invalidateDrawable(Drawable who);
        void scheduleDrawable(Drawable who, Runnable what, long when);
        void unscheduleDrawable(Drawable who, Runnable what);
    }

    private final Rect bounds = new Rect();
    private int[] state = new int[0];
    private java.lang.ref.WeakReference<Callback> callback;
    protected int alpha = 255;
    protected ColorFilter colorFilter;
    private int level;
    private boolean visible = true;

    public abstract void draw(Canvas canvas);

    public void setBounds(int l, int t, int r, int b) {
        if (bounds.left != l || bounds.top != t || bounds.right != r || bounds.bottom != b) {
            bounds.set(l, t, r, b);
            onBoundsChange(bounds);
        }
    }
    public void setBounds(Rect r) { setBounds(r.left, r.top, r.right, r.bottom); }
    public final Rect getBounds() { return bounds; }
    public final void copyBounds(Rect out) { out.set(bounds); }
    public Rect getDirtyBounds() { return bounds; }
    protected void onBoundsChange(Rect b) {}

    public void setAlpha(int a) { alpha = a; invalidateSelf(); }
    public int getAlpha() { return alpha; }
    public void setColorFilter(ColorFilter f) { colorFilter = f; invalidateSelf(); }
    public void setColorFilter(int color, android.graphics.PorterDuff.Mode mode) { setColorFilter(new android.graphics.PorterDuffColorFilter(color, mode)); }
    public ColorFilter getColorFilter() { return colorFilter; }
    public void clearColorFilter() { setColorFilter(null); }
    public void setTint(int color) { setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)); }
    public void setTintList(android.content.res.ColorStateList c) { if (c != null) setTint(c.getDefaultColor()); else setColorFilter(null); }
    public void setTintMode(android.graphics.PorterDuff.Mode m) {}
    public void setDither(boolean d) {}
    public void setFilterBitmap(boolean f) {}
    public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    public boolean isStateful() { return false; }
    public boolean setState(int[] s) {
        if (!java.util.Arrays.equals(state, s)) {
            state = s == null ? new int[0] : s.clone();
            return onStateChange(state);
        }
        return false;
    }
    public int[] getState() { return state; }
    protected boolean onStateChange(int[] s) { return false; }
    public void jumpToCurrentState() {}
    public Drawable getCurrent() { return this; }
    public void setHotspot(float x, float y) {}
    public void setHotspotBounds(int l, int t, int r, int b) {}

    public final boolean setLevel(int l) { if (level != l) { level = l; return onLevelChange(l); } return false; }
    public final int getLevel() { return level; }
    protected boolean onLevelChange(int l) { return false; }

    public boolean setVisible(boolean v, boolean restart) { boolean changed = visible != v; visible = v; return changed; }
    public final boolean isVisible() { return visible; }

    public int getIntrinsicWidth() { return -1; }
    public int getIntrinsicHeight() { return -1; }
    public int getMinimumWidth() { int w = getIntrinsicWidth(); return Math.max(w, 0); }
    public int getMinimumHeight() { int h = getIntrinsicHeight(); return Math.max(h, 0); }
    public boolean getPadding(Rect padding) { padding.set(0, 0, 0, 0); return false; }
    public void getOutline(android.graphics.Outline o) { o.setRect(bounds); o.setAlpha(0); }
    public Drawable mutate() { return this; }

    public final void setCallback(Callback cb) { callback = cb == null ? null : new java.lang.ref.WeakReference<>(cb); }
    public Callback getCallback() { return callback == null ? null : callback.get(); }
    public void invalidateSelf() { Callback cb = getCallback(); if (cb != null) cb.invalidateDrawable(this); }
    public void scheduleSelf(Runnable what, long when) { Callback cb = getCallback(); if (cb != null) cb.scheduleDrawable(this, what, when); }
    public void unscheduleSelf(Runnable what) { Callback cb = getCallback(); if (cb != null) cb.unscheduleDrawable(this, what); }

    public ConstantState getConstantState() { return null; }
    public abstract static class ConstantState {
        public abstract Drawable newDrawable();
        public Drawable newDrawable(android.content.res.Resources res) { return newDrawable(); }
        public int getChangingConfigurations() { return 0; }
    }

    /** ¿Está pulsado o con el puntero encima? */
    protected static boolean has(int[] state, int attr) {
        for (int s : state) if (s == attr) return true;
        return false;
    }
}
