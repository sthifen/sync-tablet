package android.text.style;

import android.text.TextPaint;

public class RelativeSizeSpan extends MetricAffectingSpan implements ParcelableSpan {
    private final float proportion;
    public RelativeSizeSpan(float proportion) { this.proportion = proportion; }
    public float getSizeChange() { return proportion; }
    @Override public void updateDrawState(TextPaint tp) { tp.setTextSize(tp.getTextSize() * proportion); }
    @Override public void updateMeasureState(TextPaint tp) { tp.setTextSize(tp.getTextSize() * proportion); }
    @Override public int getSpanTypeId() { return 3; }
}
