package android.animation;

import java.util.ArrayList;

public abstract class Animator implements Cloneable {
    public static final long DURATION_INFINITE = -1;

    public interface AnimatorListener {
        default void onAnimationStart(Animator a, boolean isReverse) { onAnimationStart(a); }
        void onAnimationStart(Animator a);
        default void onAnimationEnd(Animator a, boolean isReverse) { onAnimationEnd(a); }
        void onAnimationEnd(Animator a);
        void onAnimationCancel(Animator a);
        void onAnimationRepeat(Animator a);
    }

    public interface AnimatorPauseListener { void onAnimationPause(Animator a); void onAnimationResume(Animator a); }

    ArrayList<AnimatorListener> listeners;

    public void start() {}
    public void cancel() {}
    public void end() {}
    public void pause() {}
    public void resume() {}
    public boolean isPaused() { return false; }
    public abstract long getStartDelay();
    public abstract void setStartDelay(long d);
    public abstract Animator setDuration(long d);
    public abstract long getDuration();
    public abstract void setInterpolator(TimeInterpolator i);
    public TimeInterpolator getInterpolator() { return null; }
    public abstract boolean isRunning();
    public boolean isStarted() { return isRunning(); }
    public long getTotalDuration() { return getStartDelay() + getDuration(); }
    public void addListener(AnimatorListener l) { if (listeners == null) listeners = new ArrayList<>(); listeners.add(l); }
    public void removeListener(AnimatorListener l) { if (listeners != null) listeners.remove(l); }
    public ArrayList<AnimatorListener> getListeners() { return listeners; }
    public void removeAllListeners() { if (listeners != null) listeners.clear(); }
    public void addPauseListener(AnimatorPauseListener l) {}
    public void setTarget(Object target) {}
    public void setupStartValues() {}
    public void setupEndValues() {}
    @Override public Animator clone() { try { Animator a = (Animator) super.clone(); if (listeners != null) a.listeners = new ArrayList<>(listeners); return a; } catch (CloneNotSupportedException e) { throw new AssertionError(); } }

    ArrayList<AnimatorListener> listenersCopy() { return listeners == null ? new ArrayList<>() : new ArrayList<>(listeners); }
}
