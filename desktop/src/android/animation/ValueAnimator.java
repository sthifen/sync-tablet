package android.animation;

import android.os.MainQueue;
import android.os.SystemClock;
import java.util.ArrayList;

/** Animaciones a 60 cuadros por segundo con un temporizador de Swing. */
public class ValueAnimator extends Animator {
    public static final int RESTART = 1, REVERSE = 2, INFINITE = -1;

    public interface AnimatorUpdateListener { void onAnimationUpdate(ValueAnimator a); }

    private static final ArrayList<ValueAnimator> ACTIVE = new ArrayList<>();
    private static javax.swing.Timer ticker;
    private static float durationScale = 1f;

    public static void setDurationScale(float s) { durationScale = s; }
    public static float getDurationScale() { return durationScale; }
    public static boolean areAnimatorsEnabled() { return true; }
    public static long getFrameDelay() { return 16; }

    private float[] floats;
    private int[] ints;
    private Object[] objects;
    private TypeEvaluator<?> evaluator;
    private long duration = 300, startDelay;
    private TimeInterpolator interpolator = new android.view.animation.AccelerateDecelerateInterpolator();
    private final ArrayList<AnimatorUpdateListener> updates = new ArrayList<>();
    /** Con -Dsynctablet.fps: quién la arrancó (para cazar animaciones que no paran). */
    private String origin;
    private static long lastReport;
    private boolean running, started, reversing;
    private long startTime;
    private float fraction, animatedFraction;
    private Object value;
    private int repeatCount, repeatMode = RESTART, iteration;
    private long seekTime = -1;

    public ValueAnimator() {}

    public static ValueAnimator ofFloat(float... v) { ValueAnimator a = new ValueAnimator(); a.setFloatValues(v); return a; }
    public static ValueAnimator ofInt(int... v) { ValueAnimator a = new ValueAnimator(); a.setIntValues(v); return a; }
    public static ValueAnimator ofArgb(int... v) { ValueAnimator a = new ValueAnimator(); a.setIntValues(v); a.setEvaluator(ArgbEvaluator.getInstance()); return a; }
    public static ValueAnimator ofObject(TypeEvaluator<?> e, Object... v) { ValueAnimator a = new ValueAnimator(); a.setObjectValues(v); a.setEvaluator(e); return a; }
    public static ValueAnimator ofPropertyValuesHolder(PropertyValuesHolder... h) { ValueAnimator a = new ValueAnimator(); if (h.length > 0) a.setFloatValues(h[0].floats); return a; }

    public void setFloatValues(float... v) { floats = v.clone(); ints = null; objects = null; value = v.length > 0 ? v[0] : 0f; }
    public void setIntValues(int... v) { ints = v.clone(); floats = null; objects = null; value = v.length > 0 ? v[0] : 0; }
    public void setObjectValues(Object... v) { objects = v.clone(); floats = null; ints = null; value = v.length > 0 ? v[0] : null; }
    public void setEvaluator(TypeEvaluator<?> e) { evaluator = e; }

    @Override public ValueAnimator setDuration(long d) { if (d < 0) throw new IllegalArgumentException("Animators cannot have negative duration: " + d); duration = d; return this; }
    @Override public long getDuration() { return duration; }
    @Override public long getStartDelay() { return startDelay; }
    @Override public void setStartDelay(long d) { startDelay = Math.max(0, d); }
    @Override public void setInterpolator(TimeInterpolator i) { interpolator = i == null ? new android.view.animation.LinearInterpolator() : i; }
    @Override public TimeInterpolator getInterpolator() { return interpolator; }
    public void setRepeatCount(int c) { repeatCount = c; }
    public int getRepeatCount() { return repeatCount; }
    public void setRepeatMode(int m) { repeatMode = m; }
    public int getRepeatMode() { return repeatMode; }
    public void addUpdateListener(AnimatorUpdateListener l) { updates.add(l); }
    public void removeUpdateListener(AnimatorUpdateListener l) { updates.remove(l); }
    public void removeAllUpdateListeners() { updates.clear(); }
    @Override public boolean isRunning() { return running; }
    @Override public boolean isStarted() { return started; }
    public Object getAnimatedValue() { return value; }
    public Object getAnimatedValue(String name) { return value; }
    public float getAnimatedFraction() { return animatedFraction; }
    public long getCurrentPlayTime() { return started ? SystemClock.uptimeMillis() - startTime : 0; }
    public void setCurrentPlayTime(long t) { seekTime = t; if (!started) { animateValue(duration == 0 ? 1 : Math.min(1, t / (float) duration)); } else startTime = SystemClock.uptimeMillis() - t; }
    public void setCurrentFraction(float f) { setCurrentPlayTime((long) (f * duration)); }

    @Override
    public void start() { start(false); }

    public void reverse() { start(!reversing); }

    private void start(boolean reverse) {
        if (!android.os.MainQueue.isMain()) { MainQueue.post(this, () -> start(reverse), 0, null); return; }
        reversing = reverse;
        started = true;
        running = startDelay == 0;
        iteration = 0;
        startTime = SystemClock.uptimeMillis() + startDelay - (seekTime > 0 ? seekTime : 0);
        seekTime = -1;
        for (AnimatorListener l : listenersCopy()) l.onAnimationStart(this);
        animateValue(reverse ? 1 : 0);
        if (System.getProperty("synctablet.fps") != null) {
            for (StackTraceElement e : new Throwable().getStackTrace()) {
                if (e.getClassName().startsWith("dev.synctablet")) { origin = e.getClassName().replace("dev.synctablet.", "") + "." + e.getMethodName() + ":" + e.getLineNumber(); break; }
            }
        }
        synchronized (ACTIVE) { if (!ACTIVE.contains(this)) ACTIVE.add(this); }
        ensureTicker();
        if (duration == 0 && startDelay == 0) frame(SystemClock.uptimeMillis());
    }

    @Override
    public void cancel() {
        if (!started) return;
        stop();
        for (AnimatorListener l : listenersCopy()) l.onAnimationCancel(this);
        for (AnimatorListener l : listenersCopy()) l.onAnimationEnd(this);
    }

    @Override
    public void end() {
        if (!started) { start(); }
        animateValue(reversing ? 0 : 1);
        stop();
        for (AnimatorListener l : listenersCopy()) l.onAnimationEnd(this);
    }

    private void stop() {
        running = false;
        started = false;
        synchronized (ACTIVE) { ACTIVE.remove(this); }
    }

    private void frame(long now) {
        if (!started) return;
        if (now < startTime) return;
        running = true;
        float d = duration * durationScale;
        float t = d <= 0 ? 1f : (now - startTime) / d;
        boolean done = false;
        if (t >= 1f) {
            if (repeatCount == INFINITE || iteration < repeatCount) {
                int loops = (int) t;
                iteration += loops;
                startTime += (long) (loops * d);
                t = t - loops;
                if (repeatMode == REVERSE && (loops % 2 == 1)) reversing = !reversing;
                for (AnimatorListener l : listenersCopy()) l.onAnimationRepeat(this);
            } else {
                t = 1f;
                done = true;
            }
        }
        animateValue(reversing ? 1 - t : t);
        if (done) {
            stop();
            for (AnimatorListener l : listenersCopy()) l.onAnimationEnd(this);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    void animateValue(float f) {
        fraction = f;
        animatedFraction = f;
        float v = interpolator.getInterpolation(f);
        if (floats != null) value = interp(floats, v);
        else if (ints != null) {
            if (evaluator != null) {
                int n = ints.length;
                if (n == 1) value = ints[0];
                else {
                    float pos = v * (n - 1);
                    int i = Math.max(0, Math.min(n - 2, (int) Math.floor(pos)));
                    value = ((TypeEvaluator) evaluator).evaluate(pos - i, ints[i], ints[i + 1]);
                }
            } else value = Math.round(interp(ints, v));
        } else if (objects != null && evaluator != null && objects.length > 0) {
            int n = objects.length;
            if (n == 1) value = objects[0];
            else {
                float pos = v * (n - 1);
                int i = Math.max(0, Math.min(n - 2, (int) Math.floor(pos)));
                value = ((TypeEvaluator) evaluator).evaluate(pos - i, objects[i], objects[i + 1]);
            }
        }
        for (AnimatorUpdateListener l : new ArrayList<>(updates)) l.onAnimationUpdate(this);
    }

    private static float interp(float[] vs, float v) {
        if (vs.length == 0) return 0;
        if (vs.length == 1) return vs[0];
        float pos = v * (vs.length - 1);
        int i = Math.max(0, Math.min(vs.length - 2, (int) Math.floor(pos)));
        float u = pos - i;
        return vs[i] + (vs[i + 1] - vs[i]) * u;
    }

    private static float interp(int[] vs, float v) {
        if (vs.length == 0) return 0;
        if (vs.length == 1) return vs[0];
        float pos = v * (vs.length - 1);
        int i = Math.max(0, Math.min(vs.length - 2, (int) Math.floor(pos)));
        float u = pos - i;
        return vs[i] + (vs[i + 1] - vs[i]) * u;
    }

    private static void ensureTicker() {
        if (ticker == null) {
            ticker = new javax.swing.Timer(14, e -> tick());
            ticker.setCoalesce(true);
        }
        if (!ticker.isRunning()) ticker.start();
    }

    private static void tick() {
        ArrayList<ValueAnimator> list;
        synchronized (ACTIVE) { list = new ArrayList<>(ACTIVE); }
        long now = SystemClock.uptimeMillis();
        for (ValueAnimator a : list) {
            try { a.frame(now); } catch (Throwable t) { android.util.Log.e("SyncTablet", "animación", t); a.stop(); }
        }
        synchronized (ACTIVE) { if (ACTIVE.isEmpty()) ticker.stop(); }
        if (origin(list) && now - lastReport > 2000) {
            lastReport = now;
            StringBuilder b = new StringBuilder("animaciones activas:");
            for (ValueAnimator a : list) b.append(" [").append(a.origin).append(" dur=").append(a.getDuration()).append(" rep=").append(a.getRepeatCount()).append("]");
            System.err.println(b);
        }
    }

    private static boolean origin(ArrayList<ValueAnimator> list) {
        return System.getProperty("synctablet.fps") != null && !list.isEmpty();
    }

    @Override public ValueAnimator clone() { ValueAnimator a = (ValueAnimator) super.clone(); return a; }
}
