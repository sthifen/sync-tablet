package dev.synctablet;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Cuadernos, páginas y trazos, con el mismo JSON que el PC (src/ink.rs).
 * Las páginas miden 1000×1414 unidades (A4); los trazos guardan x, y y
 * presión seguidos.
 */
final class Ink {
    static final float W = 1000f, H = 1414f;
    static final String PEN = "pen", PENCIL = "pencil", BRUSH = "brush", CALLIGRAPHY = "calligraphy",
            HIGHLIGHTER = "highlighter", TAPE = "tape";
    static final String PLAIN = "plain", LINES = "lines", GRID = "grid", DOTS = "dots", CORNELL = "cornell",
            MUSIC = "music", TODO = "todo", HALF = "half", HALF_GRID = "halfgrid", HALF_H = "halfh", MM = "mm", ISO = "iso",
            ENGINEERING = "engineering", HEX = "hex", WIDE = "wide", WEEK = "week", STORY = "story", HANDWRITING = "handwriting";
    static final String MARKER = "marker", FOUNTAIN = "fountain";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    static String newId() {
        char[] c = new char[12];
        for (int i = 0; i < c.length; i++) c[i] = ID_CHARS[RANDOM.nextInt(ID_CHARS.length)];
        return new String(c);
    }

    /** #RRGGBB, o #RRGGBBAA si no es opaco (lápices propios con opacidad; CSS y SVG lo entienden). */
    static String hex(int color) {
        int a = color >>> 24;
        if (a == 0xFF || a == 0) return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
        return String.format(Locale.ROOT, "#%06X%02X", color & 0xFFFFFF, a);
    }

    static int parseColor(String s, int fallback) {
        try {
            if (s.length() == 9) {
                long v = Long.parseLong(s.substring(1), 16);
                return (int) ((v & 0xFF) << 24 | (v >>> 8));
            }
            return 0xFF000000 | Integer.parseInt(s.substring(1), 16);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Grosor de un tramo con presión p (igual que el PC). */
    static float pressureWidth(float width, float p) {
        return width * (0.35f + 0.65f * Math.max(0f, Math.min(1f, p)));
    }

    /**
     * Grosor de un tramo según el lápiz, la presión y la dirección (dx, dy);
     * las mismas fórmulas que el PC (svg.rs) y el visor web.
     */
    static float segWidth(String tool, float w, float p, float dx, float dy) {
        p = Math.max(0f, Math.min(1f, p));
        switch (tool) {
            case PENCIL: return w * 0.7f * (0.5f + 0.5f * p);
            case BRUSH: return w * (0.1f + 1.7f * p);
            case CALLIGRAPHY:
                double a = Math.atan2(dy, dx) - Math.PI / 4;
                return w * (0.25f + 0.95f * (float) Math.abs(Math.sin(a))) * (0.6f + 0.4f * p);
            case HIGHLIGHTER: case TAPE: case MARKER: return w;
            case FOUNTAIN: return w * (0.55f + 0.75f * p * p);
            default: return pressureWidth(w, p);
        }
    }

    /** Opacidad (0–255) de un tramo: el lápiz es más claro si se aprieta poco. */
    static int segAlpha(String tool, float p) {
        if (!PENCIL.equals(tool)) return 255;
        return Math.round(255 * 0.75f * (0.4f + 0.6f * Math.max(0f, Math.min(1f, p))));
    }

    /** Imagen o cuadro de texto sobre la hoja. */
    static final class Item {
        final String id;
        final String kind;
        String asset, text;
        float x, y, w, h, size = 28;
        int color = 0xFF111827;
        /** Hoja de cálculo: lo escrito y lo calculado, por filas. */
        int rows, cols;
        String[] cells = new String[0], values = new String[0];

        Item(String id, String kind) {
            this.id = id;
            this.kind = kind;
        }

        boolean isImage() { return "image".equals(kind); }

        boolean isTable() { return "table".equals(kind); }

        /** Recalcula las fórmulas. */
        void evaluate() {
            if (cells.length != rows * cols) cells = java.util.Arrays.copyOf(cells, rows * cols);
            for (int i = 0; i < cells.length; i++) if (cells[i] == null) cells[i] = "";
            values = Sheet.evaluate(cells, rows, cols);
        }

        Item copy(String newId) {
            Item c = new Item(newId, kind);
            c.asset = asset; c.text = text; c.x = x; c.y = y; c.w = w; c.h = h; c.size = size; c.color = color;
            c.rows = rows; c.cols = cols; c.cells = cells.clone(); c.values = values.clone();
            return c;
        }

        JSONObject toJsonObject() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("kind", kind);
            o.put("x", x);
            o.put("y", y);
            o.put("w", w);
            if (isImage()) {
                o.put("asset", asset);
                o.put("h", h);
            } else if (isTable()) {
                o.put("h", h);
                o.put("rows", rows);
                o.put("cols", cols);
                o.put("cells", new JSONArray(java.util.Arrays.asList(cells)));
                o.put("values", new JSONArray(java.util.Arrays.asList(values)));
            } else {
                o.put("text", text == null ? "" : text);
                o.put("size", size);
                o.put("color", hex(color));
            }
            return o;
        }

        static Item fromJson(JSONObject o) throws JSONException {
            Item i = new Item(o.getString("id"), o.optString("kind", "text"));
            i.asset = o.optString("asset", null);
            i.text = o.optString("text", "");
            i.x = (float) o.optDouble("x"); i.y = (float) o.optDouble("y");
            i.w = (float) o.optDouble("w", 300); i.h = (float) o.optDouble("h", 200);
            i.size = (float) o.optDouble("size", 28);
            i.color = parseColor(o.optString("color", "#111827"), 0xFF111827);
            i.rows = o.optInt("rows");
            i.cols = o.optInt("cols");
            JSONArray c = o.optJSONArray("cells"), v = o.optJSONArray("values");
            if (c != null) {
                i.cells = new String[c.length()];
                for (int k = 0; k < c.length(); k++) i.cells[k] = c.optString(k);
            }
            if (v != null) {
                i.values = new String[v.length()];
                for (int k = 0; k < v.length(); k++) i.values[k] = v.optString(k);
            }
            return i;
        }
    }

    static final class Stroke {
        final String id;
        final String tool;
        final int color;
        final float width;
        float[] pts = new float[3 * 64];
        int n;
        /** Si es una figura editable (ShapeTools). */
        ShapeTools.Spec shape;
        /** Caja que lo contiene, para la goma. */
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

        /** Copia con otro id y/o color (para duplicar y recolorear). */
        Stroke copy(String newId, int newColor) {
            Stroke s = new Stroke(newId, tool, newColor, width);
            for (int i = 0; i < n; i++) s.add(x(i), y(i), p(i));
            s.shape = shape;
            return s;
        }

        /** Copia movida y escalada: (x, y) → (ax + (x − ax)·k + dx, …). */
        Stroke transformed(float ax, float ay, float k, float dx, float dy) {
            return mapped(new float[] {k, 0, 0, k, ax - ax * k + dx, ay - ay * k + dy}, k, 0);
        }

        /** La misma línea hecha de nuevo con esta figura. */
        Stroke withShape(ShapeTools.Spec spec) {
            Stroke s = new Stroke(id, tool, color, width);
            s.replace(spec.points(), n > 0 ? p(0) : 0.7f);
            s.shape = spec;
            return s;
        }

        /** Copia con la transformación afín {a, b, c, d, e, f} y el grosor multiplicado. */
        Stroke mapped(float[] m, float widthScale, float turn) {
            Stroke s = new Stroke(id, tool, color, width * widthScale);
            // Una figura se rehace: así una punta de flecha no se deforma.
            ShapeTools.Spec spec = shape == null ? null : shape.mapped(m, turn);
            if (spec != null) {
                s.replace(spec.points(), n > 0 ? p(0) : 0.7f);
                s.shape = spec;
                return s;
            }
            for (int i = 0; i < n; i++) {
                s.add(m[0] * x(i) + m[2] * y(i) + m[4], m[1] * x(i) + m[3] * y(i) + m[5], p(i));
            }
            return s;
        }

        Stroke(String id, String tool, int color, float width) {
            this.id = id;
            this.tool = tool;
            this.color = color;
            this.width = width;
        }

        void add(float x, float y, float p) {
            if (3 * n + 3 > pts.length) pts = java.util.Arrays.copyOf(pts, pts.length * 2);
            pts[3 * n] = x;
            pts[3 * n + 1] = y;
            pts[3 * n + 2] = p;
            n++;
            minX = Math.min(minX, x); minY = Math.min(minY, y);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
        }

        /** Cambia los puntos por otros (una figura perfecta), con presión fija. */
        void replace(float[] xy, float p) {
            n = 0;
            minX = minY = Float.MAX_VALUE;
            maxX = maxY = -Float.MAX_VALUE;
            boolean up = false;
            for (int i = 0; i + 1 < xy.length; i += 2) {
                if (Float.isNaN(xy[i])) {
                    up = true;
                    continue;
                }
                // Presión negativa: aquí se levantó el lápiz.
                add(xy[i], xy[i + 1], up ? -1 : p);
                up = false;
            }
        }

        float x(int i) { return pts[3 * i]; }
        float y(int i) { return pts[3 * i + 1]; }
        float p(int i) { return pts[3 * i + 2]; }

        /** ¿Pasa a menos de r del punto (x, y)? */
        boolean hits(float x, float y, float r) {
            float reach = r + width;
            if (x < minX - reach || x > maxX + reach || y < minY - reach || y > maxY + reach) return false;
            for (int i = 0; i < n; i++) {
                float ax = x(i), ay = y(i);
                float bx = i + 1 < n ? x(i + 1) : ax, by = i + 1 < n ? y(i + 1) : ay;
                if (segmentDistance(x, y, ax, ay, bx, by) <= r + width / 2) return true;
            }
            return false;
        }

        String toJson() {
            StringBuilder b = new StringBuilder(32 + n * 18);
            b.append("{\"id\":\"").append(id).append("\",\"tool\":\"").append(tool)
                    .append("\",\"color\":\"").append(hex(color)).append("\",\"width\":").append(round(width, 10))
                    .append(",\"points\":[");
            for (int i = 0; i < n; i++) {
                if (i > 0) b.append(',');
                b.append(round(x(i), 10)).append(',').append(round(y(i), 10)).append(',').append(round(p(i), 100));
            }
            b.append(']');
            if (shape != null) {
                b.append(",\"shape\":{\"v\":1,\"kind\":\"").append(shape.name()).append("\",\"c\":[").append(round(shape.x0, 100)).append(',')
                        .append(round(shape.y0, 100)).append(',').append(round(shape.x1, 100)).append(',').append(round(shape.y1, 100))
                        .append("],\"a\":").append(round(shape.angle, 10000));
                if (shape.label != null) b.append(",\"label\":\"").append(shape.label).append('"');
                if (shape.mirror) b.append(",\"m\":true");
                b.append('}');
            }
            return b.append('}').toString();
        }

        static Stroke fromJson(JSONObject o) throws JSONException {
            Stroke s = new Stroke(o.getString("id"), o.optString("tool", PEN),
                    parseColor(o.optString("color", "#000000"), 0xFF000000), (float) o.optDouble("width", 3));
            JSONArray p = o.getJSONArray("points");
            for (int i = 0; i + 2 < p.length(); i += 3) {
                s.add((float) p.getDouble(i), (float) p.getDouble(i + 1), (float) p.getDouble(i + 2));
            }
            JSONObject f = o.optJSONObject("shape");
            JSONArray c = f == null ? null : f.optJSONArray("c");
            int kind = f == null ? -1 : ShapeTools.Spec.kindOf(f.optString("kind"));
            // Un tipo que esta versión no conoce: queda como dibujo libre.
            if (kind >= 0 && c != null && c.length() == 4) {
                s.shape = new ShapeTools.Spec(kind, (float) c.getDouble(0), (float) c.getDouble(1), (float) c.getDouble(2), (float) c.getDouble(3),
                        (float) f.optDouble("a", 0), f.has("label") ? f.getString("label") : null, f.optBoolean("m"));
            }
            return s;
        }
    }

    /** Una nota adhesiva: es de la app, nunca sale al exportar. Puede quedar fuera de la hoja. */
    /**
     * Lo que el lápiz neurona rodeó en una página: una zona con sus palabras
     * clave y sus enlaces a otras marcas. Como las notas, nunca se exporta.
     */
    static final class Mark {
        final String id;
        float x, y, w, h;
        final ArrayList<String> words = new ArrayList<>();
        final ArrayList<String> links = new ArrayList<>();

        Mark(String id) {
            this.id = id;
        }

        boolean contains(float px, float py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }

        JSONObject toJsonObject() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("x", x);
            o.put("y", y);
            o.put("w", w);
            o.put("h", h);
            o.put("words", new JSONArray(words));
            o.put("links", new JSONArray(links));
            return o;
        }

        static Mark fromJson(JSONObject o) throws JSONException {
            Mark m = new Mark(o.getString("id"));
            m.x = (float) o.optDouble("x");
            m.y = (float) o.optDouble("y");
            m.w = (float) o.optDouble("w");
            m.h = (float) o.optDouble("h");
            JSONArray a = o.optJSONArray("words");
            if (a != null) for (int i = 0; i < a.length(); i++) m.words.add(a.getString(i));
            a = o.optJSONArray("links");
            if (a != null) for (int i = 0; i < a.length(); i++) m.links.add(a.getString(i));
            return m;
        }
    }

    static final class Note {
        final String id;
        String text = "";
        float x, y, w = 240;
        int color = NOTE_COLORS[0];
        /** La punta de su flecha, si tiene. */
        boolean arrow;
        float ax, ay;

        Note(String id) {
            this.id = id;
        }

        JSONObject toJsonObject() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("text", text);
            o.put("x", x);
            o.put("y", y);
            o.put("w", w);
            o.put("color", color);
            if (arrow) {
                o.put("ax", ax);
                o.put("ay", ay);
            }
            return o;
        }

        static Note fromJson(JSONObject o) throws JSONException {
            Note n = new Note(o.getString("id"));
            n.text = o.optString("text");
            n.x = (float) o.optDouble("x");
            n.y = (float) o.optDouble("y");
            n.w = (float) o.optDouble("w", 240);
            n.color = o.optInt("color", NOTE_COLORS[0]);
            n.arrow = o.has("ax");
            n.ax = (float) o.optDouble("ax");
            n.ay = (float) o.optDouble("ay");
            return n;
        }
    }

    static final int[] NOTE_COLORS = {0xFFFDE68A, 0xFFFBCFE8, 0xFFBFDBFE, 0xFFBBF7D0, 0xFFFED7AA, 0xFFDDD6FE};

    static final class Page {
        final String id;
        final String notebook;
        long version;
        String background = LINES;
        /** Página de un PDF importado como fondo (id de la imagen). */
        String bgAsset;
        /** Texto de esa página del PDF o diapositiva. */
        String bgText;
        final ArrayList<Stroke> strokes = new ArrayList<>();
        final ArrayList<Item> items = new ArrayList<>();
        final ArrayList<Note> notes = new ArrayList<>();
        final ArrayList<Mark> marks = new ArrayList<>();

        /** Toma el contenido de otra (para deshacer volviendo a una copia). */
        void copyFrom(Page o) {
            background = o.background;
            bgAsset = o.bgAsset;
            bgText = o.bgText;
            strokes.clear();
            strokes.addAll(o.strokes);
            items.clear();
            items.addAll(o.items);
            notes.clear();
            notes.addAll(o.notes);
            marks.clear();
            marks.addAll(o.marks);
        }

        Page(String id, String notebook) {
            this.id = id;
            this.notebook = notebook;
        }

        String toJson() {
            StringBuilder b = new StringBuilder();
            b.append("{\"id\":\"").append(id).append("\",\"notebook\":\"").append(notebook)
                    .append("\",\"version\":").append(version).append(",\"background\":\"").append(background)
                    .append("\",\"strokes\":[");
            for (int i = 0; i < strokes.size(); i++) {
                if (i > 0) b.append(',');
                b.append(strokes.get(i).toJson());
            }
            b.append("],\"items\":[");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) b.append(',');
                try {
                    b.append(items.get(i).toJsonObject());
                } catch (JSONException ignored) {
                }
            }
            b.append(']');
            if (bgAsset != null) b.append(",\"bg_asset\":\"").append(bgAsset).append('"');
            if (bgText != null) b.append(",\"bg_text\":").append(JSONObject.quote(bgText));
            if (!notes.isEmpty()) {
                b.append(",\"notes\":[");
                for (int i = 0; i < notes.size(); i++) {
                    if (i > 0) b.append(',');
                    try {
                        b.append(notes.get(i).toJsonObject());
                    } catch (JSONException ignored) {
                    }
                }
                b.append(']');
            }
            if (!marks.isEmpty()) {
                b.append(",\"marks\":[");
                for (int i = 0; i < marks.size(); i++) {
                    if (i > 0) b.append(',');
                    try {
                        b.append(marks.get(i).toJsonObject());
                    } catch (JSONException ignored) {
                    }
                }
                b.append(']');
            }
            return b.append('}').toString();
        }

        static Page fromJson(String json) throws JSONException {
            JSONObject o = new JSONObject(json);
            Page p = new Page(o.getString("id"), o.getString("notebook"));
            p.version = o.optLong("version");
            p.background = o.optString("background", LINES);
            JSONArray s = o.optJSONArray("strokes");
            if (s != null) for (int i = 0; i < s.length(); i++) p.strokes.add(Stroke.fromJson(s.getJSONObject(i)));
            JSONArray it = o.optJSONArray("items");
            if (it != null) for (int i = 0; i < it.length(); i++) p.items.add(Item.fromJson(it.getJSONObject(i)));
            if (o.has("bg_asset") && !o.isNull("bg_asset")) p.bgAsset = o.getString("bg_asset");
            if (o.has("bg_text") && !o.isNull("bg_text")) p.bgText = o.getString("bg_text");
            JSONArray ns = o.optJSONArray("notes");
            if (ns != null) for (int i = 0; i < ns.length(); i++) p.notes.add(Note.fromJson(ns.getJSONObject(i)));
            JSONArray ms = o.optJSONArray("marks");
            if (ms != null) for (int i = 0; i < ms.length(); i++) p.marks.add(Mark.fromJson(ms.getJSONObject(i)));
            return p;
        }
    }

    static final class Notebook {
        final String id;
        String title;
        int color;
        String folder = "";
        boolean favorite;
        /** Última vez que se escribió en él (ms), para «Recientes». */
        long updated;
        final ArrayList<String> pages = new ArrayList<>();

        Notebook(String id, String title, int color) {
            this.id = id;
            this.title = title;
            this.color = color;
        }

        JSONObject toJsonObject() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", title);
            o.put("color", hex(color));
            o.put("pages", new JSONArray(pages));
            o.put("folder", folder);
            o.put("favorite", favorite);
            o.put("updated", updated);
            return o;
        }

        static Notebook fromJson(JSONObject o) throws JSONException {
            Notebook n = new Notebook(o.getString("id"), o.optString("title", "Cuaderno"),
                    parseColor(o.optString("color", "#2DD4BF"), 0xFF2DD4BF));
            JSONArray p = o.optJSONArray("pages");
            if (p != null) for (int i = 0; i < p.length(); i++) n.pages.add(p.getString(i));
            n.folder = o.optString("folder", "");
            n.favorite = o.optBoolean("favorite");
            n.updated = o.optLong("updated");
            return n;
        }
    }

    static float segmentDistance(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float len2 = dx * dx + dy * dy;
        float t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        float cx = ax + t * dx - px, cy = ay + t * dy - py;
        return (float) Math.sqrt(cx * cx + cy * cy);
    }

    /** Redondea a 1/scale y lo escribe sin ceros de más. */
    private static String round(float v, int scale) {
        long r = Math.round(v * scale);
        if (r % scale == 0) return Long.toString(r / scale);
        return Double.toString((double) r / scale);
    }

    private Ink() {}
}
