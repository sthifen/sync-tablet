package android.text.style;

import android.text.TextPaint;

public class ScaleXSpan extends MetricAffectingSpan {
    private final float s;
    public ScaleXSpan(float s) { this.s = s; }
    @Override public void updateDrawState(TextPaint tp) { tp.setTextScaleX(tp.getTextScaleX() * s); }
    @Override public void updateMeasureState(TextPaint tp) { tp.setTextScaleX(tp.getTextScaleX() * s); }
}
