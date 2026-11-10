package dev.synctablet;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mapa de ideas: todo lo relacionado, como una red de neuronas. Une las marcas
 * del lápiz neurona, los #temas y [[enlaces]] de las notas, las carpetas y las
 * palabras que varios documentos comparten. Se calcula aquí, sin internet.
 */
public final class GraphActivity extends Activity {
    private static final int KEYWORD = 0, MARK = 1, NOTEBOOK = 2, NOTE = 3, FOLDER = 4;
    private static final int[] COLORS = {0xFF22D3EE, 0xFFF472B6, 0xFFA78BFA, 0xFF34D399, 0xFFFBBF24};

    private static final class Node {
        final String label;
        final int kind;
        String notebook, page, note;
        /** Dónde está una marca, para distinguirla de otras con la misma palabra. */
        String place;
        float x, y, vx, vy;
        final ArrayList<Node> near = new ArrayList<>();

        Node(String label, int kind) {
            this.label = label;
            this.kind = kind;
        }

        float radius() {
            return kind == KEYWORD || kind == FOLDER ? 10 + Math.min(14, near.size() * 1.6f) : kind == MARK ? 6 : 9;
        }
    }

    private final ArrayList<Node> nodes = new ArrayList<>();
    private final HashMap<String, Node> byKey = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Web web;
    private OptionDock dock;
    private TextView hint;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(0xFF0B0F17);
        getWindow().setNavigationBarColor(0xFF0B0F17);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0F17);
        web = new Web(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        IconView back = new IconView(this, IconView.BACK, 0xFFE5E7EB, "Volver");
        back.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48), Gravity.TOP | Gravity.START);
        blp.setMargins(Ui.dp(this, 12), Ui.dp(this, 30), 0, 0);
        root.addView(back, blp);
        hint = Ui.text(this, "Buscando relaciones…", 14, 0xFF9CA3AF, false);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hlp.topMargin = Ui.dp(this, 44);
        root.addView(hint, hlp);
        dock = new OptionDock(this, root, null, 0xF71B2130, true);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            @SuppressWarnings("deprecation") int bottom = in.getSystemWindowInsetBottom();
            dock.setInsets(bottom, 0);
            return in;
        });
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.post(() -> getWindow().getInsetsController().setSystemBarsAppearance(0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS));
        }
        new Thread(this::collect).start();
    }

    private Node node(String key, String label, int kind) {
        Node n = byKey.get(key);
        if (n == null) {
            n = new Node(label, kind);
            byKey.put(key, n);
            nodes.add(n);
        }
        return n;
    }

    private static void join(Node a, Node b) {
        if (a == b || a.near.contains(b)) return;
        a.near.add(b);
        b.near.add(a);
    }

    private static String word(String w) {
        return w.trim().toLowerCase(Locale.ROOT);
    }

    private void collect() {
        Store store = Store.get(this);
        Notes notes = Notes.get(this);
        HashMap<String, List<Node>> shared = new HashMap<>();
        HashMap<String, Node> marks = new HashMap<>();
        HashMap<String, ArrayList<String>> links = new HashMap<>();
        for (Ink.Notebook nb : new ArrayList<>(store.notebooks())) {
            StringBuilder text = new StringBuilder();
            Node book = null;
            int limit = Math.min(nb.pages.size(), 60);
            for (int i = 0; i < limit; i++) {
                String id = nb.pages.get(i);
                Ink.Page p = store.peek(id);
                if (p == null) continue;
                text.append(Store.text(p));
                for (Ink.Mark m : p.marks) {
                    if (book == null) book = opened(node("nb:" + nb.id, nb.title, NOTEBOOK), nb.id, null);
                    String title = m.words.isEmpty() ? "Marca · pág. " + (i + 1) : m.words.get(0);
                    Node mark = opened(node("mark:" + m.id, title, MARK), nb.id, id);
                    mark.place = nb.title + " · pág. " + (i + 1);
                    join(mark, book);
                    marks.put(m.id, mark);
                    links.put(m.id, m.links);
                    for (String w : m.words) join(mark, node("kw:" + word(w), w, KEYWORD));
                }
            }
            List<String> top = Topics.keywords(text.toString(), 6);
            if (book == null && !top.isEmpty()) book = opened(node("nb:" + nb.id, nb.title, NOTEBOOK), nb.id, null);
            for (String w : top) shared.computeIfAbsent(w, k -> new ArrayList<>()).add(book);
            if (book != null && !nb.folder.isEmpty()) join(book, node("folder:" + nb.folder, nb.folder, FOLDER));
        }
        for (Map.Entry<String, ArrayList<String>> e : links.entrySet()) {
            for (String other : e.getValue()) {
                Node b = marks.get(other);
                if (b != null) join(marks.get(e.getKey()), b);
            }
        }
        Pattern tag = Pattern.compile("(?:^|\\s)#([\\p{L}0-9_-]{2,})"), wiki = Pattern.compile("\\[\\[([^\\]]+)\\]\\]");
        HashMap<String, Node> titled = new HashMap<>();
        ArrayList<JSONObject> list = notes.list();
        for (JSONObject n : list) {
            String title = n.optString("title");
            Node note = node("note:" + n.optString("id"), title.isEmpty() ? "Sin título" : title, NOTE);
            note.note = n.optString("id");
            if (!title.isEmpty()) titled.put(word(title), note);
        }
        for (JSONObject n : list) {
            Node note = byKey.get("note:" + n.optString("id"));
            String body = Notes.text(n);
            Matcher m = tag.matcher(body);
            while (m.find()) join(note, node("kw:" + word(m.group(1)), m.group(1), KEYWORD));
            m = wiki.matcher(body);
            while (m.find()) {
                Node other = titled.get(word(m.group(1)));
                if (other != null) join(note, other);
            }
            for (String w : Topics.keywords(n.optString("title") + "\n" + body, 6)) shared.computeIfAbsent(w, k -> new ArrayList<>()).add(note);
        }
        // Solo es relación si la comparten dos documentos o más.
        for (Map.Entry<String, List<Node>> e : shared.entrySet()) {
            HashSet<Node> docs = new HashSet<>(e.getValue());
            if (docs.size() < 2) continue;
            Node kw = node("kw:" + e.getKey(), e.getKey(), KEYWORD);
            for (Node d : docs) join(kw, d);
        }
        // Lo que no se relaciona con nada solo estorba.
        nodes.removeIf(n -> n.near.isEmpty());
        main.post(() -> {
            if (isDestroyed()) return;
            hint.setText(nodes.isEmpty() ? "Aún no hay relaciones: usa el lápiz neurona, o escribe #temas en tus notas" : "");
            web.start();
        });
    }

    private static Node opened(Node n, String notebook, String page) {
        n.notebook = notebook;
        n.page = page;
        return n;
    }

    private void open(Node n) {
        if (n.note != null) {
            setResult(RESULT_OK, new Intent().putExtra("note", n.note));
            finish();
        } else if (n.notebook != null) {
            Intent i = new Intent(this, PageActivity.class).putExtra("notebook", n.notebook);
            if (n.page != null) i.putExtra("page", n.page);
            startActivity(i);
        }
    }

    /** Dock del nodo tocado: abrirlo o saltar a lo que tiene cerca. */
    private void showNode(Node n) {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        if (n.note != null || n.notebook != null) tiles.add(new OptionDock.Tile(dock.glyph(n.note != null ? IconView.PAGE : IconView.PEN), "Abrir", false, () -> open(n)));
        int shown = 0;
        for (Node o : n.near) {
            if (shown++ == 11) break;
            tiles.add(new OptionDock.Tile(dock.swatch(COLORS[o.kind]), o.place != null ? o.place : o.label, false, () -> {
                web.select(o);
                showNode(o);
            }).stays());
        }
        dock.tiles(n.label, tiles);
    }

    private final class Web extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ScaleGestureDetector scaler;
        private final float density;
        private float zoom = 1, panX, panY, lastX, lastY, downX, downY;
        private boolean moved, running;
        private Node chosen;
        private int rest;

        Web(Context c) {
            super(c);
            density = c.getResources().getDisplayMetrics().density;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            text.setTextAlign(Paint.Align.CENTER);
            scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    float k = Math.max(0.3f, Math.min(4f, zoom * d.getScaleFactor())) / zoom;
                    panX = d.getFocusX() - (d.getFocusX() - panX) * k;
                    panY = d.getFocusY() - (d.getFocusY() - panY) * k;
                    zoom *= k;
                    moved = true;
                    invalidate();
                    return true;
                }
            });
        }

        void start() {
            java.util.Random r = new java.util.Random(7);
            float spread = 90 * density * (float) Math.sqrt(nodes.size() + 1);
            for (Node n : nodes) {
                n.x = (r.nextFloat() - 0.5f) * spread;
                n.y = (r.nextFloat() - 0.5f) * spread;
            }
            panX = getWidth() / 2f;
            panY = getHeight() / 2f;
            running = true;
            rest = 0;
            invalidate();
        }

        void select(Node n) {
            chosen = n;
            invalidate();
        }

        /** Las neuronas se empujan entre sí y los enlaces las acercan. */
        private void step() {
            float repel = 5200 * density * density, want = 95 * density, energy = 0;
            int count = nodes.size();
            for (int i = 0; i < count; i++) {
                Node a = nodes.get(i);
                for (int j = i + 1; j < count; j++) {
                    Node b = nodes.get(j);
                    float dx = a.x - b.x, dy = a.y - b.y, d2 = Math.max(dx * dx + dy * dy, 25);
                    float f = repel / d2, d = (float) Math.sqrt(d2);
                    float fx = dx / d * f, fy = dy / d * f;
                    a.vx += fx;
                    a.vy += fy;
                    b.vx -= fx;
                    b.vy -= fy;
                }
                for (Node b : a.near) {
                    float dx = b.x - a.x, dy = b.y - a.y, d = Math.max(1, (float) Math.hypot(dx, dy));
                    float f = (d - want) * 0.03f;
                    a.vx += dx / d * f;
                    a.vy += dy / d * f;
                }
                a.vx -= a.x * 0.004f;
                a.vy -= a.y * 0.004f;
            }
            for (Node n : nodes) {
                n.vx *= 0.82f;
                n.vy *= 0.82f;
                n.x += n.vx;
                n.y += n.vy;
                energy += Math.abs(n.vx) + Math.abs(n.vy);
            }
            rest = energy / Math.max(1, count) < 0.15f * density ? rest + 1 : 0;
            if (rest > 30) running = false;
        }

        @Override
        protected void onDraw(Canvas c) {
            if (running) step();
            c.save();
            c.translate(panX, panY);
            c.scale(zoom, zoom);
            for (Node a : nodes) {
                for (Node b : a.near) {
                    if (System.identityHashCode(a) > System.identityHashCode(b)) continue;
                    boolean lit = chosen == null || a == chosen || b == chosen;
                    int color = COLORS[a.kind == KEYWORD ? b.kind : a.kind];
                    line.setColor(color & 0xFFFFFF | (lit ? 0x22000000 : 0x0A000000));
                    line.setStrokeWidth(6 * density / zoom);
                    c.drawLine(a.x, a.y, b.x, b.y, line);
                    line.setColor(color & 0xFFFFFF | (lit ? 0x99000000 : 0x18000000));
                    line.setStrokeWidth(1.3f * density / zoom);
                    c.drawLine(a.x, a.y, b.x, b.y, line);
                }
            }
            text.setTextSize(13 * density / zoom);
            for (Node n : nodes) {
                boolean lit = chosen == null || n == chosen || chosen.near.contains(n);
                float r = n.radius() * density;
                int color = COLORS[n.kind];
                dot.setColor(color & 0xFFFFFF | (lit ? 0x18000000 : 0x08000000));
                c.drawCircle(n.x, n.y, r * 2.4f, dot);
                dot.setColor(color & 0xFFFFFF | (lit ? 0x40000000 : 0x10000000));
                c.drawCircle(n.x, n.y, r * 1.5f, dot);
                dot.setColor(color & 0xFFFFFF | (lit ? 0xFF000000 : 0x40000000));
                c.drawCircle(n.x, n.y, r, dot);
                if (n == chosen) {
                    line.setColor(0xFFFFFFFF);
                    line.setStrokeWidth(2 * density / zoom);
                    c.drawCircle(n.x, n.y, r * 1.5f, line);
                }
                if (n.kind == MARK && zoom < 1.3f && n != chosen) continue;
                text.setColor(lit ? 0xFFE5E7EB : 0x55E5E7EB);
                text.setFakeBoldText(n.kind == KEYWORD || n.kind == FOLDER);
                String s = n.label.length() > 24 ? n.label.substring(0, 23) + "…" : n.label;
                c.drawText(s, n.x, n.y + r + 18 * density / zoom, text);
            }
            c.restore();
            if (running) postInvalidateOnAnimation();
        }

        private Node hit(float sx, float sy) {
            float x = (sx - panX) / zoom, y = (sy - panY) / zoom, reach = 26 * density / zoom;
            Node best = null;
            float bestD = Float.MAX_VALUE;
            for (Node n : nodes) {
                float d = (float) Math.hypot(n.x - x, n.y - y);
                if (d < Math.max(reach, n.radius() * density * 1.5f) && d < bestD) {
                    bestD = d;
                    best = n;
                }
            }
            return best;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            scaler.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = lastX = e.getX();
                    downY = lastY = e.getY();
                    moved = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (e.getPointerCount() == 1 && !scaler.isInProgress()) {
                        if (Math.hypot(e.getX() - downX, e.getY() - downY) > 12 * density) moved = true;
                        panX += e.getX() - lastX;
                        panY += e.getY() - lastY;
                        invalidate();
                    }
                    lastX = e.getX();
                    lastY = e.getY();
                    break;
                case MotionEvent.ACTION_UP:
                    if (!moved) {
                        Node n = hit(e.getX(), e.getY());
                        chosen = n;
                        invalidate();
                        if (n != null) showNode(n);
                    }
                    break;
                default:
            }
            return true;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) Ui.applyFullscreen(this);
    }
}
