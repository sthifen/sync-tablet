package android.text.style;

import android.text.TextPaint;

public class UnderlineSpan extends CharacterStyle implements UpdateAppearance, ParcelableSpan {
    @Override public void updateDrawState(TextPaint tp) { tp.setUnderlineText(true); }
    @Override public int getSpanTypeId() { return 6; }
}
