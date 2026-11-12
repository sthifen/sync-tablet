package android.text.style;

import android.text.TextPaint;
import android.view.View;

public abstract class ClickableSpan extends CharacterStyle implements UpdateAppearance {
    public abstract void onClick(View widget);
    @Override public void updateDrawState(TextPaint ds) { ds.setColor(ds.linkColor != 0 ? ds.linkColor : 0xFF2563EB); ds.setUnderlineText(true); }
}
