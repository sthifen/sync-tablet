package android.os;

public class HandlerThread extends Thread {
    private Looper looper;
    public HandlerThread(String name) { super(name); setDaemon(true); }
    public HandlerThread(String name, int priority) { this(name); }
    @Override public void run() {
        Looper.prepare();
        synchronized (this) { looper = Looper.myLooper(); notifyAll(); }
        Looper.loop();
    }
    public Looper getLooper() {
        synchronized (this) {
            while (looper == null && isAlive()) { try { wait(); } catch (InterruptedException e) { return null; } }
            if (looper == null) { while (looper == null) { try { wait(10); } catch (InterruptedException e) { return null; } } }
        }
        return looper;
    }
    public boolean quit() { if (looper != null) looper.quit(); return true; }
    public boolean quitSafely() { return quit(); }
}
