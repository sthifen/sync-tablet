package android.view;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.MainQueue;
import android.os.SystemClock;
import android.util.SparseArray;
import android.util.Property;
import android.os.IBinder;
import android.view.animation.Animation;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * La vista de Android, hecha a mano sobre Java2D: mide, se coloca, se
 * dibuja con su fondo, recibe toques (ratón) y teclas, y tiene foco.
 */
public class View implements Drawable.Callback, KeyEvent.Callback {
    public static final int VISIBLE = 0, INVISIBLE = 4, GONE = 8;
    public static final int NO_ID = -1;
    public static final int LAYER_TYPE_NONE = 0, LAYER_TYPE_SOFTWARE = 1, LAYER_TYPE_HARDWARE = 2;
    public static final int FOCUS_BACKWARD = 1, FOCUS_FORWARD = 2, FOCUS_LEFT = 17, FOCUS_UP = 33, FOCUS_RIGHT = 66, FOCUS_DOWN = 130;
    public static final int SYSTEM_UI_FLAG_VISIBLE = 0, SYSTEM_UI_FLAG_LOW_PROFILE = 1, SYSTEM_UI_FLAG_HIDE_NAVIGATION = 2, SYSTEM_UI_FLAG_FULLSCREEN = 4,
            SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR = 0x10, SYSTEM_UI_FLAG_LAYOUT_STABLE = 0x100, SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION = 0x200,
            SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN = 0x400, SYSTEM_UI_FLAG_IMMERSIVE = 0x800, SYSTEM_UI_FLAG_IMMERSIVE_STICKY = 0x1000,
            SYSTEM_UI_FLAG_LIGHT_STATUS_BAR = 0x2000;
    public static final int IMPORTANT_FOR_ACCESSIBILITY_AUTO = 0, IMPORTANT_FOR_ACCESSIBILITY_YES = 1, IMPORTANT_FOR_ACCESSIBILITY_NO = 2,
            IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS = 4;
    public static final int IMPORTANT_FOR_AUTOFILL_NO = 2, IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS = 8;
    public static final int OVER_SCROLL_ALWAYS = 0, OVER_SCROLL_IF_CONTENT_SCROLLS = 1, OVER_SCROLL_NEVER = 2;
    public static final int SCROLLBARS_INSIDE_OVERLAY = 0, SCROLLBARS_INSIDE_INSET = 0x01000000, SCROLLBARS_OUTSIDE_OVERLAY = 0x02000000, SCROLLBARS_OUTSIDE_INSET = 0x03000000;
    public static final int SCROLL_AXIS_NONE = 0, SCROLL_AXIS_HORIZONTAL = 1, SCROLL_AXIS_VERTICAL = 2;
    public static final int TEXT_ALIGNMENT_INHERIT = 0, TEXT_ALIGNMENT_GRAVITY = 1, TEXT_ALIGNMENT_TEXT_START = 2, TEXT_ALIGNMENT_TEXT_END = 3,
            TEXT_ALIGNMENT_CENTER = 4, TEXT_ALIGNMENT_VIEW_START = 5, TEXT_ALIGNMENT_VIEW_END = 6;
    public static final int TEXT_DIRECTION_LTR = 3, LAYOUT_DIRECTION_LTR = 0, LAYOUT_DIRECTION_RTL = 1, LAYOUT_DIRECTION_INHERIT = 2, LAYOUT_DIRECTION_LOCALE = 3;
    public static final int MEASURED_SIZE_MASK = 0x00ffffff, MEASURED_STATE_MASK = 0xff000000, MEASURED_HEIGHT_STATE_SHIFT = 16, MEASURED_STATE_TOO_SMALL = 0x01000000;
    public static final int DRAG_FLAG_GLOBAL = 256;
    public static final int FOCUSABLE_AUTO = 0x10, NOT_FOCUSABLE = 0, FOCUSABLE = 1;
    public static final int HAPTIC_FEEDBACK_ENABLED = 1;
    public static final int AUTOFILL_TYPE_NONE = 0;
    public static final int SCREEN_STATE_ON = 1;
    public static final int SCROLL_INDICATOR_TOP = 1, SCROLL_INDICATOR_BOTTOM = 2;

    public static final Property<View, Float> ALPHA = new FloatProp("alpha") { @Override public void setValue(View v, float f) { v.setAlpha(f); } @Override public Float get(View v) { return v.getAlpha(); } };
    public static final Property<View, Float> TRANSLATION_X = new FloatProp("translationX") { @Override public void setValue(View v, float f) { v.setTranslationX(f); } @Override public Float get(View v) { return v.getTranslationX(); } };
    public static final Property<View, Float> TRANSLATION_Y = new FloatProp("translationY") { @Override public void setValue(View v, float f) { v.setTranslationY(f); } @Override public Float get(View v) { return v.getTranslationY(); } };
    public static final Property<View, Float> SCALE_X = new FloatProp("scaleX") { @Override public void setValue(View v, float f) { v.setScaleX(f); } @Override public Float get(View v) { return v.getScaleX(); } };
    public static final Property<View, Float> SCALE_Y = new FloatProp("scaleY") { @Override public void setValue(View v, float f) { v.setScaleY(f); } @Override public Float get(View v) { return v.getScaleY(); } };
    public static final Property<View, Float> ROTATION = new FloatProp("rotation") { @Override public void setValue(View v, float f) { v.setRotation(f); } @Override public Float get(View v) { return v.getRotation(); } };

    abstract static class FloatProp extends Property<View, Float> {
        FloatProp(String n) { super(Float.class, n); }
        public abstract void setValue(View v, float f);
        @Override public void set(View v, Float f) { setValue(v, f); }
    }

    // ---------------------------------------------------------------- interfaces

    public interface OnClickListener { void onClick(View v); }
    public interface OnLongClickListener { boolean onLongClick(View v); }
    public interface OnTouchListener { boolean onTouch(View v, MotionEvent e); }
    public interface OnHoverListener { boolean onHover(View v, MotionEvent e); }
    public interface OnGenericMotionListener { boolean onGenericMotion(View v, MotionEvent e); }
    public interface OnKeyListener { boolean onKey(View v, int keyCode, KeyEvent e); }
    public interface OnFocusChangeListener { void onFocusChange(View v, boolean hasFocus); }
    public interface OnLayoutChangeListener { void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob); }
    public interface OnAttachStateChangeListener { void onViewAttachedToWindow(View v); void onViewDetachedFromWindow(View v); }
    public interface OnApplyWindowInsetsListener { WindowInsets onApplyWindowInsets(View v, WindowInsets insets); }
    public interface OnScrollChangeListener { void onScrollChange(View v, int x, int y, int ox, int oy); }
    public interface OnSystemUiVisibilityChangeListener { void onSystemUiVisibilityChange(int visibility); }
    public interface OnDragListener { boolean onDrag(View v, DragEvent e); }
    public interface OnContextClickListener { boolean onContextClick(View v); }
    public interface OnCreateContextMenuListener { void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo info); }
    public interface OnUnhandledKeyEventListener { boolean onUnhandledKeyEvent(View v, KeyEvent e); }

    public static class DragShadowBuilder {
        private final View view;
        public DragShadowBuilder(View v) { view = v; }
        public DragShadowBuilder() { view = null; }
        public final View getView() { return view; }
        public void onProvideShadowMetrics(Point size, Point touch) { if (view != null) { size.set(view.getWidth(), view.getHeight()); touch.set(size.x / 2, size.y / 2); } }
        public void onDrawShadow(Canvas c) { if (view != null) view.draw(c); }
    }

    public static class BaseSavedState extends AbsSavedState {
        public BaseSavedState(android.os.Parcelable superState) { super(superState); }
    }

    public static class AbsSavedState implements android.os.Parcelable {
        public static final AbsSavedState EMPTY_STATE = new AbsSavedState(null);
        private final android.os.Parcelable superState;
        protected AbsSavedState(android.os.Parcelable s) { superState = s; }
        public final android.os.Parcelable getSuperState() { return superState; }
    }

    // ---------------------------------------------------------------- MeasureSpec

    public static class MeasureSpec {
        private static final int MODE_SHIFT = 30, MODE_MASK = 0x3 << MODE_SHIFT;
        public static final int UNSPECIFIED = 0, EXACTLY = 1 << MODE_SHIFT, AT_MOST = 2 << MODE_SHIFT;

        public static int makeMeasureSpec(int size, int mode) { return (size & ~MODE_MASK) | (mode & MODE_MASK); }
        public static int makeSafeMeasureSpec(int size, int mode) { return makeMeasureSpec(size, mode); }
        public static int getMode(int spec) { return spec & MODE_MASK; }
        public static int getSize(int spec) { return spec & ~MODE_MASK; }
        public static String toString(int spec) {
            int m = getMode(spec);
            return "MeasureSpec: " + (m == EXACTLY ? "EXACTLY " : m == AT_MOST ? "AT_MOST " : "UNSPECIFIED ") + getSize(spec);
        }
    }

    // ---------------------------------------------------------------- estado

    private static int nextId = 0x00FFFFFF;
    public static int generateViewId() { return ++nextId; }

    protected Context mContext;
    ViewParent mParent;
    ViewRoot root;
    private int id = NO_ID;
    private Object tag;
    private SparseArray<Object> keyedTags;
    protected int mLeft, mTop, mRight, mBottom;
    protected int mPaddingLeft, mPaddingTop, mPaddingRight, mPaddingBottom;
    protected int mScrollX, mScrollY;
    private int measuredWidth, measuredHeight;
    private int minWidth, minHeight;
    private int oldWidthSpec = Integer.MIN_VALUE, oldHeightSpec = Integer.MIN_VALUE;
    boolean layoutRequested = true, forceLayout = true;
    private boolean measured;
    private boolean laidOut;
    private float translationX, translationY, translationZ, elevation, scaleX = 1, scaleY = 1, rotation, rotationX, rotationY, alpha = 1, pivotX = Float.NaN, pivotY = Float.NaN;
    private int visibility = VISIBLE;
    private Drawable background, foreground;
    private ViewGroup.LayoutParams layoutParams;
    private boolean clickable, longClickable, contextClickable, focusable, focusableInTouchMode, enabled = true, selected, activated, pressed, hovered;
    private boolean willNotDraw, clipToOutline, hapticEnabled = true, soundEnabled = true, keepScreenOn, duplicateParentState;
    private boolean hasFocus;
    private ViewOutlineProvider outlineProvider = ViewOutlineProvider.BACKGROUND;
    private CharSequence contentDescription, tooltip;
    private int systemUiVisibility;
    private int layerType = LAYER_TYPE_NONE;
    private int importantForAccessibility, scrollBarStyle, overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS, textAlignment = TEXT_ALIGNMENT_GRAVITY;
    private boolean verticalScrollBarEnabled = true, horizontalScrollBarEnabled = true, nestedScrolling;
    private Rect clipBounds;
    private ViewPropertyAnimator animator;
    private Animation animation;
    private Matrix animMatrix;

    OnClickListener onClick;
    OnLongClickListener onLongClick;
    OnTouchListener onTouch;
    OnHoverListener onHover;
    OnGenericMotionListener onGeneric;
    OnKeyListener onKey;
    OnFocusChangeListener onFocusChange;
    OnApplyWindowInsetsListener onInsets;
    OnScrollChangeListener onScrollChange;
    OnSystemUiVisibilityChangeListener onSystemUi;
    OnDragListener onDrag;
    OnContextClickListener onContextClick;
    OnCreateContextMenuListener onCreateContextMenu;
    private CopyOnWriteArrayList<OnLayoutChangeListener> layoutListeners;
    private CopyOnWriteArrayList<OnAttachStateChangeListener> attachListeners;
    private ViewTreeObserver floatingObserver;

    public View(Context c) {
        mContext = c;
    }

    public View(Context c, android.util.AttributeSet attrs) { this(c); }
    public View(Context c, android.util.AttributeSet attrs, int defStyleAttr) { this(c); }
    public View(Context c, android.util.AttributeSet attrs, int defStyleAttr, int defStyleRes) { this(c); }

    public final Context getContext() { return mContext; }
    public Resources getResources() { return mContext == null ? Resources.getSystem() : mContext.getResources(); }
    public final ViewParent getParent() { return mParent; }
    public View getRootView() { View v = this; while (v.mParent instanceof View) v = (View) v.mParent; return v; }
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public Object getTag() { return tag; }
    public void setTag(Object t) { tag = t; }
    public Object getTag(int key) { return keyedTags == null ? null : keyedTags.get(key); }
    public void setTag(int key, Object t) { if (keyedTags == null) keyedTags = new SparseArray<>(); keyedTags.put(key, t); }

    @SuppressWarnings("unchecked")
    public final <T extends View> T findViewById(int id) { return id == NO_ID ? null : (T) findViewTraversal(id); }
    protected View findViewTraversal(int id) { return id == this.id ? this : null; }
    @SuppressWarnings("unchecked")
    public final <T extends View> T findViewWithTag(Object t) { return t == null ? null : (T) findViewWithTagTraversal(t); }
    protected View findViewWithTagTraversal(Object t) { return t.equals(tag) ? this : null; }
    @SuppressWarnings("unchecked")
    public final <T extends View> T requireViewById(int id) { View v = findViewById(id); if (v == null) throw new IllegalArgumentException("ID does not reference a View inside this View"); return (T) v; }

    // ---------------------------------------------------------------- geometría

    public final int getLeft() { return mLeft; }
    public final int getTop() { return mTop; }
    public final int getRight() { return mRight; }
    public final int getBottom() { return mBottom; }
    public final int getWidth() { return mRight - mLeft; }
    public final int getHeight() { return mBottom - mTop; }
    public void setLeft(int l) { setFrame(l, mTop, mRight, mBottom); }
    public void setTop(int t) { setFrame(mLeft, t, mRight, mBottom); }
    public void setRight(int r) { setFrame(mLeft, mTop, r, mBottom); }
    public void setBottom(int b) { setFrame(mLeft, mTop, mRight, b); }
    public void setLeftTopRightBottom(int l, int t, int r, int b) { setFrame(l, t, r, b); }
    public float getX() { return mLeft + translationX; }
    public float getY() { return mTop + translationY; }
    public float getZ() { return elevation + translationZ; }
    public void setX(float x) { setTranslationX(x - mLeft); }
    public void setY(float y) { setTranslationY(y - mTop); }
    public void setZ(float z) { setTranslationZ(z - elevation); }

    public void offsetTopAndBottom(int d) { if (d != 0) { mTop += d; mBottom += d; invalidateParent(); } }
    public void offsetLeftAndRight(int d) { if (d != 0) { mLeft += d; mRight += d; invalidateParent(); } }

    public int getPaddingLeft() { return mPaddingLeft; }
    public int getPaddingTop() { return mPaddingTop; }
    public int getPaddingRight() { return mPaddingRight; }
    public int getPaddingBottom() { return mPaddingBottom; }
    public int getPaddingStart() { return mPaddingLeft; }
    public int getPaddingEnd() { return mPaddingRight; }
    public boolean isPaddingRelative() { return false; }

    public void setPadding(int l, int t, int r, int b) {
        if (l != mPaddingLeft || t != mPaddingTop || r != mPaddingRight || b != mPaddingBottom) {
            mPaddingLeft = l; mPaddingTop = t; mPaddingRight = r; mPaddingBottom = b;
            requestLayout();
            invalidate();
        }
    }
    public void setPaddingRelative(int s, int t, int e, int b) { setPadding(s, t, e, b); }

    public int getMinimumWidth() { return minWidth; }
    public int getMinimumHeight() { return minHeight; }
    public void setMinimumWidth(int w) { minWidth = w; requestLayout(); }
    public void setMinimumHeight(int h) { minHeight = h; requestLayout(); }

    public final int getMeasuredWidth() { return measuredWidth & MEASURED_SIZE_MASK; }
    public final int getMeasuredHeight() { return measuredHeight & MEASURED_SIZE_MASK; }
    public final int getMeasuredWidthAndState() { return measuredWidth; }
    public final int getMeasuredHeightAndState() { return measuredHeight; }
    public final int getMeasuredState() { return (measuredWidth & MEASURED_STATE_MASK) | ((measuredHeight >> MEASURED_HEIGHT_STATE_SHIFT) & (MEASURED_STATE_MASK >> MEASURED_HEIGHT_STATE_SHIFT)); }

    protected final void setMeasuredDimension(int w, int h) { measuredWidth = w; measuredHeight = h; measured = true; }

    public final void measure(int widthSpec, int heightSpec) {
        boolean specChanged = widthSpec != oldWidthSpec || heightSpec != oldHeightSpec;
        if (forceLayout || specChanged) {
            measured = false;
            onMeasure(widthSpec, heightSpec);
            if (!measured) throw new IllegalStateException(getClass().getName() + "#onMeasure() did not set the measured dimension by calling setMeasuredDimension()");
            layoutRequested = true;
        }
        oldWidthSpec = widthSpec;
        oldHeightSpec = heightSpec;
    }

    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthSpec), getDefaultSize(getSuggestedMinimumHeight(), heightSpec));
    }

    protected int getSuggestedMinimumWidth() { return background == null ? minWidth : Math.max(minWidth, background.getMinimumWidth()); }
    protected int getSuggestedMinimumHeight() { return background == null ? minHeight : Math.max(minHeight, background.getMinimumHeight()); }

    public static int getDefaultSize(int size, int spec) {
        int mode = MeasureSpec.getMode(spec), s = MeasureSpec.getSize(spec);
        return mode == MeasureSpec.UNSPECIFIED ? size : s;
    }

    public static int resolveSize(int size, int spec) { return resolveSizeAndState(size, spec, 0) & MEASURED_SIZE_MASK; }

    public static int resolveSizeAndState(int size, int spec, int childState) {
        int mode = MeasureSpec.getMode(spec), s = MeasureSpec.getSize(spec);
        int result;
        if (mode == MeasureSpec.AT_MOST) result = s < size ? s | MEASURED_STATE_TOO_SMALL : size;
        else if (mode == MeasureSpec.EXACTLY) result = s;
        else result = size;
        return result | (childState & MEASURED_STATE_MASK);
    }

    public static int combineMeasuredStates(int a, int b) { return a | b; }

    public void layout(int l, int t, int r, int b) {
        int ol = mLeft, ot = mTop, or = mRight, ob = mBottom;
        boolean changed = setFrame(l, t, r, b);
        boolean wanted = changed || layoutRequested;
        // Se limpian antes: lo que se pida durante onLayout queda para la siguiente pasada.
        layoutRequested = false;
        forceLayout = false;
        laidOut = true;
        if (wanted) {
            onLayout(changed, l, t, r, b);
            if (layoutListeners != null) for (OnLayoutChangeListener li : layoutListeners) li.onLayoutChange(this, l, t, r, b, ol, ot, or, ob);
        }
    }

    protected boolean setFrame(int l, int t, int r, int b) {
        if (mLeft == l && mTop == t && mRight == r && mBottom == b) return false;
        int ow = mRight - mLeft, oh = mBottom - mTop;
        invalidateParent();
        mLeft = l; mTop = t; mRight = r; mBottom = b;
        int w = r - l, h = b - t;
        if (w != ow || h != oh) {
            if (background != null) background.setBounds(0, 0, w, h);
            if (foreground != null) foreground.setBounds(0, 0, w, h);
            onSizeChanged(w, h, ow, oh);
        }
        invalidate();
        return true;
    }

    protected void onLayout(boolean changed, int l, int t, int r, int b) {}
    protected void onSizeChanged(int w, int h, int ow, int oh) {}

    public boolean isLaidOut() { return laidOut; }
    public boolean isLayoutRequested() { return layoutRequested; }
    public boolean isInLayout() { return root != null && root.inLayout; }
    public void forceLayout() { forceLayout = true; layoutRequested = true; }

    public void requestLayout() {
        forceLayout = true;
        layoutRequested = true;
        if (mParent != null) mParent.requestLayout();
        else if (root != null) root.scheduleLayout();
    }

    public void addOnLayoutChangeListener(OnLayoutChangeListener l) { if (layoutListeners == null) layoutListeners = new CopyOnWriteArrayList<>(); layoutListeners.add(l); }
    public void removeOnLayoutChangeListener(OnLayoutChangeListener l) { if (layoutListeners != null) layoutListeners.remove(l); }

    public ViewGroup.LayoutParams getLayoutParams() { return layoutParams; }
    public void setLayoutParams(ViewGroup.LayoutParams p) {
        if (p == null) throw new NullPointerException("Layout parameters cannot be null");
        layoutParams = p;
        if (mParent instanceof ViewGroup) ((ViewGroup) mParent).onSetLayoutParams(this, p);
        requestLayout();
    }

    void assignParent(ViewParent p) { mParent = p; }

    // ---------------------------------------------------------------- transformaciones

    public float getTranslationX() { return translationX; }
    public float getTranslationY() { return translationY; }
    public float getTranslationZ() { return translationZ; }
    public void setTranslationX(float v) { if (v != translationX) { invalidateParent(); translationX = v; invalidateParent(); } }
    public void setTranslationY(float v) { if (v != translationY) { invalidateParent(); translationY = v; invalidateParent(); } }
    public void setTranslationZ(float v) { if (v != translationZ) { translationZ = v; invalidateParent(); } }
    public float getElevation() { return elevation; }
    public void setElevation(float e) { if (e != elevation) { elevation = e; invalidateParent(); } }
    public float getScaleX() { return scaleX; }
    public float getScaleY() { return scaleY; }
    public void setScaleX(float v) { if (v != scaleX) { invalidateParent(); scaleX = v; invalidateParent(); } }
    public void setScaleY(float v) { if (v != scaleY) { invalidateParent(); scaleY = v; invalidateParent(); } }
    public float getRotation() { return rotation; }
    public void setRotation(float v) { if (v != rotation) { invalidateParent(); rotation = v; invalidateParent(); } }
    public float getRotationX() { return rotationX; }
    public float getRotationY() { return rotationY; }
    public void setRotationX(float v) { rotationX = v; invalidateParent(); }
    public void setRotationY(float v) { rotationY = v; invalidateParent(); }
    public void setCameraDistance(float d) {}
    public float getPivotX() { return Float.isNaN(pivotX) ? getWidth() / 2f : pivotX; }
    public float getPivotY() { return Float.isNaN(pivotY) ? getHeight() / 2f : pivotY; }
    public void setPivotX(float v) { pivotX = v; invalidateParent(); }
    public void setPivotY(float v) { pivotY = v; invalidateParent(); }
    public void resetPivot() { pivotX = pivotY = Float.NaN; }
    public boolean isPivotSet() { return !Float.isNaN(pivotX) || !Float.isNaN(pivotY); }
    public float getAlpha() { return alpha; }
    public void setAlpha(float a) { a = Math.max(0, Math.min(1, a)); if (a != alpha) { alpha = a; invalidateParent(); } }
    public void setTransitionAlpha(float a) { setAlpha(a); }
    public boolean hasOverlappingRendering() { return true; }
    public void setHasTransientState(boolean t) {}
    public void setTransitionName(String n) {}

    boolean hasTransform() { return scaleX != 1 || scaleY != 1 || rotation != 0 || animMatrix != null; }

    /** Matriz de la vista respecto de su padre (sin la posición). */
    public Matrix getMatrix() {
        Matrix m = new Matrix();
        if (hasTransform()) {
            float px = getPivotX(), py = getPivotY();
            m.setTranslate(-px, -py);
            m.postScale(scaleX, scaleY);
            m.postRotate(rotation);
            m.postTranslate(px, py);
            if (animMatrix != null) m.postConcat(animMatrix);
        }
        return m;
    }

    void applyTransform(Canvas c) {
        c.translate(mLeft + translationX, mTop + translationY);
        if (hasTransform()) c.concat(getMatrix());
    }

    // ---------------------------------------------------------------- visibilidad y estado

    public int getVisibility() { return visibility; }
    public void setVisibility(int v) {
        if (v == visibility) return;
        int old = visibility;
        visibility = v;
        if (v == GONE || old == GONE) requestLayout();
        invalidateParent();
        if (v != VISIBLE && hasFocus) clearFocus();
        onVisibilityChanged(this, v);
        if (root != null && v != VISIBLE) root.viewHidden(this);
    }
    protected void onVisibilityChanged(View changed, int v) {}
    protected void onWindowVisibilityChanged(int v) {}
    public boolean isShown() {
        View v = this;
        while (true) {
            if (v.visibility != VISIBLE) return false;
            if (!(v.mParent instanceof View)) return v.root != null;
            v = (View) v.mParent;
        }
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean e) { if (enabled != e) { enabled = e; refreshDrawableState(); invalidate(); } }
    public boolean isClickable() { return clickable; }
    public void setClickable(boolean c) { clickable = c; }
    public boolean isLongClickable() { return longClickable; }
    public void setLongClickable(boolean c) { longClickable = c; }
    public boolean isContextClickable() { return contextClickable; }
    public void setContextClickable(boolean c) { contextClickable = c; }
    public boolean isFocusable() { return focusable; }
    public void setFocusable(boolean f) { focusable = f; if (!f) focusableInTouchMode = false; }
    public void setFocusable(int f) { setFocusable(f != NOT_FOCUSABLE); }
    public boolean isFocusableInTouchMode() { return focusableInTouchMode; }
    public void setFocusableInTouchMode(boolean f) { focusableInTouchMode = f; if (f) focusable = true; }
    public boolean isSelected() { return selected; }
    public void setSelected(boolean s) { if (selected != s) { selected = s; refreshDrawableState(); invalidate(); dispatchSetSelected(s); } }
    protected void dispatchSetSelected(boolean s) {}
    public boolean isActivated() { return activated; }
    public void setActivated(boolean a) { if (activated != a) { activated = a; refreshDrawableState(); invalidate(); dispatchSetActivated(a); } }
    protected void dispatchSetActivated(boolean a) {}
    public boolean isPressed() { return pressed; }
    public void setPressed(boolean p) { if (pressed != p) { pressed = p; refreshDrawableState(); dispatchSetPressed(p); } }
    protected void dispatchSetPressed(boolean p) {}
    public boolean isHovered() { return hovered; }
    public void setHovered(boolean h) { if (hovered != h) { hovered = h; refreshDrawableState(); onHoverChanged(h); } }
    public void onHoverChanged(boolean h) {}
    public void setDuplicateParentStateEnabled(boolean e) { duplicateParentState = e; }
    public boolean isDuplicateParentStateEnabled() { return duplicateParentState; }
    public void setWillNotDraw(boolean w) { willNotDraw = w; }
    public boolean willNotDraw() { return willNotDraw; }
    public void setWillNotCacheDrawing(boolean w) {}
    public void setDrawingCacheEnabled(boolean e) {}
    public boolean isHapticFeedbackEnabled() { return hapticEnabled; }
    public void setHapticFeedbackEnabled(boolean e) { hapticEnabled = e; }
    public boolean performHapticFeedback(int c) { return false; }
    public boolean performHapticFeedback(int c, int flags) { return false; }
    public void setSoundEffectsEnabled(boolean e) { soundEnabled = e; }
    public boolean isSoundEffectsEnabled() { return soundEnabled; }
    public void playSoundEffect(int s) {}
    public boolean getKeepScreenOn() { return keepScreenOn; }
    public void setKeepScreenOn(boolean k) { keepScreenOn = k; }
    public CharSequence getContentDescription() { return contentDescription; }
    public void setContentDescription(CharSequence d) { contentDescription = d; }
    public CharSequence getTooltipText() { return tooltip; }
    public void setTooltipText(CharSequence t) { tooltip = t; }
    public void setImportantForAccessibility(int m) { importantForAccessibility = m; }
    public int getImportantForAccessibility() { return importantForAccessibility; }
    public void setImportantForAutofill(int m) {}
    public void setAccessibilityDelegate(Object d) {}
    public void setAccessibilityHeading(boolean h) {}
    public void setAccessibilityLiveRegion(int m) {}
    public void setStateDescription(CharSequence s) {}
    public void announceForAccessibility(CharSequence t) {}
    public void sendAccessibilityEvent(int t) {}
    public void setLabelFor(int id) {}
    public void setAutofillHints(String... h) {}
    public void setLayerType(int t, Paint p) { layerType = t; }
    public int getLayerType() { return layerType; }
    public void setLayerPaint(Paint p) {}
    public void setSystemUiVisibility(int v) { systemUiVisibility = v; if (onSystemUi != null) onSystemUi.onSystemUiVisibilityChange(v); }
    public int getSystemUiVisibility() { return systemUiVisibility; }
    public int getWindowSystemUiVisibility() { return systemUiVisibility; }
    public void setOnSystemUiVisibilityChangeListener(OnSystemUiVisibilityChangeListener l) { onSystemUi = l; }
    public void setFitsSystemWindows(boolean f) {}
    public boolean getFitsSystemWindows() { return false; }
    public void setScrollBarStyle(int s) { scrollBarStyle = s; }
    public void setVerticalScrollBarEnabled(boolean e) { verticalScrollBarEnabled = e; }
    public void setHorizontalScrollBarEnabled(boolean e) { horizontalScrollBarEnabled = e; }
    public boolean isVerticalScrollBarEnabled() { return verticalScrollBarEnabled; }
    public boolean isHorizontalScrollBarEnabled() { return horizontalScrollBarEnabled; }
    public void setScrollbarFadingEnabled(boolean f) {}
    public void setScrollIndicators(int i) {}
    public void setVerticalFadingEdgeEnabled(boolean e) {}
    public void setHorizontalFadingEdgeEnabled(boolean e) {}
    public void setFadingEdgeLength(int l) {}
    public int getOverScrollMode() { return overScrollMode; }
    public void setOverScrollMode(int m) { overScrollMode = m; }
    public void setNestedScrollingEnabled(boolean e) { nestedScrolling = e; }
    public boolean isNestedScrollingEnabled() { return nestedScrolling; }
    public void setTextAlignment(int a) { textAlignment = a; invalidate(); }
    public int getTextAlignment() { return textAlignment; }
    public void setTextDirection(int d) {}
    public void setLayoutDirection(int d) {}
    public int getLayoutDirection() { return LAYOUT_DIRECTION_LTR; }
    public boolean isLayoutRtl() { return false; }
    public void setClipBounds(Rect r) { clipBounds = r == null ? null : new Rect(r); invalidate(); }
    public Rect getClipBounds() { return clipBounds == null ? null : new Rect(clipBounds); }
    public boolean isInEditMode() { return false; }
    public boolean isHardwareAccelerated() { return true; }
    public boolean isAttachedToWindow() { return root != null; }
    public boolean isInTouchMode() { return true; }
    public IBinder getWindowToken() { return root == null ? null : root.token; }
    public IBinder getApplicationWindowToken() { return getWindowToken(); }
    public Display getDisplay() { return root == null ? null : Display.DEFAULT; }
    public int getWindowVisibility() { return root == null ? GONE : VISIBLE; }
    public boolean hasWindowFocus() { return root != null && root.windowFocused; }
    public void onWindowFocusChanged(boolean f) {}
    public boolean isTemporarilyDetached() { return false; }
    public void setSaveEnabled(boolean e) {}
    public void setSaveFromParentEnabled(boolean e) {}
    public void setOnCreateContextMenuListener(OnCreateContextMenuListener l) { onCreateContextMenu = l; }
    public void setOnDragListener(OnDragListener l) { onDrag = l; }
    public final boolean startDragAndDrop(android.content.ClipData data, DragShadowBuilder shadow, Object local, int flags) { return false; }
    public final boolean startDrag(android.content.ClipData data, DragShadowBuilder shadow, Object local, int flags) { return false; }
    public void cancelDragAndDrop() {}
    public void addOnUnhandledKeyEventListener(OnUnhandledKeyEventListener l) {}
    public void setPointerIcon(PointerIcon p) { pointerIcon = p; if (root != null && hovered) root.updateCursor(); }
    public PointerIcon getPointerIcon() { return pointerIcon; }
    public PointerIcon onResolvePointerIcon(MotionEvent e, int pointerIndex) { return pointerIcon; }
    private PointerIcon pointerIcon;

    // ---------------------------------------------------------------- estado dibujable

    private static final int[] EMPTY = new int[0];

    protected int[] onCreateDrawableState(int extraSpace) {
        ArrayList<Integer> s = new ArrayList<>();
        if (enabled) s.add(android.R.attr.state_enabled);
        if (pressed) s.add(android.R.attr.state_pressed);
        if (hovered) s.add(android.R.attr.state_hovered);
        if (selected) s.add(android.R.attr.state_selected);
        if (activated) s.add(android.R.attr.state_activated);
        if (hasFocus) s.add(android.R.attr.state_focused);
        if (root != null && root.windowFocused) s.add(android.R.attr.state_window_focused);
        int[] out = new int[s.size() + extraSpace];
        for (int i = 0; i < s.size(); i++) out[i] = s.get(i);
        return out;
    }

    protected static int[] mergeDrawableStates(int[] base, int[] additional) {
        int i = base.length - 1;
        while (i >= 0 && base[i] == 0) i--;
        System.arraycopy(additional, 0, base, i + 1, additional.length);
        return base;
    }

    public final int[] getDrawableState() {
        if (duplicateParentState && mParent instanceof View) return ((View) mParent).getDrawableState();
        return onCreateDrawableState(0);
    }

    public void refreshDrawableState() {
        drawableStateChanged();
        if (mParent instanceof ViewGroup) ((ViewGroup) mParent).childDrawableStateChanged(this);
    }

    protected void drawableStateChanged() {
        int[] s = getDrawableState();
        boolean changed = false;
        if (background != null && background.isStateful()) changed |= background.setState(s);
        if (foreground != null && foreground.isStateful()) changed |= foreground.setState(s);
        if (changed) invalidate();
    }

    public void jumpDrawablesToCurrentState() {}
    public void drawableHotspotChanged(float x, float y) {}

    // ---------------------------------------------------------------- fondo

    public Drawable getBackground() { return background; }
    public void setBackground(Drawable d) {
        if (d == background) return;
        if (background != null) background.setCallback(null);
        background = d;
        if (d != null) {
            d.setCallback(this);
            d.setBounds(0, 0, getWidth(), getHeight());
            if (d.isStateful()) d.setState(getDrawableState());
            Rect pad = new Rect();
            if (d.getPadding(pad)) setPadding(pad.left, pad.top, pad.right, pad.bottom);
        }
        requestLayout();
        invalidate();
    }
    @Deprecated public void setBackgroundDrawable(Drawable d) { setBackground(d); }
    public void setBackgroundColor(int c) {
        if (background instanceof ColorDrawable) { ((ColorDrawable) background).setColor(c); invalidate(); }
        else setBackground(new ColorDrawable(c));
    }
    public void setBackgroundResource(int id) { setBackground(id == 0 ? null : getResources().getDrawable(id, null)); }
    public void setBackgroundTintList(android.content.res.ColorStateList c) { if (background != null) background.setTintList(c); invalidate(); }
    public void setBackgroundTintMode(android.graphics.PorterDuff.Mode m) {}
    public Drawable getForeground() { return foreground; }
    public void setForeground(Drawable d) {
        if (foreground != null) foreground.setCallback(null);
        foreground = d;
        if (d != null) { d.setCallback(this); d.setBounds(0, 0, getWidth(), getHeight()); if (d.isStateful()) d.setState(getDrawableState()); }
        invalidate();
    }
    public void setForegroundGravity(int g) {}

    public ViewOutlineProvider getOutlineProvider() { return outlineProvider; }
    public void setOutlineProvider(ViewOutlineProvider p) { outlineProvider = p; invalidateParent(); }
    public void invalidateOutline() { invalidateParent(); }
    public boolean getClipToOutline() { return clipToOutline; }
    public void setClipToOutline(boolean c) { clipToOutline = c; invalidateParent(); }
    public void setOutlineAmbientShadowColor(int c) {}
    public void setOutlineSpotShadowColor(int c) {}

    Outline outline() {
        if (outlineProvider == null || getWidth() <= 0 || getHeight() <= 0) return null;
        Outline o = new Outline();
        outlineProvider.getOutline(this, o);
        return o.isEmpty() ? null : o;
    }

    @Override public void invalidateDrawable(Drawable who) { invalidate(); }
    @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { MainQueue.postAt(this, what, when, who); }
    @Override public void unscheduleDrawable(Drawable who, Runnable what) { MainQueue.remove(this, what, who); }
    public void unscheduleDrawable(Drawable who) { MainQueue.remove(this, null, who); }
    protected boolean verifyDrawable(Drawable who) { return who == background || who == foreground; }

    // ---------------------------------------------------------------- dibujo

    public void invalidate() {
        if (root == null) return;
        int w = getWidth(), h = getHeight();
        if (!ViewRoot.PARTIAL || w <= 0 || h <= 0) { root.invalidate(); return; }
        // Las esquinas de la vista en la ventana (con giros y escalas), más un margen
        // para la sombra y el suavizado de los bordes.
        float[] pts = {0, 0, w, 0, 0, h, w, h};
        mapToWindow(pts);
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        for (int i = 0; i < pts.length; i += 2) {
            l = Math.min(l, pts[i]); r = Math.max(r, pts[i]);
            t = Math.min(t, pts[i + 1]); b = Math.max(b, pts[i + 1]);
        }
        int pad = 6 + (int) Math.ceil(Math.max(0, getZ()) * 3);
        root.invalidate(new java.awt.Rectangle((int) Math.floor(l) - pad, (int) Math.floor(t) - pad,
                (int) Math.ceil(r - l) + 2 * pad, (int) Math.ceil(b - t) + 2 * pad));
    }
    public void invalidate(Rect r) { invalidate(); }
    public void invalidate(int l, int t, int r, int b) { invalidate(); }
    public void postInvalidate() { MainQueue.post(this, this::invalidate, 0, null); }
    public void postInvalidate(int l, int t, int r, int b) { postInvalidate(); }
    public void postInvalidateDelayed(long d) { MainQueue.post(this, this::invalidate, d, null); }
    public void postInvalidateOnAnimation() { if (MainQueue.isMain()) invalidate(); else postInvalidate(); }
    public void postInvalidateOnAnimation(int l, int t, int r, int b) { postInvalidateOnAnimation(); }
    void invalidateParent() { invalidate(); }

    public void draw(Canvas c) {
        if (background != null) drawBackground(c);
        if (!willNotDraw || !(this instanceof ViewGroup)) onDraw(c);
        dispatchDraw(c);
        onDrawForeground(c);
    }

    private void drawBackground(Canvas c) {
        if (background.getBounds().width() != getWidth() || background.getBounds().height() != getHeight()) background.setBounds(0, 0, getWidth(), getHeight());
        if (mScrollX == 0 && mScrollY == 0) background.draw(c);
        else {
            c.translate(mScrollX, mScrollY);
            background.draw(c);
            c.translate(-mScrollX, -mScrollY);
        }
    }

    protected void onDraw(Canvas c) {}
    protected void dispatchDraw(Canvas c) {}

    public void onDrawForeground(Canvas c) {
        onDrawScrollBars(c);
        if (foreground != null) {
            foreground.setBounds(mScrollX, mScrollY, mScrollX + getWidth(), mScrollY + getHeight());
            foreground.draw(c);
        }
    }

    protected void onDrawScrollBars(Canvas c) {}

    public Bitmap getDrawingCache() { return null; }
    public void buildDrawingCache() {}
    public void destroyDrawingCache() {}

    /** Para ViewGroup.drawChild: la vista con su transformación, recorte, opacidad y sombra. */
    boolean drawInParent(Canvas c, ViewGroup parent) {
        if (animation != null) stepAnimation();
        if (visibility != VISIBLE && animation == null) return false;
        if (alpha <= 0) return false;
        int w = getWidth(), h = getHeight();
        computeScroll();
        int save = c.save();
        applyTransform(c);
        float z = getZ();
        // Fuera de lo que se repinta: ni se recorre (en un fotograma parcial ahorra casi todo).
        if (!hasTransform()) {
            float m = 8 + Math.max(0, z) * 3;
            if (c.quickReject(-m, -m, w + m, h + m)) { c.restoreToCount(save); return animation != null; }
        }
        if (z > 0.5f) Shadows.draw(c, this, z);
        boolean layer = alpha < 1 && hasOverlappingRendering();
        if (layer) c.saveLayerAlpha(0, 0, w, h, Math.round(alpha * 255));
        if (parent.getClipChildren()) c.clipRect(0, 0, w, h);
        if (clipBounds != null) c.clipRect(clipBounds);
        if (clipToOutline) {
            Outline o = outline();
            if (o != null) {
                Path p = new Path();
                if (o.path != null) p.addPath(o.path);
                else p.addRoundRect(new RectF(o.rect), o.radius, o.radius, Path.Direction.CW);
                c.clipPath(p);
            }
        }
        c.translate(-mScrollX, -mScrollY);
        draw(c);
        c.restoreToCount(save);
        return animation != null;
    }

    // ---------------------------------------------------------------- desplazamiento

    public final int getScrollX() { return mScrollX; }
    public final int getScrollY() { return mScrollY; }
    public void setScrollX(int x) { scrollTo(x, mScrollY); }
    public void setScrollY(int y) { scrollTo(mScrollX, y); }
    public void scrollTo(int x, int y) {
        if (mScrollX != x || mScrollY != y) {
            int ox = mScrollX, oy = mScrollY;
            mScrollX = x; mScrollY = y;
            onScrollChanged(x, y, ox, oy);
            invalidate();
        }
    }
    public void scrollBy(int x, int y) { scrollTo(mScrollX + x, mScrollY + y); }
    protected void onScrollChanged(int l, int t, int ol, int ot) { if (onScrollChange != null) onScrollChange.onScrollChange(this, l, t, ol, ot); }
    public void setOnScrollChangeListener(OnScrollChangeListener l) { onScrollChange = l; }
    public void computeScroll() {}
    protected int computeVerticalScrollRange() { return getHeight(); }
    protected int computeVerticalScrollOffset() { return Math.max(0, mScrollY); }
    protected int computeVerticalScrollExtent() { return getHeight(); }
    protected int computeHorizontalScrollRange() { return getWidth(); }
    protected int computeHorizontalScrollOffset() { return Math.max(0, mScrollX); }
    protected int computeHorizontalScrollExtent() { return getWidth(); }
    public boolean canScrollVertically(int dir) {
        int offset = computeVerticalScrollOffset(), range = computeVerticalScrollRange() - computeVerticalScrollExtent();
        if (range == 0) return false;
        return dir < 0 ? offset > 0 : offset < range - 1;
    }
    public boolean canScrollHorizontally(int dir) {
        int offset = computeHorizontalScrollOffset(), range = computeHorizontalScrollRange() - computeHorizontalScrollExtent();
        if (range == 0) return false;
        return dir < 0 ? offset > 0 : offset < range - 1;
    }
    protected boolean awakenScrollBars() { invalidate(); return true; }
    protected boolean overScrollBy(int dx, int dy, int sx, int sy, int rx, int ry, int mx, int my, boolean touch) {
        int nx = sx + dx, ny = sy + dy;
        boolean cx = false, cy = false;
        if (nx > rx + mx) { nx = rx + mx; cx = true; } else if (nx < -mx) { nx = -mx; cx = true; }
        if (ny > ry + my) { ny = ry + my; cy = true; } else if (ny < -my) { ny = -my; cy = true; }
        onOverScrolled(nx, ny, cx, cy);
        return cx || cy;
    }
    protected void onOverScrolled(int sx, int sy, boolean cx, boolean cy) { scrollTo(sx, sy); }
    public boolean requestRectangleOnScreen(Rect r) { return requestRectangleOnScreen(r, false); }
    public boolean requestRectangleOnScreen(Rect r, boolean immediate) {
        View child = this;
        ViewParent p = mParent;
        Rect pos = new Rect(r);
        boolean scrolled = false;
        while (p instanceof ViewGroup) {
            scrolled |= p.requestChildRectangleOnScreen(child, pos, immediate);
            View pv = (View) p;
            pos.offset(child.mLeft - child.mScrollX, child.mTop - child.mScrollY);
            child = pv;
            p = pv.mParent;
        }
        return scrolled;
    }

    // ---------------------------------------------------------------- posición en la ventana

    public void getLocationInWindow(int[] out) {
        float[] p = {0, 0};
        mapToWindow(p);
        out[0] = Math.round(p[0]);
        out[1] = Math.round(p[1]);
    }

    public void getLocationOnScreen(int[] out) {
        getLocationInWindow(out);
        if (root != null) { java.awt.Point s = root.screenOffset(); out[0] += s.x; out[1] += s.y; }
    }

    public void getLocationInSurface(int[] out) { getLocationInWindow(out); }

    /** Puntos de la vista → ventana. */
    public void mapToWindow(float[] pts) {
        View v = this;
        while (v != null) {
            if (v.hasTransform()) v.getMatrix().mapPoints(pts);
            for (int i = 0; i + 1 < pts.length; i += 2) {
                pts[i] += v.mLeft + v.translationX;
                pts[i + 1] += v.mTop + v.translationY;
            }
            if (!(v.mParent instanceof View)) break;
            View p = (View) v.mParent;
            for (int i = 0; i + 1 < pts.length; i += 2) { pts[i] -= p.mScrollX; pts[i + 1] -= p.mScrollY; }
            v = p;
        }
    }

    public boolean getGlobalVisibleRect(Rect r) { return getGlobalVisibleRect(r, null); }
    public boolean getGlobalVisibleRect(Rect r, Point offset) {
        int[] loc = new int[2];
        getLocationInWindow(loc);
        r.set(loc[0], loc[1], loc[0] + getWidth(), loc[1] + getHeight());
        if (offset != null) offset.set(loc[0], loc[1]);
        if (root != null) return r.intersect(0, 0, root.width(), root.height()) && isShown();
        return false;
    }
    public final boolean getLocalVisibleRect(Rect r) { boolean v = getGlobalVisibleRect(r); int[] l = new int[2]; getLocationInWindow(l); r.offset(-l[0], -l[1]); return v; }
    public void getHitRect(Rect out) { out.set(mLeft + Math.round(translationX), mTop + Math.round(translationY), mRight + Math.round(translationX), mBottom + Math.round(translationY)); }
    public void getDrawingRect(Rect out) { out.set(mScrollX, mScrollY, mScrollX + getWidth(), mScrollY + getHeight()); }
    public void getWindowVisibleDisplayFrame(Rect out) { if (root != null) out.set(0, 0, root.width(), root.height()); else out.set(0, 0, 0, 0); }
    public void getFocusedRect(Rect r) { getDrawingRect(r); }

    // ---------------------------------------------------------------- ventana

    void dispatchAttachedToWindow(ViewRoot r) {
        root = r;
        refreshDrawableState();
        onAttachedToWindow();
        if (attachListeners != null) for (OnAttachStateChangeListener l : attachListeners) l.onViewAttachedToWindow(this);
        if (floatingObserver != null) { r.observer.merge(floatingObserver); floatingObserver = null; }
        onWindowVisibilityChanged(VISIBLE);
        if (pendingRunnables != null) {
            for (Object[] p : pendingRunnables) MainQueue.post(this, (Runnable) p[0], (Long) p[1], null);
            pendingRunnables = null;
        }
    }

    void dispatchDetachedFromWindow() {
        if (root != null) root.viewDetached(this);
        onWindowVisibilityChanged(GONE);
        if (attachListeners != null) for (OnAttachStateChangeListener l : attachListeners) l.onViewDetachedFromWindow(this);
        onDetachedFromWindow();
        pressed = false;
        hovered = false;
        hasFocus = false;
        root = null;
        if (animator != null) animator.cancel();
    }

    protected void onAttachedToWindow() {}
    protected void onDetachedFromWindow() {}
    protected void onFinishInflate() {}
    protected void onConfigurationChanged(android.content.res.Configuration c) {}
    public void addOnAttachStateChangeListener(OnAttachStateChangeListener l) { if (attachListeners == null) attachListeners = new CopyOnWriteArrayList<>(); attachListeners.add(l); }
    public void removeOnAttachStateChangeListener(OnAttachStateChangeListener l) { if (attachListeners != null) attachListeners.remove(l); }

    public ViewTreeObserver getViewTreeObserver() {
        if (root != null) return root.observer;
        if (floatingObserver == null) floatingObserver = new ViewTreeObserver();
        return floatingObserver;
    }

    public void setOnApplyWindowInsetsListener(OnApplyWindowInsetsListener l) { onInsets = l; requestApplyInsets(); }
    public WindowInsets onApplyWindowInsets(WindowInsets in) { return in; }
    public WindowInsets dispatchApplyWindowInsets(WindowInsets in) { return onInsets != null ? onInsets.onApplyWindowInsets(this, in) : onApplyWindowInsets(in); }
    public void requestApplyInsets() { if (root != null) root.requestInsets(); }
    @Deprecated public void requestFitSystemWindows() { requestApplyInsets(); }
    public WindowInsets getRootWindowInsets() { return root == null ? null : WindowInsets.NONE; }
    public WindowInsetsController getWindowInsetsController() { return root == null ? null : root.insetsController(); }
    public void setWindowInsetsAnimationCallback(Object cb) {}
    public void setSystemGestureExclusionRects(java.util.List<Rect> rects) {}

    // ---------------------------------------------------------------- tareas

    private ArrayList<Object[]> pendingRunnables;

    public boolean post(Runnable r) { return postDelayed(r, 0); }
    public boolean postDelayed(Runnable r, long delay) {
        if (root == null && !MainQueue.isMain()) {
            // Fuera de la ventana y de otro hilo: igual que Android, a la cola principal.
            MainQueue.post(this, r, delay, null);
            return true;
        }
        MainQueue.post(this, r, delay, null);
        return true;
    }
    public void postOnAnimation(Runnable r) { MainQueue.post(this, r, 10, null); }
    public void postOnAnimationDelayed(Runnable r, long d) { MainQueue.post(this, r, Math.max(10, d), null); }
    public boolean removeCallbacks(Runnable r) { MainQueue.remove(this, r, null); return true; }
    public Handler getHandler() { return new Handler(android.os.Looper.getMainLooper()); }

    // ---------------------------------------------------------------- oyentes

    public void setOnClickListener(OnClickListener l) { if (!clickable) clickable = true; onClick = l; }
    public boolean hasOnClickListeners() { return onClick != null; }
    public void setOnLongClickListener(OnLongClickListener l) { if (!longClickable) longClickable = true; onLongClick = l; }
    public void setOnContextClickListener(OnContextClickListener l) { contextClickable = true; onContextClick = l; }
    public void setOnTouchListener(OnTouchListener l) { onTouch = l; }
    public void setOnHoverListener(OnHoverListener l) { onHover = l; }
    public void setOnGenericMotionListener(OnGenericMotionListener l) { onGeneric = l; }
    public void setOnKeyListener(OnKeyListener l) { onKey = l; }
    public void setOnFocusChangeListener(OnFocusChangeListener l) { onFocusChange = l; }
    public OnFocusChangeListener getOnFocusChangeListener() { return onFocusChange; }

    public boolean performClick() {
        if (onClick != null) {
            playSoundEffect(0);
            onClick.onClick(this);
            return true;
        }
        return false;
    }
    public boolean callOnClick() { if (onClick != null) { onClick.onClick(this); return true; } return false; }
    public boolean performLongClick() {
        if (onLongClick != null && onLongClick.onLongClick(this)) return true;
        return showContextMenu();
    }
    public boolean performLongClick(float x, float y) { return performLongClick(); }
    public boolean performContextClick() { return onContextClick != null && onContextClick.onContextClick(this); }
    public boolean performContextClick(float x, float y) { return performContextClick(); }
    public boolean showContextMenu() { return false; }
    public boolean showContextMenu(float x, float y) { return false; }
    public boolean performAccessibilityAction(int a, android.os.Bundle b) { return false; }

    // ---------------------------------------------------------------- toques

    private boolean hasPerformedLongPress;
    private Runnable pendingLongPress, pendingUnpress;
    private float downX, downY;
    private boolean inTap;

    public boolean dispatchTouchEvent(MotionEvent e) {
        if (!onFilterTouchEventForSecurity(e)) return false;
        if (enabled && onTouch != null && onTouch.onTouch(this, e)) return true;
        return onTouchEvent(e);
    }

    public boolean onFilterTouchEventForSecurity(MotionEvent e) { return true; }

    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        boolean clickableNow = clickable || longClickable || contextClickable;
        if (!enabled) {
            if (action == MotionEvent.ACTION_UP && pressed) setPressed(false);
            return clickableNow;
        }
        if (!clickableNow) return false;
        float x = e.getX(), y = e.getY();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                hasPerformedLongPress = false;
                downX = x; downY = y;
                inTap = true;
                if (e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE && (e.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0 && contextClickable) {
                    performContextClick(x, y);
                    inTap = false;
                    return true;
                }
                setPressed(true);
                drawableHotspotChanged(x, y);
                if (longClickable) {
                    if (pendingLongPress == null) pendingLongPress = () -> {
                        if (pressed && !hasPerformedLongPress) {
                            if (performLongClick(downX, downY)) {
                                hasPerformedLongPress = true;
                                setPressed(false);
                            }
                        }
                    };
                    MainQueue.post(this, pendingLongPress, ViewConfiguration.getLongPressTimeout(), null);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                drawableHotspotChanged(x, y);
                if (!pointInView(x, y, ViewConfiguration.get(mContext).getScaledTouchSlop())) {
                    removeLongPress();
                    if (pressed) setPressed(false);
                    inTap = false;
                }
                return true;
            case MotionEvent.ACTION_UP:
                boolean wasPressed = pressed;
                if (wasPressed || inTap) {
                    if (!hasPerformedLongPress) {
                        removeLongPress();
                        if (inTap && (onClick != null || clickable)) {
                            // Como Android: el clic va después del cambio de estado.
                            setPressed(false);
                            if (onClick != null) MainQueue.post(this, this::performClickIfAttached, 0, null);
                        }
                    }
                    setPressed(false);
                }
                inTap = false;
                removeLongPress();
                return true;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                removeLongPress();
                inTap = false;
                return true;
            default:
                return true;
        }
    }

    private void performClickIfAttached() { performClick(); }

    private void removeLongPress() { if (pendingLongPress != null) MainQueue.remove(this, pendingLongPress, null); }

    public void cancelLongPress() { removeLongPress(); }
    public void cancelPendingInputEvents() { removeLongPress(); }

    boolean pointInView(float x, float y, float slop) { return x >= -slop && y >= -slop && x < getWidth() + slop && y < getHeight() + slop; }

    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (onGeneric != null && enabled && onGeneric.onGenericMotion(this, e)) return true;
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_HOVER_ENTER || a == MotionEvent.ACTION_HOVER_MOVE || a == MotionEvent.ACTION_HOVER_EXIT) {
            if (dispatchHoverEvent(e)) return true;
        }
        return onGenericMotionEvent(e);
    }
    public boolean onGenericMotionEvent(MotionEvent e) { return false; }

    public boolean dispatchHoverEvent(MotionEvent e) {
        if (onHover != null && enabled && onHover.onHover(this, e)) return true;
        return onHoverEvent(e);
    }

    public boolean onHoverEvent(MotionEvent e) {
        int a = e.getActionMasked();
        boolean hoverable = clickable || longClickable || contextClickable || tooltip != null;
        if (!hoverable) return false;
        if (a == MotionEvent.ACTION_HOVER_ENTER) setHovered(true);
        else if (a == MotionEvent.ACTION_HOVER_EXIT) setHovered(false);
        return false;
    }

    public final void requestUnbufferedDispatch(MotionEvent e) {}
    public final void requestUnbufferedDispatch(int source) {}
    public void requestPointerCapture() {}
    public void releasePointerCapture() {}
    public boolean hasPointerCapture() { return false; }

    // ---------------------------------------------------------------- teclas y foco

    public boolean dispatchKeyEvent(KeyEvent e) {
        if (onKey != null && enabled && onKey.onKey(this, e.getKeyCode(), e)) return true;
        return e.dispatch(this, null, this);
    }
    public boolean dispatchKeyEventPreIme(KeyEvent e) { return onKeyPreIme(e.getKeyCode(), e); }
    public boolean onKeyPreIme(int code, KeyEvent e) { return false; }
    public boolean dispatchKeyShortcutEvent(KeyEvent e) { return onKeyShortcut(e.getKeyCode(), e); }
    public boolean onKeyShortcut(int code, KeyEvent e) { return false; }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if ((code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_SPACE || code == KeyEvent.KEYCODE_DPAD_CENTER) && (clickable || longClickable) && enabled) {
            setPressed(true);
            return true;
        }
        return false;
    }
    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if ((code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_SPACE || code == KeyEvent.KEYCODE_DPAD_CENTER) && pressed) {
            setPressed(false);
            performClick();
            return true;
        }
        return false;
    }
    @Override public boolean onKeyLongPress(int code, KeyEvent e) { return false; }
    @Override public boolean onKeyMultiple(int code, int n, KeyEvent e) { return false; }
    public boolean onCheckIsTextEditor() { return false; }
    public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo info) { return null; }

    public boolean isFocused() { return hasFocus; }
    public boolean hasFocus() { return hasFocus; }
    public boolean hasFocusable() { return visibility == VISIBLE && focusable; }
    public View findFocus() { return hasFocus ? this : null; }
    public boolean requestFocus() { return requestFocus(FOCUS_DOWN, null); }
    public final boolean requestFocus(int direction) { return requestFocus(direction, null); }
    public boolean requestFocus(int direction, Rect previous) {
        if (!focusable || visibility != VISIBLE || !enabled) return false;
        if (root == null) { pendingFocus = true; return true; }
        root.setFocus(this);
        return true;
    }
    public final boolean requestFocusFromTouch() { return requestFocus(); }
    boolean pendingFocus;
    public void clearFocus() { if (hasFocus && root != null) root.setFocus(null); }

    void setFocusedInternal(boolean f) {
        if (hasFocus == f) return;
        hasFocus = f;
        onFocusChanged(f, FOCUS_DOWN, null);
        refreshDrawableState();
        if (onFocusChange != null) onFocusChange.onFocusChange(this, f);
        invalidate();
    }

    protected void onFocusChanged(boolean gainFocus, int direction, Rect previous) {}
    public View focusSearch(int direction) { return mParent != null ? mParent.focusSearch(this, direction) : null; }
    public void setNextFocusDownId(int id) {}
    public void setNextFocusUpId(int id) {}
    public void setNextFocusForwardId(int id) {}
    public void setDefaultFocusHighlightEnabled(boolean e) {}
    public void setFocusedByDefault(boolean f) {}
    public void setKeyboardNavigationCluster(boolean c) {}

    // ---------------------------------------------------------------- animación

    public ViewPropertyAnimator animate() { if (animator == null) animator = new ViewPropertyAnimator(this); return animator; }
    public void startAnimation(Animation a) { animation = a; a.start(this); invalidateParent(); }
    public void setAnimation(Animation a) { animation = a; }
    public Animation getAnimation() { return animation; }
    public void clearAnimation() { if (animation != null) { animation.cancel(); animation = null; animMatrix = null; invalidateParent(); } }
    void stepAnimation() {
        if (animation == null) return;
        Animation.Transformation t = animation.step();
        animMatrix = t.matrix;
        if (t.done) {
            Animation a = animation;
            animation = null;
            animMatrix = null;
            a.ended(this);
        }
        invalidate();
    }

    // ---------------------------------------------------------------- varios

    public void setOnSystemUiChange(Runnable r) {}
    public boolean isOpaque() { return false; }
    public boolean onCheckIsLaidOut() { return laidOut; }
    public void bringToFront() { if (mParent instanceof ViewGroup) ((ViewGroup) mParent).bringChildToFront(this); }
    public boolean isShowingLayoutBounds() { return false; }
    public void setMinWidth(int w) { setMinimumWidth(w); }
    public boolean isScrollContainer() { return false; }
    public void setScrollContainer(boolean s) {}
    public int getBaseline() { return -1; }
    public void setVerticalScrollbarPosition(int p) {}
    public void setScrollBarSize(int s) {}
    public void setScrollBarFadeDuration(int d) {}
    public void setScrollBarDefaultDelayBeforeFade(int d) {}
    public void setVerticalScrollbarThumbDrawable(Drawable d) {}
    public void setHorizontalScrollbarThumbDrawable(Drawable d) {}

    public Bitmap drawToBitmap() {
        Bitmap b = Bitmap.createBitmap(Math.max(1, getWidth()), Math.max(1, getHeight()), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.translate(-mScrollX, -mScrollY);
        draw(c);
        c.release();
        return b;
    }

    public static View inflate(Context c, int resource, ViewGroup root) { throw new UnsupportedOperationException("sin XML"); }

    // ---------------------------------------------------------------- árbol

    void setLayoutParamsSilently(ViewGroup.LayoutParams p) { layoutParams = p; }
    void dispatchAttachedToWindowTree(ViewRoot r) { dispatchAttachedToWindow(r); }
    void dispatchDetachedFromWindowTree() { dispatchDetachedFromWindow(); }
    void dispatchConfigurationChangedTree(android.content.res.Configuration c) { onConfigurationChanged(c); }
    void dispatchWindowFocus(boolean f) { onWindowFocusChanged(f); refreshDrawableState(); }

    @Override public String toString() { return getClass().getSimpleName() + "{" + Integer.toHexString(hashCode()) + " " + mLeft + "," + mTop + "-" + mRight + "," + mBottom + "}"; }
}
