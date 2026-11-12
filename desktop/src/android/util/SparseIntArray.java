package android.util;

import java.util.TreeMap;

public class SparseIntArray {
    private final TreeMap<Integer, Integer> map = new TreeMap<>();
    public int get(int key) { return get(key, 0); }
    public int get(int key, int def) { Integer v = map.get(key); return v == null ? def : v; }
    public void put(int key, int v) { map.put(key, v); }
    public void delete(int key) { map.remove(key); }
    public int size() { return map.size(); }
    public void clear() { map.clear(); }
    public int keyAt(int i) { return (Integer) map.keySet().toArray()[i]; }
    public int valueAt(int i) { return (Integer) map.values().toArray()[i]; }
}
