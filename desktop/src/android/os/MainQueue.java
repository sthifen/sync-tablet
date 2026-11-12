package android.os;

import java.util.ArrayList;
import java.util.PriorityQueue;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * La cola del hilo principal (el de Swing): lo que se manda con Handler.post
 * o View.post se ejecuta ahí, en orden y a su hora.
 */
public final class MainQueue {
    private static final class Msg implements Comparable<Msg> {
        final long when, seq;
        final Runnable r;
        final Object owner, token;
        Msg(long when, long seq, Runnable r, Object owner, Object token) { this.when = when; this.seq = seq; this.r = r; this.owner = owner; this.token = token; }
        @Override public int compareTo(Msg o) { return when != o.when ? Long.compare(when, o.when) : Long.compare(seq, o.seq); }
    }

    private static final PriorityQueue<Msg> QUEUE = new PriorityQueue<>();
    private static long seq;
    private static Timer timer;
    private static boolean pumpPosted;

    public static boolean isMain() { return SwingUtilities.isEventDispatchThread(); }

    public static void post(Object owner, Runnable r, long delayMs, Object token) {
        postAt(owner, r, SystemClock.uptimeMillis() + Math.max(0, delayMs), token);
    }

    public static void postAt(Object owner, Runnable r, long uptime, Object token) {
        if (r == null) return;
        synchronized (QUEUE) {
            QUEUE.add(new Msg(uptime, seq++, r, owner, token));
        }
        schedule();
    }

    public static void postAtFront(Object owner, Runnable r) {
        synchronized (QUEUE) {
            QUEUE.add(new Msg(Long.MIN_VALUE, seq++, r, owner, null));
        }
        schedule();
    }

    public static void remove(Object owner, Runnable r, Object token) {
        synchronized (QUEUE) {
            QUEUE.removeIf(m -> m.owner == owner && (r == null || m.r == r) && (token == null || m.token == token));
        }
    }

    public static boolean has(Object owner, Runnable r) {
        synchronized (QUEUE) {
            for (Msg m : QUEUE) if (m.owner == owner && (r == null || m.r == r)) return true;
        }
        return false;
    }

    private static void schedule() {
        synchronized (QUEUE) {
            if (pumpPosted) return;
            pumpPosted = true;
        }
        SwingUtilities.invokeLater(MainQueue::pump);
    }

    private static void pump() {
        synchronized (QUEUE) { pumpPosted = false; }
        long now = SystemClock.uptimeMillis();
        ArrayList<Msg> due = new ArrayList<>();
        long next;
        synchronized (QUEUE) {
            while (!QUEUE.isEmpty() && QUEUE.peek().when <= now) due.add(QUEUE.poll());
            next = QUEUE.isEmpty() ? -1 : QUEUE.peek().when;
        }
        for (Msg m : due) {
            try {
                m.r.run();
            } catch (Throwable t) {
                android.util.Log.e("SyncTablet", "error en el hilo principal", t);
            }
        }
        if (next >= 0) arm(Math.max(1, next - SystemClock.uptimeMillis()));
        synchronized (QUEUE) {
            if (!QUEUE.isEmpty() && QUEUE.peek().when <= SystemClock.uptimeMillis()) schedule();
        }
    }

    private static void arm(long delay) {
        if (timer == null) {
            timer = new Timer((int) delay, e -> pump());
            timer.setRepeats(false);
        }
        timer.stop();
        timer.setInitialDelay((int) Math.min(Integer.MAX_VALUE, delay));
        timer.start();
    }

    private MainQueue() {}
}
