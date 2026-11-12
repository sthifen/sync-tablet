package android.transition;

public class Transition {
    public interface TransitionListener {
        void onTransitionStart(Transition t);
        void onTransitionEnd(Transition t);
        void onTransitionCancel(Transition t);
        void onTransitionPause(Transition t);
        void onTransitionResume(Transition t);
    }
    public Transition setDuration(long d) { return this; }
    public Transition setInterpolator(android.animation.TimeInterpolator i) { return this; }
    public Transition addListener(TransitionListener l) { return this; }
    public Transition addTarget(android.view.View v) { return this; }
    public Transition excludeTarget(android.view.View v, boolean e) { return this; }
    public Transition setStartDelay(long d) { return this; }
}
