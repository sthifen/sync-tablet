package android.widget;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;

public class FrameLayout extends ViewGroup {
    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        public static final int UNSPECIFIED_GRAVITY = -1;
        public int gravity = UNSPECIFIED_GRAVITY;
        public LayoutParams(int w, int h) { super(w, h); }
        public LayoutParams(int w, int h, int gravity) { super(w, h); this.gravity = gravity; }
        public LayoutParams(ViewGroup.LayoutParams src) { super(src); if (src instanceof LayoutParams) gravity = ((LayoutParams) src).gravity; }
        public LayoutParams(ViewGroup.MarginLayoutParams src) { super(src); }
        public LayoutParams(LayoutParams src) { super((ViewGroup.MarginLayoutParams) src); gravity = src.gravity; }
        public LayoutParams(Context c, android.util.AttributeSet a) { super(c, a); }
    }

    private boolean measureAllChildren;
    private int foregroundGravity = Gravity.FILL;
    private final ArrayList<View> matchParentChildren = new ArrayList<>();

    public FrameLayout(Context c) { super(c); }
    public FrameLayout(Context c, android.util.AttributeSet a) { super(c); }
    public FrameLayout(Context c, android.util.AttributeSet a, int s) { super(c); }

    public void setMeasureAllChildren(boolean m) { measureAllChildren = m; }
    public boolean getMeasureAllChildren() { return measureAllChildren; }
    @Override public void setForegroundGravity(int g) { foregroundGravity = g; }

    @Override protected LayoutParams generateDefaultLayoutParams() { return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT); }
    @Override protected boolean checkLayoutParams(ViewGroup.LayoutParams p) { return p instanceof LayoutParams; }
    @Override protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        if (p instanceof LayoutParams) return new LayoutParams((LayoutParams) p);
        if (p instanceof MarginLayoutParams) return new LayoutParams((MarginLayoutParams) p);
        return new LayoutParams(p);
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int count = getChildCount();
        boolean measureMatchParent = MeasureSpec.getMode(ws) != MeasureSpec.EXACTLY || MeasureSpec.getMode(hs) != MeasureSpec.EXACTLY;
        matchParentChildren.clear();
        int maxH = 0, maxW = 0, childState = 0;
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (measureAllChildren || child.getVisibility() != GONE) {
                measureChildWithMargins(child, ws, 0, hs, 0);
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                maxW = Math.max(maxW, child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin);
                maxH = Math.max(maxH, child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
                childState = combineMeasuredStates(childState, child.getMeasuredState());
                if (measureMatchParent && (lp.width == LayoutParams.MATCH_PARENT || lp.height == LayoutParams.MATCH_PARENT)) matchParentChildren.add(child);
            }
        }
        maxW += mPaddingLeft + mPaddingRight;
        maxH += mPaddingTop + mPaddingBottom;
        maxH = Math.max(maxH, getSuggestedMinimumHeight());
        maxW = Math.max(maxW, getSuggestedMinimumWidth());
        android.graphics.drawable.Drawable fg = getForeground();
        if (fg != null) { maxH = Math.max(maxH, fg.getMinimumHeight()); maxW = Math.max(maxW, fg.getMinimumWidth()); }
        setMeasuredDimension(resolveSizeAndState(maxW, ws, childState), resolveSizeAndState(maxH, hs, childState << MEASURED_HEIGHT_STATE_SHIFT));
        if (matchParentChildren.size() > 1) {
            for (View child : matchParentChildren) {
                MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
                int cw = lp.width == LayoutParams.MATCH_PARENT
                        ? MeasureSpec.makeMeasureSpec(Math.max(0, getMeasuredWidth() - mPaddingLeft - mPaddingRight - lp.leftMargin - lp.rightMargin), MeasureSpec.EXACTLY)
                        : getChildMeasureSpec(ws, mPaddingLeft + mPaddingRight + lp.leftMargin + lp.rightMargin, lp.width);
                int ch = lp.height == LayoutParams.MATCH_PARENT
                        ? MeasureSpec.makeMeasureSpec(Math.max(0, getMeasuredHeight() - mPaddingTop - mPaddingBottom - lp.topMargin - lp.bottomMargin), MeasureSpec.EXACTLY)
                        : getChildMeasureSpec(hs, mPaddingTop + mPaddingBottom + lp.topMargin + lp.bottomMargin, lp.height);
                child.measure(cw, ch);
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) { layoutChildren(l, t, r, b, false); }

    void layoutChildren(int left, int top, int right, int bottom, boolean forceLeftGravity) {
        int count = getChildCount();
        int parentLeft = mPaddingLeft, parentRight = right - left - mPaddingRight, parentTop = mPaddingTop, parentBottom = bottom - top - mPaddingBottom;
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            int width = child.getMeasuredWidth(), height = child.getMeasuredHeight();
            int gravity = lp.gravity;
            if (gravity == -1) gravity = Gravity.TOP | Gravity.START;
            int absolute = Gravity.getAbsoluteGravity(gravity, 0);
            int vertical = gravity & Gravity.VERTICAL_GRAVITY_MASK;
            int childLeft, childTop;
            switch (absolute & Gravity.HORIZONTAL_GRAVITY_MASK) {
                case Gravity.CENTER_HORIZONTAL: childLeft = parentLeft + (parentRight - parentLeft - width) / 2 + lp.leftMargin - lp.rightMargin; break;
                case Gravity.RIGHT: if (!forceLeftGravity) { childLeft = parentRight - width - lp.rightMargin; break; }
                default: childLeft = parentLeft + lp.leftMargin;
            }
            switch (vertical) {
                case Gravity.CENTER_VERTICAL: childTop = parentTop + (parentBottom - parentTop - height) / 2 + lp.topMargin - lp.bottomMargin; break;
                case Gravity.BOTTOM: childTop = parentBottom - height - lp.bottomMargin; break;
                default: childTop = parentTop + lp.topMargin;
            }
            child.layout(childLeft, childTop, childLeft + width, childTop + height);
        }
    }
}
