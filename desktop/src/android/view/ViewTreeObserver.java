package android.view;

import java.util.concurrent.CopyOnWriteArrayList;

public final class ViewTreeObserver {
    public interface OnGlobalLayoutListener { void onGlobalLayout(); }
    public interface OnPreDrawListener { boolean onPreDraw(); }
    public interface OnDrawListener { void onDraw(); }
    public interface OnScrollChangedListener { void onScrollChanged(); }
    public interface OnGlobalFocusChangeListener { void onGlobalFocusChanged(View oldFocus, View newFocus); }
    public interface OnWindowFocusChangeListener { void onWindowFocusChanged(boolean hasFocus); }
    public interface OnTouchModeChangeListener { void onTouchModeChanged(boolean isInTouchMode); }
    public interface OnWindowAttachListener { void onWindowAttached(); void onWindowDetached(); }

    final CopyOnWriteArrayList<OnGlobalLayoutListener> layout = new CopyOnWriteArrayList<>();
    final CopyOnWriteArrayList<OnPreDrawListener> preDraw = new CopyOnWriteArrayList<>();
    final CopyOnWriteArrayList<OnDrawListener> draw = new CopyOnWriteArrayList<>();
    final CopyOnWriteArrayList<OnScrollChangedListener> scroll = new CopyOnWriteArrayList<>();
    final CopyOnWriteArrayList<OnGlobalFocusChangeListener> focus = new CopyOnWriteArrayList<>();
    final CopyOnWriteArrayList<OnWindowFocusChangeListener> windowFocus = new CopyOnWriteArrayList<>();

    public void addOnGlobalLayoutListener(OnGlobalLayoutListener l) { layout.add(l); }
    public void removeOnGlobalLayoutListener(OnGlobalLayoutListener l) { layout.remove(l); }
    @Deprecated public void removeGlobalOnLayoutListener(OnGlobalLayoutListener l) { layout.remove(l); }
    public void addOnPreDrawListener(OnPreDrawListener l) { preDraw.add(l); }
    public void removeOnPreDrawListener(OnPreDrawListener l) { preDraw.remove(l); }
    public void addOnDrawListener(OnDrawListener l) { draw.add(l); }
    public void removeOnDrawListener(OnDrawListener l) { draw.remove(l); }
    public void addOnScrollChangedListener(OnScrollChangedListener l) { scroll.add(l); }
    public void removeOnScrollChangedListener(OnScrollChangedListener l) { scroll.remove(l); }
    public void addOnGlobalFocusChangeListener(OnGlobalFocusChangeListener l) { focus.add(l); }
    public void removeOnGlobalFocusChangeListener(OnGlobalFocusChangeListener l) { focus.remove(l); }
    public void addOnWindowFocusChangeListener(OnWindowFocusChangeListener l) { windowFocus.add(l); }
    public void removeOnWindowFocusChangeListener(OnWindowFocusChangeListener l) { windowFocus.remove(l); }
    public void addOnTouchModeChangeListener(OnTouchModeChangeListener l) {}
    public void addOnWindowAttachListener(OnWindowAttachListener l) {}
    public boolean isAlive() { return true; }

    void merge(ViewTreeObserver o) {
        layout.addAll(o.layout); preDraw.addAll(o.preDraw); draw.addAll(o.draw); scroll.addAll(o.scroll); focus.addAll(o.focus); windowFocus.addAll(o.windowFocus);
    }

    public void dispatchOnGlobalLayout() { for (OnGlobalLayoutListener l : layout) l.onGlobalLayout(); }
    public boolean dispatchOnPreDraw() { boolean cancel = false; for (OnPreDrawListener l : preDraw) cancel |= !l.onPreDraw(); return cancel; }
    void dispatchOnDraw() { for (OnDrawListener l : draw) l.onDraw(); }
}
