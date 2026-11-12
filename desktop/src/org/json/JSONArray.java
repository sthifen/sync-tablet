package org.json;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** org.json.JSONArray con el comportamiento del de Android. */
public class JSONArray {
    private final List<Object> values;

    public JSONArray() { values = new ArrayList<>(); }

    @SuppressWarnings("rawtypes")
    public JSONArray(Collection copyFrom) {
        this();
        if (copyFrom != null) for (Object o : copyFrom) values.add(JSONObject.wrap(o));
    }

    public JSONArray(JSONTokener t) throws JSONException {
        Object o = t.nextValue();
        if (o instanceof JSONArray) values = ((JSONArray) o).values;
        else throw JSON.typeMismatch("input", o, "JSONArray");
    }

    public JSONArray(String json) throws JSONException { this(new JSONTokener(json)); }

    public JSONArray(Object array) throws JSONException {
        if (!array.getClass().isArray()) throw new JSONException("Not a primitive array: " + array.getClass());
        int length = Array.getLength(array);
        values = new ArrayList<>(length);
        for (int i = 0; i < length; i++) put(JSONObject.wrap(Array.get(array, i)));
    }

    public int length() { return values.size(); }

    public JSONArray put(boolean value) { values.add(value); return this; }
    public JSONArray put(double value) throws JSONException { values.add(JSON.checkDouble(value)); return this; }
    public JSONArray put(int value) { values.add(value); return this; }
    public JSONArray put(long value) { values.add(value); return this; }
    public JSONArray put(Object value) { values.add(value); return this; }

    void checkedPut(Object value) throws JSONException {
        if (value instanceof Number) JSON.checkDouble(((Number) value).doubleValue());
        put(value);
    }

    public JSONArray put(int index, boolean value) throws JSONException { return put(index, (Object) value); }
    public JSONArray put(int index, double value) throws JSONException { return put(index, (Object) value); }
    public JSONArray put(int index, int value) throws JSONException { return put(index, (Object) value); }
    public JSONArray put(int index, long value) throws JSONException { return put(index, (Object) value); }

    public JSONArray put(int index, Object value) throws JSONException {
        if (value instanceof Number) JSON.checkDouble(((Number) value).doubleValue());
        if (index < 0) throw new JSONException("Index " + index + " out of range");
        while (values.size() <= index) values.add(null);
        values.set(index, value);
        return this;
    }

    public boolean isNull(int index) { Object v = opt(index); return v == null || v == JSONObject.NULL; }

    public Object get(int index) throws JSONException {
        try {
            Object value = values.get(index);
            if (value == null) throw new JSONException("Value at " + index + " is null.");
            return value;
        } catch (IndexOutOfBoundsException e) {
            throw new JSONException("Index " + index + " out of range [0.." + values.size() + ")");
        }
    }

    public Object opt(int index) {
        if (index < 0 || index >= values.size()) return null;
        return values.get(index);
    }

    public Object remove(int index) {
        if (index < 0 || index >= values.size()) return null;
        return values.remove(index);
    }

    public boolean getBoolean(int index) throws JSONException {
        Object o = get(index);
        Boolean r = JSON.toBoolean(o);
        if (r == null) throw JSON.typeMismatch(index, o, "boolean");
        return r;
    }
    public boolean optBoolean(int index) { return optBoolean(index, false); }
    public boolean optBoolean(int index, boolean fallback) { Boolean r = JSON.toBoolean(opt(index)); return r != null ? r : fallback; }

    public double getDouble(int index) throws JSONException {
        Object o = get(index);
        Double r = JSON.toDouble(o);
        if (r == null) throw JSON.typeMismatch(index, o, "double");
        return r;
    }
    public double optDouble(int index) { return optDouble(index, Double.NaN); }
    public double optDouble(int index, double fallback) { Double r = JSON.toDouble(opt(index)); return r != null ? r : fallback; }

    public int getInt(int index) throws JSONException {
        Object o = get(index);
        Integer r = JSON.toInteger(o);
        if (r == null) throw JSON.typeMismatch(index, o, "int");
        return r;
    }
    public int optInt(int index) { return optInt(index, 0); }
    public int optInt(int index, int fallback) { Integer r = JSON.toInteger(opt(index)); return r != null ? r : fallback; }

    public long getLong(int index) throws JSONException {
        Object o = get(index);
        Long r = JSON.toLong(o);
        if (r == null) throw JSON.typeMismatch(index, o, "long");
        return r;
    }
    public long optLong(int index) { return optLong(index, 0L); }
    public long optLong(int index, long fallback) { Long r = JSON.toLong(opt(index)); return r != null ? r : fallback; }

    public String getString(int index) throws JSONException {
        Object o = get(index);
        String r = JSON.toString(o);
        if (r == null) throw JSON.typeMismatch(index, o, "String");
        return r;
    }
    public String optString(int index) { return optString(index, ""); }
    public String optString(int index, String fallback) { String r = JSON.toString(opt(index)); return r != null ? r : fallback; }

    public JSONArray getJSONArray(int index) throws JSONException {
        Object o = get(index);
        if (o instanceof JSONArray) return (JSONArray) o;
        throw JSON.typeMismatch(index, o, "JSONArray");
    }
    public JSONArray optJSONArray(int index) { Object o = opt(index); return o instanceof JSONArray ? (JSONArray) o : null; }

    public JSONObject getJSONObject(int index) throws JSONException {
        Object o = get(index);
        if (o instanceof JSONObject) return (JSONObject) o;
        throw JSON.typeMismatch(index, o, "JSONObject");
    }
    public JSONObject optJSONObject(int index) { Object o = opt(index); return o instanceof JSONObject ? (JSONObject) o : null; }

    public JSONObject toJSONObject(JSONArray names) throws JSONException {
        JSONObject result = new JSONObject();
        int length = Math.min(names.length(), values.size());
        if (length == 0) return null;
        for (int i = 0; i < length; i++) result.put(JSON.toString(names.opt(i)), opt(i));
        return result;
    }

    public String join(String separator) throws JSONException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(separator);
            JSON.write(sb, values.get(i), 0, 0);
        }
        return sb.toString();
    }

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
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            JSON.newline(sb, indent, level + 1);
            JSON.write(sb, values.get(i), indent, level + 1);
        }
        if (!values.isEmpty()) JSON.newline(sb, indent, level);
        sb.append(']');
    }

    @Override
    public boolean equals(Object o) { return o instanceof JSONArray && ((JSONArray) o).values.equals(values); }

    @Override
    public int hashCode() { return values.hashCode(); }
}
