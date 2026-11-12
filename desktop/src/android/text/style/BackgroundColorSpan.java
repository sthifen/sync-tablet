package android.text.style;

import android.text.TextPaint;

public class BackgroundColorSpan extends CharacterStyle implements UpdateAppearance, ParcelableSpan {
    private final int color;
    public BackgroundColorSpan(int color) { this.color = color; }
    public int getBackgroundColor() { return color; }
    @Override public void updateDrawState(TextPaint tp) { tp.bgColor = color; }
    @Override public int getSpanTypeId() { return 12; }
}
