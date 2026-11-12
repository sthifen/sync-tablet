package android.view.animation;

public class AccelerateDecelerateInterpolator extends BaseInterpolator {
    public AccelerateDecelerateInterpolator() {}
    @Override public float getInterpolation(float t) { return (float) (Math.cos((t + 1) * Math.PI) / 2.0f) + 0.5f; }
}
