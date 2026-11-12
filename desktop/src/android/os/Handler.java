package android.os;

import java.util.ArrayList;

public class Handler {
    public interface Callback { boolean handleMessage(Message msg); }

    private final Looper looper;
    private final Callback callback;

    public Handler() { this(Looper.myLooper() == null ? Looper.getMainLooper() : Looper.myLooper(), null); }
    public Handler(Callback cb) { this(Looper.getMainLooper(), cb); }
    public Handler(Looper looper) { this(looper, null); }
    public Handler(Looper looper, Callback cb) { this.looper = looper == null ? Looper.getMainLooper() : looper; this.callback = cb; }

    public static Handler createAsync(Looper l) { return new Handler(l); }

    public final Looper getLooper() { return looper; }

    public void handleMessage(Message msg) {}

    public void dispatchMessage(Message msg) {
        if (msg.callback != null) { msg.callback.run(); return; }
        if (callback != null && callback.handleMessage(msg)) return;
        handleMessage(msg);
    }

    private final ArrayList<Message> pending = new ArrayList<>();

    private boolean enqueue(Runnable r, long delay, Object token) {
        if (looper.main) {
            MainQueue.post(this, r, delay, token);
        } else {
            if (delay <= 0) looper.queue.offer(r);
            else {
                Thread t = new Thread(() -> { SystemClock.sleep(delay); looper.queue.offer(r); });
                t.setDaemon(true);
                t.start();
            }
        }
        return true;
    }

    public final boolean post(Runnable r) { return enqueue(r, 0, null); }
    public final boolean postDelayed(Runnable r, long delayMillis) { return enqueue(r, delayMillis, null); }
    public final boolean postDelayed(Runnable r, Object token, long delayMillis) { return enqueue(r, delayMillis, token); }
    public final boolean postAtTime(Runnable r, long uptimeMillis) { return enqueue(r, uptimeMillis - SystemClock.uptimeMillis(), null); }
    public final boolean postAtTime(Runnable r, Object token, long uptimeMillis) { return enqueue(r, uptimeMillis - SystemClock.uptimeMillis(), token); }
    public final boolean postAtFrontOfQueue(Runnable r) { if (looper.main) { MainQueue.postAtFront(this, r); return true; } return post(r); }

    public final void removeCallbacks(Runnable r) { MainQueue.remove(this, r, null); }
    public final void removeCallbacks(Runnable r, Object token) { MainQueue.remove(this, r, token); }
    public final void removeCallbacksAndMessages(Object token) { MainQueue.remove(this, null, token); }
    public final boolean hasCallbacks(Runnable r) { return MainQueue.has(this, r); }

    // Mensajes: cada uno se manda como un Runnable propio.
    private final java.util.HashMap<Integer, ArrayList<Runnable>> byWhat = new java.util.HashMap<>();

    public final boolean sendMessage(Message m) { return sendMessageDelayed(m, 0); }
    public final boolean sendEmptyMessage(int what) { return sendMessage(Message.obtain(this, what)); }
    public final boolean sendEmptyMessageDelayed(int what, long delay) { return sendMessageDelayed(Message.obtain(this, what), delay); }
    public final boolean sendMessageDelayed(Message m, long delay) {
        m.target = this;
        Runnable[] self = new Runnable[1];
        self[0] = () -> {
            synchronized (byWhat) { ArrayList<Runnable> l = byWhat.get(m.what); if (l != null) l.remove(self[0]); }
            dispatchMessage(m);
        };
        synchronized (byWhat) { byWhat.computeIfAbsent(m.what, k -> new ArrayList<>()).add(self[0]); }
        return enqueue(self[0], delay, null);
    }
    public final boolean sendMessageAtTime(Message m, long uptime) { return sendMessageDelayed(m, uptime - SystemClock.uptimeMillis()); }
    public final void removeMessages(int what) {
        ArrayList<Runnable> l;
        synchronized (byWhat) { l = byWhat.remove(what); }
        if (l != null) for (Runnable r : l) MainQueue.remove(this, r, null);
    }
    public final boolean hasMessages(int what) { synchronized (byWhat) { ArrayList<Runnable> l = byWhat.get(what); return l != null && !l.isEmpty(); } }
    public final Message obtainMessage() { return Message.obtain(this); }
    public final Message obtainMessage(int what) { return Message.obtain(this, what); }
    public final Message obtainMessage(int what, Object obj) { return Message.obtain(this, what, obj); }
}
