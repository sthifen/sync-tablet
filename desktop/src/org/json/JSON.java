package org.json;

/** Conversiones de tipos como las de org.json de Android. */
final class JSON {
    static double checkDouble(double d) throws JSONException {
        if (Double.isInfinite(d) || Double.isNaN(d)) throw new JSONException("Forbidden numeric value: " + d);
        return d;
    }

    static Boolean toBoolean(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) {
            String s = (String) v;
            if ("true".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s)) return false;
        }
        return null;
    }

    static Double toDouble(Object v) {
        if (v instanceof Double) return (Double) v;
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.valueOf((String) v); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    static Integer toInteger(Object v) {
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return (int) Double.parseDouble((String) v); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    static Long toLong(Object v) {
        if (v instanceof Long) return (Long) v;
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return (long) Double.parseDouble((String) v); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    static String toString(Object v) {
        if (v instanceof String) return (String) v;
        if (v != null) return String.valueOf(v);
        return null;
    }

    static JSONException typeMismatch(Object where, Object actual, String req) {
        if (actual == null) return new JSONException("Value at " + where + " is null.");
        return new JSONException("Value " + actual + " at " + where + " of type " + actual.getClass().getName()
                + " cannot be converted to " + req);
    }

    static String numberToString(Number n) {
        if (n == null) return "null";
        double d = n.doubleValue();
        if (n instanceof Double || n instanceof Float) {
            if (d == (long) d && Math.abs(d) < 1e15) return Long.toString((long) d);
            return Double.toString(d);
        }
        return n.toString();
    }

    static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c == ' ' || c == ' ') sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    static void write(StringBuilder sb, Object v, int indent, int level) {
        if (v == null || v == JSONObject.NULL) sb.append("null");
        else if (v instanceof JSONObject) ((JSONObject) v).write(sb, indent, level);
        else if (v instanceof JSONArray) ((JSONArray) v).write(sb, indent, level);
        else if (v instanceof String) quote(sb, (String) v);
        else if (v instanceof Number) sb.append(numberToString((Number) v));
        else if (v instanceof Boolean) sb.append(v);
        else quote(sb, v.toString());
    }

    static void newline(StringBuilder sb, int indent, int level) {
        if (indent <= 0) return;
        sb.append('\n');
        for (int i = 0; i < indent * level; i++) sb.append(' ');
    }

    private JSON() {}
}
