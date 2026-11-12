package android.text.style;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.Spanned;

public class BulletSpan implements LeadingMarginSpan, ParcelableSpan {
    public static final int STANDARD_GAP_WIDTH = 2;
    private final int gap, color, radius;
    private final boolean wantColor;

    public BulletSpan() { this(STANDARD_GAP_WIDTH, 0, false, 4); }
    public BulletSpan(int gapWidth) { this(gapWidth, 0, false, 4); }
    public BulletSpan(int gapWidth, int color) { this(gapWidth, color, true, 4); }
    public BulletSpan(int gapWidth, int color, int radius) { this(gapWidth, color, true, radius); }
    private BulletSpan(int gap, int color, boolean wantColor, int radius) { this.gap = gap; this.color = color; this.wantColor = wantColor; this.radius = radius; }

    public int getGapWidth() { return gap; }
    public int getBulletRadius() { return radius; }
    public int getColor() { return color; }
    @Override public int getSpanTypeId() { return 8; }
    @Override public int getLeadingMargin(boolean first) { return 2 * radius + gap; }

    @Override
    public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom, CharSequence text, int start, int end, boolean first, Layout l) {
        if (!(text instanceof Spanned) || ((Spanned) text).getSpanStart(this) != start) return;
        Paint.Style style = p.getStyle();
        int old = p.getColor();
        if (wantColor) p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        float yPosition = l != null ? (l.getLineTop(l.getLineForOffset(start)) + l.getLineBottom(l.getLineForOffset(start))) / 2f : (top + bottom) / 2f;
        float xPosition = x + dir * radius;
        c.drawCircle(xPosition, yPosition, radius, p);
        if (wantColor) p.setColor(old);
        p.setStyle(style);
    }
}
