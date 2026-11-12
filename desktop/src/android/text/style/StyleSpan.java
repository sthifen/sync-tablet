package android.text.style;

import android.graphics.Typeface;
import android.text.TextPaint;

public class StyleSpan extends MetricAffectingSpan implements ParcelableSpan {
    private final int style;
    public StyleSpan(int style) { this.style = style; }
    public int getStyle() { return style; }
    @Override public void updateDrawState(TextPaint tp) { apply(tp, style); }
    @Override public void updateMeasureState(TextPaint tp) { apply(tp, style); }
    @Override public int getSpanTypeId() { return 7; }
    private static void apply(TextPaint p, int style) {
        Typeface old = p.getTypeface();
        int oldStyle = old == null ? 0 : old.getStyle();
        int want = oldStyle | style;
        Typeface tf = old == null ? Typeface.defaultFromStyle(want) : Typeface.create(old, want);
        p.setTypeface(tf);
    }
}
