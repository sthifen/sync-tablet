package android.view;

import android.content.Context;

public class ViewConfiguration {
    private final float density;
    private ViewConfiguration(float d) { density = d; }
    public static ViewConfiguration get(Context c) { return new ViewConfiguration(c == null ? android.content.res.Resources.getSystem().getDisplayMetrics().density : c.getResources().getDisplayMetrics().density); }
    public int getScaledTouchSlop() { return Math.round(8 * density); }
    public int getScaledPagingTouchSlop() { return Math.round(16 * density); }
    public int getScaledDoubleTapSlop() { return Math.round(100 * density); }
    public int getScaledWindowTouchSlop() { return Math.round(16 * density); }
    public int getScaledMinimumFlingVelocity() { return Math.round(50 * density); }
    public int getScaledMaximumFlingVelocity() { return Math.round(8000 * density); }
    public int getScaledOverscrollDistance() { return 0; }
    public int getScaledOverflingDistance() { return Math.round(6 * density); }
    public int getScaledEdgeSlop() { return Math.round(12 * density); }
    public int getScaledScrollBarSize() { return Math.round(4 * density); }
    public float getScaledVerticalScrollFactor() { return 64 * density; }
    public float getScaledHorizontalScrollFactor() { return 64 * density; }
    public int getScaledHoverSlop() { return Math.round(4 * density); }
    public static int getLongPressTimeout() { return 400; }
    public static int getTapTimeout() { return 100; }
    public static int getDoubleTapTimeout() { return 300; }
    public static int getJumpTapTimeout() { return 500; }
    public static int getPressedStateDuration() { return 64; }
    public static long getZoomControlsTimeout() { return 3000; }
    public static int getScrollBarFadeDuration() { return 250; }
    public static int getScrollDefaultDelay() { return 300; }
    public static float getScrollFriction() { return 0.015f; }
    public static int getKeyRepeatTimeout() { return 400; }
    public static int getKeyRepeatDelay() { return 50; }
    public static int getTouchSlop() { return 8; }
    public static int getMinimumFlingVelocity() { return 50; }
    public static int getMaximumFlingVelocity() { return 8000; }
    public boolean hasPermanentMenuKey() { return false; }
}
