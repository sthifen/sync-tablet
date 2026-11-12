package android.content;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ContentValues {
    private final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    public ContentValues() {}
    public ContentValues(ContentValues o) { map.putAll(o.map); }
    public void put(String k, String v) { map.put(k, v); }
    public void put(String k, Integer v) { map.put(k, v); }
    public void put(String k, Long v) { map.put(k, v); }
    public void put(String k, Boolean v) { map.put(k, v); }
    public void put(String k, Float v) { map.put(k, v); }
    public void put(String k, Double v) { map.put(k, v); }
    public void put(String k, byte[] v) { map.put(k, v); }
    public void putNull(String k) { map.put(k, null); }
    public Object get(String k) { return map.get(k); }
    public String getAsString(String k) { Object o = map.get(k); return o == null ? null : o.toString(); }
    public Integer getAsInteger(String k) { Object o = map.get(k); return o instanceof Number ? ((Number) o).intValue() : null; }
    public Long getAsLong(String k) { Object o = map.get(k); return o instanceof Number ? ((Number) o).longValue() : null; }
    public Boolean getAsBoolean(String k) { Object o = map.get(k); return o instanceof Boolean ? (Boolean) o : null; }
    public boolean containsKey(String k) { return map.containsKey(k); }
    public void remove(String k) { map.remove(k); }
    public void clear() { map.clear(); }
    public int size() { return map.size(); }
    public boolean isEmpty() { return map.isEmpty(); }
    public Set<String> keySet() { return map.keySet(); }
    public Set<Map.Entry<String, Object>> valueSet() { return map.entrySet(); }
}
