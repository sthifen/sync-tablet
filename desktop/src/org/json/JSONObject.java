package org.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** org.json.JSONObject con el comportamiento del de Android (orden de inserción, conversiones, opt*). */
public class JSONObject {
    public static final Object NULL = new Object() {
        @Override public boolean equals(Object o) { return o == this || o == null; }
        @Override public int hashCode() { return 0; }
        @Override public String toString() { return "null"; }
    };

    private final LinkedHashMap<String, Object> map;

    public JSONObject() { map = new LinkedHashMap<>(); }

    @SuppressWarnings("rawtypes")
    public JSONObject(Map copyFrom) {
        this();
        for (Object o : copyFrom.entrySet()) {
            Map.Entry e = (Map.Entry) o;
            String key = (String) e.getKey();
            if (key == null) throw new NullPointerException("key == null");
            map.put(key, wrap(e.getValue()));
        }
    }

    public JSONObject(JSONTokener t) throws JSONException {
        Object o = t.nextValue();
        if (o instanceof JSONObject) map = ((JSONObject) o).map;
        else throw JSON.typeMismatch("input", o, "JSONObject");
    }

    public JSONObject(String json) throws JSONException { this(new JSONTokener(json)); }

    public JSONObject(JSONObject copyFrom, String[] names) throws JSONException {
        this();
        for (String n : names) {
            Object v = copyFrom.opt(n);
            if (v != null) map.put(n, v);
        }
    }

    public int length() { return map.size(); }

    public JSONObject put(String name, boolean value) throws JSONException { map.put(checkName(name), value); return this; }
    public JSONObject put(String name, double value) throws JSONException { map.put(checkName(name), JSON.checkDouble(value)); return this; }
    public JSONObject put(String name, int value) throws JSONException { map.put(checkName(name), value); return this; }
    public JSONObject put(String name, long value) throws JSONException { map.put(checkName(name), value); return this; }

    public JSONObject put(String name, Object value) throws JSONException {
        if (value == null) { map.remove(name); return this; }
        if (value instanceof Number) JSON.checkDouble(((Number) value).doubleValue());
        map.put(checkName(name), value);
        return this;
    }

    public JSONObject putOpt(String name, Object value) throws JSONException {
        if (name == null || value == null) return this;
        return put(name, value);
    }

    public JSONObject accumulate(String name, Object value) throws JSONException {
        Object current = map.get(checkName(name));
        if (current == null) return put(name, value);
        if (current instanceof JSONArray) {
            ((JSONArray) current).checkedPut(value);
        } else {
            JSONArray array = new JSONArray();
            array.checkedPut(current);
            array.checkedPut(value);
            map.put(name, array);
        }
        return this;
    }

    public JSONObject append(String name, Object value) throws JSONException {
        Object current = map.get(checkName(name));
        JSONArray array;
        if (current instanceof JSONArray) array = (JSONArray) current;
        else if (current == null) { array = new JSONArray(); map.put(name, array); }
        else throw new JSONException("Key " + name + " is not a JSONArray");
        array.checkedPut(value);
        return this;
    }

    String checkName(String name) throws JSONException {
        if (name == null) throw new JSONException("Names must be non-null");
        return name;
    }

    public Object remove(String name) { return map.remove(name); }
    public boolean isNull(String name) { Object v = map.get(name); return v == null || v == NULL; }
    public boolean has(String name) { return map.containsKey(name); }

    public Object get(String name) throws JSONException {
        Object result = map.get(name);
        if (result == null) throw new JSONException("No value for " + name);
        return result;
    }

    public Object opt(String name) { return name == null ? null : map.get(name); }

    public boolean getBoolean(String name) throws JSONException {
        Object o = get(name);
        Boolean r = JSON.toBoolean(o);
        if (r == null) throw JSON.typeMismatch(name, o, "boolean");
        return r;
    }
    public boolean optBoolean(String name) { return optBoolean(name, false); }
    public boolean optBoolean(String name, boolean fallback) { Boolean r = JSON.toBoolean(opt(name)); return r != null ? r : fallback; }

    public double getDouble(String name) throws JSONException {
        Object o = get(name);
        Double r = JSON.toDouble(o);
        if (r == null) throw JSON.typeMismatch(name, o, "double");
        return r;
    }
    public double optDouble(String name) { return optDouble(name, Double.NaN); }
    public double optDouble(String name, double fallback) { Double r = JSON.toDouble(opt(name)); return r != null ? r : fallback; }

    public int getInt(String name) throws JSONException {
        Object o = get(name);
        Integer r = JSON.toInteger(o);
        if (r == null) throw JSON.typeMismatch(name, o, "int");
        return r;
    }
    public int optInt(String name) { return optInt(name, 0); }
    public int optInt(String name, int fallback) { Integer r = JSON.toInteger(opt(name)); return r != null ? r : fallback; }

    public long getLong(String name) throws JSONException {
        Object o = get(name);
        Long r = JSON.toLong(o);
        if (r == null) throw JSON.typeMismatch(name, o, "long");
        return r;
    }
    public long optLong(String name) { return optLong(name, 0L); }
    public long optLong(String name, long fallback) { Long r = JSON.toLong(opt(name)); return r != null ? r : fallback; }

    public String getString(String name) throws JSONException {
        Object o = get(name);
        String r = JSON.toString(o);
        if (r == null) throw JSON.typeMismatch(name, o, "String");
        return r;
    }
    public String optString(String name) { return optString(name, ""); }
    public String optString(String name, String fallback) { String r = JSON.toString(opt(name)); return r != null ? r : fallback; }

    public JSONArray getJSONArray(String name) throws JSONException {
        Object o = get(name);
        if (o instanceof JSONArray) return (JSONArray) o;
        throw JSON.typeMismatch(name, o, "JSONArray");
    }
    public JSONArray optJSONArray(String name) { Object o = opt(name); return o instanceof JSONArray ? (JSONArray) o : null; }

    public JSONObject getJSONObject(String name) throws JSONException {
        Object o = get(name);
        if (o instanceof JSONObject) return (JSONObject) o;
        throw JSON.typeMismatch(name, o, "JSONObject");
    }
    public JSONObject optJSONObject(String name) { Object o = opt(name); return o instanceof JSONObject ? (JSONObject) o : null; }

    public JSONArray toJSONArray(JSONArray names) throws JSONException {
        JSONArray result = new JSONArray();
        if (names == null) return null;
        int length = names.length();
        if (length == 0) return null;
        for (int i = 0; i < length; i++) result.put(opt(JSON.toString(names.opt(i))));
        return result;
    }

    public Iterator<String> keys() { return new ArrayList<>(map.keySet()).iterator(); }
    public Set<String> keySet() { return map.keySet(); }

    public JSONArray names() { return map.isEmpty() ? null : new JSONArray(new ArrayList<>(map.keySet())); }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        write(sb, 0, 0);
        return sb.toString();
    }

    public String toString(int indentSpaces) throws JSONException {
        StringBuilder sb = new StringBuilder();
        write(sb, indentSpaces, 0);
        return sb.toString();
    }

    void write(StringBuilder sb, int indent, int level) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            JSON.newline(sb, indent, level + 1);
            JSON.quote(sb, e.getKey());
            sb.append(indent > 0 ? ": " : ":");
            JSON.write(sb, e.getValue(), indent, level + 1);
        }
        if (!first) JSON.newline(sb, indent, level);
        sb.append('}');
    }

    public static String numberToString(Number number) throws JSONException {
        if (number == null) throw new JSONException("Number must be non-null");
        JSON.checkDouble(number.doubleValue());
        return JSON.numberToString(number);
    }

    public static String quote(String data) {
        if (data == null) return "\"\"";
        StringBuilder sb = new StringBuilder();
        JSON.quote(sb, data);
        return sb.toString();
    }

    @SuppressWarnings("rawtypes")
    public static Object wrap(Object o) {
        if (o == null) return NULL;
        if (o instanceof JSONArray || o instanceof JSONObject) return o;
        if (o.equals(NULL)) return o;
        try {
            if (o instanceof Collection) return new JSONArray((Collection) o);
            if (o.getClass().isArray()) return new JSONArray(o);
            if (o instanceof Map) return new JSONObject((Map) o);
            if (o instanceof Boolean || o instanceof Byte || o instanceof Character || o instanceof Double || o instanceof Float
                    || o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof String) return o;
            if (o.getClass().getPackage() != null && o.getClass().getPackage().getName().startsWith("java.")) return o.toString();
        } catch (Exception ignored) {}
        return null;
    }
}
