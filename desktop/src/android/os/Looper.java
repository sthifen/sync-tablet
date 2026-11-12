package android.os;

/** Sólo hay un bucle: el hilo de Swing. Los hilos de fondo con Looper.prepare tienen el suyo propio. */
public final class Looper {
    private static final Looper MAIN = new Looper(true);
    private static final ThreadLocal<Looper> MINE = new ThreadLocal<>();

    final boolean main;
    final java.util.concurrent.LinkedBlockingQueue<Runnable> queue;
    private Thread thread;
    volatile boolean quit;

    private Looper(boolean main) {
        this.main = main;
        this.queue = main ? null : new java.util.concurrent.LinkedBlockingQueue<>();
    }

    public static Looper getMainLooper() { return MAIN; }
    public static Looper myLooper() { return MainQueue.isMain() ? MAIN : MINE.get(); }
    public static void prepare() { if (MINE.get() == null) { Looper l = new Looper(false); l.thread = Thread.currentThread(); MINE.set(l); } }
    public static void prepareMainLooper() {}
    public static void loop() {
        Looper l = MINE.get();
        if (l == null) return;
        while (!l.quit) {
            try {
                Runnable r = l.queue.take();
                r.run();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                android.util.Log.e("SyncTablet", "error en un hilo", t);
            }
        }
    }
    public boolean isCurrentThread() { return main ? MainQueue.isMain() : Thread.currentThread() == thread; }
    public Thread getThread() { return thread; }
    public void quit() { quit = true; if (queue != null) queue.offer(() -> {}); }
    public void quitSafely() { quit(); }
}
