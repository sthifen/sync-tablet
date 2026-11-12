package android.widget;

import android.content.Context;

public class Scroller extends OverScroller {
    public Scroller(Context c) { super(c); }
    public Scroller(Context c, android.view.animation.Interpolator i) { super(c, i); }
    public Scroller(Context c, android.view.animation.Interpolator i, boolean flywheel) { super(c, i); }
    public void extendDuration(int extend) {}
}
