package android.text.style;

import android.text.TextPaint;

public class StrikethroughSpan extends CharacterStyle implements UpdateAppearance, ParcelableSpan {
    @Override public void updateDrawState(TextPaint tp) { tp.setStrikeThruText(true); }
    @Override public int getSpanTypeId() { return 5; }
}
