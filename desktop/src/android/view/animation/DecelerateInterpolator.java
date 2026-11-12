package android.view.animation;

public class DecelerateInterpolator extends BaseInterpolator {
    private final float factor;
    public DecelerateInterpolator() { this(1f); }
    public DecelerateInterpolator(float f) { factor = f; }
    @Override public float getInterpolation(float t) { return factor == 1f ? 1f - (1f - t) * (1f - t) : (float) (1f - Math.pow(1f - t, 2 * factor)); }
}
