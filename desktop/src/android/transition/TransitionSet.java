package android.transition;

public class TransitionSet extends Transition {
    public static final int ORDERING_TOGETHER = 0, ORDERING_SEQUENTIAL = 1;
    public TransitionSet addTransition(Transition t) { return this; }
    public TransitionSet setOrdering(int o) { return this; }
}
