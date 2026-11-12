package android.view.animation;

public class OvershootInterpolator extends BaseInterpolator {
    private final float tension;
    public OvershootInterpolator() { this(2f); }
    public OvershootInterpolator(float t) { tension = t; }
    @Override public float getInterpolation(float t) { t -= 1f; return t * t * ((tension + 1) * t + tension) + 1f; }
}
