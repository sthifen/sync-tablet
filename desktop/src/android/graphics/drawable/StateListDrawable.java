package android.graphics.drawable;

import android.graphics.Canvas;
import java.util.ArrayList;

public class StateListDrawable extends Drawable implements Drawable.Callback {
    private final ArrayList<int[]> specs = new ArrayList<>();
    private final ArrayList<Drawable> drawables = new ArrayList<>();
    private Drawable current;

    public void addState(int[] spec, Drawable d) { specs.add(spec); drawables.add(d); d.setCallback(this); pick(getState()); }

    private boolean pick(int[] state) {
        Drawable next = null;
        for (int i = 0; i < specs.size(); i++) {
            boolean ok = true;
            for (int s : specs.get(i)) {
                boolean want = s > 0;
                if (has(state, Math.abs(s)) != want) { ok = false; break; }
            }
            if (ok) { next = drawables.get(i); break; }
        }
        if (next != current) {
            current = next;
            if (current != null) current.setBounds(getBounds());
            invalidateSelf();
            return true;
        }
        return false;
    }

    @Override public boolean isStateful() { return true; }
    @Override protected boolean onStateChange(int[] s) { return pick(s); }
    @Override protected void onBoundsChange(android.graphics.Rect b) { if (current != null) current.setBounds(b); }
    @Override public void draw(Canvas c) { if (current != null) current.draw(c); }
    @Override public Drawable getCurrent() { return current; }
    @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
    @Override public void scheduleDrawable(Drawable who, Runnable what, long when) {}
    @Override public void unscheduleDrawable(Drawable who, Runnable what) {}
}
