package android.graphics.drawable;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;

public class GradientDrawable extends Drawable {
    public static final int RECTANGLE = 0, OVAL = 1, LINE = 2, RING = 3;
    public static final int LINEAR_GRADIENT = 0, RADIAL_GRADIENT = 1, SWEEP_GRADIENT = 2;

    public enum Orientation { TOP_BOTTOM, TR_BL, RIGHT_LEFT, BR_TL, BOTTOM_TOP, BL_TR, LEFT_RIGHT, TL_BR }

    private int shape = RECTANGLE, gradientType = LINEAR_GRADIENT;
    private Orientation orientation = Orientation.TOP_BOTTOM;
    private int color;
    private boolean hasColor;
    private int[] colors;
    private float radius;
    private float[] radii;
    private int strokeWidth, strokeColor;
    private float dashWidth, dashGap;
    private int width = -1, height = -1;
    private float gradientRadius = 0.5f, centerX = 0.5f, centerY = 0.5f;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private android.content.res.ColorStateList colorList;

    public GradientDrawable() {}
    public GradientDrawable(Orientation o, int[] colors) { orientation = o; this.colors = colors == null ? null : colors.clone(); }

    public void setShape(int s) { shape = s; invalidateSelf(); }
    public int getShape() { return shape; }
    public void setGradientType(int t) { gradientType = t; invalidateSelf(); }
    public int getGradientType() { return gradientType; }
    public void setOrientation(Orientation o) { orientation = o; invalidateSelf(); }
    public Orientation getOrientation() { return orientation; }
    public void setColor(int c) { color = c; hasColor = true; colors = null; colorList = null; invalidateSelf(); }
    public void setColor(android.content.res.ColorStateList c) { colorList = c; color = c == null ? 0 : c.getDefaultColor(); hasColor = c != null; colors = null; invalidateSelf(); }
    public android.content.res.ColorStateList getColor() { return colorList != null ? colorList : android.content.res.ColorStateList.valueOf(color); }
    public void setColors(int[] cs) { colors = cs == null ? null : cs.clone(); hasColor = false; invalidateSelf(); }
    public void setColors(int[] cs, float[] offsets) { setColors(cs); }
    public int[] getColors() { return colors; }
    public void setCornerRadius(float r) { radius = r; radii = null; invalidateSelf(); }
    public float getCornerRadius() { return radius; }
    public void setCornerRadii(float[] r) { radii = r == null ? null : r.clone(); invalidateSelf(); }
    public float[] getCornerRadii() { return radii; }
    public void setStroke(int w, int c) { setStroke(w, c, 0, 0); }
    public void setStroke(int w, int c, float dashW, float dashG) { strokeWidth = w; strokeColor = c; dashWidth = dashW; dashGap = dashG; invalidateSelf(); }
    public void setStroke(int w, android.content.res.ColorStateList c) { setStroke(w, c == null ? 0 : c.getDefaultColor()); }
    public void setSize(int w, int h) { width = w; height = h; }
    public void setGradientRadius(float r) { gradientRadius = r; }
    public void setGradientCenter(float x, float y) { centerX = x; centerY = y; }
    public void setUseLevel(boolean u) {}
    public void setDither(boolean d) {}

    @Override public int getIntrinsicWidth() { return width; }
    @Override public int getIntrinsicHeight() { return height; }

    @Override
    protected boolean onStateChange(int[] state) {
        if (colorList != null) {
            int c = colorList.getColorForState(state, colorList.getDefaultColor());
            if (c != color) { color = c; return true; }
        }
        return false;
    }

    @Override public boolean isStateful() { return colorList != null && colorList.isStateful(); }

    private int withAlpha(int c) { return ((((c >>> 24) * alpha) / 255) << 24) | (c & 0xFFFFFF); }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float inset = strokeWidth / 2f;
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
        fill.setColorFilter(colorFilter);
        stroke.setColorFilter(colorFilter);
        boolean doFill = false;
        if (colors != null && colors.length > 0) {
            int[] cs = new int[colors.length];
            for (int i = 0; i < cs.length; i++) cs[i] = withAlpha(colors[i]);
            fill.setShader(shader(b, cs));
            fill.setColor(0xFFFFFFFF);
            fill.setAlpha(255);
            doFill = true;
        } else if (hasColor && (color >>> 24) != 0) {
            fill.setShader(null);
            fill.setColor(withAlpha(color));
            doFill = true;
        }
        fill.setStyle(Paint.Style.FILL);
        if (doFill) drawShape(c, fill, b);
        if (strokeWidth > 0 && (strokeColor >>> 24) != 0) {
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(strokeWidth);
            stroke.setColor(withAlpha(strokeColor));
            stroke.setPathEffect(dashWidth > 0 ? new DashPathEffect(new float[] {dashWidth, dashGap}, 0) : null);
            drawShape(c, stroke, b);
        }
    }

    private void drawShape(Canvas c, Paint p, Rect b) {
        RectF r = p.getStyle() == Paint.Style.STROKE ? rect : new RectF(b);
        switch (shape) {
            case OVAL: c.drawOval(r, p); break;
            case LINE: { float y = b.exactCenterY(); c.drawLine(b.left, y, b.right, y, p); break; }
            case RING: {
                float cx = b.exactCenterX(), cy = b.exactCenterY(), rr = Math.min(b.width(), b.height()) / 2f;
                c.drawCircle(cx, cy, rr * 0.75f, p);
                break;
            }
            default:
                if (radii != null) {
                    path.reset();
                    path.addRoundRect(r, radii, Path.Direction.CW);
                    c.drawPath(path, p);
                } else if (radius > 0) {
                    float k = p.getStyle() == Paint.Style.STROKE ? Math.max(0, radius - strokeWidth / 2f) : radius;
                    c.drawRoundRect(r, k, k, p);
                } else c.drawRect(r, p);
        }
    }

    private Shader shader(Rect b, int[] cs) {
        if (cs.length == 1) cs = new int[] {cs[0], cs[0]};
        if (gradientType == RADIAL_GRADIENT) {
            return new RadialGradient(b.left + b.width() * centerX, b.top + b.height() * centerY,
                    Math.max(1, gradientRadius <= 1 ? gradientRadius * Math.min(b.width(), b.height()) : gradientRadius), cs, null, Shader.TileMode.CLAMP);
        }
        if (gradientType == SWEEP_GRADIENT) return new SweepGradient(b.left + b.width() * centerX, b.top + b.height() * centerY, cs, null);
        float x0, y0, x1, y1;
        switch (orientation) {
            case TOP_BOTTOM: x0 = b.left; y0 = b.top; x1 = x0; y1 = b.bottom; break;
            case TR_BL: x0 = b.right; y0 = b.top; x1 = b.left; y1 = b.bottom; break;
            case RIGHT_LEFT: x0 = b.right; y0 = b.top; x1 = b.left; y1 = y0; break;
            case BR_TL: x0 = b.right; y0 = b.bottom; x1 = b.left; y1 = b.top; break;
            case BOTTOM_TOP: x0 = b.left; y0 = b.bottom; x1 = x0; y1 = b.top; break;
            case BL_TR: x0 = b.left; y0 = b.bottom; x1 = b.right; y1 = b.top; break;
            case LEFT_RIGHT: x0 = b.left; y0 = b.top; x1 = b.right; y1 = y0; break;
            default: x0 = b.left; y0 = b.top; x1 = b.right; y1 = b.bottom;
        }
        return new LinearGradient(x0, y0, x1, y1, cs, null, Shader.TileMode.CLAMP);
    }

    @Override
    public void getOutline(android.graphics.Outline o) {
        Rect b = getBounds();
        if (shape == OVAL) o.setOval(b.left, b.top, b.right, b.bottom);
        else o.setRoundRect(b, radius);
        o.setAlpha(hasColor ? (color >>> 24) / 255f : 1f);
    }

    @Override public Drawable mutate() { return this; }
}
