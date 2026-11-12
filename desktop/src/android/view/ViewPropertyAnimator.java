package android.view;

import android.animation.Animator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;

public class ViewPropertyAnimator {
    private final View view;
    private long duration = 300, startDelay;
    private TimeInterpolator interpolator;
    private Animator.AnimatorListener listener;
    private ValueAnimator.AnimatorUpdateListener update;
    private Runnable startAction, endAction;
    private final float[] to = new float[8];
    private final boolean[] has = new boolean[8];
    private ValueAnimator running;
    private static final int ALPHA = 0, TX = 1, TY = 2, SX = 3, SY = 4, ROT = 5, TZ = 6, Z = 7;

    ViewPropertyAnimator(View v) { view = v; }

    private ViewPropertyAnimator set(int i, float v) { to[i] = v; has[i] = true; schedule(); return this; }
    private ViewPropertyAnimator by(int i, float d) { return set(i, current(i) + d); }

    private float current(int i) {
        switch (i) {
            case ALPHA: return view.getAlpha();
            case TX: return view.getTranslationX();
            case TY: return view.getTranslationY();
            case SX: return view.getScaleX();
            case SY: return view.getScaleY();
            case ROT: return view.getRotation();
            case TZ: return view.getTranslationZ();
            default: return view.getZ();
        }
    }

    private void apply(int i, float v) {
        switch (i) {
            case ALPHA: view.setAlpha(v); break;
            case TX: view.setTranslationX(v); break;
            case TY: view.setTranslationY(v); break;
            case SX: view.setScaleX(v); break;
            case SY: view.setScaleY(v); break;
            case ROT: view.setRotation(v); break;
            case TZ: view.setTranslationZ(v); break;
            default: view.setZ(v);
        }
    }

    public ViewPropertyAnimator alpha(float v) { return set(ALPHA, v); }
    public ViewPropertyAnimator alphaBy(float v) { return by(ALPHA, v); }
    public ViewPropertyAnimator translationX(float v) { return set(TX, v); }
    public ViewPropertyAnimator translationY(float v) { return set(TY, v); }
    public ViewPropertyAnimator translationXBy(float v) { return by(TX, v); }
    public ViewPropertyAnimator translationYBy(float v) { return by(TY, v); }
    public ViewPropertyAnimator translationZ(float v) { return set(TZ, v); }
    public ViewPropertyAnimator x(float v) { return set(TX, v - view.getLeft()); }
    public ViewPropertyAnimator y(float v) { return set(TY, v - view.getTop()); }
    public ViewPropertyAnimator z(float v) { return set(Z, v); }
    public ViewPropertyAnimator scaleX(float v) { return set(SX, v); }
    public ViewPropertyAnimator scaleY(float v) { return set(SY, v); }
    public ViewPropertyAnimator scaleXBy(float v) { return by(SX, v); }
    public ViewPropertyAnimator scaleYBy(float v) { return by(SY, v); }
    public ViewPropertyAnimator rotation(float v) { return set(ROT, v); }
    public ViewPropertyAnimator rotationBy(float v) { return by(ROT, v); }
    public ViewPropertyAnimator rotationX(float v) { return this; }
    public ViewPropertyAnimator rotationY(float v) { return this; }
    public ViewPropertyAnimator setDuration(long d) { duration = d; return this; }
    public long getDuration() { return duration; }
    public ViewPropertyAnimator setStartDelay(long d) { startDelay = d; return this; }
    public long getStartDelay() { return startDelay; }
    public ViewPropertyAnimator setInterpolator(TimeInterpolator i) { interpolator = i; return this; }
    public TimeInterpolator getInterpolator() { return interpolator; }
    public ViewPropertyAnimator setListener(Animator.AnimatorListener l) { listener = l; return this; }
    public ViewPropertyAnimator setUpdateListener(ValueAnimator.AnimatorUpdateListener l) { update = l; return this; }
    public ViewPropertyAnimator withEndAction(Runnable r) { endAction = r; return this; }
    public ViewPropertyAnimator withStartAction(Runnable r) { startAction = r; return this; }
    public ViewPropertyAnimator withLayer() { return this; }

    private boolean scheduled;
    private final Runnable starter = this::start;

    private void schedule() {
        if (!scheduled) { scheduled = true; android.os.MainQueue.post(this, starter, 0, null); }
    }

    public void start() {
        android.os.MainQueue.remove(this, starter, null);
        scheduled = false;
        boolean any = false;
        for (boolean b : has) any |= b;
        if (!any) return;
        if (running != null) { ValueAnimator r = running; running = null; r.cancel(); }
        final float[] from = new float[8], target = to.clone();
        final boolean[] which = has.clone();
        java.util.Arrays.fill(has, false);
        for (int i = 0; i < 8; i++) if (which[i]) from[i] = current(i);
        final Runnable sa = startAction, ea = endAction;
        final Animator.AnimatorListener li = listener;
        final ValueAnimator.AnimatorUpdateListener up = update;
        startAction = null; endAction = null;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(duration);
        a.setStartDelay(startDelay);
        if (interpolator != null) a.setInterpolator(interpolator);
        a.addUpdateListener(an -> {
            float f = an.getAnimatedFraction();
            float v = (Float) an.getAnimatedValue();
            for (int i = 0; i < 8; i++) if (which[i]) apply(i, from[i] + (target[i] - from[i]) * v);
            if (up != null) up.onAnimationUpdate(an);
        });
        a.addListener(new Animator.AnimatorListener() {
            boolean canceled;
            @Override public void onAnimationStart(Animator an) { if (sa != null) sa.run(); if (li != null) li.onAnimationStart(an); }
            @Override public void onAnimationEnd(Animator an) {
                if (running == an) running = null;
                if (li != null) li.onAnimationEnd(an);
                if (ea != null && !canceled) ea.run();
            }
            @Override public void onAnimationCancel(Animator an) { canceled = true; if (li != null) li.onAnimationCancel(an); }
            @Override public void onAnimationRepeat(Animator an) {}
        });
        running = a;
        a.start();
    }

    public void cancel() {
        android.os.MainQueue.remove(this, starter, null);
        scheduled = false;
        java.util.Arrays.fill(has, false);
        if (running != null) { ValueAnimator r = running; running = null; r.cancel(); }
    }
}
