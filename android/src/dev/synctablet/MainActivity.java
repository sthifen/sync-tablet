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
    /** La carpeta abierta en la página principal; null es Inicio. */
    private String folderView;
    private ImageView wall, coverPhoto;
    private LinearLayout rootBox, mainCol;
    private String lookKey, lookTitle;
    private boolean lookGlass;
    private Object folderBack;
    private Store store;
    private Notes notes;
    private SyncClient sync;
    private PageRenderer renderer;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService thumbs = Executors.newSingleThreadExecutor();
    /** Miniaturas de páginas de la tablet, por "id@versión". */
    private final HashMap<String, Bitmap> thumbCache = new HashMap<>();

    // vistas
    private LinearLayout sidebar, noteList, nbList, folderList, pageBox;
    /** Se ve la papelera en vez de Inicio. */
    private boolean trashView;
    private Object trashBack;
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

    /** Selección de varios: "nb:<id>" y "note:<id>". */
    private boolean selecting;
    private final java.util.LinkedHashSet<String> picked = new java.util.LinkedHashSet<>();
    private FrameLayout frame;
    private LinearLayout pickBar;
    private TextView pickCount;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("ui", MODE_PRIVATE);
        t = new NoteTheme(this);
        store = Store.get(this);
        notes = Notes.get(this);
        sync = SyncClient.get(this);
        BarSettings.applyPaper(this);
        // Si la tablet tiene cuadernos compartidos propios, su nodo arranca ya.
        String[] sharedFiles = new java.io.File(getFilesDir(), "compartidos").list();
        if (!Updater.DESKTOP && sharedFiles != null && sharedFiles.length > 0) {
            new Thread(() -> {
                SharedNode.get(this);
                try {
                    Class.forName("dev.synctablet.KeepAliveService").getMethod("update", android.content.Context.class).invoke(null, getApplicationContext());
                } catch (ReflectiveOperationException ignored) {
                }
            }, "compartidos").start();
        }
        renderer = new PageRenderer(this);
        Trash.purgeOld(this);
        Clip.listen(getApplicationContext());
        build();
        String last = saved != null ? saved.getString("note") : prefs.getString("last", null);
        if (last != null && notes.note(last) != null) openNote(last); else showHome();
        reopenDock();
        checkUpdate(false);
        if (saved == null) pairLink(getIntent().getData());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Updater.DONE.equals(intent.getAction())) installStatus(intent);
        pairLink(intent.getData());
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
        Backup.auto(this);
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
        rootBox = root;
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
        frame = new FrameLayout(this);
        wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(wall, new FrameLayout.LayoutParams(-1, -1));
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
        pickBar = buildPickBar();
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, t.dp(60), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        plp.bottomMargin = t.dp(26);
        frame.addView(pickBar, plp);
        dock = new OptionDock(this, frame, null, t.dark ? 0xF7262626 : 0xF7FFFFFF, t.dark);
        setContentView(frame);
        applyLooks();
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
        sidebar.addView(navItem(IconView.HOME, "Inicio", v -> goTo(null)));
        sidebar.addView(navItem(IconView.PLUS, "Crear", v -> createMenu()));
        sidebar.addView(navItem(IconView.WIFI, "Compartidos", v -> showSharedList()));
        sidebar.addView(navItem(IconView.NEURON, "Mapa de ideas", v -> startActivityForResult(new Intent(this, GraphActivity.class), OPEN_GRAPH)));
        sidebar.addView(navItem(IconView.SLIDERS, "Organizar materias", v -> organize()));
        sidebar.addView(navItem(IconView.WAND, "Personalizar", v -> personalize()));
        sidebar.addView(navItem(IconView.TRASH, "Papelera", v -> openTrash()));

        ScrollView sv = new ScrollView(this);
        LinearLayout lists = new LinearLayout(this);
        lists.setOrientation(LinearLayout.VERTICAL);
        sv.addView(lists);
        // Carpetas primero (con sus cuadernos y notas dentro); después lo suelto.
        lists.addView(label("Carpetas", v -> askNewFolder(null, ""), "Nueva carpeta"));
        folderList = new LinearLayout(this);
        folderList.setOrientation(LinearLayout.VERTICAL);
        lists.addView(folderList);
        lists.addView(label("Cuadernos", v -> { goTo(null); askNewNotebook(); }, "Nuevo cuaderno"));
        nbList = new LinearLayout(this);
        nbList.setOrientation(LinearLayout.VERTICAL);
        lists.addView(nbList);
        lists.addView(label("Notas", v -> { goTo(null); newNote(); }, "Nueva nota"));
        noteList = new LinearLayout(this);
        noteList.setOrientation(LinearLayout.VERTICAL);
        lists.addView(noteList);
        sidebar.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        View line = new View(this);
        line.setBackgroundColor(t.line);
        sidebar.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, t.dp(1))));
        // Un botón de verdad para conectar: en el PC enseña el QR; en la tablet, lo escanea.
        TextView connect = text(Updater.DESKTOP ? "📱  Conectar un aparato" : "📷  Conectar con el PC", 15, 0xFFFFFFFF, t.medium);
        connect.setGravity(Gravity.CENTER);
        connect.setMinHeight(t.dp(46));
        connect.setBackground(Ui.round(0xFF0F766E, t.dp(12)));
        connect.setClickable(true);
        connect.setFocusable(true);
        connect.setOnClickListener(v -> askWifi());
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(-1, -2);
        cbp.setMargins(t.dp(12), t.dp(12), t.dp(12), 0);
        sidebar.addView(connect, cbp);
        LinearLayout conn = row();
        conn.setPadding(t.dp(12), t.dp(14), t.dp(10), t.dp(16));
        connDot = new View(this);
        conn.addView(connDot, new LinearLayout.LayoutParams(t.dp(8), t.dp(8)));
        connText = text("", 13, t.faint, t.regular);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1);
        clp.leftMargin = t.dp(8);
        conn.addView(connText, clp);
        IconView wifi = icon(IconView.WIFI, t.icon, "Conectar con el PC");
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
        mainCol = col;

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
        FrameLayout coverBox = new FrameLayout(this);
        coverBox.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        coverPhoto = new ImageView(this);
        coverPhoto.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverBox.addView(coverPhoto, new FrameLayout.LayoutParams(-1, -1));
        content.addView(coverBox, new LinearLayout.LayoutParams(-1, t.dp(170)));
        ColumnLayout column = new ColumnLayout();
        column.setClipChildren(false);
        content.addView(column, new LinearLayout.LayoutParams(-1, -2));
        pageBox = new LinearLayout(this);
        pageBox.setOrientation(LinearLayout.VERTICAL);
        pageBox.setClipChildren(false);
        pageBox.setPadding(t.dp(GUTTER), 0, 0, 0);
        pageBox.setClipToPadding(false);
        column.addView(pageBox);
        return col;
    }

    /** Margen a la izquierda de la página, donde van las asas de los bloques. */
    private static final int GUTTER = 30;

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
                c.measure(MeasureSpec.makeMeasureSpec(inner(w) + t.dp(GUTTER), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                h = c.getMeasuredHeight();
            }
            setMeasuredDimension(w, h);
        }

        @Override protected void onLayout(boolean changed, int l, int tp, int r, int b) {
            if (getChildCount() == 0) return;
            int w = r - l, in = inner(w), x = (w - in) / 2;
            View c = getChildAt(0);
            // La caja llega hasta el margen: si no, las asas de los bloques se verían
            // pero no recibirían toques.
            c.layout(x - t.dp(GUTTER), 0, x + in, c.getMeasuredHeight());
        }
    }

    // ---------------------------------------------------------------- barra lateral

    private void renderSidebar() {
        noteList.removeAllViews();
        HashMap<String, String> placed = noteFolders();
        int loose = 0;
        for (JSONObject n : sortedNotes()) {
            if (placed.containsKey(n.optString("id"))) continue;
            noteList.addView(sideNoteRow(n, t.dp(6)));
            loose++;
        }
        if (loose == 0) noteList.addView(sideHint(notes.list().isEmpty() ? "Aún no hay notas" : "Todas están en carpetas"));

        folderList.removeAllViews();
        nbList.removeAllViews();
        // Primero las carpetas, cada una con sus cuadernos; después los sueltos.
        java.util.Set<String> closed = prefs.getStringSet("foldersClosed", java.util.Collections.emptySet());
        for (String folder : childFolders("")) sideFolder(folder, 0, closed);
        if (childFolders("").isEmpty()) folderList.addView(sideHint("Toca + para crear una carpeta"));
        for (Ink.Notebook n : notebooksIn("")) nbList.addView(notebookRow(n, t.dp(6)));
        if (notebooksIn("").isEmpty()) nbList.addView(sideHint(store.notebooks().isEmpty() ? "Aún no hay cuadernos" : "Todos están en carpetas"));
        View spacer = new View(this);
        nbList.addView(spacer, new LinearLayout.LayoutParams(-1, t.dp(12)));
    }

    /** Una carpeta en la barra lateral y, si está abierta, sus subcarpetas y cuadernos. */
    private void sideFolder(String folder, int depth, java.util.Set<String> closed) {
        ArrayList<Ink.Notebook> inside = notebooksIn(folder);
        ArrayList<String> subs = childFolders(folder);
        boolean open = !closed.contains(folder);
        ArrayList<JSONObject> ns = notesIn(folder);
        folderList.addView(folderRow(folder, inside.size() + subs.size() + ns.size(), open, depth));
        if (!open) return;
        for (String sub : subs) sideFolder(sub, depth + 1, closed);
        for (Ink.Notebook n : inside) folderList.addView(notebookRow(n, t.dp(24 + 16 * depth)));
        for (JSONObject n : ns) folderList.addView(sideNoteRow(n, t.dp(30 + 16 * depth)));
    }

    private View sideNoteRow(JSONObject n, int indent) {
        String id = n.optString("id");
        String ic = n.optString("icon");
        LinearLayout r = sideRow(id.equals(noteId));
        r.setPadding(indent, 0, t.dp(10), 0);
        TextView em = text(ic.isEmpty() ? "📄" : ic, 15, t.text, t.regular);
        em.setGravity(Gravity.CENTER);
        r.addView(em, new LinearLayout.LayoutParams(t.dp(26), -2));
        r.addView(sideTitle(n.optString("title"), id.equals(noteId)), new LinearLayout.LayoutParams(0, -2, 1));
        r.setOnClickListener(v -> openNote(id));
        r.setOnLongClickListener(v -> { confirmDeleteNote(id); return true; });
        return r;
    }

    /** Ir a una carpeta (o a Inicio con null), saliendo de la papelera. */
    private void goTo(String folder) {
        flushSave();
        setFolderView(folder);
        showHome();
    }

    private View notebookRow(Ink.Notebook n, int indent) {
        LinearLayout r = sideRow(false);
        r.setPadding(indent, 0, t.dp(10), 0);
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
        return r;
    }

    /** Tocar el nombre abre la carpeta; la flecha solo pliega su lista. */
    private View folderRow(String folder, int count, boolean open, int depth) {
        LinearLayout r = sideRow(folder.equals(folderView) && noteId == null);
        r.setPadding(t.dp(6 + 16 * depth), 0, t.dp(10), 0);
        TextView arrow = text(open ? "▾" : "▸", 15, t.faint, t.regular);
        arrow.setGravity(Gravity.CENTER);
        arrow.setContentDescription((open ? "Plegar " : "Desplegar ") + folder);
        arrow.setOnClickListener(v -> {
            java.util.HashSet<String> closed = new java.util.HashSet<>(prefs.getStringSet("foldersClosed", java.util.Collections.emptySet()));
            if (!closed.remove(folder)) closed.add(folder);
            prefs.edit().putStringSet("foldersClosed", closed).apply();
            renderSidebar();
        });
        r.addView(arrow, new LinearLayout.LayoutParams(t.dp(30), -1));
        r.addView(icon(IconView.FOLDER, hasFolderColor(folder) ? folderColor(folder) : t.muted, ""), new LinearLayout.LayoutParams(t.dp(22), t.dp(22)));
        r.addView(sideTitle(leaf(folder), folder.equals(folderView) && noteId == null), new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(text(String.valueOf(count), 12, t.faint, t.regular));
        IconView add = icon(IconView.PLUS, t.faint, "Crear en " + leaf(folder));
        add.setOnClickListener(v -> { goTo(folder); createMenu(); });
        r.addView(add, new LinearLayout.LayoutParams(t.dp(30), t.dp(30)));
        r.setContentDescription("Carpeta " + folder);
        r.setOnClickListener(v -> { flushSave(); setFolderView(folder); showHome(); });
        r.setOnLongClickListener(v -> { folderMenu(folder); return true; });
        return r;
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
        toggleTheme(null);
    }

    /** `reopen`: el dock que estaba abierto vuelve a abrirse ya con el tema nuevo. */
    private void toggleTheme(String reopen) {
        flushSave();
        prefs.edit().putInt("theme", t.dark ? 1 : 2).putString("reopen", reopen).apply();
        recreate();
    }

    /** Tras cambiar de tema: el mismo dock, sin que el usuario lo pierda de vista. */
    private void reopenDock() {
        String which = prefs.getString("reopen", null);
        if (which == null) return;
        prefs.edit().remove("reopen").apply();
        main.post(() -> {
            if ("personalize".equals(which)) personalize();
            else if ("more".equals(which)) moreMenu(null);
        });
    }

    /** Cambios de otro aparato (la app del PC o la tablet): la portada se pone al día, sin parpadear con cada trazo. */
    private final Runnable remoteRefresh = () -> {
        if (isFinishing()) return;
        renderSidebar();
        if (noteId == null && !selecting && !dock.isOpen()) showHome();
    };

    @Override
    public void onRemote(String notebookId) {
        main.removeCallbacks(remoteRefresh);
        main.postDelayed(remoteRefresh, 400);
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
        connText.setText(Updater.DESKTOP ? (online ? "Servidor de este PC en marcha" : "Arrancando el servidor…")
                : online ? "Conectado al PC · " + where : "Sin conexión al PC");
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
        if (trashView) {
            showTrash();
            return;
        }
        if (folderView != null && !folders().contains(folderView)) setFolderView(null);
        if (folderView != null) {
            showFolder();
            return;
        }
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
        pageBox.addView(homeActions());

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

    /** Por dentro de una carpeta: sus cuadernos y cómo meter más. */
    private void showFolder() {
        boolean arriving = noteId != null || pageBox.getChildCount() == 0 || !folderView.equals(pageBox.getTag());
        noteId = null;
        note = null;
        editor = null;
        prefs.edit().remove("last").apply();
        pageBox.removeAllViews();
        pageBox.setTag(folderView);
        crumb.setText("🏠  Inicio  ›  " + folderView.replace("/", "  ›  "));
        saveState.setText("");

        View ic = new FolderArt(this, folderColor(folderView));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(t.dp(84), t.dp(72));
        ilp.topMargin = -t.dp(40);
        pageBox.addView(ic, ilp);
        TextView title = text(leaf(folderView), 36, t.text, t.bold);
        title.setPadding(0, t.dp(14), 0, t.dp(6));
        pageBox.addView(title);
        int nb = notebooksIn(folderView).size(), nn = notesIn(folderView).size(), nf = childFolders(folderView).size(), nd = documentCards(folderView).size();
        String what = nb + nn + nf + nd == 0 ? "Carpeta vacía" : (nf == 0 ? "" : (nf == 1 ? "1 carpeta · " : nf + " carpetas · ")) + (nb == 1 ? "1 cuaderno" : nb + " cuadernos")
                + (nn == 0 ? "" : nn == 1 ? " · 1 nota" : " · " + nn + " notas") + (nd == 0 ? "" : nd == 1 ? " · 1 documento" : " · " + nd + " documentos");
        pageBox.addView(text(what, 15, t.faint, t.regular));
        LinearLayout actions = row();
        actions.setPadding(0, t.dp(14), 0, 0);
        String up = parentOf(folderView);
        actions.addView(pill(up.isEmpty() ? "‹  Inicio" : "‹  " + leaf(up), v -> { setFolderView(up.isEmpty() ? null : up); showHome(); }));
        String here = folderView;
        actions.addView(pill("Opciones de la carpeta", v -> folderMenu(here)));
        actions.addView(pill(selecting ? "Terminar selección" : "Seleccionar", v -> setSelecting(!selecting)));
        pageBox.addView(actions);
        gallery();
        View end = new View(this);
        pageBox.addView(end, new LinearLayout.LayoutParams(-1, t.dp(120)));
        renderSidebar();
        if (arriving) scroller.post(() -> scroller.scrollTo(0, 0));
    }

    private TextView pill(String s, View.OnClickListener click) {
        TextView tv = text(s, 14, t.muted, t.medium);
        tv.setGravity(Gravity.CENTER);
        tv.setMinHeight(t.dp(44));
        tv.setPadding(t.dp(14), 0, t.dp(14), 0);
        tv.setBackground(pressable(false));
        tv.setOnClickListener(click);
        return tv;
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
        if (selecting) {
            boolean on = picked.contains("note:" + id);
            r.addView(checkMark(on), 0, new LinearLayout.LayoutParams(t.dp(30), t.dp(30)));
            r.setOnClickListener(v -> togglePick("note:" + id));
            return r;
        }
        r.setOnClickListener(v -> openNote(id));
        r.setOnLongClickListener(v -> { setSelecting(true); togglePick("note:" + id); return true; });
        return r;
    }

    /** Galería de cuadernos: vista previa de la primera página, título y datos. */
    private void gallery() {
        ArrayList<Ink.Notebook> list = notebooksIn(folderView == null ? "" : folderView);
        list.sort((a, b) -> Long.compare(b.updated, a.updated));
        ArrayList<View> views = new ArrayList<>();
        String here = folderView;
        if (here == null) {
            for (String f : childFolders("")) views.add(folderCard(f));
            for (Ink.Notebook n : list) views.add(card(n));
            views.addAll(documentCards(""));
            if (!selecting) views.add(actionCard(IconView.PLUS, "Crear", v -> createMenu()));
            grid(views);
            return;
        }
        // Todo junto: subcarpetas, cuadernos y notas, y un solo «Crear».
        for (String f : childFolders(here)) views.add(folderCard(f));
        for (Ink.Notebook n : list) views.add(card(n));
        for (JSONObject n : notesIn(here)) views.add(noteCard(n));
        views.addAll(documentCards(here));
        if (!selecting) views.add(actionCard(IconView.PLUS, "Crear", v -> createMenu()));
        grid(views);
    }

    /** Una nota como tarjeta, junto a los cuadernos. */
    private View noteCard(JSONObject n) {
        String id = n.optString("id");
        LinearLayout c = cardBox();
        FrameLayout art = new FrameLayout(this);
        art.setBackgroundColor(t.callout);
        String ic = n.optString("icon");
        TextView em = text(ic.isEmpty() ? "📄" : ic, 34, t.text, t.regular);
        em.setGravity(Gravity.CENTER);
        art.addView(em, new FrameLayout.LayoutParams(-1, t.dp(64), Gravity.TOP));
        String body = Notes.text(n);
        TextView snip = text(body, 12, t.muted, t.regular);
        snip.setMaxLines(4);
        snip.setEllipsize(TextUtils.TruncateAt.END);
        snip.setPadding(t.dp(14), 0, t.dp(14), t.dp(8));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        art.addView(snip, slp);
        c.addView(art, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(t.dp(12), t.dp(10), t.dp(12), t.dp(12));
        String title = n.optString("title");
        TextView name = text(title.isEmpty() ? "Sin título" : title, 15, t.text, t.medium);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name);
        String when = n.optLong("updated") > 0 ? " · " + DateUtils.getRelativeTimeSpanString(n.optLong("updated"), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) : "";
        TextView sub = text("Nota" + when, 12, t.faint, t.regular);
        sub.setPadding(0, t.dp(4), 0, 0);
        info.addView(sub);
        c.addView(info);
        if (selecting) {
            boolean on = picked.contains("note:" + id);
            FrameLayout wrap = new FrameLayout(this);
            wrap.addView(c, new FrameLayout.LayoutParams(-1, -1));
            wrap.addView(checkMark(on), new FrameLayout.LayoutParams(t.dp(30), t.dp(30), Gravity.TOP | Gravity.END));
            wrap.setOnClickListener(v -> togglePick("note:" + id));
            c.setClickable(false);
            return wrap;
        }
        c.setOnClickListener(v -> openNote(id));
        c.setOnLongClickListener(v -> { setSelecting(true); togglePick("note:" + id); return true; });
        return c;
    }

    private void grid(ArrayList<View> views) {
        GridLayout grid = new GridLayout(this);
        int width = pageBox.getWidth() - pageBox.getPaddingLeft();
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels - t.dp(sidebar.getVisibility() == View.VISIBLE ? 400 : 130);
        int gap = t.dp(16);
        int cols = Math.max(2, Math.min(5, (width + gap) / (t.dp(190) + gap)));
        int cellW = (width - gap * (cols - 1)) / cols;
        grid.setColumnCount(cols);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = t.dp(16);
        pageBox.addView(grid, glp);
        int i = 0;
        for (View v : views) grid.addView(v, cell(i++, cols, cellW, gap));
    }

    /** La portada de una carpeta toma el color de su primer cuaderno. */
    private View folderCard(String folder) {
        ArrayList<Ink.Notebook> inside = notebooksIn(folder);
        LinearLayout c = cardBox();
        if (Looks.has(this, "folder:" + folder)) {
            ImageView photo = new ImageView(this);
            photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            showLook(photo, "folder:" + folder);
            c.addView(photo, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            c.addView(new FolderArt(this, folderColor(folder)), new LinearLayout.LayoutParams(-1, 0, 1));
        }
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(t.dp(12), t.dp(10), t.dp(12), t.dp(12));
        TextView name = text(leaf(folder), 15, t.text, t.medium);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name);
        int docs = inside.size() + notesIn(folder).size() + childFolders(folder).size();
        for (SheetFiles.Doc d : SheetFiles.list(this)) if (d.folder.equals(folder)) docs++;
        for (DeckActivity.Info d : DeckActivity.list(this)) if (d.folder.equals(folder)) docs++;
        for (DocActivity.Info d : DocActivity.list(this)) if (d.folder.equals(folder)) docs++;
        for (FlowActivity.Info d : FlowActivity.list(this)) if (d.folder.equals(folder)) docs++;
        TextView s = text(docs == 0 ? "Vacía" : docs == 1 ? "1 documento" : docs + " documentos", 12, t.faint, t.regular);
        s.setPadding(0, t.dp(4), 0, 0);
        info.addView(s);
        c.addView(info);
        c.setOnClickListener(v -> { setFolderView(folder); showHome(); });
        c.setOnLongClickListener(v -> { folderMenu(folder); return true; });
        c.setContentDescription("Carpeta " + folder);
        return c;
    }

    private static final class FolderArt extends View {
        private final android.graphics.Paint back = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint front = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        FolderArt(android.content.Context c, int color) {
            super(c);
            back.setColor(color);
            front.setColor(color);
            front.setAlpha(205);
            back.setAlpha(120);
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float h = getHeight() * 0.62f, w = h * 1.3f, x = (getWidth() - w) / 2, y = (getHeight() - h) / 2, r = h * 0.09f;
            c.drawRoundRect(x, y, x + w * 0.45f, y + h * 0.4f, r, r, back);
            c.drawRoundRect(x, y + h * 0.12f, x + w, y + h, r, r, back);
            c.drawRoundRect(x, y + h * 0.26f, x + w, y + h, r, r, front);
        }
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
            if (p != null && !Looks.has(this, "nb:" + n.id)) thumbnail(p, t.dp(200), img);
        }
        showLook(img, "nb:" + n.id);
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
        if (selecting) {
            boolean on = picked.contains("nb:" + n.id);
            FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(t.dp(30), t.dp(30), Gravity.TOP | Gravity.END);
            mlp.setMargins(0, t.dp(8), t.dp(8), 0);
            prev.addView(checkMark(on), mlp);
            if (on) prev.setForeground(new android.graphics.drawable.ColorDrawable(0x220F766E));
            c.setOnClickListener(v -> togglePick("nb:" + n.id));
        } else {
            c.setOnClickListener(v -> openNotebook(n, null));
            c.setOnLongClickListener(v -> { notebookMenu(n); return true; });
        }
        c.setContentDescription("Cuaderno " + n.title);
        return c;
    }

    private View actionCard(int glyph, String label, View.OnClickListener click) {
        LinearLayout c = cardBox();
        c.setGravity(Gravity.CENTER);
        IconView ic = icon(glyph, t.faint, "");
        // El icono no se queda con el toque: toda la tarjeta responde.
        ic.setClickable(false);
        c.addView(ic, new LinearLayout.LayoutParams(t.dp(40), t.dp(40)));
        TextView tv = text(label, 15, t.faint, t.medium);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, t.dp(6), 0, 0);
        c.addView(tv);
        c.setOnClickListener(click);
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
        setTrashView(false);
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
        if (folderView != null && !trashView) {
            HashMap<String, String> map = noteFolders();
            map.put(n.optString("id"), folderView);
            keepNoteFolders(map);
        }
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
        dock.confirm("¿Mover «" + (name.isEmpty() ? "Sin título" : name) + "» a la papelera?", "Puedes restaurarla durante 30 días.", "A la papelera", () -> {
            if (id.equals(noteId)) {
                main.removeCallbacks(save);
                note = null;
                noteId = null;
            }
            Trash.note(this, notes, n, noteFolders().get(id));
            renderSidebar();
            showHome();
        });
    }

    private void moreMenu(View anchor) {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        if (note != null) {
            tiles.add(new OptionDock.Tile(dock.letter(note.optString("icon", "📄")), "Cambiar icono", false, this::pickIcon).stays());
            tiles.add(new OptionDock.Tile(dock.glyph(IconView.FOLDER), "Mover a carpeta", false, this::moveNoteToFolder).stays());
            if (Native.available()) tiles.add(new OptionDock.Tile(dock.glyph(IconView.EXPORT), "Exportar a Word", false, this::saveDocx));
        }
        if (Native.available()) tiles.add(new OptionDock.Tile(dock.glyph(IconView.FILE), "Importar Word", false, this::pickDocx));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.EXPORT), "Buscar actualización", false, () -> checkUpdate(true)).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.MOON), t.dark ? "Modo claro" : "Modo oscuro", false, () -> toggleTheme("more")).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.WIFI), "Conectar con el PC", false, this::askWifi).stays());
        if (note != null) tiles.add(new OptionDock.Tile(dock.glyph(IconView.TRASH), "Eliminar esta nota", false, () -> confirmDeleteNote(noteId)).stays());
        dock.tiles("Opciones", tiles);
    }

    // ---------------------------------------------------------------- Word

    private static final int PICK_DOCX = 41, SAVE_DOCX = 42, PICK_LOOK = 43, OPEN_GRAPH = 44, PICK_IMPORT = 45, PICK_MODEL = 46,
            PICK_BACKUP_DEST = 47, PICK_BACKUP_RESTORE = 48, SCAN_QR = 49;
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
        if (request == SCAN_QR) {
            if (result == RESULT_OK && data != null && data.getStringExtra("link") != null) pairLink(android.net.Uri.parse(data.getStringExtra("link")));
            return;
        }
        if (request == OPEN_GRAPH && result == RESULT_OK && data != null && data.getStringExtra("note") != null) {
            openNote(data.getStringExtra("note"));
            return;
        }
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == PICK_LOOK) {
            android.net.Uri uri = data.getData();
            String key = lookKey, title = lookTitle;
            boolean glass = lookGlass;
            new Thread(() -> {
                boolean ok = Looks.save(this, uri, key);
                main.post(() -> {
                    if (!ok) Ui.toast(this, "No se pudo leer esa imagen");
                    applyLooks();
                    if (noteId == null) showHome();
                    editLook(key, title, glass);
                });
            }).start();
        } else if (request == PICK_IMPORT) importFile(data.getData());
        else if (request == PICK_MODEL) startActivity(new Intent(this, ModelActivity.class).setData(data.getData()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        else if (request == PICK_BACKUP_DEST) Backup.chosen(this, data.getData());
        else if (request == PICK_BACKUP_RESTORE) Backup.restore(this, data.getData(), () -> { renderSidebar(); showHome(); });
        else if (request == PICK_DOCX) importDocx(data.getData());
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
            if (folderView != null && noteId == null) {
                n.folder = folderView;
                store.saveNotebooks();
            }
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
        tiles.add(new OptionDock.Tile(lookTile("nb:" + n.id), "Portada", false, () -> editLook("nb:" + n.id, "Portada de «" + n.title + "»", false)).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.STAR), n.favorite ? "Quitar de favoritos" : "Marcar favorito", n.favorite, () -> {
            n.favorite = !n.favorite;
            changed(n);
        }));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.WIFI), "Compartir en vivo", false, () -> showShare(n.id, n.title)).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.FOLDER), "Mover a carpeta", false, () -> moveToFolder(n)).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.FILE), "Exportar PDF", false, () -> PdfActivity.open(this, java.util.Collections.singletonList(n.id), -1)));
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
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.SLIDERS), "Más colores", false,
                () -> ColorWheel.show(this, dock, "Color de «" + n.title + "»", n.color, t.text, t.muted, c -> {
                    n.color = c;
                    changed(n);
                })).stays());
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
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (String folder : folders()) {
            tiles.add(new OptionDock.Tile(dock.glyph(IconView.FOLDER), pretty(folder), folder.equals(n.folder), () -> {
                n.folder = folder;
                changed(n);
            }));
        }
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.PLUS), "Nueva carpeta", false, () -> askNewFolder(n)).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.HOME), "Sin carpeta", n.folder.isEmpty(), () -> {
            n.folder = "";
            changed(n);
        }));
        dock.tiles("Mover «" + n.title + "» a", tiles);
    }

    // ---- Actualizar ----

    private static final long DAY_MS = 24 * 60 * 60 * 1000L;

    /** Sola, una vez al día; `asked` es cuando el usuario lo pide. */
    private void checkUpdate(boolean asked) {
        long now = System.currentTimeMillis();
        if (!asked && now - prefs.getLong("updateChecked", 0) < DAY_MS) return;
        if (asked) Ui.toast(this, "Buscando…");
        new Thread(() -> {
            try {
                Updater.Release r = Updater.latest();
                prefs.edit().putLong("updateChecked", now).apply();
                String mine = Updater.installed(this);
                boolean better = r != null && Version.newer(r.version, mine);
                main.post(() -> {
                    if (isFinishing()) return;
                    if (better) {
                        // Sin interrumpir: si está escribiendo, ya saldrá otro día o en el menú.
                        if (asked || (noteId == null && !dock.isOpen())) offerUpdate(r, mine);
                    } else if (asked) {
                        LinearLayout box = lookBox();
                        box.addView(dock.message("Tienes la última versión: " + mine));
                        dock.show("Actualización", box, new OptionDock.Action("Listo", true, null));
                    }
                });
            } catch (java.io.IOException | JSONException e) {
                android.util.Log.w("SyncTablet", "buscar actualización", e);
                if (asked) main.post(() -> Ui.toast(this, "No se pudo conectar con GitHub"));
            }
        }).start();
    }

    private LinearLayout lookBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(t.dp(20), 0, t.dp(20), t.dp(8));
        return box;
    }

    private void offerUpdate(Updater.Release r, String mine) {
        LinearLayout box = lookBox();
        box.addView(dock.message("Tienes la " + mine + ". Tus apuntes no se tocan." + (r.notes.isEmpty() ? "" : "\n\n" + r.notes)));
        OptionDock.Action go = new OptionDock.Action("Actualizar", true, () -> startUpdate(r));
        go.stays = true;
        dock.show("Versión " + r.version + " disponible", box, new OptionDock.Action("Después", false, null), go);
    }

    private void startUpdate(Updater.Release r) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            LinearLayout box = lookBox();
            box.addView(dock.message("Android pide tu permiso una sola vez: activa «Permitir de esta fuente» y vuelve aquí."));
            OptionDock.Action open = new OptionDock.Action("Abrir ajustes", true, () -> startActivity(
                    new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + getPackageName()))));
            dock.show("Permiso para actualizar", box, new OptionDock.Action("Cancelar", false, null), open);
            return;
        }
        flushSave();
        LinearLayout box = lookBox();
        TextView label = dock.message("Descargando…");
        box.addView(label);
        android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        dock.show("Actualizando a " + r.version, box, new OptionDock.Action("Cancelar", false, () -> cancelled.set(true)));
        new Thread(() -> {
            try {
                java.io.File apk = Updater.download(this, r, p -> main.post(() -> {
                    bar.setProgress(p);
                    label.setText("Descargando… " + p + " %");
                }), cancelled);
                main.post(() -> label.setText("Comprobada. Abriendo el instalador…"));
                Updater.install(this, apk);
            } catch (java.io.IOException | java.security.NoSuchAlgorithmException | SecurityException e) {
                android.util.Log.w("SyncTablet", "actualizar", e);
                if (!cancelled.get()) main.post(() -> {
                    dock.close();
                    Ui.toast(this, "No se pudo actualizar: " + e.getMessage());
                });
            }
        }).start();
    }

    /** Android contesta aquí: pide confirmación al usuario o dice cómo acabó. */
    @SuppressWarnings("deprecation")
    private void installStatus(Intent intent) {
        int status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_FAILURE);
        if (status == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) startActivity(confirm);
            return;
        }
        dock.close();
        if (status == android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED) return;
        if (status != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
            android.util.Log.w("SyncTablet", "instalador: " + intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE));
            Ui.toast(this, "Android no pudo instalar la actualización");
        }
    }

    // ---- Personalizar ----

    /** Fondo de la app y portada de Inicio; los paneles dejan ver la foto como un cristal. */
    private void applyLooks() {
        android.graphics.Bitmap photo = Looks.bitmap(this, Looks.APP);
        wall.setImageBitmap(photo);
        int alpha = Math.round(255 * (0.3f + 0.65f * Looks.dim(this, Looks.APP) / 100f)) << 24;
        rootBox.setBackgroundColor(photo == null ? t.bg : 0);
        sidebar.setBackgroundColor(photo == null ? t.bar : t.bar & 0xFFFFFF | alpha);
        mainCol.setBackgroundColor(photo == null ? 0 : t.bg & 0xFFFFFF | alpha);
        coverPhoto.setVisibility(Looks.has(this, Looks.HOME) ? View.VISIBLE : View.GONE);
        showLook(coverPhoto, Looks.HOME);
    }

    private void showLook(ImageView v, String key) {
        android.graphics.Bitmap b = Looks.bitmap(this, key);
        if (b == null) {
            if (!(v == coverPhoto)) return;
            v.setImageDrawable(null);
            return;
        }
        v.setImageBitmap(b);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        shade(v, Looks.dim(this, key));
    }

    private static void shade(ImageView v, int dim) {
        if (dim <= 0) v.clearColorFilter();
        else v.setColorFilter(Math.round(dim * 2.2f) << 24, android.graphics.PorterDuff.Mode.SRC_ATOP);
    }

    private View lookTile(String key) {
        if (!Looks.has(this, key)) return dock.glyph(IconView.IMAGE);
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setImageBitmap(Looks.bitmap(this, key));
        v.setBackground(rounded(t.card, 10));
        v.setClipToOutline(true);
        return v;
    }

    private void personalize() {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        tiles.add(new OptionDock.Tile(lookTile(Looks.APP), "Fondo de la app", false, () -> editLook(Looks.APP, "Fondo de la app", true)).stays());
        tiles.add(new OptionDock.Tile(lookTile(Looks.HOME), "Portada de Inicio", false, () -> editLook(Looks.HOME, "Portada de Inicio", false)).stays());
        tiles.add(new OptionDock.Tile(lookTile(Looks.DESK), "Detrás de las hojas", false, () -> editLook(Looks.DESK, "Detrás de las hojas", false)).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.SLIDERS), "Barra del cuaderno", false, this::openBarDesigner));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.PAGE), "Formato de cuaderno", false, () -> BarSettings.paperMenu(this, dock)).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.MOON), t.dark ? "Modo claro" : "Modo oscuro", false, () -> toggleTheme("personalize")).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.CLOUD), "Copia en Drive", false, () -> Backup.menu(this, dock)).stays());
        boolean full = Ui.fullscreen(this);
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.EXPAND), full ? "Salir de pantalla completa" : "Pantalla completa", full, () -> {
            Ui.setFullscreen(this, !full);
            personalize();
        }).stays());
        dock.tiles("Personalizar", tiles);
    }

    /** Personalizar la barra sobre un cuaderno de verdad: la página a la izquierda, el panel a la derecha. */
    private void openBarDesigner() {
        flushSave();
        Ink.Notebook n = store.notebooks().isEmpty() ? null : store.notebooks().get(0);
        if (n == null) {
            n = store.createNotebook("Mi cuaderno", Ui.COVERS[0]);
            sync.sendNotebook(n);
            sync.sendPage(store.page(n.pages.get(0)));
        }
        startActivity(new Intent(this, PageActivity.class).putExtra("notebook", n.id).putExtra("design", true));
    }

    /** Foto, difuminado y oscurecido de un sitio; los cambios se ven al momento. */
    private void editLook(String key, String title, boolean glass) {
        lookKey = key;
        lookTitle = title;
        lookGlass = glass;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(t.dp(20), 0, t.dp(20), t.dp(8));
        boolean has = Looks.has(this, key);
        ImageView shot = new ImageView(this);
        shot.setScaleType(ImageView.ScaleType.CENTER_CROP);
        shot.setBackground(rounded(t.hover, 12));
        shot.setClipToOutline(true);
        shot.setContentDescription("Vista previa");
        if (has) {
            shot.setImageBitmap(Looks.bitmap(this, key));
            if (!glass) shade(shot, Looks.dim(this, key));
        }
        box.addView(shot, new LinearLayout.LayoutParams(-1, t.dp(170)));
        LinearLayout actions = row();
        actions.setPadding(0, t.dp(8), 0, t.dp(4));
        actions.addView(pill(has ? "Cambiar foto" : "Elegir foto", v -> {
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(pick, PICK_LOOK);
        }));
        if (has) actions.addView(pill("Quitar", v -> {
            Looks.remove(this, key);
            applyLooks();
            if (noteId == null) showHome();
            editLook(key, title, glass);
        }));
        box.addView(actions);
        if (has) {
            android.widget.SeekBar blur = lookBar(box, "Difuminado", Looks.blur(this, key));
            android.widget.SeekBar dim = lookBar(box, glass ? "Paneles más sólidos" : "Oscurecer", Looks.dim(this, key));
            android.widget.SeekBar.OnSeekBarChangeListener live = new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar s, int v, boolean user) {
                    if (user && s == dim && !glass) shade(shot, v);
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar s) { }
                @Override public void onStopTrackingTouch(android.widget.SeekBar s) {
                    Looks.set(MainActivity.this, key, blur.getProgress(), dim.getProgress());
                    shot.setImageBitmap(Looks.bitmap(MainActivity.this, key));
                    applyLooks();
                    if (noteId == null) showHome();
                }
            };
            blur.setOnSeekBarChangeListener(live);
            dim.setOnSeekBarChangeListener(live);
        }
        dock.show(title, box, new OptionDock.Action("Listo", true, null));
    }

    private android.widget.SeekBar lookBar(LinearLayout box, String label, int value) {
        TextView tv = text(label, 14, t.muted, t.regular);
        tv.setPadding(0, t.dp(8), 0, t.dp(2));
        box.addView(tv);
        android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(100);
        bar.setProgress(value);
        bar.setContentDescription(label);
        box.addView(bar);
        return bar;
    }

    // ---- Organizar materias ----

    /** Un documento suelto y lo que se le propone. */
    private static final class Proposal {
        Ink.Notebook notebook;
        String noteId, title, subject, month, course;
        int confidence;
        boolean chosen = true;
    }

    /** Lee lo escrito a máquina de todo lo suelto y propone; nada se mueve sin confirmar. */
    private void organize() {
        Ui.toast(this, "Leyendo tus documentos…");
        new Thread(() -> {
            HashMap<String, String> learned = new HashMap<>();
            HashMap<String, String> texts = new HashMap<>();
            HashMap<String, String> placed = noteFolders();
            for (Ink.Notebook n : new ArrayList<>(store.notebooks())) {
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < Math.min(n.pages.size(), 40); i++) b.append(Store.text(store.peek(n.pages.get(i))));
                texts.put(n.id, b.toString());
                if (!n.folder.isEmpty()) learned.merge(n.folder, n.title + " " + b, (x, y) -> x + " " + y);
            }
            ArrayList<JSONObject> all = notes.list();
            for (JSONObject n : all) {
                String folder = placed.get(n.optString("id"));
                if (folder != null) learned.merge(folder, n.optString("title") + " " + Notes.text(n), (x, y) -> x + " " + y);
            }
            for (String folder : folders()) learned.putIfAbsent(folder, folder);
            ArrayList<Proposal> found = new ArrayList<>();
            int unread = 0;
            for (Ink.Notebook n : new ArrayList<>(store.notebooks())) {
                if (!n.folder.isEmpty()) continue;
                String text = texts.get(n.id);
                Topics.Guess g = Topics.classify(n.title, text, learned);
                if (g.subject == null) {
                    unread++;
                    continue;
                }
                Proposal p = proposal(n.title, g, text, n.updated);
                p.notebook = n;
                found.add(p);
            }
            for (JSONObject n : all) {
                if (placed.containsKey(n.optString("id"))) continue;
                String title = n.optString("title"), text = Notes.text(n);
                Topics.Guess g = Topics.classify(title, text, learned);
                if (g.subject == null) {
                    unread++;
                    continue;
                }
                Proposal p = proposal(title.isEmpty() ? "Sin título" : title, g, text, n.optLong("updated"));
                p.noteId = n.optString("id");
                found.add(p);
            }
            found.sort((a, b) -> a.subject.compareTo(b.subject));
            int left = unread;
            main.post(() -> {
                if (!isFinishing()) showProposals(found, left);
            });
        }).start();
    }

    private static Proposal proposal(String title, Topics.Guess g, String text, long updated) {
        Proposal p = new Proposal();
        p.title = title;
        p.subject = g.subject;
        p.confidence = g.confidence;
        p.month = Topics.month(text, updated > 0 ? updated : System.currentTimeMillis());
        p.course = Topics.course(text);
        // Si no se está seguro, que no vaya marcado.
        p.chosen = g.confidence >= 55;
        return p;
    }

    private void showProposals(ArrayList<Proposal> found, int left) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(t.dp(20), 0, t.dp(20), t.dp(8));
        String note = left == 0 ? "" : left == 1 ? "1 documento sin texto suficiente (a mano): no lo toco." : left + " documentos sin texto suficiente (a mano): no los toco.";
        if (found.isEmpty()) {
            box.addView(dock.message(note.isEmpty() ? "No hay documentos sueltos por organizar." : note));
            dock.show("Organizar materias", box, new OptionDock.Action("Listo", true, null));
            return;
        }
        if (!note.isEmpty()) box.addView(dock.message(note + " Con el lápiz neurona sus palabras clave también cuentan."));
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        for (Proposal p : found) {
            LinearLayout r = row();
            r.setPadding(t.dp(8), t.dp(8), t.dp(4), t.dp(8));
            r.setBackground(pressable(false));
            TextView mark = text(p.chosen ? "☑" : "☐", 22, t.text, t.regular);
            r.addView(mark, new LinearLayout.LayoutParams(t.dp(36), -2));
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(p.title, 15, t.text, t.medium);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(name);
            TextView sub = text(describe(p), 13, t.muted, t.regular);
            sub.setPadding(0, t.dp(3), 0, 0);
            info.addView(sub);
            r.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            r.setOnClickListener(v -> {
                p.chosen = !p.chosen;
                mark.setText(p.chosen ? "☑" : "☐");
            });
            r.addView(pill("Cambiar", v -> {
                EditText e = field(p.subject);
                ask("Materia de «" + p.title + "»", e, "Usar", () -> {
                    String subject = e.getText().toString().trim();
                    if (!subject.isEmpty()) {
                        p.subject = subject;
                        p.chosen = true;
                        p.confidence = 100;
                    }
                    showProposals(found, left);
                });
            }));
            list.addView(r);
        }
        box.addView(scroll, new LinearLayout.LayoutParams(-1, t.dp(Math.min(400, 68 * found.size()))));
        dock.show("Organizar materias", box, new OptionDock.Action("Cancelar", false, null), new OptionDock.Action("Ordenar", true, () -> applyProposals(found)));
    }

    private static String describe(Proposal p) {
        String s = p.subject + " · " + p.month;
        if (!p.course.isEmpty()) s += " · " + p.course;
        return s + (p.confidence >= 100 ? "" : " · " + p.confidence + " %");
    }

    /** Cada materia es una carpeta; una sigla de curso queda en su nombre. */
    private void applyProposals(ArrayList<Proposal> found) {
        java.util.TreeSet<String> all = folders();
        HashMap<String, String> map = noteFolders();
        int moved = 0;
        for (Proposal p : found) {
            if (!p.chosen) continue;
            String folder = p.subject;
            boolean code = p.course.matches("[A-Z]{2,5}\\d{3,5}");
            if (code && !all.contains(folder)) folder += " · " + p.course;
            all.add(folder);
            if (p.notebook != null) {
                p.notebook.folder = folder;
                sync.sendNotebook(p.notebook);
            } else {
                map.put(p.noteId, folder);
            }
            moved++;
        }
        keepFolders(all);
        keepNoteFolders(map);
        store.saveNotebooks();
        renderSidebar();
        if (noteId == null) showHome();
        Ui.toast(this, moved == 1 ? "1 documento ordenado" : moved + " documentos ordenados");
    }

    // ---- Carpetas ----
    // Un cuaderno guarda el nombre de su carpeta (y eso llega al PC); las que
    // aún están vacías solo existen en esta lista de la tablet.

    private java.util.TreeSet<String> folders() {
        java.util.TreeSet<String> all = new java.util.TreeSet<>(prefs.getStringSet("folders", java.util.Collections.emptySet()));
        for (Ink.Notebook n : store.notebooks()) if (!n.folder.isEmpty()) all.add(n.folder);
        all.addAll(noteFolders().values());
        // Una subcarpeta («Universidad/Cálculo») hace existir a sus padres.
        for (String f : new ArrayList<>(all)) for (String p = parentOf(f); !p.isEmpty(); p = parentOf(p)) all.add(p);
        return all;
    }

    static String parentOf(String folder) {
        int i = folder.lastIndexOf('/');
        return i < 0 ? "" : folder.substring(0, i);
    }

    static String leaf(String folder) {
        return folder.substring(folder.lastIndexOf('/') + 1);
    }

    /** «Universidad/Cálculo» → «Universidad › Cálculo», para elegir dónde mover. */
    static String pretty(String folder) {
        return folder.replace("/", " › ");
    }

    private static boolean inside(String path, String folder) {
        return path.equals(folder) || path.startsWith(folder + "/");
    }

    /** Las carpetas que cuelgan directamente de `parent` ("" = Inicio). */
    private ArrayList<String> childFolders(String parent) {
        ArrayList<String> list = new ArrayList<>();
        for (String f : folders()) if (parentOf(f).equals(parent)) list.add(f);
        return list;
    }

    private JSONObject folderColors() {
        try {
            return new JSONObject(prefs.getString("folderColors", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private boolean hasFolderColor(String folder) {
        return folderColors().has(folder);
    }

    /** El color elegido; si no, el de su primer cuaderno. */
    private int folderColor(String folder) {
        JSONObject o = folderColors();
        if (o.has(folder)) return o.optInt(folder);
        ArrayList<Ink.Notebook> in = notebooksIn(folder);
        return in.isEmpty() ? t.faint : in.get(0).color;
    }

    private void setFolderColor(String folder, Integer color) {
        JSONObject o = folderColors();
        try {
            if (color == null) o.remove(folder);
            else o.put(folder, (int) color);
        } catch (JSONException ignored) {
        }
        prefs.edit().putString("folderColors", o.toString()).apply();
        renderSidebar();
        if (noteId == null) showHome();
    }

    private void chooseFolderColor(String folder) {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        boolean custom = hasFolderColor(folder);
        int now = folderColor(folder);
        int[] palette = {0xFF0F766E, 0xFF2563EB, 0xFF7C3AED, 0xFFDB2777, 0xFFDC2626, 0xFFEA580C, 0xFFCA8A04, 0xFF16A34A, 0xFF0EA5E9, 0xFF475569};
        for (int c : palette) tiles.add(new OptionDock.Tile(dock.swatch(c), "", custom && c == now, () -> setFolderColor(folder, c)));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.SLIDERS), "Más colores", false,
                () -> ColorWheel.show(this, dock, "Color de «" + leaf(folder) + "»", now, t.text, t.muted, c -> setFolderColor(folder, c))).stays());
        if (custom) tiles.add(new OptionDock.Tile(dock.glyph(IconView.PAGES), "Como su cuaderno", false, () -> setFolderColor(folder, null)));
        dock.tiles("Color de «" + leaf(folder) + "»", tiles);
    }

    /** Nota → carpeta. Las notas no viajan con carpeta al PC: esto es de la tablet. */
    private HashMap<String, String> noteFolders() {
        HashMap<String, String> map = new HashMap<>();
        try {
            JSONObject o = new JSONObject(prefs.getString("noteFolders", "{}"));
            for (java.util.Iterator<String> k = o.keys(); k.hasNext(); ) {
                String id = k.next();
                if (notes.note(id) != null) map.put(id, o.getString(id));
            }
        } catch (JSONException ignored) {
        }
        return map;
    }

    private void keepNoteFolders(HashMap<String, String> map) {
        prefs.edit().putString("noteFolders", new JSONObject(map).toString()).apply();
    }

    private ArrayList<JSONObject> notesIn(String folder) {
        ArrayList<JSONObject> list = new ArrayList<>();
        HashMap<String, String> map = noteFolders();
        for (JSONObject n : sortedNotes()) if (folder.equals(map.get(n.optString("id")))) list.add(n);
        return list;
    }

    private void moveNoteToFolder() {
        String id = noteId;
        HashMap<String, String> map = noteFolders();
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (String folder : folders()) {
            tiles.add(new OptionDock.Tile(dock.glyph(IconView.FOLDER), pretty(folder), folder.equals(map.get(id)), () -> {
                map.put(id, folder);
                keepNoteFolders(map);
                renderSidebar();
            }));
        }
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.HOME), "Sin carpeta", !map.containsKey(id), () -> {
            map.remove(id);
            keepNoteFolders(map);
            renderSidebar();
        }));
        dock.tiles("Mover la nota a", tiles);
    }

    private void keepFolders(java.util.Set<String> all) {
        prefs.edit().putStringSet("folders", new java.util.HashSet<>(all)).apply();
    }

    private ArrayList<Ink.Notebook> notebooksIn(String folder) {
        ArrayList<Ink.Notebook> list = new ArrayList<>();
        for (Ink.Notebook n : store.notebooks()) if (n.folder.equals(folder)) list.add(n);
        return list;
    }

    /** Dentro de una carpeta, Atrás vuelve a Inicio en vez de cerrar la app. */
    private void setFolderView(String folder) {
        folderView = folder;
        setTrashView(false);
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        if (folder != null && folderBack == null) {
            android.window.OnBackInvokedCallback back = this::backFromFolder;
            folderBack = back;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, back);
        } else if (folder == null && folderBack != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) folderBack);
            folderBack = null;
        }
    }

    private void backFromFolder() {
        flushSave();
        if (noteId == null) {
            String up = folderView == null ? "" : parentOf(folderView);
            setFolderView(up.isEmpty() ? null : up);
        }
        showHome();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (dock.isOpen()) dock.close();
        else if (trashView) {
            setTrashView(false);
            showHome();
        } else if (folderView != null) backFromFolder();
        else super.onBackPressed();
    }

    /** Pide el nombre; si viene un cuaderno, lo mete en ella. */
    private void askNewFolder(Ink.Notebook n) {
        askNewFolder(n, "");
    }

    /** Carpeta nueva dentro de `parent` ("" = en Inicio). */
    private void askNewFolder(Ink.Notebook n, String parent) {
        EditText e = field("");
        e.setHint("Universidad, Proyectos…");
        ask(parent.isEmpty() ? "Carpeta nueva" : "Carpeta nueva en «" + leaf(parent) + "»", e, "Crear", () -> {
            String name = e.getText().toString().trim().replace('/', '-');
            if (name.isEmpty()) return;
            if (!parent.isEmpty()) name = parent + "/" + name;
            java.util.TreeSet<String> all = folders();
            all.add(name);
            keepFolders(all);
            if (n != null) {
                n.folder = name;
                changed(n);
                return;
            }
            setFolderView(name);
            flushSave();
            showHome();
        });
    }

    private void folderMenu(String folder) {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.FOLDER), "Abrir", false, () -> { flushSave(); setFolderView(folder); showHome(); }));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.PAGES), "Agregar cuadernos", false, () -> addNotebooks(folder)).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.FOLDER), "Nueva subcarpeta", false, () -> askNewFolder(null, folder)).stays());
        tiles.add(new OptionDock.Tile(dock.swatch(folderColor(folder)), "Color", false, () -> chooseFolderColor(folder)).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.TEXT), "Renombrar", false, () -> renameFolder(folder)).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.SELECT), "Mover dentro de…", false, () -> moveFolder(folder)).stays());
        tiles.add(new OptionDock.Tile(lookTile("folder:" + folder), "Portada", false, () -> editLook("folder:" + folder, "Portada de «" + leaf(folder) + "»", false)).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.TRASH), "Borrar carpeta", false, () -> confirmDeleteFolder(folder)).stays());
        dock.tiles(pretty(folder), tiles);
    }

    /** Tocar un cuaderno lo mete o lo saca; el dock sigue abierto para elegir varios. */
    private void addNotebooks(String folder) {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        ArrayList<Ink.Notebook> list = new ArrayList<>(store.notebooks());
        list.sort((a, b) -> Long.compare(b.updated, a.updated));
        for (Ink.Notebook n : list) {
            tiles.add(new OptionDock.Tile(dock.swatch(n.color), n.title, n.folder.equals(folder), () -> {
                n.folder = n.folder.equals(folder) ? "" : folder;
                java.util.TreeSet<String> all = folders();
                all.add(folder);
                keepFolders(all);
                changed(n);
                addNotebooks(folder);
            }).stays());
        }
        if (list.isEmpty()) {
            dock.confirm("Aún no hay cuadernos", "Crea uno y podrás guardarlo aquí.", "Listo", () -> { });
            return;
        }
        dock.tiles("Cuadernos en «" + leaf(folder) + "»", tiles);
    }

    private void renameFolder(String folder) {
        EditText e = field(leaf(folder));
        ask("Renombrar carpeta", e, "Guardar", () -> {
            String name = e.getText().toString().trim().replace('/', '-');
            String parent = parentOf(folder);
            if (!parent.isEmpty()) name = parent + "/" + name;
            if (leaf(name).isEmpty() || name.equals(folder)) return;
            repath(folder, name);
        });
    }

    /** Mover una carpeta (con todo lo suyo) dentro de otra, o a Inicio. */
    private void moveFolder(String folder) {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        String parent = parentOf(folder);
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.HOME), "Inicio", parent.isEmpty(), () -> repath(folder, leaf(folder))));
        for (String f : folders()) {
            if (inside(f, folder)) continue;
            tiles.add(new OptionDock.Tile(dock.glyph(IconView.FOLDER), pretty(f), f.equals(parent), () -> repath(folder, f + "/" + leaf(folder))));
        }
        dock.tiles("Mover «" + leaf(folder) + "» a", tiles);
    }

    /** Cambia la ruta de una carpeta y de todo lo que cuelga de ella. */
    private void repath(String from, String to) {
        if (from.equals(to)) return;
        if (folders().contains(to)) {
            Ui.toast(this, "Ya hay una carpeta «" + leaf(to) + "» ahí");
            return;
        }
        java.util.TreeSet<String> all = folders();
        java.util.TreeSet<String> moved = new java.util.TreeSet<>();
        for (String f : all) if (inside(f, from)) moved.add(f);
        JSONObject colors = folderColors();
        JSONObject newColors = new JSONObject();
        java.util.HashSet<String> closed = new java.util.HashSet<>(prefs.getStringSet("foldersClosed", java.util.Collections.emptySet()));
        for (String f : moved) {
            String g = to + f.substring(from.length());
            all.remove(f);
            all.add(g);
            Looks.rename(this, "folder:" + f, "folder:" + g);
            if (closed.remove(f)) closed.add(g);
        }
        for (java.util.Iterator<String> k = colors.keys(); k.hasNext(); ) {
            String f = k.next();
            try {
                newColors.put(inside(f, from) ? to + f.substring(from.length()) : f, colors.get(f));
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString("folderColors", newColors.toString()).putStringSet("foldersClosed", closed).apply();
        keepFolders(all);
        for (Ink.Notebook n : store.notebooks()) {
            if (!inside(n.folder, from)) continue;
            n.folder = to + n.folder.substring(from.length());
            sync.sendNotebook(n);
        }
        store.saveNotebooks();
        HashMap<String, String> map = noteFolders();
        map.replaceAll((id, f) -> inside(f, from) ? to + f.substring(from.length()) : f);
        keepNoteFolders(map);
        for (SheetFiles.Doc d : SheetFiles.list(this)) if (inside(d.folder, from)) {
            d.folder = to + d.folder.substring(from.length());
            SheetFiles.update(this, d);
        }
        for (DeckActivity.Info d : DeckActivity.list(this)) if (inside(d.folder, from)) DeckActivity.setFolder(this, d.id, to + d.folder.substring(from.length()));
        for (DocActivity.Info d : DocActivity.list(this)) if (inside(d.folder, from)) DocActivity.setFolder(this, d.id, to + d.folder.substring(from.length()));
        for (FlowActivity.Info d : FlowActivity.list(this)) if (inside(d.folder, from)) FlowActivity.setFolder(this, d.id, to + d.folder.substring(from.length()));
        if (folderView != null && inside(folderView, from)) setFolderView(to + folderView.substring(from.length()));
        renderSidebar();
        if (noteId == null) showHome();
    }

    /** Lo de dentro (también lo de sus subcarpetas) sube a la carpeta de arriba. */
    private void confirmDeleteFolder(String folder) {
        String up = parentOf(folder);
        int count = 0;
        for (Ink.Notebook n : store.notebooks()) if (inside(n.folder, folder)) count++;
        String where = up.isEmpty() ? "Inicio" : "«" + leaf(up) + "»";
        String what = count == 0 ? "Está vacía." : count == 1 ? "Su cuaderno no se borra: pasa a " + where + "." : "Sus " + count + " cuadernos no se borran: pasan a " + where + ".";
        dock.confirm("¿Borrar la carpeta «" + leaf(folder) + "»?", what, "Borrar carpeta", () -> {
            for (Ink.Notebook n : store.notebooks()) {
                if (!inside(n.folder, folder)) continue;
                n.folder = up;
                sync.sendNotebook(n);
            }
            store.saveNotebooks();
            HashMap<String, String> map = noteFolders();
            map.replaceAll((id, f) -> inside(f, folder) ? up : f);
            map.values().removeIf(String::isEmpty);
            keepNoteFolders(map);
            java.util.TreeSet<String> all = folders();
            for (String f : all) if (inside(f, folder)) Looks.remove(this, "folder:" + f);
            all.removeIf(f -> inside(f, folder));
            keepFolders(all);
            JSONObject colors = folderColors();
            for (java.util.Iterator<String> k = colors.keys(); k.hasNext(); ) if (inside(k.next(), folder)) k.remove();
            prefs.edit().putString("folderColors", colors.toString()).apply();
            if (folderView != null && inside(folderView, folder)) setFolderView(up.isEmpty() ? null : up);
            renderSidebar();
            if (noteId == null) showHome();
        });
    }

    private void changed(Ink.Notebook n) {
        store.saveNotebooks();
        sync.sendNotebook(n);
        renderSidebar();
        if (noteId == null) showHome();
    }

    // ---- Importar y crear ----

    private void importMenu() {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        tiles.add(new OptionDock.Tile(dock.letter("PDF"), "PDF → cuaderno", false, () -> pickImport("application/pdf")));
        if (Native.available()) {
            tiles.add(new OptionDock.Tile(dock.glyph(IconView.DOC), "Word → documento", false, () -> pickImport(DOCX)));
            tiles.add(new OptionDock.Tile(dock.glyph(IconView.SHEET), "Excel → hoja de cálculo", false, () -> pickImport(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel",
                    "application/vnd.oasis.opendocument.spreadsheet", "text/csv", "text/comma-separated-values")));
            tiles.add(new OptionDock.Tile(dock.glyph(IconView.SLIDES), "PowerPoint → presentación", false, () -> pickImport(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation")));
            tiles.add(new OptionDock.Tile(dock.glyph(IconView.PAGES), "PowerPoint → cuaderno (para apuntar encima)", false, () -> {
                pptxToNotebook = true;
                pickImport("application/vnd.openxmlformats-officedocument.presentationml.presentation");
            }));
        }
        tiles.add(new OptionDock.Tile(dock.letter("X++"), "Xournal++ → cuaderno", false, () -> pickImport("*/*")));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.CUBE), "Modelo 3D (STL, OBJ)", false, () -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(i, PICK_MODEL);
        }));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.FILE), "Cualquier archivo", false, () -> pickImport("*/*")));
        dock.tiles("Importar", tiles);
    }

    private void pickImport(String... types) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(types.length == 1 ? types[0] : "*/*");
        if (types.length > 1) i.putExtra(Intent.EXTRA_MIME_TYPES, types);
        startActivityForResult(i, PICK_IMPORT);
    }

    private String displayName(android.net.Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, new String[] {android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
        }
        return "Archivo";
    }

    /** Según la extensión: PDF y PowerPoint a cuaderno, Word a nota, Excel a hoja de cálculo, STL/OBJ al visor. */
    private void importFile(android.net.Uri uri) {
        String name = displayName(uri);
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        String type = getContentResolver().getType(uri);
        String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        if (lower.endsWith(".docx") || (type != null && type.contains("wordprocessing"))) {
            importWordDoc(uri, base);
        } else if (lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".ods") || lower.endsWith(".csv")
                || (type != null && (type.contains("spreadsheet") || type.contains("excel") || type.contains("csv")))) {
            importSheet(uri, name, base);
        } else if (lower.endsWith(".xopp") || lower.endsWith(".xoj")) {
            importXopp(uri, base);
        } else if (lower.endsWith(".stl") || lower.endsWith(".obj")) {
            startActivity(new Intent(this, ModelActivity.class).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } else if ((lower.endsWith(".pptx") || (type != null && type.contains("presentation"))) && !pptxToNotebook && Native.available()) {
            importPresentation(uri, base);
        } else if (lower.endsWith(".pdf") || lower.endsWith(".pptx") || "application/pdf".equals(type) || (type != null && type.contains("presentation"))) {
            pptxToNotebook = false;
            boolean deck = lower.endsWith(".pptx") || (type != null && type.contains("presentation"));
            Ink.Notebook n = store.createNotebook(base, Ui.COVERS[store.notebooks().size() % Ui.COVERS.length]);
            if (folderView != null) {
                n.folder = folderView;
                store.saveNotebooks();
            }
            sync.sendNotebook(n);
            Intent i = new Intent(this, PageActivity.class).putExtra("notebook", n.id).setData(uri)
                    .putExtra("importKind", deck ? "pptx" : "pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } else {
            Ui.toast(this, "Ese archivo no se puede abrir aquí: usa PDF, Word, Excel, PowerPoint, STL u OBJ");
        }
    }

    /** Apuntes de Xournal++: un cuaderno nuevo con sus páginas. */
    private void importXopp(android.net.Uri uri, String title) {
        Ui.toast(this, "Importando de Xournal++…");
        Ink.Notebook n = store.createNotebook(title, Ui.COVERS[store.notebooks().size() % Ui.COVERS.length]);
        String blank = n.pages.get(0);
        if (folderView != null) n.folder = folderView;
        new Thread(() -> {
            int pages = 0;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                pages = Xopp.read(in, store, n);
            } catch (java.io.IOException | RuntimeException e) {
                android.util.Log.w("SyncTablet", "importar xopp", e);
            }
            int made = pages;
            runOnUiThread(() -> {
                if (made == 0) {
                    store.deleteNotebook(n);
                    Ui.toast(this, "No se pudo leer ese archivo de Xournal++");
                    showHome();
                    return;
                }
                store.deletePage(n, blank);
                sync.sendNotebook(n);
                for (String id : n.pages) {
                    Ink.Page pg = store.page(id);
                    if (pg == null) continue;
                    for (Ink.Item it : pg.items) if (it.isImage()) sync.sendAsset(it.asset);
                    sync.sendPage(pg);
                }
                Ui.toast(this, made == 1 ? "1 página importada" : made + " páginas importadas");
                openNotebook(n, n.pages.get(0));
            });
        }, "xopp").start();
    }

    /** «PowerPoint → cuaderno» lo pide así; si no, un PowerPoint abre como presentación. */
    private boolean pptxToNotebook;

    private void importPresentation(android.net.Uri uri, String title) {
        Ui.toast(this, "Abriendo la presentación…");
        new Thread(() -> {
            String id = null;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16];
                for (int k; (k = in.read(buf)) > 0; ) bytes.write(buf, 0, k);
                id = DeckActivity.importPptx(this, bytes.toByteArray(), title, folderView);
            } catch (java.io.IOException | RuntimeException | OutOfMemoryError e) {
                android.util.Log.w("SyncTablet", "importar PowerPoint", e);
            }
            String made = id;
            runOnUiThread(() -> {
                if (made == null) Ui.toast(this, "No se pudo abrir la presentación");
                else startActivity(new Intent(this, DeckActivity.class).putExtra("id", made));
            });
        }, "pptx").start();
    }

    private void importSheet(android.net.Uri uri, String name, String title) {
        Ui.toast(this, "Abriendo la hoja de cálculo…");
        new Thread(() -> {
            String id = null;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16];
                for (int k; (k = in.read(buf)) > 0; ) bytes.write(buf, 0, k);
                long h = Native.gridOpen(name, bytes.toByteArray());
                if (h != 0) {
                    byte[] xlsx = Native.gridSave(h, "xlsx");
                    Native.gridClose(h);
                    if (xlsx != null) {
                        id = Ink.newId();
                        SheetFiles.save(this, id, title, xlsx);
                        if (folderView != null) {
                            SheetFiles.Doc d = SheetFiles.get(this, id);
                            d.folder = folderView;
                            SheetFiles.update(this, d);
                        }
                    }
                }
            } catch (java.io.IOException | RuntimeException | OutOfMemoryError e) {
                android.util.Log.w("SyncTablet", "importar hoja", e);
            }
            String made = id;
            runOnUiThread(() -> {
                if (made == null) Ui.toast(this, "No se pudo leer esa hoja de cálculo");
                else openSheet(made);
            });
        }, "hoja").start();
    }

    private void openSheet(String id) {
        flushSave();
        startActivity(new Intent(this, GridActivity.class).putExtra("id", id));
    }

    /** Un solo «Crear» con todo; lo nuevo va a la carpeta que se ve. */
    private void createMenu() {
        String here = folderView == null ? "" : folderView;
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.PAGES), "Cuaderno", false, this::askNewNotebook).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.TEXT), "Nota", false, this::newNote));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.FOLDER), here.isEmpty() ? "Carpeta" : "Subcarpeta", false, () -> askNewFolder(null, here)).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.WIFI), "Unirse a un compartido", false, this::askJoin).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.IMPORT), "Importar archivo", false, this::importMenu).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.IMAGE), "Importar imagen", false, () -> notebookWith("image")));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.CAMERA), "Tomar foto", false, () -> notebookWith("photo")));
        tiles.add(new OptionDock.Tile(new FlowActivity.ShapeIcon(this, FlowActivity.DECISION, t.text), "Diagrama de flujo", false, this::newFlow));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.PEN), "Hoja infinita", false, this::newBoard));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.DOC), "Documento (Word)", false, this::newDocMenu).stays());
        if (Native.available()) tiles.add(new OptionDock.Tile(dock.glyph(IconView.SHEET), "Hoja de cálculo", false, this::newSheetMenu).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.SLIDES), "Presentación", false, () -> DeckActivity.chooseTemplate(this, dock, folderView)).stays());
        if (!here.isEmpty()) tiles.add(new OptionDock.Tile(dock.glyph(IconView.SELECT), "Traer cuadernos aquí", false, () -> addNotebooks(here)).stays());
        dock.tiles(here.isEmpty() ? "Crear" : "Crear en «" + leaf(here) + "»", tiles);
    }

    /** Cuaderno nuevo que se abre pidiendo una imagen de la galería o una foto. */
    private void notebookWith(String start) {
        flushSave();
        String title = "image".equals(start) ? "Imagen" : "Foto";
        Ink.Notebook n = store.createNotebook(title, Ui.COVERS[store.notebooks().size() % Ui.COVERS.length]);
        if (folderView != null) {
            n.folder = folderView;
            store.saveNotebooks();
        }
        sync.sendNotebook(n);
        sync.sendPage(store.page(n.pages.get(0)));
        startActivity(new Intent(this, PageActivity.class).putExtra("notebook", n.id).putExtra("start", start));
    }

    private void newBoard() {
        flushSave();
        String id = FlowActivity.create(this, "Hoja infinita", folderView, true);
        if (id == null) Ui.toast(this, "No se pudo crear");
        else startActivity(new Intent(this, FlowActivity.class).putExtra("id", id));
    }

    private void newFlow() {
        flushSave();
        String id = FlowActivity.create(this, "Diagrama", folderView);
        if (id == null) Ui.toast(this, "No se pudo crear");
        else startActivity(new Intent(this, FlowActivity.class).putExtra("id", id));
    }

    private void flowMenu(FlowActivity.Info d) {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.SHAPES), "Abrir", false, () -> startActivity(new Intent(this, FlowActivity.class).putExtra("id", d.id))));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.FOLDER), "Mover a carpeta", false, () -> {
            picked.clear();
            selecting = true;
            picked.add("flow:" + d.id);
            movePicked();
        }).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.TRASH), "Borrar", false, () -> dock.confirm("¿Mover «" + d.title + "» a la papelera?", "Puedes restaurarlo durante 30 días.", "A la papelera", () -> {
            Trash.file(this, Trash.FLOW, d.id, d.title, d.folder, d.updated);
            showHome();
        })).stays());
        dock.tiles(d.title, tiles);
    }

    private void newDocMenu() {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < Templates.DOCS.length; i++) {
            String key = Templates.DOCS[i], name = Templates.DOC_NAMES[i];
            tiles.add(new OptionDock.Tile(dock.letter(Templates.DOC_ICONS[i]), name, false, () -> {
                flushSave();
                String id = DocActivity.create(this, "blank".equals(key) ? "Documento" : name, Templates.docHtml(key), folderView);
                if (id == null) Ui.toast(this, "No se pudo crear");
                else openDoc(id);
            }));
        }
        dock.tiles("Documento nuevo", tiles);
    }

    private void openDoc(String id) {
        flushSave();
        startActivity(new Intent(this, DocActivity.class).putExtra("id", id));
    }

    /** Un Word con su formato, como documento editable. */
    private void importWordDoc(android.net.Uri uri, String title) {
        Ui.toast(this, "Importando el Word…");
        new Thread(() -> {
            String id = null;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16];
                for (int k; (k = in.read(buf)) > 0; ) bytes.write(buf, 0, k);
                String html = Native.docxToHtml(bytes.toByteArray());
                if (html != null) id = DocActivity.create(this, title, html, folderView);
            } catch (java.io.IOException | RuntimeException | OutOfMemoryError e) {
                android.util.Log.w("SyncTablet", "importar Word", e);
            }
            String made = id;
            runOnUiThread(() -> {
                if (made == null) Ui.toast(this, "No se pudo leer ese Word");
                else openDoc(made);
            });
        }, "word").start();
    }

    private void newSheetMenu() {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < Templates.SHEETS.length; i++) {
            String key = Templates.SHEETS[i], name = Templates.SHEET_NAMES[i];
            tiles.add(new OptionDock.Tile(dock.letter(Templates.SHEET_ICONS[i]), name, false, () -> {
                flushSave();
                Intent in = new Intent(this, GridActivity.class).putExtra("title", "blank".equals(key) ? "Hoja de cálculo" : name).putExtra("template", key);
                if (folderView != null) in.putExtra("folder", folderView);
                startActivity(in);
            }));
        }
        dock.tiles("Hoja de cálculo nueva", tiles);
    }

    /** Hojas de cálculo y presentaciones de esta carpeta ("" = sueltas). */
    /** Documentos, hojas, presentaciones, diagramas y hojas infinitas de una carpeta, como tarjetas. */
    private ArrayList<View> documentCards(String folder) {
        ArrayList<View> views = new ArrayList<>();
        for (SheetFiles.Doc d : SheetFiles.list(this)) if (d.folder.equals(folder)) views.add(fileCard("sheet:" + d.id, IconView.SHEET, 0xFF16A34A, d.title, "Hoja de cálculo", d.updated,
                () -> openSheet(d.id), () -> sheetMenu(d)));
        for (DeckActivity.Info d : DeckActivity.list(this)) if (d.folder.equals(folder)) {
            View card = fileCard("deck:" + d.id, IconView.SLIDES, d.accent, d.title,
                    d.slides == 1 ? "1 diapositiva" : d.slides + " diapositivas", d.updated,
                    () -> startActivity(new Intent(this, DeckActivity.class).putExtra("id", d.id)), () -> deckMenu(d));
            // La portada de verdad en vez del icono.
            FrameLayout art = (FrameLayout) ((LinearLayout) card).getChildAt(0);
            art.addView(d.cover(this), 0, new FrameLayout.LayoutParams(-1, -1));
            art.getChildAt(1).setVisibility(View.GONE);
            views.add(card);
        }
        for (DocActivity.Info d : DocActivity.list(this)) if (d.folder.equals(folder)) views.add(fileCard("doc:" + d.id, IconView.DOC, 0xFF2563EB, d.title,
                "Documento", d.updated, () -> openDoc(d.id), () -> docMenu(d)));
        for (FlowActivity.Info d : FlowActivity.list(this)) if (d.folder.equals(folder)) views.add(fileCard("flow:" + d.id, d.board ? IconView.PEN : IconView.SHAPES, d.board ? 0xFF0EA5E9 : 0xFF7C3AED, d.title,
                d.board ? "Hoja infinita" : "Diagrama · " + d.nodes + (d.nodes == 1 ? " figura" : " figuras"), d.updated,
                () -> startActivity(new Intent(this, FlowActivity.class).putExtra("id", d.id)), () -> flowMenu(d)));
        return views;
    }

    private View fileCard(String key, int glyph, int color, String title, String kind, long updated, Runnable open, Runnable menu) {
        LinearLayout c = cardBox();
        FrameLayout art = new FrameLayout(this);
        art.setBackgroundColor(color & 0xFFFFFF | 0x22000000);
        IconView ic = icon(glyph, color, "");
        ic.setScaleX(2.2f);
        ic.setScaleY(2.2f);
        ic.setClickable(false);
        art.addView(ic, new FrameLayout.LayoutParams(t.dp(60), t.dp(60), Gravity.CENTER));
        View band = new View(this);
        band.setBackgroundColor(color);
        art.addView(band, new FrameLayout.LayoutParams(-1, t.dp(4), Gravity.TOP));
        c.addView(art, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(t.dp(12), t.dp(10), t.dp(12), t.dp(12));
        TextView name = text(title, 15, t.text, t.medium);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name);
        String sub = kind + (updated > 0 ? " · " + DateUtils.getRelativeTimeSpanString(updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) : "");
        TextView s = text(sub, 12, t.faint, t.regular);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        s.setPadding(0, t.dp(4), 0, 0);
        info.addView(s);
        c.addView(info);
        if (selecting) {
            boolean on = picked.contains(key);
            FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(t.dp(30), t.dp(30), Gravity.TOP | Gravity.END);
            mlp.setMargins(0, t.dp(8), t.dp(8), 0);
            art.addView(checkMark(on), mlp);
            c.setOnClickListener(v -> togglePick(key));
        } else {
            c.setOnClickListener(v -> open.run());
            c.setOnLongClickListener(v -> { menu.run(); return true; });
        }
        c.setContentDescription(kind + " " + title);
        return c;
    }

    private void sheetMenu(SheetFiles.Doc d) {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.SHEET), "Abrir", false, () -> openSheet(d.id)));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.FOLDER), "Mover a carpeta", false, () -> {
            picked.clear();
            selecting = true;
            picked.add("sheet:" + d.id);
            movePicked();
        }).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.TRASH), "Borrar", false, () -> dock.confirm("¿Mover «" + d.title + "» a la papelera?", "Puedes restaurarlo durante 30 días.", "A la papelera", () -> {
            Trash.file(this, Trash.SHEET, d.id, d.title, d.folder, d.updated);
            showHome();
        })).stays());
        dock.tiles(d.title, tiles);
    }

    private void docMenu(DocActivity.Info d) {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.DOC), "Abrir", false, () -> openDoc(d.id)));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.FOLDER), "Mover a carpeta", false, () -> {
            picked.clear();
            selecting = true;
            picked.add("doc:" + d.id);
            movePicked();
        }).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.TRASH), "Borrar", false, () -> dock.confirm("¿Mover «" + d.title + "» a la papelera?", "Puedes restaurarlo durante 30 días.", "A la papelera", () -> {
            Trash.file(this, Trash.DOC, d.id, d.title, d.folder, d.updated);
            showHome();
        })).stays());
        dock.tiles(d.title, tiles);
    }

    private void deckMenu(DeckActivity.Info d) {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.SLIDES), "Abrir", false, () -> startActivity(new Intent(this, DeckActivity.class).putExtra("id", d.id))));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.PLAY), "Presentar", false, () -> startActivity(new Intent(this, DeckActivity.class).putExtra("id", d.id).putExtra("present", true))));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.FOLDER), "Mover a carpeta", false, () -> {
            picked.clear();
            selecting = true;
            picked.add("deck:" + d.id);
            movePicked();
        }).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.TRASH), "Borrar", false, () -> dock.confirm("¿Mover «" + d.title + "» a la papelera?", "Puedes restaurarlo durante 30 días.", "A la papelera", () -> {
            Trash.file(this, Trash.DECK, d.id, d.title, d.folder, d.updated);
            showHome();
        })).stays());
        dock.tiles(d.title, tiles);
    }

    // ---- Seleccionar varios ----

    /** Botones bajo el título de Inicio: importar archivos y seleccionar varios. */
    private View homeActions() {
        LinearLayout actions = row();
        actions.setPadding(0, t.dp(14), 0, 0);
        actions.addView(actionPill(IconView.IMPORT, "Importar PDF, Word o Excel", v -> importMenu()));
        actions.addView(actionPill(IconView.PLUS, "Crear", v -> createMenu()));
        actions.addView(actionPill(IconView.FILE, "Exportar PDF", v -> exportPdfMenu(new java.util.LinkedHashSet<>())));
        actions.addView(actionPill(IconView.SELECT, selecting ? "Terminar selección" : "Seleccionar", v -> setSelecting(!selecting)));
        actions.addView(actionPill(IconView.HELP, "Tutorial", v -> Tutorial.menu(this, dock)));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.addView(actions);
        return hs;
    }

    private View actionPill(int glyph, String label, View.OnClickListener click) {
        LinearLayout p = row();
        p.setPadding(t.dp(10), 0, t.dp(16), 0);
        GradientDrawable bg = rounded(t.card, 22);
        bg.setStroke(Math.max(1, t.dp(1)), t.line);
        p.setBackground(new RippleDrawable(ColorStateList.valueOf(t.hover | 0x22000000), bg, null));
        p.addView(icon(glyph, t.icon, ""), new LinearLayout.LayoutParams(t.dp(30), t.dp(30)));
        TextView tv = text(label, 14, t.muted, t.medium);
        tv.setPadding(t.dp(6), 0, 0, 0);
        p.addView(tv);
        p.setOnClickListener(click);
        p.setContentDescription(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, t.dp(44));
        lp.rightMargin = t.dp(10);
        p.setLayoutParams(lp);
        return p;
    }

    private View checkMark(boolean on) {
        View v = new View(this) {
            final android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                float w = getWidth(), r = w / 2 - t.dp(2);
                pt.setStyle(android.graphics.Paint.Style.FILL);
                pt.setColor(on ? t.accent : (t.dark ? 0xCC191919 : 0xCCFFFFFF));
                c.drawCircle(w / 2, w / 2, r, pt);
                pt.setStyle(android.graphics.Paint.Style.STROKE);
                pt.setStrokeWidth(t.px(2));
                pt.setColor(on ? t.accent : t.faint);
                c.drawCircle(w / 2, w / 2, r, pt);
                if (on) {
                    pt.setColor(0xFFFFFFFF);
                    pt.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                    c.drawLine(w * 0.3f, w * 0.52f, w * 0.44f, w * 0.66f, pt);
                    c.drawLine(w * 0.44f, w * 0.66f, w * 0.71f, w * 0.36f, pt);
                }
            }
        };
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    private LinearLayout buildPickBar() {
        LinearLayout b = row();
        b.setPadding(t.dp(18), 0, t.dp(8), 0);
        b.setBackground(rounded(t.dark ? 0xF2262626 : 0xF7FFFFFF, 30));
        b.setElevation(t.px(12));
        b.setClickable(true);
        pickCount = text("", 15, t.text, t.medium);
        b.addView(pickCount, new LinearLayout.LayoutParams(-2, -2));
        View gap = new View(this);
        b.addView(gap, new LinearLayout.LayoutParams(t.dp(16), 1));
        b.addView(pill("Todo", v -> pickAll()));
        b.addView(pill("Mover", v -> movePicked()));
        b.addView(pill("PDF", v -> exportPicked()));
        TextView del = pill("Borrar", v -> deletePicked());
        del.setTextColor(t.error);
        b.addView(del);
        b.addView(pill("Listo", v -> setSelecting(false)));
        b.setVisibility(View.GONE);
        return b;
    }

    /** Elegir uno o varios cuadernos (se juntan en un PDF) y pasar a sus páginas. */
    private void exportPdfMenu(java.util.LinkedHashSet<String> chosen) {
        ArrayList<Ink.Notebook> list = new ArrayList<>(store.notebooks());
        if (list.isEmpty()) {
            Ui.toast(this, "Aún no hay cuadernos");
            return;
        }
        list.sort((a, b) -> Long.compare(b.updated, a.updated));
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (Ink.Notebook n : list) {
            tiles.add(new OptionDock.Tile(dock.swatch(n.color), n.title + " · " + n.pages.size(), chosen.contains(n.id), () -> {
                if (!chosen.remove(n.id)) chosen.add(n.id);
                exportPdfMenu(chosen);
            }).stays());
        }
        String head = chosen.isEmpty() ? "¿Qué cuadernos exportar?" : chosen.size() == 1 ? "1 cuaderno" : chosen.size() + " cuadernos en un PDF";
        dock.tiles(head, tiles, new OptionDock.Action("Cancelar", false, null), new OptionDock.Action("Elegir páginas", true, () -> {
            if (chosen.isEmpty()) Ui.toast(this, "Toca uno o varios cuadernos");
            else PdfActivity.open(this, new ArrayList<>(chosen), -1);
        }));
    }

    private void exportPicked() {
        ArrayList<String> ids = new ArrayList<>();
        for (String key : picked) if (key.startsWith("nb:")) ids.add(key.substring(3));
        if (ids.isEmpty()) {
            Ui.toast(this, "Elige uno o varios cuadernos");
            return;
        }
        PdfActivity.open(this, ids, -1);
    }

    private void setSelecting(boolean on) {
        if (selecting == on) return;
        selecting = on;
        picked.clear();
        if (on) {
            pickBar.setVisibility(View.VISIBLE);
            pickBar.setAlpha(0);
            pickBar.setTranslationY(t.dp(40));
            pickBar.animate().alpha(1).translationY(0).setDuration(260).setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0.9f, 0.2f, 1f)).start();
        } else {
            pickBar.animate().alpha(0).translationY(t.dp(40)).setDuration(200).withEndAction(() -> pickBar.setVisibility(View.GONE)).start();
        }
        paintPickBar();
        if (noteId == null) showHome();
    }

    private void togglePick(String key) {
        if (!picked.remove(key)) picked.add(key);
        paintPickBar();
        int y = scroller.getScrollY();
        showHome();
        scroller.post(() -> scroller.scrollTo(0, y));
    }

    private void pickAll() {
        String here = folderView == null ? "" : folderView;
        for (Ink.Notebook n : notebooksIn(here)) picked.add("nb:" + n.id);
        for (JSONObject n : folderView == null ? sortedNotes() : notesIn(here)) picked.add("note:" + n.optString("id"));
        for (SheetFiles.Doc d : SheetFiles.list(this)) if (d.folder.equals(here)) picked.add("sheet:" + d.id);
        for (DeckActivity.Info d : DeckActivity.list(this)) if (d.folder.equals(here)) picked.add("deck:" + d.id);
        for (DocActivity.Info d : DocActivity.list(this)) if (d.folder.equals(here)) picked.add("doc:" + d.id);
        for (FlowActivity.Info d : FlowActivity.list(this)) if (d.folder.equals(here)) picked.add("flow:" + d.id);
        paintPickBar();
        int y = scroller.getScrollY();
        showHome();
        scroller.post(() -> scroller.scrollTo(0, y));
    }

    private void paintPickBar() {
        int n = picked.size();
        pickCount.setText(n == 0 ? "Toca para elegir" : n == 1 ? "1 elegido" : n + " elegidos");
    }

    private void movePicked() {
        if (picked.isEmpty()) {
            Ui.toast(this, "Elige primero cuadernos o notas");
            return;
        }
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (String folder : folders()) tiles.add(new OptionDock.Tile(dock.glyph(IconView.FOLDER), pretty(folder), false, () -> movePickedTo(folder)));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.PLUS), "Nueva carpeta", false, () -> {
            EditText e = field("");
            e.setHint("Universidad, Proyectos…");
            ask("Carpeta nueva", e, "Crear y mover", () -> {
                String name = e.getText().toString().trim();
                if (name.isEmpty()) return;
                java.util.TreeSet<String> all = folders();
                all.add(name);
                keepFolders(all);
                movePickedTo(name);
            });
        }).stays());
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.HOME), "Sin carpeta", false, () -> movePickedTo("")));
        dock.tiles(picked.size() == 1 ? "Mover 1 elemento a" : "Mover " + picked.size() + " elementos a", tiles);
    }

    private void movePickedTo(String folder) {
        HashMap<String, String> map = noteFolders();
        for (String key : picked) {
            if (key.startsWith("nb:")) {
                Ink.Notebook n = store.notebook(key.substring(3));
                if (n == null) continue;
                n.folder = folder;
                sync.sendNotebook(n);
            } else if (key.startsWith("note:")) {
                if (folder.isEmpty()) map.remove(key.substring(5));
                else map.put(key.substring(5), folder);
            } else if (key.startsWith("sheet:")) {
                SheetFiles.Doc d = SheetFiles.get(this, key.substring(6));
                if (d != null) {
                    d.folder = folder;
                    SheetFiles.update(this, d);
                }
            } else if (key.startsWith("deck:")) {
                DeckActivity.setFolder(this, key.substring(5), folder);
            } else if (key.startsWith("doc:")) {
                DocActivity.setFolder(this, key.substring(4), folder);
            } else if (key.startsWith("flow:")) {
                FlowActivity.setFolder(this, key.substring(5), folder);
            }
        }
        store.saveNotebooks();
        keepNoteFolders(map);
        int n = picked.size();
        Ui.toast(this, (n == 1 ? "1 elemento movido" : n + " elementos movidos") + (folder.isEmpty() ? " a Inicio" : " a " + folder));
        setSelecting(false);
        renderSidebar();
        showHome();
    }

    private void deletePicked() {
        if (picked.isEmpty()) {
            Ui.toast(this, "Elige primero cuadernos o notas");
            return;
        }
        int n = picked.size();
        dock.confirm(n == 1 ? "¿Mover 1 elemento a la papelera?" : "¿Mover " + n + " elementos a la papelera?", "Puedes restaurarlos durante 30 días.", "A la papelera", () -> {
            HashMap<String, String> map = noteFolders();
            for (String key : new ArrayList<>(picked)) {
                String id = key.substring(key.indexOf(':') + 1);
                if (key.startsWith("nb:")) {
                    Ink.Notebook nb = store.notebook(id);
                    if (nb != null) Trash.notebook(this, nb);
                } else if (key.startsWith("note:")) {
                    JSONObject note = notes.note(id);
                    if (note != null) Trash.note(this, notes, note, map.get(id));
                } else if (key.startsWith("sheet:")) {
                    SheetFiles.Doc d = SheetFiles.get(this, id);
                    if (d != null) Trash.file(this, Trash.SHEET, id, d.title, d.folder, d.updated);
                } else if (key.startsWith("deck:")) {
                    for (DeckActivity.Info d : DeckActivity.list(this)) if (d.id.equals(id)) Trash.file(this, Trash.DECK, id, d.title, d.folder, d.updated);
                } else if (key.startsWith("doc:")) {
                    for (DocActivity.Info d : DocActivity.list(this)) if (d.id.equals(id)) Trash.file(this, Trash.DOC, id, d.title, d.folder, d.updated);
                } else if (key.startsWith("flow:")) {
                    for (FlowActivity.Info d : FlowActivity.list(this)) if (d.id.equals(id)) Trash.file(this, Trash.FLOW, id, d.title, d.folder, d.updated);
                }
            }
            setSelecting(false);
            renderSidebar();
            showHome();
        });
    }

    // ---- Papelera ----

    private void openTrash() {
        flushSave();
        if (noteId != null) {
            main.removeCallbacks(save);
            note = null;
            noteId = null;
        }
        setTrashView(true);
        showHome();
    }

    private void setTrashView(boolean on) {
        trashView = on;
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        if (on && trashBack == null) {
            android.window.OnBackInvokedCallback back = () -> { setTrashView(false); showHome(); };
            trashBack = back;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT + 1, back);
        } else if (!on && trashBack != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) trashBack);
            trashBack = null;
        }
    }

    private void showTrash() {
        noteId = null;
        note = null;
        editor = null;
        pageBox.removeAllViews();
        crumb.setText("🗑  Papelera");
        saveState.setText("");
        TextView ic = text("🗑", 58, t.text, t.regular);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-2, -2);
        ilp.topMargin = -t.dp(40);
        pageBox.addView(ic, ilp);
        TextView title = text("Papelera", 36, t.text, t.bold);
        title.setPadding(0, t.dp(14), 0, t.dp(6));
        pageBox.addView(title);
        ArrayList<Trash.Entry> list = Trash.list(this);
        pageBox.addView(text(list.isEmpty() ? "Vacía" : (list.size() == 1 ? "1 elemento" : list.size() + " elementos")
                + " · lo borrado se guarda 30 días", 15, t.faint, t.regular));
        LinearLayout actions = row();
        actions.setPadding(0, t.dp(14), 0, 0);
        actions.addView(pill("‹  Inicio", v -> goTo(null)));
        if (!list.isEmpty()) {
            TextView all = pill("Vaciar papelera", v -> dock.confirm("¿Vaciar la papelera?", "Se borra todo para siempre.", "Vaciar", () -> {
                Trash.empty(this);
                showHome();
            }));
            all.setTextColor(t.error);
            actions.addView(all);
        }
        pageBox.addView(actions);
        sectionTitle("Borrado hace poco");
        long now = System.currentTimeMillis();
        for (Trash.Entry e : list) {
            LinearLayout r = row();
            r.setPadding(t.dp(8), t.dp(10), t.dp(4), t.dp(10));
            int glyph = Trash.NOTEBOOK.equals(e.kind) ? IconView.PAGES : Trash.NOTE.equals(e.kind) ? IconView.TEXT : Trash.SHEET.equals(e.kind) ? IconView.SHEET
                    : Trash.DECK.equals(e.kind) ? IconView.SLIDES : Trash.FLOW.equals(e.kind) ? IconView.SHAPES : IconView.DOC;
            IconView iv = icon(glyph, e.color != 0 ? e.color : t.icon, "");
            iv.setClickable(false);
            r.addView(iv, new LinearLayout.LayoutParams(t.dp(34), t.dp(34)));
            LinearLayout txt = new LinearLayout(this);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.setPadding(t.dp(10), 0, 0, 0);
            TextView name = text(e.title.isEmpty() ? "Sin título" : e.title, 16, t.text, t.medium);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            txt.addView(name);
            long day = 24L * 60 * 60 * 1000, left = Math.max(0, (Trash.KEEP_MS - (now - e.when) + day - 1) / day);
            String where = e.folder.isEmpty() ? "" : " · en " + pretty(e.folder);
            txt.addView(text(e.kindName() + where + " · borrado " + DateUtils.getRelativeTimeSpanString(e.when, now, DateUtils.MINUTE_IN_MILLIS)
                    + " · quedan " + left + (left == 1 ? " día" : " días"), 12, t.faint, t.regular));
            r.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(pill("Restaurar", v -> {
                if (!Trash.restore(this, notes, e)) {
                    Ui.toast(this, "No se pudo restaurar");
                    return;
                }
                if (Trash.NOTE.equals(e.kind) && !e.folder.isEmpty()) {
                    HashMap<String, String> map = noteFolders();
                    map.put(e.itemId, e.folder);
                    keepNoteFolders(map);
                }
                Ui.toast(this, "«" + e.title + "» restaurado");
                renderSidebar();
                showHome();
            }));
            TextView del = pill("Borrar", v -> dock.confirm("¿Borrar «" + e.title + "» para siempre?", "No se puede deshacer.", "Borrar", () -> {
                Trash.purge(this, e);
                showHome();
            }));
            del.setTextColor(t.error);
            r.addView(del);
            pageBox.addView(r);
        }
        View end = new View(this);
        pageBox.addView(end, new LinearLayout.LayoutParams(-1, t.dp(120)));
        renderSidebar();
    }

    private void confirmDelete(Ink.Notebook n) {
        dock.confirm("¿Mover «" + n.title + "» a la papelera?", "Se quita de la tablet y del PC. Puedes restaurarlo durante 30 días.", "A la papelera", () -> {
            Trash.notebook(this, n);
            renderSidebar();
            if (noteId == null) showHome();
        });
    }

    // ------------------------------------------------------- cuenta y aparatos

    private static final String[] KIND_NAMES = {"pc", "PC", "desktop", "App del PC", "tablet", "Tablet", "phone", "Celular"};

    private static String kindName(String k) {
        for (int i = 0; i < KIND_NAMES.length; i += 2) if (KIND_NAMES[i].equals(k)) return KIND_NAMES[i + 1];
        return k;
    }

    /** Conectar con el PC: escanear su QR (lo normal) o entrar con la cuenta. */
    private void askWifi() {
        if (Updater.DESKTOP) {
            showPcConnect();
            return;
        }
        Account account = Account.get(this);
        if (account.loggedIn()) showDevices(account);
        else askLogin(account);
    }

    // ------------------------------------------- cuadernos compartidos (estilo Hamachi)

    private static String roleName(String r) {
        return "admin".equals(r) ? "Admin" : "editor".equals(r) ? "Editor" : "Lector";
    }

    /** Todos los compartidos: quién hace de host y quién está. */
    private void showSharedList() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setMinimumWidth(t.dp(Updater.DESKTOP ? 640 : 0));
        box.setPadding(t.dp(18), 0, t.dp(18), t.dp(10));
        box.addView(dock.message("Cuadernos que trabajas con otras personas. El host los mantiene vivos; si se apaga, sigue otro que tenga «Mantener vivo»."));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.addView(dock.message("Buscando…"));
        box.addView(list);
        dock.show("Compartidos", box, new OptionDock.Action("Unirse con un enlace", false, this::askJoin).stays(), new OptionDock.Action("Listo", true, null));
        new Thread(() -> {
            JSONObject l = null;
            String problem = null;
            try {
                l = SharedDocs.list(this);
            } catch (java.io.IOException e) {
                problem = e.getMessage();
            }
            JSONObject fl = l;
            String fp = problem;
            runOnUiThread(() -> {
                list.removeAllViews();
                if (fl == null) {
                    list.addView(dock.message(fp));
                    return;
                }
                org.json.JSONArray docs = fl.optJSONArray("docs");
                if (docs == null || docs.length() == 0) {
                    list.addView(dock.message("Aún no hay ninguno. Para compartir uno: mantén pulsado un cuaderno (clic derecho en el PC) → «Compartir en vivo»."));
                    return;
                }
                for (int i = 0; i < docs.length(); i++) {
                    JSONObject d = docs.optJSONObject(i);
                    int online = 0;
                    org.json.JSONArray peers = d.optJSONArray("peers");
                    for (int k = 0; peers != null && k < peers.length(); k++) if (peers.optJSONObject(k).optBoolean("online")) online++;
                    String host = d.isNull("host") ? "sin host ahora" : "host: " + d.optString("host");
                    String label = (d.optBoolean("keep") ? "🟢 " : "⚪ ") + d.optString("title") + " — " + roleName(d.optString("role")) + " · " + host
                            + " · " + online + (online == 1 ? " persona" : " personas") + " en línea";
                    String nb = d.optString("notebook"), title = d.optString("title");
                    list.addView(dock.row(label, v -> showShare(nb, title)));
                }
            });
        }, "compartidos").start();
    }

    /** Compartir un cuaderno: QR y enlace (editor o lector), Mantener vivo y quién está. */
    private void showShare(String notebookId, String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(Updater.DESKTOP ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setMinimumWidth(t.dp(Updater.DESKTOP ? 820 : 0));
        box.setPadding(t.dp(22), t.dp(4), t.dp(22), t.dp(12));
        ImageView qr = new ImageView(this);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setBackground(Ui.round(0xFFFFFFFF, t.dp(18)));
        qr.setPadding(t.dp(10), t.dp(10), t.dp(10), t.dp(10));
        int side = t.dp(Updater.DESKTOP ? 320 : 260);
        box.addView(qr, new LinearLayout.LayoutParams(side, side));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = Updater.DESKTOP ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2);
        if (Updater.DESKTOP) colLp.leftMargin = t.dp(26);
        else colLp.topMargin = t.dp(14);
        box.addView(col, colLp);
        TextView status = text("Preparando…", 14, t.muted, t.regular);
        col.addView(status);
        LinearLayout roles = row();
        TextView edit = dock.chip("Puede editar", true), view = dock.chip("Solo ver", false);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1);
        rp.rightMargin = t.dp(8);
        roles.addView(edit, rp);
        roles.addView(view, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2);
        gap.topMargin = t.dp(14);
        col.addView(roles, gap);
        TextView copy = dock.chip("📋  Copiar enlace", false);
        LinearLayout.LayoutParams gap2 = new LinearLayout.LayoutParams(-1, -2);
        gap2.topMargin = t.dp(8);
        col.addView(copy, gap2);
        TextView keep = dock.chip("Mantener vivo en " + SharedDocs.where(this), false);
        col.addView(keep, gap2);
        TextView keepNote = text("", 12, t.muted, t.regular);
        keepNote.setPadding(0, t.dp(4), 0, 0);
        col.addView(keepNote);
        TextView people = text("", 14, t.text, t.regular);
        people.setPadding(0, t.dp(14), 0, 0);
        col.addView(people);
        TextView leave = dock.row("Dejar de compartir aquí", null);
        leave.setTextColor(0xFFEB5757);
        col.addView(leave);
        dock.show("Compartir «" + title + "»", box, new OptionDock.Action("Listo", true, null));

        final String[] st = {null, null, null}; // doc, enlace de editor, enlace de lector
        final boolean[] editor = {true}, keepOn = {false};
        Runnable paintQr = () -> {
            String link = editor[0] && st[1] != null ? st[1] : st[2];
            if (link == null || st[0] == null) return;
            boolean ed = editor[0] && st[1] != null;
            new Thread(() -> {
                android.graphics.Bitmap b = null;
                try {
                    b = SharedDocs.qr(this, st[0], link, ed);
                } catch (java.io.IOException ignored) {
                }
                android.graphics.Bitmap fb = b;
                runOnUiThread(() -> { if (fb != null) qr.setImageBitmap(fb); });
            }, "qr").start();
        };
        edit.setOnClickListener(v -> {
            if (st[1] == null) { Ui.toast(this, "Tú sólo puedes invitar a lectores"); return; }
            editor[0] = true;
            dock.setChip(edit, true);
            dock.setChip(view, false);
            paintQr.run();
        });
        view.setOnClickListener(v -> {
            editor[0] = false;
            dock.setChip(edit, false);
            dock.setChip(view, true);
            paintQr.run();
        });
        copy.setOnClickListener(v -> {
            String link = editor[0] && st[1] != null ? st[1] : st[2];
            if (link == null) return;
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Sync Tablet", link));
            Ui.toast(this, editor[0] && st[1] != null ? "Enlace para editar copiado" : "Enlace para ver copiado");
        });
        keep.setOnClickListener(v -> {
            if (st[0] == null) return;
            boolean on = !keepOn[0];
            new Thread(() -> {
                try {
                    SharedDocs.keep(this, st[0], on);
                    runOnUiThread(() -> {
                        keepOn[0] = on;
                        dock.setChip(keep, on);
                        Ui.toast(this, on ? "Ahora " + SharedDocs.where(this) + " es host de «" + title + "»" : "Ya no es host; los demás siguen con otro host");
                    });
                } catch (java.io.IOException e) {
                    runOnUiThread(() -> Ui.toast(this, e.getMessage()));
                }
            }, "keep").start();
        });
        leave.setOnClickListener(v -> dock.confirm("¿Dejar de compartir «" + title + "» aquí?",
                "El cuaderno se queda en este aparato como uno normal; los demás siguen con su copia.", "Dejar de compartir", () -> new Thread(() -> {
                    try {
                        if (st[0] != null) SharedDocs.leave(this, st[0]);
                        runOnUiThread(() -> Ui.toast(this, "«" + title + "» ya no se comparte aquí"));
                    } catch (java.io.IOException e) {
                        runOnUiThread(() -> Ui.toast(this, e.getMessage()));
                    }
                }, "leave").start()));

        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (!dock.isOpen() || box.getParent() == null) return;
            new Thread(() -> {
                JSONObject links = null, doc = null;
                String problem = null;
                try {
                    if (st[0] == null) links = SharedDocs.share(this, notebookId);
                    String id = st[0] != null ? st[0] : links.optString("doc");
                    org.json.JSONArray docs = SharedDocs.list(this).optJSONArray("docs");
                    for (int i = 0; docs != null && i < docs.length(); i++) if (id.equals(docs.optJSONObject(i).optString("doc"))) doc = docs.optJSONObject(i);
                } catch (java.io.IOException e) {
                    problem = e.getMessage();
                }
                JSONObject fl = links, fd = doc;
                String fp = problem;
                runOnUiThread(() -> {
                    if (fp != null) {
                        status.setText(fp);
                        return;
                    }
                    if (fl != null) {
                        st[0] = fl.optString("doc");
                        st[1] = fl.isNull("editor") ? null : fl.optString("editor");
                        st[2] = fl.optString("lector");
                        if (st[1] == null) {
                            editor[0] = false;
                            dock.setChip(edit, false);
                            dock.setChip(view, true);
                        }
                        paintQr.run();
                    }
                    if (fd != null) {
                        keepOn[0] = fd.optBoolean("keep");
                        dock.setChip(keep, keepOn[0]);
                        String host = fd.isNull("host") ? null : fd.optString("host");
                        status.setText("Tu papel: " + roleName(fd.optString("role")) + " · " + (host == null ? "ahora no hay host: los cambios se guardan y se mandan al volver" : "host: " + host));
                        keepNote.setText(keepOn[0] ? "Mientras " + SharedDocs.where(this) + " esté encendido, los demás pueden entrar y trabajar."
                                : "Actívalo para que " + SharedDocs.where(this) + " sea host y el cuaderno siga vivo aunque los demás se vayan.");
                        StringBuilder b = new StringBuilder();
                        org.json.JSONArray peers = fd.optJSONArray("peers");
                        for (int i = 0; peers != null && i < peers.length(); i++) {
                            JSONObject p = peers.optJSONObject(i);
                            b.append(p.optBoolean("online") ? "🟢 " : "⚪ ").append(p.optString("name")).append(p.optBoolean("keep") ? "  (host)" : "").append('\n');
                        }
                        people.setText(b.length() == 0 ? "Todavía no ha entrado nadie. Comparte el QR o el enlace." : "Personas:\n" + b.toString().trim());
                    }
                    status.postDelayed(tick[0], 3000);
                });
            }, "compartir").start();
        };
        tick[0].run();
    }

    /** Entrar a un cuaderno que compartió otra persona: pegar el enlace o escanear su QR. */
    private void askJoin() {
        EditText e = dock.field("", "Pega aquí el enlace (synctablet://doc…)");
        e.setSingleLine(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setMinimumWidth(t.dp(Updater.DESKTOP ? 560 : 0));
        box.setPadding(t.dp(18), 0, t.dp(18), t.dp(10));
        box.addView(dock.message("Pide a quien lo comparte su enlace o su QR. El cuaderno aparecerá en la carpeta «Compartidos»."));
        if (!Updater.DESKTOP) {
            TextView scan = dock.chip("📷  Escanear su QR", true);
            scan.setMinHeight(t.dp(56));
            scan.setOnClickListener(v -> scanQr());
            box.addView(scan, new LinearLayout.LayoutParams(-1, -2));
            TextView or = dock.message("o pega el enlace:");
            or.setPadding(0, t.dp(14), 0, t.dp(4));
            box.addView(or);
        }
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        dock.show("Unirse a un compartido", box, new OptionDock.Action("Cancelar", false, null), new OptionDock.Action("Unirse", true, () -> joinLink(e.getText().toString())));
        dock.focus(e);
    }

    private void joinLink(String link) {
        if (!link.trim().startsWith("synctablet://doc?")) {
            Ui.toast(this, "Ese no es un enlace de Sync Tablet");
            return;
        }
        new Thread(() -> {
            try {
                JSONObject r = SharedDocs.join(this, link.trim());
                runOnUiThread(() -> Ui.toast(this, "Entraste a «" + r.optString("title") + "»: llegará a «Compartidos» en unos segundos"));
            } catch (java.io.IOException e) {
                runOnUiThread(() -> Ui.toast(this, "No se pudo entrar: " + e.getMessage()));
            }
        }, "unirse").start();
    }

    // ------------------------------------------- en el PC: el QR y el servidor de cuentas

    /** GET o POST al servidor de este PC (la app de escritorio lo arranca sola). */
    private static byte[] localHttp(String method, String path) throws java.io.IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("http://127.0.0.1:" + SyncClient.PORT + path).openConnection();
        c.setConnectTimeout(1500);
        c.setReadTimeout(5000);
        c.setRequestMethod(method);
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            try (java.io.InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192];
                for (int k; (k = in.read(b)) > 0; ) out.write(b, 0, k);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject localJson(String method, String path) throws java.io.IOException {
        try {
            return new JSONObject(new String(localHttp(method, path), java.nio.charset.StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            throw new java.io.IOException(e);
        }
    }

    /** «Conectar un aparato»: un QR que cambia solo y se escanea con la tablet o el celular. */
    private void showPcConnect() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setMinimumWidth(t.dp(780));
        box.setPadding(t.dp(24), t.dp(4), t.dp(24), t.dp(14));
        ImageView qr = new ImageView(this);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setBackground(Ui.round(0xFFFFFFFF, t.dp(18)));
        qr.setPadding(t.dp(12), t.dp(12), t.dp(12), t.dp(12));
        qr.setContentDescription("Código QR para conectar un aparato");
        box.addView(qr, new LinearLayout.LayoutParams(t.dp(340), t.dp(340)));
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sideLp = new LinearLayout.LayoutParams(0, -2, 1);
        sideLp.leftMargin = t.dp(28);
        box.addView(side, sideLp);
        side.addView(text("Escanéalo con la tablet o el celular", 22, t.text, t.medium));
        String[] how = {"1.  Abre Sync Tablet en la tablet o el celular.", "2.  Toca «📷 Conectar con el PC».", "3.  Apunta la cámara a este código. ¡Listo!"};
        for (String h : how) {
            TextView step = text(h, 16, t.text, t.regular);
            step.setPadding(0, t.dp(10), 0, 0);
            side.addView(step);
        }
        TextView left = text("Preparando el código…", 13, t.muted, t.regular);
        left.setPadding(0, t.dp(16), 0, 0);
        side.addView(left);
        TextView setup = dock.chip("☁  Crear mi servidor de cuentas", false);
        setup.setOnClickListener(v -> showTrackerSetup());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = t.dp(26);
        side.addView(setup, sp);
        TextView note = text("Para encontrar tus aparatos también fuera de casa. Se hace una sola vez y es gratis.", 13, t.muted, t.regular);
        note.setPadding(0, t.dp(6), 0, 0);
        side.addView(note);
        dock.show("Conectar un aparato", box, new OptionDock.Action("Listo", true, null));

        String[] shown = {null};
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (!dock.isOpen() || box.getParent() == null) return;
            new Thread(() -> {
                String code = null, text;
                android.graphics.Bitmap bmp = null;
                try {
                    JSONObject p = localJson("GET", "/api/pair/code");
                    code = p.optString("code");
                    int secs = p.optInt("expires_in");
                    text = p.isNull("ip") ? "Conecta este PC a una red WiFi para poder conectar aparatos."
                            : "El código se renueva en " + secs / 60 + ":" + String.format("%02d", secs % 60) + " y cada vez que se usa.";
                    if (!code.equals(shown[0])) {
                        byte[] png = localHttp("GET", "/api/pair/qr.png");
                        bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
                    }
                } catch (java.io.IOException e) {
                    text = "Arrancando el servidor de Sync Tablet…";
                }
                String fc = code, ft = text;
                android.graphics.Bitmap fb = bmp;
                runOnUiThread(() -> {
                    left.setText(ft);
                    if (fb != null) {
                        qr.setImageBitmap(fb);
                        shown[0] = fc;
                    }
                    left.postDelayed(tick[0], 1000);
                });
            }, "qr").start();
        };
        tick[0].run();
    }

    private static final String[] SETUP_ICONS = {"pending", "○", "running", "●", "ok", "✓", "error", "✗"};

    /** Publica el tracker en la cuenta de Cloudflare de quien lo pulsa, enseñando cada paso. */
    private void showTrackerSetup() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setMinimumWidth(t.dp(860));
        box.setPadding(t.dp(24), 0, t.dp(24), t.dp(12));
        TextView msg = dock.message("Pulsa «Empezar». Se abrirá el navegador para que autorices a Cloudflare (si no tienes cuenta, la creas ahí mismo); lo demás es automático.");
        box.addView(msg, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, t.dp(400));
        bodyLp.topMargin = t.dp(12);
        box.addView(body, bodyLp);
        LinearLayout steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.VERTICAL);
        body.addView(steps, new LinearLayout.LayoutParams(t.dp(290), -1));
        TextView log = new TextView(this);
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        log.setTextSize(12);
        log.setTextColor(0xFFC9D1D9);
        log.setPadding(t.dp(12), t.dp(10), t.dp(12), t.dp(10));
        ScrollView sv = new ScrollView(this);
        sv.setBackground(Ui.round(0xFF0D1117, t.dp(12)));
        sv.addView(log, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(0, -1, 1);
        svLp.leftMargin = t.dp(18);
        body.addView(sv, svLp);
        dock.show("Crear mi servidor de cuentas", box, new OptionDock.Action("Cerrar", false, null),
                new OptionDock.Action("Empezar", true, () -> new Thread(() -> {
                    try {
                        localHttp("POST", "/api/tracker/setup");
                    } catch (java.io.IOException ignored) {
                    }
                }, "setup").start()).stays());
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (!dock.isOpen() || box.getParent() == null) return;
            new Thread(() -> {
                JSONObject st;
                try {
                    st = localJson("GET", "/api/tracker/setup");
                } catch (java.io.IOException e) {
                    st = null;
                }
                JSONObject fs = st;
                runOnUiThread(() -> {
                    if (fs != null) paintSetup(fs, msg, steps, log, sv);
                    msg.postDelayed(tick[0], 800);
                });
            }, "setup-poll").start();
        };
        tick[0].run();
    }

    private void paintSetup(JSONObject st, TextView msg, LinearLayout steps, TextView log, ScrollView sv) {
        org.json.JSONArray list = st.optJSONArray("steps");
        steps.removeAllViews();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject x = list.optJSONObject(i);
            String state = x.optString("state"), icon = "○";
            for (int k = 0; k < SETUP_ICONS.length; k += 2) if (SETUP_ICONS[k].equals(state)) icon = SETUP_ICONS[k + 1];
            int color = "ok".equals(state) ? 0xFF35A86B : "error".equals(state) ? 0xFFEB5757 : "running".equals(state) ? t.text : t.faint;
            TextView row = text(icon + "   " + x.optString("label"), 15, color, "running".equals(state) ? t.medium : t.regular);
            row.setPadding(0, t.dp(8), 0, t.dp(8));
            steps.addView(row);
        }
        String l = st.optString("log").trim();
        if (!l.equals(log.getText().toString())) {
            log.setText(l);
            sv.post(() -> sv.scrollTo(0, Math.max(0, log.getHeight() - sv.getHeight())));
        }
        boolean running = st.optBoolean("running");
        if (running) {
            boolean login = false;
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject x = list.optJSONObject(i);
                login |= "running".equals(x.optString("state")) && x.optString("label").contains("Cloudflare");
            }
            msg.setText(login ? "Autoriza a Cloudflare en el navegador que se abrió." : "Trabajando… cada paso aparece abajo.");
        } else if (!st.isNull("url")) {
            msg.setText("¡Listo! Tu servidor de cuentas está en " + st.optString("url") + ". Este PC ya lo usa y la tablet lo recibe al escanear el QR.");
        } else if (!st.isNull("error")) {
            msg.setText(st.isNull("hint") ? "No se pudo terminar: " + st.optString("error") + ". Pulsa «Empezar» para reintentar." : st.optString("hint"));
        }
    }

    /** El botón grande para leer el QR del PC (no existe en la app de escritorio, que ya está en el PC). */
    private View scanButton() {
        TextView b = dock.chip("📷  Escanear QR del PC", true);
        b.setTextSize(18);
        b.setMinHeight(t.dp(64));
        b.setOnClickListener(v -> scanQr());
        return b;
    }

    private void scanQr() {
        try {
            startActivityForResult(new Intent(this, Class.forName("dev.synctablet.QrScanActivity")), SCAN_QR);
        } catch (ClassNotFoundException | android.content.ActivityNotFoundException e) {
            Ui.toast(this, "Este aparato no puede escanear");
        }
    }

    /** synctablet://pair?h=IP&p=puerto&c=código&n=nombre&t=servidor de cuentas (lo que lleva el QR del PC). */
    private void pairLink(android.net.Uri u) {
        if (u != null && "synctablet".equals(u.getScheme()) && "doc".equals(u.getHost())) {
            joinLink(u.toString());
            return;
        }
        if (u == null || !"synctablet".equals(u.getScheme()) || !"pair".equals(u.getHost())) return;
        String host = u.getQueryParameter("h"), code = u.getQueryParameter("c"), name = u.getQueryParameter("n"), tracker = u.getQueryParameter("t");
        String port = u.getQueryParameter("p");
        if (host == null || host.isEmpty() || code == null) {
            Ui.toast(this, "El PC no está conectado a ninguna red WiFi");
            return;
        }
        if (port != null && !port.equals(String.valueOf(SyncClient.PORT))) {
            Ui.toast(this, "Ese QR es de otra versión de Sync Tablet");
            return;
        }
        String pc = name == null || name.isEmpty() ? "el PC" : "«" + name + "»";
        Ui.toast(this, "Conectando con " + pc + "…");
        if (tracker != null && tracker.startsWith("http")) Account.get(this).rememberTracker(tracker);
        new Thread(() -> {
            sync.setWifiHost(host);
            boolean ok = sync.pair(host, code);
            runOnUiThread(() -> {
                if (ok) {
                    Ui.toast(this, "¡Conectado con " + pc + "!");
                    if (dock.isOpen()) dock.close();
                } else {
                    Ui.toast(this, "No se pudo conectar: revisa que la tablet y el PC estén en la misma WiFi y vuelve a escanear");
                }
            });
        }, "pair").start();
    }

    private void askLogin(Account account) {
        // Buscar ya el PC de esta WiFi: así se sabe qué servidor de cuentas usa.
        if (SyncClient.nearby.isEmpty()) {
            new Thread(() -> SyncClient.nearby = Discovery.probe(SyncClient.PORT, 700), "probe").start();
        }
        EditText user = field("");
        user.setHint("Usuario");
        user.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        EditText pass = field("");
        pass.setHint("Contraseña (8 o más)");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TextView err = dock.message("");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(t.dp(18), 0, t.dp(18), t.dp(10));
        if (!Updater.DESKTOP) {
            box.addView(dock.message("En el PC pulsa «Conectar un aparato» y escanea el código que aparece."));
            box.addView(scanButton(), new LinearLayout.LayoutParams(-1, -2));
            TextView or = dock.message("o entra con tu cuenta, para encontrar tus aparatos también fuera de casa:");
            or.setPadding(0, t.dp(18), 0, t.dp(4));
            box.addView(or);
        } else {
            box.addView(dock.message("Entra con tu cuenta para ver y encontrar tus aparatos."));
        }
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2);
        gap.topMargin = t.dp(8);
        box.addView(user, new LinearLayout.LayoutParams(-1, -2));
        box.addView(pass, gap);
        box.addView(err);
        Runnable[] go = new Runnable[2];
        for (int i = 0; i < 2; i++) {
            boolean register = i == 1;
            go[i] = () -> {
                String u = user.getText().toString().trim().toLowerCase(java.util.Locale.ROOT), p = pass.getText().toString();
                if (!u.matches("[a-z0-9][a-z0-9._-]{2,31}")) {
                    err.setText("El usuario debe tener de 3 a 32 letras, números, «.», «_» o «-».");
                    return;
                }
                if (p.length() < 8) {
                    err.setText("La contraseña debe tener 8 caracteres o más.");
                    return;
                }
                err.setText(register ? "Creando la cuenta…" : "Entrando…");
                new Thread(() -> {
                    String problem = null;
                    try {
                        account.login(account.tracker(), u, p, register);
                    } catch (java.io.IOException ex) {
                        problem = ex.getMessage();
                    }
                    String fp = problem;
                    runOnUiThread(() -> {
                        if (fp != null) {
                            err.setText(fp);
                            return;
                        }
                        sync.searchSoon();
                        Ui.toast(this, "Sesión iniciada como " + account.user());
                        showDevices(account);
                    });
                }, "login").start();
            };
        }
        dock.show("Conectar con el PC", box, new OptionDock.Action("Crear cuenta", false, go[1]).stays(), new OptionDock.Action("Entrar", true, go[0]).stays());
    }

    private void showDevices(Account account) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(t.dp(18), 0, t.dp(18), t.dp(10));
        box.addView(dock.message("Sesión: " + account.user() + (online ? " · conectado al PC" : " · buscando el PC…")));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.addView(dock.message("Buscando aparatos…"));
        box.addView(list);
        if (!Updater.DESKTOP) {
            View scan = scanButton();
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
            sp.topMargin = t.dp(10);
            box.addView(scan, sp);
        }
        dock.show("Mis aparatos", box, new OptionDock.Action("Cerrar sesión", false, () -> new Thread(() -> {
            account.logout();
            runOnUiThread(() -> Ui.toast(this, "Sesión cerrada"));
        }, "logout").start()), new OptionDock.Action("Listo", true, null));
        new Thread(() -> {
            org.json.JSONArray devs = null;
            String problem = null;
            try {
                devs = account.devices();
            } catch (java.io.IOException ex) {
                problem = ex.getMessage();
            }
            org.json.JSONArray fd = devs;
            String fp = problem;
            runOnUiThread(() -> {
                list.removeAllViews();
                if (fd == null) {
                    list.addView(dock.message(fp));
                    return;
                }
                for (int i = 0; i < fd.length(); i++) {
                    JSONObject d = fd.optJSONObject(i);
                    if (d == null) continue;
                    String label = (d.optBoolean("online") ? "🟢 " : "⚪ ") + d.optString("name") + (d.optBoolean("me") ? " (este)" : "")
                            + " — " + kindName(d.optString("kind")) + (d.optBoolean("online") ? " · en línea" : "");
                    String id = d.optString("id"), name = d.optString("name");
                    boolean me = d.optBoolean("me");
                    list.addView(dock.row(label, me ? null : v -> dock.confirm("¿Quitar «" + name + "» de la cuenta?",
                            "Tendrá que volver a iniciar sesión para conectarse.", "Quitar", () -> new Thread(() -> {
                                try {
                                    account.removeDevice(id);
                                } catch (java.io.IOException ignored) {
                                }
                                runOnUiThread(() -> showDevices(account));
                            }, "remove").start())));
                }
            });
        }, "devices").start();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) Clip.check(this);
        if (focus) Ui.applyFullscreen(this);
    }
}
