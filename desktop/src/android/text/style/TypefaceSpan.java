package android.text.style;

import android.graphics.Typeface;
import android.text.TextPaint;

public class TypefaceSpan extends MetricAffectingSpan implements ParcelableSpan {
    private final String family;
    private final Typeface typeface;
    public TypefaceSpan(String family) { this.family = family; this.typeface = null; }
    public TypefaceSpan(Typeface t) { this.family = null; this.typeface = t; }
    public String getFamily() { return family; }
    public Typeface getTypeface() { return typeface; }
    @Override public void updateDrawState(TextPaint tp) { apply(tp); }
    @Override public void updateMeasureState(TextPaint tp) { apply(tp); }
    private void apply(TextPaint p) {
        Typeface old = p.getTypeface();
        int style = old == null ? 0 : old.getStyle();
        p.setTypeface(typeface != null ? Typeface.create(typeface, style) : Typeface.create(family, style));
    }
    @Override public int getSpanTypeId() { return 13; }
}
