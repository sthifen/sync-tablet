package dev.synctablet;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exportar a PDF eligiendo páginas, como en un organizador de PDF: todas, pares,
 * impares, un rango («1-3, 7») o tocando las miniaturas. Sirve con uno o varios
 * cuadernos a la vez (se juntan en un solo PDF) y, sobre los mismos cuadernos,
 * permite sacar las elegidas a un cuaderno nuevo o borrarlas.
 */
public final class PdfActivity extends Activity {
    /** Una página con su cuaderno y su número dentro de él. */
    private static final class Entry {
        final Ink.Notebook notebook;
        final String pageId;
        final int number;

        Entry(Ink.Notebook n, String id, int number) {
            this.notebook = n;
            this.pageId = id;
            this.number = number;
        }
    }

    static final float[][] SIZES = {{595, 842}, {612, 792}};
    static final String[] SIZE_NAMES = {"A4", "Carta"};

    private final ArrayList<Entry> entries = new ArrayList<>();
    private final LinkedHashSet<Integer> chosen = new LinkedHashSet<>();
    private final ArrayList<View> cells = new ArrayList<>();
    private final HashMap<String, Bitmap> thumbs = new HashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private NoteTheme t;
    private Store store;
    private TextView count, go;
    private EditText range;
    private boolean typingRange;
    private int size, perSheet = 1;
    private boolean plain;
    private final ArrayList<String> notebookIds = new ArrayList<>();

    /** Abre el exportador con estos cuadernos; `preselect` (o −1): la página que se ve. */
    static void open(Context c, List<String> notebooks, int preselect) {
        c.startActivity(new Intent(c, PdfActivity.class).putStringArrayListExtra("notebooks", new ArrayList<>(notebooks)).putExtra("page", preselect));
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        t = new NoteTheme(this);
        store = Store.get(this);
        ArrayList<String> ids = getIntent().getStringArrayListExtra("notebooks");
        if (ids != null) notebookIds.addAll(ids);
        android.content.SharedPreferences prefs = getSharedPreferences("tools", MODE_PRIVATE);
        size = prefs.getInt("pdfSize", 0);
        perSheet = prefs.getInt("pdfPerSheet", 1);
        plain = prefs.getBoolean("pdfPlain", false);
        loadEntries();
        if (entries.isEmpty()) {
            finish();
            return;
        }
        for (int i = 0; i < entries.size(); i++) chosen.add(i);
        build();
        getWindow().setStatusBarColor(t.bar);
        getWindow().setNavigationBarColor(t.bg);
    }

    private void loadEntries() {
        entries.clear();
        for (String id : notebookIds) {
            Ink.Notebook n = store.notebook(id);
            if (n == null) continue;
            for (int i = 0; i < n.pages.size(); i++) entries.add(new Entry(n, n.pages.get(i), i + 1));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        for (Bitmap b : thumbs.values()) b.recycle();
        thumbs.clear();
    }

    // ---- Pantalla ----

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(t.bg);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(t.bar);
        bar.setPadding(t.dp(8), t.dp(6), t.dp(16), t.dp(6));
        IconView back = new IconView(this, IconView.BACK, t.text, "Volver");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(t.dp(48), t.dp(48)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        String name = notebookIds.size() == 1 ? entries.get(0).notebook.title : notebookIds.size() + " cuadernos juntos";
        titles.addView(text("Exportar a PDF", 20, t.text, t.bold));
        titles.addView(text(name, 13, t.muted, t.regular));
        bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        count = text("", 14, t.muted, t.medium);
        bar.addView(count);
        root.addView(bar);

        // Elegir páginas.
        LinearLayout pick = row();
        pick.setPadding(t.dp(16), t.dp(12), t.dp(16), t.dp(4));
        pick.addView(chip("Todas", v -> select(i -> true)));
        pick.addView(chip("Ninguna", v -> select(i -> false)));
        pick.addView(chip("Impares", v -> select(i -> entries.get(i).number % 2 == 1)));
        pick.addView(chip("Pares", v -> select(i -> entries.get(i).number % 2 == 0)));
        pick.addView(chip("Invertir", v -> select(i -> !chosen.contains(i))));
        range = new EditText(this);
        range.setHint("Páginas: 1-3, 7, 10-");
        range.setSingleLine(true);
        range.setInputType(InputType.TYPE_CLASS_TEXT);
        range.setTextSize(15);
        range.setTextColor(t.text);
        range.setHintTextColor(t.faint);
        range.setBackground(Ui.round(t.callout, t.dp(12)));
        range.setPadding(t.dp(14), 0, t.dp(14), 0);
        range.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                if (typingRange) return;
                java.util.Set<Integer> want = parseRange(e.toString());
                if (want == null) return;
                typingRange = true;
                select(i -> want.contains(notebookIds.size() == 1 ? entries.get(i).number : i + 1));
                typingRange = false;
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(t.dp(220), t.dp(44));
        rlp.leftMargin = t.dp(8);
        pick.addView(range, rlp);
        root.addView(scrollRow(pick));

        // Cómo sale.
        LinearLayout opts = row();
        opts.setPadding(t.dp(16), t.dp(4), t.dp(16), t.dp(8));
        opts.addView(label("Hoja"));
        for (int k = 0; k < SIZE_NAMES.length; k++) {
            final int which = k;
            opts.addView(toggle(SIZE_NAMES[k], size == k, v -> { size = which; keep(); build(); }));
        }
        opts.addView(label("Por hoja"));
        for (int k : new int[] {1, 2, 4}) opts.addView(toggle(String.valueOf(k), perSheet == k, v -> { perSheet = k; keep(); build(); }));
        opts.addView(label("Plantilla"));
        opts.addView(toggle("Con rayas", !plain, v -> { plain = false; keep(); build(); }));
        opts.addView(toggle("Sin rayas", plain, v -> { plain = true; keep(); build(); }));
        root.addView(scrollRow(opts));

        View line = new View(this);
        line.setBackgroundColor(t.line);
        root.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, t.dp(1))));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(t.dp(16), t.dp(8), t.dp(16), t.dp(24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        fillGrid(list);

        // Acciones.
        LinearLayout actions = row();
        actions.setBackgroundColor(t.bar);
        actions.setPadding(t.dp(16), t.dp(10), t.dp(16), t.dp(10));
        actions.addView(secondary("Sacar a cuaderno nuevo", v -> extract()));
        actions.addView(secondary("Borrar del cuaderno", v -> confirmDelete()));
        actions.addView(secondary("Un PDF por página", v -> export(true)));
        View fill = new View(this);
        actions.addView(fill, new LinearLayout.LayoutParams(0, 1, 1));
        go = text("", 16, Ui.ACCENT_TEXT, t.bold);
        go.setGravity(Gravity.CENTER);
        go.setPadding(t.dp(22), 0, t.dp(22), 0);
        go.setBackground(Ui.round(Ui.ACCENT, t.dp(24)));
        go.setOnClickListener(v -> export(false));
        actions.addView(go, new LinearLayout.LayoutParams(-2, t.dp(48)));
        root.addView(actions);

        Ui.fitSystemBars(root, 0, 0, 0, 0);
        setContentView(root);
        updateCount();
    }

    private void keep() {
        getSharedPreferences("tools", MODE_PRIVATE).edit().putInt("pdfSize", size).putInt("pdfPerSheet", perSheet).putBoolean("pdfPlain", plain).apply();
    }

    private void fillGrid(LinearLayout list) {
        cells.clear();
        int width = getResources().getDisplayMetrics().widthPixels - t.dp(32);
        int gap = t.dp(12);
        int cols = Math.max(3, Math.min(8, (width + gap) / (t.dp(150) + gap)));
        int cw = (width - gap * (cols - 1)) / cols, th = Math.round((cw - t.dp(12)) * Ink.H / Ink.W);
        GridLayout grid = null;
        Ink.Notebook current = null;
        int col = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.notebook != current) {
                current = e.notebook;
                if (notebookIds.size() > 1) {
                    TextView h = text(current.title, 15, t.text, t.medium);
                    h.setPadding(t.dp(2), t.dp(16), 0, t.dp(4));
                    list.addView(h);
                }
                grid = new GridLayout(this);
                grid.setColumnCount(cols);
                list.addView(grid);
                col = 0;
            }
            View cell = cell(i, cw, th);
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = cw;
            p.setMargins(col == 0 ? 0 : gap / 2, gap / 2, col == cols - 1 ? 0 : gap / 2, gap / 2);
            grid.addView(cell, p);
            col = (col + 1) % cols;
            cells.add(cell);
        }
    }

    private View cell(int i, int cw, int th) {
        Entry e = entries.get(i);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(t.dp(6), t.dp(6), t.dp(6), t.dp(6));
        FrameLayout frame = new FrameLayout(this);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setBackgroundColor(0xFFFFFFFF);
        img.setElevation(t.dp(2));
        frame.addView(img, new FrameLayout.LayoutParams(-1, th));
        TextView mark = text("✓", 16, 0xFFFFFFFF, t.bold);
        mark.setGravity(Gravity.CENTER);
        mark.setElevation(t.dp(4));
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(t.dp(30), t.dp(30), Gravity.TOP | Gravity.END);
        mlp.setMargins(0, t.dp(6), t.dp(6), 0);
        frame.addView(mark, mlp);
        c.addView(frame, new LinearLayout.LayoutParams(-1, th));
        TextView n = text(String.valueOf(e.number), 13, t.muted, t.medium);
        n.setPadding(0, t.dp(6), 0, 0);
        n.setGravity(Gravity.CENTER);
        c.addView(n, new LinearLayout.LayoutParams(-1, -2));
        c.setTag(new View[] {img, mark});
        c.setOnClickListener(v -> {
            if (!chosen.remove(i)) chosen.add(i);
            paintCell(i);
            updateCount();
            clearRange();
        });
        c.setContentDescription("Página " + e.number);
        paintCell(c, chosen.contains(i));
        Bitmap ready = thumbs.get(e.pageId);
        if (ready != null) img.setImageBitmap(ready);
        else loadThumb(e.pageId, img, cw - t.dp(12), th);
        return c;
    }

    private void paintCell(int i) {
        paintCell(cells.get(i), chosen.contains(i));
    }

    private void paintCell(View c, boolean on) {
        View[] parts = (View[]) c.getTag();
        c.setBackground(Ui.round(on ? t.accent & 0xFFFFFF | 0x33000000 : 0, t.dp(14)));
        parts[1].setBackground(Ui.round(on ? t.accent : t.card, t.dp(15)));
        ((TextView) parts[1]).setText(on ? "✓" : "");
        if (!on) ((android.graphics.drawable.GradientDrawable) parts[1].getBackground()).setStroke(t.dp(2), t.faint);
        parts[0].setAlpha(on ? 1f : 0.55f);
    }

    private void loadThumb(String id, ImageView img, int w, int h) {
        worker.execute(() -> {
            Ink.Page p = store.page(id);
            if (p == null || w <= 0 || h <= 0) return;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(0xFFFFFFFF);
            float k = w / Ink.W;
            c.scale(k, k);
            renderer().drawPage(c, styled(p), k, null, null);
            main.post(() -> {
                if (isDestroyed()) {
                    b.recycle();
                    return;
                }
                thumbs.put(id, b);
                img.setImageBitmap(b);
            });
        });
    }

    private PageRenderer workerRenderer;

    private PageRenderer renderer() {
        if (workerRenderer == null) workerRenderer = new PageRenderer(this);
        return workerRenderer;
    }

    /** La página tal como sale (sin plantilla, si se pidió). */
    private Ink.Page styled(Ink.Page p) {
        if (!plain || Ink.PLAIN.equals(p.background)) return p;
        Ink.Page copy = new Ink.Page(p.id, p.notebook);
        copy.copyFrom(p);
        copy.background = Ink.PLAIN;
        return copy;
    }

    private interface Pick {
        boolean on(int i);
    }

    private void select(Pick pick) {
        chosen.clear();
        for (int i = 0; i < entries.size(); i++) if (pick.on(i)) chosen.add(i);
        for (int i = 0; i < cells.size(); i++) paintCell(i);
        updateCount();
        if (!typingRange) clearRange();
    }

    private void clearRange() {
        if (range == null || range.getText().length() == 0 || typingRange) return;
        typingRange = true;
        range.setText("");
        typingRange = false;
    }

    private void updateCount() {
        int n = chosen.size();
        count.setText(n + " de " + entries.size() + (entries.size() == 1 ? " página" : " páginas"));
        go.setText(n == 0 ? "Elige páginas" : "Exportar " + n + (n == 1 ? " página" : " páginas"));
        go.setAlpha(n == 0 ? 0.5f : 1f);
    }

    /** «1-3, 7, 10-» → {1, 2, 3, 7, 10, …}; null si aún no se entiende. */
    static java.util.Set<Integer> parseRange(String s) {
        java.util.TreeSet<Integer> out = new java.util.TreeSet<>();
        s = s.trim();
        if (s.isEmpty()) return null;
        for (String part : s.split("[,;\\s]+")) {
            if (part.isEmpty()) continue;
            try {
                int dash = part.indexOf('-');
                if (dash < 0) {
                    out.add(Integer.parseInt(part));
                } else {
                    String a = part.substring(0, dash), b = part.substring(dash + 1);
                    int from = a.isEmpty() ? 1 : Integer.parseInt(a), to = b.isEmpty() ? 100000 : Integer.parseInt(b);
                    if (to - from > 100000) return null;
                    for (int i = Math.min(from, to); i <= Math.max(from, to); i++) out.add(i);
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    private ArrayList<Entry> picked() {
        ArrayList<Entry> list = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) if (chosen.contains(i)) list.add(entries.get(i));
        return list;
    }

    // ---- Exportar ----

    private void export(boolean split) {
        ArrayList<Entry> list = picked();
        if (list.isEmpty()) {
            Ui.toast(this, "Elige al menos una página");
            return;
        }
        Ui.toast(this, split ? "Creando " + list.size() + " PDF…" : "Creando el PDF…");
        String base = clean(notebookIds.size() == 1 ? list.get(0).notebook.title : "Cuadernos");
        int total = entries.size();
        new Thread(() -> {
            PageRenderer r = new PageRenderer(this);
            ArrayList<Uri> done = new ArrayList<>();
            String last = null;
            try {
                if (split) {
                    for (Entry e : list) {
                        ArrayList<Ink.Page> one = new ArrayList<>();
                        Ink.Page p = store.page(e.pageId);
                        if (p != null) one.add(styled(p));
                        last = base + " - página " + e.number + ".pdf";
                        done.add(write(r, one, last));
                    }
                } else {
                    ArrayList<Ink.Page> pages = new ArrayList<>();
                    for (Entry e : list) {
                        Ink.Page p = store.page(e.pageId);
                        if (p != null) pages.add(styled(p));
                    }
                    last = base + (list.size() == total ? "" : " (" + list.size() + (list.size() == 1 ? " página)" : " páginas)")) + ".pdf";
                    done.add(write(r, pages, last));
                }
            } catch (IOException | RuntimeException e) {
                android.util.Log.w("SyncTablet", "PDF", e);
                main.post(() -> Ui.toast(this, "No se pudo guardar el PDF"));
                return;
            }
            final String shown = split ? done.size() + " archivos" : last;
            main.post(() -> {
                Ui.toast(this, "Guardado en Descargas/SyncTablet: " + shown);
                Intent share;
                if (done.size() == 1) {
                    share = new Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, done.get(0));
                } else {
                    share = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/pdf").putParcelableArrayListExtra(Intent.EXTRA_STREAM, done);
                }
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(share, "Compartir"));
            });
        }).start();
    }

    private Uri write(PageRenderer r, List<Ink.Page> pages, String name) throws IOException {
        PdfDocument doc = new PdfDocument();
        try {
            draw(doc, r, pages, SIZES[size][0], SIZES[size][1], perSheet);
            return save(this, doc, name);
        } finally {
            doc.close();
        }
    }

    /** Las páginas en hojas de pw × ph puntos, 1, 2 (apaisada) o 4 por hoja. */
    static void draw(PdfDocument doc, PageRenderer r, List<Ink.Page> pages, float pw, float ph, int perSheet) {
        int cols = perSheet == 1 ? 1 : 2, rows = perSheet == 4 ? 2 : 1;
        if (perSheet == 2) {
            float s = pw;
            pw = ph;
            ph = s;
        }
        float margin = perSheet == 1 ? 0 : 18;
        float cellW = (pw - margin * (cols + 1)) / cols, cellH = (ph - margin * (rows + 1)) / rows;
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(0.5f);
        edge.setColor(0xFFBDBDBD);
        int sheet = 0;
        for (int i = 0; i < pages.size(); i += perSheet) {
            PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder((int) pw, (int) ph, ++sheet).create());
            Canvas c = page.getCanvas();
            for (int k = 0; k < perSheet && i + k < pages.size(); k++) {
                float x = margin + (k % cols) * (cellW + margin), y = margin + (k / cols) * (cellH + margin);
                float s = Math.min(cellW / Ink.W, cellH / Ink.H);
                c.save();
                c.translate(x + (cellW - Ink.W * s) / 2, y + (cellH - Ink.H * s) / 2);
                c.scale(s, s);
                c.clipRect(0, 0, Ink.W, Ink.H);
                r.drawPage(c, pages.get(i + k), 1, null, null);
                c.restore();
                if (perSheet > 1) c.drawRect(x + (cellW - Ink.W * s) / 2, y + (cellH - Ink.H * s) / 2, x + (cellW + Ink.W * s) / 2, y + (cellH + Ink.H * s) / 2, edge);
            }
            doc.finishPage(page);
        }
    }

    static Uri save(Context ctx, PdfDocument doc, String name) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
        if (Build.VERSION.SDK_INT >= 29) v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/SyncTablet");
        Uri uri = ctx.getContentResolver().insert(MediaStore.Files.getContentUri("external"), v);
        if (uri == null) throw new IOException("sin acceso a Descargas");
        try (OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
            doc.writeTo(out);
        }
        return uri;
    }

    static String clean(String s) {
        s = s.replaceAll("[\\\\/:*?\"<>|]", " ").trim();
        return s.isEmpty() ? "Cuaderno" : s;
    }

    // ---- En los mismos cuadernos ----

    /** Las elegidas, copiadas a un cuaderno nuevo (las originales se quedan). */
    private void extract() {
        ArrayList<Entry> list = picked();
        if (list.isEmpty()) {
            Ui.toast(this, "Elige al menos una página");
            return;
        }
        Ink.Notebook from = list.get(0).notebook;
        String title = (notebookIds.size() == 1 ? from.title : "Páginas elegidas") + " · " + list.size() + (list.size() == 1 ? " página" : " páginas");
        Ink.Notebook n = store.createNotebook(title, from.color);
        n.folder = from.folder;
        String firstEmpty = n.pages.isEmpty() ? null : n.pages.get(0);
        SyncClient sync = SyncClient.get(this);
        for (Entry e : list) {
            Ink.Page p = store.page(e.pageId);
            if (p == null) continue;
            Ink.Page copy = store.duplicatePage(n, p, n.pages.size());
            sync.sendPage(copy);
        }
        if (firstEmpty != null && n.pages.size() > 1) store.deletePage(n, firstEmpty);
        n.updated = System.currentTimeMillis();
        store.saveNotebooks();
        sync.sendNotebook(n);
        Ui.toast(this, "Cuaderno nuevo: " + n.title);
        startActivity(new Intent(this, PageActivity.class).putExtra("notebook", n.id));
        finish();
    }

    private void confirmDelete() {
        ArrayList<Entry> list = picked();
        if (list.isEmpty()) {
            Ui.toast(this, "Elige al menos una página");
            return;
        }
        // Cada cuaderno se queda con al menos una página.
        HashMap<Ink.Notebook, Integer> left = new HashMap<>();
        for (Entry e : entries) left.merge(e.notebook, 1, Integer::sum);
        for (Entry e : list) left.merge(e.notebook, -1, Integer::sum);
        for (Integer v : left.values()) {
            if (v <= 0) {
                Ui.toast(this, "Cada cuaderno debe quedarse con una página por lo menos");
                return;
            }
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(String.format(Locale.ROOT, "¿Borrar %d %s?", list.size(), list.size() == 1 ? "página" : "páginas"))
                .setMessage("Se borran del cuaderno en la tablet y en el PC.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Borrar", (d, w) -> {
                    SyncClient sync = SyncClient.get(this);
                    java.util.HashSet<Ink.Notebook> touched = new java.util.HashSet<>();
                    for (Entry e : list) {
                        if (store.deletePage(e.notebook, e.pageId)) {
                            touched.add(e.notebook);
                            sync.send("{\"t\":\"delete_page\",\"page\":\"" + e.pageId + "\"}");
                        }
                    }
                    for (Ink.Notebook n : touched) sync.sendNotebook(n);
                    Ui.toast(this, "Páginas borradas");
                    loadEntries();
                    chosen.clear();
                    build();
                })
                .show();
    }

    // ---- Piezas ----

    private TextView text(String s, float sp, int color, android.graphics.Typeface face) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setTypeface(face);
        return tv;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private View scrollRow(View content) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(content);
        return h;
    }

    private TextView chip(String s, View.OnClickListener click) {
        TextView tv = text(s, 14, t.text, t.medium);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(t.dp(16), 0, t.dp(16), 0);
        tv.setBackground(Ui.round(t.callout, t.dp(22)));
        tv.setOnClickListener(click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, t.dp(44));
        p.rightMargin = t.dp(6);
        tv.setLayoutParams(p);
        return tv;
    }

    private TextView toggle(String s, boolean on, View.OnClickListener click) {
        TextView tv = chip(s, click);
        tv.setTextColor(on ? Ui.ACCENT_TEXT : t.muted);
        tv.setBackground(Ui.round(on ? Ui.ACCENT : t.callout, t.dp(22)));
        return tv;
    }

    private TextView label(String s) {
        TextView tv = text(s, 13, t.faint, t.regular);
        tv.setPadding(t.dp(10), 0, t.dp(6), 0);
        return tv;
    }

    private TextView secondary(String s, View.OnClickListener click) {
        TextView tv = text(s, 14, t.muted, t.medium);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(t.dp(14), 0, t.dp(14), 0);
        tv.setBackground(Ui.round(t.callout, t.dp(22)));
        tv.setOnClickListener(click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, t.dp(48));
        p.rightMargin = t.dp(8);
        tv.setLayoutParams(p);
        return tv;
    }
}
