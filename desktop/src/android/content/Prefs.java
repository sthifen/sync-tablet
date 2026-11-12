package android.content;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/** Preferencias guardadas en JSON, una por nombre: ~/.local/share/synctablet/shared_prefs/<nombre>.json */
public final class Prefs implements SharedPreferences {
    private static final HashMap<String, Prefs> ALL = new HashMap<>();
    private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "prefs");
        t.setDaemon(true);
        return t;
    });

    /** Espera a que se escriba todo (al cerrar la app). */
    public static void flush() {
        try { WRITER.submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
    }

    private final File file;
    private final HashMap<String, Object> map = new HashMap<>();
    private final CopyOnWriteArrayList<OnSharedPreferenceChangeListener> listeners = new CopyOnWriteArrayList<>();

    static synchronized Prefs get(String name) {
        Prefs p = ALL.get(name);
        if (p == null) { p = new Prefs(name); ALL.put(name, p); }
        return p;
    }

    static synchronized boolean delete(String name) {
        Prefs p = ALL.remove(name);
        File f = p != null ? p.file : new File(new File(Context.dataRoot(), "shared_prefs"), name + ".json");
        return f.delete();
    }

    private Prefs(String name) {
        File dir = new File(Context.dataRoot(), "shared_prefs");
        dir.mkdirs();
        file = new File(dir, name + ".json");
        if (file.exists()) {
            try {
                JSONObject o = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    Object v = o.get(k);
                    if (v instanceof JSONObject) {
                        JSONObject t = (JSONObject) v;
                        String type = t.optString("t");
                        switch (type) {
                            case "long": map.put(k, t.getLong("v")); break;
                            case "float": map.put(k, (float) t.getDouble("v")); break;
                            case "set": {
                                LinkedHashSet<String> s = new LinkedHashSet<>();
                                JSONArray a = t.getJSONArray("v");
                                for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
                                map.put(k, s);
                                break;
                            }
                            default: map.put(k, t.opt("v"));
                        }
                    } else if (v instanceof Integer || v instanceof Boolean || v instanceof String) {
                        map.put(k, v);
                    } else if (v instanceof Long) {
                        map.put(k, (int) (long) (Long) v);
                    } else if (v instanceof Double) {
                        map.put(k, (float) (double) (Double) v);
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("SyncTablet", "preferencias ilegibles: " + file, e);
            }
        }
    }

    private synchronized void save() {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, Object> e : map.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Long) o.put(e.getKey(), new JSONObject().put("t", "long").put("v", (long) (Long) v));
                else if (v instanceof Float) o.put(e.getKey(), new JSONObject().put("t", "float").put("v", (double) (Float) v));
                else if (v instanceof Set) {
                    JSONArray a = new JSONArray();
                    for (Object s : (Set<?>) v) a.put(s);
                    o.put(e.getKey(), new JSONObject().put("t", "set").put("v", a));
                } else o.put(e.getKey(), v);
            }
        } catch (Exception ignored) {}
        final String text = o.toString();
        WRITER.execute(() -> {
            try {
                File tmp = new File(file.getPath() + ".tmp");
                Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                android.util.Log.w("SyncTablet", "no se pudieron guardar las preferencias", e);
            }
        });
    }

    @Override public synchronized Map<String, ?> getAll() { return new HashMap<>(map); }
    @Override public synchronized String getString(String k, String def) { Object v = map.get(k); return v instanceof String ? (String) v : def; }
    @SuppressWarnings("unchecked")
    @Override public synchronized Set<String> getStringSet(String k, Set<String> def) { Object v = map.get(k); return v instanceof Set ? (Set<String>) v : def; }
    @Override public synchronized int getInt(String k, int def) { Object v = map.get(k); return v instanceof Number ? ((Number) v).intValue() : def; }
    @Override public synchronized long getLong(String k, long def) { Object v = map.get(k); return v instanceof Number ? ((Number) v).longValue() : def; }
    @Override public synchronized float getFloat(String k, float def) { Object v = map.get(k); return v instanceof Number ? ((Number) v).floatValue() : def; }
    @Override public synchronized boolean getBoolean(String k, boolean def) { Object v = map.get(k); return v instanceof Boolean ? (Boolean) v : def; }
    @Override public synchronized boolean contains(String k) { return map.containsKey(k); }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { listeners.add(l); }
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { listeners.remove(l); }

    @Override
    public Editor edit() {
        return new Editor() {
            private final HashMap<String, Object> puts = new HashMap<>();
            private final HashSet<String> removes = new HashSet<>();
            private boolean clear;
            @Override public Editor putString(String k, String v) { if (v == null) return remove(k); puts.put(k, v); return this; }
            @Override public Editor putStringSet(String k, Set<String> v) { if (v == null) return remove(k); puts.put(k, new LinkedHashSet<>(v)); return this; }
            @Override public Editor putInt(String k, int v) { puts.put(k, v); return this; }
            @Override public Editor putLong(String k, long v) { puts.put(k, v); return this; }
            @Override public Editor putFloat(String k, float v) { puts.put(k, v); return this; }
            @Override public Editor putBoolean(String k, boolean v) { puts.put(k, v); return this; }
            @Override public Editor remove(String k) { removes.add(k); puts.remove(k); return this; }
            @Override public Editor clear() { clear = true; return this; }
            @Override public boolean commit() { apply(); return true; }
            @Override public void apply() {
                ArrayList<String> changed = new ArrayList<>();
                synchronized (Prefs.this) {
                    if (clear) { changed.addAll(map.keySet()); map.clear(); }
                    for (String k : removes) if (map.remove(k) != null) changed.add(k);
                    for (Map.Entry<String, Object> e : puts.entrySet()) { map.put(e.getKey(), e.getValue()); changed.add(e.getKey()); }
                    save();
                }
                if (!listeners.isEmpty()) {
                    Runnable notify = () -> { for (String k : changed) for (OnSharedPreferenceChangeListener l : listeners) l.onSharedPreferenceChanged(Prefs.this, k); };
                    if (android.os.MainQueue.isMain()) notify.run(); else android.os.MainQueue.post(Prefs.this, notify, 0, null);
                }
            }
        };
    }
}
