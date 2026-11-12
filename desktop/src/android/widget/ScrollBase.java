package android.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.MainQueue;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;

/** Lo común de ScrollView y HorizontalScrollView: arrastrar, lanzar, la rueda y la barra fina. */
abstract class ScrollBase extends FrameLayout {
    private final boolean vertical;
    private final OverScroller scroller;
    private VelocityTracker velocity;
    private boolean dragging, fillViewport, smoothScrolling = true;
    private float lastMotion, lastMotionOther, downOther;
    private int activePointer = -1;
    private final int touchSlop, minFling, maxFling;
    private long lastScrollTime;
    private final float density;
    private long barShownAt = -10000;
    private boolean barHover;
    private View childToFocus;
    private final Runnable fadeBar = this::invalidate;

    ScrollBase(Context c, boolean vertical) {
        super(c);
        this.vertical = vertical;
        scroller = new OverScroller(c);
        ViewConfiguration conf = ViewConfiguration.get(c);
        touchSlop = conf.getScaledTouchSlop();
        minFling = conf.getScaledMinimumFlingVelocity();
        maxFling = conf.getScaledMaximumFlingVelocity();
        density = c.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        setWillNotDraw(false);
    }

    @Override public boolean isScrollContainer() { return true; }

    public void setFillViewport(boolean f) { if (f != fillViewport) { fillViewport = f; requestLayout(); } }
    public boolean isFillViewport() { return fillViewport; }
    public void setSmoothScrollingEnabled(boolean e) { smoothScrolling = e; }
    public boolean isSmoothScrollingEnabled() { return smoothScrolling; }

    @Override public void addView(View child) { check(); super.addView(child); }
    @Override public void addView(View child, int index) { check(); super.addView(child, index); }
    @Override public void addView(View child, ViewGroup.LayoutParams p) { check(); super.addView(child, p); }
    @Override public void addView(View child, int index, ViewGroup.LayoutParams p) { check(); super.addView(child, index, p); }
    private void check() { if (getChildCount() > 0) throw new IllegalStateException(getClass().getSimpleName() + " can host only one direct child"); }

    private int range() {
        if (getChildCount() == 0) return 0;
        View child = getChildAt(0);
        MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
        if (vertical) return Math.max(0, child.getHeight() + lp.topMargin + lp.bottomMargin - (getHeight() - mPaddingTop - mPaddingBottom));
        return Math.max(0, child.getWidth() + lp.leftMargin + lp.rightMargin - (getWidth() - mPaddingLeft - mPaddingRight));
    }

    private int pos() { return vertical ? mScrollY : mScrollX; }

    private void scrollToPos(int p) {
        p = Math.max(0, Math.min(range(), p));
        if (vertical) super.scrollTo(mScrollX, p); else super.scrollTo(p, mScrollY);
        showBar();
    }

    @Override
    public void scrollTo(int x, int y) {
        if (getChildCount() > 0) {
            View child = getChildAt(0);
            if (vertical) y = clamp(y, getHeight() - mPaddingBottom - mPaddingTop, child.getHeight());
            else x = clamp(x, getWidth() - mPaddingRight - mPaddingLeft, child.getWidth());
            if (vertical) x = mScrollX; else y = mScrollY;
            if (x != mScrollX || y != mScrollY) super.scrollTo(x, y);
        }
    }

    private static int clamp(int n, int my, int child) {
        if (my >= child || n < 0) return 0;
        if ((my + n) > child) return child - my;
        return n;
    }

    // ---------------------------------------------------------------- medida

    @Override
    protected void measureChild(View child, int ws, int hs) {
        ViewGroup.LayoutParams lp = child.getLayoutParams();
        int cw, ch;
        if (vertical) {
            cw = getChildMeasureSpec(ws, mPaddingLeft + mPaddingRight, lp.width);
            ch = MeasureSpec.makeSafeMeasureSpec(Math.max(0, MeasureSpec.getSize(hs) - mPaddingTop - mPaddingBottom), MeasureSpec.UNSPECIFIED);
        } else {
            ch = getChildMeasureSpec(hs, mPaddingTop + mPaddingBottom, lp.height);
            cw = MeasureSpec.makeSafeMeasureSpec(Math.max(0, MeasureSpec.getSize(ws) - mPaddingLeft - mPaddingRight), MeasureSpec.UNSPECIFIED);
        }
        child.measure(cw, ch);
    }

    @Override
    protected void measureChildWithMargins(View child, int ws, int wUsed, int hs, int hUsed) {
        MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
        int cw, ch;
        if (vertical) {
            cw = getChildMeasureSpec(ws, mPaddingLeft + mPaddingRight + lp.leftMargin + lp.rightMargin + wUsed, lp.width);
            int used = mPaddingTop + mPaddingBottom + lp.topMargin + lp.bottomMargin + hUsed;
            ch = MeasureSpec.makeSafeMeasureSpec(Math.max(0, MeasureSpec.getSize(hs) - used), MeasureSpec.UNSPECIFIED);
        } else {
            ch = getChildMeasureSpec(hs, mPaddingTop + mPaddingBottom + lp.topMargin + lp.bottomMargin + hUsed, lp.height);
            int used = mPaddingLeft + mPaddingRight + lp.leftMargin + lp.rightMargin + wUsed;
            cw = MeasureSpec.makeSafeMeasureSpec(Math.max(0, MeasureSpec.getSize(ws) - used), MeasureSpec.UNSPECIFIED);
        }
        child.measure(cw, ch);
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        super.onMeasure(ws, hs);
        if (!fillViewport || getChildCount() == 0) return;
        int mode = MeasureSpec.getMode(vertical ? hs : ws);
        if (mode == MeasureSpec.UNSPECIFIED) return;
        View child = getChildAt(0);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) child.getLayoutParams();
        if (vertical) {
            int desired = getMeasuredHeight() - mPaddingTop - mPaddingBottom - lp.topMargin - lp.bottomMargin;
            if (child.getMeasuredHeight() < desired) {
                int cw = getChildMeasureSpec(ws, mPaddingLeft + mPaddingRight + lp.leftMargin + lp.rightMargin, lp.width);
                child.measure(cw, MeasureSpec.makeMeasureSpec(desired, MeasureSpec.EXACTLY));
            }
        } else {
            int desired = getMeasuredWidth() - mPaddingLeft - mPaddingRight - lp.leftMargin - lp.rightMargin;
            if (child.getMeasuredWidth() < desired) {
                int ch = getChildMeasureSpec(hs, mPaddingTop + mPaddingBottom + lp.topMargin + lp.bottomMargin, lp.height);
                child.measure(MeasureSpec.makeMeasureSpec(desired, MeasureSpec.EXACTLY), ch);
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (childToFocus != null && isDescendant(childToFocus, this)) scrollToChild(childToFocus);
        childToFocus = null;
        // Que la posición siga dentro del contenido.
        scrollToPos(pos());
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        View f = findFocus();
        if (f == null || f == this) return;
        if (isWithinDelta(f, 0, vertical ? oh : ow)) {
            Rect r = new Rect();
            f.getDrawingRect(r);
            offsetDescendantRectToMyCoords(f, r);
            int d = computeScrollDeltaToGetChildRectOnScreen(r);
            if (d != 0) scrollBy(vertical ? 0 : d, vertical ? d : 0);
        }
    }

    private boolean isWithinDelta(View d, int delta, int size) {
        Rect r = new Rect();
        d.getDrawingRect(r);
        offsetDescendantRectToMyCoords(d, r);
        return vertical ? (r.bottom + delta) >= getScrollY() && (r.top - delta) <= (getScrollY() + size) : (r.right + delta) >= getScrollX() && (r.left - delta) <= (getScrollX() + size);
    }

    private static boolean isDescendant(View child, View parent) {
        if (child == parent) return true;
        ViewParent p = child.getParent();
        return p instanceof View && isDescendant((View) p, parent);
    }

    public void offsetDescendantRectToMyCoords(View d, Rect r) {
        View v = d;
        while (v != null && v != this) {
            r.offset(v.getLeft() - v.getScrollX(), v.getTop() - v.getScrollY());
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
    }

    // ---------------------------------------------------------------- tocar y arrastrar

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE && dragging) return true;
        if (range() == 0 && action != MotionEvent.ACTION_DOWN) return false;
        switch (action) {
            case MotionEvent.ACTION_MOVE: {
                if (activePointer == -1) break;
                int idx = e.findPointerIndex(activePointer);
                if (idx == -1) break;
                float m = vertical ? e.getY(idx) : e.getX(idx);
                float o = vertical ? e.getX(idx) : e.getY(idx);
                float d = Math.abs(m - lastMotion);
                if (d > touchSlop && d > Math.abs(o - downOther) * 0.8f) {
                    dragging = true;
                    lastMotion = m;
                    tracker().addMovement(e);
                    ViewParent p = getParent();
                    if (p != null) p.requestDisallowInterceptTouchEvent(true);
                }
                break;
            }
            case MotionEvent.ACTION_DOWN: {
                float m = vertical ? e.getY() : e.getX();
                lastMotion = m;
                downOther = vertical ? e.getX() : e.getY();
                activePointer = e.getPointerId(0);
                tracker().clear();
                tracker().addMovement(e);
                // Un toque durante un lanzamiento lo para y se queda con el gesto.
                dragging = !scroller.isFinished();
                if (dragging) scroller.abortAnimation();
                break;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                dragging = false;
                activePointer = -1;
                break;
            case MotionEvent.ACTION_POINTER_UP:
                onSecondaryPointerUp(e);
                break;
            default:
        }
        return dragging;
    }

    private VelocityTracker tracker() { if (velocity == null) velocity = VelocityTracker.obtain(); return velocity; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        tracker().addMovement(e);
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                if (getChildCount() == 0) return false;
                if ((dragging = !scroller.isFinished())) {
                    ViewParent p = getParent();
                    if (p != null) p.requestDisallowInterceptTouchEvent(true);
                }
                if (!scroller.isFinished()) scroller.abortAnimation();
                lastMotion = vertical ? e.getY() : e.getX();
                activePointer = e.getPointerId(0);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                int idx = e.findPointerIndex(activePointer);
                if (idx == -1) break;
                float m = vertical ? e.getY(idx) : e.getX(idx);
                int delta = Math.round(lastMotion - m);
                if (!dragging && Math.abs(delta) > touchSlop) {
                    ViewParent p = getParent();
                    if (p != null) p.requestDisallowInterceptTouchEvent(true);
                    dragging = true;
                    delta += delta > 0 ? -touchSlop : touchSlop;
                }
                if (dragging) {
                    lastMotion = m;
                    scrollToPos(pos() + delta);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    tracker().computeCurrentVelocity(1000, maxFling);
                    int v = (int) (vertical ? tracker().getYVelocity() : tracker().getXVelocity());
                    if (Math.abs(v) > minFling) fling(-v);
                }
                activePointer = -1;
                dragging = false;
                tracker().clear();
                break;
            case MotionEvent.ACTION_CANCEL:
                activePointer = -1;
                dragging = false;
                tracker().clear();
                break;
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                lastMotion = vertical ? e.getY(i) : e.getX(i);
                activePointer = e.getPointerId(i);
                break;
            }
            case MotionEvent.ACTION_POINTER_UP:
                onSecondaryPointerUp(e);
                int idx = e.findPointerIndex(activePointer);
                if (idx >= 0) lastMotion = vertical ? e.getY(idx) : e.getX(idx);
                break;
            default:
        }
        return true;
    }

    private void onSecondaryPointerUp(MotionEvent e) {
        int i = e.getActionIndex();
        if (e.getPointerId(i) == activePointer) {
            int n = i == 0 ? 1 : 0;
            lastMotion = vertical ? e.getY(n) : e.getX(n);
            activePointer = e.getPointerId(n);
            tracker().clear();
        }
    }

    public void fling(int velocity) {
        if (getChildCount() == 0) return;
        int r = range();
        if (vertical) scroller.fling(mScrollX, mScrollY, 0, velocity, 0, 0, 0, r);
        else scroller.fling(mScrollX, mScrollY, velocity, 0, 0, r, 0, 0);
        postInvalidateOnAnimation();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            int p = vertical ? scroller.getCurrY() : scroller.getCurrX();
            if (p != pos()) scrollToPos(p);
            if (!scroller.isFinished()) postInvalidateOnAnimation();
        }
    }

    // ---------------------------------------------------------------- rueda

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_SCROLL && !dragging) {
            float axis;
            if (vertical) axis = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            else {
                axis = e.getAxisValue(MotionEvent.AXIS_HSCROLL);
                // En el escritorio, la rueda normal también mueve una franja horizontal
                // si nada por encima se desplaza en vertical.
                if (axis == 0) {
                    float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    if (v != 0 && !ancestorScrollsVertically((int) -Math.signum(v))) axis = v;
                }
            }
            if (axis != 0 && range() > 0) {
                int delta = Math.round(axis * 64 * density);
                int base = scroller.isFinished() ? pos() : (vertical ? scroller.getFinalY() : scroller.getFinalX());
                int target = Math.max(0, Math.min(range(), base - delta));
                if (target != pos() || !scroller.isFinished()) {
                    if (smoothScrolling) {
                        int from = pos();
                        if (vertical) scroller.startScroll(mScrollX, from, 0, target - from, 140);
                        else scroller.startScroll(from, mScrollY, target - from, 0, 140);
                        postInvalidateOnAnimation();
                    } else scrollToPos(target);
                    showBar();
                    return true;
                }
            }
        }
        return super.onGenericMotionEvent(e);
    }

    private boolean ancestorScrollsVertically(int dir) {
        ViewParent p = getParent();
        while (p instanceof View) {
            View v = (View) p;
            if (v.canScrollVertically(dir)) return true;
            p = v.getParent();
        }
        return false;
    }

    @Override
    public boolean onHoverEvent(MotionEvent e) {
        int a = e.getActionMasked();
        boolean near = a != MotionEvent.ACTION_HOVER_EXIT && (vertical ? e.getX() > getWidth() - 14 * density : e.getY() > getHeight() - 14 * density);
        if (near != barHover) { barHover = near; if (near) showBar(); else invalidate(); }
        return super.onHoverEvent(e);
    }

    // ---------------------------------------------------------------- teclado

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (super.dispatchKeyEvent(e)) return true;
        return e.getAction() == KeyEvent.ACTION_DOWN && executeKeyEvent(e);
    }

    public boolean executeKeyEvent(KeyEvent e) {
        if (range() == 0) return false;
        int page = vertical ? getHeight() : getWidth();
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: if (!vertical) return false; smoothScrollBy(0, -Math.round(48 * density)); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (!vertical) return false; smoothScrollBy(0, Math.round(48 * density)); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: if (vertical) return false; smoothScrollBy(-Math.round(48 * density), 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (vertical) return false; smoothScrollBy(Math.round(48 * density), 0); return true;
            case KeyEvent.KEYCODE_PAGE_UP: return pageScroll(View.FOCUS_UP);
            case KeyEvent.KEYCODE_PAGE_DOWN: return pageScroll(View.FOCUS_DOWN);
            case KeyEvent.KEYCODE_SPACE: return pageScroll(e.isShiftPressed() ? View.FOCUS_UP : View.FOCUS_DOWN);
            case KeyEvent.KEYCODE_MOVE_HOME: if (e.isCtrlPressed() || !vertical) return fullScroll(View.FOCUS_UP); return false;
            case KeyEvent.KEYCODE_MOVE_END: if (e.isCtrlPressed() || !vertical) return fullScroll(View.FOCUS_DOWN); return false;
            default: return false;
        }
    }

    public boolean pageScroll(int dir) {
        int page = vertical ? getHeight() : getWidth();
        boolean back = dir == View.FOCUS_UP || dir == View.FOCUS_LEFT;
        smoothScrollToPos(pos() + (back ? -page : page));
        return true;
    }

    public boolean fullScroll(int dir) {
        boolean back = dir == View.FOCUS_UP || dir == View.FOCUS_LEFT;
        smoothScrollToPos(back ? 0 : range());
        return true;
    }

    public boolean arrowScroll(int dir) {
        boolean back = dir == View.FOCUS_UP || dir == View.FOCUS_LEFT;
        smoothScrollToPos(pos() + (back ? -1 : 1) * Math.round(48 * density));
        return true;
    }

    private void smoothScrollToPos(int target) {
        target = Math.max(0, Math.min(range(), target));
        if (vertical) smoothScrollTo(mScrollX, target); else smoothScrollTo(target, mScrollY);
    }

    public final void smoothScrollBy(int dx, int dy) {
        if (getChildCount() == 0) return;
        long since = SystemClock.uptimeMillis() - lastScrollTime;
        int d = vertical ? dy : dx;
        int target = Math.max(0, Math.min(range(), pos() + d));
        int delta = target - pos();
        if (since > 250 && smoothScrolling) {
            if (vertical) scroller.startScroll(mScrollX, mScrollY, 0, delta, 250); else scroller.startScroll(mScrollX, mScrollY, delta, 0, 250);
            postInvalidateOnAnimation();
        } else {
            if (!scroller.isFinished()) scroller.abortAnimation();
            scrollToPos(target);
        }
        lastScrollTime = SystemClock.uptimeMillis();
    }

    public final void smoothScrollTo(int x, int y) { smoothScrollBy(x - mScrollX, y - mScrollY); }
    public final void smoothScrollTo(int x, int y, int duration) { smoothScrollTo(x, y); }

    public int getMaxScrollAmount() { return (int) (0.5f * (vertical ? getHeight() : getWidth())); }

    // ---------------------------------------------------------------- foco y rectángulos

    @Override
    public void requestChildFocus(View child, View focused) {
        if (focused != null && focused.onCheckIsTextEditor()) {
            if (!isLayoutRequested()) scrollToChild(focused); else childToFocus = focused;
        }
        super.requestChildFocus(child, focused);
    }

    private void scrollToChild(View child) {
        Rect r = new Rect();
        child.getDrawingRect(r);
        offsetDescendantRectToMyCoords(child, r);
        int d = computeScrollDeltaToGetChildRectOnScreen(r);
        if (d != 0) scrollBy(vertical ? 0 : d, vertical ? d : 0);
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect r, boolean immediate) {
        Rect rect = new Rect(r);
        rect.offset(child.getLeft() - child.getScrollX(), child.getTop() - child.getScrollY());
        int d = computeScrollDeltaToGetChildRectOnScreen(rect);
        boolean scroll = d != 0;
        if (scroll) {
            if (immediate) scrollBy(vertical ? 0 : d, vertical ? d : 0);
            else smoothScrollBy(vertical ? 0 : d, vertical ? d : 0);
        }
        return scroll;
    }

    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        if (getChildCount() == 0) return 0;
        int size = vertical ? getHeight() : getWidth();
        int start = pos(), end = start + size;
        int fade = 0;
        if ((vertical ? rect.top : rect.left) > 0) start += fade;
        View child = getChildAt(0);
        int childEnd = vertical ? child.getBottom() : child.getRight();
        if ((vertical ? rect.bottom : rect.right) < childEnd) end -= fade;
        int rs = vertical ? rect.top : rect.left, re = vertical ? rect.bottom : rect.right;
        int delta = 0;
        if (re > end && rs > start) {
            if (re - rs > size) delta += (rs - start); else delta += (re - end);
            delta = Math.min(delta, childEnd - end);
        } else if (rs < start && re < end) {
            if (re - rs > size) delta -= (end - re); else delta -= (start - rs);
            delta = Math.max(delta, -pos());
        }
        return delta;
    }

    // ---------------------------------------------------------------- barra

    private void showBar() {
        barShownAt = SystemClock.uptimeMillis();
        MainQueue.remove(this, fadeBar, null);
        MainQueue.post(this, fadeBar, 1300, null);
        MainQueue.post(this, fadeBar, 1600, null);
        invalidate();
    }

    @Override
    protected void onDrawScrollBars(Canvas c) {
        if (!(vertical ? isVerticalScrollBarEnabled() : isHorizontalScrollBarEnabled())) return;
        int r = range();
        if (r <= 0) return;
        long since = SystemClock.uptimeMillis() - barShownAt;
        float alpha = barHover ? 1f : since < 1200 ? 1f : since < 1500 ? 1f - (since - 1200) / 300f : 0f;
        if (alpha <= 0) return;
        if (alpha < 1) postInvalidateDelayed(16);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(((int) (alpha * 0x70) << 24) | 0x808080);
        float thick = (barHover ? 6 : 4) * density, margin = 2 * density;
        int size = vertical ? getHeight() : getWidth();
        int content = size + r;
        float len = Math.max(24 * density, size * (size / (float) content));
        float at = (size - len) * (pos() / (float) r);
        RectF bar;
        if (vertical) bar = new RectF(mScrollX + getWidth() - thick - margin, mScrollY + at, mScrollX + getWidth() - margin, mScrollY + at + len);
        else bar = new RectF(mScrollX + at, mScrollY + getHeight() - thick - margin, mScrollX + at + len, mScrollY + getHeight() - margin);
        c.drawRoundRect(bar, thick / 2, thick / 2, p);
    }

    // ---------------------------------------------------------------- medidas del desplazamiento

    @Override
    protected int computeVerticalScrollRange() {
        if (!vertical || getChildCount() == 0) return getHeight() - mPaddingBottom - mPaddingTop;
        int bottom = getChildAt(0).getBottom();
        int over = Math.max(0, bottom - (getHeight() - mPaddingBottom - mPaddingTop));
        int sy = mScrollY;
        if (sy < 0) bottom -= sy; else if (sy > over) bottom += sy - over;
        return bottom;
    }

    @Override protected int computeVerticalScrollOffset() { return vertical ? Math.max(0, mScrollY) : 0; }
    @Override protected int computeVerticalScrollExtent() { return getHeight(); }

    @Override
    protected int computeHorizontalScrollRange() {
        if (vertical || getChildCount() == 0) return getWidth() - mPaddingLeft - mPaddingRight;
        int right = getChildAt(0).getRight();
        int over = Math.max(0, right - (getWidth() - mPaddingLeft - mPaddingRight));
        int sx = mScrollX;
        if (sx < 0) right -= sx; else if (sx > over) right += sx - over;
        return right;
    }

    @Override protected int computeHorizontalScrollOffset() { return vertical ? 0 : Math.max(0, mScrollX); }
    @Override protected int computeHorizontalScrollExtent() { return getWidth(); }

    @Override
    public boolean canScrollVertically(int dir) {
        if (!vertical) return false;
        return dir < 0 ? mScrollY > 0 : mScrollY < range();
    }

    @Override
    public boolean canScrollHorizontally(int dir) {
        if (vertical) return false;
        return dir < 0 ? mScrollX > 0 : mScrollX < range();
    }
}
