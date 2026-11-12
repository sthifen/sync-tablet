package android.animation;

public class LayoutTransition {
    public static final int CHANGE_APPEARING = 0, CHANGE_DISAPPEARING = 1, APPEARING = 2, DISAPPEARING = 3, CHANGING = 4;
    public void enableTransitionType(int t) {}
    public void disableTransitionType(int t) {}
    public void setDuration(long d) {}
    public void setDuration(int type, long d) {}
    public void setAnimateParentHierarchy(boolean a) {}
    public void setInterpolator(int type, TimeInterpolator i) {}
    public void setStartDelay(int type, long d) {}
}
