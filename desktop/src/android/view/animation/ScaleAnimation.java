package android.view.animation;

public class ScaleAnimation extends Animation {
    private final float fx, tx, fy, ty, px, py;
    public ScaleAnimation(float fx, float tx, float fy, float ty) { this(fx, tx, fy, ty, 0, 0); }
    public ScaleAnimation(float fx, float tx, float fy, float ty, float px, float py) { this.fx = fx; this.tx = tx; this.fy = fy; this.ty = ty; this.px = px; this.py = py; }
    @Override protected void applyTransformation(float t, Transformation out) { out.matrix.setScale(fx + (tx - fx) * t, fy + (ty - fy) * t, px, py); }
}
