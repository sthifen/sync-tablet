package android.view;

import android.content.Context;
import android.os.Handler;

public class ScaleGestureDetector {
    public interface OnScaleGestureListener {
        boolean onScale(ScaleGestureDetector d);
        boolean onScaleBegin(ScaleGestureDetector d);
        void onScaleEnd(ScaleGestureDetector d);
    }

    public static class SimpleOnScaleGestureListener implements OnScaleGestureListener {
        @Override public boolean onScale(ScaleGestureDetector d) { return false; }
        @Override public boolean onScaleBegin(ScaleGestureDetector d) { return true; }
        @Override public void onScaleEnd(ScaleGestureDetector d) {}
    }

    private final OnScaleGestureListener listener;
    private float focusX, focusY, currSpan, prevSpan, initialSpan, currSpanX, currSpanY, prevSpanX, prevSpanY;
    private long currTime, prevTime;
    private boolean inProgress;
    private final int spanSlop, minSpan;

    public ScaleGestureDetector(Context c, OnScaleGestureListener l) { this(c, l, null); }
    public ScaleGestureDetector(Context c, OnScaleGestureListener l, Handler h) {
        listener = l;
        ViewConfiguration conf = ViewConfiguration.get(c);
        spanSlop = conf.getScaledTouchSlop() * 2;
        float d = c == null ? 1 : c.getResources().getDisplayMetrics().density;
        minSpan = Math.round(27 * d);
    }

    public void setQuickScaleEnabled(boolean e) {}
    public boolean isQuickScaleEnabled() { return false; }
    public void setStylusScaleEnabled(boolean e) {}
    public boolean isStylusScaleEnabled() { return false; }

    public boolean onTouchEvent(MotionEvent e) {
        currTime = e.getEventTime();
        int action = e.getActionMasked();
        boolean streamComplete = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        if (action == MotionEvent.ACTION_DOWN || streamComplete) {
            if (inProgress) {
                listener.onScaleEnd(this);
                inProgress = false;
                initialSpan = 0;
            }
            if (streamComplete) return true;
        }
        boolean configChanged = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_POINTER_DOWN;
        boolean pointerUp = action == MotionEvent.ACTION_POINTER_UP;
        int skip = pointerUp ? e.getActionIndex() : -1;
        int count = e.getPointerCount();
        int div = pointerUp ? count - 1 : count;
        float sumX = 0, sumY = 0;
        for (int i = 0; i < count; i++) { if (i == skip) continue; sumX += e.getX(i); sumY += e.getY(i); }
        float fx = sumX / div, fy = sumY / div;
        float devX = 0, devY = 0;
        for (int i = 0; i < count; i++) { if (i == skip) continue; devX += Math.abs(e.getX(i) - fx); devY += Math.abs(e.getY(i) - fy); }
        devX /= div; devY /= div;
        float spanX = devX * 2, spanY = devY * 2;
        float span = (float) Math.hypot(spanX, spanY);
        boolean wasInProgress = inProgress;
        focusX = fx; focusY = fy;
        if (inProgress && (span < minSpan || configChanged)) {
            listener.onScaleEnd(this);
            inProgress = false;
            initialSpan = span;
        }
        if (configChanged) {
            prevSpanX = currSpanX = spanX;
            prevSpanY = currSpanY = spanY;
            initialSpan = prevSpan = currSpan = span;
        }
        int minSpanNow = minSpan;
        if (!inProgress && span >= minSpanNow && count >= 2 && (wasInProgress || Math.abs(span - initialSpan) > spanSlop)) {
            prevSpanX = currSpanX = spanX;
            prevSpanY = currSpanY = spanY;
            prevSpan = currSpan = span;
            prevTime = currTime;
            inProgress = listener.onScaleBegin(this);
        }
        if (action == MotionEvent.ACTION_MOVE) {
            currSpanX = spanX; currSpanY = spanY; currSpan = span;
            boolean update = true;
            if (inProgress) update = listener.onScale(this);
            if (update) {
                prevSpanX = currSpanX; prevSpanY = currSpanY; prevSpan = currSpan; prevTime = currTime;
            }
        }
        return true;
    }

    public boolean isInProgress() { return inProgress; }
    public float getFocusX() { return focusX; }
    public float getFocusY() { return focusY; }
    public float getCurrentSpan() { return currSpan; }
    public float getCurrentSpanX() { return currSpanX; }
    public float getCurrentSpanY() { return currSpanY; }
    public float getPreviousSpan() { return prevSpan; }
    public float getPreviousSpanX() { return prevSpanX; }
    public float getPreviousSpanY() { return prevSpanY; }
    public float getScaleFactor() { return prevSpan > 0 ? currSpan / prevSpan : 1; }
    public long getTimeDelta() { return currTime - prevTime; }
    public long getEventTime() { return currTime; }
    public int getMinSpan() { return minSpan; }
    public int getSpanSlop() { return spanSlop; }
}
