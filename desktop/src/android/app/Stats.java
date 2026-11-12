package android.app;

import java.util.HashMap;
import java.util.Map;

/**
 * Con -Dsynctablet.fps: cada 2 s, cuántos fotogramas se pintaron, cuánto
 * tardaron y quién pidió repintar (para cazar animaciones que no paran).
 */
final class Stats {
    static final boolean ON = System.getProperty("synctablet.fps") != null;
    private static long frames, total, max, last = System.nanoTime();
    private static final Map<String, Integer> who = new HashMap<>();

    private Stats() {}

    static {
        if (ON && System.getProperty("synctablet.watch") != null) {
            Thread w = new Thread(() -> {
                while (true) {
                    try { Thread.sleep(700); } catch (InterruptedException e) { return; }
                    for (java.util.Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                        if (!e.getKey().getName().startsWith("AWT-EventQueue")) continue;
                        StringBuilder b = new StringBuilder("EDT " + e.getKey().getState() + ":");
                        StackTraceElement[] st = e.getValue();
                        for (int i = 0; i < Math.min(Integer.getInteger("synctablet.watchdepth", 9), st.length); i++) b.append(" ← ").append(st[i].getClassName().replaceAll(".*\\.", "")).append('.').append(st[i].getMethodName());
                        System.err.println(b);
                    }
                }
            }, "vigía");
            w.setDaemon(true);
            w.start();
        }
    }

    static synchronized void frame(long ns) {
        frames++;
        total += ns;
        max = Math.max(max, ns);
        long now = System.nanoTime();
        if (now - last < 2_000_000_000L) return;
        double secs = (now - last) / 1e9;
        StringBuilder top = new StringBuilder();
        who.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(4)
                .forEach(e -> top.append("  ").append(e.getValue()).append("× ").append(e.getKey()));
        System.err.printf("fps %.1f · media %.1f ms · peor %.1f ms ·%s%n", frames / secs, total / 1e6 / Math.max(1, frames), max / 1e6, top);
        frames = total = max = 0;
        who.clear();
        last = now;
    }

    /** Quién pidió repintar: el primer marco fuera de la maquinaria de vistas y colas. */
    static synchronized void asked() {
        StringBuilder k = new StringBuilder();
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            if (c.startsWith("android.app.") || c.startsWith("android.view.View") || c.startsWith("android.view.ViewRoot")
                    || c.startsWith("android.view.ViewGroup") || c.startsWith("android.os.") || c.startsWith("java.")) continue;
            k.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(e.getMethodName());
            if (c.startsWith("dev.synctablet") || k.length() > 120) break;
            k.append(" ← ");
        }
        who.merge(k.length() == 0 ? "?" : k.toString(), 1, Integer::sum);
    }
}
