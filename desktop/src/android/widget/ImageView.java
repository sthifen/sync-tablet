package android.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.View;

public class ImageView extends View {
    public enum ScaleType { MATRIX, FIT_XY, FIT_START, FIT_CENTER, FIT_END, CENTER, CENTER_CROP, CENTER_INSIDE }

    private Drawable drawable;
    private ScaleType scaleType = ScaleType.FIT_CENTER;
    private boolean adjustViewBounds, cropToPadding;
    private int maxWidth = Integer.MAX_VALUE, maxHeight = Integer.MAX_VALUE, alpha = 255;
    private final Matrix matrix = new Matrix();
    private Matrix drawMatrix;
    private ColorFilter filter;
    private int resource;

    public ImageView(Context c) { super(c); }
    public ImageView(Context c, android.util.AttributeSet a) { super(c); }
    public ImageView(Context c, android.util.AttributeSet a, int s) { super(c); }

    public void setImageDrawable(Drawable d) {
        if (drawable == d) return;
        if (drawable != null) drawable.setCallback(null);
        int ow = drawable == null ? -1 : drawable.getIntrinsicWidth(), oh = drawable == null ? -1 : drawable.getIntrinsicHeight();
        drawable = d;
        resource = 0;
        if (d != null) {
            d.setCallback(this);
            if (d.isStateful()) d.setState(getDrawableState());
            if (filter != null) d.setColorFilter(filter);
            d.setAlpha(alpha);
        }
        configure();
        if (d == null || ow != d.getIntrinsicWidth() || oh != d.getIntrinsicHeight()) requestLayout();
        invalidate();
    }
    public void setImageBitmap(Bitmap b) { setImageDrawable(b == null ? null : new BitmapDrawable(getResources(), b)); }
    public void setImageResource(int id) { setImageDrawable(id == 0 ? null : getResources().getDrawable(id, null)); resource = id; }
    public void setImageURI(Uri uri) {
        if (uri == null) { setImageDrawable(null); return; }
        try (java.io.InputStream in = getContext().getContentResolver().openInputStream(uri)) {
            setImageBitmap(android.graphics.BitmapFactory.decodeStream(in));
        } catch (Exception e) {
            setImageDrawable(null);
        }
    }
    public Drawable getDrawable() { return drawable; }
    public void setImageLevel(int l) { if (drawable != null) drawable.setLevel(l); }
    public void setImageState(int[] s, boolean merge) {}
    public void setScaleType(ScaleType t) { if (t != scaleType) { scaleType = t; configure(); requestLayout(); invalidate(); } }
    public ScaleType getScaleType() { return scaleType; }
    public void setAdjustViewBounds(boolean a) { adjustViewBounds = a; if (a) setScaleType(ScaleType.FIT_CENTER); requestLayout(); }
    public boolean getAdjustViewBounds() { return adjustViewBounds; }
    public void setMaxWidth(int w) { maxWidth = w; }
    public void setMaxHeight(int h) { maxHeight = h; }
    public int getMaxWidth() { return maxWidth; }
    public int getMaxHeight() { return maxHeight; }
    public void setCropToPadding(boolean c) { cropToPadding = c; invalidate(); }
    public boolean getCropToPadding() { return cropToPadding; }
    public void setImageMatrix(Matrix m) { matrix.set(m); configure(); invalidate(); }
    public Matrix getImageMatrix() { return drawMatrix == null ? new Matrix() : new Matrix(drawMatrix); }
    public final void setColorFilter(int color, PorterDuff.Mode mode) { setColorFilter(new PorterDuffColorFilter(color, mode)); }
    public final void setColorFilter(int color) { setColorFilter(color, PorterDuff.Mode.SRC_ATOP); }
    public void setColorFilter(ColorFilter f) { filter = f; if (drawable != null) drawable.setColorFilter(f); invalidate(); }
    public ColorFilter getColorFilter() { return filter; }
    public final void clearColorFilter() { setColorFilter((ColorFilter) null); }
    public void setImageTintList(ColorStateList t) { setColorFilter(t == null ? null : new PorterDuffColorFilter(t.getDefaultColor(), PorterDuff.Mode.SRC_IN)); }
    public void setImageTintMode(PorterDuff.Mode m) {}
    public void setImageAlpha(int a) { alpha = a; if (drawable != null) drawable.setAlpha(a); invalidate(); }
    @Deprecated public void setAlpha(int a) { setImageAlpha(a); }
    public int getImageAlpha() { return alpha; }
    public void setBaseline(int b) {}

    @Override protected boolean verifyDrawable(Drawable who) { return who == drawable || super.verifyDrawable(who); }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        if (drawable != null && drawable.isStateful() && drawable.setState(getDrawableState())) invalidate();
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w, h;
        float desiredAspect = 0;
        boolean resizeW = false, resizeH = false;
        int wMode = MeasureSpec.getMode(ws), hMode = MeasureSpec.getMode(hs);
        if (drawable == null) { w = -1; h = -1; }
        else {
            w = drawable.getIntrinsicWidth(); h = drawable.getIntrinsicHeight();
            if (w <= 0) w = 1;
            if (h <= 0) h = 1;
            if (adjustViewBounds) {
                resizeW = wMode != MeasureSpec.EXACTLY;
                resizeH = hMode != MeasureSpec.EXACTLY;
                desiredAspect = (float) w / h;
            }
        }
        int pl = mPaddingLeft, pr = mPaddingRight, pt = mPaddingTop, pb = mPaddingBottom;
        int widthSize, heightSize;
        if (resizeW || resizeH) {
            widthSize = resolveAdjusted(w + pl + pr, maxWidth, ws);
            heightSize = resolveAdjusted(h + pt + pb, maxHeight, hs);
            if (desiredAspect != 0) {
                float actual = (float) (widthSize - pl - pr) / (heightSize - pt - pb);
                if (Math.abs(actual - desiredAspect) > 0.0000001) {
                    boolean done = false;
                    if (resizeW) {
                        int nw = (int) (desiredAspect * (heightSize - pt - pb)) + pl + pr;
                        if (!resizeH) widthSize = resolveAdjusted(nw, maxWidth, ws);
                        if (nw <= widthSize) { widthSize = nw; done = true; }
                    }
                    if (!done && resizeH) {
                        int nh = (int) ((widthSize - pl - pr) / desiredAspect) + pt + pb;
                        if (!resizeW) heightSize = resolveAdjusted(nh, maxHeight, hs);
                        if (nh <= heightSize) heightSize = nh;
                    }
                }
            }
        } else {
            w = Math.max(w + pl + pr, getSuggestedMinimumWidth());
            h = Math.max(h + pt + pb, getSuggestedMinimumHeight());
            widthSize = resolveSizeAndState(w, ws, 0);
            heightSize = resolveSizeAndState(h, hs, 0);
        }
        setMeasuredDimension(widthSize, heightSize);
    }

    private int resolveAdjusted(int desired, int max, int spec) {
        int mode = MeasureSpec.getMode(spec), size = MeasureSpec.getSize(spec);
        switch (mode) {
            case MeasureSpec.UNSPECIFIED: return Math.min(desired, max);
            case MeasureSpec.AT_MOST: return Math.min(Math.min(desired, size), max);
            default: return size;
        }
    }

    @Override protected boolean setFrame(int l, int t, int r, int b) { boolean c = super.setFrame(l, t, r, b); configure(); return c; }

    private void configure() {
        if (drawable == null) { drawMatrix = null; return; }
        int dw = drawable.getIntrinsicWidth(), dh = drawable.getIntrinsicHeight();
        int vw = getWidth() - mPaddingLeft - mPaddingRight, vh = getHeight() - mPaddingTop - mPaddingBottom;
        boolean fits = (dw < 0 || vw == dw) && (dh < 0 || vh == dh);
        if (dw <= 0 || dh <= 0 || scaleType == ScaleType.FIT_XY) {
            drawable.setBounds(0, 0, vw, vh);
            drawMatrix = null;
            return;
        }
        drawable.setBounds(0, 0, dw, dh);
        Matrix m = new Matrix();
        switch (scaleType) {
            case MATRIX: m.set(matrix); break;
            case CENTER: m.setTranslate(Math.round((vw - dw) * 0.5f), Math.round((vh - dh) * 0.5f)); break;
            case CENTER_CROP: {
                float scale, dx = 0, dy = 0;
                if (dw * vh > vw * dh) { scale = (float) vh / dh; dx = (vw - dw * scale) * 0.5f; }
                else { scale = (float) vw / dw; dy = (vh - dh * scale) * 0.5f; }
                m.setScale(scale, scale);
                m.postTranslate(Math.round(dx), Math.round(dy));
                break;
            }
            case CENTER_INSIDE: {
                float scale = dw <= vw && dh <= vh ? 1f : Math.min((float) vw / dw, (float) vh / dh);
                float dx = Math.round((vw - dw * scale) * 0.5f), dy = Math.round((vh - dh * scale) * 0.5f);
                m.setScale(scale, scale);
                m.postTranslate(dx, dy);
                break;
            }
            default: {
                Matrix.ScaleToFit stf = scaleType == ScaleType.FIT_START ? Matrix.ScaleToFit.START : scaleType == ScaleType.FIT_END ? Matrix.ScaleToFit.END : Matrix.ScaleToFit.CENTER;
                m.setRectToRect(new RectF(0, 0, dw, dh), new RectF(0, 0, vw, vh), stf);
            }
        }
        drawMatrix = fits && scaleType != ScaleType.MATRIX ? null : m;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (drawable == null) return;
        if (drawable.getIntrinsicWidth() == 0 || drawable.getIntrinsicHeight() == 0) return;
        if (drawMatrix == null && mPaddingTop == 0 && mPaddingLeft == 0) { drawable.draw(c); return; }
        int save = c.save();
        if (cropToPadding) c.clipRect(mScrollX + mPaddingLeft, mScrollY + mPaddingTop, mScrollX + getWidth() - mPaddingRight, mScrollY + getHeight() - mPaddingBottom);
        c.translate(mPaddingLeft, mPaddingTop);
        if (drawMatrix != null) c.concat(drawMatrix);
        drawable.draw(c);
        c.restoreToCount(save);
    }

    @Override public int getBaseline() { return -1; }
}
