package android.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.KeyEvent;
import android.view.MotionEvent;

public abstract class AbsSeekBar extends ProgressBar {
    private int thumbTint = 0xFF2DD4BF;
    private boolean dragging;
    private android.graphics.drawable.Drawable thumb;
    private int keyStep = 1;

    AbsSeekBar(Context c) {
        super(c, null, android.R.attr.seekBarStyle);
        horizontal = true;
        setIndeterminate(false);
        setFocusable(true);
        setClickable(true);
    }

    public void setThumbTintList(ColorStateList c) { if (c != null) thumbTint = c.getDefaultColor(); invalidate(); }
    public void setThumb(android.graphics.drawable.Drawable d) { thumb = d; invalidate(); }
    public android.graphics.drawable.Drawable getThumb() { return thumb; }
    public void setThumbOffset(int o) {}
    public void setSplitTrack(boolean s) {}
    public void setKeyProgressIncrement(int k) { keyStep = Math.max(1, k); }

    float thumbRadius() { return 10 * density; }

    void onStartTrackingTouch() {}
    void onStopTrackingTouch() {}

    @Override
    protected void onMeasure(int ws, int hs) {
        int h = Math.round(thumbRadius() * 2 + 12 * density);
        setMeasuredDimension(resolveSizeAndState(Math.round(64 * density) + mPaddingLeft + mPaddingRight, ws, 0), resolveSizeAndState(h + mPaddingTop + mPaddingBottom, hs, 0));
    }

    @Override
    protected void onDraw(Canvas c) {
        float r = thumbRadius();
        float l = mPaddingLeft + r, rr = getWidth() - mPaddingRight - r, cy = (mPaddingTop + getHeight() - mPaddingBottom) / 2f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float h = 2 * density;
        p.setColor(trackColor());
        c.drawRoundRect(new RectF(l, cy - h, rr, cy + h), h, h, p);
        float x = l + (rr - l) * fraction();
        p.setColor(tint());
        c.drawRoundRect(new RectF(l, cy - h, x, cy + h), h, h, p);
        if (isPressed() || isHovered() || dragging) {
            p.setColor((tint() & 0xFFFFFF) | 0x33000000);
            c.drawCircle(x, cy, r * 2, p);
        }
        p.setColor(thumbTint);
        c.drawCircle(x, cy, r, p);
    }

    private void track(float x) {
        float r = thumbRadius();
        float l = mPaddingLeft + r, rr = getWidth() - mPaddingRight - r;
        float f = rr <= l ? 0 : Math.max(0, Math.min(1, (x - l) / (rr - l)));
        setProgressInternal(Math.round(getMin() + f * (getMax() - getMin())), true);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                setPressed(true);
                onStartTrackingTouch();
                track(e.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) track(e.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) { if (e.getActionMasked() == MotionEvent.ACTION_UP) track(e.getX()); dragging = false; onStopTrackingTouch(); }
                setPressed(false);
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_SCROLL && isEnabled()) {
            float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL) + e.getAxisValue(MotionEvent.AXIS_HSCROLL);
            if (v != 0) {
                int step = Math.max(keyStep, (getMax() - getMin()) / 50);
                onStartTrackingTouch();
                setProgressInternal(getProgress() + (v > 0 ? step : -step), true);
                onStopTrackingTouch();
                return true;
            }
        }
        return super.onGenericMotionEvent(e);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            setProgressInternal(getProgress() + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? keyStep : -keyStep), true);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onHoverEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_HOVER_ENTER) setHovered(true);
        else if (a == MotionEvent.ACTION_HOVER_EXIT) setHovered(false);
        invalidate();
        return true;
    }
}
