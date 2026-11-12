package android.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

/** LinearLayout con el mismo algoritmo de medida que Android (pesos, línea base, divisores). */
public class LinearLayout extends ViewGroup {
    public static final int HORIZONTAL = 0, VERTICAL = 1;
    public static final int SHOW_DIVIDER_NONE = 0, SHOW_DIVIDER_BEGINNING = 1, SHOW_DIVIDER_MIDDLE = 2, SHOW_DIVIDER_END = 4;
    private static final int INDEX_CENTER_VERTICAL = 0, INDEX_TOP = 1, INDEX_BOTTOM = 2, INDEX_FILL = 3;

    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        public float weight;
        public int gravity = -1;
        public LayoutParams(int w, int h) { super(w, h); }
        public LayoutParams(int w, int h, float weight) { super(w, h); this.weight = weight; }
        public LayoutParams(ViewGroup.LayoutParams p) { super(p); }
        public LayoutParams(ViewGroup.MarginLayoutParams p) { super(p); }
        public LayoutParams(LayoutParams p) { super((ViewGroup.MarginLayoutParams) p); weight = p.weight; gravity = p.gravity; }
        public LayoutParams(Context c, android.util.AttributeSet a) { super(c, a); }
    }

    private int orientation = HORIZONTAL;
    private int gravity = Gravity.START | Gravity.TOP;
    private boolean baselineAligned = true, useLargestChild;
    private float weightSum = -1;
    private int totalLength;
    private final int[] maxAscent = new int[4], maxDescent = new int[4];
    private Drawable divider;
    private int dividerWidth, dividerHeight, showDividers, dividerPadding;

    public LinearLayout(Context c) { super(c); }
    public LinearLayout(Context c, android.util.AttributeSet a) { super(c); }
    public LinearLayout(Context c, android.util.AttributeSet a, int s) { super(c); }

    public void setOrientation(int o) { if (orientation != o) { orientation = o; requestLayout(); } }
    public int getOrientation() { return orientation; }
    public void setGravity(int g) {
        if ((g & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == 0) g |= Gravity.START;
        if ((g & Gravity.VERTICAL_GRAVITY_MASK) == 0) g |= Gravity.TOP;
        if (gravity != g) { gravity = g; requestLayout(); }
    }
    public int getGravity() { return gravity; }
    public void setHorizontalGravity(int h) { setGravity((gravity & ~Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) | (h & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)); }
    public void setVerticalGravity(int v) { setGravity((gravity & ~Gravity.VERTICAL_GRAVITY_MASK) | (v & Gravity.VERTICAL_GRAVITY_MASK)); }
    public void setBaselineAligned(boolean b) { baselineAligned = b; }
    public boolean isBaselineAligned() { return baselineAligned; }
    public void setBaselineAlignedChildIndex(int i) {}
    public void setWeightSum(float w) { weightSum = Math.max(0, w); }
    public float getWeightSum() { return weightSum; }
    public void setMeasureWithLargestChildEnabled(boolean e) { useLargestChild = e; }
    public void setDividerDrawable(Drawable d) {
        divider = d;
        dividerWidth = d == null ? 0 : d.getIntrinsicWidth();
        dividerHeight = d == null ? 0 : d.getIntrinsicHeight();
        setWillNotDraw(d == null);
        requestLayout();
    }
    public Drawable getDividerDrawable() { return divider; }
    public void setShowDividers(int s) { showDividers = s; requestLayout(); }
    public int getShowDividers() { return showDividers; }
    public void setDividerPadding(int p) { dividerPadding = p; }

    @Override protected LayoutParams generateDefaultLayoutParams() {
        return orientation == HORIZONTAL ? new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT) : new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    }
    @Override protected boolean checkLayoutParams(ViewGroup.LayoutParams p) { return p instanceof LayoutParams; }
    @Override protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        if (p instanceof LayoutParams) return new LayoutParams((LayoutParams) p);
        if (p instanceof MarginLayoutParams) return new LayoutParams((MarginLayoutParams) p);
        return new LayoutParams(p);
    }

    private boolean allGoneBefore(int i) {
        for (int j = i - 1; j >= 0; j--) { View c = getChildAt(j); if (c != null && c.getVisibility() != GONE) return false; }
        return true;
    }

    boolean hasDividerBeforeChildAt(int i) {
        if (divider == null) return false;
        if (i == getChildCount()) return (showDividers & SHOW_DIVIDER_END) != 0;
        if (allGoneBefore(i)) return (showDividers & SHOW_DIVIDER_BEGINNING) != 0;
        return (showDividers & SHOW_DIVIDER_MIDDLE) != 0;
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        if (orientation == VERTICAL) measureVertical(ws, hs); else measureHorizontal(ws, hs);
    }

    private void measureVertical(int ws, int hs) {
        totalLength = 0;
        int maxWidth = 0, childState = 0, alternativeMaxWidth = 0, weightedMaxWidth = 0, consumedExcess = 0, largest = Integer.MIN_VALUE;
        boolean allFillParent = true, matchWidth = false, skippedMeasure = false;
        float totalWeight = 0;
        int count = getChildCount();
        int widthMode = MeasureSpec.getMode(ws), heightMode = MeasureSpec.getMode(hs);
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            if (hasDividerBeforeChildAt(i)) totalLength += dividerHeight;
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            totalWeight += lp.weight;
            boolean useExcess = lp.height == 0 && lp.weight > 0;
            if (heightMode == MeasureSpec.EXACTLY && useExcess) {
                int t = totalLength;
                totalLength = Math.max(t, t + lp.topMargin + lp.bottomMargin);
                skippedMeasure = true;
            } else {
                if (useExcess) lp.height = LayoutParams.WRAP_CONTENT;
                int usedHeight = totalWeight == 0 ? totalLength : 0;
                measureChildWithMargins(child, ws, 0, hs, usedHeight);
                int childHeight = child.getMeasuredHeight();
                if (useExcess) { lp.height = 0; consumedExcess += childHeight; }
                int t = totalLength;
                totalLength = Math.max(t, t + childHeight + lp.topMargin + lp.bottomMargin);
                if (useLargestChild) largest = Math.max(childHeight, largest);
            }
            boolean matchLocally = false;
            if (widthMode != MeasureSpec.EXACTLY && lp.width == LayoutParams.MATCH_PARENT) { matchWidth = true; matchLocally = true; }
            int margin = lp.leftMargin + lp.rightMargin;
            int measuredWidth = child.getMeasuredWidth() + margin;
            maxWidth = Math.max(maxWidth, measuredWidth);
            childState = combineMeasuredStates(childState, child.getMeasuredState());
            allFillParent = allFillParent && lp.width == LayoutParams.MATCH_PARENT;
            if (lp.weight > 0) weightedMaxWidth = Math.max(weightedMaxWidth, matchLocally ? margin : measuredWidth);
            else alternativeMaxWidth = Math.max(alternativeMaxWidth, matchLocally ? margin : measuredWidth);
        }
        if (totalLength > 0 && hasDividerBeforeChildAt(count)) totalLength += dividerHeight;
        if (useLargestChild && (heightMode == MeasureSpec.AT_MOST || heightMode == MeasureSpec.UNSPECIFIED)) {
            totalLength = 0;
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                int t = totalLength;
                totalLength = Math.max(t, t + largest + lp.topMargin + lp.bottomMargin);
            }
        }
        totalLength += mPaddingTop + mPaddingBottom;
        int heightSize = Math.max(totalLength, getSuggestedMinimumHeight());
        int heightSizeAndState = resolveSizeAndState(heightSize, hs, 0);
        heightSize = heightSizeAndState & MEASURED_SIZE_MASK;
        int remaining = heightSize - totalLength + consumedExcess;
        if (skippedMeasure || totalWeight > 0) {
            float remainingWeight = weightSum > 0 ? weightSum : totalWeight;
            totalLength = 0;
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                float w = lp.weight;
                if (w > 0) {
                    int share = (int) (w * remaining / remainingWeight);
                    remaining -= share;
                    remainingWeight -= w;
                    int childHeight;
                    if (useLargestChild && heightMode != MeasureSpec.EXACTLY) childHeight = largest;
                    else if (lp.height == 0) childHeight = share;
                    else childHeight = child.getMeasuredHeight() + share;
                    int chs = MeasureSpec.makeMeasureSpec(Math.max(0, childHeight), MeasureSpec.EXACTLY);
                    int cws = getChildMeasureSpec(ws, mPaddingLeft + mPaddingRight + lp.leftMargin + lp.rightMargin, lp.width);
                    child.measure(cws, chs);
                    childState = combineMeasuredStates(childState, child.getMeasuredState() & (MEASURED_STATE_MASK >> MEASURED_HEIGHT_STATE_SHIFT));
                }
                int margin = lp.leftMargin + lp.rightMargin;
                int measuredWidth = child.getMeasuredWidth() + margin;
                maxWidth = Math.max(maxWidth, measuredWidth);
                boolean matchLocally = widthMode != MeasureSpec.EXACTLY && lp.width == LayoutParams.MATCH_PARENT;
                alternativeMaxWidth = Math.max(alternativeMaxWidth, matchLocally ? margin : measuredWidth);
                allFillParent = allFillParent && lp.width == LayoutParams.MATCH_PARENT;
                if (hasDividerBeforeChildAt(i)) totalLength += dividerHeight;
                int t = totalLength;
                totalLength = Math.max(t, t + child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
            }
            if (totalLength > 0 && hasDividerBeforeChildAt(count)) totalLength += dividerHeight;
            totalLength += mPaddingTop + mPaddingBottom;
        } else {
            alternativeMaxWidth = Math.max(alternativeMaxWidth, weightedMaxWidth);
        }
        if (!allFillParent && widthMode != MeasureSpec.EXACTLY) maxWidth = alternativeMaxWidth;
        maxWidth += mPaddingLeft + mPaddingRight;
        maxWidth = Math.max(maxWidth, getSuggestedMinimumWidth());
        setMeasuredDimension(resolveSizeAndState(maxWidth, ws, childState), heightSizeAndState);
        if (matchWidth) {
            int uniform = MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY);
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if (lp.width == LayoutParams.MATCH_PARENT) {
                    int old = lp.height;
                    lp.height = child.getMeasuredHeight();
                    measureChildWithMargins(child, uniform, 0, hs, 0);
                    lp.height = old;
                }
            }
        }
    }

    private static int baselineIndex(int g) { return ((g >> Gravity.AXIS_Y_SHIFT) & ~Gravity.AXIS_SPECIFIED) >> 1; }

    private void measureHorizontal(int ws, int hs) {
        totalLength = 0;
        int maxHeight = 0, childState = 0, alternativeMaxHeight = 0, weightedMaxHeight = 0, usedExcess = 0, largest = Integer.MIN_VALUE;
        boolean allFillParent = true, matchHeight = false, skippedMeasure = false;
        float totalWeight = 0;
        int count = getChildCount();
        int widthMode = MeasureSpec.getMode(ws), heightMode = MeasureSpec.getMode(hs);
        java.util.Arrays.fill(maxAscent, -1);
        java.util.Arrays.fill(maxDescent, -1);
        boolean isExactly = widthMode == MeasureSpec.EXACTLY;
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            if (hasDividerBeforeChildAt(i)) totalLength += dividerWidth;
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            totalWeight += lp.weight;
            boolean useExcess = lp.width == 0 && lp.weight > 0;
            if (widthMode == MeasureSpec.EXACTLY && useExcess) {
                if (isExactly) totalLength += lp.leftMargin + lp.rightMargin;
                else { int t = totalLength; totalLength = Math.max(t, t + lp.leftMargin + lp.rightMargin); }
                if (baselineAligned) {
                    int fw = MeasureSpec.makeSafeMeasureSpec(MeasureSpec.getSize(ws), MeasureSpec.UNSPECIFIED);
                    int fh = MeasureSpec.makeSafeMeasureSpec(MeasureSpec.getSize(hs), MeasureSpec.UNSPECIFIED);
                    child.measure(fw, fh);
                } else skippedMeasure = true;
            } else {
                if (useExcess) lp.width = LayoutParams.WRAP_CONTENT;
                int usedWidth = totalWeight == 0 ? totalLength : 0;
                measureChildWithMargins(child, ws, usedWidth, hs, 0);
                int childWidth = child.getMeasuredWidth();
                if (useExcess) { lp.width = 0; usedExcess += childWidth; }
                if (isExactly) totalLength += childWidth + lp.leftMargin + lp.rightMargin;
                else { int t = totalLength; totalLength = Math.max(t, t + childWidth + lp.leftMargin + lp.rightMargin); }
                if (useLargestChild) largest = Math.max(childWidth, largest);
            }
            boolean matchLocally = false;
            if (heightMode != MeasureSpec.EXACTLY && lp.height == LayoutParams.MATCH_PARENT) { matchHeight = true; matchLocally = true; }
            int margin = lp.topMargin + lp.bottomMargin;
            int childHeight = child.getMeasuredHeight() + margin;
            childState = combineMeasuredStates(childState, child.getMeasuredState());
            if (baselineAligned) {
                int b = child.getBaseline();
                if (b != -1) {
                    int g = (lp.gravity < 0 ? gravity : lp.gravity) & Gravity.VERTICAL_GRAVITY_MASK;
                    int idx = baselineIndex(g);
                    maxAscent[idx] = Math.max(maxAscent[idx], b);
                    maxDescent[idx] = Math.max(maxDescent[idx], childHeight - b);
                }
            }
            maxHeight = Math.max(maxHeight, childHeight);
            allFillParent = allFillParent && lp.height == LayoutParams.MATCH_PARENT;
            if (lp.weight > 0) weightedMaxHeight = Math.max(weightedMaxHeight, matchLocally ? margin : childHeight);
            else alternativeMaxHeight = Math.max(alternativeMaxHeight, matchLocally ? margin : childHeight);
        }
        if (totalLength > 0 && hasDividerBeforeChildAt(count)) totalLength += dividerWidth;
        if (maxAscent[INDEX_TOP] != -1 || maxAscent[INDEX_CENTER_VERTICAL] != -1 || maxAscent[INDEX_BOTTOM] != -1 || maxAscent[INDEX_FILL] != -1) {
            int ascent = Math.max(maxAscent[INDEX_FILL], Math.max(maxAscent[INDEX_CENTER_VERTICAL], Math.max(maxAscent[INDEX_TOP], maxAscent[INDEX_BOTTOM])));
            int descent = Math.max(maxDescent[INDEX_FILL], Math.max(maxDescent[INDEX_CENTER_VERTICAL], Math.max(maxDescent[INDEX_TOP], maxDescent[INDEX_BOTTOM])));
            maxHeight = Math.max(maxHeight, ascent + descent);
        }
        if (useLargestChild && (widthMode == MeasureSpec.AT_MOST || widthMode == MeasureSpec.UNSPECIFIED)) {
            totalLength = 0;
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if (isExactly) totalLength += largest + lp.leftMargin + lp.rightMargin;
                else { int t = totalLength; totalLength = Math.max(t, t + largest + lp.leftMargin + lp.rightMargin); }
            }
        }
        totalLength += mPaddingLeft + mPaddingRight;
        int widthSize = Math.max(totalLength, getSuggestedMinimumWidth());
        int widthSizeAndState = resolveSizeAndState(widthSize, ws, 0);
        widthSize = widthSizeAndState & MEASURED_SIZE_MASK;
        int remaining = widthSize - totalLength + usedExcess;
        if (skippedMeasure || totalWeight > 0) {
            float remainingWeight = weightSum > 0 ? weightSum : totalWeight;
            java.util.Arrays.fill(maxAscent, -1);
            java.util.Arrays.fill(maxDescent, -1);
            maxHeight = -1;
            totalLength = 0;
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                float w = lp.weight;
                if (w > 0) {
                    int share = (int) (w * remaining / remainingWeight);
                    remaining -= share;
                    remainingWeight -= w;
                    int childWidth;
                    if (useLargestChild && widthMode != MeasureSpec.EXACTLY) childWidth = largest;
                    else if (lp.width == 0) childWidth = share;
                    else childWidth = child.getMeasuredWidth() + share;
                    int cws = MeasureSpec.makeMeasureSpec(Math.max(0, childWidth), MeasureSpec.EXACTLY);
                    int chs = getChildMeasureSpec(hs, mPaddingTop + mPaddingBottom + lp.topMargin + lp.bottomMargin, lp.height);
                    child.measure(cws, chs);
                    childState = combineMeasuredStates(childState, child.getMeasuredState() & MEASURED_STATE_MASK);
                }
                if (hasDividerBeforeChildAt(i)) totalLength += dividerWidth;
                if (isExactly) totalLength += child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
                else { int t = totalLength; totalLength = Math.max(t, t + child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin); }
                boolean matchLocally = heightMode != MeasureSpec.EXACTLY && lp.height == LayoutParams.MATCH_PARENT;
                int margin = lp.topMargin + lp.bottomMargin;
                int childHeight = child.getMeasuredHeight() + margin;
                maxHeight = Math.max(maxHeight, childHeight);
                alternativeMaxHeight = Math.max(alternativeMaxHeight, matchLocally ? margin : childHeight);
                allFillParent = allFillParent && lp.height == LayoutParams.MATCH_PARENT;
                if (baselineAligned) {
                    int b = child.getBaseline();
                    if (b != -1) {
                        int g = (lp.gravity < 0 ? gravity : lp.gravity) & Gravity.VERTICAL_GRAVITY_MASK;
                        int idx = baselineIndex(g);
                        maxAscent[idx] = Math.max(maxAscent[idx], b);
                        maxDescent[idx] = Math.max(maxDescent[idx], childHeight - b);
                    }
                }
            }
            if (totalLength > 0 && hasDividerBeforeChildAt(count)) totalLength += dividerWidth;
            totalLength += mPaddingLeft + mPaddingRight;
            if (maxAscent[INDEX_TOP] != -1 || maxAscent[INDEX_CENTER_VERTICAL] != -1 || maxAscent[INDEX_BOTTOM] != -1 || maxAscent[INDEX_FILL] != -1) {
                int ascent = Math.max(maxAscent[INDEX_FILL], Math.max(maxAscent[INDEX_CENTER_VERTICAL], Math.max(maxAscent[INDEX_TOP], maxAscent[INDEX_BOTTOM])));
                int descent = Math.max(maxDescent[INDEX_FILL], Math.max(maxDescent[INDEX_CENTER_VERTICAL], Math.max(maxDescent[INDEX_TOP], maxDescent[INDEX_BOTTOM])));
                maxHeight = Math.max(maxHeight, ascent + descent);
            }
        } else {
            alternativeMaxHeight = Math.max(alternativeMaxHeight, weightedMaxHeight);
        }
        if (!allFillParent && heightMode != MeasureSpec.EXACTLY) maxHeight = alternativeMaxHeight;
        maxHeight += mPaddingTop + mPaddingBottom;
        maxHeight = Math.max(maxHeight, getSuggestedMinimumHeight());
        setMeasuredDimension(widthSizeAndState | (childState & MEASURED_STATE_MASK), resolveSizeAndState(maxHeight, hs, childState << MEASURED_HEIGHT_STATE_SHIFT));
        if (matchHeight) {
            int uniform = MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY);
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if (lp.height == LayoutParams.MATCH_PARENT) {
                    int old = lp.width;
                    lp.width = child.getMeasuredWidth();
                    measureChildWithMargins(child, ws, 0, uniform, 0);
                    lp.width = old;
                }
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (orientation == VERTICAL) layoutVertical(l, t, r, b); else layoutHorizontal(l, t, r, b);
    }

    private void layoutVertical(int left, int top, int right, int bottom) {
        int width = right - left, childRight = width - mPaddingRight, childSpace = width - mPaddingLeft - mPaddingRight;
        int count = getChildCount();
        int major = gravity & Gravity.VERTICAL_GRAVITY_MASK, minor = gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK;
        int childTop;
        switch (major) {
            case Gravity.BOTTOM: childTop = mPaddingTop + bottom - top - totalLength; break;
            case Gravity.CENTER_VERTICAL: childTop = mPaddingTop + (bottom - top - totalLength) / 2; break;
            default: childTop = mPaddingTop;
        }
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int cw = child.getMeasuredWidth(), ch = child.getMeasuredHeight();
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            int g = lp.gravity < 0 ? minor : lp.gravity;
            int abs = Gravity.getAbsoluteGravity(g, 0);
            int childLeft;
            switch (abs & Gravity.HORIZONTAL_GRAVITY_MASK) {
                case Gravity.CENTER_HORIZONTAL: childLeft = mPaddingLeft + ((childSpace - cw) / 2) + lp.leftMargin - lp.rightMargin; break;
                case Gravity.RIGHT: childLeft = childRight - cw - lp.rightMargin; break;
                default: childLeft = mPaddingLeft + lp.leftMargin;
            }
            if (hasDividerBeforeChildAt(i)) childTop += dividerHeight;
            childTop += lp.topMargin;
            child.layout(childLeft, childTop, childLeft + cw, childTop + ch);
            childTop += ch + lp.bottomMargin;
        }
    }

    private void layoutHorizontal(int left, int top, int right, int bottom) {
        int height = bottom - top, childBottom = height - mPaddingBottom, childSpace = height - mPaddingTop - mPaddingBottom;
        int count = getChildCount();
        int major = gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK, minor = gravity & Gravity.VERTICAL_GRAVITY_MASK;
        int childLeft;
        switch (Gravity.getAbsoluteGravity(major, 0)) {
            case Gravity.RIGHT: childLeft = mPaddingLeft + right - left - totalLength; break;
            case Gravity.CENTER_HORIZONTAL: childLeft = mPaddingLeft + (right - left - totalLength) / 2; break;
            default: childLeft = mPaddingLeft;
        }
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int cw = child.getMeasuredWidth(), ch = child.getMeasuredHeight();
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            int childBaseline = -1;
            if (baselineAligned && lp.height != LayoutParams.MATCH_PARENT) childBaseline = child.getBaseline();
            int g = lp.gravity < 0 ? minor : lp.gravity;
            int childTop;
            switch (g & Gravity.VERTICAL_GRAVITY_MASK) {
                case Gravity.TOP:
                    childTop = mPaddingTop + lp.topMargin;
                    if (childBaseline != -1) childTop += maxAscent[INDEX_TOP] - childBaseline;
                    break;
                case Gravity.CENTER_VERTICAL: childTop = mPaddingTop + ((childSpace - ch) / 2) + lp.topMargin - lp.bottomMargin; break;
                case Gravity.BOTTOM:
                    childTop = childBottom - ch - lp.bottomMargin;
                    if (childBaseline != -1) { int descent = ch - childBaseline; childTop -= (maxDescent[INDEX_BOTTOM] - descent); }
                    break;
                default: childTop = mPaddingTop;
            }
            if (hasDividerBeforeChildAt(i)) childLeft += dividerWidth;
            childLeft += lp.leftMargin;
            child.layout(childLeft, childTop, childLeft + cw, childTop + ch);
            childLeft += cw + lp.rightMargin;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (divider == null) return;
        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE || !hasDividerBeforeChildAt(i)) continue;
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (orientation == VERTICAL) {
                int t = child.getTop() - lp.topMargin - dividerHeight;
                divider.setBounds(mPaddingLeft + dividerPadding, t, getWidth() - mPaddingRight - dividerPadding, t + dividerHeight);
            } else {
                int l = child.getLeft() - lp.leftMargin - dividerWidth;
                divider.setBounds(l, mPaddingTop + dividerPadding, l + dividerWidth, getHeight() - mPaddingBottom - dividerPadding);
            }
            divider.draw(c);
        }
        if (hasDividerBeforeChildAt(count)) {
            View last = null;
            for (int i = count - 1; i >= 0; i--) if (getChildAt(i).getVisibility() != GONE) { last = getChildAt(i); break; }
            if (orientation == VERTICAL) {
                int t = last == null ? getHeight() - mPaddingBottom - dividerHeight : last.getBottom() + ((LayoutParams) last.getLayoutParams()).bottomMargin;
                divider.setBounds(mPaddingLeft + dividerPadding, t, getWidth() - mPaddingRight - dividerPadding, t + dividerHeight);
            } else {
                int l = last == null ? mPaddingLeft : last.getRight() + ((LayoutParams) last.getLayoutParams()).rightMargin;
                divider.setBounds(l, mPaddingTop + dividerPadding, l + dividerWidth, getHeight() - mPaddingBottom - dividerPadding);
            }
            divider.draw(c);
        }
    }

    @Override
    public int getBaseline() { return super.getBaseline(); }
}
