package android.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.IBinder;
import android.os.MainQueue;
import android.os.SystemClock;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.util.ArrayList;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * El puente entre la ventana de Swing y el árbol de vistas: mide y coloca,
 * dibuja, reparte el ratón, la rueda y el teclado, y lleva el foco.
 */
public final class ViewRoot {
    /** Lo que la ventana sabe hacer (lo pone el lanzador). */
    public interface Host {
        void repaint();
        int width();
        int height();
        java.awt.Point screenOffset();
        void setCursor(Cursor c);
        /** Una tecla que nadie quiso (Atrás, etc.). */
        boolean unhandledKey(KeyEvent e);
        /** La tecla, a la actividad primero (como Activity.dispatchKeyEvent). */
        boolean dispatchKey(KeyEvent e);
        boolean dispatchTouch(MotionEvent e);
        boolean dispatchGeneric(MotionEvent e);
    }

    final ViewTreeObserver observer = new ViewTreeObserver();
    final IBinder token = new IBinder() {};
    private final Host host;
    private View decor;
    private View focused;
    boolean windowFocused = true;
    boolean inLayout;
    private boolean needLayout = true, needInsets = true, repaintPosted;
    private int lastW = -1, lastH = -1;
    private final float density;

    public ViewRoot(Host host, float density) {
        this.host = host;
        this.density = density;
    }

    public View decor() { return decor; }

    public void setDecor(View d) {
        if (decor == d) return;
        if (decor != null) {
            if (focused != null) { View f = focused; focused = null; clearFocusChain(f); f.setFocusedInternal(false); }
            decor.dispatchDetachedFromWindowTree();
        }
        decor = d;
        hoverPath = null;
        if (d != null) {
            d.dispatchAttachedToWindowTree(this);
            needInsets = true;
            needLayout = true;
            d.requestLayout();
        }
        invalidate();
    }

    int width() { return host.width(); }
    int height() { return host.height(); }
    java.awt.Point screenOffset() { return host.screenOffset(); }

    // ---- Qué hay que repintar. Como en Android, una vista que cambia sólo pide su
    // rectángulo; lo demás (disposición, márgenes, avisos) pide la ventana entera.
    static final boolean PARTIAL = !"false".equals(System.getProperty("synctablet.dirty"));
    private boolean fullDirty = true;
    private java.awt.Rectangle dirty;

    public void invalidate() { fullDirty = true; host.repaint(); }

    /** Sólo este rectángulo (en la ventana) cambió. */
    public void invalidate(java.awt.Rectangle r) {
        if (!PARTIAL) { invalidate(); return; }
        if (!fullDirty && !r.isEmpty()) dirty = dirty == null ? new java.awt.Rectangle(r) : dirty.union(r);
        host.repaint();
    }

    /**
     * Antes de pintar: dispone si hace falta y dice qué repintar (null: todo).
     * Un rectángulo vacío quiere decir que nada cambió (la ventana se destapó).
     */
    public java.awt.Rectangle beginFrame() {
        if (needLayout || needInsets) fullDirty = true;
        layoutIfNeeded();
        java.awt.Rectangle r = fullDirty ? null : dirty == null ? new java.awt.Rectangle() : dirty;
        fullDirty = false;
        dirty = null;
        return r;
    }

    public void scheduleLayout() {
        needLayout = true;
        fullDirty = true;
        host.repaint();
    }

    void requestInsets() { needInsets = true; fullDirty = true; host.repaint(); }

    WindowInsetsController insetsController() { return null; }

    // ---------------------------------------------------------------- pasada

    /** Mide y coloca si hace falta. */
    public void layoutIfNeeded() {
        if (decor == null) return;
        int w = host.width(), h = host.height();
        if (w <= 0 || h <= 0) return;
        if (needInsets) {
            needInsets = false;
            decor.dispatchApplyWindowInsets(WindowInsets.NONE);
        }
        boolean sized = w != lastW || h != lastH;
        for (int pass = 0; pass < 4 && (needLayout || sized || decor.forceLayout); pass++) {
            needLayout = false;
            sized = false;
            lastW = w;
            lastH = h;
            inLayout = true;
            try {
                decor.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                decor.layout(0, 0, w, h);
            } finally {
                inLayout = false;
            }
            observer.dispatchOnGlobalLayout();
        }
    }

    public void paint(Graphics2D g) {
        repaintPosted = false;
        layoutIfNeeded();
        if (decor == null) return;
        if (observer.dispatchOnPreDraw()) { invalidate(); }
        // Si al prepararse algo pidió la ventana entera, que se vea ya y no en el siguiente.
        layoutIfNeeded();
        Canvas c = new Canvas(g, host.width(), host.height());
        decor.computeScroll();
        c.save();
        c.translate(-decor.getScrollX(), -decor.getScrollY());
        decor.draw(c);
        c.restore();
        drawToasts(c);
        drawTooltip(c);
        observer.dispatchOnDraw();
    }

    // ---------------------------------------------------------------- foco

    View focused() { return focused; }

    void setFocus(View v) {
        if (v == focused) return;
        View old = focused;
        if (old != null) {
            focused = null;
            clearFocusChain(old);
            old.setFocusedInternal(false);
        }
        focused = v;
        if (v != null) {
            // Cada padre recuerda qué hijo lleva el foco.
            View child = v;
            ViewParent p = v.getParent();
            while (p instanceof ViewGroup) {
                ((ViewGroup) p).focusedChild = child;
                child = (View) p;
                p = child.getParent();
            }
            v.setFocusedInternal(true);
        }
        for (ViewTreeObserver.OnGlobalFocusChangeListener l : observer.focus) l.onGlobalFocusChanged(old, v);
        android.view.inputmethod.InputMethodManager.focusChanged(v);
    }

    private static void clearFocusChain(View v) {
        ViewParent p = v.getParent();
        while (p instanceof ViewGroup) {
            ((ViewGroup) p).focusedChild = null;
            p = ((View) p).getParent();
        }
    }

    void focusLost(View removed) {
        if (focused != null && isDescendant(focused, removed)) setFocus(null);
    }

    void viewDetached(View v) {
        if (v == focused) { focused = null; clearFocusChain(v); }
        if (hoverPath != null && hoverPath.contains(v)) hoverPath = null;
        if (tooltipView == v) hideTooltip();
    }

    void viewHidden(View v) {
        if (focused != null && isDescendant(focused, v)) setFocus(null);
    }

    private static boolean isDescendant(View v, View ancestor) {
        while (v != null) {
            if (v == ancestor) return true;
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return false;
    }

    public void setWindowFocused(boolean f) {
        if (windowFocused == f) return;
        windowFocused = f;
        if (decor != null) decor.dispatchWindowFocus(f);
        for (ViewTreeObserver.OnWindowFocusChangeListener l : observer.windowFocus) l.onWindowFocusChanged(f);
    }

    // ---------------------------------------------------------------- ratón

    private long downTime;
    private int activeButtons;
    private boolean pointerDown;
    private int tool;
    private float lastX, lastY;
    private boolean hovering;

    private static final int SRC_MOUSE = InputDevice.SOURCE_MOUSE, SRC_TOUCH = InputDevice.SOURCE_TOUCHSCREEN;

    private MotionEvent one(int action, float x, float y, int toolType, int buttons, int meta) {
        MotionEvent e = MotionEvent.create(downTime, SystemClock.uptimeMillis(), action, new int[] {0}, new int[] {toolType}, new float[] {x}, new float[] {y},
                new float[] {toolType == MotionEvent.TOOL_TYPE_FINGER ? 1f : 0.6f}, buttons, meta, toolType == MotionEvent.TOOL_TYPE_FINGER ? SRC_TOUCH : SRC_MOUSE);
        java.awt.Point s = host.screenOffset();
        e.setRawOffset(s.x, s.y);
        return e;
    }

    /** Botón del ratón: el izquierdo y el derecho son el ratón de Android; el central arrastra como un dedo. */
    public void mousePressed(java.awt.event.MouseEvent ev, float x, float y) {
        if (synth != null) endSynth();
        int b = awtButton(ev);
        int meta = KeyEvent.metaFromAwt(ev.getModifiersEx());
        hideTooltip();
        if (pointerDown) {
            activeButtons |= b;
            return;
        }
        pointerDown = true;
        activeButtons = b;
        tool = b == MotionEvent.BUTTON_TERTIARY ? MotionEvent.TOOL_TYPE_FINGER : MotionEvent.TOOL_TYPE_MOUSE;
        downTime = SystemClock.uptimeMillis();
        lastX = x; lastY = y;
        if (hovering) {
            sendHover(MotionEvent.ACTION_HOVER_EXIT, x, y, meta);
            hovering = false;
        }
        host.dispatchTouch(one(MotionEvent.ACTION_DOWN, x, y, tool, tool == MotionEvent.TOOL_TYPE_FINGER ? 0 : activeButtons, meta));
        if (tool == MotionEvent.TOOL_TYPE_MOUSE) {
            MotionEvent bp = one(MotionEvent.ACTION_BUTTON_PRESS, x, y, tool, activeButtons, meta);
            host.dispatchGeneric(bp);
        }
        invalidate();
    }

    public void mouseDragged(java.awt.event.MouseEvent ev, float x, float y) {
        if (!pointerDown) return;
        lastX = x; lastY = y;
        host.dispatchTouch(one(MotionEvent.ACTION_MOVE, x, y, tool, tool == MotionEvent.TOOL_TYPE_FINGER ? 0 : activeButtons, KeyEvent.metaFromAwt(ev.getModifiersEx())));
    }

    public void mouseReleased(java.awt.event.MouseEvent ev, float x, float y) {
        if (!pointerDown) return;
        int b = awtButton(ev);
        activeButtons &= ~b;
        if (activeButtons != 0) return;
        pointerDown = false;
        int meta = KeyEvent.metaFromAwt(ev.getModifiersEx());
        host.dispatchTouch(one(MotionEvent.ACTION_UP, x, y, tool, 0, meta));
        if (tool == MotionEvent.TOOL_TYPE_MOUSE) host.dispatchGeneric(one(MotionEvent.ACTION_BUTTON_RELEASE, x, y, tool, 0, meta));
        // El ratón sigue encima: vuelve a flotar.
        mouseMoved(ev, x, y);
    }

    public void cancelPointer() {
        if (!pointerDown) return;
        pointerDown = false;
        activeButtons = 0;
        host.dispatchTouch(one(MotionEvent.ACTION_CANCEL, lastX, lastY, tool, 0, 0));
    }

    private static int awtButton(java.awt.event.MouseEvent ev) {
        switch (ev.getButton()) {
            case java.awt.event.MouseEvent.BUTTON1: return MotionEvent.BUTTON_PRIMARY;
            case java.awt.event.MouseEvent.BUTTON2: return MotionEvent.BUTTON_TERTIARY;
            case java.awt.event.MouseEvent.BUTTON3: return MotionEvent.BUTTON_SECONDARY;
            default: return MotionEvent.BUTTON_PRIMARY;
        }
    }

    private ArrayList<View> hoverPath;

    public void mouseMoved(java.awt.event.MouseEvent ev, float x, float y) {
        if (pointerDown || decor == null) return;
        int meta = ev == null ? 0 : KeyEvent.metaFromAwt(ev.getModifiersEx());
        sendHover(hovering ? MotionEvent.ACTION_HOVER_MOVE : MotionEvent.ACTION_HOVER_ENTER, x, y, meta);
        hovering = true;
        lastX = x; lastY = y;
        updateCursor();
        updateTooltip(x, y);
    }

    public void mouseExited(java.awt.event.MouseEvent ev, float x, float y) {
        if (pointerDown || !hovering) return;
        sendHover(MotionEvent.ACTION_HOVER_EXIT, x, y, 0);
        hovering = false;
        hideTooltip();
    }

    private void sendHover(int action, float x, float y, int meta) {
        MotionEvent e = one(action, x, y, MotionEvent.TOOL_TYPE_MOUSE, 0, meta);
        host.dispatchGeneric(e);
    }

    /** La vista más honda bajo el punto, con sus padres (de la raíz hacia abajo). */
    ArrayList<View> pathAt(float x, float y) {
        ArrayList<View> path = new ArrayList<>();
        View v = decor;
        float[] p = {x + decor.getScrollX(), y + decor.getScrollY()};
        float px = x, py = y;
        while (v != null) {
            path.add(v);
            if (!(v instanceof ViewGroup)) break;
            ViewGroup g = (ViewGroup) v;
            ArrayList<View> order = g.drawingOrder();
            View next = null;
            float[] out = new float[2];
            for (int i = order.size() - 1; i >= 0; i--) {
                View c = order.get(i);
                if (c.getVisibility() != View.VISIBLE) continue;
                if (g.hitChild(c, px, py, out)) { next = c; px = out[0]; py = out[1]; break; }
            }
            v = next;
        }
        return path;
    }

    void updateCursor() {
        if (decor == null) return;
        ArrayList<View> path = pathAt(lastX, lastY);
        PointerIcon icon = null;
        for (int i = path.size() - 1; i >= 0 && icon == null; i--) {
            View v = path.get(i);
            icon = v.onResolvePointerIcon(null, 0);
        }
        int type = icon == null ? PointerIcon.TYPE_ARROW : icon.type;
        Cursor c;
        switch (type) {
            case PointerIcon.TYPE_HAND: c = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR); break;
            case PointerIcon.TYPE_TEXT: case PointerIcon.TYPE_VERTICAL_TEXT: c = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR); break;
            case PointerIcon.TYPE_CROSSHAIR: c = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR); break;
            case PointerIcon.TYPE_WAIT: c = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR); break;
            case PointerIcon.TYPE_ALL_SCROLL: case PointerIcon.TYPE_GRAB: case PointerIcon.TYPE_GRABBING: c = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR); break;
            case PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW: c = Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR); break;
            case PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW: c = Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR); break;
            case PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW: c = Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR); break;
            case PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW: c = Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR); break;
            case PointerIcon.TYPE_NULL: c = java.awt.Toolkit.getDefaultToolkit().createCustomCursor(new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB), new java.awt.Point(), "nada"); break;
            default: c = Cursor.getDefaultCursor();
        }
        host.setCursor(c);
    }

    // ---------------------------------------------------------------- rueda

    /** Gesto sintético de dos dedos (desplazar o pellizcar) para las vistas que no entienden la rueda. */
    private static final class Synth {
        float cx, cy, dx, dy, span;
        boolean pinch;
        long down;
    }

    private Synth synth;
    private final Runnable synthEnd = this::endSynth;

    public void mouseWheel(java.awt.event.MouseWheelEvent ev, float x, float y) {
        if (decor == null || pointerDown) return;
        double rot = ev.getPreciseWheelRotation();
        boolean ctrl = (ev.getModifiersEx() & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0;
        boolean shift = (ev.getModifiersEx() & java.awt.event.InputEvent.SHIFT_DOWN_MASK) != 0;
        hideTooltip();
        if (!ctrl) {
            if (synth == null || synth.pinch) {
                if (synth != null) endSynth();
                MotionEvent e = one(MotionEvent.ACTION_SCROLL, x, y, MotionEvent.TOOL_TYPE_MOUSE, 0, KeyEvent.metaFromAwt(ev.getModifiersEx()));
                e.setScroll(shift ? 0 : (float) -rot, shift ? (float) -rot : 0);
                if (host.dispatchGeneric(e)) return;
            }
            float step = (float) (rot * 64 * density);
            moveSynth(x, y, false, shift ? -step : 0, shift ? 0 : -step, 1f);
        } else {
            moveSynth(x, y, true, 0, 0, (float) Math.pow(1.1, -rot));
        }
    }

    private MotionEvent two(int action, Synth s, float scale) {
        float half = s.span / 2;
        float[] xs = {s.cx + s.dx - half, s.cx + s.dx + half}, ys = {s.cy + s.dy, s.cy + s.dy};
        MotionEvent e = MotionEvent.create(s.down, SystemClock.uptimeMillis(), action, new int[] {0, 1},
                new int[] {MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_FINGER}, xs, ys, new float[] {1, 1}, 0, 0, SRC_TOUCH);
        java.awt.Point o = host.screenOffset();
        e.setRawOffset(o.x, o.y);
        return e;
    }

    private MotionEvent first(int action, Synth s) {
        float half = s.span / 2;
        MotionEvent e = MotionEvent.create(s.down, SystemClock.uptimeMillis(), action, new int[] {0}, new int[] {MotionEvent.TOOL_TYPE_FINGER},
                new float[] {s.cx + s.dx - half}, new float[] {s.cy + s.dy}, new float[] {1}, 0, 0, SRC_TOUCH);
        java.awt.Point o = host.screenOffset();
        e.setRawOffset(o.x, o.y);
        return e;
    }

    private void moveSynth(float x, float y, boolean pinch, float dx, float dy, float scale) {
        if (synth != null && synth.pinch != pinch) endSynth();
        if (synth == null) {
            Synth s = new Synth();
            s.cx = x; s.cy = y; s.pinch = pinch;
            s.span = pinch ? 240 * density : 48 * density;
            s.down = SystemClock.uptimeMillis();
            synth = s;
            host.dispatchTouch(first(MotionEvent.ACTION_DOWN, s));
            host.dispatchTouch(two(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), s, 1));
            if (pinch) {
                // Un primer salto para que el pellizco empiece (supera el umbral del detector).
                s.span += 40 * density;
                host.dispatchTouch(two(MotionEvent.ACTION_MOVE, s, 1));
            }
        }
        Synth s = synth;
        s.dx += dx;
        s.dy += dy;
        if (pinch) s.span = Math.max(60 * density, Math.min(4000 * density, s.span * scale));
        host.dispatchTouch(two(MotionEvent.ACTION_MOVE, s, scale));
        MainQueue.remove(this, synthEnd, null);
        MainQueue.post(this, synthEnd, 140, null);
    }

    private void endSynth() {
        MainQueue.remove(this, synthEnd, null);
        Synth s = synth;
        if (s == null) return;
        synth = null;
        // Quieto un momento antes de soltar: así no queda un lanzamiento (fling).
        host.dispatchTouch(two(MotionEvent.ACTION_MOVE, s, 1));
        host.dispatchTouch(two(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), s, 1));
        host.dispatchTouch(first(MotionEvent.ACTION_UP, s));
    }

    // ---------------------------------------------------------------- teclado

    public boolean key(KeyEvent e) {
        if (decor == null) return false;
        if (host.dispatchKey(e)) return true;
        return host.unhandledKey(e);
    }

    /** Lo que se escribe: al campo de texto con foco. */
    public boolean typed(String text) {
        View f = focused;
        if (f instanceof TextInput && f.isEnabled()) {
            ((TextInput) f).commitText(text);
            return true;
        }
        return false;
    }

    public void composing(String text) {
        View f = focused;
        if (f instanceof TextInput) ((TextInput) f).setComposing(text);
    }

    public java.awt.Rectangle caretRect() {
        View f = focused;
        if (f instanceof TextInput) {
            RectF r = ((TextInput) f).caretBounds();
            float[] p = {r.left, r.bottom};
            f.mapToWindow(p);
            return new java.awt.Rectangle(Math.round(p[0]), Math.round(p[1]), 1, Math.round(r.height()));
        }
        return new java.awt.Rectangle(0, 0, 1, 1);
    }

    /** Lo que implementa un campo de texto que recibe lo escrito. */
    public interface TextInput {
        void commitText(String s);
        void setComposing(String s);
        RectF caretBounds();
    }

    // ---------------------------------------------------------------- avisos (Toast)

    private static final class ToastItem {
        final String text;
        final long until;
        ToastItem(String t, long u) { text = t; until = u; }
    }

    private final ArrayList<ToastItem> toasts = new ArrayList<>();

    public void toast(String text, long duration) {
        if (!SwingUtilities.isEventDispatchThread()) { MainQueue.post(this, () -> toast(text, duration), 0, null); return; }
        toasts.clear();
        long until = SystemClock.uptimeMillis() + duration;
        toasts.add(new ToastItem(text, until));
        MainQueue.post(this, this::invalidate, duration + 20, null);
        invalidate();
    }

    private void drawToasts(Canvas c) {
        long now = SystemClock.uptimeMillis();
        toasts.removeIf(t -> t.until <= now);
        if (toasts.isEmpty()) return;
        ToastItem t = toasts.get(toasts.size() - 1);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(14 * density);
        float maxW = Math.min(host.width() - 48 * density, 560 * density);
        android.text.StaticLayout layout = android.text.StaticLayout.Builder.obtain(t.text, 0, t.text.length(), new android.text.TextPaint(p), (int) maxW).build();
        float w = 0;
        for (int i = 0; i < layout.getLineCount(); i++) w = Math.max(w, layout.getLineWidth(i));
        float padX = 18 * density, padY = 11 * density;
        float bw = w + padX * 2, bh = layout.getHeight() + padY * 2;
        float x = (host.width() - bw) / 2, y = host.height() - bh - 64 * density;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xE6323232);
        c.drawRoundRect(new RectF(x, y, x + bw, y + bh), bh / 2 > 24 * density ? 16 * density : bh / 2, bh / 2 > 24 * density ? 16 * density : bh / 2, bg);
        c.save();
        c.translate(x + padX, y + padY);
        layout.getPaint().setColor(0xFFFFFFFF);
        layout.draw(c);
        c.restore();
    }

    // ---------------------------------------------------------------- descripciones (al dejar el ratón quieto)

    private View tooltipView;
    private String tooltipText;
    private float tipX, tipY;
    private boolean tipShown;
    private final Runnable showTip = () -> { if (tooltipView != null) { tipShown = true; invalidate(); } };

    private void updateTooltip(float x, float y) {
        View t = null;
        ArrayList<View> path = pathAt(x, y);
        for (int i = path.size() - 1; i >= 0; i--) {
            View v = path.get(i);
            if (v.getTooltipText() != null) { t = v; break; }
        }
        if (t != tooltipView) {
            hideTooltip();
            tooltipView = t;
            if (t != null) {
                tooltipText = t.getTooltipText().toString();
                tipX = x; tipY = y;
                MainQueue.post(this, showTip, 700, null);
            }
        }
    }

    private void hideTooltip() {
        MainQueue.remove(this, showTip, null);
        if (tipShown) invalidate();
        tipShown = false;
        tooltipView = null;
    }

    private void drawTooltip(Canvas c) {
        if (!tipShown || tooltipView == null || tooltipText == null) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(13 * density);
        p.setColor(0xFFFFFFFF);
        float w = p.measureText(tooltipText), padX = 10 * density, padY = 6 * density;
        Paint.FontMetrics fm = p.getFontMetrics();
        float h = fm.descent - fm.ascent;
        float x = Math.max(4, Math.min(host.width() - w - padX * 2 - 4, tipX - (w + padX * 2) / 2)), y = tipY + 24 * density;
        if (y + h + padY * 2 > host.height()) y = tipY - h - padY * 2 - 12 * density;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xF0303436);
        c.drawRoundRect(new RectF(x, y, x + w + padX * 2, y + h + padY * 2), 6 * density, 6 * density, bg);
        c.drawText(tooltipText, x + padX, y + padY - fm.ascent, p);
    }
}
