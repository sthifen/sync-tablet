package android.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.Collections;
import android.animation.LayoutTransition;

public abstract class ViewGroup extends View implements ViewParent, ViewManager {
    public static final int FOCUS_BEFORE_DESCENDANTS = 0x20000, FOCUS_AFTER_DESCENDANTS = 0x40000, FOCUS_BLOCK_DESCENDANTS = 0x60000;
    public static final int LAYOUT_MODE_CLIP_BOUNDS = 0, LAYOUT_MODE_OPTICAL_BOUNDS = 1;
    public static final int PERSISTENT_NO_CACHE = 0;

    public interface OnHierarchyChangeListener {
        void onChildViewAdded(View parent, View child);
        void onChildViewRemoved(View parent, View child);
    }

    public static class LayoutParams {
        public static final int MATCH_PARENT = -1, FILL_PARENT = -1, WRAP_CONTENT = -2;
        public int width, height;
        public LayoutParams(int w, int h) { width = w; height = h; }
        public LayoutParams(LayoutParams src) { width = src.width; height = src.height; }
        public LayoutParams(Context c, android.util.AttributeSet a) { this(WRAP_CONTENT, WRAP_CONTENT); }
        public void resolveLayoutDirection(int d) {}
    }

    public static class MarginLayoutParams extends LayoutParams {
        public int leftMargin, topMargin, rightMargin, bottomMargin;
        public MarginLayoutParams(int w, int h) { super(w, h); }
        public MarginLayoutParams(LayoutParams src) { super(src); }
        public MarginLayoutParams(MarginLayoutParams src) { super(src); leftMargin = src.leftMargin; topMargin = src.topMargin; rightMargin = src.rightMargin; bottomMargin = src.bottomMargin; }
        public MarginLayoutParams(Context c, android.util.AttributeSet a) { super(c, a); }
        public void setMargins(int l, int t, int r, int b) { leftMargin = l; topMargin = t; rightMargin = r; bottomMargin = b; }
        public void setMarginStart(int s) { leftMargin = s; }
        public void setMarginEnd(int e) { rightMargin = e; }
        public int getMarginStart() { return leftMargin; }
        public int getMarginEnd() { return rightMargin; }
        public boolean isMarginRelative() { return false; }
        public int getLayoutDirection() { return LAYOUT_DIRECTION_LTR; }
        public void setLayoutDirection(int d) {}
    }

    private final ArrayList<View> children = new ArrayList<>();
    private boolean clipChildren = true, clipToPadding = true, childrenDrawingOrder, motionSplitting = true;
    private int descendantFocusability = FOCUS_BEFORE_DESCENDANTS;
    private OnHierarchyChangeListener hierarchy;
    View focusedChild;
    private View touchTarget;
    private boolean disallowIntercept;
    private View hoverTarget;
    private boolean selfHovered;
    private LayoutTransition transition;

    public ViewGroup(Context c) {
        super(c);
        setWillNotDraw(true);
    }

    public ViewGroup(Context c, android.util.AttributeSet a) { this(c); }
    public ViewGroup(Context c, android.util.AttributeSet a, int s) { this(c); }

    // ---------------------------------------------------------------- hijos

    public int getChildCount() { return children.size(); }
    public View getChildAt(int i) { return i < 0 || i >= children.size() ? null : children.get(i); }
    public int indexOfChild(View v) { return children.indexOf(v); }

    public void addView(View child) { addView(child, -1); }
    public void addView(View child, int index) {
        LayoutParams p = child.getLayoutParams();
        if (p == null) p = generateDefaultLayoutParams();
        addView(child, index, p);
    }
    public void addView(View child, int width, int height) {
        LayoutParams p = generateDefaultLayoutParams();
        p.width = width;
        p.height = height;
        addView(child, -1, p);
    }
    @Override public void addView(View child, LayoutParams params) { addView(child, -1, params); }
    public void addView(View child, int index, LayoutParams params) {
        requestLayout();
        invalidate();
        addViewInner(child, index, params);
    }
    protected boolean addViewInLayout(View child, int index, LayoutParams params) { addViewInner(child, index, params); return true; }
    protected boolean addViewInLayout(View child, int index, LayoutParams params, boolean preventRequestLayout) { addViewInner(child, index, params); return true; }

    private void addViewInner(View child, int index, LayoutParams params) {
        if (child == null) throw new IllegalArgumentException("Cannot add a null child view to a ViewGroup");
        if (child.getParent() != null) throw new IllegalStateException("The specified child already has a parent. You must call removeView() on the child's parent first.");
        if (!checkLayoutParams(params)) params = generateLayoutParams(params);
        child.setLayoutParamsSilently(params);
        if (index < 0 || index > children.size()) index = children.size();
        children.add(index, child);
        child.assignParent(this);
        if (root != null) child.dispatchAttachedToWindowTree(root);
        child.forceLayout();
        onViewAdded(child);
        if (hierarchy != null) hierarchy.onChildViewAdded(this, child);
        if (child.pendingFocus && root != null) { child.pendingFocus = false; child.requestFocus(); }
    }

    public void onViewAdded(View child) {}
    public void onViewRemoved(View child) {}

    void onSetLayoutParams(View child, LayoutParams p) {}

    @Override public void removeView(View v) { int i = children.indexOf(v); if (i >= 0) removeViewAt(i); }
    public void removeViewInLayout(View v) { removeView(v); }
    public void removeViewsInLayout(int start, int count) { removeViews(start, count); }
    public void removeViewAt(int index) {
        View v = children.remove(index);
        detached(v);
        requestLayout();
        invalidate();
    }
    public void removeViews(int start, int count) { for (int i = start + count - 1; i >= start; i--) removeViewAt(i); }
    public void removeAllViews() {
        if (children.isEmpty()) return;
        while (!children.isEmpty()) detached(children.remove(children.size() - 1));
        requestLayout();
        invalidate();
    }
    public void removeAllViewsInLayout() { removeAllViews(); }
    protected void detachViewFromParent(View v) { int i = children.indexOf(v); if (i >= 0) { children.remove(i); v.assignParent(null); } }
    protected void detachAllViewsFromParent() { for (View v : children) v.assignParent(null); children.clear(); }
    protected void attachViewToParent(View v, int index, LayoutParams p) { v.setLayoutParamsSilently(p); children.add(index < 0 ? children.size() : index, v); v.assignParent(this); }

    private void detached(View v) {
        if (v == focusedChild) {
            focusedChild = null;
            if (root != null) root.focusLost(v);
        }
        if (v == touchTarget) touchTarget = null;
        if (v == hoverTarget) hoverTarget = null;
        if (v.root != null) v.dispatchDetachedFromWindowTree();
        v.assignParent(null);
        onViewRemoved(v);
        if (hierarchy != null) hierarchy.onChildViewRemoved(this, v);
    }

    @Override public void updateViewLayout(View v, LayoutParams p) { v.setLayoutParams(p); }

    public void bringChildToFront(View child) {
        int i = children.indexOf(child);
        if (i >= 0 && i != children.size() - 1) {
            children.remove(i);
            children.add(child);
            requestLayout();
            invalidate();
        }
    }

    public void setOnHierarchyChangeListener(OnHierarchyChangeListener l) { hierarchy = l; }
    public void setLayoutTransition(LayoutTransition t) { transition = t; }
    public LayoutTransition getLayoutTransition() { return transition; }
    public void setClipChildren(boolean c) { clipChildren = c; invalidate(); }
    public boolean getClipChildren() { return clipChildren; }
    public void setClipToPadding(boolean c) { clipToPadding = c; invalidate(); }
    public boolean getClipToPadding() { return clipToPadding; }
    public void setMotionEventSplittingEnabled(boolean s) { motionSplitting = s; }
    public boolean isMotionEventSplittingEnabled() { return motionSplitting; }
    public void setDescendantFocusability(int f) { descendantFocusability = f; }
    public int getDescendantFocusability() { return descendantFocusability; }
    public void setAddStatesFromChildren(boolean a) {}
    public void setChildrenDrawingOrderEnabled(boolean e) { childrenDrawingOrder = e; }
    protected int getChildDrawingOrder(int count, int i) { return i; }
    public void setTouchscreenBlocksFocus(boolean b) {}
    public void setTransitionGroup(boolean g) {}
    public void setLayoutMode(int m) {}
    public void setPersistentDrawingCache(int c) {}
    public void setAlwaysDrawnWithCacheEnabled(boolean a) {}
    public void setAnimationCacheEnabled(boolean a) {}
    public void suppressLayout(boolean s) {}
    public boolean shouldDelayChildPressedState() { return false; }
    public void startViewTransition(View v) {}
    public void endViewTransition(View v) {}
    public void setStaticTransformationsEnabled(boolean e) {}

    protected LayoutParams generateDefaultLayoutParams() { return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); }
    protected LayoutParams generateLayoutParams(LayoutParams p) { return p; }
    public LayoutParams generateLayoutParams(android.util.AttributeSet a) { return generateDefaultLayoutParams(); }
    protected boolean checkLayoutParams(LayoutParams p) { return p != null; }

    @Override
    protected View findViewTraversal(int id) {
        if (id == getId()) return this;
        for (View v : children) {
            View r = v.findViewTraversal(id);
            if (r != null) return r;
        }
        return null;
    }

    @Override
    protected View findViewWithTagTraversal(Object t) {
        if (t.equals(getTag())) return this;
        for (View v : children) {
            View r = v.findViewWithTagTraversal(t);
            if (r != null) return r;
        }
        return null;
    }

    public void findViewsWithText(ArrayList<View> out, CharSequence text, int flags) {}

    // ---------------------------------------------------------------- ventana

    @Override
    void dispatchAttachedToWindowTree(ViewRoot r) {
        dispatchAttachedToWindow(r);
        for (View v : new ArrayList<>(children)) v.dispatchAttachedToWindowTree(r);
    }

    @Override
    void dispatchDetachedFromWindowTree() {
        for (View v : new ArrayList<>(children)) v.dispatchDetachedFromWindowTree();
        touchTarget = null;
        hoverTarget = null;
        selfHovered = false;
        dispatchDetachedFromWindow();
    }

    @Override
    public WindowInsets dispatchApplyWindowInsets(WindowInsets in) {
        WindowInsets out = super.dispatchApplyWindowInsets(in);
        if (!out.isConsumed()) for (View v : children) v.dispatchApplyWindowInsets(out);
        return out;
    }

    @Override
    void dispatchConfigurationChangedTree(android.content.res.Configuration c) {
        super.dispatchConfigurationChangedTree(c);
        for (View v : new ArrayList<>(children)) v.dispatchConfigurationChangedTree(c);
    }

    @Override
    void dispatchWindowFocus(boolean f) {
        super.dispatchWindowFocus(f);
        for (View v : new ArrayList<>(children)) v.dispatchWindowFocus(f);
    }

    // ---------------------------------------------------------------- medida

    protected void measureChildren(int ws, int hs) {
        for (View c : children) if (c.getVisibility() != GONE) measureChild(c, ws, hs);
    }

    protected void measureChild(View child, int ws, int hs) {
        LayoutParams lp = child.getLayoutParams();
        int cw = getChildMeasureSpec(ws, mPaddingLeft + mPaddingRight, lp.width);
        int ch = getChildMeasureSpec(hs, mPaddingTop + mPaddingBottom, lp.height);
        child.measure(cw, ch);
    }

    protected void measureChildWithMargins(View child, int ws, int widthUsed, int hs, int heightUsed) {
        MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
        int cw = getChildMeasureSpec(ws, mPaddingLeft + mPaddingRight + lp.leftMargin + lp.rightMargin + widthUsed, lp.width);
        int ch = getChildMeasureSpec(hs, mPaddingTop + mPaddingBottom + lp.topMargin + lp.bottomMargin + heightUsed, lp.height);
        child.measure(cw, ch);
    }

    public static int getChildMeasureSpec(int spec, int padding, int childDimension) {
        int specMode = MeasureSpec.getMode(spec), specSize = MeasureSpec.getSize(spec);
        int size = Math.max(0, specSize - padding);
        int resultSize = 0, resultMode = 0;
        switch (specMode) {
            case MeasureSpec.EXACTLY:
                if (childDimension >= 0) { resultSize = childDimension; resultMode = MeasureSpec.EXACTLY; }
                else if (childDimension == LayoutParams.MATCH_PARENT) { resultSize = size; resultMode = MeasureSpec.EXACTLY; }
                else if (childDimension == LayoutParams.WRAP_CONTENT) { resultSize = size; resultMode = MeasureSpec.AT_MOST; }
                break;
            case MeasureSpec.AT_MOST:
                if (childDimension >= 0) { resultSize = childDimension; resultMode = MeasureSpec.EXACTLY; }
                else if (childDimension == LayoutParams.MATCH_PARENT) { resultSize = size; resultMode = MeasureSpec.AT_MOST; }
                else if (childDimension == LayoutParams.WRAP_CONTENT) { resultSize = size; resultMode = MeasureSpec.AT_MOST; }
                break;
            default:
                if (childDimension >= 0) { resultSize = childDimension; resultMode = MeasureSpec.EXACTLY; }
                else { resultSize = size; resultMode = MeasureSpec.UNSPECIFIED; }
        }
        return MeasureSpec.makeMeasureSpec(resultSize, resultMode);
    }

    @Override
    protected abstract void onLayout(boolean changed, int l, int t, int r, int b);

    @Override
    public void requestLayout() {
        super.requestLayout();
    }

    @Override public boolean isLayoutRequested() { return layoutRequested; }

    // ---------------------------------------------------------------- dibujo

    /** Hijos en orden de dibujo: por altura (Z) si alguno la tiene, como en Android. */
    ArrayList<View> drawingOrder() {
        ArrayList<View> list = new ArrayList<>(children);
        if (childrenDrawingOrder) {
            ArrayList<View> o = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) o.add(list.get(getChildDrawingOrder(list.size(), i)));
            list = o;
        }
        boolean z = false;
        for (View v : list) if (v.getZ() != 0) { z = true; break; }
        if (z) {
            // Orden estable por Z.
            ArrayList<View> sorted = new ArrayList<>(list);
            Collections.sort(sorted, (a, b) -> Float.compare(a.getZ(), b.getZ()));
            list = sorted;
        }
        return list;
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        int save = c.save();
        if (clipToPadding && (mPaddingLeft | mPaddingTop | mPaddingRight | mPaddingBottom) != 0) {
            c.clipRect(mScrollX + mPaddingLeft, mScrollY + mPaddingTop, mScrollX + getWidth() - mPaddingRight, mScrollY + getHeight() - mPaddingBottom);
        }
        boolean more = false;
        for (View v : drawingOrder()) more |= drawChild(c, v, 0);
        c.restoreToCount(save);
        if (more) invalidate();
    }

    protected boolean drawChild(Canvas c, View child, long drawingTime) { return child.drawInParent(c, this); }

    public void childDrawableStateChanged(View child) {}

    @Override public void invalidateChild(View child, Rect r) { invalidate(); }
    @Override public ViewParent invalidateChildInParent(int[] location, Rect r) { return getParent(); }

    // ---------------------------------------------------------------- toques

    public boolean onInterceptTouchEvent(MotionEvent e) { return false; }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallow) {
        disallowIntercept = disallow;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(disallow);
    }

    /** El punto (en esta vista) cae dentro del hijo; deja en `out` el punto en el hijo. */
    boolean hitChild(View child, float x, float y, float[] out) {
        float px = x + mScrollX - child.mLeft - child.getTranslationX();
        float py = y + mScrollY - child.mTop - child.getTranslationY();
        if (child.hasTransform()) {
            Matrix inv = new Matrix();
            if (child.getMatrix().invert(inv)) {
                float[] p = {px, py};
                inv.mapPoints(p);
                px = p[0]; py = p[1];
            }
        }
        if (out != null) { out[0] = px; out[1] = py; }
        return px >= 0 && py >= 0 && px < child.getWidth() && py < child.getHeight();
    }

    private boolean dispatchTransformed(MotionEvent e, boolean cancel, View child) {
        MotionEvent t = MotionEvent.obtain(e);
        if (cancel) t.setAction(MotionEvent.ACTION_CANCEL);
        if (child == null) return super.dispatchTouchEvent(t);
        t.offsetLocation(mScrollX - child.mLeft - child.getTranslationX(), mScrollY - child.mTop - child.getTranslationY());
        if (child.hasTransform()) {
            Matrix inv = new Matrix();
            if (child.getMatrix().invert(inv)) t.transform(inv);
        }
        return child.dispatchTouchEvent(t);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (touchTarget != null) { dispatchTransformed(e, true, touchTarget); touchTarget = null; }
            disallowIntercept = false;
        }
        boolean intercepted;
        if (action == MotionEvent.ACTION_DOWN || touchTarget != null) {
            intercepted = !disallowIntercept && onInterceptTouchEvent(e);
        } else {
            intercepted = true;
        }
        boolean canceled = action == MotionEvent.ACTION_CANCEL;
        boolean handled = false, dispatchedNew = false;
        if (!canceled && !intercepted && action == MotionEvent.ACTION_DOWN) {
            float x = e.getX(), y = e.getY();
            ArrayList<View> order = drawingOrder();
            for (int i = order.size() - 1; i >= 0; i--) {
                View child = order.get(i);
                if (child.getVisibility() != VISIBLE && child.getAnimation() == null) continue;
                if (!hitChild(child, x, y, null)) continue;
                if (dispatchTransformed(e, false, child)) {
                    touchTarget = child;
                    dispatchedNew = true;
                    break;
                }
            }
        }
        if (touchTarget == null) {
            handled = dispatchTransformed(e, canceled, null);
        } else if (dispatchedNew) {
            handled = true;
        } else {
            View target = touchTarget;
            boolean cancelChild = intercepted || canceled;
            handled = dispatchTransformed(e, cancelChild, target);
            if (cancelChild) touchTarget = null;
        }
        if (canceled || action == MotionEvent.ACTION_UP) {
            touchTarget = null;
            disallowIntercept = false;
        }
        return handled;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_HOVER_ENTER || a == MotionEvent.ACTION_HOVER_MOVE || a == MotionEvent.ACTION_HOVER_EXIT) return dispatchHoverEvent(e);
        if (onInterceptHoverEvent(e)) return super.dispatchGenericMotionEvent(e);
        float x = e.getX(), y = e.getY();
        ArrayList<View> order = drawingOrder();
        for (int i = order.size() - 1; i >= 0; i--) {
            View child = order.get(i);
            if (child.getVisibility() != VISIBLE) continue;
            if (!hitChild(child, x, y, null)) continue;
            MotionEvent t = MotionEvent.obtain(e);
            t.offsetLocation(mScrollX - child.mLeft - child.getTranslationX(), mScrollY - child.mTop - child.getTranslationY());
            if (child.dispatchGenericMotionEvent(t)) return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    public boolean onInterceptHoverEvent(MotionEvent e) { return false; }

    private boolean sendHover(View child, MotionEvent e, int action) {
        MotionEvent t = MotionEvent.obtain(e);
        t.setAction(action);
        if (child == null) return super.dispatchHoverEvent(t);
        t.offsetLocation(mScrollX - child.mLeft - child.getTranslationX(), mScrollY - child.mTop - child.getTranslationY());
        if (child.hasTransform()) {
            Matrix inv = new Matrix();
            if (child.getMatrix().invert(inv)) t.transform(inv);
        }
        return child.dispatchHoverEvent(t);
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent e) {
        int action = e.getActionMasked();
        boolean intercept = onInterceptHoverEvent(e);
        View next = null;
        boolean handled = false;
        if (action != MotionEvent.ACTION_HOVER_EXIT && !intercept) {
            float x = e.getX(), y = e.getY();
            ArrayList<View> order = drawingOrder();
            for (int i = order.size() - 1; i >= 0; i--) {
                View child = order.get(i);
                if (child.getVisibility() != VISIBLE || !hitChild(child, x, y, null)) continue;
                boolean r = sendHover(child, e, child == hoverTarget ? MotionEvent.ACTION_HOVER_MOVE : MotionEvent.ACTION_HOVER_ENTER);
                if (r) { next = child; handled = true; break; }
                // No lo quiso: si lo tenía, sale.
                if (child == hoverTarget) { sendHover(child, e, MotionEvent.ACTION_HOVER_EXIT); hoverTarget = null; }
                else sendHover(child, e, MotionEvent.ACTION_HOVER_EXIT);
            }
        }
        if (hoverTarget != null && hoverTarget != next) sendHover(hoverTarget, e, MotionEvent.ACTION_HOVER_EXIT);
        hoverTarget = next;
        if (!handled && action != MotionEvent.ACTION_HOVER_EXIT) {
            handled = sendHover(null, e, selfHovered ? MotionEvent.ACTION_HOVER_MOVE : MotionEvent.ACTION_HOVER_ENTER);
            selfHovered = true;
        } else if (selfHovered) {
            sendHover(null, e, MotionEvent.ACTION_HOVER_EXIT);
            selfHovered = false;
        }
        return handled;
    }

    // ---------------------------------------------------------------- teclas y foco

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (isFocused()) return super.dispatchKeyEvent(e);
        if (focusedChild != null && focusedChild.dispatchKeyEvent(e)) return true;
        return false;
    }

    @Override
    public boolean dispatchKeyEventPreIme(KeyEvent e) {
        if (focusedChild != null) return focusedChild.dispatchKeyEventPreIme(e);
        return super.dispatchKeyEventPreIme(e);
    }

    @Override
    public View findFocus() {
        if (isFocused()) return this;
        return focusedChild != null ? focusedChild.findFocus() : null;
    }

    @Override public boolean hasFocus() { return isFocused() || focusedChild != null; }
    public View getFocusedChild() { return focusedChild; }

    @Override
    public boolean requestFocus(int direction, Rect previous) {
        if (getVisibility() != VISIBLE) return false;
        if (descendantFocusability == FOCUS_BLOCK_DESCENDANTS) return super.requestFocus(direction, previous);
        if (descendantFocusability == FOCUS_BEFORE_DESCENDANTS && isFocusable() && super.requestFocus(direction, previous)) return true;
        for (View v : children) if (v.getVisibility() == VISIBLE && v.requestFocus(direction, previous)) return true;
        return descendantFocusability == FOCUS_AFTER_DESCENDANTS && super.requestFocus(direction, previous);
    }

    @Override public void requestChildFocus(View child, View focused) { focusedChild = child; if (getParent() != null) getParent().requestChildFocus(this, focused); }
    @Override public void clearChildFocus(View child) { focusedChild = null; if (getParent() != null) getParent().clearChildFocus(this); }
    @Override public void clearFocus() { if (focusedChild != null) { View f = findFocus(); if (f != null) f.clearFocus(); } else super.clearFocus(); }
    @Override public void focusableViewAvailable(View v) {}
    @Override public View focusSearch(View focused, int direction) { return getParent() != null ? getParent().focusSearch(focused, direction) : FocusFinder.find(this, focused, direction); }
    @Override public boolean requestChildRectangleOnScreen(View child, Rect r, boolean immediate) { return false; }
    @Override public boolean showContextMenuForChild(View v) { return getParent() != null && getParent().showContextMenuForChild(v); }
    @Override public void childHasTransientStateChanged(View child, boolean t) {}
    @Override public boolean onStartNestedScroll(View child, View target, int axes) { return false; }
    @Override public void onNestedScrollAccepted(View child, View target, int axes) {}
    @Override public void onStopNestedScroll(View target) {}
    @Override public void onNestedScroll(View target, int dxc, int dyc, int dxu, int dyu) {}
    @Override public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) {}
    @Override public boolean onNestedFling(View target, float vx, float vy, boolean consumed) { return false; }
    @Override public boolean onNestedPreFling(View target, float vx, float vy) { return false; }
    @Override public void bringChildToFrontParent(View child) { bringChildToFront(child); }
    @Override public void recomputeViewAttributes(View child) {}
    @Override public void requestTransparentRegion(View child) {}
    @Override public void createContextMenu(ContextMenu menu) {}

    @Override
    protected void dispatchSetPressed(boolean p) {
        for (View v : children) if (!p || (!v.isClickable() && !v.isLongClickable())) if (v.isDuplicateParentStateEnabled()) v.setPressed(p);
    }

    @Override
    protected void dispatchSetSelected(boolean s) { for (View v : children) v.setSelected(s); }

    @Override
    protected void dispatchSetActivated(boolean a) { for (View v : children) v.setActivated(a); }

    @Override
    public void setEnabled(boolean e) { super.setEnabled(e); }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        for (View v : children) if (v.isDuplicateParentStateEnabled()) v.refreshDrawableState();
    }
}
