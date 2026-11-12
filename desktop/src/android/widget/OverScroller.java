package android.widget;

import android.content.Context;
import android.view.animation.Interpolator;

/** Desplazamientos animados y lanzamientos con fricción, como OverScroller de Android. */
public class OverScroller {
    private static final int SCROLL = 0, FLING = 1, SPRING = 2;
    private int mode;
    private int startX, startY, finalX, finalY, currX, currY, minX, maxX, minY, maxY;
    private long startTime;
    private int duration;
    private boolean finished = true;
    private float vx, vy, friction = 0.015f;
    private final Interpolator interpolator;
    private final float density;
    private double flingK;

    public OverScroller(Context c) { this(c, null); }
    public OverScroller(Context c, Interpolator i) {
        interpolator = i;
        density = c == null ? 1 : c.getResources().getDisplayMetrics().density;
    }
    public OverScroller(Context c, Interpolator i, float bx, float by) { this(c, i); }
    public OverScroller(Context c, Interpolator i, float bx, float by, boolean flywheel) { this(c, i); }

    private static long now() { return android.view.animation.AnimationUtils.currentAnimationTimeMillis(); }

    public final void setFriction(float f) { friction = f; }
    public final boolean isFinished() { return finished; }
    public final void forceFinished(boolean f) { finished = f; }
    public final int getCurrX() { return currX; }
    public final int getCurrY() { return currY; }
    public final int getStartX() { return startX; }
    public final int getStartY() { return startY; }
    public final int getFinalX() { return finalX; }
    public final int getFinalY() { return finalY; }
    public float getCurrVelocity() {
        if (finished) return 0;
        if (mode == FLING) {
            double t = (now() - startTime) / 1000.0;
            double f = Math.exp(-flingK * t);
            return (float) (Math.hypot(vx, vy) * f);
        }
        return 0;
    }
    public final int getDuration() { return duration; }
    public final boolean isScrollingInDirection(float x, float y) { return !finished && Math.signum(x) == Math.signum(finalX - startX) && Math.signum(y) == Math.signum(finalY - startY); }
    public void setFinalX(int x) { finalX = x; }
    public void setFinalY(int y) { finalY = y; }

    public boolean computeScrollOffset() {
        if (finished) return false;
        long t = now() - startTime;
        if (mode == SCROLL || mode == SPRING) {
            if (t >= duration) { currX = finalX; currY = finalY; finished = true; return true; }
            float x = t / (float) duration;
            float v = interpolator != null ? interpolator.getInterpolation(x) : viscous(x);
            currX = startX + Math.round(v * (finalX - startX));
            currY = startY + Math.round(v * (finalY - startY));
            return true;
        }
        double secs = t / 1000.0;
        double f = 1 - Math.exp(-flingK * secs);
        double dist = 1 / flingK;
        int nx = (int) Math.round(startX + vx * dist * f), ny = (int) Math.round(startY + vy * dist * f);
        boolean done = t >= duration;
        // Se para en los bordes.
        if (nx < minX) { nx = minX; } else if (nx > maxX) { nx = maxX; }
        if (ny < minY) { ny = minY; } else if (ny > maxY) { ny = maxY; }
        if ((ny == minY || ny == maxY) && (nx == minX || nx == maxX)) done = true;
        if (vx == 0 && (ny == minY || ny == maxY)) done = true;
        if (vy == 0 && (nx == minX || nx == maxX)) done = true;
        currX = nx;
        currY = ny;
        if (done) { finished = true; finalX = nx; finalY = ny; }
        return true;
    }

    private static float viscous(float x) {
        x *= 8f;
        if (x < 1f) x -= 1f - (float) Math.exp(-x);
        else { float start = 0.36787944117f; x = 1f - (float) Math.exp(1f - x); x = start + x * (1f - start); }
        return x / 1.0052f;
    }

    public void startScroll(int sx, int sy, int dx, int dy) { startScroll(sx, sy, dx, dy, 250); }
    public void startScroll(int sx, int sy, int dx, int dy, int dur) {
        mode = SCROLL;
        finished = false;
        duration = Math.max(1, dur);
        startTime = now();
        startX = currX = sx; startY = currY = sy;
        finalX = sx + dx; finalY = sy + dy;
    }

    public boolean springBack(int sx, int sy, int minX, int maxX, int minY, int maxY) {
        int tx = Math.max(minX, Math.min(maxX, sx)), ty = Math.max(minY, Math.min(maxY, sy));
        if (tx == sx && ty == sy) { finished = true; return false; }
        startScroll(sx, sy, tx - sx, ty - sy, 220);
        mode = SPRING;
        return true;
    }

    public void fling(int sx, int sy, int vx, int vy, int minX, int maxX, int minY, int maxY) { fling(sx, sy, vx, vy, minX, maxX, minY, maxY, 0, 0); }

    public void fling(int sx, int sy, int vx, int vy, int minX, int maxX, int minY, int maxY, int overX, int overY) {
        mode = FLING;
        finished = false;
        startTime = now();
        startX = currX = sx; startY = currY = sy;
        this.vx = vx; this.vy = vy;
        this.minX = minX; this.maxX = maxX; this.minY = minY; this.maxY = maxY;
        // Fricción: cuanto mayor, antes se para (Android usa 0.015 por defecto).
        flingK = 4.2 * (friction / 0.015);
        double v = Math.hypot(vx, vy);
        double stopV = 30 * density;
        duration = v <= stopV ? 0 : (int) (1000 * Math.log(v / stopV) / flingK);
        double dist = 1 / flingK;
        finalX = Math.max(minX, Math.min(maxX, (int) Math.round(sx + vx * dist)));
        finalY = Math.max(minY, Math.min(maxY, (int) Math.round(sy + vy * dist)));
        if (duration <= 0) finished = true;
    }

    public void notifyHorizontalEdgeReached(int startX, int finalX, int overX) {}
    public void notifyVerticalEdgeReached(int startY, int finalY, int overY) {}
    public boolean isOverScrolled() { return false; }
    public void abortAnimation() { currX = finalX; currY = finalY; finished = true; }
    public int timePassed() { return (int) (now() - startTime); }
}
