package android.window;

import java.util.ArrayList;

/** Atrás (Esc en el escritorio): el de más prioridad y, a igual prioridad, el último registrado. */
public final class OnBackInvokedDispatcher {
    public static final int PRIORITY_DEFAULT = 0, PRIORITY_OVERLAY = 1000000, PRIORITY_SYSTEM_NAVIGATION_OBSERVER = -2;

    private static final class Entry { final int priority; final OnBackInvokedCallback cb; Entry(int p, OnBackInvokedCallback c) { priority = p; cb = c; } }
    private final ArrayList<Entry> entries = new ArrayList<>();

    public void registerOnBackInvokedCallback(int priority, OnBackInvokedCallback cb) {
        unregisterOnBackInvokedCallback(cb);
        entries.add(new Entry(priority, cb));
    }

    public void unregisterOnBackInvokedCallback(OnBackInvokedCallback cb) { entries.removeIf(e -> e.cb == cb); }

    /** Llama al que toca; falso si no hay ninguno. */
    public boolean dispatch() {
        Entry best = null;
        for (Entry e : entries) if (e.priority >= 0 && (best == null || e.priority >= best.priority)) best = e;
        if (best == null) return false;
        best.cb.onBackInvoked();
        return true;
    }

    public boolean has() { for (Entry e : entries) if (e.priority >= 0) return true; return false; }
}
