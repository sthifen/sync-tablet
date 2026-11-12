package android.graphics.drawable;

import android.graphics.Canvas;
import android.graphics.Rect;
import java.util.ArrayList;

public class LayerDrawable extends Drawable implements Drawable.Callback {
    private static final class Layer {
        Drawable d;
        int id = -1, l, t, r, b, gravity, w = -1, h = -1;
    }

    private final ArrayList<Layer> layers = new ArrayList<>();

    public LayerDrawable(Drawable[] ds) {
        for (Drawable d : ds) addLayerInternal(d);
    }

    private Layer addLayerInternal(Drawable d) {
        Layer l = new Layer();
        l.d = d;
        if (d != null) d.setCallback(this);
        layers.add(l);
        return l;
    }

    public int addLayer(Drawable d) { addLayerInternal(d); layout(getBounds()); return layers.size() - 1; }
    public int getNumberOfLayers() { return layers.size(); }
    public Drawable getDrawable(int i) { return layers.get(i).d; }
    public void setDrawable(int i, Drawable d) { layers.get(i).d = d; if (d != null) d.setCallback(this); layout(getBounds()); invalidateSelf(); }
    public void setId(int i, int id) { layers.get(i).id = id; }
    public Drawable findDrawableByLayerId(int id) { for (Layer l : layers) if (l.id == id) return l.d; return null; }
    public boolean setDrawableByLayerId(int id, Drawable d) { for (Layer l : layers) if (l.id == id) { l.d = d; layout(getBounds()); return true; } return false; }
    public void setLayerInset(int i, int l, int t, int r, int b) { Layer x = layers.get(i); x.l = l; x.t = t; x.r = r; x.b = b; layout(getBounds()); }
    public void setLayerInsetLeft(int i, int v) { layers.get(i).l = v; layout(getBounds()); }
    public void setLayerInsetTop(int i, int v) { layers.get(i).t = v; layout(getBounds()); }
    public void setLayerInsetRight(int i, int v) { layers.get(i).r = v; layout(getBounds()); }
    public void setLayerInsetBottom(int i, int v) { layers.get(i).b = v; layout(getBounds()); }
    public void setLayerGravity(int i, int g) { layers.get(i).gravity = g; layout(getBounds()); }
    public void setLayerSize(int i, int w, int h) { layers.get(i).w = w; layers.get(i).h = h; layout(getBounds()); }
    public void setLayerWidth(int i, int w) { layers.get(i).w = w; layout(getBounds()); }
    public void setLayerHeight(int i, int h) { layers.get(i).h = h; layout(getBounds()); }
    public void setPaddingMode(int m) {}

    @Override
    protected void onBoundsChange(Rect b) { layout(b); }

    private void layout(Rect b) {
        for (Layer l : layers) {
            if (l.d == null) continue;
            int left = b.left + l.l, top = b.top + l.t, right = b.right - l.r, bottom = b.bottom - l.b;
            if (l.w >= 0 || l.h >= 0 || l.gravity != 0) {
                int w = l.w >= 0 ? l.w : l.d.getIntrinsicWidth() >= 0 && l.gravity != 0 ? l.d.getIntrinsicWidth() : right - left;
                int h = l.h >= 0 ? l.h : l.d.getIntrinsicHeight() >= 0 && l.gravity != 0 ? l.d.getIntrinsicHeight() : bottom - top;
                Rect out = new Rect();
                android.view.Gravity.apply(l.gravity == 0 ? android.view.Gravity.CENTER : l.gravity, w, h, new Rect(left, top, right, bottom), out);
                l.d.setBounds(out);
            } else l.d.setBounds(left, top, right, bottom);
        }
    }

    @Override
    public void draw(Canvas c) {
        for (Layer l : layers) if (l.d != null) l.d.draw(c);
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean changed = false;
        for (Layer l : layers) if (l.d != null && l.d.setState(state)) changed = true;
        return changed;
    }

    @Override public boolean isStateful() { for (Layer l : layers) if (l.d != null && l.d.isStateful()) return true; return false; }
    @Override public void setAlpha(int a) { super.setAlpha(a); for (Layer l : layers) if (l.d != null) l.d.setAlpha(a); }
    @Override public void setColorFilter(android.graphics.ColorFilter f) { for (Layer l : layers) if (l.d != null) l.d.setColorFilter(f); }

    @Override
    public int getIntrinsicWidth() {
        int w = -1;
        for (Layer l : layers) if (l.d != null) w = Math.max(w, (l.w >= 0 ? l.w : l.d.getIntrinsicWidth()) + l.l + l.r);
        return w;
    }

    @Override
    public int getIntrinsicHeight() {
        int h = -1;
        for (Layer l : layers) if (l.d != null) h = Math.max(h, (l.h >= 0 ? l.h : l.d.getIntrinsicHeight()) + l.t + l.b);
        return h;
    }

    @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
    @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
    @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
}
