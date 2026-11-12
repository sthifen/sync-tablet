package android.util;

import java.util.TreeMap;

public class SparseArray<E> {
    private final TreeMap<Integer, E> map = new TreeMap<>();
    public SparseArray() {}
    public SparseArray(int cap) {}
    public E get(int key) { return map.get(key); }
    public E get(int key, E def) { E v = map.get(key); return v == null ? def : v; }
    public void put(int key, E v) { map.put(key, v); }
    public void append(int key, E v) { map.put(key, v); }
    public void remove(int key) { map.remove(key); }
    public void delete(int key) { map.remove(key); }
    public int size() { return map.size(); }
    public void clear() { map.clear(); }
    public int keyAt(int i) { return (Integer) map.keySet().toArray()[i]; }
    @SuppressWarnings("unchecked") public E valueAt(int i) { return (E) map.values().toArray()[i]; }
    public int indexOfKey(int key) { int i = 0; for (Integer k : map.keySet()) { if (k == key) return i; i++; } return -1; }
    public boolean contains(int key) { return map.containsKey(key); }
}
