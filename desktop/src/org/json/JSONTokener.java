package org.json;

/** Lector de JSON con las mismas reglas que el de Android (también acepta comillas simples y comas sobrantes). */
public class JSONTokener {
    private final String in;
    private int pos;

    public JSONTokener(String in) {
        if (in != null && in.startsWith("﻿")) in = in.substring(1);
        this.in = in == null ? "" : in;
    }

    public Object nextValue() throws JSONException {
        int c = nextClean();
        switch (c) {
            case -1: throw syntaxError("End of input");
            case '{': return readObject();
            case '[': return readArray();
            case '\'':
            case '"': return nextString((char) c);
            default: pos--; return readLiteral();
        }
    }

    private int nextClean() throws JSONException {
        while (pos < in.length()) {
            int c = in.charAt(pos++);
            switch (c) {
                case '\t': case ' ': case '\n': case '\r': continue;
                case '/':
                    if (pos == in.length()) return c;
                    char peek = in.charAt(pos);
                    if (peek == '*') {
                        pos++;
                        int end = in.indexOf("*/", pos);
                        if (end == -1) throw syntaxError("Unterminated comment");
                        pos = end + 2;
                        continue;
                    } else if (peek == '/') {
                        pos++;
                        skipToEol();
                        continue;
                    }
                    return c;
                case '#': skipToEol(); continue;
                default: return c;
            }
        }
        return -1;
    }

    private void skipToEol() {
        for (; pos < in.length(); pos++) {
            char c = in.charAt(pos);
            if (c == '\r' || c == '\n') { pos++; break; }
        }
    }

    public String nextString(char quote) throws JSONException {
        StringBuilder b = null;
        int start = pos;
        while (pos < in.length()) {
            int c = in.charAt(pos++);
            if (c == quote) {
                if (b == null) return in.substring(start, pos - 1);
                b.append(in, start, pos - 1);
                return b.toString();
            }
            if (c == '\\') {
                if (pos == in.length()) throw syntaxError("Unterminated escape sequence");
                if (b == null) b = new StringBuilder();
                b.append(in, start, pos - 1);
                b.append(readEscape());
                start = pos;
            }
        }
        throw syntaxError("Unterminated string");
    }

    private char readEscape() throws JSONException {
        char e = in.charAt(pos++);
        switch (e) {
            case 'u':
                if (pos + 4 > in.length()) throw syntaxError("Unterminated escape sequence");
                String hex = in.substring(pos, pos + 4);
                pos += 4;
                try { return (char) Integer.parseInt(hex, 16); } catch (NumberFormatException x) { throw syntaxError("Invalid escape sequence: " + hex); }
            case 't': return '\t';
            case 'b': return '\b';
            case 'n': return '\n';
            case 'r': return '\r';
            case 'f': return '\f';
            default: return e;
        }
    }

    private Object readLiteral() throws JSONException {
        String literal = nextToInternal("{}[]/\\:,=;# \t\f");
        if (literal.isEmpty()) throw syntaxError("Expected literal value");
        if ("null".equalsIgnoreCase(literal)) return JSONObject.NULL;
        if ("true".equalsIgnoreCase(literal)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(literal)) return Boolean.FALSE;
        if (literal.indexOf('.') == -1 && literal.indexOf('e') == -1 && literal.indexOf('E') == -1) {
            try {
                long l = Long.parseLong(literal);
                if (l <= Integer.MAX_VALUE && l >= Integer.MIN_VALUE) return (int) l;
                return l;
            } catch (NumberFormatException ignored) {}
        }
        try { return Double.valueOf(literal); } catch (NumberFormatException ignored) {}
        return literal;
    }

    private String nextToInternal(String excluded) {
        int start = pos;
        for (; pos < in.length(); pos++) {
            char c = in.charAt(pos);
            if (c == '\r' || c == '\n' || excluded.indexOf(c) != -1) return in.substring(start, pos);
        }
        return in.substring(start);
    }

    private JSONObject readObject() throws JSONException {
        JSONObject result = new JSONObject();
        int first = nextClean();
        if (first == '}') return result;
        if (first != -1) pos--;
        while (true) {
            Object name = nextValue();
            if (!(name instanceof String)) {
                if (name == null) throw syntaxError("Names cannot be null");
                throw syntaxError("Names must be strings, but " + name + " is of type " + name.getClass().getName());
            }
            int sep = nextClean();
            if (sep != ':' && sep != '=') throw syntaxError("Expected ':' after " + name);
            if (pos < in.length() && in.charAt(pos) == '>') pos++;
            result.put((String) name, nextValue());
            switch (nextClean()) {
                case '}': return result;
                case ';': case ',': continue;
                default: throw syntaxError("Unterminated object");
            }
        }
    }

    private JSONArray readArray() throws JSONException {
        JSONArray result = new JSONArray();
        boolean hasTrailingSeparator = false;
        while (true) {
            switch (nextClean()) {
                case -1: throw syntaxError("Unterminated array");
                case ']':
                    if (hasTrailingSeparator) result.put(JSONObject.NULL);
                    return result;
                case ',': case ';':
                    result.put(JSONObject.NULL);
                    hasTrailingSeparator = true;
                    continue;
                default: pos--;
            }
            result.put(nextValue());
            switch (nextClean()) {
                case ']': return result;
                case ',': case ';': hasTrailingSeparator = true; continue;
                default: throw syntaxError("Unterminated array");
            }
        }
    }

    public JSONException syntaxError(String message) {
        return new JSONException(message + " at character " + pos);
    }

    public boolean more() { return pos < in.length(); }

    @Override
    public String toString() { return " at character " + pos + " of " + in; }
}
