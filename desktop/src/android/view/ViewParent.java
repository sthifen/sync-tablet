package android.view;

import android.graphics.Rect;

public interface ViewParent {
    void requestLayout();
    boolean isLayoutRequested();
    void requestTransparentRegion(View child);
    void invalidateChild(View child, Rect r);
    ViewParent invalidateChildInParent(int[] location, Rect r);
    ViewParent getParent();
    void requestChildFocus(View child, View focused);
    void recomputeViewAttributes(View child);
    void clearChildFocus(View child);
    View focusSearch(View v, int direction);
    void bringChildToFrontParent(View child);
    void focusableViewAvailable(View v);
    boolean showContextMenuForChild(View original);
    void createContextMenu(ContextMenu menu);
    void requestDisallowInterceptTouchEvent(boolean disallow);
    boolean requestChildRectangleOnScreen(View child, Rect r, boolean immediate);
    void childHasTransientStateChanged(View child, boolean hasTransientState);
    boolean onStartNestedScroll(View child, View target, int nestedScrollAxes);
    void onNestedScrollAccepted(View child, View target, int nestedScrollAxes);
    void onStopNestedScroll(View target);
    void onNestedScroll(View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed);
    void onNestedPreScroll(View target, int dx, int dy, int[] consumed);
    boolean onNestedFling(View target, float velocityX, float velocityY, boolean consumed);
    boolean onNestedPreFling(View target, float velocityX, float velocityY);
}
