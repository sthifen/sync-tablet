package android.os;

public final class SystemClock {
    private static final long START = System.nanoTime();
    public static long uptimeMillis() { return (System.nanoTime() - START) / 1_000_000L + 10_000L; }
    public static long elapsedRealtime() { return uptimeMillis(); }
    public static long elapsedRealtimeNanos() { return System.nanoTime() - START + 10_000_000_000L; }
    public static long uptimeNanos() { return elapsedRealtimeNanos(); }
    public static long currentThreadTimeMillis() { return uptimeMillis(); }
    public static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
    public static boolean setCurrentTimeMillis(long m) { return false; }
    private SystemClock() {}
}
