package android.view.animation;

public class AlphaAnimation extends Animation {
    private final float from, to;
    public AlphaAnimation(float from, float to) { this.from = from; this.to = to; }
    @Override protected void applyTransformation(float t, Transformation out) { out.alpha = from + (to - from) * t; }
}
