package android.view.animation;

import android.graphics.Matrix;
import android.os.SystemClock;

/** Animaciones de vista antiguas (alpha, translate, scale): sólo lo básico. */
public abstract class Animation {
    public static final int INFINITE = -1, RESTART = 1, REVERSE = 2, ABSOLUTE = 0, RELATIVE_TO_SELF = 1, RELATIVE_TO_PARENT = 2;

    public interface AnimationListener {
        void onAnimationStart(Animation a);
        void onAnimationEnd(Animation a);
        void onAnimationRepeat(Animation a);
    }

    public static final class Transformation {
        public Matrix matrix;
        public float alpha = 1;
        public boolean done;
    }

    protected long duration = 300, startOffset;
    private long startTime = -1;
    protected Interpolator interpolator = new AccelerateDecelerateInterpolator();
    private AnimationListener listener;
    private boolean fillAfter;
    protected int width, height;

    public void setDuration(long d) { duration = d; }
    public long getDuration() { return duration; }
    public void setStartOffset(long o) { startOffset = o; }
    public void setInterpolator(Interpolator i) { interpolator = i; }
    public void setFillAfter(boolean f) { fillAfter = f; }
    public void setFillBefore(boolean f) {}
    public void setFillEnabled(boolean f) {}
    public void setRepeatCount(int c) {}
    public void setRepeatMode(int m) {}
    public void setAnimationListener(AnimationListener l) { listener = l; }
    public boolean hasStarted() { return startTime >= 0; }
    public boolean hasEnded() { return false; }
    public void reset() { startTime = -1; }
    public void cancel() { startTime = -1; }
    public void start() { startTime = SystemClock.uptimeMillis(); }
    public void startNow() { start(); }

    public void start(android.view.View v) {
        width = v.getWidth(); height = v.getHeight();
        start();
        if (listener != null) listener.onAnimationStart(this);
    }

    public void ended(android.view.View v) { if (listener != null) listener.onAnimationEnd(this); }

    public Transformation step() {
        Transformation t = new Transformation();
        long now = SystemClock.uptimeMillis();
        float f = duration <= 0 ? 1 : Math.max(0, Math.min(1, (now - startTime - startOffset) / (float) duration));
        t.matrix = new Matrix();
        applyTransformation(interpolator.getInterpolation(f), t);
        t.done = f >= 1;
        return t;
    }

    protected void applyTransformation(float t, Transformation out) {}
}
