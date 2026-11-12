package android.text.style;

import android.graphics.Canvas;
import android.graphics.Paint;

public abstract class ReplacementSpan extends MetricAffectingSpan {
    private CharSequence contentDescription;
    public abstract int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm);
    public abstract void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint);
    public CharSequence getContentDescription() { return contentDescription; }
    public void setContentDescription(CharSequence d) { contentDescription = d; }
    @Override public void updateMeasureState(android.text.TextPaint p) {}
    @Override public void updateDrawState(android.text.TextPaint ds) {}
}
