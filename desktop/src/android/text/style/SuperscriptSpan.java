package android.text.style;

import android.text.TextPaint;

public class SuperscriptSpan extends MetricAffectingSpan implements ParcelableSpan {
    @Override public void updateDrawState(TextPaint tp) { tp.baselineShift += (int) (tp.ascent() / 2); }
    @Override public void updateMeasureState(TextPaint tp) { tp.baselineShift += (int) (tp.ascent() / 2); }
    @Override public int getSpanTypeId() { return 14; }
}
