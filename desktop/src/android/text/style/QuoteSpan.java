package android.text.style;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;

public class QuoteSpan implements LeadingMarginSpan, ParcelableSpan {
    private final int color;
    public QuoteSpan() { this(0xff0000ff); }
    public QuoteSpan(int color) { this.color = color; }
    public int getColor() { return color; }
    @Override public int getSpanTypeId() { return 9; }
    @Override public int getLeadingMargin(boolean first) { return 4 + 2; }
    @Override public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom, CharSequence text, int start, int end, boolean first, Layout layout) {
        Paint.Style style = p.getStyle();
        int old = p.getColor();
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawRect(x, top, x + dir * 4, bottom, p);
        p.setStyle(style);
        p.setColor(old);
    }
}
