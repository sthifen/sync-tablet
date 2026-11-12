package android.view.animation;

public class AnticipateOvershootInterpolator extends BaseInterpolator {
    private final float tension;
    public AnticipateOvershootInterpolator() { this(2f * 1.5f); }
    public AnticipateOvershootInterpolator(float t) { tension = t * 1.5f; }
    private static float a(float t, float s) { return t * t * ((s + 1) * t - s); }
    private static float o(float t, float s) { return t * t * ((s + 1) * t + s); }
    @Override public float getInterpolation(float t) { return t < 0.5f ? 0.5f * a(t * 2f, tension) : 0.5f * (o(t * 2f - 2f, tension) + 2f); }
}
