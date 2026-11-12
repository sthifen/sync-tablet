package android.animation;

public abstract class AnimatorListenerAdapter implements Animator.AnimatorListener, Animator.AnimatorPauseListener {
    @Override public void onAnimationCancel(Animator a) {}
    @Override public void onAnimationEnd(Animator a) {}
    @Override public void onAnimationRepeat(Animator a) {}
    @Override public void onAnimationStart(Animator a) {}
    @Override public void onAnimationPause(Animator a) {}
    @Override public void onAnimationResume(Animator a) {}
}
