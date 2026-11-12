package android.view;

public final class Choreographer {
    public interface FrameCallback { void doFrame(long frameTimeNanos); }
    private static final Choreographer INSTANCE = new Choreographer();
    public static Choreographer getInstance() { return INSTANCE; }
    public void postFrameCallback(FrameCallback cb) { postFrameCallbackDelayed(cb, 0); }
    public void postFrameCallbackDelayed(FrameCallback cb, long delay) {
        android.os.MainQueue.post(this, () -> cb.doFrame(System.nanoTime()), Math.max(8, delay), cb);
    }
    public void removeFrameCallback(FrameCallback cb) { android.os.MainQueue.remove(this, null, cb); }
}
