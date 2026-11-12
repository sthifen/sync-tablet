package android.util;

import java.util.LinkedHashMap;
import java.util.Map;

public class LruCache<K, V> {
    private final LinkedHashMap<K, V> map;
    private int size, maxSize;

    public LruCache(int maxSize) {
        if (maxSize <= 0) throw new IllegalArgumentException("maxSize <= 0");
        this.maxSize = maxSize;
        this.map = new LinkedHashMap<>(0, 0.75f, true);
    }

    public final synchronized V get(K key) {
        V v = map.get(key);
        if (v != null) return v;
        V created = create(key);
        if (created == null) return null;
        map.put(key, created);
        size += safeSizeOf(key, created);
        trimToSize(maxSize);
        return created;
    }

    public final synchronized V put(K key, V value) {
        size += safeSizeOf(key, value);
        V previous = map.put(key, value);
        if (previous != null) { size -= safeSizeOf(key, previous); entryRemoved(false, key, previous, value); }
        trimToSize(maxSize);
        return previous;
    }

    public synchronized void trimToSize(int max) {
        while (size > max && !map.isEmpty()) {
            Map.Entry<K, V> e = map.entrySet().iterator().next();
            map.remove(e.getKey());
            size -= safeSizeOf(e.getKey(), e.getValue());
            entryRemoved(true, e.getKey(), e.getValue(), null);
        }
    }

    public final synchronized V remove(K key) {
        V previous = map.remove(key);
        if (previous != null) { size -= safeSizeOf(key, previous); entryRemoved(false, key, previous, null); }
        return previous;
    }

    protected void entryRemoved(boolean evicted, K key, V oldValue, V newValue) {}
    protected V create(K key) { return null; }
    private int safeSizeOf(K key, V value) { return sizeOf(key, value); }
    protected int sizeOf(K key, V value) { return 1; }
    public final synchronized void evictAll() { trimToSize(-1); }
    public final synchronized int size() { return size; }
    public final synchronized int maxSize() { return maxSize; }
    public void resize(int maxSize) { synchronized (this) { this.maxSize = maxSize; } trimToSize(maxSize); }
    public final synchronized Map<K, V> snapshot() { return new LinkedHashMap<>(map); }
}
