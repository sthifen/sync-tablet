package android.view.animation;

public class TranslateAnimation extends Animation {
    private final float fx, tx, fy, ty;
    public TranslateAnimation(float fx, float tx, float fy, float ty) { this.fx = fx; this.tx = tx; this.fy = fy; this.ty = ty; }
    @Override protected void applyTransformation(float t, Transformation out) { out.matrix.setTranslate(fx + (tx - fx) * t, fy + (ty - fy) * t); }
}
