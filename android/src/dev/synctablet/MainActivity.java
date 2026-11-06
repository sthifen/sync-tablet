package dev.synctablet;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * La app de la tablet con el aspecto del visor del PC: barra lateral con las
 * notas y los cuadernos, barra superior, y en medio la página (con portada,
 * icono y título). «Inicio» enseña las notas recientes y la galería de
 * cuadernos; una nota se edita aquí mismo con bloques, y un cuaderno se abre
 * en la hoja para escribir a mano (PageActivity).
 */
public final class MainActivity extends Activity implements SyncClient.Listener, Notes.Listener, BlockEditor.Host {
    private static final String[] EMOJIS = {"📝", "📄", "👋", "📚", "✏️", "💡", "✅", "📌", "🗓️", "🎯", "🧠", "📎",
        "🩺", "💊", "🏠", "💼", "🎓", "🧪", "📊", "🗂️", "⭐", "❤️", "🔥", "🌱", "🎨", "🎵", "✈️", "🍎", "⚽", "🐶", "☕", "🌙"};

    private NoteTheme t;
    private SharedPreferences prefs;
    private Store store;
    private Notes notes;
    private SyncClient sync;
    private PageRenderer renderer;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService thumbs = Executors.newSingleThreadExecutor();
    /** Miniaturas de páginas de la tablet, por "id@versión". */
    private final HashMap<String, Bitmap> thumbCache = new HashMap<>();

    // vistas
    private LinearLayout sidebar, noteList, nbList, pageBox;
    private View edge, connDot;
    private TextView connText, crumb, saveState;
    private ScrollView scroller;
    private AuroraView cover;
    private boolean online;
    private String where = "";

    /** Nota abierta (null = Inicio). */
    private String noteId;
    private JSONObject note;
    private OptionDock dock;
    private BlockEditor editor;
    private TextView iconView;
    private EditText titleView;
    private boolean loading, pendingRemote;
    private final Runnable save = () -> { if (note != null) notes.edited(note); };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("ui", MODE_PRIVATE);
        t = new NoteTheme(this);
        store = Store.get(this);
        notes = Notes.get(this);
        sync = SyncClient.get(this);
        renderer = new PageRenderer(this);
        build();
        String last = saved != null ? saved.getString("note") : prefs.getString("last", null);
        if (last != null && notes.note(last) != null) openNote(last); else showHome();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("note", noteId);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sync.addListener(this);
        notes.addListener(this);
        notes.sync();
        cover.setRunning(true);
        renderSidebar();
        // Al volver de la hoja: miniaturas y cuadernos al día.
        if (noteId == null) showHome(); else if (editor != null) editor.refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        cover.setRunning(false);
        flushSave();
        sync.removeListener(this);
        notes.removeListener(this);
    }

    // ---------------------------------------------------------------- piezas

    private TextView text(String s, float sp, int color, android.graphics.Typeface face) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setTypeface(face);
        v.setIncludeFontPadding(false);
        return v;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(t.px(radiusDp));
        return d;
    }

    /** Fondo que se resalta al tocar (y queda marcado si `selected`). */
    private android.graphics.drawable.Drawable pressable(boolean selected) {
        return new RippleDrawable(ColorStateList.valueOf(t.hover | 0x22000000),
                rounded(selected ? (t.dark ? 0x1AFFFFFF : 0x14333333) : 0, 6), rounded(0xFFFFFFFF, 6));
    }

    private IconView icon(int which, int color, String label) {
        IconView v = new IconView(this, which, color, label);
        v.setMinimumWidth(0);
        v.setMinimumHeight(0);
        return v;
    }

    // ---------------------------------------------------------------- construcción

    @SuppressWarnings("deprecation")
    private void build() {
        getWindow().setStatusBarColor(t.bar);
        getWindow().setNavigationBarColor(t.bg);
        if (!t.dark) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(t.bg);
        root.addView(buildSidebar(), new LinearLayout.LayoutParams(t.dp(270), -1));
        edge = new View(this);
        edge.setBackgroundColor(t.edge);
        root.addView(edge, new LinearLayout.LayoutParams(Math.max(1, t.dp(1)), -1));
        root.addView(buildMain(), new LinearLayout.LayoutParams(0, -1, 1));
        boolean open = prefs.getBoolean("sidebar", true);
        sidebar.setVisibility(open ? View.VISIBLE : View.GONE);
        edge.setVisibility(open ? View.VISIBLE : View.GONE);
        // De borde a borde (Android 15+): sitio para las barras del sistema y el teclado.
        root.setOnApplyWindowInsetsListener((v, in) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets s = in.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());
                v.setPadding(s.left, s.top, s.right, s.bottom);
                dock.setInsets(in.getInsets(android.view.WindowInsets.Type.systemBars()).bottom, in.getInsets(android.view.WindowInsets.Type.ime()).bottom);
            } else {
                // Antes de Android 11 la ventana ya se encoge con el teclado.
                v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(), in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
                dock.setInsets(in.getSystemWindowInsetBottom(), 0);
            }
            return in;
        });
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((o, n) -> {
            if (pendingRemote && !isEditing()) main.postDelayed(() -> onNotesChanged(noteId), 400);
        });
        // El dock de opciones flota sobre todo.
        FrameLayout frame = new FrameLayout(this);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
        dock = new OptionDock(this, frame, null, t.dark ? 0xF7262626 : 0xF7FFFFFF, t.dark);
        setContentView(frame);
    }

    private View buildSidebar() {
        sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setBackgroundColor(t.bar);
        sidebar.setPadding(t.dp(6), t.dp(6), t.dp(6), 0);

        LinearLayout brand = row();
        brand.setPadding(t.dp(10), 0, t.dp(4), 0);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        logo.setBackground(rounded(0xFF0F766E, 5));
        logo.setClipToOutline(true);
        brand.addView(logo, new LinearLayout.LayoutParams(t.dp(24), t.dp(24)));
        TextView name = text("Sync Tablet", 15, t.text, t.bold);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1);
        nlp.leftMargin = t.dp(10);
        brand.addView(name, nlp);
        IconView close = icon(IconView.SIDEBAR, t.icon, "Cerrar la barra lateral");
        close.setOnClickListener(v -> toggleSidebar());
        brand.addView(close, new LinearLayout.LayoutParams(t.dp(40), t.dp(40)));
        sidebar.addView(brand, new LinearLayout.LayoutParams(-1, t.dp(50)));

        sidebar.addView(navItem(IconView.SEARCH, "Buscar", v -> openSearch()));
        sidebar.addView(navItem(IconView.HOME, "Inicio", v -> { flushSave(); showHome(); }));
        sidebar.addView(navItem(IconView.PLUS, "Nueva nota", v -> newNote()));
        sidebar.addView(navItem(IconView.PEN, "Nuevo cuaderno", v -> askNewNotebook()));
        sidebar.addView(navItem(IconView.MOON, t.dark ? "Modo claro" : "Modo oscuro", v -> toggleTheme()));

        ScrollView sv = new ScrollView(this);
        LinearLayout lists = new LinearLayout(this);
        lists.setOrientation(LinearLayout.VERTICAL);
        sv.addView(lists);
        lists.addView(label("Notas", v -> newNote(), "Nueva nota"));
        noteList = new LinearLayout(this);
        noteList.setOrientation(LinearLayout.VERTICAL);
        lists.addView(noteList);
        lists.addView(label("Cuadernos", v -> askNewNotebook(), "Nuevo cuaderno"));
        nbList = new LinearLayout(this);
        nbList.setOrientation(LinearLayout.VERTICAL);
        lists.addView(nbList);
        sidebar.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        View line = new View(this);
        line.setBackgroundColor(t.line);
        sidebar.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, t.dp(1))));
        LinearLayout conn = row();
        conn.setPadding(t.dp(12), t.dp(14), t.dp(10), t.dp(16));
        connDot = new View(this);
        conn.addView(connDot, new LinearLayout.LayoutParams(t.dp(8), t.dp(8)));
        connText = text("", 13, t.faint, t.regular);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1);
        clp.leftMargin = t.dp(8);
        conn.addView(connText, clp);
        IconView wifi = icon(IconView.WIFI, t.icon, "Conectar por WiFi");
        conn.addView(wifi, new LinearLayout.LayoutParams(t.dp(28), t.dp(28)));
        conn.setOnClickListener(v -> askWifi());
        wifi.setOnClickListener(v -> askWifi());
        sidebar.addView(conn, new LinearLayout.LayoutParams(-1, -2));
        paintConn();
        return sidebar;
    }

    private View navItem(int ic, String label, View.OnClickListener l) {
        LinearLayout r = row();
        r.setPadding(t.dp(8), 0, t.dp(10), 0);
        r.setBackground(pressable(false));
        r.addView(icon(ic, t.icon, ""), new LinearLayout.LayoutParams(t.dp(26), t.dp(26)));
        TextView tv = text(label, 15, t.muted, t.medium);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = t.dp(10);
        r.addView(tv, lp);
        r.setOnClickListener(l);
        r.setContentDescription(label);
        r.setLayoutParams(new LinearLayout.LayoutParams(-1, t.dp(40)));
        return r;
    }

    private View label(String s, View.OnClickListener add, String addLabel) {
        LinearLayout r = row();
        r.setPadding(t.dp(10), t.dp(18), t.dp(2), t.dp(4));
        r.addView(text(s, 12, t.faint, t.medium), new LinearLayout.LayoutParams(0, -2, 1));
        IconView plus = icon(IconView.PLUS, t.faint, addLabel);
        plus.setOnClickListener(add);
        r.addView(plus, new LinearLayout.LayoutParams(t.dp(32), t.dp(32)));
        return r;
    }

    private View buildMain() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = row();
        top.setPadding(t.dp(8), 0, t.dp(8), 0);
        IconView menu = icon(IconView.SIDEBAR, t.icon, "Barra lateral");
        menu.setOnClickListener(v -> toggleSidebar());
        top.addView(menu, new LinearLayout.LayoutParams(t.dp(44), t.dp(44)));
        crumb = text("", 15, t.text, t.regular);
        crumb.setSingleLine(true);
        crumb.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1);
        cl.leftMargin = t.dp(6);
        top.addView(crumb, cl);
        saveState = text("", 13, t.faint, t.regular);
        saveState.setPadding(t.dp(8), 0, t.dp(8), 0);
        top.addView(saveState);
        IconView more = icon(IconView.MORE, t.icon, "Más opciones");
        more.setOnClickListener(this::moreMenu);
        top.addView(more, new LinearLayout.LayoutParams(t.dp(44), t.dp(44)));
        col.addView(top, new LinearLayout.LayoutParams(-1, t.dp(52)));

        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        // Al abrir algo, nada queda en edición hasta que tocas un texto.
        scroller.setFocusableInTouchMode(true);
        scroller.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        scroller.addView(content, new ViewGroup.LayoutParams(-1, -2));
        col.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));

        cover = new AuroraView(this, t);
        content.addView(cover, new LinearLayout.LayoutParams(-1, t.dp(170)));
        ColumnLayout column = new ColumnLayout();
        column.setClipChildren(false);
        content.addView(column, new LinearLayout.LayoutParams(-1, -2));
        pageBox = new LinearLayout(this);
        pageBox.setOrientation(LinearLayout.VERTICAL);
        pageBox.setClipChildren(false);
        column.addView(pageBox);
        return col;
    }

    /** Centra la página con un ancho máximo, como en el visor del PC. */
    private final class ColumnLayout extends ViewGroup {
        ColumnLayout() { super(MainActivity.this); }

        private int inner(int w) {
            int pad = w < t.dp(600) ? t.dp(28) : t.dp(64);
            return Math.max(t.dp(200), Math.min(t.dp(noteId == null ? 980 : 720), w - 2 * pad));
        }

        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), h = 0;
            if (getChildCount() > 0) {
                View c = getChildAt(0);
                c.measure(MeasureSpec.makeMeasureSpec(inner(w), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                h = c.getMeasuredHeight();
            }
            setMeasuredDimension(w, h);
        }

        @Override protected void onLayout(boolean changed, int l, int tp, int r, int b) {
            if (getChildCount() == 0) return;
            int w = r - l, in = inner(w), x = (w - in) / 2;
            View c = getChildAt(0);
            c.layout(x, 0, x + in, c.getMeasuredHeight());
        }
    }

    // ---------------------------------------------------------------- barra lateral

    private void renderSidebar() {
        noteList.removeAllViews();
        for (JSONObject n : sortedNotes()) {
            String id = n.optString("id");
            String ic = n.optString("icon");
            LinearLayout r = sideRow(id.equals(noteId));
            TextView em = text(ic.isEmpty() ? "📄" : ic, 17, t.text, t.regular);
            em.setGravity(Gravity.CENTER);
            r.addView(em, new LinearLayout.LayoutParams(t.dp(26), -2));
            r.addView(sideTitle(n.optString("title"), id.equals(noteId)), new LinearLayout.LayoutParams(0, -2, 1));
            r.setOnClickListener(v -> openNote(id));
            r.setOnLongClickListener(v -> { confirmDeleteNote(id); return true; });
            noteList.addView(r);
        }
        if (notes.list().isEmpty()) noteList.addView(sideHint("Aún no hay notas"));

        nbList.removeAllViews();
        // Agrupados por carpeta; los de sin carpeta primero.
        TreeMap<String, ArrayList<Ink.Notebook>> groups = new TreeMap<>();
        for (Ink.Notebook n : store.notebooks()) groups.computeIfAbsent(n.folder, k -> new ArrayList<>()).add(n);
        for (java.util.Map.Entry<String, ArrayList<Ink.Notebook>> g : groups.entrySet()) {
            if (!g.getKey().isEmpty()) {
                TextView f = text("📁  " + g.getKey(), 13, t.faint, t.medium);
                f.setPadding(t.dp(12), t.dp(10), 0, t.dp(4));
                nbList.addView(f);
            }
            for (Ink.Notebook n : g.getValue()) {
                LinearLayout r = sideRow(false);
                View chip = new View(this);
                chip.setBackground(rounded(n.color, 2));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(t.dp(12), t.dp(16));
                clp.leftMargin = t.dp(7);
                clp.rightMargin = t.dp(7);
                r.addView(chip, clp);
                r.addView(sideTitle((n.favorite ? "★ " : "") + n.title, false), new LinearLayout.LayoutParams(0, -2, 1));
                r.addView(text(String.valueOf(n.pages.size()), 12, t.faint, t.regular));
                r.setOnClickListener(v -> openNotebook(n, null));
                r.setOnLongClickListener(v -> { notebookMenu(n); return true; });
                nbList.addView(r);
            }
        }
        if (store.notebooks().isEmpty()) nbList.addView(sideHint("Aún no hay cuadernos"));
        View spacer = new View(this);
        nbList.addView(spacer, new LinearLayout.LayoutParams(-1, t.dp(12)));
    }

    private LinearLayout sideRow(boolean selected) {
        LinearLayout r = row();
        r.setPadding(t.dp(6), 0, t.dp(10), 0);
        r.setBackground(pressable(selected));
        r.setLayoutParams(new LinearLayout.LayoutParams(-1, t.dp(38)));
        return r;
    }

    private TextView sideTitle(String s, boolean selected) {
        TextView tv = text(s.isEmpty() ? "Sin título" : s, 15, s.isEmpty() ? t.faint : selected ? t.text : t.muted, selected ? t.medium : t.regular);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(t.dp(6), 0, t.dp(6), 0);
        return tv;
    }

    private TextView sideHint(String s) {
        TextView tv = text(s, 13, t.faint, t.regular);
        tv.setPadding(t.dp(14), t.dp(4), 0, t.dp(4));
        return tv;
    }

    private void toggleSidebar() {
        boolean open = sidebar.getVisibility() != View.VISIBLE;
        sidebar.setVisibility(open ? View.VISIBLE : View.GONE);
        edge.setVisibility(open ? View.VISIBLE : View.GONE);
        prefs.edit().putBoolean("sidebar", open).apply();
    }

    private void toggleTheme() {
        flushSave();
        prefs.edit().putInt("theme", t.dark ? 1 : 2).apply();
        recreate();
    }

    @Override
    public void onStatus(boolean connected, String via) {
        online = connected;
        where = via;
        paintConn();
        paintState();
    }

    private void paintConn() {
        if (connText == null) return;
        connText.setText(online ? "Conectado al PC · " + where : "Sin conexión al PC");
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(online ? 0xFF35A86B : t.faint);
        connDot.setBackground(d);
    }

    // ---------------------------------------------------------------- Inicio

    private ArrayList<JSONObject> sortedNotes() {
        ArrayList<JSONObject> list = notes.list();
        list.sort((a, b) -> Long.compare(b.optLong("updated"), a.optLong("updated")));
        return list;
    }

    private void showHome() {
        // Sólo se sube arriba al llegar desde otra cosa, no al repintar.
        boolean arriving = noteId != null || pageBox.getChildCount() == 0;
        noteId = null;
        note = null;
        editor = null;
        prefs.edit().remove("last").apply();
        pageBox.removeAllViews();
        crumb.setText("🏠  Inicio");
        saveState.setText("");

        TextView ic = text("🏠", 58, t.text, t.regular);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-2, -2);
        ilp.topMargin = -t.dp(40);
        pageBox.addView(ic, ilp);
        TextView title = text("Inicio", 36, t.text, t.bold);
        title.setPadding(0, t.dp(14), 0, t.dp(6));
        pageBox.addView(title);
        int nn = notes.list().size(), nb = store.notebooks().size();
        pageBox.addView(text((nn == 1 ? "1 nota" : nn + " notas") + " · " + (nb == 1 ? "1 cuaderno" : nb + " cuadernos"), 15, t.faint, t.regular));

        sectionTitle("Notas recientes");
        ArrayList<JSONObject> recent = sortedNotes();
        for (int i = 0; i < Math.min(5, recent.size()); i++) pageBox.addView(noteRow(recent.get(i)));
        TextView add = text("+  Nueva nota", 15, t.faint, t.regular);
        add.setPadding(t.dp(8), t.dp(12), 0, t.dp(12));
        add.setBackground(pressable(false));
        add.setOnClickListener(v -> newNote());
        pageBox.addView(add, new LinearLayout.LayoutParams(-1, -2));

        sectionTitle("Cuadernos");
        gallery();
        View end = new View(this);
        pageBox.addView(end, new LinearLayout.LayoutParams(-1, t.dp(120)));
        renderSidebar();
        if (arriving) scroller.post(() -> scroller.scrollTo(0, 0));
    }

    private void sectionTitle(String s) {
        TextView tv = text(s, 14, t.muted, t.medium);
        tv.setPadding(t.dp(2), t.dp(34), 0, t.dp(8));
        pageBox.addView(tv);
        View line = new View(this);
        line.setBackgroundColor(t.line);
        pageBox.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, t.dp(1))));
    }

    private View noteRow(JSONObject n) {
        LinearLayout r = row();
        r.setPadding(t.dp(8), t.dp(10), t.dp(8), t.dp(10));
        r.setBackground(pressable(false));
        String ic = n.optString("icon");
        r.addView(text(ic.isEmpty() ? "📄" : ic, 20, t.text, t.regular), new LinearLayout.LayoutParams(t.dp(34), -2));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        String title = n.optString("title");
        TextView name = text(title.isEmpty() ? "Sin título" : title, 16, title.isEmpty() ? t.faint : t.text, t.medium);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        txt.addView(name);
        String body = Notes.text(n);
        if (!body.isEmpty()) {
            TextView snip = text(body, 13, t.faint, t.regular);
            snip.setSingleLine(true);
            snip.setEllipsize(TextUtils.TruncateAt.END);
            snip.setPadding(0, t.dp(4), 0, 0);
            txt.addView(snip);
        }
        r.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
        String when = n.optLong("updated") > 0
                ? DateUtils.getRelativeTimeSpanString(n.optLong("updated"), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() : "";
        if (n.optBoolean("dirty") && !online) when = "Se subirá al conectar";
        r.addView(text(when, 12, t.faint, t.regular));
        String id = n.optString("id");
        r.setOnClickListener(v -> openNote(id));
        r.setOnLongClickListener(v -> { confirmDeleteNote(id); return true; });
        return r;
    }

    /** Galería de cuadernos: vista previa de la primera página, título y datos. */
    private void gallery() {
        GridLayout grid = new GridLayout(this);
        int width = pageBox.getWidth();
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels - t.dp(sidebar.getVisibility() == View.VISIBLE ? 400 : 130);
        int gap = t.dp(16);
        int cols = Math.max(2, Math.min(5, (width + gap) / (t.dp(190) + gap)));
        int cellW = (width - gap * (cols - 1)) / cols;
        grid.setColumnCount(cols);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = t.dp(16);
        pageBox.addView(grid, glp);
        ArrayList<Ink.Notebook> list = new ArrayList<>(store.notebooks());
        list.sort((a, b) -> Long.compare(b.updated, a.updated));
        int i = 0;
        for (Ink.Notebook n : list) grid.addView(card(n), cell(i++, cols, cellW, gap));
        grid.addView(newCard(), cell(i, cols, cellW, gap));
    }

    private GridLayout.LayoutParams cell(int i, int cols, int w, int gap) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = w;
        lp.height = Math.round(w * 1.18f);
        if (i % cols != cols - 1) lp.rightMargin = gap;
        lp.bottomMargin = gap;
        return lp;
    }

    private LinearLayout cardBox() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = rounded(t.card, 10);
        bg.setStroke(Math.max(1, t.dp(1)), t.line);
        c.setBackground(new RippleDrawable(ColorStateList.valueOf(t.hover | 0x22000000), bg, null));
        c.setClipToOutline(true);
        c.setElevation(t.px(1));
        return c;
    }

    private View card(Ink.Notebook n) {
        LinearLayout c = cardBox();
        FrameLayout prev = new FrameLayout(this);
        prev.setBackgroundColor(t.paper);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(0xFFFFFFFF);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(-1, -1);
        ilp.setMargins(t.dp(14), t.dp(14), t.dp(14), 0);
        prev.addView(img, ilp);
        View band = new View(this);
        band.setBackgroundColor(n.color);
        prev.addView(band, new FrameLayout.LayoutParams(-1, t.dp(4), Gravity.TOP));
        c.addView(prev, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!n.pages.isEmpty()) {
            Ink.Page p = store.page(n.pages.get(0));
            if (p != null) thumbnail(p, t.dp(200), img);
        }
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(t.dp(12), t.dp(10), t.dp(12), t.dp(12));
        TextView name = text((n.favorite ? "★ " : "") + n.title, 15, t.text, t.medium);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name);
        String sub = n.pages.size() == 1 ? "1 página" : n.pages.size() + " páginas";
        if (n.updated > 0) sub += " · " + DateUtils.getRelativeTimeSpanString(n.updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        TextView s = text(sub, 12, t.faint, t.regular);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        s.setPadding(0, t.dp(4), 0, 0);
        info.addView(s);
        c.addView(info);
        c.setOnClickListener(v -> openNotebook(n, null));
        c.setOnLongClickListener(v -> { notebookMenu(n); return true; });
        c.setContentDescription("Cuaderno " + n.title);
        return c;
    }

    private View newCard() {
        LinearLayout c = cardBox();
        c.setGravity(Gravity.CENTER);
        c.addView(icon(IconView.PLUS, t.faint, ""), new LinearLayout.LayoutParams(t.dp(40), t.dp(40)));
        TextView tv = text("Nuevo cuaderno", 15, t.faint, t.medium);
        tv.setPadding(0, t.dp(6), 0, 0);
        c.addView(tv);
        c.setOnClickListener(v -> askNewNotebook());
        return c;
    }

    private void thumbnail(Ink.Page p, int w, ImageView into) {
        String key = p.id + "@" + p.version + "@" + w;
        Bitmap have = thumbCache.get(key);
        if (have != null) {
            into.setImageBitmap(have);
            return;
        }
        String json = p.toJson();
        thumbs.execute(() -> {
            Ink.Page copy;
            try {
                copy = Ink.Page.fromJson(json);
            } catch (JSONException e) {
                return;
            }
            int h = Math.round(w * Ink.H / Ink.W);
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            float k = w / Ink.W;
            c.scale(k, k);
            renderer.drawPage(c, copy, k, null, null);
            main.post(() -> {
                thumbCache.put(key, bmp);
                into.setImageBitmap(bmp);
            });
        });
    }

    private void openNotebook(Ink.Notebook n, String page) {
        flushSave();
        Intent i = new Intent(this, PageActivity.class).putExtra("notebook", n.id);
        if (page != null) i.putExtra("page", page);
        startActivity(i);
    }

    // ---------------------------------------------------------------- una nota

    private void openNote(String id) {
        flushSave();
        JSONObject n = notes.note(id);
        if (n == null) { showHome(); return; }
        noteId = id;
        prefs.edit().putString("last", id).apply();
        pageBox.removeAllViews();

        iconView = text("📄", 58, t.text, t.regular);
        iconView.setContentDescription("Cambiar icono");
        iconView.setOnClickListener(v -> pickIcon());
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-2, -2);
        ilp.topMargin = -t.dp(40);
        pageBox.addView(iconView, ilp);

        titleView = new EditText(this);
        titleView.setBackground(null);
        titleView.setPadding(0, t.dp(14), 0, t.dp(8));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
        titleView.setTypeface(t.bold);
        titleView.setTextColor(t.text);
        titleView.setHint("Sin título");
        titleView.setHintTextColor(t.faint);
        titleView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        titleView.setHorizontallyScrolling(false);
        titleView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (loading || note == null) return;
                String v = s.toString();
                if (v.indexOf('\n') >= 0) { // Enter en el título: al primer bloque
                    s.replace(0, s.length(), v.replace("\n", ""));
                    editor.focusFirst();
                    return;
                }
                try { note.put("title", v); } catch (JSONException ignored) { }
                paintCrumb();
                changed();
                renderSidebar();
            }
        });
        pageBox.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout blocks = new LinearLayout(this);
        blocks.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.leftMargin = -t.dp(30); // el asa de cada bloque queda en el margen
        pageBox.addView(blocks, blp);
        editor = new BlockEditor(this, t, blocks, this);

        TextView add = text("+  Añadir un bloque", 15, t.faint, t.regular);
        add.setPadding(0, t.dp(12), 0, t.dp(12));
        add.setOnClickListener(v -> editor.addAtEnd());
        pageBox.addView(add, new LinearLayout.LayoutParams(-1, -2));
        View below = new View(this);
        below.setOnClickListener(v -> editor.addAtEnd());
        pageBox.addView(below, new LinearLayout.LayoutParams(-1, t.dp(260)));

        show(n);
        renderSidebar();
        scroller.post(() -> scroller.scrollTo(0, 0));
        if (n.optString("title").isEmpty() && Notes.text(n).isEmpty()) {
            titleView.requestFocus();
            titleView.post(() -> {
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(titleView, 0);
            });
        }
    }

    private void show(JSONObject n) {
        loading = true;
        note = n;
        String ic = n.optString("icon");
        iconView.setText(ic.isEmpty() ? "📄" : ic);
        if (!titleView.getText().toString().equals(n.optString("title"))) titleView.setText(n.optString("title"));
        paintCrumb();
        editor.setPage(n);
        loading = false;
        paintState();
    }

    private void paintCrumb() {
        if (note == null) return;
        String ic = note.optString("icon"), ti = note.optString("title");
        crumb.setText((ic.isEmpty() ? "📄" : ic) + "  " + (ti.isEmpty() ? "Sin título" : ti));
    }

    private boolean isEditing() {
        return editor != null && (editor.isEditing() || (titleView != null && titleView.hasFocus()));
    }

    @Override
    public void changed() {
        if (loading || note == null) return;
        // Pendiente desde ya, para que una bajada del PC no lo pise mientras se escribe.
        try { note.put("dirty", true); } catch (JSONException ignored) { }
        saveState.setText("Guardando…");
        main.removeCallbacks(save);
        main.postDelayed(save, 600);
    }

    private void flushSave() {
        main.removeCallbacks(save);
        if (note != null && note.optBoolean("dirty")) notes.edited(note);
    }

    private void paintState() {
        if (note == null) { saveState.setText(""); return; }
        boolean pending = note.optBoolean("dirty");
        saveState.setText(!pending ? (online ? "Guardado en el PC" : "Guardado") : online ? "Guardando…" : "Se subirá al conectar");
    }

    @Override
    public void onNotesChanged(String changed) {
        renderSidebar();
        if (noteId == null) {
            if (!isFinishing()) showHome();
            return;
        }
        if (changed != null && !changed.equals(noteId)) return;
        JSONObject now = notes.note(noteId);
        if (now == null) {
            Ui.toast(this, "Esta nota se borró");
            showHome();
            return;
        }
        if (now == note) { paintState(); return; }
        // Llegó una versión nueva del PC: se aplica cuando no estés escribiendo.
        if (isEditing()) { pendingRemote = true; return; }
        pendingRemote = false;
        show(now);
    }

    private void newNote() {
        flushSave();
        JSONObject n = notes.create();
        openNote(n.optString("id"));
    }

    private void pickIcon() {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(8);
        grid.setPadding(t.dp(12), t.dp(8), t.dp(12), t.dp(8));
        for (String em : EMOJIS) {
            TextView e = new TextView(this);
            e.setText(em);
            e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            e.setGravity(Gravity.CENTER);
            e.setTextColor(t.text);
            e.setOnClickListener(v -> {
                try { note.put("icon", em); } catch (JSONException ignored) { }
                iconView.setText(em);
                paintCrumb();
                changed();
                renderSidebar();
                dock.close();
            });
            grid.addView(e, new ViewGroup.LayoutParams(t.dp(48), t.dp(48)));
        }
        dock.show("Elige un icono", grid);
    }

    private void confirmDeleteNote(String id) {
        JSONObject n = notes.note(id);
        if (n == null) return;
        String name = n.optString("title");
        dock.confirm("¿Eliminar «" + (name.isEmpty() ? "Sin título" : name) + "»?", "Se borra de la tablet y del PC.", "Eliminar", () -> {
            if (id.equals(noteId)) {
                main.removeCallbacks(save);
                note = null;
                noteId = null;
            }
            notes.delete(id);
            showHome();
        });
    }

    private void moreMenu(View anchor) {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        if (note != null) {
            tiles.add(new OptionDock.Tile(dock.letter(note.optString("icon", "📄")), "Cambiar icono", false, this::pickIcon).stays());
            if (Native.available()) tiles.add(new OptionDock.Tile(dock.glyph(IconView.EXPORT), "Exportar a Word", false, this::saveDocx));
        }
        if (Native.available()) tiles.add(new OptionDock.Tile(dock.glyph(IconView.FILE), "Importar Word", false, this::pickDocx));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.MOON), t.dark ? "Modo claro" : "Modo oscuro", false, this::toggleTheme));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.WIFI), "Conectar por WiFi", false, this::askWifi).stays());
        if (note != null) tiles.add(new OptionDock.Tile(dock.glyph(IconView.TRASH), "Eliminar esta nota", false, () -> confirmDeleteNote(noteId)).stays());
        dock.tiles("Opciones", tiles);
    }

    // ---------------------------------------------------------------- Word

    private static final int PICK_DOCX = 41, SAVE_DOCX = 42;
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private void pickDocx() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(DOCX);
        startActivityForResult(i, PICK_DOCX);
    }

    private void saveDocx() {
        String title = note == null ? "" : note.optString("title").trim();
        String name = (title.isEmpty() ? "Nota" : title.replaceAll("[\\\\/:*?\"<>|]", "-")) + ".docx";
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(DOCX)
                .putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(i, SAVE_DOCX);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == PICK_DOCX) importDocx(data.getData());
        else if (request == SAVE_DOCX) exportDocx(data.getData());
    }

    private void importDocx(android.net.Uri uri) {
        Ui.toast(this, "Importando…");
        new Thread(() -> {
            String json = null;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16];
                for (int k; (k = in.read(buf)) > 0; ) bytes.write(buf, 0, k);
                json = Native.docxToNote(bytes.toByteArray());
            } catch (java.io.IOException | RuntimeException | OutOfMemoryError e) {
                android.util.Log.w("SyncTablet", "importar Word", e);
            }
            String doc = json;
            runOnUiThread(() -> {
                try {
                    if (doc == null) throw new org.json.JSONException("sin contenido");
                    openNote(Notes.get(this).createFrom(doc, "Documento de Word").optString("id"));
                } catch (org.json.JSONException e) {
                    Ui.toast(this, "No se pudo leer ese documento");
                }
            });
        }, "docx").start();
    }

    private void exportDocx(android.net.Uri uri) {
        String json = note == null ? null : note.toString();
        if (json == null) return;
        new Thread(() -> {
            boolean ok = false;
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                byte[] docx = Native.noteToDocx(json);
                if (docx != null && out != null) {
                    out.write(docx);
                    ok = true;
                }
            } catch (java.io.IOException | RuntimeException | OutOfMemoryError e) {
                android.util.Log.w("SyncTablet", "exportar Word", e);
            }
            boolean done = ok;
            runOnUiThread(() -> Ui.toast(this, done ? "Guardado en Word" : "No se pudo exportar"));
        }, "docx").start();
    }

    // ---------------------------------------------------------------- páginas de la tablet dentro de una nota

    private Ink.Notebook notebookOf(String pageId) {
        for (Ink.Notebook n : store.notebooks()) if (n.pages.contains(pageId)) return n;
        return null;
    }

    @Override
    public View tabletBlock(JSONObject b) {
        final String pageId = b.optString("page");
        Ink.Notebook nb = notebookOf(pageId);
        String lbl = nb == null ? null : nb.title + " · Página " + (nb.pages.indexOf(pageId) + 1);
        if (lbl != null && !lbl.equals(b.optString("label"))) {
            try { b.put("label", lbl); } catch (JSONException ignored) { }
        }
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = rounded(t.card, 8);
        bg.setStroke(Math.max(1, t.dp(1)), t.line);
        c.setBackground(bg);
        c.setClipToOutline(true);
        LinearLayout head = row();
        head.setPadding(t.dp(12), 0, t.dp(12), 0);
        TextView name = text("✏️  " + (lbl != null ? lbl : b.optString("label", "Página de la tablet")), 15, t.text, t.medium);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(text(lbl != null ? "Abrir" : "", 14, t.muted, t.regular));
        c.addView(head, new LinearLayout.LayoutParams(-1, t.dp(44)));
        FrameLayout body = new FrameLayout(this);
        body.setBackgroundColor(t.paper);
        body.setPadding(t.dp(16), t.dp(16), t.dp(16), t.dp(16));
        Ink.Page p = store.page(pageId);
        if (p == null) {
            body.addView(text("Esta página no está en la tablet.", 14, t.faint, t.regular));
        } else {
            ImageView img = new ImageView(this);
            img.setBackgroundColor(0xFFFFFFFF);
            img.setElevation(t.px(3));
            int w = t.dp(320);
            body.addView(img, new FrameLayout.LayoutParams(w, Math.round(w * Ink.H / Ink.W), Gravity.CENTER_HORIZONTAL));
            thumbnail(p, w, img);
            c.setOnClickListener(v -> { if (nb != null) openNotebook(nb, pageId); });
            c.setContentDescription("Abrir " + lbl);
        }
        c.addView(body, new LinearLayout.LayoutParams(-1, -2));
        return c;
    }

    @Override
    public void pickTabletPage(JSONObject b) {
        final ArrayList<Ink.Notebook> list = new ArrayList<>();
        for (Ink.Notebook n : store.notebooks()) if (!n.pages.isEmpty()) list.add(n);
        if (list.isEmpty()) {
            Ui.toast(this, "Todavía no tienes cuadernos con páginas");
            return;
        }
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (Ink.Notebook nb : list) {
            tiles.add(new OptionDock.Tile(dock.swatch(nb.color), nb.title + " · " + nb.pages.size() + " pág.", false, () -> {
                ArrayList<OptionDock.Tile> pages = new ArrayList<>();
                for (int i = 0; i < nb.pages.size(); i++) {
                    int page = i;
                    pages.add(new OptionDock.Tile(dock.glyph(IconView.PAGE), "Página " + (i + 1), false,
                            () -> editor.setTablet(b, nb.pages.get(page), nb.title + " · Página " + (page + 1))));
                }
                dock.tiles(nb.title, pages);
            }).stays());
        }
        dock.tiles("¿De qué cuaderno?", tiles);
    }

    @Override
    public void blockOptions(String[] samples, String[] names, int current, java.util.function.IntConsumer pick) {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            int which = i;
            // Elegir página sigue en el dock; lo demás lo cierra.
            OptionDock.Tile tile = new OptionDock.Tile(dock.letter(samples[i]), names[i], i == current, () -> pick.accept(which));
            tiles.add("✏️".equals(samples[i]) ? tile.stays() : tile);
        }
        dock.tiles("Bloque", tiles);
    }

    // ---------------------------------------------------------------- búsqueda

    private void openSearch() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(t.dp(18), 0, t.dp(18), t.dp(8));
        EditText q = dock.field("", "Busca notas y cuadernos…");
        q.setSingleLine(true);
        box.addView(q);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(results);
        box.addView(sv, new LinearLayout.LayoutParams(-1, t.dp(360)));
        Runnable fill = () -> {
            results.removeAllViews();
            String s = q.getText().toString().trim().toLowerCase();
            for (JSONObject n : sortedNotes()) {
                String ti = n.optString("title");
                if (!s.isEmpty() && !ti.toLowerCase().contains(s) && !Notes.text(n).toLowerCase().contains(s)) continue;
                String ic = n.optString("icon");
                results.addView(dock.row((ic.isEmpty() ? "📄" : ic) + "  " + (ti.isEmpty() ? "Sin título" : ti), v -> { dock.close(); openNote(n.optString("id")); }));
            }
            for (Ink.Notebook n : store.notebooks()) {
                if (!s.isEmpty() && !n.title.toLowerCase().contains(s) && !n.folder.toLowerCase().contains(s)) continue;
                results.addView(dock.row("✏️  " + n.title + "  ·  " + n.pages.size() + " pág.", v -> { dock.close(); openNotebook(n, null); }));
            }
            if (results.getChildCount() == 0) results.addView(sideHint("Nada coincide con «" + s + "»"));
        };
        q.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { fill.run(); }
        });
        fill.run();
        dock.show("Buscar", box);
        dock.focus(q);
    }

    // ---------------------------------------------------------------- cuadernos

    private EditText field(String value) {
        EditText e = dock.field(value, "");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setSingleLine(true);
        e.setSelectAllOnFocus(true);
        return e;
    }

    /** Un formulario de un campo: se escribe y se confirma. */
    private void ask(String heading, EditText e, String yes, Runnable run) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(t.dp(18), 0, t.dp(18), t.dp(10));
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        dock.show(heading, box, new OptionDock.Action("Cancelar", false, null), new OptionDock.Action(yes, true, run));
        dock.focus(e);
    }

    private void askNewNotebook() {
        EditText e = field("Cuaderno " + (store.notebooks().size() + 1));
        ask("Nuevo cuaderno", e, "Crear", () -> {
            String title = e.getText().toString().trim();
            int color = Ui.COVERS[store.notebooks().size() % Ui.COVERS.length];
            Ink.Notebook n = store.createNotebook(title.isEmpty() ? "Cuaderno" : title, color);
            sync.sendNotebook(n);
            sync.sendPage(store.page(n.pages.get(0)));
            openNotebook(n, null);
        });
    }

    private void notebookMenu(Ink.Notebook n) {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.PAGE), "Abrir", false, () -> openNotebook(n, null)));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.TEXT), "Renombrar", false, () -> rename(n)).stays());
        tiles.add(new OptionDock.Tile(dock.swatch(n.color), "Cambiar color", false, () -> chooseCover(n)).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.STAR), n.favorite ? "Quitar de favoritos" : "Marcar favorito", n.favorite, () -> {
            n.favorite = !n.favorite;
            changed(n);
        }));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.PAGES), "Mover a carpeta", false, () -> moveToFolder(n)).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.TRASH), "Borrar", false, () -> confirmDelete(n)).stays());
        dock.tiles(n.title, tiles);
    }

    private void chooseCover(Ink.Notebook n) {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (int c : Ui.COVERS) {
            tiles.add(new OptionDock.Tile(dock.swatch(c), "", c == n.color, () -> {
                n.color = c;
                changed(n);
            }));
        }
        dock.tiles("Color de «" + n.title + "»", tiles);
    }

    private void rename(Ink.Notebook n) {
        EditText e = field(n.title);
        ask("Renombrar", e, "Guardar", () -> {
            String title = e.getText().toString().trim();
            if (!title.isEmpty()) {
                n.title = title;
                changed(n);
            }
        });
    }

    private void moveToFolder(Ink.Notebook n) {
        java.util.TreeSet<String> folders = new java.util.TreeSet<>();
        for (Ink.Notebook o : store.notebooks()) if (!o.folder.isEmpty()) folders.add(o.folder);
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (String folder : folders) {
            tiles.add(new OptionDock.Tile(dock.glyph(IconView.PAGES), folder, folder.equals(n.folder), () -> {
                n.folder = folder;
                changed(n);
            }));
        }
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.PLUS), "Nueva carpeta", false, () -> {
            EditText e = field("");
            e.setHint("Universidad, Proyectos…");
            ask("Carpeta nueva", e, "Mover", () -> {
                String name = e.getText().toString().trim();
                if (!name.isEmpty()) {
                    n.folder = name;
                    changed(n);
                }
            });
        }).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.HOME), "Sin carpeta", n.folder.isEmpty(), () -> {
            n.folder = "";
            changed(n);
        }));
        dock.tiles("Mover «" + n.title + "» a", tiles);
    }

    private void changed(Ink.Notebook n) {
        store.saveNotebooks();
        sync.sendNotebook(n);
        renderSidebar();
        if (noteId == null) showHome();
    }

    private void confirmDelete(Ink.Notebook n) {
        dock.confirm("¿Borrar «" + n.title + "»?", "Se borra de la tablet y del PC. No se puede deshacer.", "Borrar", () -> {
            store.deleteNotebook(n);
            sync.send("{\"t\":\"delete_notebook\",\"notebook\":\"" + n.id + "\"}");
            renderSidebar();
            if (noteId == null) showHome();
        });
    }

    private void askWifi() {
        EditText e = field(sync.wifiHost());
        e.setHint("IP del PC, como 192.168.1.20");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText code = field("");
        code.setHint("Código de 6 dígitos");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(t.dp(18), 0, t.dp(18), t.dp(10));
        box.addView(dock.message("Si ya conectaste la tablet por cable, basta la IP. Si no, escribe también el código: "
                + "los dos salen en el PC, en «Conectar por WiFi»."));
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2);
        gap.topMargin = t.dp(8);
        box.addView(code, gap);
        dock.show("Conectar por WiFi", box, new OptionDock.Action("Cancelar", false, null), new OptionDock.Action("Guardar", true, () -> {
            String host = e.getText().toString().trim(), digits = code.getText().toString().trim();
            sync.setWifiHost(host);
            if (digits.isEmpty()) return;
            new Thread(() -> {
                boolean ok = sync.pair(host, digits);
                runOnUiThread(() -> Ui.toast(this, ok ? "Tablet vinculada" : "No se pudo vincular: revisa la IP y el código"));
            }, "pair").start();
        }));
        dock.focus(e);
    }
}
