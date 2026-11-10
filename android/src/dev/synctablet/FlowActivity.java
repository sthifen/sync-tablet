package dev.synctablet;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;

/**
 * Diagramas de flujo en una hoja infinita, al estilo de Lucidchart: figuras de
 * diagrama (proceso, decisión, inicio/fin, datos, documento…), conectores en
 * codo que siguen a las figuras al moverlas, flechas «+» alrededor de la figura
 * elegida para crear el siguiente paso ya conectado, guías de alineación,
 * lápiz para anotar, deshacer, y exportar a PDF o imagen. Se guarda en
 * files/flows/&lt;id&gt;.json (solo en la tablet, como las presentaciones).
 */
public final class FlowActivity extends Activity {
    static final int PROCESS = 0, DECISION = 1, TERMINAL = 2, DATA = 3, DOCUMENT = 4, DATABASE = 5, SUBPROCESS = 6,
            CIRCLE = 7, PREPARATION = 8, MANUAL = 9, NOTE = 10, TEXT = 11;
    static final String[] KIND_NAMES = {"Proceso", "Decisión", "Inicio / Fin", "Datos (E/S)", "Documento", "Base de datos",
            "Subproceso", "Conector", "Preparación", "Entrada manual", "Nota", "Texto"};
    private static final String[] KIND_KEYS = {"process", "decision", "terminal", "data", "document", "database",
            "subprocess", "circle", "preparation", "manual", "note", "text"};
    static final int[] COLORS = {0xFF2563EB, 0xFF16A34A, 0xFFDC2626, 0xFFEA580C, 0xFFCA8A04, 0xFF7C3AED, 0xFFDB2777, 0xFF0EA5E9, 0xFF475569, 0xFF111827};
    private static final float GRID = 20;

    static final class Node {
        String id;
        int kind;
        float x, y, w, h;
        String text = "";
        int color = COLORS[0];

        float cx() { return x + w / 2; }
        float cy() { return y + h / 2; }
        boolean contains(float px, float py, float slop) {
            return px >= x - slop && px <= x + w + slop && py >= y - slop && py <= y + h + slop;
        }
    }

    static final class Edge {
        String id, from, to;
        /** Lado de salida y de llegada: 0 arriba, 1 derecha, 2 abajo, 3 izquierda; −1 automático. */
        int fromSide = -1, toSide = -1;
        String text = "";
        int color = 0xFF475569;
        /** 0 en codo, 1 recto. */
        int style;
    }

    static final class Scribble {
        float[] pts = new float[64];
        int n;
        int color;
        float width;
        /** 0 normal, 1 punteado, 2 rayado, 3 resaltador. */
        int style;

        void add(float x, float y) {
            if (2 * n + 2 > pts.length) pts = java.util.Arrays.copyOf(pts, pts.length * 2);
            pts[2 * n] = x;
            pts[2 * n + 1] = y;
            n++;
        }
    }

    private String id;
    private String title = "Diagrama";
    private String folder = "";
    /** Hoja infinita: se abre con el lápiz y sin la paleta de figuras. */
    private boolean board;
    private float penWidth = 3;
    private int penStyle;
    private View paletteView;
    private final ArrayList<Node> nodes = new ArrayList<>();
    private final ArrayList<Edge> edges = new ArrayList<>();
    private final ArrayList<Scribble> scribbles = new ArrayList<>();
    private final ArrayList<String> undo = new ArrayList<>(), redo = new ArrayList<>();
    private NoteTheme t;
    private OptionDock dock;
    private FlowView view;
    private TextView titleView;
    private LinearLayout toolRow;
    private int color = COLORS[0];
    /** 0 elegir/mover, 1 conectar, 2 lápiz, 3 borrar. */
    private int tool;
    private static final int SELECT = 0, CONNECT = 1, PEN = 2, ERASE = 3;

    // ---- Archivos ----

    static final class Info {
        String id, title, folder = "";
        long updated;
        boolean board;
        int nodes;
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "flows");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static ArrayList<Info> list(Context c) {
        ArrayList<Info> out = new ArrayList<>();
        File[] files = dir(c).listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return out;
        for (File f : files) {
            try {
                JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                Info i = new Info();
                i.id = o.getString("id");
                i.title = o.optString("title", "Diagrama");
                i.folder = o.optString("folder");
                i.updated = o.optLong("updated");
                JSONArray n = o.optJSONArray("nodes");
                i.nodes = n == null ? 0 : n.length();
                i.board = o.optBoolean("board");
                out.add(i);
            } catch (IOException | JSONException e) {
                android.util.Log.w("SyncTablet", "diagrama " + f, e);
            }
        }
        out.sort((a, b) -> Long.compare(b.updated, a.updated));
        return out;
    }

    /** Diagrama nuevo con un «Inicio» para empezar; devuelve su id. */
    static String create(Context c, String title, String folder) {
        return create(c, title, folder, false);
    }

    /** `board`: hoja infinita para escribir a mano (sin «Inicio»). */
    static String create(Context c, String title, String folder, boolean board) {
        String id = Ink.newId();
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", title);
            if (board) {
                o.put("board", true);
                o.put("folder", folder == null ? "" : folder);
                o.put("updated", System.currentTimeMillis());
                o.put("nodes", new JSONArray());
                o.put("edges", new JSONArray());
                write(new File(dir(c), id + ".json"), o.toString());
                return id;
            }
            o.put("folder", folder == null ? "" : folder);
            o.put("updated", System.currentTimeMillis());
            JSONArray nodes = new JSONArray();
            JSONObject start = new JSONObject();
            start.put("id", Ink.newId());
            start.put("kind", "terminal");
            start.put("x", -80);
            start.put("y", -30);
            start.put("w", 160);
            start.put("h", 60);
            start.put("text", "Inicio");
            start.put("color", Ink.hex(COLORS[1]));
            nodes.put(start);
            o.put("nodes", nodes);
            o.put("edges", new JSONArray());
            write(new File(dir(c), id + ".json"), o.toString());
            return id;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    static void delete(Context c, String id) {
        new File(dir(c), id + ".json").delete();
    }

    static void setFolder(Context c, String id, String folder) {
        File f = new File(dir(c), id + ".json");
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            o.put("folder", folder);
            write(f, o.toString());
        } catch (IOException | JSONException e) {
            android.util.Log.w("SyncTablet", "mover diagrama", e);
        }
    }

    private static void write(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("no se pudo guardar");
    }

    private String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", title);
            o.put("folder", folder);
            if (board) o.put("board", true);
            o.put("updated", System.currentTimeMillis());
            JSONArray ns = new JSONArray();
            for (Node n : nodes) {
                JSONObject j = new JSONObject();
                j.put("id", n.id);
                j.put("kind", KIND_KEYS[n.kind]);
                j.put("x", n.x);
                j.put("y", n.y);
                j.put("w", n.w);
                j.put("h", n.h);
                j.put("text", n.text);
                j.put("color", Ink.hex(n.color));
                ns.put(j);
            }
            o.put("nodes", ns);
            JSONArray es = new JSONArray();
            for (Edge e : edges) {
                JSONObject j = new JSONObject();
                j.put("id", e.id);
                j.put("from", e.from);
                j.put("to", e.to);
                j.put("fs", e.fromSide);
                j.put("ts", e.toSide);
                j.put("text", e.text);
                j.put("color", Ink.hex(e.color));
                j.put("style", e.style);
                es.put(j);
            }
            o.put("edges", es);
            JSONArray ss = new JSONArray();
            for (Scribble s : scribbles) {
                JSONObject j = new JSONObject();
                j.put("color", Ink.hex(s.color));
                j.put("width", s.width);
                if (s.style != 0) j.put("style", s.style);
                JSONArray p = new JSONArray();
                for (int i = 0; i < 2 * s.n; i++) p.put(Math.round(s.pts[i] * 10) / 10.0);
                j.put("points", p);
                ss.put(j);
            }
            o.put("ink", ss);
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    private void fromJson(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        title = o.optString("title", "Diagrama");
        folder = o.optString("folder");
        board = o.optBoolean("board");
        nodes.clear();
        edges.clear();
        scribbles.clear();
        JSONArray ns = o.optJSONArray("nodes");
        if (ns != null) for (int i = 0; i < ns.length(); i++) {
            JSONObject j = ns.getJSONObject(i);
            Node n = new Node();
            n.id = j.getString("id");
            n.kind = Math.max(0, java.util.Arrays.asList(KIND_KEYS).indexOf(j.optString("kind")));
            n.x = (float) j.optDouble("x");
            n.y = (float) j.optDouble("y");
            n.w = (float) j.optDouble("w", 160);
            n.h = (float) j.optDouble("h", 80);
            n.text = j.optString("text");
            n.color = Ink.parseColor(j.optString("color", "#2563EB"), COLORS[0]);
            nodes.add(n);
        }
        JSONArray es = o.optJSONArray("edges");
        if (es != null) for (int i = 0; i < es.length(); i++) {
            JSONObject j = es.getJSONObject(i);
            Edge e = new Edge();
            e.id = j.getString("id");
            e.from = j.getString("from");
            e.to = j.getString("to");
            e.fromSide = j.optInt("fs", -1);
            e.toSide = j.optInt("ts", -1);
            e.text = j.optString("text");
            e.color = Ink.parseColor(j.optString("color", "#475569"), 0xFF475569);
            e.style = j.optInt("style");
            edges.add(e);
        }
        JSONArray ss = o.optJSONArray("ink");
        if (ss != null) for (int i = 0; i < ss.length(); i++) {
            JSONObject j = ss.getJSONObject(i);
            Scribble s = new Scribble();
            s.color = Ink.parseColor(j.optString("color", "#111827"), 0xFF111827);
            s.width = (float) j.optDouble("width", 3);
            s.style = j.optInt("style");
            JSONArray p = j.getJSONArray("points");
            for (int k = 0; k + 1 < p.length(); k += 2) s.add((float) p.getDouble(k), (float) p.getDouble(k + 1));
            scribbles.add(s);
        }
    }

    private void save() {
        try {
            write(new File(dir(this), id + ".json"), toJson());
        } catch (IOException e) {
            Ui.toast(this, "No se pudo guardar el diagrama");
        }
    }

    /** Antes de cada cambio: para deshacer. */
    private void remember() {
        undo.add(toJson());
        if (undo.size() > 80) undo.remove(0);
        redo.clear();
    }

    private void changed() {
        save();
        view.invalidate();
    }

    private void undo(boolean back) {
        ArrayList<String> from = back ? undo : redo, to = back ? redo : undo;
        if (from.isEmpty()) return;
        to.add(toJson());
        try {
            fromJson(from.remove(from.size() - 1));
        } catch (JSONException ignored) {
        }
        view.selNode = null;
        view.selEdge = null;
        changed();
    }

    // ---- Pantalla ----

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        t = new NoteTheme(this);
        id = getIntent().getStringExtra("id");
        if (id == null) {
            finish();
            return;
        }
        try {
            fromJson(new String(Files.readAllBytes(new File(dir(this), id + ".json").toPath()), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            Ui.toast(this, "No se pudo abrir el diagrama");
            finish();
            return;
        }
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(t.dark ? 0xFF161616 : 0xFFF6F7F9);
        view = new FlowView(this);
        frame.addView(view, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(t.dp(6), t.dp(4), t.dp(10), t.dp(4));
        bar.setBackground(Ui.round(t.dark ? 0xF2262626 : 0xF7FFFFFF, t.dp(22)));
        bar.setElevation(t.dp(6));
        bar.addView(icon(IconView.BACK, "Volver", v -> finish()));
        titleView = Ui.text(this, title, 17, t.text, true);
        titleView.setPadding(t.dp(6), 0, t.dp(12), 0);
        titleView.setSingleLine(true);
        titleView.setMaxWidth(t.dp(260));
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setOnClickListener(v -> rename());
        bar.addView(titleView);
        bar.addView(icon(IconView.UNDO, "Deshacer", v -> undo(true)));
        bar.addView(icon(IconView.REDO, "Rehacer", v -> undo(false)));
        bar.addView(sep());
        toolRow = new LinearLayout(this);
        toolRow.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(toolRow);
        bar.addView(sep());
        bar.addView(icon(IconView.SHAPES, "Mostrar u ocultar figuras", v -> togglePalette()));
        bar.addView(icon(IconView.EXPAND, "Ver todo", v -> view.fit(true)));
        bar.addView(icon(IconView.EXPORT, "Exportar", v -> exportMenu()));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setPadding(t.dp(12), t.dp(10), t.dp(12), t.dp(10));
        hs.addView(bar);
        top.addView(hs);
        frame.addView(top, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

        // Paleta de figuras a la izquierda, como en Lucidchart.
        android.widget.ScrollView ps = new android.widget.ScrollView(this);
        ps.setVerticalScrollBarEnabled(false);
        LinearLayout palette = new LinearLayout(this);
        palette.setOrientation(LinearLayout.VERTICAL);
        palette.setPadding(t.dp(6), t.dp(8), t.dp(6), t.dp(8));
        palette.setBackground(Ui.round(t.dark ? 0xF2262626 : 0xF7FFFFFF, t.dp(20)));
        palette.setElevation(t.dp(6));
        for (int k = 0; k < KIND_NAMES.length; k++) {
            final int kind = k;
            ShapeIcon s = new ShapeIcon(this, kind, t.text);
            s.setContentDescription("Añadir " + KIND_NAMES[k]);
            s.setOnClickListener(v -> addFromPalette(kind));
            palette.addView(s, new LinearLayout.LayoutParams(t.dp(52), t.dp(44)));
        }
        ps.addView(palette);
        paletteView = ps;
        if (board) ps.setVisibility(View.GONE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL);
        plp.leftMargin = t.dp(12);
        plp.topMargin = t.dp(70);
        frame.addView(ps, plp);

        frame.setOnApplyWindowInsetsListener((v, in) -> {
            top.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(), in.getSystemWindowInsetRight(), 0);
            return in;
        });
        dock = new OptionDock(this, frame, null, t.dark ? 0xF7262626 : 0xF7FFFFFF, t.dark);
        setContentView(frame);
        if (board) tool = PEN;
        refreshTools();
        view.post(() -> view.fit(false));
        getWindow().setNavigationBarColor(t.dark ? 0xFF161616 : 0xFFF6F7F9);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (id != null && view != null) save();
    }

    private IconView icon(int which, String label, View.OnClickListener click) {
        IconView v = new IconView(this, which, t.text, label);
        v.setOnClickListener(click);
        return v;
    }

    private View sep() {
        View v = new View(this);
        v.setBackgroundColor(t.line);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Math.max(1, t.dp(1)), t.dp(26));
        p.setMargins(t.dp(6), 0, t.dp(6), 0);
        v.setLayoutParams(p);
        return v;
    }

    private void refreshTools() {
        toolRow.removeAllViews();
        int[] icons = {IconView.SELECT, IconView.S_ARROW, IconView.PEN, IconView.ERASER};
        String[] names = {"Elegir y mover", "Conectar", "Lápiz", "Borrar"};
        for (int k = 0; k < icons.length; k++) {
            final int which = k;
            IconView v = icon(icons[k], names[k], x -> {
                tool = which;
                view.selNode = null;
                view.selEdge = null;
                refreshTools();
                view.invalidate();
                if (which == CONNECT) Ui.toast(this, "Arrastra de una figura a otra");
            });
            v.setActive(tool == k);
            toolRow.addView(v);
        }
        if (tool == PEN) {
            TextView pen = Ui.text(this, PEN_STYLES[penStyle] + " · " + WIDTH_NAMES[widthIndex()], 14, t.text, false);
            pen.setPadding(t.dp(10), 0, t.dp(10), 0);
            pen.setGravity(Gravity.CENTER);
            pen.setBackground(Ui.round(t.callout, t.dp(18)));
            pen.setContentDescription("Tipo y grosor del lápiz");
            pen.setOnClickListener(v -> penMenu());
            toolRow.addView(pen, new LinearLayout.LayoutParams(-2, t.dp(36)));
        }
        View sw = new View(this);
        sw.setBackground(Ui.round(color, t.dp(13)));
        FrameLayout f = new FrameLayout(this);
        f.addView(sw, new FrameLayout.LayoutParams(t.dp(26), t.dp(26), Gravity.CENTER));
        f.setContentDescription("Color");
        f.setOnClickListener(v -> colorMenu());
        toolRow.addView(f, new LinearLayout.LayoutParams(t.dp(44), t.dp(44)));
        boolean any = view.selNode != null || view.selEdge != null;
        if (any) {
            toolRow.addView(icon(IconView.TEXT, "Escribir", v -> editText()));
            if (view.selNode != null) toolRow.addView(icon(IconView.SHAPES, "Cambiar figura", v -> changeKind()));
            if (view.selEdge != null) toolRow.addView(icon(IconView.S_LINE, "Codo o recta", v -> {
                remember();
                view.selEdge.style = 1 - view.selEdge.style;
                changed();
            }));
            if (view.selNode != null) toolRow.addView(icon(IconView.COPY, "Duplicar", v -> duplicate()));
            toolRow.addView(icon(IconView.TRASH, "Borrar", v -> deleteSelected()));
        }
    }

    private static final String[] PEN_STYLES = {"Normal", "Punteado", "Rayado", "Resaltador"};
    private static final String[] WIDTH_NAMES = {"Fino", "Medio", "Grueso"};
    private static final float[] WIDTHS = {2, 4, 8};

    private int widthIndex() {
        for (int i = 0; i < WIDTHS.length; i++) if (WIDTHS[i] == penWidth) return i;
        return 0;
    }

    private void penMenu() {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (int k = 0; k < PEN_STYLES.length; k++) {
            final int style = k;
            tiles.add(new OptionDock.Tile(new PenLine(this, k, t.text), PEN_STYLES[k], penStyle == k, () -> {
                penStyle = style;
                refreshTools();
            }));
        }
        for (int k = 0; k < WIDTHS.length; k++) {
            final float w = WIDTHS[k];
            tiles.add(new OptionDock.Tile(new PenLine(this, -1 - k, t.text), WIDTH_NAMES[k], penWidth == w, () -> {
                penWidth = w;
                refreshTools();
            }));
        }
        dock.tiles("Lápiz", tiles);
    }

    /** Muestra de un estilo (0–3) o de un grosor (−1, −2, −3). */
    private static final class PenLine extends View {
        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        PenLine(Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density, w = getWidth(), h = getHeight();
            float sw = kind < 0 ? (-kind) * 2.5f * d : 3 * d;
            p.setStrokeWidth(kind == 3 ? 14 * d : kind == 1 ? sw * 1.3f : sw);
            p.setAlpha(kind == 3 ? 110 : 255);
            if (kind == 1) p.setPathEffect(new android.graphics.DashPathEffect(new float[] {0.01f, sw * 2.6f}, 0));
            else if (kind == 2) p.setPathEffect(new android.graphics.DashPathEffect(new float[] {sw * 3.5f, sw * 2.8f}, 0));
            else p.setPathEffect(null);
            Path path = new Path();
            path.moveTo(w * 0.15f, h * 0.6f);
            path.cubicTo(w * 0.35f, h * 0.2f, w * 0.6f, h * 0.85f, w * 0.85f, h * 0.4f);
            c.drawPath(path, p);
        }
    }

    private void togglePalette() {
        if (paletteView == null) return;
        boolean show = paletteView.getVisibility() != View.VISIBLE;
        paletteView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && tool == PEN) {
            tool = SELECT;
            refreshTools();
        }
    }

    private void rename() {
        EditText e = field(title, false);
        dock.show("Nombre del diagrama", e, new OptionDock.Action("Cancelar", false, null), new OptionDock.Action("Guardar", true, () -> {
            String s = e.getText().toString().trim();
            if (s.isEmpty()) return;
            title = s;
            titleView.setText(s);
            save();
        }));
        focus(e);
    }

    private EditText field(String s, boolean multi) {
        EditText e = new EditText(this);
        e.setText(s);
        e.setSelection(e.getText().length());
        e.setTextColor(t.text);
        e.setTextSize(17);
        if (!multi) e.setSingleLine(true);
        else e.setMinLines(2);
        int pad = t.dp(20);
        e.setPadding(pad, t.dp(10), pad, t.dp(10));
        return e;
    }

    private void focus(EditText e) {
        dock.focus(e);
    }

    private void editText() {
        Node n = view.selNode;
        Edge ed = view.selEdge;
        if (n == null && ed == null) return;
        EditText e = field(n != null ? n.text : ed.text, true);
        e.setHint(n != null ? "Texto de la figura" : "Etiqueta: Sí, No…");
        dock.show(n != null ? KIND_NAMES[n.kind] : "Etiqueta del conector", e, new OptionDock.Action("Cancelar", false, null), new OptionDock.Action("Listo", true, () -> {
            remember();
            if (n != null) {
                n.text = e.getText().toString();
                fitText(n);
            } else {
                ed.text = e.getText().toString().trim();
            }
            changed();
        }));
        focus(e);
    }

    /** Si el texto no cabe, la figura crece hacia abajo. */
    private void fitText(Node n) {
        StaticLayout l = textLayout(n, textPaint);
        float need = l.getHeight() + (n.kind == DECISION ? n.h * 0.45f : 24);
        if (need > n.h) n.h = snap(need + GRID / 2);
    }

    private void colorMenu() {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (int c : COLORS) tiles.add(new OptionDock.Tile(dock.swatch(c), "", c == color, () -> useColor(c)));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.SLIDERS), "Más colores", false,
                () -> ColorWheel.show(this, dock, "Color", color, t.text, t.muted, this::useColor)).stays());
        dock.tiles("Color", tiles);
    }

    private void useColor(int c) {
        color = c;
        if (view.selNode != null || view.selEdge != null) {
            remember();
            if (view.selNode != null) view.selNode.color = c;
            if (view.selEdge != null) view.selEdge.color = c;
            changed();
        }
        refreshTools();
    }

    private void changeKind() {
        Node n = view.selNode;
        if (n == null) return;
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (int k = 0; k < KIND_NAMES.length; k++) {
            final int kind = k;
            tiles.add(new OptionDock.Tile(new ShapeIcon(this, k, t.text), KIND_NAMES[k], n.kind == k, () -> {
                remember();
                n.kind = kind;
                changed();
            }));
        }
        dock.tiles("Cambiar figura", tiles);
    }

    private void duplicate() {
        Node n = view.selNode;
        if (n == null) return;
        remember();
        Node c = newNode(n.kind, n.x + 40, n.y + 40);
        c.w = n.w;
        c.h = n.h;
        c.text = n.text;
        c.color = n.color;
        view.selNode = c;
        changed();
        refreshTools();
    }

    private void deleteSelected() {
        remember();
        if (view.selNode != null) {
            String gone = view.selNode.id;
            nodes.remove(view.selNode);
            edges.removeIf(e -> e.from.equals(gone) || e.to.equals(gone));
        }
        if (view.selEdge != null) edges.remove(view.selEdge);
        view.selNode = null;
        view.selEdge = null;
        changed();
        refreshTools();
    }

    private Node node(String id) {
        for (Node n : nodes) if (n.id.equals(id)) return n;
        return null;
    }

    private static float snap(float v) {
        return Math.round(v / GRID) * GRID;
    }

    private Node newNode(int kind, float cx, float cy) {
        Node n = new Node();
        n.id = Ink.newId();
        n.kind = kind;
        float[] size = defaultSize(kind);
        n.w = size[0];
        n.h = size[1];
        // El centro va a la rejilla: así las figuras quedan alineadas entre sí.
        n.x = snap(cx) - n.w / 2;
        n.y = snap(cy) - n.h / 2;
        n.color = kind == NOTE ? 0xFFCA8A04 : kind == TEXT ? 0xFF111827 : color;
        n.text = kind == TEXT ? "Texto" : kind == DECISION ? "¿?" : "";
        nodes.add(n);
        return n;
    }

    static float[] defaultSize(int kind) {
        switch (kind) {
            case DECISION: return new float[] {160, 120};
            case TERMINAL: return new float[] {160, 60};
            case CIRCLE: return new float[] {60, 60};
            case DATABASE: return new float[] {120, 100};
            case NOTE: return new float[] {160, 140};
            case TEXT: return new float[] {160, 40};
            default: return new float[] {160, 80};
        }
    }

    /**
     * Tocar una figura de la paleta: con una figura elegida, la nueva va debajo
     * y ya conectada; si no, al centro de la vista.
     */
    private void addFromPalette(int kind) {
        remember();
        Node from = view.selNode;
        Node n;
        if (from != null && kind != NOTE && kind != TEXT) {
            n = nextTo(from, 2, kind);
        } else {
            float[] c = view.center();
            n = newNode(kind, c[0], c[1]);
        }
        view.selNode = n;
        view.selEdge = null;
        tool = SELECT;
        changed();
        refreshTools();
        view.reveal(n);
    }

    /** Figura nueva al lado `side` de `from`, conectada, sin pisar a otra. */
    private Node nextTo(Node from, int side, int kind) {
        float[] size = defaultSize(kind);
        // De lado deja sitio para la etiqueta («Sí», «No»).
        float gap = side == 1 || side == 3 ? 120 : 80;
        float cx = from.cx(), cy = from.cy();
        if (side == 0) cy = from.y - gap - size[1] / 2;
        else if (side == 1) cx = from.x + from.w + gap + size[0] / 2;
        else if (side == 2) cy = from.y + from.h + gap + size[1] / 2;
        else cx = from.x - gap - size[0] / 2;
        // Si ya hay algo ahí, se corre hacia un lado.
        for (int tries = 0; tries < 12; tries++) {
            boolean busy = false;
            for (Node o : nodes) {
                if (Math.abs(o.cx() - cx) < (o.w + size[0]) / 2 + 20 && Math.abs(o.cy() - cy) < (o.h + size[1]) / 2 + 20) {
                    busy = true;
                    break;
                }
            }
            if (!busy) break;
            if (side == 0 || side == 2) cx += size[0] + 40;
            else cy += size[1] + 40;
        }
        Node n = newNode(kind, cx, cy);
        if (side == 0 || side == 2) n.x = from.cx() - n.w / 2;
        else n.y = from.cy() - n.h / 2;
        n.color = from.kind == TERMINAL && kind != TERMINAL ? color : from.color;
        Edge e = new Edge();
        e.id = Ink.newId();
        e.from = from.id;
        e.to = n.id;
        e.color = from.color == COLORS[0] ? 0xFF475569 : from.color;
        edges.add(e);
        return n;
    }

    private void connect(Node a, Node b, int fromSide, int toSide) {
        if (a == b) return;
        for (Edge e : edges) if (e.from.equals(a.id) && e.to.equals(b.id)) return;
        Edge e = new Edge();
        e.id = Ink.newId();
        e.from = a.id;
        e.to = b.id;
        e.fromSide = fromSide;
        e.toSide = toSide;
        if (a.kind == DECISION) {
            int out = 0;
            for (Edge o : edges) if (o.from.equals(a.id)) out++;
            e.text = out == 0 ? "Sí" : out == 1 ? "No" : "";
        }
        edges.add(e);
    }

    // ---- Exportar ----

    private void exportMenu() {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.FILE), "PDF", false, () -> export(true)));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.IMAGE), "Imagen PNG", false, () -> export(false)));
        dock.tiles("Exportar el diagrama", tiles);
    }

    private RectF contentBounds() {
        RectF r = null;
        for (Node n : nodes) {
            RectF b = new RectF(n.x, n.y, n.x + n.w, n.y + n.h);
            if (r == null) r = b;
            else r.union(b);
        }
        for (Scribble s : scribbles) for (int i = 0; i < s.n; i++) {
            if (r == null) r = new RectF(s.pts[0], s.pts[1], s.pts[0], s.pts[1]);
            r.union(s.pts[2 * i], s.pts[2 * i + 1]);
        }
        if (r == null) r = new RectF(-100, -100, 100, 100);
        r.inset(-40, -40);
        return r;
    }

    private void export(boolean pdf) {
        view.selNode = null;
        view.selEdge = null;
        view.invalidate();
        refreshTools();
        RectF b = contentBounds();
        String name = PdfActivity.clean(title) + (pdf ? ".pdf" : ".png");
        try {
            Uri uri;
            if (pdf) {
                PdfDocument doc = new PdfDocument();
                // Hoja apaisada o vertical según el diagrama, con tamaño A4.
                boolean wide = b.width() > b.height();
                float pw = wide ? 842 : 595, ph = wide ? 595 : 842;
                float k = Math.min((pw - 48) / b.width(), (ph - 48) / b.height());
                k = Math.min(k, 1.5f);
                PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder((int) pw, (int) ph, 1).create());
                Canvas c = page.getCanvas();
                c.translate((pw - b.width() * k) / 2, (ph - b.height() * k) / 2);
                c.scale(k, k);
                c.translate(-b.left, -b.top);
                view.drawContent(c, false, true);
                doc.finishPage(page);
                try {
                    uri = PdfActivity.save(this, doc, name);
                } finally {
                    doc.close();
                }
            } else {
                float k = Math.min(2f, 4000 / Math.max(b.width(), b.height()));
                Bitmap bm = Bitmap.createBitmap(Math.max(1, Math.round(b.width() * k)), Math.max(1, Math.round(b.height() * k)), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bm);
                c.drawColor(0xFFFFFFFF);
                c.scale(k, k);
                c.translate(-b.left, -b.top);
                view.drawContent(c, false, true);
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
                if (Build.VERSION.SDK_INT >= 29) v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/SyncTablet");
                uri = getContentResolver().insert(MediaStore.Files.getContentUri("external"), v);
                if (uri == null) throw new IOException("sin acceso a Descargas");
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    bm.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                bm.recycle();
            }
            Ui.toast(this, "Guardado en Descargas/SyncTablet/" + name);
            Intent share = new Intent(Intent.ACTION_SEND).setType(pdf ? "application/pdf" : "image/png")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Compartir"));
        } catch (IOException | RuntimeException e) {
            android.util.Log.w("SyncTablet", "exportar diagrama", e);
            Ui.toast(this, "No se pudo exportar");
        }
    }

    // ---- Dibujo de figuras ----

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private StaticLayout textLayout(Node n, TextPaint p) {
        p.setTextSize(n.kind == TEXT ? 22 : 18);
        p.setColor(n.kind == TEXT ? n.color : 0xFF111827);
        float inset = n.kind == DECISION ? n.w * 0.22f : n.kind == DATA || n.kind == PREPARATION ? 28 : 12;
        int w = Math.max(10, Math.round(n.w - 2 * inset));
        String s = n.text == null ? "" : n.text;
        return StaticLayout.Builder.obtain(s, 0, s.length(), p, w).setAlignment(n.kind == TEXT || n.kind == NOTE ? Layout.Alignment.ALIGN_NORMAL : Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0, 1.05f).build();
    }

    /** Contorno de cada figura en su caja. */
    static Path outline(int kind, float x, float y, float w, float h) {
        Path p = new Path();
        float r = Math.min(w, h);
        switch (kind) {
            case DECISION:
                p.moveTo(x + w / 2, y);
                p.lineTo(x + w, y + h / 2);
                p.lineTo(x + w / 2, y + h);
                p.lineTo(x, y + h / 2);
                p.close();
                break;
            case TERMINAL:
                p.addRoundRect(new RectF(x, y, x + w, y + h), h / 2, h / 2, Path.Direction.CW);
                break;
            case DATA: {
                float s = Math.min(w * 0.18f, h * 0.5f);
                p.moveTo(x + s, y);
                p.lineTo(x + w, y);
                p.lineTo(x + w - s, y + h);
                p.lineTo(x, y + h);
                p.close();
                break;
            }
            case DOCUMENT: {
                float wave = h * 0.12f;
                p.moveTo(x, y);
                p.lineTo(x + w, y);
                p.lineTo(x + w, y + h - wave);
                p.cubicTo(x + w * 0.75f, y + h - 3 * wave, x + w * 0.5f, y + h + wave, x + w * 0.25f, y + h);
                p.cubicTo(x + w * 0.12f, y + h - wave * 0.5f, x + w * 0.05f, y + h - wave, x, y + h - wave);
                p.close();
                break;
            }
            case DATABASE: {
                float e = Math.min(h * 0.18f, 22);
                RectF top = new RectF(x, y, x + w, y + 2 * e);
                p.moveTo(x, y + e);
                p.arcTo(top, 180, 180);
                p.lineTo(x + w, y + h - e);
                p.arcTo(new RectF(x, y + h - 2 * e, x + w, y + h), 0, 180);
                p.close();
                break;
            }
            case CIRCLE:
                p.addOval(new RectF(x + (w - r) / 2, y + (h - r) / 2, x + (w + r) / 2, y + (h + r) / 2), Path.Direction.CW);
                break;
            case PREPARATION: {
                float s = Math.min(w * 0.2f, h * 0.5f);
                p.moveTo(x + s, y);
                p.lineTo(x + w - s, y);
                p.lineTo(x + w, y + h / 2);
                p.lineTo(x + w - s, y + h);
                p.lineTo(x + s, y + h);
                p.lineTo(x, y + h / 2);
                p.close();
                break;
            }
            case MANUAL:
                p.moveTo(x, y + h * 0.3f);
                p.lineTo(x + w, y);
                p.lineTo(x + w, y + h);
                p.lineTo(x, y + h);
                p.close();
                break;
            case NOTE: {
                float f = Math.min(24, r * 0.2f);
                p.moveTo(x, y);
                p.lineTo(x + w - f, y);
                p.lineTo(x + w, y + f);
                p.lineTo(x + w, y + h);
                p.lineTo(x, y + h);
                p.close();
                break;
            }
            default:
                p.addRoundRect(new RectF(x, y, x + w, y + h), 8, 8, Path.Direction.CW);
        }
        return p;
    }

    /** Punto de enganche del lado `side` (0 arriba, 1 derecha, 2 abajo, 3 izquierda). */
    static float[] anchor(Node n, int side) {
        switch (side) {
            case 0: return new float[] {n.cx(), n.y};
            case 1: return new float[] {n.x + n.w, n.cy()};
            case 2: return new float[] {n.cx(), n.y + n.h};
            default: return new float[] {n.x, n.cy()};
        }
    }

    /** Lados automáticos: los que se miran de frente. */
    static int[] sides(Node a, Node b) {
        float dx = b.cx() - a.cx(), dy = b.cy() - a.cy();
        boolean vertical = Math.abs(dy) * 1.2f > Math.abs(dx) || (Math.abs(dy) > (a.h + b.h) / 2 + 20 && Math.abs(dx) < (a.w + b.w) / 2);
        if (vertical) return dy >= 0 ? new int[] {2, 0} : new int[] {0, 2};
        return dx >= 0 ? new int[] {1, 3} : new int[] {3, 1};
    }

    /** Puntos del conector (en codo o recto). */
    float[] route(Edge e) {
        Node a = node(e.from), b = node(e.to);
        if (a == null || b == null) return null;
        int[] auto = sides(a, b);
        int fs = e.fromSide >= 0 ? e.fromSide : auto[0], ts = e.toSide >= 0 ? e.toSide : auto[1];
        float[] p = anchor(a, fs), q = anchor(b, ts);
        if (e.style == 1) return new float[] {p[0], p[1], q[0], q[1]};
        float stub = 24;
        float[] d = {0, 1, 0, -1}, dyv = {-1, 0, 1, 0};
        float px = p[0] + d[fs] * stub, py = p[1] + dyv[fs] * stub;
        float qx = q[0] + d[ts] * stub, qy = q[1] + dyv[ts] * stub;
        boolean ph = fs == 1 || fs == 3, qh = ts == 1 || ts == 3;
        ArrayList<Float> pts = new ArrayList<>();
        add(pts, p[0], p[1]);
        add(pts, px, py);
        if (ph && qh) {
            float mx = (px + qx) / 2;
            add(pts, mx, py);
            add(pts, mx, qy);
        } else if (!ph && !qh) {
            float my = (py + qy) / 2;
            add(pts, px, my);
            add(pts, qx, my);
        } else if (ph) {
            add(pts, qx, py);
        } else {
            add(pts, px, qy);
        }
        add(pts, qx, qy);
        add(pts, q[0], q[1]);
        // Fuera los puntos repetidos y los que siguen en línea recta.
        ArrayList<Float> clean = new ArrayList<>();
        for (int i = 0; i < pts.size(); i += 2) {
            float x = pts.get(i), y = pts.get(i + 1);
            int n = clean.size();
            if (n >= 2 && Math.abs(clean.get(n - 2) - x) < 0.5f && Math.abs(clean.get(n - 1) - y) < 0.5f) continue;
            if (n >= 4) {
                float ax = clean.get(n - 4), ay = clean.get(n - 3), bx = clean.get(n - 2), by = clean.get(n - 1);
                if ((Math.abs(ax - bx) < 0.5f && Math.abs(bx - x) < 0.5f) || (Math.abs(ay - by) < 0.5f && Math.abs(by - y) < 0.5f)) {
                    clean.set(n - 2, x);
                    clean.set(n - 1, y);
                    continue;
                }
            }
            clean.add(x);
            clean.add(y);
        }
        float[] out = new float[clean.size()];
        for (int i = 0; i < out.length; i++) out[i] = clean.get(i);
        return out;
    }

    private static void add(ArrayList<Float> l, float x, float y) {
        l.add(x);
        l.add(y);
    }

    /** Distancia del punto a la polilínea. */
    static float distance(float[] pts, float x, float y) {
        float best = Float.MAX_VALUE;
        for (int i = 0; i + 3 < pts.length; i += 2) best = Math.min(best, Ink.segmentDistance(x, y, pts[i], pts[i + 1], pts[i + 2], pts[i + 3]));
        return best;
    }

    /** Punto a la mitad del recorrido (para la etiqueta). */
    static float[] middle(float[] pts) {
        float total = 0;
        for (int i = 0; i + 3 < pts.length; i += 2) total += (float) Math.hypot(pts[i + 2] - pts[i], pts[i + 3] - pts[i + 1]);
        float half = total / 2;
        for (int i = 0; i + 3 < pts.length; i += 2) {
            float l = (float) Math.hypot(pts[i + 2] - pts[i], pts[i + 3] - pts[i + 1]);
            if (half <= l && l > 0) {
                float k = half / l;
                return new float[] {pts[i] + (pts[i + 2] - pts[i]) * k, pts[i + 1] + (pts[i + 3] - pts[i + 1]) * k};
            }
            half -= l;
        }
        return new float[] {pts[0], pts[1]};
    }

    // ---- El lienzo ----

    private final class FlowView extends View {
        float scale = 1, ox, oy;
        Node selNode;
        Edge selEdge;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG), accent = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG), labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final ScaleGestureDetector scaler;
        private final float density;
        private static final int NONE = 0, PAN = 1, MOVE = 2, RESIZE = 3, LINK = 4, DRAW = 5, ERASING = 6, PINCH = 7;
        private int mode = NONE;
        private float downX, downY, lastX, lastY, startX, startY, startW, startH;
        private boolean moved;
        private Node linkFrom;
        private int linkSide = -1;
        private float linkX, linkY;
        private Node hover;
        private Scribble drawing;
        private long lastTap;
        private Object lastTapTarget;
        private float guideX = Float.NaN, guideY = Float.NaN;
        private boolean changedThisGesture;
        private float focusX, focusY;

        FlowView(Context c) {
            super(c);
            density = c.getResources().getDisplayMetrics().density;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            accent.setColor(0xFF2563EB);
            guide.setColor(0xFFEC4899);
            guide.setStyle(Paint.Style.STROKE);
            label.setTextSize(16);
            label.setTextAlign(Paint.Align.CENTER);
            scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    float s = Math.max(0.15f, Math.min(4f, scale * d.getScaleFactor()));
                    float fx = d.getFocusX(), fy = d.getFocusY();
                    ox = fx - (fx - ox) * s / scale + (fx - focusX);
                    oy = fy - (fy - oy) * s / scale + (fy - focusY);
                    focusX = fx;
                    focusY = fy;
                    scale = s;
                    invalidate();
                    return true;
                }

                @Override
                public boolean onScaleBegin(ScaleGestureDetector d) {
                    focusX = d.getFocusX();
                    focusY = d.getFocusY();
                    return true;
                }
            });
        }

        float docX(float sx) { return (sx - ox) / scale; }
        float docY(float sy) { return (sy - oy) / scale; }

        float[] center() {
            return new float[] {docX(getWidth() / 2f), docY(getHeight() / 2f)};
        }

        void fit(boolean animate) {
            if (getWidth() == 0) return;
            RectF b = contentBounds();
            float s = Math.min(1.4f * density, Math.min((getWidth() - 160 * density) / b.width(), (getHeight() - 180 * density) / b.height()));
            s = Math.max(0.15f, s);
            float tx = (getWidth() + 60 * density) / 2f - b.centerX() * s, ty = (getHeight() + 60 * density) / 2f - b.centerY() * s;
            if (!animate) {
                scale = s;
                ox = tx;
                oy = ty;
                invalidate();
                return;
            }
            float s0 = scale, x0 = ox, y0 = oy, s1 = s;
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0, 1);
            a.setDuration(320);
            a.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
            a.addUpdateListener(v -> {
                float k = (float) v.getAnimatedValue();
                scale = s0 + (s1 - s0) * k;
                ox = x0 + (tx - x0) * k;
                oy = y0 + (ty - y0) * k;
                invalidate();
            });
            a.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            drawGrid(c);
            c.save();
            c.translate(ox, oy);
            c.scale(scale, scale);
            drawContent(c, true, false);
            drawOverlay(c);
            c.restore();
        }

        private void drawGrid(Canvas c) {
            float step = GRID * scale;
            int big = 5;
            while (step < 14 * density) {
                step *= big;
            }
            grid.setColor(t.dark ? 0x33FFFFFF : 0x2A1F2937);
            float x0 = ((ox % step) + step) % step, y0 = ((oy % step) + step) % step;
            float r = Math.max(1f, 1.2f * density);
            for (float y = y0; y < getHeight(); y += step) for (float x = x0; x < getWidth(); x += step) c.drawCircle(x, y, r, grid);
        }

        /** Figuras, conectores y lápiz; `live`: con colores de pantalla (modo oscuro). */
        void drawContent(Canvas c, boolean live, boolean paper) {
            boolean dark = live && t.dark && !paper;
            for (Scribble s : scribbles) {
                stroke.setColor(dark && s.color == 0xFF111827 ? 0xFFE5E7EB : s.color);
                float w = s.style == 3 ? s.width * 6 : s.style == 1 ? s.width * 1.3f : s.width;
                stroke.setStrokeWidth(w);
                if (s.style == 3) stroke.setAlpha(90);
                if (s.style == 1) stroke.setPathEffect(new android.graphics.DashPathEffect(new float[] {0.01f, s.width * 2.6f}, 0));
                if (s.style == 2) stroke.setPathEffect(new android.graphics.DashPathEffect(new float[] {s.width * 3.5f, s.width * 2.8f}, 0));
                Path p = new Path();
                for (int i = 0; i < s.n; i++) {
                    if (i == 0) p.moveTo(s.pts[0], s.pts[1]);
                    else p.lineTo(s.pts[2 * i], s.pts[2 * i + 1]);
                }
                if (s.n == 1) p.lineTo(s.pts[0] + 0.01f, s.pts[1]);
                c.drawPath(p, stroke);
                stroke.setPathEffect(null);
                stroke.setAlpha(255);
            }
            for (Edge e : edges) drawEdge(c, e, e == selEdge && live, dark);
            for (Node n : nodes) drawNode(c, n, dark);
        }

        private void drawEdge(Canvas c, Edge e, boolean selected, boolean dark) {
            float[] pts = route(e);
            if (pts == null) return;
            int col = dark && e.color == 0xFF475569 ? 0xFF9CA3AF : e.color;
            if (selected) {
                stroke.setColor(0x552563EB);
                stroke.setStrokeWidth(10);
                drawPolyline(c, pts);
            }
            stroke.setColor(col);
            stroke.setStrokeWidth(2.4f);
            drawPolyline(c, pts);
            // Punta de flecha.
            int n = pts.length;
            float ex = pts[n - 2], ey = pts[n - 1], bx = pts[n - 4], by = pts[n - 3];
            double a = Math.atan2(ey - by, ex - bx);
            Path head = new Path();
            float len = 14, wid = 0.42f;
            head.moveTo(ex, ey);
            head.lineTo(ex - len * (float) Math.cos(a - wid), ey - len * (float) Math.sin(a - wid));
            head.lineTo(ex - len * (float) Math.cos(a + wid), ey - len * (float) Math.sin(a + wid));
            head.close();
            fill.setColor(col);
            c.drawPath(head, fill);
            if (e.text != null && !e.text.isEmpty()) {
                float[] m = middle(pts);
                label.setColor(dark ? 0xFFE5E7EB : 0xFF111827);
                float w = label.measureText(e.text) + 16;
                labelBg.setColor(dark ? 0xFF1F1F1F : 0xFFFFFFFF);
                c.drawRoundRect(m[0] - w / 2, m[1] - 13, m[0] + w / 2, m[1] + 13, 8, 8, labelBg);
                c.drawText(e.text, m[0], m[1] + 6, label);
            }
        }

        private void drawPolyline(Canvas c, float[] pts) {
            Path p = new Path();
            p.moveTo(pts[0], pts[1]);
            for (int i = 2; i + 1 < pts.length; i += 2) p.lineTo(pts[i], pts[i + 1]);
            c.drawPath(p, stroke);
        }

        private void drawNode(Canvas c, Node n, boolean dark) {
            if (n.kind != TEXT) {
                Path p = outline(n.kind, n.x, n.y, n.w, n.h);
                int tint = n.kind == NOTE ? 0xFFFEF3C7 : mix(n.color, 0xFFFFFFFF, 0.86f);
                fill.setColor(tint);
                if (!dark) {
                    fill.setShadowLayer(6, 0, 2, 0x22000000);
                }
                c.drawPath(p, fill);
                fill.clearShadowLayer();
                stroke.setColor(n.color);
                stroke.setStrokeWidth(2.2f);
                c.drawPath(p, stroke);
                stroke.setStrokeWidth(1.6f);
                if (n.kind == SUBPROCESS) {
                    c.drawLine(n.x + 14, n.y, n.x + 14, n.y + n.h, stroke);
                    c.drawLine(n.x + n.w - 14, n.y, n.x + n.w - 14, n.y + n.h, stroke);
                } else if (n.kind == DATABASE) {
                    float e = Math.min(n.h * 0.18f, 22);
                    c.drawArc(new RectF(n.x, n.y, n.x + n.w, n.y + 2 * e), 0, 180, false, stroke);
                }
            }
            StaticLayout l = textLayout(n, textPaint);
            // El layout usa la pintura al dibujar: basta cambiar el color.
            if (n.kind == TEXT && dark && n.color == 0xFF111827) textPaint.setColor(0xFFE5E7EB);
            c.save();
            float inset = n.kind == DECISION ? n.w * 0.22f : n.kind == DATA || n.kind == PREPARATION ? 28 : 12;
            float top = n.kind == NOTE || n.kind == TEXT ? n.y + 10 : n.cy() - l.getHeight() / 2f;
            if (n.kind == DATABASE) top += Math.min(n.h * 0.18f, 22) / 2;
            c.translate(n.x + inset, top);
            l.draw(c);
            c.restore();
        }

        private int mix(int a, int b, float k) {
            int r = Math.round(((a >> 16) & 0xFF) * (1 - k) + ((b >> 16) & 0xFF) * k);
            int g = Math.round(((a >> 8) & 0xFF) * (1 - k) + ((b >> 8) & 0xFF) * k);
            int bl = Math.round((a & 0xFF) * (1 - k) + (b & 0xFF) * k);
            return 0xFF000000 | r << 16 | g << 8 | bl;
        }

        /** Selección, asas, flechas «+», enlace en curso y guías. */
        private void drawOverlay(Canvas c) {
            float px = density / scale;
            if (!Float.isNaN(guideX) || !Float.isNaN(guideY)) {
                guide.setStrokeWidth(1.2f * px);
                guide.setPathEffect(new android.graphics.DashPathEffect(new float[] {6 * px, 4 * px}, 0));
                float l = docX(0), r = docX(getWidth()), tp = docY(0), b = docY(getHeight());
                if (!Float.isNaN(guideX)) c.drawLine(guideX, tp, guideX, b, guide);
                if (!Float.isNaN(guideY)) c.drawLine(l, guideY, r, guideY, guide);
                guide.setPathEffect(null);
            }
            if (mode == LINK && linkFrom != null) {
                float[] a = anchor(linkFrom, linkSide >= 0 ? linkSide : 1);
                stroke.setColor(0xFF2563EB);
                stroke.setStrokeWidth(2.4f * Math.max(1, px));
                stroke.setPathEffect(new android.graphics.DashPathEffect(new float[] {8 * px, 6 * px}, 0));
                c.drawLine(a[0], a[1], linkX, linkY, stroke);
                stroke.setPathEffect(null);
                if (hover != null) {
                    stroke.setStrokeWidth(3 * px);
                    c.drawPath(outline(hover.kind, hover.x - 4, hover.y - 4, hover.w + 8, hover.h + 8), stroke);
                }
            }
            Node n = selNode;
            if (n == null) return;
            stroke.setColor(0xFF2563EB);
            stroke.setStrokeWidth(1.6f * px);
            c.drawRect(n.x - 6 * px, n.y - 6 * px, n.x + n.w + 6 * px, n.y + n.h + 6 * px, stroke);
            accent.setColor(0xFFFFFFFF);
            float hr = 7 * px;
            c.drawCircle(n.x + n.w + 6 * px, n.y + n.h + 6 * px, hr, accent);
            accent.setColor(0xFF2563EB);
            stroke.setStrokeWidth(2 * px);
            c.drawCircle(n.x + n.w + 6 * px, n.y + n.h + 6 * px, hr, stroke);
            if (n.kind == NOTE || n.kind == TEXT || tool != SELECT) return;
            // Flechas para crear el paso siguiente.
            for (int side = 0; side < 4; side++) {
                float[] p = plusAt(n, side);
                accent.setColor(0xFF2563EB);
                c.drawCircle(p[0], p[1], 13 * px, accent);
                accent.setColor(0xFFFFFFFF);
                accent.setStrokeWidth(2.4f * px);
                accent.setStyle(Paint.Style.STROKE);
                c.drawLine(p[0] - 6 * px, p[1], p[0] + 6 * px, p[1], accent);
                c.drawLine(p[0], p[1] - 6 * px, p[0], p[1] + 6 * px, accent);
                accent.setStyle(Paint.Style.FILL);
            }
        }

        private float[] plusAt(Node n, int side) {
            float d = 34 * density / scale;
            float[] a = anchor(n, side);
            float[] dx = {0, 1, 0, -1}, dy = {-1, 0, 1, 0};
            return new float[] {a[0] + dx[side] * d, a[1] + dy[side] * d};
        }

        private Node nodeAt(float x, float y, float slop) {
            for (int i = nodes.size() - 1; i >= 0; i--) if (nodes.get(i).contains(x, y, slop)) return nodes.get(i);
            return null;
        }

        private Edge edgeAt(float x, float y) {
            float reach = 14 * density / scale;
            for (int i = edges.size() - 1; i >= 0; i--) {
                float[] pts = route(edges.get(i));
                if (pts != null && distance(pts, x, y) < reach) return edges.get(i);
            }
            return null;
        }

        private int nearestSide(Node n, float x, float y) {
            int best = 0;
            float bd = Float.MAX_VALUE;
            for (int s = 0; s < 4; s++) {
                float[] a = anchor(n, s);
                float d = (float) Math.hypot(a[0] - x, a[1] - y);
                if (d < bd) {
                    bd = d;
                    best = s;
                }
            }
            return best;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            scaler.onTouchEvent(e);
            float sx = e.getX(), sy = e.getY();
            float x = docX(sx), y = docY(sy);
            float slop = 6 * density / scale;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    downX = lastX = sx;
                    downY = lastY = sy;
                    moved = false;
                    changedThisGesture = false;
                    mode = NONE;
                    if (tool == PEN) {
                        remember();
                        drawing = new Scribble();
                        drawing.color = color == COLORS[0] ? 0xFF111827 : color;
                        drawing.width = penWidth;
                        drawing.style = penStyle;
                        if (penStyle == 3 && color == COLORS[0]) drawing.color = 0xFFFACC15;
                        drawing.add(x, y);
                        scribbles.add(drawing);
                        mode = DRAW;
                        return true;
                    }
                    if (tool == ERASE) {
                        remember();
                        mode = ERASING;
                        eraseAt(x, y);
                        return true;
                    }
                    Node n = selNode;
                    if (n != null && tool == SELECT) {
                        float hr = 22 * density / scale;
                        if (Math.hypot(x - (n.x + n.w), y - (n.y + n.h)) < hr) {
                            remember();
                            mode = RESIZE;
                            startW = n.w;
                            startH = n.h;
                            startX = x;
                            startY = y;
                            return true;
                        }
                        if (n.kind != NOTE && n.kind != TEXT) {
                            for (int side = 0; side < 4; side++) {
                                float[] p = plusAt(n, side);
                                if (Math.hypot(x - p[0], y - p[1]) < 22 * density / scale) {
                                    mode = LINK;
                                    linkFrom = n;
                                    linkSide = side;
                                    linkX = x;
                                    linkY = y;
                                    invalidate();
                                    return true;
                                }
                            }
                        }
                    }
                    Node hit = nodeAt(x, y, slop);
                    if (hit != null && tool == CONNECT) {
                        mode = LINK;
                        linkFrom = hit;
                        linkSide = -1;
                        linkX = x;
                        linkY = y;
                        return true;
                    }
                    if (hit != null) {
                        boolean again = hit == lastTapTarget && SystemClock.uptimeMillis() - lastTap < 350;
                        selNode = hit;
                        selEdge = null;
                        refreshTools();
                        if (again) {
                            lastTapTarget = null;
                            editText();
                            return true;
                        }
                        mode = MOVE;
                        startX = hit.x;
                        startY = hit.y;
                        invalidate();
                        return true;
                    }
                    Edge he = edgeAt(x, y);
                    if (he != null) {
                        boolean again = he == lastTapTarget && SystemClock.uptimeMillis() - lastTap < 350;
                        selEdge = he;
                        selNode = null;
                        refreshTools();
                        invalidate();
                        if (again) {
                            lastTapTarget = null;
                            editText();
                        } else {
                            lastTapTarget = he;
                            lastTap = SystemClock.uptimeMillis();
                        }
                        return true;
                    }
                    mode = PAN;
                    return true;
                }
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (mode == DRAW && drawing != null) {
                        scribbles.remove(drawing);
                        drawing = null;
                        if (!undo.isEmpty()) undo.remove(undo.size() - 1);
                    }
                    if (mode == MOVE && changedThisGesture && selNode != null) {
                        selNode.x = startX;
                        selNode.y = startY;
                    }
                    mode = PINCH;
                    guideX = guideY = Float.NaN;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (Math.hypot(sx - downX, sy - downY) > 8 * density) moved = true;
                    switch (mode) {
                        case PAN:
                            ox += sx - lastX;
                            oy += sy - lastY;
                            invalidate();
                            break;
                        case DRAW:
                            for (int h = 0; h < e.getHistorySize(); h++) drawing.add(docX(e.getHistoricalX(h)), docY(e.getHistoricalY(h)));
                            drawing.add(x, y);
                            invalidate();
                            break;
                        case ERASING:
                            eraseAt(x, y);
                            break;
                        case MOVE:
                            if (!moved) break;
                            if (!changedThisGesture) {
                                remember();
                                changedThisGesture = true;
                            }
                            moveSnapped(selNode, startX + (sx - downX) / scale, startY + (sy - downY) / scale);
                            invalidate();
                            break;
                        case RESIZE:
                            selNode.w = Math.max(40, snap(startW + x - startX));
                            selNode.h = Math.max(30, snap(startH + y - startY));
                            if (selNode.kind == CIRCLE) selNode.h = selNode.w;
                            invalidate();
                            break;
                        case LINK:
                            linkX = x;
                            linkY = y;
                            hover = nodeAt(x, y, 10 * density / scale);
                            if (hover == linkFrom) hover = null;
                            invalidate();
                            break;
                        default:
                    }
                    lastX = sx;
                    lastY = sy;
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    switch (mode) {
                        case PAN:
                            if (!moved) {
                                selNode = null;
                                selEdge = null;
                                refreshTools();
                                invalidate();
                            }
                            break;
                        case DRAW:
                            if (drawing != null && drawing.n < 2) scribbles.remove(drawing);
                            drawing = null;
                            changed();
                            break;
                        case ERASING:
                            changed();
                            break;
                        case MOVE:
                            guideX = guideY = Float.NaN;
                            if (changedThisGesture) changed();
                            else {
                                lastTapTarget = selNode;
                                lastTap = SystemClock.uptimeMillis();
                            }
                            break;
                        case RESIZE:
                            changed();
                            break;
                        case LINK:
                            finishLink(x, y);
                            break;
                        default:
                    }
                    mode = NONE;
                    hover = null;
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    mode = NONE;
                    guideX = guideY = Float.NaN;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        private void finishLink(float x, float y) {
            Node from = linkFrom;
            linkFrom = null;
            if (from == null) return;
            Node target = nodeAt(x, y, 10 * density / scale);
            if (target == from) target = null;
            remember();
            if (target != null) {
                int fs = linkSide >= 0 ? linkSide : -1;
                connect(from, target, fs, -1);
                selNode = target;
            } else if (!moved && linkSide >= 0) {
                // Un toque en «+»: el siguiente paso, igual que el actual.
                int kind = from.kind == TERMINAL || from.kind == DECISION || from.kind == CIRCLE ? PROCESS : from.kind;
                selNode = nextTo(from, linkSide, kind);
                Edge last = edges.get(edges.size() - 1);
                last.fromSide = linkSide;
                if (from.kind == DECISION) {
                    int out = 0;
                    for (Edge o : edges) if (o.from.equals(from.id)) out++;
                    last.text = out == 1 ? "Sí" : out == 2 ? "No" : "";
                }
            } else if (moved) {
                // Soltar en vacío: figura nueva ahí, conectada.
                int kind = from.kind == TERMINAL || from.kind == DECISION || from.kind == CIRCLE ? PROCESS : from.kind;
                Node n = newNode(kind, x, y);
                n.color = from.color;
                connect(from, n, linkSide, -1);
                selNode = n;
            } else {
                undo.remove(undo.size() - 1);
                return;
            }
            selEdge = null;
            refreshTools();
            changed();
            if (selNode != null) reveal(selNode);
        }

        /** Si la figura queda fuera de la pantalla, la vista se desliza hasta ella. */
        void reveal(Node n) {
            float m = 90 * density;
            float l = n.x * scale + ox, r = (n.x + n.w) * scale + ox, tp = n.y * scale + oy, b = (n.y + n.h) * scale + oy;
            float dx = 0, dy = 0;
            if (l < m + 60 * density) dx = m + 60 * density - l;
            else if (r > getWidth() - m) dx = getWidth() - m - r;
            if (tp < m) dy = m - tp;
            else if (b > getHeight() - m) dy = getHeight() - m - b;
            if (dx == 0 && dy == 0) return;
            float x0 = ox, y0 = oy, fdx = dx, fdy = dy;
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0, 1);
            a.setDuration(300);
            a.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
            a.addUpdateListener(v -> {
                float k = (float) v.getAnimatedValue();
                ox = x0 + fdx * k;
                oy = y0 + fdy * k;
                invalidate();
            });
            a.start();
        }

        /** Mover con la rejilla y pegarse a los centros de otras figuras (guías rosas). */
        private void moveSnapped(Node n, float nx, float ny) {
            nx = snap(nx + n.w / 2) - n.w / 2;
            ny = snap(ny + n.h / 2) - n.h / 2;
            guideX = guideY = Float.NaN;
            float reach = 10 * density / scale;
            for (Node o : nodes) {
                if (o == n) continue;
                if (Math.abs(nx + n.w / 2 - o.cx()) < reach) {
                    nx = o.cx() - n.w / 2;
                    guideX = o.cx();
                }
                if (Math.abs(ny + n.h / 2 - o.cy()) < reach) {
                    ny = o.cy() - n.h / 2;
                    guideY = o.cy();
                }
            }
            n.x = nx;
            n.y = ny;
        }

        private void eraseAt(float x, float y) {
            float r = 12 * density / scale;
            boolean any = scribbles.removeIf(s -> {
                for (int i = 0; i < s.n; i++) if (Math.hypot(s.pts[2 * i] - x, s.pts[2 * i + 1] - y) < r) return true;
                return false;
            });
            Edge e = edgeAt(x, y);
            if (e != null) {
                edges.remove(e);
                any = true;
            }
            if (!any) {
                Node n = nodeAt(x, y, 0);
                if (n != null) {
                    nodes.remove(n);
                    edges.removeIf(ed -> ed.from.equals(n.id) || ed.to.equals(n.id));
                    any = true;
                }
            }
            if (any) invalidate();
        }
    }

    /** Muestra de la figura para la paleta y los menús. */
    static final class ShapeIcon extends View {
        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;

        ShapeIcon(Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            this.color = color;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            float bw = Math.min(w - 12 * d, 36 * d), bh = kind == CIRCLE ? Math.min(bw, h - 16 * d) * 0.8f : Math.min(h - 16 * d, bw * 0.66f);
            if (kind == DECISION) bh = Math.min(h - 12 * d, bw * 0.8f);
            if (kind == CIRCLE) bw = bh;
            float x = (w - bw) / 2, y = (h - bh) / 2;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.8f * d);
            p.setColor(color);
            if (kind == TEXT) {
                p.setStyle(Paint.Style.FILL);
                p.setTextSize(18 * d);
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText("T", w / 2, h / 2 + 6 * d, p);
                return;
            }
            Path path = outline(kind, x, y, bw, bh);
            p.setStyle(Paint.Style.FILL);
            p.setColor(color & 0xFFFFFF | 0x1A000000);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(color);
            c.drawPath(path, p);
            if (kind == SUBPROCESS) {
                c.drawLine(x + 5 * d, y, x + 5 * d, y + bh, p);
                c.drawLine(x + bw - 5 * d, y, x + bw - 5 * d, y + bh, p);
            } else if (kind == DATABASE) {
                float e = Math.min(bh * 0.18f, 22);
                c.drawArc(new RectF(x, y, x + bw, y + 2 * e), 0, 180, false, p);
            }
        }
    }
}
