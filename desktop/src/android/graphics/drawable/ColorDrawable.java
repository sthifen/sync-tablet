package android.graphics.drawable;

import android.graphics.Canvas;
import android.graphics.Paint;

public class ColorDrawable extends Drawable {
    private int color;
    private final Paint paint = new Paint();

    public ColorDrawable() {}
    public ColorDrawable(int color) { this.color = color; }

    public int getColor() { return color; }
    public void setColor(int c) { if (color != c) { color = c; invalidateSelf(); } }

    @Override
    public void draw(Canvas c) {
        int a = ((color >>> 24) * alpha) / 255;
        if (a == 0) return;
        paint.setColor((a << 24) | (color & 0xFFFFFF));
        paint.setColorFilter(colorFilter);
        c.drawRect(getBounds(), paint);
    }

    @Override public int getAlpha() { return color >>> 24; }
    @Override public void setAlpha(int a) { color = (a << 24) | (color & 0xFFFFFF); invalidateSelf(); }
}
