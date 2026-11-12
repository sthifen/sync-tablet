package android.graphics.drawable;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

public class BitmapDrawable extends Drawable {
    private Bitmap bitmap;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private int gravity = android.view.Gravity.FILL;

    public BitmapDrawable() {}
    public BitmapDrawable(Bitmap b) { bitmap = b; }
    public BitmapDrawable(android.content.res.Resources res, Bitmap b) { bitmap = b; }

    public final Bitmap getBitmap() { return bitmap; }
    public void setBitmap(Bitmap b) { bitmap = b; invalidateSelf(); }
    public final Paint getPaint() { return paint; }
    public void setGravity(int g) { gravity = g; }
    public void setAntiAlias(boolean aa) { paint.setAntiAlias(aa); }

    @Override
    public void draw(Canvas c) {
        if (bitmap == null) return;
        paint.setAlpha(alpha);
        paint.setColorFilter(colorFilter);
        c.drawBitmap(bitmap, null, new RectF(getBounds()), paint);
    }

    @Override public int getIntrinsicWidth() { return bitmap == null ? -1 : bitmap.getWidth(); }
    @Override public int getIntrinsicHeight() { return bitmap == null ? -1 : bitmap.getHeight(); }
}
