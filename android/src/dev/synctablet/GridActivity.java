package dev.synctablet;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.OverScroller;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Hoja de cálculo con GridCraft: todas sus fórmulas, formato, ordenar, filtros,
 * varias hojas y gráficas, y .xlsx de entrada y salida. Se toca para elegir, se
 * arrastra para moverse, se mantiene y arrastra para elegir varias celdas, y se
 * pellizca para acercar.
 */
public final class GridActivity extends Activity {
    private static final int SAVE_XLSX = 71, SAVE_CSV = 72;
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final float HEAD_W = 46, HEAD_H = 26;
    private static final String[] CHART_TYPES = {"column", "bar", "line", "pie", "doughnut", "area", "scatter", "radar", "waterfall", "histogram", "funnel", "treemap", "combo", "bubble", "boxWhisker", "sunburst"};
    private static final String[] CHART_NAMES = {"Columnas", "Barras", "Líneas", "Circular", "Anillo", "Área", "Dispersión", "Radar", "Cascada", "Histograma", "Embudo", "Rectángulos", "Combinada", "Burbujas", "Cajas y bigotes", "Proyección solar"};
    private static final String[] FORMATS = {"General", "Number", "Currency", "Accounting", "Percentage", "Short Date", "Long Date", "Time", "Fraction", "Scientific", "Text"};
    private static final String[] FORMAT_NAMES = {"General", "Número", "Moneda", "Contabilidad", "Porcentaje", "Fecha corta", "Fecha larga", "Hora", "Fracción", "Científica", "Texto"};
    private static final int[] SWATCHES = {0xFFFFFFFF, 0xFFFDE68A, 0xFFBBF7D0, 0xFFBFDBFE, 0xFFFBCFE8, 0xFFFED7AA, 0xFFE9D5FF, 0xFFE5E7EB,
            0xFF111827, 0xFFDC2626, 0xFF16A34A, 0xFF2563EB, 0xFF7C3AED, 0xFFEA580C, 0xFF0F766E, 0xFF6B7280};

    private NoteTheme t;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long book;
    private String id, title;
    private OptionDock dock;
    private GridCanvas grid;
    private EditText formula;
    private TextView cellName, titleView, state;
    private IconView undo, redo;
    private LinearLayout tabs;
    private String[] functions;
    private boolean dirty;
    private final Runnable autosave = this::saveNow;

    // Lo que se ve, traído del motor.
    private int viewR0 = -1, viewC0 = -1, viewRows, viewCols;
    private JSONArray cells;
    private final HashMap<Integer, Float> colW = new HashMap<>(), rowH = new HashMap<>();
    private JSONArray charts = new JSONArray();
    private JSONArray sheetNames = new JSONArray();
    private int activeSheet;
    // Selección: ancla y celda activa.
    private int ar, ac, sr, sc;
    private int pickedChart = -1;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        t = new NoteTheme(this);
        if (!Native.available()) {
            Ui.toast(this, "Las hojas de cálculo necesitan la librería nativa (tablet ARM de 64 bits)");
            finish();
            return;
        }
        id = getIntent().getStringExtra("id");
        SheetFiles.Doc doc = id == null ? null : SheetFiles.get(this, id);
        if (doc != null) {
            title = doc.title;
            try {
                book = Native.gridOpen(id + ".xlsx", Files.readAllBytes(SheetFiles.file(this, id).toPath()));
            } catch (java.io.IOException e) {
                book = 0;
            }
            if (book == 0) {
                Ui.toast(this, "No se pudo abrir la hoja");
                finish();
                return;
            }
        } else {
            id = Ink.newId();
            title = getIntent().getStringExtra("title");
            if (title == null || title.trim().isEmpty()) title = "Hoja de cálculo";
            book = Native.gridNew();
            String template = getIntent().getStringExtra("template");
            if (template != null) Templates.sheet(this, template);
            dirty = true;
            saveNow();
            String folder = getIntent().getStringExtra("folder");
            SheetFiles.Doc made = folder == null ? null : SheetFiles.get(this, id);
            if (made != null) {
                made.folder = folder;
                SheetFiles.update(this, made);
            }
        }
        build();
        refresh();
        select(0, 0, 0, 0);
        Tutorial.offerOnce(this, dock, "sheet");
    }

    /** Llamado por Templates: escribe un rango de valores en la hoja vacía. */
    void exec(String cmd, JSONObject params) {
        if (book == 0) return;
        String out = Native.gridExec(book, cmd, params.toString());
        if (out != null && out.contains("\"error\"")) android.util.Log.w("SyncTablet", cmd + ": " + out);
    }

    long book() {
        return book;
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(autosave);
        saveNow();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (book != 0) Native.gridClose(book);
        book = 0;
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) Ui.applyFullscreen(this);
    }

    // ---------------------------------------------------------------- pantalla

    private TextView label(String s, float sp, int color, boolean bold) {
        TextView v = Ui.text(this, s, sp, color, bold);
        v.setIncludeFontPadding(false);
        return v;
    }

    private IconView icon(int which, String label, View.OnClickListener click) {
        IconView v = new IconView(this, which, t.icon, label);
        v.setOnClickListener(click);
        return v;
    }

    @SuppressWarnings("deprecation")
    private void build() {
        getWindow().setStatusBarColor(t.bar);
        getWindow().setNavigationBarColor(t.bg);
        if (!t.dark) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        FrameLayout frame = new FrameLayout(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(t.bg);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));

        // Arriba: volver, nombre, deshacer, rehacer y más.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(t.dp(6), 0, t.dp(6), 0);
        top.setBackgroundColor(t.bar);
        top.addView(icon(IconView.BACK, "Volver", v -> finish()));
        titleView = label(title, 17, t.text, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setPadding(t.dp(6), 0, t.dp(6), 0);
        titleView.setOnClickListener(v -> rename());
        top.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
        state = label("", 13, t.faint, false);
        state.setPadding(t.dp(8), 0, t.dp(8), 0);
        top.addView(state);
        undo = icon(IconView.UNDO, "Deshacer", v -> run("edit.undo", "{}"));
        redo = icon(IconView.REDO, "Rehacer", v -> run("edit.redo", "{}"));
        top.addView(undo);
        top.addView(redo);
        top.addView(icon(IconView.CHART, "Insertar gráfica", v -> chartMenu()));
        top.addView(icon(IconView.HELP, "Tutorial de hojas de cálculo", v -> Tutorial.show(this, dock, "sheet")));
        top.addView(icon(IconView.MORE, "Más", v -> moreMenu()));
        root.addView(top, new LinearLayout.LayoutParams(-1, t.dp(54)));

        // La barra de fórmulas.
        LinearLayout fx = new LinearLayout(this);
        fx.setGravity(Gravity.CENTER_VERTICAL);
        fx.setPadding(t.dp(10), t.dp(4), t.dp(6), t.dp(4));
        cellName = label("A1", 14, t.muted, true);
        cellName.setGravity(Gravity.CENTER);
        cellName.setBackground(Ui.round(t.card, t.dp(8)));
        cellName.setOnClickListener(v -> goTo());
        fx.addView(cellName, new LinearLayout.LayoutParams(t.dp(76), t.dp(40)));
        TextView fxBtn = label("fx", 16, t.accent, true);
        fxBtn.setGravity(Gravity.CENTER);
        fxBtn.setContentDescription("Funciones");
        fxBtn.setOnClickListener(v -> functionMenu());
        fx.addView(fxBtn, new LinearLayout.LayoutParams(t.dp(44), t.dp(44)));
        formula = new EditText(this);
        formula.setSingleLine(true);
        formula.setTextSize(16);
        formula.setTextColor(t.text);
        formula.setHintTextColor(t.faint);
        formula.setHint("Escribe un valor o =SUMA(A1:A5)");
        formula.setBackground(Ui.round(t.card, t.dp(8)));
        formula.setPadding(t.dp(12), 0, t.dp(12), 0);
        formula.setImeOptions(EditorInfo.IME_ACTION_DONE);
        formula.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        formula.setOnEditorActionListener((v, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_DONE || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                commit(1, 0);
                return true;
            }
            return false;
        });
        fx.addView(formula, new LinearLayout.LayoutParams(0, t.dp(40), 1));
        IconView ok = icon(IconView.CHECK, "Aceptar", v -> commit(1, 0));
        fx.addView(ok);
        root.addView(fx, new LinearLayout.LayoutParams(-1, -2));

        // Cinta de herramientas.
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout ribbon = new LinearLayout(this);
        ribbon.setGravity(Gravity.CENTER_VERTICAL);
        ribbon.setPadding(t.dp(8), 0, t.dp(8), t.dp(6));
        hs.addView(ribbon);
        chip(ribbon, "B", "Negrita", () -> run("home.bold", "{}")).setTypeface(Typeface.DEFAULT_BOLD);
        chip(ribbon, "I", "Cursiva", () -> run("home.italic", "{}")).setTypeface(Typeface.defaultFromStyle(Typeface.ITALIC));
        chip(ribbon, "Relleno", "Color de relleno", () -> colorMenu(true));
        chip(ribbon, "Color", "Color del texto", () -> colorMenu(false));
        chip(ribbon, "⇤", "Alinear a la izquierda", () -> run("home.alignLeft", "{}"));
        chip(ribbon, "↔", "Centrar", () -> run("home.alignCenter", "{}"));
        chip(ribbon, "⇥", "Alinear a la derecha", () -> run("home.alignRight", "{}"));
        chip(ribbon, "123", "Formato de número", this::formatMenu);
        chip(ribbon, "%", "Porcentaje", () -> run("home.numberFormat", "{\"format\":\"Percentage\"}"));
        chip(ribbon, ".0", "Más decimales", () -> run("home.increaseDecimal", "{}"));
        chip(ribbon, ".00→.0", "Menos decimales", () -> run("home.decreaseDecimal", "{}"));
        chip(ribbon, "Σ", "Autosuma", () -> run("formulas.autoSum", "{\"function\":\"SUM\"}"));
        chip(ribbon, "A→Z", "Ordenar de menor a mayor", () -> run("data.sortAscending", "{}"));
        chip(ribbon, "Z→A", "Ordenar de mayor a menor", () -> run("data.sortDescending", "{}"));
        chip(ribbon, "Filtro", "Filtro", () -> run("data.filter", "{}"));
        chip(ribbon, "Rellenar ↓", "Rellenar hacia abajo", () -> run("edit.fillDown", "{}"));
        chip(ribbon, "Serie", "Rellenar serie", () -> run("edit.fillSeries", "{}"));
        chip(ribbon, "Combinar", "Combinar y centrar", () -> run("home.mergeCenter", "{}"));
        chip(ribbon, "+ Fila", "Insertar fila", () -> run("home.insertRows", "{}"));
        chip(ribbon, "+ Columna", "Insertar columna", () -> run("home.insertColumns", "{}"));
        chip(ribbon, "− Fila", "Borrar fila", () -> run("home.deleteRows", "{}"));
        chip(ribbon, "− Columna", "Borrar columna", () -> run("home.deleteColumns", "{}"));
        chip(ribbon, "Fijar fila 1", "Inmovilizar la fila de arriba", () -> run("view.freezeTopRow", "{}"));
        chip(ribbon, "Quitar duplicados", "Quitar duplicados", () -> run("data.removeDuplicates", "{}"));
        chip(ribbon, "Tabla", "Dar formato de tabla", () -> run("insert.table", "{}"));
        chip(ribbon, "Ajustar ancho", "Ajustar el ancho de la columna", () -> run("home.autofitColumnWidth", "{}"));
        chip(ribbon, "Borrar", "Borrar el contenido", () -> run("edit.clearContents", "{}"));
        chip(ribbon, "Fórmulas", "Ver fórmulas", () -> run("formulas.showFormulas", "{}"));
        root.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        grid = new GridCanvas();
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));

        HorizontalScrollView ts = new HorizontalScrollView(this);
        ts.setHorizontalScrollBarEnabled(false);
        ts.setBackgroundColor(t.bar);
        tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(t.dp(8), 0, t.dp(8), 0);
        ts.addView(tabs);
        root.addView(ts, new LinearLayout.LayoutParams(-1, t.dp(50)));

        root.setOnApplyWindowInsetsListener((v, in) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets s = in.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());
                v.setPadding(s.left, s.top, s.right, s.bottom);
                dock.setInsets(in.getInsets(android.view.WindowInsets.Type.systemBars()).bottom, in.getInsets(android.view.WindowInsets.Type.ime()).bottom);
            } else {
                v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(), in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
            }
            return in;
        });
        dock = new OptionDock(this, frame, null, t.dark ? 0xF7262626 : 0xF7FFFFFF, t.dark);
        setContentView(frame);
    }

    private TextView chip(LinearLayout parent, String text, String label, Runnable run) {
        TextView c = label(text, 14, t.text, false);
        c.setGravity(Gravity.CENTER);
        c.setPadding(t.dp(14), 0, t.dp(14), 0);
        c.setMinWidth(t.dp(44));
        c.setBackground(Ui.round(t.card, t.dp(10)));
        c.setContentDescription(label);
        c.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, t.dp(40));
        lp.rightMargin = t.dp(6);
        parent.addView(c, lp);
        return c;
    }

    // ---------------------------------------------------------------- motor

    static String col(int c) {
        StringBuilder s = new StringBuilder();
        for (c++; c > 0; c = (c - 1) / 26) s.insert(0, (char) ('A' + (c - 1) % 26));
        return s.toString();
    }

    private static String a1(int r, int c) {
        return col(c) + (r + 1);
    }

    private String range() {
        int r0 = Math.min(ar, sr), r1 = Math.max(ar, sr), c0 = Math.min(ac, sc), c1 = Math.max(ac, sc);
        return r0 == r1 && c0 == c1 ? a1(r0, c0) : a1(r0, c0) + ":" + a1(r1, c1);
    }

    /** Un comando sobre lo elegido; avisa si el motor no pudo. */
    private JSONObject run(String cmd, String params) {
        if (book == 0) return null;
        String out = Native.gridExec(book, cmd, params);
        JSONObject o = null;
        try {
            o = out == null ? null : new JSONObject(out);
        } catch (JSONException ignored) {
        }
        if (o == null || o.has("error")) {
            String why = o == null ? "" : o.optString("error");
            Ui.toast(this, why.isEmpty() ? "No se pudo" : "No se pudo: " + why);
            return null;
        }
        changed();
        return o;
    }

    private void changed() {
        dirty = true;
        state.setText("Sin guardar");
        main.removeCallbacks(autosave);
        main.postDelayed(autosave, 1500);
        viewR0 = -1;
        refresh();
        showCell();
    }

    private void saveNow() {
        if (!dirty || book == 0) return;
        byte[] bytes = Native.gridSave(book, "xlsx");
        if (bytes == null) {
            if (state != null) state.setText("No se pudo guardar");
            return;
        }
        try {
            SheetFiles.save(this, id, title, bytes);
            dirty = false;
            if (state != null) state.setText("Guardado");
        } catch (java.io.IOException e) {
            if (state != null) state.setText("No se pudo guardar");
        }
    }

    /** Trae del motor lo que hace falta para pintar lo visible (con margen). */
    private void refresh() {
        if (book == 0 || grid == null) return;
        int r0 = Math.max(0, grid.firstRow() - 10), c0 = Math.max(0, grid.firstCol() - 4);
        int rows = grid.visibleRows() + 30, cols = grid.visibleCols() + 12;
        if (viewR0 >= 0 && r0 >= viewR0 && c0 >= viewC0 && r0 + rows - 20 <= viewR0 + viewRows && c0 + cols - 8 <= viewC0 + viewCols) return;
        String json = Native.gridView(book, r0, c0, rows, cols);
        if (json == null) return;
        try {
            JSONObject o = new JSONObject(json);
            cells = o.getJSONArray("cells");
            JSONArray cw = o.getJSONArray("colW"), rh = o.getJSONArray("rowH");
            for (int i = 0; i < cw.length(); i++) colW.put(c0 + i, (float) cw.getDouble(i));
            for (int i = 0; i < rh.length(); i++) rowH.put(r0 + i, (float) rh.getDouble(i));
            viewR0 = r0;
            viewC0 = c0;
            viewRows = rows;
            viewCols = cols;
            sheetNames = o.getJSONArray("sheets");
            int act = o.getInt("active");
            if (act != activeSheet || tabs.getChildCount() == 0 || tabs.getChildCount() != sheetNames.length() + 1) {
                activeSheet = act;
                paintTabs();
            }
            undo.setEnabled(o.optBoolean("canUndo"));
            redo.setEnabled(o.optBoolean("canRedo"));
            String ch = Native.gridCharts(book);
            charts = ch == null ? new JSONArray() : new JSONArray(ch);
        } catch (JSONException e) {
            android.util.Log.w("SyncTablet", "vista de la hoja", e);
        }
        grid.invalidate();
    }

    private JSONObject cell(int r, int c) {
        if (cells == null || r < viewR0 || c < viewC0 || r >= viewR0 + viewRows || c >= viewC0 + viewCols) return null;
        JSONArray line = cells.optJSONArray(r - viewR0);
        return line == null ? null : line.optJSONObject(c - viewC0);
    }

    private float colWidth(int c) {
        Float w = colW.get(c);
        return w == null ? 64 : w;
    }

    private float rowHeight(int r) {
        Float h = rowH.get(r);
        return h == null ? 20 : h;
    }

    private float colLeft(int c) {
        float x = c * 64f;
        for (java.util.Map.Entry<Integer, Float> e : colW.entrySet()) if (e.getKey() < c) x += e.getValue() - 64f;
        return x;
    }

    private float rowTop(int r) {
        float y = r * 20f;
        for (java.util.Map.Entry<Integer, Float> e : rowH.entrySet()) if (e.getKey() < r) y += e.getValue() - 20f;
        return y;
    }

    private int colAt(float x) {
        int c = Math.max(0, (int) (x / 64f) - 8);
        while (colLeft(c + 1) <= x) c++;
        while (c > 0 && colLeft(c) > x) c--;
        return c;
    }

    private int rowAt(float y) {
        int r = Math.max(0, (int) (y / 20f) - 20);
        while (rowTop(r + 1) <= y) r++;
        while (r > 0 && rowTop(r) > y) r--;
        return r;
    }

    // ---------------------------------------------------------------- elegir y escribir

    private void select(int r0, int c0, int r1, int c1) {
        ar = r0;
        ac = c0;
        sr = r1;
        sc = c1;
        pickedChart = -1;
        String range = range();
        if (book != 0) Native.gridExec(book, "selection.set", "{\"range\":\"" + range + "\",\"active\":\"" + a1(r0, c0) + "\"}");
        showCell();
        grid.invalidate();
    }

    private void showCell() {
        cellName.setText(range());
        String edit = book == 0 ? "" : Native.gridEdit(book, ar, ac);
        formula.setText(edit == null ? "" : edit);
    }

    /** Escribe lo de la barra en la celda y pasa a la siguiente (dr, dc). */
    private void commit(int dr, int dc) {
        String input = formula.getText().toString();
        String before = Native.gridEdit(book, ar, ac);
        if (!input.equals(before == null ? "" : before)) {
            try {
                run("cell.set", new JSONObject().put("cell", a1(ar, ac)).put("input", input).toString());
            } catch (JSONException ignored) {
            }
        }
        int r = Math.max(0, ar + dr), c = Math.max(0, ac + dc);
        select(r, c, r, c);
        grid.reveal(r, c);
    }

    private void goTo() {
        EditText e = dock.field("", "B12 o C3:F20");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        dock.show("Ir a", e, new OptionDock.Action("Cancelar", false, null), new OptionDock.Action("Ir", true, () -> {
            int[] a = parse(e.getText().toString().trim().toUpperCase(Locale.ROOT));
            if (a == null) {
                Ui.toast(this, "Escribe una celda como B12");
                return;
            }
            select(a[0], a[1], a[2], a[3]);
            grid.reveal(a[0], a[1]);
        }));
        dock.focus(e);
    }

    /** "B2" o "B2:D5" → {r0, c0, r1, c1}. */
    private static int[] parse(String s) {
        String[] parts = s.split(":");
        int[] out = new int[4];
        for (int k = 0; k < Math.min(2, parts.length); k++) {
            String p = parts[k];
            int i = 0, c = 0;
            while (i < p.length() && Character.isLetter(p.charAt(i))) c = c * 26 + (p.charAt(i++) - 'A' + 1);
            if (c == 0 || i == p.length()) return null;
            int r;
            try {
                r = Integer.parseInt(p.substring(i));
            } catch (NumberFormatException e) {
                return null;
            }
            out[k * 2] = r - 1;
            out[k * 2 + 1] = c - 1;
        }
        if (parts.length == 1) {
            out[2] = out[0];
            out[3] = out[1];
        }
        return out;
    }

    // ---------------------------------------------------------------- docks

    private void functionMenu() {
        if (functions == null) {
            String all = Native.gridFunctions();
            functions = all == null ? new String[0] : all.split(",");
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(t.dp(16), 0, t.dp(16), t.dp(8));
        EditText search = dock.field("", "Buscar entre " + functions.length + " funciones: SUMA, BUSCARV, SI…");
        box.addView(search, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        box.addView(list);
        Runnable fill = () -> {
            list.removeAllViews();
            String q = search.getText().toString().trim().toUpperCase(Locale.ROOT);
            String[] common = {"SUM", "AVERAGE", "IF", "COUNT", "MAX", "MIN", "ROUND", "VLOOKUP", "XLOOKUP", "COUNTIF", "SUMIF", "TODAY", "CONCAT", "STDEV.S", "MEDIAN"};
            ArrayList<String> show = new ArrayList<>();
            if (q.isEmpty()) {
                java.util.Collections.addAll(show, common);
            } else {
                for (String f : functions) if (f.startsWith(q)) show.add(f);
                for (String f : functions) if (!f.startsWith(q) && f.contains(q)) show.add(f);
            }
            for (int i = 0; i < Math.min(40, show.size()); i++) {
                String f = show.get(i);
                list.addView(dock.row(f + "( )", v -> {
                    dock.close();
                    String cur = formula.getText().toString();
                    formula.setText(cur.isEmpty() || !cur.startsWith("=") ? "=" + f + "(" : cur + f + "(");
                    formula.setSelection(formula.getText().length());
                    formula.requestFocus();
                    android.view.inputmethod.InputMethodManager im = getSystemService(android.view.inputmethod.InputMethodManager.class);
                    if (im != null) im.showSoftInput(formula, 0);
                }));
            }
            if (show.isEmpty()) list.addView(dock.message("Ninguna función se llama así"));
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) { fill.run(); }
        });
        fill.run();
        dock.show("Funciones", box, new OptionDock.Action("Cerrar", false, null));
    }

    private void chartMenu() {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < CHART_TYPES.length; i++) {
            String type = CHART_TYPES[i];
            tiles.add(new OptionDock.Tile(new ChartIcon(type), CHART_NAMES[i], false, () -> insertChart(type)));
        }
        dock.tiles("Gráfica con " + range(), tiles);
    }

    private void insertChart(String type) {
        int c1 = Math.max(ac, sc), r0 = Math.min(ar, sr);
        try {
            JSONObject p = new JSONObject().put("range", range()).put("type", type).put("at", a1(r0, c1 + 2)).put("width", 440).put("height", 270);
            if (run("insert.chart", p.toString()) == null) Ui.toast(this, "Elige primero los datos: una columna de nombres y otra de números");
        } catch (JSONException ignored) {
        }
    }

    private void chartOptions(int index) {
        JSONObject ch = charts.optJSONObject(index);
        if (ch == null) return;
        int chartId = ch.optInt("id");
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < CHART_TYPES.length; i++) {
            String type = CHART_TYPES[i];
            tiles.add(new OptionDock.Tile(new ChartIcon(type), CHART_NAMES[i], false, () -> run("chart.changeType", "{\"chart\":" + chartId + ",\"type\":\"" + type + "\"}")));
        }
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.PAGES), "Poner en un cuaderno o presentación", false, () -> sendToNotebook(chartImage(ch))).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.TRASH), "Borrar gráfica", false, () -> {
            run("chart.delete", "{\"chart\":" + chartId + "}");
            pickedChart = -1;
        }));
        dock.tiles("Gráfica", tiles);
    }

    // ---- a un cuaderno ----

    /** La gráfica como imagen, al doble de tamaño para que se vea nítida en la hoja. */
    private android.graphics.Bitmap chartImage(JSONObject ch) {
        float w = (float) ch.optDouble("w"), h = (float) ch.optDouble("h"), k = 2.5f;
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(Math.round(w * k), Math.round(h * k), android.graphics.Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFFFFFFFF);
        Canvas c = new Canvas(b);
        c.scale(k, k);
        drawPrims(c, ch.optJSONArray("prims") == null ? new JSONArray() : ch.optJSONArray("prims"), new Paint(Paint.ANTI_ALIAS_FLAG), new Path());
        return b;
    }

    /** Las celdas elegidas como una tabla dibujada. */
    private android.graphics.Bitmap rangeImage() {
        int r0 = Math.min(ar, sr), r1 = Math.min(Math.max(ar, sr), r0 + 60), c0 = Math.min(ac, sc), c1 = Math.min(Math.max(ac, sc), c0 + 15);
        int rows = r1 - r0 + 1, cols = c1 - c0 + 1;
        JSONObject o;
        try {
            String json = Native.gridView(book, r0, c0, rows, cols);
            o = new JSONObject(json == null ? "{}" : json);
        } catch (JSONException e) {
            return null;
        }
        JSONArray cellsIn = o.optJSONArray("cells"), cw = o.optJSONArray("colW"), rh = o.optJSONArray("rowH");
        if (cellsIn == null || cw == null || rh == null) return null;
        float k = 2.5f, w = 0, h = 0;
        for (int i = 0; i < cols; i++) w += (float) cw.optDouble(i, 64);
        for (int i = 0; i < rows; i++) h += (float) rh.optDouble(i, 20);
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), android.graphics.Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFFFFFFFF);
        Canvas c = new Canvas(b);
        c.scale(k, k);
        Paint line = new Paint();
        line.setColor(0xFFBFC5CC);
        line.setStrokeWidth(0.6f);
        Paint fillP = new Paint();
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTextSize(11);
        float y = 0;
        for (int r = 0; r < rows; r++) {
            float x = 0, ch = (float) rh.optDouble(r, 20);
            JSONArray line0 = cellsIn.optJSONArray(r);
            for (int col = 0; col < cols; col++) {
                float cwid = (float) cw.optDouble(col, 64);
                JSONObject cell = line0 == null ? null : line0.optJSONObject(col);
                if (cell != null && cell.has("bg")) {
                    fillP.setColor(Ink.parseColor(cell.optString("bg"), 0xFFFFFFFF));
                    c.drawRect(x, y, x + cwid, y + ch, fillP);
                }
                if (cell != null && cell.has("t")) {
                    String t0 = cell.optString("t");
                    text.setTypeface(cell.optBoolean("b") ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                    text.setColor(cell.has("fc") ? Ink.parseColor(cell.optString("fc"), 0xFF111827) : 0xFF111827);
                    String a = cell.optString("a", "l");
                    float tw = text.measureText(t0);
                    float tx = "r".equals(a) ? x + cwid - 3 - tw : "c".equals(a) ? x + (cwid - tw) / 2 : x + 3;
                    c.save();
                    c.clipRect(x, y, x + cwid, y + ch);
                    c.drawText(t0, tx, y + ch / 2 - (text.ascent() + text.descent()) / 2, text);
                    c.restore();
                }
                c.drawRect(x, y, x + cwid, y + ch, outline(line));
                x += cwid;
            }
            y += ch;
        }
        return b;
    }

    private static Paint outline(Paint p) {
        p.setStyle(Paint.Style.STROKE);
        return p;
    }

    /** Elegir cuaderno: la imagen va a una página nueva al final. */
    private void sendToNotebook(android.graphics.Bitmap img) {
        if (img == null) {
            Ui.toast(this, "No hay nada que poner");
            return;
        }
        Store store = Store.get(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(t.dp(16), 0, t.dp(16), t.dp(8));
        for (Ink.Notebook n : store.notebooks()) {
            box.addView(dock.row("📓  " + n.title + (n.folder.isEmpty() ? "" : "  ·  " + n.folder), v -> {
                dock.close();
                putInNotebook(store, n, img);
            }));
        }
        for (DocActivity.Info d : DocActivity.list(this)) {
            box.addView(dock.row("📄  " + d.title + " (documento)", v -> {
                dock.close();
                boolean ok = DocActivity.appendImage(this, d.id, img);
                Ui.toast(this, ok ? "Puesta al final de «" + d.title + "»" : "No se pudo poner en el documento");
            }));
        }
        for (DeckActivity.Info d : DeckActivity.list(this)) {
            box.addView(dock.row("🖥️  " + d.title + " (presentación)", v -> {
                dock.close();
                boolean ok = DeckActivity.addImageSlide(this, d.id, img, title);
                Ui.toast(this, ok ? "Diapositiva nueva en «" + d.title + "»" : "No se pudo poner en la presentación");
            }));
        }
        if (box.getChildCount() == 0) box.addView(dock.message("Aún no hay cuadernos ni presentaciones: crea uno en Inicio"));
        dock.show("¿Dónde la pongo?", box, new OptionDock.Action("Cancelar", false, null));
    }

    private void putInNotebook(Store store, Ink.Notebook n, android.graphics.Bitmap img) {
        try {
            String asset = store.putAsset(img);
            Ink.Page p = store.addPage(n);
            Ink.Item it = new Ink.Item(Ink.newId(), "image");
            it.asset = asset;
            it.w = Math.min(860, img.getWidth() / 2.5f * 2f);
            it.h = it.w * img.getHeight() / img.getWidth();
            if (it.h > Ink.H - 160) {
                it.h = Ink.H - 160;
                it.w = it.h * img.getWidth() / img.getHeight();
            }
            it.x = (Ink.W - it.w) / 2;
            it.y = 80;
            p.items.add(it);
            p.version++;
            n.updated = System.currentTimeMillis();
            store.savePage(p);
            store.saveNotebooks();
            SyncClient sync = SyncClient.get(this);
            sync.sendAsset(asset);
            sync.sendPage(p);
            sync.sendNotebook(n);
            Ui.toast(this, "Puesta en «" + n.title + "», página " + n.pages.size());
        } catch (java.io.IOException e) {
            Ui.toast(this, "No se pudo guardar la imagen");
        }
    }

    private void formatMenu() {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        String[] samples = {"1234,5", "1.234,50", "$1.234", "$ 1.234", "12 %", "10/11/26", "10 nov 2026", "13:45", "½", "1,2E+03", "Abc"};
        for (int i = 0; i < FORMATS.length; i++) {
            String f = FORMATS[i];
            tiles.add(new OptionDock.Tile(dock.letter(samples[i]), FORMAT_NAMES[i], false, () -> run("home.numberFormat", "{\"format\":\"" + f + "\"}")));
        }
        dock.tiles("Formato de número", tiles);
    }

    private void colorMenu(boolean fill) {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        if (fill) tiles.add(new OptionDock.Tile(dock.glyph(IconView.ERASER), "Sin relleno", false, () -> run("home.fillColor", "{\"color\":\"none\"}")));
        else tiles.add(new OptionDock.Tile(dock.glyph(IconView.TEXT), "Automático", false, () -> run("home.fontColor", "{\"color\":\"auto\"}")));
        for (int c : SWATCHES) {
            String hex = Ink.hex(c);
            tiles.add(new OptionDock.Tile(dock.swatch(c), "", false, () -> run(fill ? "home.fillColor" : "home.fontColor", "{\"color\":\"" + hex + "\"}")));
        }
        dock.tiles(fill ? "Color de relleno" : "Color del texto", tiles);
    }

    private void moreMenu() {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.EXPORT), "Guardar como Excel", false, () -> create(XLSX, ".xlsx", SAVE_XLSX)));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.FILE), "Exportar CSV", false, () -> create("text/csv", ".csv", SAVE_CSV)));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.PAGES), "Poner lo elegido en un cuaderno", false, () -> sendToNotebook(rangeImage())).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.TEXT), "Renombrar", false, this::rename).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.SHEET), "Hoja nueva", false, () -> run("home.insertSheet", "{}")));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.CHART), "Gráfica", false, this::chartMenu).stays());
        tiles.add(new OptionDock.Tile(dock.letter("fx"), "Funciones", false, this::functionMenu).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.SEARCH), "Ir a una celda", false, this::goTo).stays());
        boolean full = Ui.fullscreen(this);
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.EXPAND), full ? "Salir de pantalla completa" : "Pantalla completa", full, () -> Ui.setFullscreen(this, !full)));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.HELP), "Tutorial", false, () -> Tutorial.show(this, dock, "sheet")).stays());
        dock.tiles(title, tiles);
    }

    private void rename() {
        EditText e = dock.field(title, "Nombre");
        dock.show("Renombrar", e, new OptionDock.Action("Cancelar", false, null), new OptionDock.Action("Guardar", true, () -> {
            String s = e.getText().toString().trim();
            if (s.isEmpty()) return;
            title = s;
            titleView.setText(s);
            dirty = true;
            saveNow();
        }));
        dock.focus(e);
    }

    private void create(String mime, String ext, int request) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
                .putExtra(Intent.EXTRA_TITLE, title.replaceAll("[\\\\/:*?\"<>|]", "-") + ext);
        startActivityForResult(i, request);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        byte[] bytes = Native.gridSave(book, request == SAVE_CSV ? "csv" : "xlsx");
        if (bytes == null) {
            Ui.toast(this, "No se pudo exportar");
            return;
        }
        try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
            out.write(bytes);
            Ui.toast(this, "Exportado");
        } catch (java.io.IOException | RuntimeException e) {
            Ui.toast(this, "No se pudo escribir el archivo");
        }
    }

    private void paintTabs() {
        tabs.removeAllViews();
        for (int i = 0; i < sheetNames.length(); i++) {
            int index = i;
            String name = sheetNames.optString(i);
            TextView tab = label(name, 14, i == activeSheet ? t.text : t.muted, i == activeSheet);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(t.dp(16), 0, t.dp(16), 0);
            tab.setBackground(Ui.round(i == activeSheet ? t.card : 0, t.dp(10)));
            tab.setOnClickListener(v -> {
                run("sheet.activate", "{\"sheet\":" + index + "}");
                select(0, 0, 0, 0);
                grid.scrollTo(0, 0);
            });
            tab.setOnLongClickListener(v -> {
                sheetMenu(index, name);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, t.dp(38));
            lp.rightMargin = t.dp(4);
            tabs.addView(tab, lp);
        }
        TextView add = label("+", 20, t.muted, false);
        add.setGravity(Gravity.CENTER);
        add.setContentDescription("Hoja nueva");
        add.setOnClickListener(v -> run("home.insertSheet", "{}"));
        tabs.addView(add, new LinearLayout.LayoutParams(t.dp(44), t.dp(44)));
    }

    private void sheetMenu(int index, String name) {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.TEXT), "Renombrar", false, () -> {
            EditText e = dock.field(name, "Nombre de la hoja");
            dock.show("Renombrar hoja", e, new OptionDock.Action("Cancelar", false, null), new OptionDock.Action("Guardar", true, () -> {
                try {
                    run("sheet.rename", new JSONObject().put("sheet", index).put("name", e.getText().toString().trim()).toString());
                } catch (JSONException ignored) {
                }
            }));
            dock.focus(e);
        }).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.COPY), "Duplicar", false, () -> run("sheet.move", "{\"sheet\":" + index + ",\"to\":" + (index + 1) + ",\"copy\":true}")));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.TRASH), "Borrar hoja", false, () ->
                dock.confirm("¿Borrar «" + name + "»?", "Se puede deshacer.", "Borrar", () -> run("home.deleteSheet", "{\"sheet\":" + index + "}"))).stays());
        dock.tiles(name, tiles);
    }

    // ---------------------------------------------------------------- dibujar gráficas

    /** Pinta los trazos de gridcraft-chart en un lienzo de Android. */
    static void drawPrims(Canvas c, JSONArray prims, Paint p, Path path) {
        for (int i = 0; i < prims.length(); i++) {
            JSONObject o = prims.optJSONObject(i);
            if (o == null || o.length() == 0) continue;
            String kind = o.keys().next();
            JSONObject v = o.optJSONObject(kind);
            if (v == null) continue;
            switch (kind) {
                case "Rect": {
                    float x = (float) v.optDouble("x"), y = (float) v.optDouble("y"), w = (float) v.optDouble("w"), h = (float) v.optDouble("h"), r = (float) v.optDouble("radius", 0);
                    if (fill(p, v.optJSONArray("fill"))) c.drawRoundRect(x, y, x + w, y + h, r, r, p);
                    if (stroke(p, v.optJSONArray("stroke"))) c.drawRoundRect(x, y, x + w, y + h, r, r, p);
                    break;
                }
                case "Line": {
                    points(path, v.optJSONArray("pts"), false);
                    p.setStyle(Paint.Style.STROKE);
                    p.setColor(rgba(v.optJSONArray("color")));
                    p.setStrokeWidth((float) v.optDouble("width", 1));
                    p.setPathEffect(v.optBoolean("dash") ? new android.graphics.DashPathEffect(new float[] {3, 2}, 0) : null);
                    c.drawPath(path, p);
                    p.setPathEffect(null);
                    break;
                }
                case "Polygon": {
                    points(path, v.optJSONArray("pts"), true);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(rgba(v.optJSONArray("fill")));
                    c.drawPath(path, p);
                    if (stroke(p, v.optJSONArray("stroke"))) c.drawPath(path, p);
                    break;
                }
                case "Wedge": {
                    float cx = (float) v.optDouble("cx"), cy = (float) v.optDouble("cy"), ro = (float) v.optDouble("r_outer"), ri = (float) v.optDouble("r_inner");
                    float a0 = (float) Math.toDegrees(v.optDouble("a0")) - 90, a1 = (float) Math.toDegrees(v.optDouble("a1")) - 90;
                    path.reset();
                    path.arcTo(new RectF(cx - ro, cy - ro, cx + ro, cy + ro), a0, a1 - a0, true);
                    if (ri > 0) path.arcTo(new RectF(cx - ri, cy - ri, cx + ri, cy + ri), a1, a0 - a1);
                    else path.lineTo(cx, cy);
                    path.close();
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(rgba(v.optJSONArray("fill")));
                    c.drawPath(path, p);
                    if (stroke(p, v.optJSONArray("stroke"))) c.drawPath(path, p);
                    break;
                }
                case "Circle": {
                    float cx = (float) v.optDouble("cx"), cy = (float) v.optDouble("cy"), r = (float) v.optDouble("r");
                    if (fill(p, v.optJSONArray("fill"))) c.drawCircle(cx, cy, r, p);
                    if (stroke(p, v.optJSONArray("stroke"))) c.drawCircle(cx, cy, r, p);
                    break;
                }
                case "Text": {
                    String s = v.optString("text");
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(rgba(v.optJSONArray("color")));
                    p.setTextSize((float) v.optDouble("size", 10));
                    p.setTypeface(v.optBoolean("bold") ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                    String align = v.optString("align", "Left"), valign = v.optString("valign", "Middle");
                    p.setTextAlign("Center".equals(align) ? Paint.Align.CENTER : "Right".equals(align) ? Paint.Align.RIGHT : Paint.Align.LEFT);
                    Paint.FontMetrics fm = p.getFontMetrics();
                    float dy = "Top".equals(valign) ? -fm.ascent : "Bottom".equals(valign) ? -fm.descent : -(fm.ascent + fm.descent) / 2;
                    float x = (float) v.optDouble("x"), y = (float) v.optDouble("y"), rot = (float) v.optDouble("rotation", 0);
                    c.save();
                    c.translate(x, y);
                    if (rot != 0) c.rotate((float) Math.toDegrees(rot));
                    c.drawText(s, 0, dy, p);
                    c.restore();
                    p.setTextAlign(Paint.Align.LEFT);
                    break;
                }
                default:
                    break;
            }
        }
    }

    private static int rgba(JSONArray a) {
        if (a == null || a.length() < 3) return 0xFF000000;
        int alpha = a.length() > 3 ? a.optInt(3, 255) : 255;
        return alpha << 24 | a.optInt(0) << 16 | a.optInt(1) << 8 | a.optInt(2);
    }

    private static boolean fill(Paint p, JSONArray color) {
        if (color == null) return false;
        p.setStyle(Paint.Style.FILL);
        p.setColor(rgba(color));
        return true;
    }

    /** `stroke` es [color, ancho]. */
    private static boolean stroke(Paint p, JSONArray s) {
        if (s == null || s.length() < 2) return false;
        p.setStyle(Paint.Style.STROKE);
        p.setColor(rgba(s.optJSONArray(0)));
        p.setStrokeWidth((float) s.optDouble(1, 1));
        return true;
    }

    private static void points(Path path, JSONArray pts, boolean close) {
        path.reset();
        if (pts == null) return;
        for (int i = 0; i < pts.length(); i++) {
            JSONArray q = pts.optJSONArray(i);
            if (q == null) continue;
            float x = (float) q.optDouble(0), y = (float) q.optDouble(1);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        if (close) path.close();
    }

    /** Dibujito de cada tipo de gráfica para el dock. */
    private final class ChartIcon extends View {
        private final String type;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        ChartIcon(String type) {
            super(GridActivity.this);
            this.type = type;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h) * 0.7f, x0 = (w - s) / 2, y0 = (h - s) / 2;
            int[] colors = {0xFF2563EB, 0xFF0F766E, 0xFFEA580C, 0xFF7C3AED};
            p.setStrokeWidth(t.px(2.5f));
            p.setStrokeCap(Paint.Cap.ROUND);
            float[] v = {0.55f, 0.85f, 0.4f, 0.7f};
            switch (type) {
                case "pie":
                case "doughnut":
                case "sunburst": {
                    float a = -90;
                    float[] parts = {140, 100, 120};
                    for (int i = 0; i < 3; i++) {
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(colors[i]);
                        c.drawArc(x0, y0, x0 + s, y0 + s, a, parts[i], true, p);
                        a += parts[i];
                    }
                    if (!"pie".equals(type)) {
                        p.setColor(t.dark ? 0xFF262626 : 0xFFFFFFFF);
                        c.drawCircle(w / 2, h / 2, s * 0.24f, p);
                    }
                    break;
                }
                case "line":
                case "area":
                case "radar":
                case "scatter":
                case "bubble": {
                    Path path = new Path();
                    for (int i = 0; i < 4; i++) {
                        float x = x0 + s * i / 3f, y = y0 + s * (1 - v[i]);
                        if (i == 0) path.moveTo(x, y);
                        else path.lineTo(x, y);
                        if (!"line".equals(type) && !"area".equals(type)) {
                            p.setStyle(Paint.Style.FILL);
                            p.setColor(colors[i % 4]);
                            c.drawCircle(x, y, "bubble".equals(type) ? t.px(3 + 2 * i) : t.px(3.5f), p);
                        }
                    }
                    if ("area".equals(type)) {
                        path.lineTo(x0 + s, y0 + s);
                        path.lineTo(x0, y0 + s);
                        path.close();
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(0x992563EB);
                        c.drawPath(path, p);
                    } else if ("line".equals(type) || "radar".equals(type)) {
                        p.setStyle(Paint.Style.STROKE);
                        p.setColor(colors[0]);
                        c.drawPath(path, p);
                    }
                    break;
                }
                default: {
                    boolean bars = "bar".equals(type) || "funnel".equals(type);
                    for (int i = 0; i < 4; i++) {
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(colors["combo".equals(type) || "treemap".equals(type) ? i : 0]);
                        float k = "funnel".equals(type) ? 1 - i * 0.22f : "waterfall".equals(type) ? 0.3f + 0.2f * i : v[i];
                        if (bars) {
                            float y = y0 + s * i / 4f, len = s * k;
                            float x = "funnel".equals(type) ? x0 + (s - len) / 2 : x0;
                            c.drawRoundRect(x, y + 2, x + len, y + s / 4f - 2, 3, 3, p);
                        } else {
                            float x = x0 + s * i / 4f, top = y0 + s * (1 - k);
                            float bottom = "waterfall".equals(type) ? top + s * 0.2f : y0 + s;
                            c.drawRoundRect(x + 2, top, x + s / 4f - 2, bottom, 3, 3, p);
                        }
                    }
                    if ("combo".equals(type)) {
                        p.setStyle(Paint.Style.STROKE);
                        p.setColor(colors[2]);
                        c.drawLine(x0, y0 + s * 0.5f, x0 + s, y0 + s * 0.2f, p);
                    }
                }
            }
        }
    }

    /** Un número que no cabe: menos decimales y, si aun así no cabe, ### (como Excel). */
    static String fit(String s, float room, Paint p) {
        if (s.matches("-?\\d+\\.\\d+")) {
            java.math.BigDecimal v = new java.math.BigDecimal(s);
            for (int d = s.length() - s.indexOf('.') - 2; d >= 0; d--) {
                String r = v.setScale(d, java.math.RoundingMode.HALF_UP).toPlainString();
                if (p.measureText(r) <= room) return r;
            }
        }
        if (!s.matches(".*\\d.*") || s.matches(".*[A-Za-z]{2,}.*")) return s;
        StringBuilder h = new StringBuilder("#");
        while (p.measureText(h.toString() + "#") <= room) h.append('#');
        return h.toString();
    }

    // ---------------------------------------------------------------- la cuadrícula

    private final class GridCanvas extends View {
        private float zoom = 1f, sx, sy;
        private final Paint fillP = new Paint(), lineP = new Paint(), textP = new Paint(Paint.ANTI_ALIAS_FLAG), headP = new Paint(Paint.ANTI_ALIAS_FLAG), chartP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final OverScroller scroller;
        private final GestureDetector gestures;
        private final ScaleGestureDetector pinch;
        private boolean extending;

        GridCanvas() {
            super(GridActivity.this);
            setBackgroundColor(t.dark ? 0xFF1E1E1E : 0xFFFFFFFF);
            lineP.setColor(t.dark ? 0xFF3A3A3A : 0xFFD4D4D8);
            lineP.setStrokeWidth(1);
            headP.setTextAlign(Paint.Align.CENTER);
            headP.setColor(t.muted);
            scroller = new OverScroller(getContext());
            gestures = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) {
                    scroller.forceFinished(true);
                    return true;
                }

                @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                    if (extending) return true;
                    scrollTo(sx + dx, sy + dy);
                    return true;
                }

                @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                    if (extending) return true;
                    scroller.fling((int) sx, (int) sy, (int) -vx, (int) -vy, 0, Integer.MAX_VALUE / 4, 0, Integer.MAX_VALUE / 4);
                    postInvalidateOnAnimation();
                    return true;
                }

                @Override public boolean onSingleTapUp(MotionEvent e) {
                    tap(e.getX(), e.getY(), false);
                    return true;
                }

                @Override public boolean onDoubleTap(MotionEvent e) {
                    tap(e.getX(), e.getY(), false);
                    formula.requestFocus();
                    formula.setSelection(formula.getText().length());
                    android.view.inputmethod.InputMethodManager im = getSystemService(android.view.inputmethod.InputMethodManager.class);
                    if (im != null) im.showSoftInput(formula, 0);
                    return true;
                }

                @Override public void onLongPress(MotionEvent e) {
                    int ch = chartAt(e.getX(), e.getY());
                    if (ch >= 0) {
                        pickedChart = ch;
                        invalidate();
                        chartOptions(ch);
                        return;
                    }
                    tap(e.getX(), e.getY(), false);
                    extending = true;
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                }
            });
            pinch = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    float before = zoom;
                    zoom = Math.max(0.4f, Math.min(3f, zoom * d.getScaleFactor()));
                    float k = zoom / before, fx = d.getFocusX() - headW(), fy = d.getFocusY() - headH();
                    scrollTo((sx + fx) * k - fx, (sy + fy) * k - fy);
                    return true;
                }
            });
        }

        float k() {
            return zoom * t.density * 1.1f;
        }

        float headW() {
            return t.dp(HEAD_W);
        }

        float headH() {
            return t.dp(HEAD_H);
        }

        int firstRow() {
            return rowAt(sy / k());
        }

        int firstCol() {
            return colAt(sx / k());
        }

        int visibleRows() {
            return (int) (Math.max(1, getHeight()) / (20 * k() * 0.6f)) + 2;
        }

        int visibleCols() {
            return (int) (Math.max(1, getWidth()) / (64 * k() * 0.4f)) + 2;
        }

        void scrollTo(float x, float y) {
            sx = Math.max(0, x);
            sy = Math.max(0, y);
            refresh();
            invalidate();
        }

        /** Que la celda (r, c) quede a la vista. */
        void reveal(int r, int c) {
            float k = k(), x = colLeft(c) * k, y = rowTop(r) * k, w = getWidth() - headW(), h = getHeight() - headH();
            float nx = sx, ny = sy;
            if (x < sx) nx = x;
            else if (x + colWidth(c) * k > sx + w) nx = x + colWidth(c) * k - w;
            if (y < sy) ny = y;
            else if (y + rowHeight(r) * k > sy + h) ny = y + rowHeight(r) * k - h;
            scrollTo(nx, ny);
        }

        private int[] cellAt(float x, float y) {
            float k = k();
            int c = x < headW() ? -1 : colAt((x - headW() + sx) / k);
            int r = y < headH() ? -1 : rowAt((y - headH() + sy) / k);
            return new int[] {r, c};
        }

        private int chartAt(float x, float y) {
            float k = k(), px = (x - headW() + sx) / k, py = (y - headH() + sy) / k;
            for (int i = charts.length() - 1; i >= 0; i--) {
                JSONObject ch = charts.optJSONObject(i);
                if (ch == null) continue;
                float cx = (float) ch.optDouble("x"), cy = (float) ch.optDouble("y");
                if (px >= cx && py >= cy && px <= cx + ch.optDouble("w") && py <= cy + ch.optDouble("h")) return i;
            }
            return -1;
        }

        private void tap(float x, float y, boolean extend) {
            if (formula.hasFocus()) {
                // Escribiendo una fórmula: tocar una celda la mete en la fórmula.
                String f = formula.getText().toString();
                if (f.startsWith("=") && !extend) {
                    int[] rc = cellAt(x, y);
                    if (rc[0] >= 0 && rc[1] >= 0) {
                        char last = f.isEmpty() ? '=' : f.charAt(f.length() - 1);
                        if ("=(+-*/;,:<>&^".indexOf(last) >= 0) {
                            formula.append(a1(rc[0], rc[1]));
                            return;
                        }
                    }
                }
                commit(0, 0);
            }
            int ch = chartAt(x, y);
            if (ch >= 0) {
                pickedChart = ch;
                invalidate();
                return;
            }
            int[] rc = cellAt(x, y);
            if (rc[0] < 0 && rc[1] < 0) return;
            if (rc[0] < 0) select(0, rc[1], 1048575, rc[1]);
            else if (rc[1] < 0) select(rc[0], 0, rc[0], 16383);
            else if (extend) select(ar, ac, rc[0], rc[1]);
            else select(rc[0], rc[1], rc[0], rc[1]);
        }

        /** Columna cuyo borde derecho se está arrastrando (−1: ninguna). */
        private int resizing = -1;
        private float resizeFrom, resizeStartW;

        /** ¿Toca el borde de una columna en la cabecera? */
        private int borderAt(float x, float y) {
            if (y > headH() || x < headW()) return -1;
            float k = k();
            int c = colAt((x - headW() + sx) / k);
            for (int cc = Math.max(0, c - 1); cc <= c + 1; cc++) {
                float right = headW() + colLeft(cc + 1) * k - sx;
                if (Math.abs(x - right) < t.dp(14)) return cc;
            }
            return -1;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                resizing = borderAt(e.getX(), e.getY());
                if (resizing >= 0) {
                    resizeFrom = e.getX();
                    resizeStartW = colWidth(resizing);
                    return true;
                }
            }
            if (resizing >= 0) {
                float w = Math.max(12, resizeStartW + (e.getX() - resizeFrom) / k());
                colW.put(resizing, w);
                invalidate();
                if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                    String col = col(resizing);
                    resizing = -1;
                    run("home.columnWidth", "{\"cols\":\"" + col + ":" + col + "\",\"width\":" + Math.round(w) + "}");
                }
                return true;
            }
            pinch.onTouchEvent(e);
            if (extending && e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                int[] rc = cellAt(e.getX(), e.getY());
                if (rc[0] >= 0 && rc[1] >= 0 && (rc[0] != sr || rc[1] != sc)) {
                    sr = rc[0];
                    sc = rc[1];
                    cellName.setText(range());
                    invalidate();
                }
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                if (extending) {
                    extending = false;
                    select(ar, ac, sr, sc);
                }
            }
            if (!pinch.isInProgress()) gestures.onTouchEvent(e);
            return true;
        }

        @Override
        public void computeScroll() {
            if (scroller.computeScrollOffset()) {
                scrollTo(scroller.getCurrX(), scroller.getCurrY());
                postInvalidateOnAnimation();
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            viewR0 = -1;
            refresh();
        }

        @Override
        protected void onDraw(Canvas c) {
            float k = k(), hw = headW(), hh = headH();
            int w = getWidth(), h = getHeight();
            int r0 = firstRow(), c0 = firstCol();
            textP.setTextSize(11 * k);
            // Celdas.
            c.save();
            c.clipRect(hw, hh, w, h);
            float y = hh + rowTop(r0) * k - sy;
            for (int r = r0; y < h; r++) {
                float rh = rowHeight(r) * k;
                float x = hw + colLeft(c0) * k - sx;
                for (int col = c0; x < w; col++) {
                    float cw = colWidth(col) * k;
                    JSONObject cell = cell(r, col);
                    if (cell != null && cell.length() > 0) {
                        String bg = cell.optString("bg", null);
                        if (bg != null) {
                            fillP.setColor(Ink.parseColor(bg, 0));
                            c.drawRect(x, y, x + cw, y + rh, fillP);
                        }
                    }
                    x += cw;
                }
                y += rh;
            }
            // Rejilla.
            y = hh + rowTop(r0) * k - sy;
            for (int r = r0; y < h; r++) {
                c.drawLine(hw, y, w, y, lineP);
                y += rowHeight(r) * k;
            }
            float x = hw + colLeft(c0) * k - sx;
            for (int col = c0; x < w; col++) {
                c.drawLine(x, hh, x, h, lineP);
                x += colWidth(col) * k;
            }
            // Texto (puede pasar a la celda de al lado si está vacía).
            y = hh + rowTop(r0) * k - sy;
            for (int r = r0; y < h; r++) {
                float rh = rowHeight(r) * k;
                x = hw + colLeft(c0) * k - sx;
                for (int col = c0; x < w; col++) {
                    float cw = colWidth(col) * k;
                    JSONObject cell = cell(r, col);
                    String s = cell == null ? null : cell.optString("t", null);
                    if (s != null) {
                        boolean b = cell.optBoolean("b"), it = cell.optBoolean("i");
                        textP.setTypeface(Typeface.create(Typeface.DEFAULT, (b ? Typeface.BOLD : 0) | (it ? Typeface.ITALIC : 0)));
                        String fc = cell.optString("fc", null);
                        textP.setColor(fc != null ? Ink.parseColor(fc, t.text) : (t.dark ? 0xFFE5E5E5 : 0xFF111827));
                        String a = cell.optString("a", "l");
                        float tw = textP.measureText(s), pad = 3 * k;
                        if ("r".equals(a) && tw > cw - 2 * pad) {
                            s = fit(s, cw - 2 * pad, textP);
                            tw = textP.measureText(s);
                        }
                        float tx = "r".equals(a) ? x + cw - pad - tw : "c".equals(a) ? x + (cw - tw) / 2 : x + pad;
                        float right = x + cw;
                        if ("l".equals(a)) {
                            int next = col + 1;
                            while (tx + tw > right && cell(r, next) != null && !cell(r, next).has("t") && right < w) right += colWidth(next++) * k;
                        }
                        c.save();
                        c.clipRect(Math.max(x, hw), y, right, y + rh);
                        c.drawText(s, tx, y + rh / 2 - (textP.ascent() + textP.descent()) / 2, textP);
                        c.restore();
                    }
                    x += cw;
                }
                y += rh;
            }
            // Gráficas.
            for (int i = 0; i < charts.length(); i++) {
                JSONObject ch = charts.optJSONObject(i);
                if (ch == null) continue;
                float cx = hw + (float) ch.optDouble("x") * k - sx, cy = hh + (float) ch.optDouble("y") * k - sy;
                c.save();
                c.translate(cx, cy);
                c.scale(k, k);
                drawPrims(c, ch.optJSONArray("prims") == null ? new JSONArray() : ch.optJSONArray("prims"), chartP, path);
                if (i == pickedChart) {
                    chartP.setStyle(Paint.Style.STROKE);
                    chartP.setColor(t.accent);
                    chartP.setStrokeWidth(2.5f / k * t.density);
                    c.drawRect(0, 0, (float) ch.optDouble("w"), (float) ch.optDouble("h"), chartP);
                }
                c.restore();
            }
            // Selección.
            int sr0 = Math.min(ar, sr), sr1 = Math.max(ar, sr), sc0 = Math.min(ac, sc), sc1 = Math.max(ac, sc);
            float selL = hw + colLeft(sc0) * k - sx, selT = hh + rowTop(sr0) * k - sy;
            float selR = sc1 > 16000 ? w : hw + colLeft(sc1 + 1) * k - sx, selB = sr1 > 1000000 ? h : hh + rowTop(sr1 + 1) * k - sy;
            fillP.setColor(t.accent & 0xFFFFFF | 0x22000000);
            c.drawRect(selL, selT, selR, selB, fillP);
            Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
            border.setStyle(Paint.Style.STROKE);
            border.setColor(t.accent);
            border.setStrokeWidth(t.px(2));
            c.drawRect(selL, selT, selR, selB, border);
            c.restore();
            // Cabeceras.
            fillP.setColor(t.dark ? 0xFF262626 : 0xFFF5F5F4);
            c.drawRect(0, 0, w, hh, fillP);
            c.drawRect(0, 0, hw, h, fillP);
            headP.setTextSize(t.px(12));
            x = hw + colLeft(c0) * k - sx;
            for (int col = c0; x < w; col++) {
                float cw = colWidth(col) * k;
                boolean on = col >= sc0 && col <= sc1;
                headP.setColor(on ? t.accent : t.muted);
                headP.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                if (x + cw > hw) c.drawText(col(col), x + cw / 2, hh / 2 - (headP.ascent() + headP.descent()) / 2, headP);
                c.drawLine(x, 0, x, hh, lineP);
                x += cw;
            }
            y = hh + rowTop(r0) * k - sy;
            for (int r = r0; y < h; r++) {
                float rh = rowHeight(r) * k;
                boolean on = r >= sr0 && r <= sr1;
                headP.setColor(on ? t.accent : t.muted);
                headP.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                if (y + rh > hh) c.drawText(String.valueOf(r + 1), hw / 2, y + rh / 2 - (headP.ascent() + headP.descent()) / 2, headP);
                c.drawLine(0, y, hw, y, lineP);
                y += rh;
            }
            c.drawLine(hw, 0, hw, h, lineP);
            c.drawLine(0, hh, w, hh, lineP);
        }
    }
}
