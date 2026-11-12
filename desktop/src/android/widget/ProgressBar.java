package android.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/** Barra de progreso de Material: horizontal (con su valor o indeterminada) o la ruedita que gira. */
public class ProgressBar extends View {
    private int min, max = 100, progress, secondary;
    boolean horizontal;
    private boolean indeterminate;
    private int tint = 0xFF2DD4BF, track = 0x40FFFFFF;
    final float density;

    public ProgressBar(Context c) { this(c, null, android.R.attr.progressBarStyle); }
    public ProgressBar(Context c, android.util.AttributeSet a) { this(c, a, android.R.attr.progressBarStyle); }
    public ProgressBar(Context c, android.util.AttributeSet a, int style) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        horizontal = style == android.R.attr.progressBarStyleHorizontal || style == android.R.attr.seekBarStyle || style == android.R.style.Widget_ProgressBar_Horizontal;
        indeterminate = !horizontal;
    }
    public ProgressBar(Context c, android.util.AttributeSet a, int style, int res) { this(c, a, res == android.R.style.Widget_ProgressBar_Horizontal ? android.R.attr.progressBarStyleHorizontal : style); }

    public synchronized void setMax(int m) { if (m < min) m = min; if (m != max) { max = m; if (progress > max) progress = max; postInvalidate(); } }
    public synchronized int getMax() { return max; }
    public synchronized void setMin(int m) { if (m > max) m = max; min = m; if (progress < min) progress = min; postInvalidate(); }
    public synchronized int getMin() { return min; }
    public synchronized void setProgress(int p) { setProgressInternal(p, false); }
    public void setProgress(int p, boolean animate) { setProgress(p); }
    synchronized boolean setProgressInternal(int p, boolean fromUser) {
        p = Math.max(min, Math.min(max, p));
        if (p == progress) return false;
        progress = p;
        onProgressRefresh(p, fromUser);
        postInvalidate();
        return true;
    }
    void onProgressRefresh(int p, boolean fromUser) {}
    public synchronized int getProgress() { return indeterminate ? 0 : progress; }
    public synchronized void setSecondaryProgress(int p) { secondary = p; postInvalidate(); }
    public synchronized int getSecondaryProgress() { return secondary; }
    public synchronized void incrementProgressBy(int d) { setProgress(progress + d); }
    public synchronized void setIndeterminate(boolean i) { indeterminate = i; postInvalidate(); }
    public synchronized boolean isIndeterminate() { return indeterminate; }
    public void setProgressTintList(ColorStateList c) { if (c != null) tint = c.getDefaultColor(); invalidate(); }
    public void setProgressBackgroundTintList(ColorStateList c) { if (c != null) track = c.getDefaultColor(); invalidate(); }
    public void setIndeterminateTintList(ColorStateList c) { if (c != null) tint = c.getDefaultColor(); invalidate(); }
    public void setSecondaryProgressTintList(ColorStateList c) {}
    public void setProgressDrawable(android.graphics.drawable.Drawable d) {}
    public void setIndeterminateDrawable(android.graphics.drawable.Drawable d) {}
    int tint() { return tint; }
    int trackColor() { return track; }
    float fraction() { return max == min ? 0 : (progress - min) / (float) (max - min); }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = horizontal ? Math.round(48 * density) : Math.round(48 * density);
        int h = horizontal ? Math.round(4 * density) : Math.round(48 * density);
        setMeasuredDimension(resolveSizeAndState(w + mPaddingLeft + mPaddingRight, ws, 0), resolveSizeAndState(h + mPaddingTop + mPaddingBottom, hs, 0));
    }

    @Override
    protected void onDraw(Canvas c) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float l = mPaddingLeft, t = mPaddingTop, r = getWidth() - mPaddingRight, b = getHeight() - mPaddingBottom;
        if (horizontal) {
            float h = Math.min(b - t, 4 * density), cy = (t + b) / 2;
            p.setColor(track);
            c.drawRoundRect(new RectF(l, cy - h / 2, r, cy + h / 2), h / 2, h / 2, p);
            p.setColor(tint);
            if (indeterminate) {
                float phase = (SystemClock.uptimeMillis() % 1600) / 1600f;
                float w = (r - l) * 0.35f, x = l - w + (r - l + w) * phase;
                c.save();
                c.clipRect(l, t, r, b);
                c.drawRoundRect(new RectF(x, cy - h / 2, x + w, cy + h / 2), h / 2, h / 2, p);
                c.restore();
                postInvalidateOnAnimation();
            } else {
                c.drawRoundRect(new RectF(l, cy - h / 2, l + (r - l) * fraction(), cy + h / 2), h / 2, h / 2, p);
            }
            return;
        }
        float size = Math.min(r - l, b - t), stroke = size / 10f;
        RectF oval = new RectF((l + r - size) / 2 + stroke, (t + b - size) / 2 + stroke, (l + r + size) / 2 - stroke, (t + b + size) / 2 - stroke);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(tint);
        long now = SystemClock.uptimeMillis();
        float rot = (now % 1333) / 1333f * 360f;
        float sweep = 30 + 240 * (float) (0.5 - 0.5 * Math.cos((now % 2666) / 2666.0 * 2 * Math.PI));
        c.drawArc(oval, rot, sweep, false, p);
        if (getVisibility() == VISIBLE && isAttachedToWindow()) postInvalidateOnAnimation();
    }
}
