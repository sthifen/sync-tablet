package android.graphics.drawable;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;

/**
 * El efecto de toque de Android. En el escritorio: un velo del color de la
 * onda al pasar el ratón (más claro) y al pulsar.
 */
public class RippleDrawable extends LayerDrawable {
    private ColorStateList color;
    private final Drawable content, mask;
    private boolean pressed, hovered, focused;
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int radius = -1;

    public RippleDrawable(ColorStateList color, Drawable content, Drawable mask) {
        super(content == null ? new Drawable[0] : new Drawable[] {content});
        this.color = color;
        this.content = content;
        this.mask = mask;
    }

    public void setColor(ColorStateList c) { color = c; invalidateSelf(); }
    public void setRadius(int r) { radius = r; }
    public int getRadius() { return radius; }

    @Override public boolean isStateful() { return true; }

    @Override
    protected boolean onStateChange(int[] state) {
        super.onStateChange(state);
        boolean p = has(state, android.R.attr.state_pressed), h = has(state, android.R.attr.state_hovered), f = has(state, android.R.attr.state_focused) && has(state, android.R.attr.state_focused_visible);
        boolean changed = p != pressed || h != hovered || f != focused;
        pressed = p; hovered = h; focused = f;
        if (changed) invalidateSelf();
        return true;
    }

    @Override
    protected void onBoundsChange(Rect b) {
        super.onBoundsChange(b);
        if (mask != null) mask.setBounds(b);
    }

    @Override
    public void draw(Canvas c) {
        super.draw(c);
        if (!pressed && !hovered) return;
        int base = color == null ? 0x33000000 : color.getDefaultColor();
        int a = base >>> 24;
        if (!pressed) a = a * 5 / 10;
        veil.setColor((a << 24) | (base & 0xFFFFFF));
        Rect b = getBounds();
        Drawable shape = mask != null ? mask : content;
        c.save();
        if (shape instanceof GradientDrawable) {
            GradientDrawable g = (GradientDrawable) shape;
            Path p = new Path();
            RectF r = new RectF(b);
            if (g.getShape() == GradientDrawable.OVAL) p.addOval(r, Path.Direction.CW);
            else if (g.getCornerRadii() != null) p.addRoundRect(r, g.getCornerRadii(), Path.Direction.CW);
            else p.addRoundRect(r, g.getCornerRadius(), g.getCornerRadius(), Path.Direction.CW);
            c.clipPath(p);
            c.drawRect(b, veil);
        } else if (shape == null) {
            // Sin máscara: un círculo que sale de la vista, como en Android.
            float r = radius > 0 ? radius : (float) Math.hypot(b.width(), b.height()) / 2f;
            r = Math.min(r, Math.max(b.width(), b.height()) * 0.5f + 4);
            c.drawCircle(b.exactCenterX(), b.exactCenterY(), r, veil);
        } else {
            c.clipRect(b);
            c.drawRect(b, veil);
        }
        c.restore();
    }
}
