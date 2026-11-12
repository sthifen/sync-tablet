package android.view.animation;

public class AccelerateInterpolator extends BaseInterpolator {
    private final float factor;
    public AccelerateInterpolator() { this(1f); }
    public AccelerateInterpolator(float f) { factor = f; }
    @Override public float getInterpolation(float t) { return factor == 1f ? t * t : (float) Math.pow(t, 2 * factor); }
}
