package android.text.style;

import android.text.TextPaint;

public class ForegroundColorSpan extends CharacterStyle implements UpdateAppearance, ParcelableSpan {
    private final int color;
    public ForegroundColorSpan(int color) { this.color = color; }
    public int getForegroundColor() { return color; }
    @Override public void updateDrawState(TextPaint tp) { tp.setColor(color); }
    @Override public int getSpanTypeId() { return 2; }
}
