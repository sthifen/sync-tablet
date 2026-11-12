package android.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

public final class Space extends View {
    public Space(Context c) { super(c); if (getVisibility() == VISIBLE) setVisibility(INVISIBLE); }
    @Override public void draw(Canvas c) {}
    @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(size(getSuggestedMinimumWidth(), ws), size(getSuggestedMinimumHeight(), hs)); }
    private static int size(int s, int spec) {
        int mode = MeasureSpec.getMode(spec), sz = MeasureSpec.getSize(spec);
        return mode == MeasureSpec.AT_MOST ? Math.min(s, sz) : mode == MeasureSpec.EXACTLY ? sz : s;
    }
}
