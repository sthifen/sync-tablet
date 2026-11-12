package android.animation;

import java.util.ArrayList;
import java.util.Collection;

public final class AnimatorSet extends Animator {
    private final ArrayList<Animator> items = new ArrayList<>();
    private boolean sequential;
    private long duration = -1, startDelay;
    private TimeInterpolator interpolator;
    private boolean running;

    public void playTogether(Animator... a) { sequential = false; for (Animator x : a) items.add(x); }
    public void playTogether(Collection<Animator> a) { sequential = false; items.addAll(a); }
    public void playSequentially(Animator... a) { sequential = true; for (Animator x : a) items.add(x); }
    public ArrayList<Animator> getChildAnimations() { return items; }

    public final class Builder {
        private final Animator anchor;
        Builder(Animator a) { anchor = a; if (!items.contains(a)) items.add(a); }
        public Builder with(Animator a) { if (!items.contains(a)) items.add(a); return this; }
        public Builder before(Animator a) { return with(a); }
        public Builder after(Animator a) { return with(a); }
    }

    public Builder play(Animator a) { return new Builder(a); }

    @Override
    public void start() {
        running = true;
        for (AnimatorListener l : listenersCopy()) l.onAnimationStart(this);
        if (items.isEmpty()) { finish(); return; }
        if (sequential) startAt(0);
        else {
            final int[] left = {items.size()};
            for (Animator a : items) {
                if (duration >= 0) a.setDuration(duration);
                if (interpolator != null) a.setInterpolator(interpolator);
                a.setStartDelay(a.getStartDelay() + startDelay);
                a.addListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator x) { x.removeListener(this); if (--left[0] == 0) finish(); }
                });
                a.start();
            }
        }
    }

    private void startAt(int i) {
        if (i >= items.size()) { finish(); return; }
        Animator a = items.get(i);
        if (duration >= 0) a.setDuration(duration);
        if (interpolator != null) a.setInterpolator(interpolator);
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator x) { x.removeListener(this); if (running) startAt(i + 1); }
        });
        a.start();
    }

    private void finish() {
        if (!running) return;
        running = false;
        for (AnimatorListener l : listenersCopy()) l.onAnimationEnd(this);
    }

    @Override public void cancel() { if (!running) return; running = false; for (Animator a : items) a.cancel(); for (AnimatorListener l : listenersCopy()) { l.onAnimationCancel(this); l.onAnimationEnd(this); } }
    @Override public void end() { for (Animator a : items) a.end(); finish(); }
    @Override public long getStartDelay() { return startDelay; }
    @Override public void setStartDelay(long d) { startDelay = d; }
    @Override public AnimatorSet setDuration(long d) { duration = d; return this; }
    @Override public long getDuration() { return duration; }
    @Override public void setInterpolator(TimeInterpolator i) { interpolator = i; }
    @Override public boolean isRunning() { return running; }
}
