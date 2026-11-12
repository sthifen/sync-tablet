package android.text.style;

import android.text.TextPaint;

public class AbsoluteSizeSpan extends MetricAffectingSpan implements ParcelableSpan {
    private final int size;
    private final boolean dip;
    public AbsoluteSizeSpan(int size) { this(size, false); }
    public AbsoluteSizeSpan(int size, boolean dip) { this.size = size; this.dip = dip; }
    public int getSize() { return size; }
    public boolean getDip() { return dip; }
    @Override public void updateDrawState(TextPaint tp) { tp.setTextSize(dip ? size * tp.density : size); }
    @Override public void updateMeasureState(TextPaint tp) { tp.setTextSize(dip ? size * tp.density : size); }
    @Override public int getSpanTypeId() { return 16; }
}
