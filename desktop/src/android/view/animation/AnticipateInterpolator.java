package android.view.animation;

public class AnticipateInterpolator extends BaseInterpolator {
    private final float tension;
    public AnticipateInterpolator() { this(2f); }
    public AnticipateInterpolator(float t) { tension = t; }
    @Override public float getInterpolation(float t) { return t * t * ((tension + 1) * t - tension); }
}
