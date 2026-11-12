package android.view;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

public class GestureDetector {
    public interface OnGestureListener {
        boolean onDown(MotionEvent e);
        void onShowPress(MotionEvent e);
        boolean onSingleTapUp(MotionEvent e);
        boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy);
        void onLongPress(MotionEvent e);
        boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy);
    }

    public interface OnDoubleTapListener {
        boolean onSingleTapConfirmed(MotionEvent e);
        boolean onDoubleTap(MotionEvent e);
        boolean onDoubleTapEvent(MotionEvent e);
    }

    public interface OnContextClickListener { boolean onContextClick(MotionEvent e); }

    public static class SimpleOnGestureListener implements OnGestureListener, OnDoubleTapListener, OnContextClickListener {
        @Override public boolean onSingleTapUp(MotionEvent e) { return false; }
        @Override public void onLongPress(MotionEvent e) {}
        @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) { return false; }
        @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) { return false; }
        @Override public void onShowPress(MotionEvent e) {}
        @Override public boolean onDown(MotionEvent e) { return false; }
        @Override public boolean onDoubleTap(MotionEvent e) { return false; }
        @Override public boolean onDoubleTapEvent(MotionEvent e) { return false; }
        @Override public boolean onSingleTapConfirmed(MotionEvent e) { return false; }
        @Override public boolean onContextClick(MotionEvent e) { return false; }
    }

    private final OnGestureListener listener;
    private OnDoubleTapListener doubleTap;
    private final Handler handler;
    private final int touchSlopSquare, doubleTapSlopSquare, minFling, maxFling;
    private boolean longpressEnabled = true;
    private boolean stillDown, deferConfirmSingleTap, inLongPress, alwaysInTapRegion, alwaysInBiggerTapRegion, isDoubleTapping, ignoreNextUp;
    private MotionEvent currentDown, previousUp;
    private float lastX, lastY, downX, downY;
    private VelocityTracker velocity;
    private final Runnable showPress = () -> { if (currentDown != null) GestureDetector.this.listener.onShowPress(currentDown); };
    private final Runnable longPress = this::dispatchLongPress;
    private final Runnable tap = () -> {
        if (doubleTap != null) {
            if (!stillDown) doubleTap.onSingleTapConfirmed(currentDown);
            else deferConfirmSingleTap = true;
        }
    };

    public GestureDetector(Context c, OnGestureListener l) { this(c, l, null); }
    public GestureDetector(Context c, OnGestureListener l, Handler h) {
        listener = l;
        handler = h != null ? h : new Handler(Looper.getMainLooper());
        if (l instanceof OnDoubleTapListener) doubleTap = (OnDoubleTapListener) l;
        ViewConfiguration conf = ViewConfiguration.get(c);
        int slop = conf.getScaledTouchSlop(), dslop = conf.getScaledDoubleTapSlop();
        touchSlopSquare = slop * slop;
        doubleTapSlopSquare = dslop * dslop;
        minFling = conf.getScaledMinimumFlingVelocity();
        maxFling = conf.getScaledMaximumFlingVelocity();
    }
    public GestureDetector(OnGestureListener l) { this(null, l, null); }

    public void setOnDoubleTapListener(OnDoubleTapListener l) { doubleTap = l; }
    public void setContextClickListener(OnContextClickListener l) {}
    public void setIsLongpressEnabled(boolean e) { longpressEnabled = e; }
    public boolean isLongpressEnabled() { return longpressEnabled; }

    private void dispatchLongPress() {
        handler.removeCallbacks(tap);
        deferConfirmSingleTap = false;
        inLongPress = true;
        if (currentDown != null) GestureDetector.this.listener.onLongPress(currentDown);
    }

    public boolean onGenericMotionEvent(MotionEvent e) { return false; }

    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(ev);
        boolean pointerUp = action == MotionEvent.ACTION_POINTER_UP;
        int skip = pointerUp ? ev.getActionIndex() : -1;
        float sx = 0, sy = 0;
        int count = ev.getPointerCount();
        for (int i = 0; i < count; i++) { if (i == skip) continue; sx += ev.getX(i); sy += ev.getY(i); }
        int div = pointerUp ? count - 1 : count;
        float fx = sx / div, fy = sy / div;
        boolean handled = false;
        switch (action) {
            case MotionEvent.ACTION_POINTER_DOWN:
                downX = lastX = fx; downY = lastY = fy;
                cancelTaps();
                break;
            case MotionEvent.ACTION_POINTER_UP:
                downX = lastX = fx; downY = lastY = fy;
                break;
            case MotionEvent.ACTION_DOWN:
                if (doubleTap != null) {
                    boolean had = handler.hasCallbacks(tap);
                    if (had) handler.removeCallbacks(tap);
                    if (currentDown != null && previousUp != null && had && isConsideredDoubleTap(currentDown, previousUp, ev)) {
                        isDoubleTapping = true;
                        handled |= doubleTap.onDoubleTap(currentDown);
                        handled |= doubleTap.onDoubleTapEvent(ev);
                    } else {
                        handler.postDelayed(tap, ViewConfiguration.getDoubleTapTimeout());
                    }
                }
                downX = lastX = fx; downY = lastY = fy;
                currentDown = MotionEvent.obtain(ev);
                alwaysInTapRegion = true;
                alwaysInBiggerTapRegion = true;
                stillDown = true;
                inLongPress = false;
                deferConfirmSingleTap = false;
                if (longpressEnabled) {
                    handler.removeCallbacks(longPress);
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                }
                handler.postDelayed(showPress, ViewConfiguration.getTapTimeout());
                handled |= listener.onDown(ev);
                break;
            case MotionEvent.ACTION_MOVE:
                if (inLongPress) break;
                float scrollX = lastX - fx, scrollY = lastY - fy;
                if (isDoubleTapping) {
                    handled |= doubleTap.onDoubleTapEvent(ev);
                } else if (alwaysInTapRegion) {
                    int dx = (int) (fx - downX), dy = (int) (fy - downY);
                    int dist = dx * dx + dy * dy;
                    if (dist > touchSlopSquare) {
                        handled = listener.onScroll(currentDown, ev, scrollX, scrollY);
                        lastX = fx; lastY = fy;
                        alwaysInTapRegion = false;
                        handler.removeCallbacks(tap);
                        handler.removeCallbacks(showPress);
                        handler.removeCallbacks(longPress);
                    }
                    if (dist > doubleTapSlopSquare) alwaysInBiggerTapRegion = false;
                } else if (Math.abs(scrollX) >= 1 || Math.abs(scrollY) >= 1) {
                    handled = listener.onScroll(currentDown, ev, scrollX, scrollY);
                    lastX = fx; lastY = fy;
                }
                break;
            case MotionEvent.ACTION_UP:
                stillDown = false;
                MotionEvent up = MotionEvent.obtain(ev);
                if (isDoubleTapping) {
                    handled |= doubleTap.onDoubleTapEvent(ev);
                } else if (inLongPress) {
                    handler.removeCallbacks(tap);
                    inLongPress = false;
                } else if (alwaysInTapRegion && !ignoreNextUp) {
                    handled = listener.onSingleTapUp(ev);
                    if (deferConfirmSingleTap && doubleTap != null) doubleTap.onSingleTapConfirmed(ev);
                } else if (!ignoreNextUp) {
                    velocity.computeCurrentVelocity(1000, maxFling);
                    float vy = velocity.getYVelocity(), vx = velocity.getXVelocity();
                    if (Math.abs(vy) > minFling || Math.abs(vx) > minFling) handled = listener.onFling(currentDown, ev, vx, vy);
                }
                previousUp = up;
                velocity.clear();
                isDoubleTapping = false;
                deferConfirmSingleTap = false;
                ignoreNextUp = false;
                handler.removeCallbacks(showPress);
                handler.removeCallbacks(longPress);
                break;
            case MotionEvent.ACTION_CANCEL:
                cancel();
                break;
        }
        return handled;
    }

    private void cancelTaps() {
        handler.removeCallbacks(showPress);
        handler.removeCallbacks(longPress);
        handler.removeCallbacks(tap);
        isDoubleTapping = false;
        alwaysInTapRegion = false;
        alwaysInBiggerTapRegion = false;
        deferConfirmSingleTap = false;
        if (inLongPress) inLongPress = false;
    }

    private void cancel() {
        cancelTaps();
        velocity.clear();
        stillDown = false;
    }

    private boolean isConsideredDoubleTap(MotionEvent firstDown, MotionEvent firstUp, MotionEvent secondDown) {
        if (!alwaysInBiggerTapRegion) return false;
        long dt = secondDown.getEventTime() - firstUp.getEventTime();
        if (dt > ViewConfiguration.getDoubleTapTimeout() || dt < 40) return false;
        int dx = (int) firstDown.getX() - (int) secondDown.getX(), dy = (int) firstDown.getY() - (int) secondDown.getY();
        return dx * dx + dy * dy < doubleTapSlopSquare;
    }
}
