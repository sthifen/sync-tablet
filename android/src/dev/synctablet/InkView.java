package dev.synctablet;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * El cuaderno: todas sus páginas una debajo de otra. El lápiz dibuja con la
 * herramienta elegida; los dedos desplazan (con inercia), hacen zoom y
 * mueven lo seleccionado. Al final del cuaderno, tirar hacia arriba y
 * mantener crea una página nueva.
 *
 * Rechazo de la palma: mientras el lápiz toca, los dedos se ignoran, y los
 * dedos sólo dibujan si se activa «Dibujar con el dedo». La punta de goma o
 * el botón del lápiz borran.
 *
 * Para que el trazo vaya pegado al lápiz, lo ya dibujado vive en un bitmap
 * (`layer`) que cubre la pantalla y media pantalla más por arriba y por
 * abajo; cada tramo nuevo se pinta directamente en él, y se usan los puntos
 * intermedios de cada MotionEvent con `requestUnbufferedDispatch`.
 *
 * Deshacer: los trazos sueltos se deshacen con su operación contraria; lo
 * demás (lazo, goma parcial, imágenes, textos) guardando una copia de la
 * página antes y después.
 */
final class InkView extends View {
    /** Separación del tirador de giro, en dp. */
    private static final float ROTATE_GAP = 34;
    /** Presión fija de las figuras arrastradas. */
    private static final float SHAPE_PRESSURE = 0.7f;
    /** Tamaño mínimo (ancho + alto) para convertir sola una figura. */
    private static final float MIN_AUTO_SHAPE = 70;
    /** Lo más largo que mide una letra alta: una línea de más ya no lo es. */
    private static final float MIN_AUTO_LINE = 45;
    /** Cuánto recuerda las líneas para unirlas, y a qué distancia las pega. */
    private static final long LINE_MEMORY_MS = 15000;
    private static final float ATTACH = 16;

    static final int PEN = 0, HIGHLIGHTER = 1, ERASER = 2, LASSO = 3, TEXT = 4, LASER = 5, TAPE = 6, SHAPE = 7, NOTE = 8, LINK = 9;

    interface Listener {
        /** Un mensaje para el PC (trazo, borrado, página entera, láser). */
        void onOp(String json);
        /** Esa página cambió: hay que guardarla. */
        void onEdited(Ink.Page page);
        void onUndoState(boolean canUndo, boolean canRedo);
        /** La página que más se ve ahora. */
        void onPageVisible(int index);
        /** Se tiró hacia arriba al final del cuaderno: crear una página. */
        void onPullAddPage();
        /** Hay (o ya no hay) algo seleccionado con el lazo. */
        void onSelection(boolean active);
        /** Con la herramienta de texto: crear (item null) o editar un cuadro. */
        void onTextRequest(int page, Ink.Item item, float x, float y);
        /** Crear (note null) o editar una nota, en la página dada. */
        void onNoteRequest(int page, Ink.Note note, float x, float y);

        /** Rodear algo con el lápiz neurona, o tocar una marca (`mark` no es null). */
        void onMarkRequest(int page, Ink.Mark mark, float x, float y, float w, float h);

        /** Dos marcas quedaron enlazadas con el lápiz neurona. */
        void onMarksLinked();
        /** Poner el valor de un componente de circuito. */
        void onValueRequest(int page, Ink.Stroke component, String current);
        /** Con la herramienta de texto sobre una hoja de cálculo: editarla. */
        void onTableRequest(int page, Ink.Item table);
        /** Botón del S Pen pulsado sin tocar la pantalla (doble: dos seguidos). */
        void onPenButton(boolean twice);
    }

    /** Modos de la goma. */
    static final int ERASE_STROKES = 0, ERASE_PARTIAL = 1, ERASE_HIGHLIGHTER = 2, ERASE_LASSO = 3;
    /** Qué hace mantener el botón del S Pen mientras se escribe. */
    static final int HOLD_ERASE = 0, HOLD_LASSO = 1, HOLD_HIGHLIGHTER = 2, HOLD_NOTHING = 3;

    private static final int DESK = 0xFFDDE2E8;
    private int desk = DESK;
    private static final float MARGIN_DP = 24;
    /** Separación entre páginas, en unidades de página. */
    private static final float GAP = 36;
    private static final float PULL_DP = 110, PULL_MAX_DP = 260;
    private static final long PULL_HOLD_MS = 220, HOLD_MS = 550, LASER_MS = 1200;

    int tool = PEN;
    /** Tipo de lápiz de la pluma: pen, pencil, brush o calligraphy. */
    String penKind = Ink.PEN;
    int penColor = 0xFF111827;
    float penWidth = 3f;
    int highlighterColor = 0xFFFFD60A;
    float highlighterWidth = 24f;
    int eraseMode = ERASE_STROKES;
    int buttonHold = HOLD_ERASE;
    boolean fingerDraws;
    /** Convertir cada trazo en figura si lo parece (sin tener que esperar). */
    boolean autoShapes;
    /** Qué dibuja la herramienta de figuras (ShapeTools). */
    int shapeKind = ShapeTools.RECT;
    /** Alto de la barra flotante: la primera página empieza debajo. */
    float topInset;
    /** Tras cada fotograma (para que el cristal de encima se actualice). */
    Runnable onFrame;

    private final PageRenderer renderer;
    private final ArrayList<Ink.Page> pages = new ArrayList<>();
    private Listener listener;
    private int visible = -1;

    // Pantalla = documento · scale + (ox, oy). La página i empieza en y = i · (H + GAP).
    private float scale = 1, ox, oy, fitScale = 1;
    private boolean fitted;
    private int pendingScroll = -1;

    /**
     * Cada página vive en la GPU como textura (RenderNode): se graba una vez y
     * desplazarla sólo la mueve. Antes de Android 10 se usa `layer`, un bitmap
     * dibujado por la CPU.
     */
    private static final boolean GPU = android.os.Build.VERSION.SDK_INT >= 29;
    private static final int MAX_NODES = 5;
    /** Sobre este tamaño la página no se guarda como textura: se repinta su grabación. */
    private static final long MAX_TEXTURE_PIXELS = 24_000_000L;

    private static final class PageNode {
        final android.graphics.RenderNode node = new android.graphics.RenderNode("page");
        /** Lo escrito desde la última grabación: repintar la textura entera por cada trazo se notaría. */
        final android.graphics.RenderNode recent = new android.graphics.RenderNode("recent");
        float scale;
        boolean dirty = true;
        /** Trazos que ya están en la textura. */
        int baked;

        void discard() {
            node.discardDisplayList();
            recent.discardDisplayList();
        }
    }

    /** Sin escribir este rato, lo reciente se funde con la textura. */
    private static final long BAKE_DELAY_MS = 1500;
    /** Escribiendo sin parar, cada tantos trazos se funden de todos modos. */
    private static final int MAX_RECENT = 40;
    private final Runnable bake = this::bakeRecent;

    /** Las más recientes primero en salir por el otro lado: orden de acceso. */
    private final LinkedHashMap<Ink.Page, PageNode> nodes = new LinkedHashMap<Ink.Page, PageNode>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Ink.Page, PageNode> eldest) {
            boolean full = size() > MAX_NODES;
            if (full) eldest.getValue().discard();
            return full;
        }
    };

    private Bitmap layer;
    private Canvas layerCanvas;
    private float layerScale, layerOx, layerOy;
    private int pad;

    private final ScaleGestureDetector scaler;
    private VelocityTracker velocity;
    private ValueAnimator scroller;
    private float focusX = Float.NaN, focusY = Float.NaN;
    private int fingers;
    private boolean pulled, atEnd;
    private long readySince, fingerDownAt;
    private float fingerDownX, fingerDownY;

    // Lo que hace el puntero que dibuja.
    private static final int NONE = 0, STROKE = 1, ERASE = 2, LASSO_DRAW = 3, DRAG = 4, LASER_DRAW = 5, TEXT_TAP = 6, NOTE_MOVE = 7, NOTE_ARROW = 8, NOTE_NEW = 9, LINK_DRAW = 10;
    private int mode = NONE;
    private int pointerId = -1;
    private int currentPage;
    private Ink.Stroke current;
    private boolean snapped;
    /** Regla, escuadras, transportador y compás. */
    final Guides guides;
    /** Se está arrastrando una figura con la herramienta: de (shapeX, shapeY) al lápiz. */
    private boolean shaping;
    /** Dibujo a mano alzada que se convierte en componente de circuito. */
    private boolean circuitFree;
    private float shapeX, shapeY;
    /** Figura ya reconocida y con el lápiz aún apoyado: se ajusta hasta soltarlo. */
    private float[] snapBase;
    private ShapeTools.Spec snapSpec;
    private String snapKind;
    private float snapPressure, snapCx, snapCy, snapReach;
    /** Trazos anteriores que esta figura sustituye al terminar. */
    private final ArrayList<Ink.Stroke> absorbed = new ArrayList<>();
    /** Líneas recién reconocidas: pueden cerrar un polígono o recibir una punta de flecha. */
    private final ArrayDeque<RecentLine> recentLines = new ArrayDeque<>();
    private float holdX, holdY;
    private final Runnable holdCheck = this::snapIfHeld;
    private final LinkedHashMap<Ink.Page, ArrayList<Ink.Stroke>> erased = new LinkedHashMap<>();
    /** Goma parcial: copia de cada página tocada, antes del cambio. */
    private final LinkedHashMap<Ink.Page, String> before = new LinkedHashMap<>();

    // Lazo y selección (en coordenadas de la página selPage).
    private final ArrayList<Float> lasso = new ArrayList<>();
    /** El lazo es de la goma: borra lo que rodea. */
    private boolean lassoErases;
    private int selPage = -1;
    private final ArrayList<Ink.Stroke> selStrokes = new ArrayList<>();
    private final ArrayList<Ink.Item> selItems = new ArrayList<>();
    private final RectF selBox = new RectF();
    /** Lo seleccionado sin el margen de la caja. */
    private final RectF selTight = new RectF();
    /** Arrastre de la selección: qué tirador, y a qué caja y ángulo va. */
    private int dragHandle;
    private float dragX, dragY, dragAngle;
    private float[] dragBox = new float[4];
    private static final float CIRCUIT_REACH = 30;
    /** Cuánto se aleja la hoja para dejar sitio a las notas. */
    private static final float NOTE_ZOOM = 0.68f;
    private int notePage;
    private Ink.Note activeNote;
    private String noteBefore;
    private float noteDownX, noteDownY, noteDx, noteDy, zoomBeforeNotes;
    private boolean noteMoved;
    private ValueAnimator zoomAnimator;
    /** Punta de línea en movimiento: la línea como va quedando. */
    private ShapeTools.Spec dragSpec;

    // Láser: estelas (en coordenadas del documento) que se desvanecen; no se guardan.
    private final ArrayList<float[]> laserTrails = new ArrayList<>();
    private final ArrayList<Long> laserEnds = new ArrayList<>();
    private float[] laser = new float[0];
    private int laserN;

    /** Cintas destapadas (sólo en esta tablet). */
    private final HashSet<String> revealed = new HashSet<>();

    /** Cada acción: {"add" | "erase", página → trazos} o {"snap", página → [antes, después]}. */
    private final ArrayDeque<Object[]> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();

    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint laserPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    InkView(Context c) {
        super(c);
        renderer = new PageRenderer(c);
        density = c.getResources().getDisplayMetrics().density;
        guides = new Guides(density);
        shade.setColor(0x26000000);
        dashed.setStyle(Paint.Style.STROKE);
        dashed.setStrokeWidth(2 * density);
        dashed.setColor(0xFF6B7280);
        dashed.setPathEffect(new DashPathEffect(new float[] {10 * density, 8 * density}, 0));
        handle.setColor(0xFF1F2937);
        laserPaint.setStyle(Paint.Style.STROKE);
        laserPaint.setStrokeCap(Paint.Cap.ROUND);
        laserPaint.setStrokeJoin(Paint.Join.ROUND);
        laserPaint.setColor(0xFFEF4444);
        laserPaint.setMaskFilter(new BlurMaskFilter(3 * density, BlurMaskFilter.Blur.SOLID));
        label.setColor(0xFF4B5563);
        label.setTextSize(16 * c.getResources().getDisplayMetrics().scaledDensity);
        label.setTextAlign(Paint.Align.CENTER);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomAround(d.getFocusX(), d.getFocusY(), d.getScaleFactor());
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                // Termina el zoom: las páginas se graban nítidas a la escala nueva.
                invalidate();
            }
        });
        setFocusable(true);
    }

    void setListener(Listener l) {
        listener = l;
    }

    PageRenderer renderer() {
        return renderer;
    }

    /** Las páginas del cuaderno, empezando por la `show`. */
    void setPages(List<Ink.Page> list, int show) {
        pages.clear();
        pages.addAll(list);
        laidOut = -1;
        for (PageNode n : nodes.values()) n.discard();
        nodes.clear();
        current = null;
        mode = NONE;
        pointerId = -1;
        clearSelection();
        undo.clear();
        redo.clear();
        visible = -1;
        pendingScroll = show;
        fitted = false;
        layout();
        notifyUndo();
    }

    /** Página nueva al final, y desplazarse hasta ella. */
    void appendPage(Ink.Page p) {
        pages.add(p);
        laidOut = -1;
        scrollToPage(pages.size() - 1, true);
    }

    /** Quita la página i de la vista (ya borrada del cuaderno). */
    void removePage(int i) {
        if (i < 0 || i >= pages.size()) return;
        Ink.Page gone = pages.remove(i);
        laidOut = -1;
        clearSelection();
        undo.removeIf(a -> ((Map<?, ?>) a[1]).containsKey(gone));
        redo.removeIf(a -> ((Map<?, ?>) a[1]).containsKey(gone));
        notifyUndo();
        visible = -1;
        clampView(false);
        renderLayer();
    }

    int pageCount() {
        return pages.size();
    }

    Ink.Page visiblePage() {
        return visible >= 0 && visible < pages.size() ? pages.get(visible) : null;
    }

    int visibleIndex() {
        return visible;
    }

    void refresh() {
        renderLayer();
    }

    void setBackground(String bg) {
        Ink.Page page = visiblePage();
        if (page == null || (bg.equals(page.background) && page.bgAsset == null)) return;
        String old = page.toJson();
        page.background = bg;
        commitSnapshot(page, old);
    }

    // ---- Cambios con copia de la página (deshacer) ----

    private void sendPage(Ink.Page page) {
        page.version++;
        listener.onOp("{\"t\":\"page\",\"page\":" + page.toJson() + "}");
        listener.onEdited(page);
    }

    /** Ya cambiada la página: mandarla entera y apuntar cómo deshacerlo. */
    private void commitSnapshot(Ink.Page page, String old) {
        LinkedHashMap<Ink.Page, String[]> m = new LinkedHashMap<>();
        m.put(page, new String[] {old, page.toJson()});
        sendPage(page);
        push(undo, new Object[] {"snap", m});
        redo.clear();
        renderLayer();
        notifyUndo();
    }

    private void restore(Ink.Page page, String json) {
        try {
            page.copyFrom(Ink.Page.fromJson(json));
        } catch (org.json.JSONException e) {
            return;
        }
        sendPage(page);
    }

    // ---- Elementos: imágenes y textos ----

    /** Inserta una imagen en la página que se ve y la deja seleccionada. */
    void insertImage(String asset, int bw, int bh) {
        insertImage(asset, bw, bh, 560);
    }

    /** `width`: ancho en la hoja (unidades de página). */
    void insertImage(String asset, int bw, int bh, float width) {
        Ink.Page page = visiblePage();
        if (page == null) return;
        int index = visible;
        String old = page.toJson();
        Ink.Item it = new Ink.Item(Ink.newId(), "image");
        it.asset = asset;
        it.w = Math.min(width, Ink.W - 80);
        it.h = it.w * bh / Math.max(1, bw);
        if (it.h > Ink.H - 160) {
            it.h = Ink.H - 160;
            it.w = it.h * bw / Math.max(1, bh);
        }
        // Centrada en la parte de la hoja que se ve.
        float viewY = (getHeight() / 2f - oy) / scale - pageTop(index);
        it.x = (Ink.W - it.w) / 2;
        it.y = Math.max(40, Math.min(Ink.H - it.h - 40, viewY - it.h / 2));
        page.items.add(it);
        commitSnapshot(page, old);
        clearSelection();
        selPage = index;
        selItems.add(it);
        updateSelBox();
        listener.onSelection(true);
        invalidate();
    }

    /** Centro (en la página que se ve) para colocar algo nuevo de alto h. */
    private float visibleCenterY(int index, float h) {
        float viewY = (getHeight() / 2f - oy) / scale - pageTop(index);
        return Math.max(40, Math.min(Ink.H - h - 40, viewY - h / 2));
    }

    /** Deja seleccionado lo recién insertado, para moverlo o cambiarle el tamaño. */
    private void selectInserted(int index, List<Ink.Stroke> strokes, List<Ink.Item> items) {
        clearSelection();
        selPage = index;
        selStrokes.addAll(strokes);
        selItems.addAll(items);
        updateSelBox();
        listener.onSelection(true);
        invalidate();
    }

    /** Inserta una figura de la galería con la pluma actual, centrada y seleccionada. */
    void insertShape(ShapeLibrary.Shape shape) {
        Ink.Page page = visiblePage();
        if (page == null) return;
        int index = visible;
        String old = page.toJson();
        float w = shape.aspect >= 1 ? 420 : 420 * shape.aspect, h = w / shape.aspect;
        float x0 = (Ink.W - w) / 2, y0 = visibleCenterY(index, h);
        ArrayList<Ink.Stroke> made = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            for (float[] line : pass == 0 ? shape.faint : shape.lines) {
                boolean faint = pass == 0;
                Ink.Stroke s = new Ink.Stroke(Ink.newId(), Ink.PEN, faint ? 0xFF94A3B8 : penColor, faint ? Math.max(1.2f, penWidth * 0.5f) : penWidth);
                for (int i = 0; i + 1 < line.length; i += 2) s.add(x0 + line[i] * w, y0 + line[i + 1] * h, 0.7f);
                made.add(s);
            }
        }
        page.strokes.addAll(made);
        commitSnapshot(page, old);
        selectInserted(index, made, new ArrayList<>());
    }

    /** Hoja de cálculo nueva en la página que se ve. */
    void insertTable(int rows, int cols) {
        Ink.Page page = visiblePage();
        if (page == null) return;
        int index = visible;
        String old = page.toJson();
        Ink.Item t = new Ink.Item(Ink.newId(), "table");
        t.rows = rows;
        t.cols = cols;
        t.cells = new String[rows * cols];
        java.util.Arrays.fill(t.cells, "");
        t.evaluate();
        t.w = Math.min(Ink.W - 80, cols * 170);
        t.h = rows * 56;
        t.x = (Ink.W - t.w) / 2;
        t.y = visibleCenterY(index, t.h);
        page.items.add(t);
        commitSnapshot(page, old);
        ArrayList<Ink.Item> one = new ArrayList<>();
        one.add(t);
        selectInserted(index, new ArrayList<>(), one);
    }

    /** Cambia las celdas (y el tamaño) de una hoja de cálculo. */
    void putTable(int index, Ink.Item t, String[] cells, int rows, int cols) {
        if (index < 0 || index >= pages.size()) return;
        Ink.Page page = pages.get(index);
        String old = page.toJson();
        float cellH = t.h / Math.max(1, t.rows), cellW = t.w / Math.max(1, t.cols);
        t.rows = rows;
        t.cols = cols;
        t.cells = cells;
        t.evaluate();
        t.w = Math.min(Ink.W - t.x - 10, cellW * cols);
        t.h = cellH * rows;
        commitSnapshot(page, old);
    }

    /** Texto pegado del portapapeles, como cuadro de texto en la página que se ve. */
    void pasteText(String text) {
        Ink.Page page = visiblePage();
        if (page == null || text.trim().isEmpty()) return;
        int index = visible;
        String old = page.toJson();
        Ink.Item it = new Ink.Item(Ink.newId(), "text");
        it.text = text;
        it.size = 28;
        it.color = penColor;
        it.x = 60;
        it.w = Ink.W - 120;
        it.y = visibleCenterY(index, renderer.itemHeight(it));
        page.items.add(it);
        commitSnapshot(page, old);
        ArrayList<Ink.Item> one = new ArrayList<>();
        one.add(it);
        selectInserted(index, new ArrayList<>(), one);
    }

    /** Vacía la página que se ve (se puede deshacer). */
    void clearVisiblePage() {
        Ink.Page page = visiblePage();
        if (page == null || (page.strokes.isEmpty() && page.items.isEmpty())) return;
        String old = page.toJson();
        clearSelection();
        page.strokes.clear();
        page.items.clear();
        commitSnapshot(page, old);
    }

    /** Crea o cambia un cuadro de texto (texto vacío: se borra). */
    void putText(int index, Ink.Item item, String text, float size, int color, float x, float y) {
        if (index < 0 || index >= pages.size()) return;
        Ink.Page page = pages.get(index);
        String old = page.toJson();
        if (item == null) {
            if (text.trim().isEmpty()) return;
            item = new Ink.Item(Ink.newId(), "text");
            item.x = x;
            item.y = y;
            item.w = Math.max(200, Ink.W - x - 40);
            page.items.add(item);
        } else if (text.trim().isEmpty()) {
            page.items.remove(item);
        }
        item.text = text;
        item.size = size;
        item.color = color;
        commitSnapshot(page, old);
    }

    // ---- Vista: encajar, zoom y desplazamiento ----

    // Las páginas van en filas: una página «a la derecha» se pone junto a la
    // anterior; las demás empiezan fila. El orden del cuaderno no cambia.
    private final HashSet<String> rightIds = new HashSet<>();
    private int[] rowOf = new int[0], colOf = new int[0];
    private int rows, cols = 1;
    private int laidOut = -1;

    /** Qué páginas van a la derecha de la anterior. */
    void setRightPages(java.util.Collection<String> ids) {
        rightIds.clear();
        if (ids != null) rightIds.addAll(ids);
        laidOut = -1;
        clampView(false);
        renderLayer();
    }

    private void grid() {
        if (laidOut == pages.size() && rowOf.length == pages.size()) return;
        int n = pages.size();
        rowOf = new int[n];
        colOf = new int[n];
        int r = -1, col = 0;
        cols = 1;
        for (int i = 0; i < n; i++) {
            if (i > 0 && rightIds.contains(pages.get(i).id)) col++;
            else {
                r++;
                col = 0;
            }
            rowOf[i] = r;
            colOf[i] = col;
            cols = Math.max(cols, col + 1);
        }
        rows = r + 1;
        laidOut = n;
    }

    private float pageTop(int i) {
        grid();
        if (i < 0) return 0;
        if (i >= rowOf.length) return (rows + i - rowOf.length) * (Ink.H + GAP);
        return rowOf[i] * (Ink.H + GAP);
    }

    private float pageLeft(int i) {
        grid();
        return i >= 0 && i < colOf.length ? colOf[i] * (Ink.W + GAP) : 0;
    }

    private float docHeight() {
        grid();
        return pages.isEmpty() ? Ink.H : rows * (Ink.H + GAP) - GAP;
    }

    private float docWidth() {
        grid();
        return cols * (Ink.W + GAP) - GAP;
    }

    /** La página en (x, y) del documento (la más cercana). */
    private int pageAt(float docX, float docY) {
        grid();
        if (pages.isEmpty()) return 0;
        int r = Math.max(0, Math.min(rows - 1, (int) Math.floor((docY + GAP / 2) / (Ink.H + GAP))));
        int c = Math.max(0, (int) Math.floor((docX + GAP / 2) / (Ink.W + GAP)));
        int best = 0;
        for (int i = 0; i < rowOf.length; i++) {
            if (rowOf[i] != r) continue;
            best = i;
            if (colOf[i] >= c) break;
        }
        return best;
    }

    private float margin() {
        return MARGIN_DP * density;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (fitted && visible >= 0) pendingScroll = visible;
        fitted = false;
        layout();
    }

    private void layout() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        fitScale = (w - 2 * margin()) / Ink.W;
        if (!fitted) {
            scale = fitScale;
            ox = (w - Ink.W * scale) / 2;
            oy = topInset + margin();
            fitted = true;
        }
        if (pendingScroll >= 0) {
            oy = topInset + margin() - pageTop(pendingScroll) * scale;
            ox = (w - Ink.W * scale) / 2 - pageLeft(pendingScroll) * scale;
            pendingScroll = -1;
        }
        clampView(false);
        pad = h / 2;
        if (!GPU && (layer == null || layer.getWidth() != w || layer.getHeight() != h + 2 * pad)) {
            if (layer != null) layer.recycle();
            layer = Bitmap.createBitmap(w, h + 2 * pad, Bitmap.Config.ARGB_8888);
            layerCanvas = new Canvas(layer);
        }
        renderLayer();
    }

    private void zoomAround(float fx, float fy, float factor) {
        float s = Math.max(fitScale * 0.5f, Math.min(fitScale * 6f, scale * factor));
        ox = fx - (fx - ox) * s / scale;
        oy = fy - (fy - oy) * s / scale;
        scale = s;
        clampView(false);
        invalidate();
    }

    private float minOy() {
        return Math.min(topInset + margin(), getHeight() - margin() - docHeight() * scale);
    }

    private float maxOy() {
        return topInset + margin();
    }

    private float overscroll() {
        return Math.max(0, minOy() - oy);
    }

    private void clampView(boolean pulling) {
        float w = getWidth(), pw = docWidth() * scale, m = margin();
        if (pw <= w - 2 * m) ox = (w - pw) / 2;
        else ox = Math.max(w - m - pw, Math.min(m, ox));
        float pullMax = pulling ? PULL_MAX_DP * density : 0;
        oy = Math.max(minOy() - pullMax, Math.min(maxOy(), oy));
        notifyVisible();
    }

    private void notifyVisible() {
        if (pages.isEmpty() || getHeight() == 0) return;
        int v = pageAt((getWidth() / 2f - ox) / scale, (getHeight() / 2f - oy) / scale);
        if (v != visible) {
            visible = v;
            if (listener != null) listener.onPageVisible(v);
        }
    }

    void scrollToPage(int i, boolean animate) {
        if (i < 0 || i >= pages.size()) return;
        float save = oy;
        oy = topInset + margin() - pageTop(i) * scale;
        if (pageLeft(i) > 0 || ox < 0) ox = (getWidth() - Ink.W * scale) / 2 - pageLeft(i) * scale;
        clampView(false);
        float target = oy;
        oy = save;
        if (animate) animateOy(target, 380);
        else {
            oy = target;
            notifyVisible();
            afterScroll();
        }
    }

    private void animateOy(float target, long ms) {
        if (scroller != null) scroller.cancel();
        scroller = ValueAnimator.ofFloat(oy, target);
        scroller.setDuration(ms);
        scroller.setInterpolator(new DecelerateInterpolator(1.6f));
        scroller.addUpdateListener(a -> {
            oy = (float) a.getAnimatedValue();
            notifyVisible();
            afterScroll();
        });
        scroller.start();
    }

    private void afterScroll() {
        if (GPU) {
            invalidate();
            return;
        }
        if (layerScale != scale || Math.abs(ox - layerOx) > 1 || Math.abs(oy - layerOy) > pad * 0.7f) renderLayer();
        else invalidate();
    }

    private boolean layerAligned() {
        return GPU || layerScale == scale && layerOx == ox && layerOy == oy;
    }

    // ---- Dibujo ----

    private void onLayerPage(int i) {
        layerCanvas.save();
        layerCanvas.translate(layerOx, layerOy + pad);
        layerCanvas.scale(layerScale, layerScale);
        layerCanvas.translate(pageLeft(i), pageTop(i));
    }

    /** Pantalla → página i en el canvas dado. */
    private void onScreenPage(Canvas c, int i) {
        c.save();
        c.translate(ox, oy);
        c.scale(scale, scale);
        c.translate(pageLeft(i), pageTop(i));
    }

    /** Ids que no se pintan en el bitmap (la selección mientras se arrastra). */
    private HashSet<String> hidden(int page) {
        if (mode != DRAG || page != selPage) return null;
        HashSet<String> h = new HashSet<>();
        for (Ink.Stroke s : selStrokes) h.add(s.id);
        for (Ink.Item it : selItems) h.add(it.id);
        return h;
    }

    private void renderLayer() {
        if (GPU) {
            for (PageNode n : nodes.values()) n.dirty = true;
            invalidate();
            return;
        }
        if (layer == null) return;
        Canvas c = layerCanvas;
        drawDesk(c);
        layerScale = scale;
        layerOx = ox;
        layerOy = oy;
        float top = (-pad - oy) / scale, bottom = (getHeight() + pad - oy) / scale;
        for (int i = 0; i < pages.size(); i++) {
            float y = pageTop(i);
            if (y > bottom || y + Ink.H < top) continue;
            onLayerPage(i);
            c.drawRect(3, 5, Ink.W + 3, Ink.H + 5, shade);
            renderer.drawPage(c, pages.get(i), scale, hidden(i), revealed);
            c.restore();
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (GPU) {
            if (pages.isEmpty()) return;
            drawPageNodes(c);
            layerScale = scale;
        } else if (layer == null) {
            return;
        } else if (layerScale == scale) {
            c.drawBitmap(layer, ox - layerOx, oy - layerOy - pad, null);
        } else {
            drawDesk(c);
            float k = scale / layerScale;
            c.save();
            c.translate(ox - layerOx * k, oy - (layerOy + pad) * k);
            c.scale(k, k);
            c.drawBitmap(layer, 0, 0, null);
            c.restore();
        }
        if (mode == STROKE && current != null && current.n > 0
                && (GPU || Ink.HIGHLIGHTER.equals(current.tool) || Ink.TAPE.equals(current.tool) || Ink.patterned(current.tool) || shaping || snapped)) {
            onScreenPage(c, currentPage);
            renderer.drawStroke(c, current, false);
            c.restore();
        }
        drawNotes(c);
        drawMarks(c);
        drawLasso(c);
        drawSelection(c);
        drawLasers(c);
        drawPull(c);
        guides.draw(c, desk != DESK);
        // Escribiendo, lo que hay tras el cristal no cambia: no hace falta repintarlo.
        if (onFrame != null && c.isHardwareAccelerated() && (mode == NONE || mode == DRAG)) onFrame.run();
    }

    /** Las páginas a la vista, ya en la GPU. Con un zoom en curso se estiran las que hay. */
    private void drawPageNodes(Canvas c) {
        drawDesk(c);
        float top = -oy / scale, bottom = (getHeight() - oy) / scale;
        boolean zooming = scaler.isInProgress() || zoomAnimator != null;
        for (int i = 0; i < pages.size(); i++) {
            float y = pageTop(i);
            if (y > bottom || y + Ink.H + 8 < top) continue;
            PageNode n = pageNode(i, zooming);
            c.save();
            c.translate(ox + pageLeft(i) * scale, oy + y * scale);
            if (n.scale != scale) c.scale(scale / n.scale, scale / n.scale);
            c.drawRenderNode(n.node);
            if (n.recent.hasDisplayList()) c.drawRenderNode(n.recent);
            c.restore();
        }
    }

    private PageNode pageNode(int i, boolean zooming) {
        Ink.Page page = pages.get(i);
        PageNode n = nodes.get(page);
        if (n == null) {
            n = new PageNode();
            nodes.put(page, n);
        }
        // Una página que sale de pantalla pierde su grabación: Android la borra para
        // ahorrar memoria. Hay que preguntar, no suponer que sigue ahí. Lo mismo la
        // capa de lo reciente, si quedaban trazos sólo en ella.
        boolean lost = !n.node.hasDisplayList() || (n.baked != page.strokes.size() && !n.recent.hasDisplayList());
        if (lost || n.dirty || n.scale == 0 || (n.scale != scale && !zooming)) record(n, i);
        return n;
    }

    private void record(PageNode n, int i) {
        n.scale = scale;
        n.dirty = false;
        n.baked = pages.get(i).strokes.size();
        n.recent.discardDisplayList();
        int w = (int) Math.ceil((Ink.W + 4) * scale), h = (int) Math.ceil((Ink.H + 6) * scale);
        n.node.setPosition(0, 0, w, h);
        n.node.setUseCompositingLayer((long) w * h <= MAX_TEXTURE_PIXELS, null);
        Canvas r = n.node.beginRecording(w, h);
        try {
            r.scale(scale, scale);
            r.drawRect(3, 5, Ink.W + 3, Ink.H + 5, shade);
            renderer.drawPage(r, pages.get(i), scale, hidden(i), revealed);
        } finally {
            n.node.endRecording();
        }
    }

    /** Un trazo nuevo en la página: va a la capa ligera. */
    private void strokeAdded(Ink.Page page) {
        PageNode n = nodes.get(page);
        if (n != null && !n.dirty) {
            if (page.strokes.size() - n.baked > MAX_RECENT) {
                n.dirty = true;
                invalidate();
                return;
            }
            int w = (int) Math.ceil((Ink.W + 4) * n.scale), h = (int) Math.ceil((Ink.H + 6) * n.scale);
            n.recent.setPosition(0, 0, w, h);
            n.recent.setUseCompositingLayer((long) w * h <= MAX_TEXTURE_PIXELS, null);
            Canvas r = n.recent.beginRecording(w, h);
            try {
                r.scale(n.scale, n.scale);
                for (int i = n.baked; i < page.strokes.size(); i++) renderer.drawStroke(r, page.strokes.get(i), revealed.contains(page.strokes.get(i).id));
            } finally {
                n.recent.endRecording();
            }
        }
        removeCallbacks(bake);
        postDelayed(bake, BAKE_DELAY_MS);
        invalidate();
    }

    private void bakeRecent() {
        if (pointerId != -1) {
            postDelayed(bake, BAKE_DELAY_MS);
            return;
        }
        for (Map.Entry<Ink.Page, PageNode> e : nodes.entrySet()) {
            if (e.getValue().baked != e.getKey().strokes.size()) e.getValue().dirty = true;
        }
        invalidate();
    }

    /**
     * Android libera las texturas al ir la app al fondo o bloquear la tablet, y una
     * grabación suelta no se entera: se vería el escritorio sin hojas. Se tiran y
     * se graban de nuevo.
     */
    /** Hoja oscura: solo cambia lo que se ve aquí, no los colores guardados. */
    void setDarkPaper(boolean on) {
        renderer.setDark(on);
        desk = on ? 0xFF0B0D10 : DESK;
        handle.setColor(on ? 0xFFE5E7EB : 0xFF1F2937);
        dashed.setColor(on ? 0xFF9CA3AF : 0xFF6B7280);
        label.setColor(on ? 0xFFD1D5DB : 0xFF4B5563);
        setBackgroundColor(desk);
    }

    private Bitmap deskPhoto;
    private int deskShade;
    private final Paint deskPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.Rect deskCrop = new android.graphics.Rect(), deskAll = new android.graphics.Rect();

    /** Foto de fondo alrededor de las hojas, ya difuminada. `dim`: 0–100. */
    void setDesk(Bitmap photo, int dim) {
        deskPhoto = photo;
        deskShade = Math.round(dim * 2.2f) << 24;
    }

    /** Lo que hay detrás de las hojas; la foto llena la pantalla sin deformarse. */
    private void drawDesk(Canvas c) {
        c.drawColor(desk);
        if (deskPhoto == null) return;
        int w = getWidth(), h = getHeight(), pw = deskPhoto.getWidth(), ph = deskPhoto.getHeight();
        float k = Math.max((float) w / pw, (float) h / ph);
        int cw = Math.round(w / k), ch = Math.round(h / k);
        deskCrop.set((pw - cw) / 2, (ph - ch) / 2, (pw + cw) / 2, (ph + ch) / 2);
        deskAll.set(0, 0, w, h);
        c.drawBitmap(deskPhoto, deskCrop, deskAll, deskPaint);
        if (deskShade != 0) c.drawColor(deskShade);
    }

    /** Deshacer y rehacer sobreviven a recrear la pantalla. */
    Object history() {
        return new Object[] {new ArrayDeque<>(undo), new ArrayDeque<>(redo)};
    }

    @SuppressWarnings("unchecked")
    void restoreHistory(Object saved) {
        Object[] both = (Object[]) saved;
        undo.addAll((ArrayDeque<Object[]>) both[0]);
        redo.addAll((ArrayDeque<Object[]>) both[1]);
        notifyUndo();
    }

    void redrawPages() {
        if (!GPU) return;
        for (PageNode n : nodes.values()) n.discard();
        nodes.clear();
        invalidate();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) redrawPages();
    }

    /** Lo que se ve detrás del cristal: las mismas texturas, sin lo que flota encima. */
    void drawGlassSource(Canvas c) {
        if (GPU && !pages.isEmpty()) drawPageNodes(c);
    }

    private void drawLasso(Canvas c) {
        if (mode != LASSO_DRAW || lasso.size() < 4) return;
        Path p = new Path();
        p.moveTo(lasso.get(0), lasso.get(1));
        for (int i = 2; i + 1 < lasso.size(); i += 2) p.lineTo(lasso.get(i), lasso.get(i + 1));
        onScreenPage(c, currentPage);
        dashed.setStrokeWidth(2 * density / scale);
        c.drawPath(p, dashed);
        dashed.setStrokeWidth(2 * density);
        c.restore();
    }

    private void drawSelection(Canvas c) {
        if (selPage < 0) return;
        onScreenPage(c, selPage);
        if (mode == DRAG) {
            // La selección, movida/escalada en vivo.
            if (dragSpec != null) {
                renderer.drawStroke(c, selStrokes.get(0).withShape(dragSpec), false);
                drawLineHandles(c, dragSpec);
                c.restore();
                return;
            }
            float[] box = selArray(), m = dragAffine();
            // Los textos se dibujan como quedarán: se reacomodan, no se deforman.
            boolean even = SelectionMath.uniform(box, dragBox);
            for (Ink.Item it : selItems) {
                Ink.Item shown = it.copy(it.id);
                mapItem(shown, m, even);
                renderer.drawItem(c, shown);
            }
            c.save();
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.setValues(new float[] {m[0], m[2], m[4], m[1], m[3], m[5], 0, 0, 1});
            for (Ink.Stroke s : selStrokes) {
                // Una figura se ve ya rehecha; un trazo libre, deformado sobre la marcha.
                if (s.shape != null) {
                    renderer.drawStroke(c, s.mapped(m, even ? (dragBox[2] - dragBox[0]) / (box[2] - box[0]) : 1, dragAngle), revealed.contains(s.id));
                } else {
                    c.save();
                    c.concat(matrix);
                    renderer.drawStroke(c, s, revealed.contains(s.id));
                    c.restore();
                }
            }
            c.restore();
        }
        Ink.Stroke line = selectedLine();
        if (line != null && mode != DRAG) {
            drawLineHandles(c, line.shape);
            c.restore();
            return;
        }
        RectF b = shownBox();
        float cx = b.centerX(), cy = b.centerY(), dot = 7 * density / scale;
        c.save();
        c.rotate((float) Math.toDegrees(mode == DRAG ? dragAngle : 0), cx, cy);
        dashed.setStrokeWidth(1.5f * density / scale);
        c.drawRect(b, dashed);
        dashed.setStrokeWidth(2 * density);
        for (float x : new float[] {b.left, b.right}) for (float y : new float[] {b.top, b.bottom}) c.drawCircle(x, y, dot, handle);
        c.drawCircle(b.left, cy, dot, handle);
        c.drawCircle(b.right, cy, dot, handle);
        if (stretchesVertically()) {
            c.drawCircle(cx, b.top, dot, handle);
            c.drawCircle(cx, b.bottom, dot, handle);
        }
        if (rotates()) {
            float gap = ROTATE_GAP * density / scale;
            handle.setStrokeWidth(1.5f * density / scale);
            c.drawLine(cx, b.top, cx, b.top - gap, handle);
            c.drawCircle(cx, b.top - gap, dot * 1.25f, handle);
        }
        c.restore();
        c.restore();
    }

    private void drawLineHandles(Canvas c, ShapeTools.Spec line) {
        float dot = 9 * density / scale;
        c.drawCircle(line.x0, line.y0, dot, handle);
        c.drawCircle(line.x1, line.y1, dot, handle);
    }

    /** La línea o flecha seleccionada, si es lo único seleccionado: se edita por sus puntas. */
    Ink.Stroke selectedLine() {
        if (!selItems.isEmpty() || selStrokes.size() != 1) return null;
        Ink.Stroke s = selStrokes.get(0);
        return s.shape != null && s.shape.isLine() ? s : null;
    }

    /** Pasa la línea seleccionada a flecha, a flecha doble y otra vez a línea. */
    void cycleSelectedLine() {
        Ink.Stroke s = selectedLine();
        if (s == null) return;
        int kind = s.shape.kind;
        int next = kind >= ShapeTools.AND ? ShapeTools.nextGate(kind)
                : kind >= ShapeTools.NPN && kind <= ShapeTools.PMOS ? ShapeTools.nextTransistor(kind) : ShapeTools.nextLineKind(kind);
        replaceSelected(s, s.withShape(s.shape.withKind(next)));
    }

    /** ¿Lo seleccionado cambia de aspecto con el botón? Una línea o un transistor. */
    boolean selectedHasVariants() {
        Ink.Stroke s = selectedLine();
        return s != null && (!s.shape.isComponent() || s.shape.kind >= ShapeTools.AND || (s.shape.kind >= ShapeTools.NPN && s.shape.kind <= ShapeTools.PMOS));
    }

    /** ¿Lo seleccionado se puede voltear? */
    boolean selectedFlips() {
        Ink.Stroke s = selectedLine();
        return s != null && s.shape.isThreeTerminal() && s.shape.kind < ShapeTools.AND;
    }

    void flipSelected() {
        Ink.Stroke s = selectedLine();
        if (s != null) replaceSelected(s, s.withShape(s.shape.flipped()));
    }

    private void replaceSelected(Ink.Stroke s, Ink.Stroke t) {
        Ink.Page page = pages.get(selPage);
        String old = page.toJson();
        int at = page.strokes.indexOf(s);
        if (at >= 0) page.strokes.set(at, t);
        selStrokes.set(0, t);
        updateSelBox();
        commitSnapshot(page, old);
    }

    /** Mueve una punta: se engancha a otra figura cercana, o endereza su ángulo. */
    private ShapeTools.Spec lineWithEnd(ShapeTools.Spec line, boolean first, float x, float y) {
        float[] hit = nearestAnchor(pages.get(selPage), selStrokes.get(0), x, y, ATTACH);
        if (hit == null) {
            hit = ShapeTools.snapAngle(first ? line.x1 : line.x0, first ? line.y1 : line.y0, x, y, 4);
        }
        return line.withEnd(first, hit[0], hit[1]);
    }

    /** El punto de enganche de otra figura más cercano, o null. */
    private static float[] nearestAnchor(Ink.Page page, Ink.Stroke self, float x, float y, float reach) {
        float best = reach;
        float[] hit = null;
        for (Ink.Stroke s : page.strokes) {
            if (s == self || s.shape == null) continue;
            float[] a = s.shape.anchors();
            for (int i = 0; i + 1 < a.length; i += 2) {
                float d = (float) Math.hypot(a[i] - x, a[i + 1] - y);
                if (d < best) {
                    best = d;
                    hit = new float[] {a[i], a[i + 1]};
                }
            }
        }
        return hit;
    }

    // ---- Circuitos ----

    /** Lo dibujado a mano pasa a ser el componente que parece, con las patas enganchadas. */
    private void drawnComponent(Ink.Stroke s) {
        float[] xy = new float[2 * s.n];
        for (int i = 0; i < s.n; i++) {
            xy[2 * i] = s.x(i);
            xy[2 * i + 1] = s.y(i);
        }
        ShapeTools.Spec spec = ShapeRecognizer.circuit(xy, s.n);
        if (spec == null) return;
        Ink.Page page = pages.get(currentPage);
        if (spec.kind == ShapeTools.VOLTAGE) spec = orientSource(page, spec);
        float[] a = nearestAnchor(page, s, spec.x0, spec.y0, CIRCUIT_REACH), b = nearestAnchor(page, s, spec.x1, spec.y1, CIRCUIT_REACH);
        if (a != null) spec = spec.withEnd(true, a[0], a[1]);
        if (b != null) spec = spec.withEnd(false, b[0], b[1]);
        // Un cable o un componente sueltos, derechos si casi lo están.
        if (a == null || b == null) {
            float[] end = ShapeTools.snapAngle(spec.x0, spec.y0, spec.x1, spec.y1, 8);
            if (b == null) spec = spec.withEnd(false, end[0], end[1]);
        }
        s.replace(spec.points(), 0.7f);
        s.shape = spec;
    }

    /** Un cable o componente colocado desde el dock se une a los terminales que tiene cerca. */
    private void hookEnds(Ink.Page page, Ink.Stroke s) {
        ShapeTools.Spec spec = s.shape;
        if (spec == null || (spec.kind != ShapeTools.LINE && (!spec.isComponent() || spec.isThreeTerminal()))) return;
        float[] a = nearestAnchor(page, s, spec.x0, spec.y0, CIRCUIT_REACH);
        float[] b = spec.kind == ShapeTools.GROUND ? null : nearestAnchor(page, s, spec.x1, spec.y1, CIRCUIT_REACH);
        if (a == null && b == null) return;
        if (a != null) spec = spec.withEnd(true, a[0], a[1]);
        if (b != null) spec = spec.withEnd(false, b[0], b[1]);
        s.replace(spec.points(), SHAPE_PRESSURE);
        s.shape = spec;
    }

    /** Una fuente se tumba si los cables cercanos le llegan por los lados. */
    private ShapeTools.Spec orientSource(Ink.Page page, ShapeTools.Spec v) {
        float cx = (v.x0 + v.x1) / 2, cy = (v.y0 + v.y1) / 2, r = Math.abs(v.y1 - v.y0) / 2;
        boolean sides = nearestAnchor(page, null, cx - r, cy, CIRCUIT_REACH) != null || nearestAnchor(page, null, cx + r, cy, CIRCUIT_REACH) != null;
        boolean ends = nearestAnchor(page, null, cx, cy - r, CIRCUIT_REACH) != null || nearestAnchor(page, null, cx, cy + r, CIRCUIT_REACH) != null;
        return sides && !ends ? new ShapeTools.Spec(v.kind, cx - r, cy, cx + r, cy, 0) : v;
    }

    /** Un toque sobre un componente pide su valor. */
    private boolean tapComponent(Ink.Stroke tap) {
        Ink.Page page = pages.get(currentPage);
        float x = tap.x(0), y = tap.y(0);
        for (int i = page.strokes.size() - 1; i >= 0; i--) {
            Ink.Stroke s = page.strokes.get(i);
            if (s.shape == null || !s.shape.isComponent() || !s.hits(x, y, 18)) continue;
            Ink.Item label = labelOf(page, s);
            listener.onValueRequest(currentPage, s, label == null ? "" : label.text);
            return true;
        }
        return false;
    }

    private static Ink.Item labelOf(Ink.Page page, Ink.Stroke s) {
        if (s.shape == null || s.shape.label == null) return null;
        for (Ink.Item it : page.items) if (it.id.equals(s.shape.label)) return it;
        return null;
    }

    private void placeLabel(Ink.Item label, ShapeTools.Spec spec) {
        float[] p = spec.labelPosition();
        label.w = Math.max(70, 15 * (label.text == null ? 0 : label.text.length()) + 20);
        label.x = p[0] - label.w / 2 + 8;
        label.y = p[1] - label.size * 0.65f;
    }

    /** Pone, cambia o quita (texto vacío) el valor de un componente. */
    void setValue(int index, Ink.Stroke s, String value) {
        Ink.Page page = pages.get(index);
        int at = page.strokes.indexOf(s);
        if (at < 0) return;
        String old = page.toJson();
        Ink.Item label = labelOf(page, s);
        String text = value.trim();
        if (text.isEmpty()) {
            if (label != null) page.items.remove(label);
            page.strokes.set(at, s.withShape(s.shape.withLabel(null)));
        } else {
            if (label == null) {
                label = new Ink.Item(Ink.newId(), "text");
                label.size = 26;
                label.color = s.color;
                page.items.add(label);
                page.strokes.set(at, s.withShape(s.shape.withLabel(label.id)));
            }
            label.text = text;
            placeLabel(label, s.shape);
        }
        commitSnapshot(page, old);
    }

    /** El valor sigue al componente. */
    private void moveLabels(Ink.Page page) {
        for (Ink.Stroke s : selStrokes) {
            Ink.Item label = labelOf(page, s);
            if (label != null) placeLabel(label, s.shape);
        }
    }

    // ---- Notas ----

    private void startNote(MotionEvent e, int idx) {
        float docX = (e.getX(idx) - ox) / scale, docY = (e.getY(idx) - oy) / scale;
        notePage = pageAt(docX, docY);
        float x = docX - pageLeft(notePage), y = docY - pageTop(notePage), reach = 26 * density / scale;
        noteDownX = x;
        noteDownY = y;
        noteMoved = false;
        activeNote = null;
        Ink.Page page = pages.get(notePage);
        for (int i = page.notes.size() - 1; i >= 0 && activeNote == null; i--) {
            Ink.Note n = page.notes.get(i);
            float h = renderer.noteHeight(n);
            if (n.arrow && Math.hypot(x - n.ax, y - n.ay) < reach) {
                activeNote = n;
                mode = NOTE_ARROW;
            } else if (Math.hypot(x - (n.x + n.w), y - (n.y + h)) < reach) {
                activeNote = n;
                mode = NOTE_ARROW;
            } else if (x >= n.x && x <= n.x + n.w && y >= n.y && y <= n.y + h) {
                activeNote = n;
                mode = NOTE_MOVE;
                noteDx = x - n.x;
                noteDy = y - n.y;
            }
        }
        if (activeNote == null) mode = NOTE_NEW;
        else noteBefore = page.toJson();
    }

    private void moveNote(float x, float y) {
        Ink.Note n = activeNote;
        if (n == null) return;
        if (Math.hypot(x - noteDownX, y - noteDownY) > 6 / scale * density) noteMoved = true;
        if (!noteMoved) return;
        if (mode == NOTE_MOVE) {
            float dx = x - noteDx - n.x, dy = y - noteDy - n.y;
            n.x += dx;
            n.y += dy;
        } else {
            n.arrow = true;
            n.ax = x;
            n.ay = y;
        }
        invalidate();
    }

    private void finishNote(int m) {
        Ink.Page page = pages.get(notePage);
        if (m == NOTE_NEW) {
            listener.onNoteRequest(notePage, null, noteDownX, noteDownY);
        } else if (noteMoved) {
            commitSnapshot(page, noteBefore);
        } else if (m == NOTE_MOVE) {
            listener.onNoteRequest(notePage, activeNote, activeNote.x, activeNote.y);
        }
        activeNote = null;
        invalidate();
    }

    /** Crea o cambia una nota; con texto vacío, la borra. */
    void putNote(int index, Ink.Note note, float x, float y, String text, int color) {
        Ink.Page page = pages.get(index);
        String old = page.toJson();
        boolean empty = text.trim().isEmpty();
        if (note == null) {
            if (empty) return;
            note = new Ink.Note(Ink.newId());
            note.x = x - note.w / 2;
            note.y = y - 30;
            page.notes.add(note);
        } else if (empty) {
            page.notes.remove(note);
        }
        note.text = text.trim();
        note.color = color;
        commitSnapshot(page, old);
        invalidate();
    }

    // ---- Lápiz neurona ----

    private static final int[] MARK_COLORS = {0xFF22D3EE, 0xFFA78BFA, 0xFFF472B6, 0xFF34D399, 0xFFFBBF24, 0xFF60A5FA, 0xFFFB923C};
    private float[] linkPath = new float[256];
    private int linkN;
    private final Paint markFill = new Paint(Paint.ANTI_ALIAS_FLAG), markLine = new Paint(Paint.ANTI_ALIAS_FLAG), markText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path markCurve = new Path();
    private final RectF markBox = new RectF();

    /** Cada palabra clave tiene su color en todas las páginas. */
    static int markColor(Ink.Mark m) {
        return m.words.isEmpty() ? 0xFF94A3B8 : MARK_COLORS[Math.floorMod(m.words.get(0).toLowerCase(java.util.Locale.ROOT).hashCode(), MARK_COLORS.length)];
    }

    private Ink.Mark markAt(Ink.Page p, float x, float y) {
        for (int i = p.marks.size() - 1; i >= 0; i--) if (p.marks.get(i).contains(x, y)) return p.marks.get(i);
        return null;
    }

    private void addLinkPoint(float x, float y) {
        if (linkN + 2 > linkPath.length) linkPath = java.util.Arrays.copyOf(linkPath, linkPath.length * 2);
        linkPath[linkN++] = x;
        linkPath[linkN++] = y;
        invalidate();
    }

    /** Un toque abre la marca; de marca a marca, las enlaza; rodear algo crea una. */
    private void finishLink() {
        int n = linkN;
        linkN = 0;
        invalidate();
        if (n < 2 || pages.isEmpty()) return;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < n; i += 2) {
            minX = Math.min(minX, linkPath[i]);
            maxX = Math.max(maxX, linkPath[i]);
            minY = Math.min(minY, linkPath[i + 1]);
            maxY = Math.max(maxY, linkPath[i + 1]);
        }
        int first = pageAt(linkPath[0], linkPath[1]), last = pageAt(linkPath[n - 2], linkPath[n - 1]);
        Ink.Page pa = pages.get(first), pb = pages.get(last);
        Ink.Mark a = markAt(pa, linkPath[0] - pageLeft(first), linkPath[1] - pageTop(first));
        Ink.Mark b = markAt(pb, linkPath[n - 2] - pageLeft(last), linkPath[n - 1] - pageTop(last));
        if (maxX - minX + maxY - minY < 16) {
            if (a != null) listener.onMarkRequest(first, a, a.x, a.y, a.w, a.h);
            return;
        }
        if (a != null && b != null && a != b) {
            String oldA = pa.toJson(), oldB = pb.toJson();
            if (!a.links.contains(b.id)) a.links.add(b.id);
            if (!b.links.contains(a.id)) b.links.add(a.id);
            commitSnapshot(pa, oldA);
            if (pb != pa) commitSnapshot(pb, oldB);
            listener.onMarksLinked();
            invalidate();
            return;
        }
        if (maxX - minX < 30 || maxY - minY < 16) return;
        float top = pageTop(first);
        minX -= pageLeft(first);
        maxX -= pageLeft(first);
        listener.onMarkRequest(first, null, minX, minY - top, maxX - minX, Math.min(maxY - top, Ink.H) - (minY - top));
    }

    /** Guarda una marca; `words` es lo que el usuario escribió, separado por comas. */
    void putMark(int index, Ink.Mark mark, float x, float y, float w, float h, String words) {
        Ink.Page page = pages.get(index);
        String old = page.toJson();
        if (mark == null) {
            mark = new Ink.Mark(Ink.newId());
            mark.x = x;
            mark.y = y;
            mark.w = w;
            mark.h = h;
            page.marks.add(mark);
        }
        mark.words.clear();
        for (String word : words.split(",")) if (!word.trim().isEmpty()) mark.words.add(word.trim());
        commitSnapshot(page, old);
        invalidate();
    }

    /** Quita la marca y los enlaces que otras marcas tenían hacia ella. */
    void removeMark(int index, Ink.Mark mark) {
        Ink.Page page = pages.get(index);
        for (Ink.Page p : pages) {
            if (p != page && !hasLinkTo(p, mark.id)) continue;
            String old = p.toJson();
            for (Ink.Mark m : p.marks) m.links.remove(mark.id);
            if (p == page) page.marks.remove(mark);
            commitSnapshot(p, old);
        }
        invalidate();
    }

    private static boolean hasLinkTo(Ink.Page p, String id) {
        for (Ink.Mark m : p.marks) if (m.links.contains(id)) return true;
        return false;
    }

    /** Lo escrito a máquina dentro de una zona: da las palabras que se sugieren. */
    String textNear(int index, float x, float y, float w, float h) {
        Ink.Page p = pages.get(index);
        StringBuilder b = new StringBuilder();
        for (Ink.Item it : p.items) {
            if (it.text != null && it.x < x + w && it.x + it.w > x && it.y < y + h && it.y + renderer.itemHeight(it) > y) b.append(it.text).append(' ');
        }
        if (b.length() == 0 && p.bgText != null) b.append(p.bgText);
        return b.toString();
    }

    private void drawMarks(Canvas c) {
        boolean editing = tool == LINK;
        HashMap<String, float[]> centers = null;
        float top = -oy / scale, bottom = (getHeight() - oy) / scale;
        float px = 1 / scale;
        for (int i = 0; i < pages.size(); i++) {
            Ink.Page p = pages.get(i);
            if (p.marks.isEmpty()) continue;
            float y = pageTop(i);
            if (centers == null) centers = new HashMap<>();
            for (Ink.Mark m : p.marks) centers.put(m.id, new float[] {pageLeft(i) + m.x + m.w / 2, y + m.y + m.h / 2});
            if (y > bottom || y + Ink.H < top) continue;
            onScreenPage(c, i);
            for (Ink.Mark m : p.marks) {
                int color = markColor(m);
                markBox.set(m.x, m.y, m.x + m.w, m.y + m.h);
                markFill.setColor(color & 0xFFFFFF | (editing ? 0x26000000 : 0x10000000));
                c.drawRoundRect(markBox, 16, 16, markFill);
                markLine.setStyle(Paint.Style.STROKE);
                markLine.setColor(color & 0xFFFFFF | 0x33000000);
                markLine.setStrokeWidth(9 * density * px);
                if (editing) c.drawRoundRect(markBox, 16, 16, markLine);
                markLine.setColor(color & 0xFFFFFF | (editing ? 0xE6000000 : 0x66000000));
                markLine.setStrokeWidth(1.6f * density * px);
                c.drawRoundRect(markBox, 16, 16, markLine);
                if (m.words.isEmpty()) continue;
                markText.setTextSize(13 * density * px);
                markText.setFakeBoldText(true);
                String label = String.join(" · ", m.words);
                float tw = markText.measureText(label), pad = 7 * density * px, th = 20 * density * px;
                markBox.set(m.x, m.y - th - 3 * density * px, m.x + tw + 2 * pad, m.y - 3 * density * px);
                markFill.setColor(color);
                c.drawRoundRect(markBox, th / 2, th / 2, markFill);
                markText.setColor(0xFF0B1220);
                c.drawText(label, m.x + pad, markBox.bottom - 6 * density * px, markText);
            }
            c.restore();
        }
        if (centers != null) drawMarkLinks(c, centers);
        if (linkN >= 4) {
            c.save();
            c.translate(ox, oy);
            c.scale(scale, scale);
            markCurve.reset();
            markCurve.moveTo(linkPath[0], linkPath[1]);
            for (int i = 2; i + 1 < linkN; i += 2) markCurve.lineTo(linkPath[i], linkPath[i + 1]);
            markLine.setStyle(Paint.Style.STROKE);
            markLine.setStrokeCap(Paint.Cap.ROUND);
            markLine.setColor(0x4422D3EE);
            markLine.setStrokeWidth(10 * density * px);
            c.drawPath(markCurve, markLine);
            markLine.setColor(0xFF22D3EE);
            markLine.setStrokeWidth(2.4f * density * px);
            c.drawPath(markCurve, markLine);
            c.restore();
        }
    }

    /** Los enlaces: curvas de un centro a otro, una vez por pareja. */
    private void drawMarkLinks(Canvas c, HashMap<String, float[]> centers) {
        float px = 1 / scale;
        c.save();
        c.translate(ox, oy);
        c.scale(scale, scale);
        markLine.setStyle(Paint.Style.STROKE);
        markLine.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < pages.size(); i++) {
            for (Ink.Mark m : pages.get(i).marks) {
                float[] a = centers.get(m.id);
                for (String other : m.links) {
                    float[] b = centers.get(other);
                    if (b == null || m.id.compareTo(other) > 0) continue;
                    // El punto de control se aparta de la línea recta, hacia un lado.
                    float dx = b[0] - a[0], dy = b[1] - a[1], k = 0.18f;
                    markCurve.reset();
                    markCurve.moveTo(a[0], a[1]);
                    markCurve.quadTo((a[0] + b[0]) / 2 - dy * k, (a[1] + b[1]) / 2 + dx * k, b[0], b[1]);
                    int color = markColor(m);
                    markLine.setColor(color & 0xFFFFFF | 0x33000000);
                    markLine.setStrokeWidth(8 * density * px);
                    c.drawPath(markCurve, markLine);
                    markLine.setColor(color & 0xFFFFFF | 0xCC000000);
                    markLine.setStrokeWidth(2 * density * px);
                    c.drawPath(markCurve, markLine);
                }
            }
        }
        c.restore();
    }

    private void drawNotes(Canvas c) {
        float top = -oy / scale, bottom = (getHeight() - oy) / scale;
        for (int i = 0; i < pages.size(); i++) {
            Ink.Page p = pages.get(i);
            if (p.notes.isEmpty()) continue;
            float y = pageTop(i);
            if (y - 900 > bottom || y + Ink.H + 900 < top) continue;
            onScreenPage(c, i);
            for (Ink.Note n : p.notes) renderer.drawNote(c, n, tool == NOTE);
            c.restore();
        }
    }

    /** Al usar notas, la hoja se aleja para dejar sitio a los lados. */
    void roomForNotes(boolean on) {
        float target = on ? fitScale * NOTE_ZOOM : fitScale;
        if (on) zoomBeforeNotes = scale;
        else if (Math.abs(scale - fitScale * NOTE_ZOOM) > 0.01f) return;
        else target = zoomBeforeNotes > 0 ? zoomBeforeNotes : fitScale;
        if (Math.abs(target - scale) < 0.001f) return;
        if (zoomAnimator != null) zoomAnimator.cancel();
        zoomAnimator = ValueAnimator.ofFloat(scale, target);
        zoomAnimator.setDuration(340);
        zoomAnimator.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0.9f, 0.2f, 1f));
        zoomAnimator.addUpdateListener(a -> zoomAround(getWidth() / 2f, getHeight() / 2f, (float) a.getAnimatedValue() / scale));
        zoomAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                zoomAnimator = null;
                invalidate();
            }
        });
        zoomAnimator.start();
    }

    private float[] selArray() {
        return new float[] {selBox.left, selBox.top, selBox.right, selBox.bottom};
    }

    /** Un texto solo mide de alto lo que ocupan sus líneas. */
    private boolean stretchesVertically() {
        if (!selStrokes.isEmpty()) return true;
        for (Ink.Item it : selItems) if (it.isImage() || it.isTable()) return true;
        return false;
    }

    /** Las imágenes y los textos no se giran. */
    private boolean rotates() {
        return selItems.isEmpty();
    }

    /** Lleva un elemento a su sitio y tamaño nuevos (sin giro). */
    private static void mapItem(Ink.Item it, float[] m, boolean even) {
        float sx = m[0], sy = m[3];
        it.x = sx * it.x + m[4];
        it.y = sy * it.y + m[5];
        if (it.isImage()) {
            it.w *= sx;
            it.h *= sy;
        } else if (it.isTable()) {
            it.w *= sx;
            it.h *= sy;
            it.size *= Math.min(sx, sy);
        } else {
            it.w = Math.max(40, it.w * sx);
            if (even) it.size *= sx;
        }
    }

    /** La caja de la selección con el arrastre en curso. */
    private RectF shownBox() {
        return mode == DRAG ? new RectF(dragBox[0], dragBox[1], dragBox[2], dragBox[3]) : new RectF(selBox);
    }

    private void drawLasers(Canvas c) {
        long now = SystemClock.uptimeMillis();
        boolean any = false;
        for (int t = laserTrails.size() - 1; t >= 0; t--) {
            long end = laserEnds.get(t);
            if (end > 0 && now - end > LASER_MS) {
                laserTrails.remove(t);
                laserEnds.remove(t);
                continue;
            }
            float[] p = laserTrails.get(t);
            int alpha = end == 0 ? 255 : (int) (255 * (1 - (now - end) / (float) LASER_MS));
            Path path = new Path();
            for (int i = 0; i + 1 < p.length; i += 2) {
                if (i == 0) path.moveTo(ox + p[i] * scale, oy + p[i + 1] * scale);
                else path.lineTo(ox + p[i] * scale, oy + p[i + 1] * scale);
            }
            laserPaint.setAlpha(alpha);
            laserPaint.setStrokeWidth(5 * density);
            c.drawPath(path, laserPaint);
            any = true;
        }
        if (any) postInvalidateOnAnimation();
    }

    private void drawPull(Canvas c) {
        float over = overscroll();
        if (over <= 0 || pages.isEmpty()) return;
        float top = oy + pageTop(pages.size()) * scale;
        float left = ox, right = ox + Ink.W * scale, bottom = Math.min(getHeight() - margin() / 2, top + Ink.H * scale);
        if (bottom <= top + 8) return;
        float r = 6 * density;
        c.drawRoundRect(new RectF(left, top, right, bottom), r, r, dashed);
        boolean ready = over >= PULL_DP * density;
        label.setFakeBoldText(ready);
        c.drawText(ready ? "Suelta para una página nueva" : "Tira para una página nueva", (left + right) / 2,
                (top + bottom) / 2 + label.getTextSize() / 3, label);
    }

    // ---- Entrada ----

    private boolean draws(MotionEvent e, int idx) {
        int t = e.getToolType(idx);
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
                || t == MotionEvent.TOOL_TYPE_MOUSE
                || ((fingerDraws || tool == LASSO || tool == NOTE) && t == MotionEvent.TOOL_TYPE_FINGER && fingers == 0);
    }

    private static boolean button(MotionEvent e) {
        return (e.getButtonState() & (MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_SECONDARY)) != 0;
    }

    private boolean erases(MotionEvent e, int idx) {
        return (tool == ERASER && eraseMode != ERASE_LASSO) || e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER
                || (button(e) && buttonHold == HOLD_ERASE);
    }

    private long lastButton;

    /** Botón del S Pen sin tocar la pantalla (el lápiz en el aire). */
    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS && e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
                && (e.getActionButton() & (MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_SECONDARY)) != 0) {
            long now = SystemClock.uptimeMillis();
            boolean twice = now - lastButton < 400;
            lastButton = twice ? 0 : now;
            listener.onPenButton(twice);
            return true;
        }
        return super.onGenericMotionEvent(e);
    }

    private float pressure(MotionEvent e, int idx, int h) {
        int t = e.getToolType(idx);
        if (t != MotionEvent.TOOL_TYPE_STYLUS && t != MotionEvent.TOOL_TYPE_ERASER) return 0.6f;
        return h < 0 ? e.getPressure(idx) : e.getHistoricalPressure(idx, h);
    }

    /** Qué parte de la selección hay en ese punto de la pantalla (SelectionMath). */
    private int selectionHit(float sx, float sy) {
        if (selPage < 0) return SelectionMath.NONE;
        float x = (sx - ox) / scale - pageLeft(selPage), y = (sy - oy) / scale - pageTop(selPage), k = density / scale;
        Ink.Stroke line = selectedLine();
        if (line != null) {
            ShapeTools.Spec l = line.shape;
            if (Math.hypot(x - l.x0, y - l.y0) < 26 * k) return SelectionMath.LINE_START;
            if (Math.hypot(x - l.x1, y - l.y1) < 26 * k) return SelectionMath.LINE_END;
            return selBox.contains(x, y) ? SelectionMath.MOVE : SelectionMath.NONE;
        }
        return SelectionMath.hit(selArray(), x, y, 22 * k, 14 * k, stretchesVertically(), rotates(), ROTATE_GAP * k);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (pages.isEmpty()) return false;
        // Los dedos sobre la regla la mueven y la giran; el lápiz sigue escribiendo.
        if (pointerId == -1 && guides.active() && (guides.dragging() || e.getActionMasked() == MotionEvent.ACTION_DOWN) && guides.touch(e)) {
            invalidate();
            return true;
        }
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        watchFingerTap(e, action);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (pointerId == -1) {
                    boolean finger = e.getToolType(idx) == MotionEvent.TOOL_TYPE_FINGER;
                    int hit = fingers == 0 ? selectionHit(e.getX(idx), e.getY(idx)) : 0;
                    if (hit > 0) {
                        startDrag(e, idx, hit);
                        return true;
                    }
                    if (draws(e, idx)) {
                        requestUnbufferedDispatch(e);
                        if (scroller != null) scroller.cancel();
                        if (selPage >= 0 && !finger) clearSelection();
                        if (!layerAligned()) renderLayer();
                        pointerId = e.getPointerId(idx);
                        start(e, idx);
                        return true;
                    }
                } else if (action == MotionEvent.ACTION_POINTER_DOWN && e.getToolType(idx) == MotionEvent.TOOL_TYPE_FINGER) {
                    // Un segundo dedo: lo que hacía el primero se deja y se mueve la hoja.
                    int first = e.findPointerIndex(pointerId);
                    if (first >= 0 && e.getToolType(first) == MotionEvent.TOOL_TYPE_FINGER) {
                        cancel();
                        return gesture(e);
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (pointerId != -1) {
                    int i = e.findPointerIndex(pointerId);
                    if (i >= 0) {
                        for (int h = 0; h < e.getHistorySize(); h++) move(e, i, h);
                        move(e, i, -1);
                    }
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (pointerId == e.getPointerId(idx)) {
                    finish(e, idx);
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                if (pointerId != -1) cancel();
                break;
        }
        if (pointerId != -1) return true;
        return gesture(e);
    }

    // Un toque corto del dedo sobre una imagen la selecciona sin soltar el lápiz.
    private static final long TAP_MS = 280, PEN_NEAR_MS = 700;
    private boolean fingerTap;
    private float tapX, tapY;
    private long tapAt, penSeen;

    /** El lápiz cerca de la pantalla: un toque ahora es la palma, no el dedo. */
    @Override
    public boolean onHoverEvent(MotionEvent e) {
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) penSeen = SystemClock.uptimeMillis();
        return super.onHoverEvent(e);
    }

    private void watchFingerTap(MotionEvent e, int action) {
        long now = SystemClock.uptimeMillis();
        if (e.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) {
            penSeen = now;
            fingerTap = false;
            return;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                fingerTap = tool == PEN && !fingerDraws && now - penSeen > PEN_NEAR_MS && (scroller == null || !scroller.isRunning());
                tapX = e.getX();
                tapY = e.getY();
                tapAt = now;
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(e.getX() - tapX, e.getY() - tapY) > 10 * density) fingerTap = false;
                break;
            case MotionEvent.ACTION_UP:
                if (fingerTap && now - tapAt < TAP_MS && pointerId == -1) tapImage(tapX, tapY);
                fingerTap = false;
                break;
            default:
                fingerTap = false;
        }
    }

    /** Selecciona la imagen de más arriba bajo el dedo; fuera de una imagen, deselecciona. */
    private void tapImage(float sx, float sy) {
        float docX = (sx - ox) / scale, docY = (sy - oy) / scale;
        int p = pageAt(docX, docY);
        float x = docX - pageLeft(p), y = docY - pageTop(p);
        ArrayList<Ink.Item> items = pages.get(p).items;
        for (int i = items.size() - 1; i >= 0; i--) {
            Ink.Item it = items.get(i);
            if (!it.isImage() || x < it.x || x > it.x + it.w || y < it.y || y > it.y + it.h) continue;
            clearSelection();
            currentPage = p;
            selPage = p;
            selItems.add(it);
            updateSelBox();
            listener.onSelection(true);
            invalidate();
            return;
        }
        if (selPage >= 0) clearSelection();
    }

    private void start(MotionEvent e, int idx) {
        float docY = (e.getY(idx) - oy) / scale;
        currentPage = pageAt((e.getX(idx) - ox) / scale, docY);
        if (erases(e, idx)) {
            mode = ERASE;
            erased.clear();
            before.clear();
        } else if (tool == LASSO || (tool == ERASER && eraseMode == ERASE_LASSO)
                || (button(e) && buttonHold == HOLD_LASSO)) {
            mode = LASSO_DRAW;
            lassoErases = tool == ERASER;
            lasso.clear();
        } else if (tool == NOTE) {
            startNote(e, idx);
        } else if (tool == LINK) {
            mode = LINK_DRAW;
            linkN = 0;
        } else if (tool == TEXT) {
            mode = TEXT_TAP;
        } else if (tool == LASER) {
            mode = LASER_DRAW;
            laser = new float[256];
            laserN = 0;
            laserTrails.add(new float[0]);
            laserEnds.add(0L);
        } else {
            mode = STROKE;
            boolean hl = tool == HIGHLIGHTER || (button(e) && buttonHold == HOLD_HIGHLIGHTER);
            // La plumilla de caligrafía deforma las figuras, y un esquema pide trazo parejo.
            boolean even = tool == SHAPE && (Ink.CALLIGRAPHY.equals(penKind) || shapeKind >= ShapeTools.RESISTOR);
            String kind = hl ? Ink.HIGHLIGHTER : tool == TAPE ? Ink.TAPE : even ? Ink.PEN : penKind;
            int color = hl ? highlighterColor : tool == TAPE ? PageRenderer.TAPE_COLOR : penColor;
            float width = hl ? highlighterWidth : tool == TAPE ? 34 : penWidth;
            current = new Ink.Stroke(Ink.newId(), kind, color, width);
            snapped = false;
            circuitFree = tool == SHAPE && !hl && shapeKind == ShapeTools.CIRCUIT_FREE;
            shaping = tool == SHAPE && !hl && !circuitFree;
            snapBase = null;
            absorbed.clear();
            holdX = holdY = Float.NaN;
        }
        move(e, idx, -1);
    }

    private void move(MotionEvent e, int idx, int h) {
        float sx = h < 0 ? e.getX(idx) : e.getHistoricalX(idx, h);
        float sy = h < 0 ? e.getY(idx) : e.getHistoricalY(idx, h);
        float docX = (sx - ox) / scale, docY = (sy - oy) / scale;
        switch (mode) {
            case ERASE: {
                int p = pageAt(docX, docY);
                eraseAt(pages.get(p), docX - pageLeft(p), docY - pageTop(p));
                return;
            }
            case NOTE_MOVE:
            case NOTE_ARROW:
                moveNote(docX - pageLeft(notePage), docY - pageTop(notePage));
                return;
            case LASSO_DRAW:
                lasso.add(docX - pageLeft(currentPage));
                lasso.add(docY - pageTop(currentPage));
                invalidate();
                return;
            case DRAG:
                drag(docX - pageLeft(selPage), docY - pageTop(selPage));
                return;
            case LINK_DRAW:
                addLinkPoint(docX, docY);
                return;
            case LASER_DRAW:
                if (2 * laserN + 2 > laser.length) laser = java.util.Arrays.copyOf(laser, laser.length * 2);
                laser[2 * laserN] = docX;
                laser[2 * laserN + 1] = docY;
                laserN++;
                laserTrails.set(laserTrails.size() - 1, java.util.Arrays.copyOf(laser, 2 * laserN));
                invalidate();
                return;
            case STROKE:
                addPoint(docX - pageLeft(currentPage), docY - pageTop(currentPage), pressure(e, idx, h));
                return;
            default:
        }
    }

    private void finish(MotionEvent e, int idx) {
        pointerId = -1;
        removeCallbacks(holdCheck);
        int m = mode;
        mode = NONE;
        switch (m) {
            case ERASE:
                finishErase();
                break;
            case LASSO_DRAW:
                finishLasso();
                break;
            case NOTE_MOVE:
            case NOTE_ARROW:
            case NOTE_NEW:
                finishNote(m);
                break;
            case DRAG:
                finishDrag();
                break;
            case LINK_DRAW:
                finishLink();
                break;
            case LASER_DRAW: {
                laserEnds.set(laserEnds.size() - 1, SystemClock.uptimeMillis());
                // En vivo al PC (en coordenadas de página), por si se presenta.
                int p = currentPage;
                StringBuilder pts = new StringBuilder();
                for (int i = 0; i < 2 * laserN; i++) {
                    if (i > 0) pts.append(',');
                    pts.append(Math.round(i % 2 == 0 ? laser[i] - pageLeft(p) : laser[i] - pageTop(p)));
                }
                listener.onOp("{\"t\":\"laser\",\"page\":\"" + pages.get(p).id + "\",\"points\":[" + pts + "]}");
                invalidate();
                break;
            }
            case TEXT_TAP: {
                float docX = (e.getX(idx) - ox) / scale, docY = (e.getY(idx) - oy) / scale;
                int page = pageAt(docX, docY);
                float x = docX - pageLeft(page), y = docY - pageTop(page);
                Ink.Item hit = null;
                for (Ink.Item it : pages.get(page).items) {
                    if (!it.isImage() && x >= it.x && x <= it.x + it.w && y >= it.y && y <= it.y + renderer.itemHeight(it) + 10) hit = it;
                }
                if (hit != null && hit.isTable()) {
                    listener.onTableRequest(page, hit);
                    break;
                }
                listener.onTextRequest(page, hit, Math.max(20, Math.min(Ink.W - 220, x)), Math.max(20, Math.min(Ink.H - 60, y)));
                break;
            }
            case STROKE:
                finishStroke();
                break;
            default:
        }
    }

    private void cancel() {
        pointerId = -1;
        removeCallbacks(holdCheck);
        if (mode == ERASE) finishErase();
        mode = NONE;
        dragSpec = null;
        current = null;
        lasso.clear();
        renderLayer();
    }

    /** Dedos: uno desplaza (con inercia), dos desplazan y hacen zoom; un toque destapa una cinta. */
    private boolean gesture(MotionEvent e) {
        scaler.onTouchEvent(e);
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (scroller != null) scroller.cancel();
            if (velocity != null) velocity.recycle();
            velocity = VelocityTracker.obtain();
            pulled = false;
            atEnd = oy <= minOy() + 2;
            fingerDownAt = SystemClock.uptimeMillis();
            fingerDownX = e.getX();
            fingerDownY = e.getY();
        }
        if (velocity != null) velocity.addMovement(e);
        if (action == MotionEvent.ACTION_UP && SystemClock.uptimeMillis() - fingerDownAt < 250
                && Math.hypot(e.getX() - fingerDownX, e.getY() - fingerDownY) < 12 * density) {
            tap(e.getX(), e.getY());
        }
        int skip = action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP ? e.getActionIndex() : -1;
        float sx = 0, sy = 0;
        int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip || e.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) continue;
            sx += e.getX(i);
            sy += e.getY(i);
            n++;
        }
        if (n != fingers) {
            focusX = Float.NaN;
            fingers = n;
        }
        if (n == 0) {
            release();
            return true;
        }
        float fx = sx / n, fy = sy / n;
        if (!Float.isNaN(focusX) && action == MotionEvent.ACTION_MOVE) {
            float dy = fy - focusY;
            if (dy < 0 && overscroll() > 0) dy *= 0.45f;
            ox += fx - focusX;
            oy += dy;
            clampView(atEnd);
            boolean ready = overscroll() >= PULL_DP * density;
            if (ready && !pulled) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                readySince = SystemClock.uptimeMillis();
            }
            pulled = ready;
            if (layerScale == scale) afterScroll();
            else invalidate();
        }
        focusX = fx;
        focusY = fy;
        return true;
    }

    /** Toque con el dedo: destapa o tapa una cinta; fuera de la selección, la quita. */
    private void tap(float sx, float sy) {
        float docX = (sx - ox) / scale, docY = (sy - oy) / scale;
        int p = pageAt(docX, docY);
        float x = docX - pageLeft(p), y = docY - pageTop(p);
        List<Ink.Stroke> strokes = pages.get(p).strokes;
        for (int i = strokes.size() - 1; i >= 0; i--) {
            Ink.Stroke s = strokes.get(i);
            if (Ink.TAPE.equals(s.tool) && s.hits(x, y, 4)) {
                if (!revealed.remove(s.id)) revealed.add(s.id);
                renderLayer();
                return;
            }
        }
        if (selPage >= 0 && selectionHit(sx, sy) == 0) clearSelection();
    }

    private void release() {
        float over = overscroll();
        boolean held = pulled && SystemClock.uptimeMillis() - readySince >= PULL_HOLD_MS;
        pulled = false;
        if (held && over >= PULL_DP * density) {
            listener.onPullAddPage();
            return;
        }
        if (over > 0) {
            animateOy(minOy(), 260);
            return;
        }
        float vy = 0;
        if (velocity != null) {
            velocity.computeCurrentVelocity(1000);
            vy = velocity.getYVelocity();
            velocity.recycle();
            velocity = null;
        }
        if (Math.abs(vy) > 600 && layerScale == scale) {
            animateOy(Math.max(minOy(), Math.min(maxOy(), oy + vy * 0.35f)), 520);
        } else if (!layerAligned()) {
            renderLayer();
        }
    }

    // ---- Trazos ----

    private void addPoint(float docX, float y, float pressure) {
        Ink.Stroke s = current;
        if (s == null) return;
        if (guides.active() && !shaping && !snapped) {
            // A pantalla, se pega al borde de la guía, y de vuelta a la hoja.
            float top = pageTop(currentPage), left = pageLeft(currentPage);
            float[] sp = {(docX + left) * scale + ox, (y + top) * scale + oy};
            if (s.n == 0) guides.begin(sp[0], sp[1]);
            if (guides.snapping()) {
                guides.snap(sp);
                docX = (sp[0] - ox) / scale - left;
                y = (sp[1] - oy) / scale - top;
                removeCallbacks(holdCheck);
            }
        }
        float x = Math.max(0, Math.min(Ink.W, docX));
        y = Math.max(0, Math.min(Ink.H, y));
        if (snapped) {
            adjustSnapped(s, x, y);
            return;
        }
        if (shaping) {
            if (s.n == 0) {
                shapeX = x;
                shapeY = y;
                s.add(x, y, SHAPE_PRESSURE);
            } else {
                ShapeTools.Spec spec = ShapeTools.spec(shapeKind, shapeX, shapeY, x, y, gridOf(pages.get(currentPage)));
                s.replace(spec.points(), SHAPE_PRESSURE);
                s.shape = spec;
            }
            invalidate();
            return;
        }
        if (Ink.TAPE.equals(s.tool)) {
            // La cinta va recta del principio al punto actual.
            if (s.n == 0) s.add(x, y, 1);
            else s.replace(new float[] {s.x(0), s.y(0), x, y}, 1);
            invalidate();
            return;
        }
        if (guides.snapping()) {
            // Pegado a una guía: ni figuras ni esperas.
        } else if (Float.isNaN(holdX) || Math.hypot(x - holdX, y - holdY) > 4) {
            holdX = x;
            holdY = y;
            removeCallbacks(holdCheck);
            postDelayed(holdCheck, HOLD_MS);
        }
        if (s.n > 0) {
            float dx = x - s.x(s.n - 1), dy = y - s.y(s.n - 1);
            if (dx * dx + dy * dy < 0.25f) return;
        }
        s.add(x, y, pressure);
        if (Ink.HIGHLIGHTER.equals(s.tool) || Ink.patterned(s.tool)) {
            invalidate();
        } else if (GPU) {
            invalidate();
        } else if (s.n > 1 && layerAligned()) {
            onLayerPage(currentPage);
            renderer.drawSegment(layerCanvas, s, s.n - 1);
            layerCanvas.restore();
            invalidate();
        }
    }

    private void snapIfHeld() {
        if (current == null || mode != STROKE || snapped || shaping || circuitFree || Ink.TAPE.equals(current.tool)) return;
        if (toShape(current)) {
            snapped = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            prepareAdjust(current);
            renderLayer();
            invalidate();
        }
    }

    /** Lo que cuenta para ajustar la figura mientras el lápiz sigue apoyado. */
    private void prepareAdjust(Ink.Stroke s) {
        snapBase = null;
        snapSpec = s.shape;
        if (!absorbed.isEmpty() || s.n < 2) return;
        float[] xy = new float[2 * s.n];
        float cx = 0, cy = 0;
        for (int i = 0; i < s.n; i++) {
            xy[2 * i] = s.x(i);
            xy[2 * i + 1] = s.y(i);
            cx += s.x(i) / s.n;
            cy += s.y(i) / s.n;
        }
        snapBase = xy;
        snapPressure = s.p(0);
        snapCx = cx;
        snapCy = cy;
        snapReach = (float) Math.max(1, Math.hypot(holdX - cx, holdY - cy));
    }

    /** Una línea sigue al lápiz; lo demás crece o encoge desde su centro. */
    private void adjustSnapped(Ink.Stroke s, float x, float y) {
        if (snapBase == null) return;
        if (ShapeRecognizer.LINE.equals(snapKind)) {
            ShapeRecognizer.Shape line = ShapeRecognizer.line(snapBase[0], snapBase[1], x, y);
            s.replace(line.xy, snapPressure);
            s.shape = line.spec;
        } else {
            float k = (float) Math.hypot(x - snapCx, y - snapCy) / snapReach;
            k = Math.max(0.2f, Math.min(5, k));
            float[] xy = new float[snapBase.length];
            for (int i = 0; i < xy.length; i += 2) {
                xy[i] = snapCx + (snapBase[i] - snapCx) * k;
                xy[i + 1] = snapCy + (snapBase[i + 1] - snapCy) * k;
            }
            s.replace(xy, snapPressure);
            float[] grow = {k, 0, 0, k, snapCx - snapCx * k, snapCy - snapCy * k};
            s.shape = snapSpec == null ? null : snapSpec.mapped(grow, 0);
        }
        invalidate();
    }

    /** Separación a la que ajustar las figuras en esta hoja; 0 si no tiene cuadrícula. */
    private static float gridOf(Ink.Page page) {
        return Ink.GRID.equals(page.background) || Ink.DOTS.equals(page.background) ? ShapeTools.GRID : 0;
    }

    private boolean toShape(Ink.Stroke s) {
        if (s.n < 3) return false;
        float[] xy = new float[2 * s.n];
        float pressure = 0;
        for (int i = 0; i < s.n; i++) {
            xy[2 * i] = s.x(i);
            xy[2 * i + 1] = s.y(i);
            pressure += s.p(i);
        }
        absorbed.clear();
        ShapeRecognizer.Shape shape = ShapeRecognizer.recognize(xy, s.n);
        Ink.Page page = pages.get(currentPage);
        dropStaleLines(page);
        if (shape == null) {
            shape = headOfLastLine(xy, s.n);
        } else if (ShapeRecognizer.LINE.equals(shape.kind)) {
            shape = attachLine(shape);
            ShapeRecognizer.Shape closed = closeWithRecentLines(shape);
            if (closed != null) shape = closed;
        }
        if (shape == null) return false;
        s.replace(shape.xy, Math.max(0.5f, pressure / s.n));
        s.shape = shape.spec;
        snapKind = shape.kind;
        return true;
    }

    // ---- Figuras de varios trazos ----

    /** Una línea reconocida y dónde empieza y acaba. */
    private static final class RecentLine {
        final Ink.Stroke stroke;
        final long time;

        RecentLine(Ink.Stroke s, long time) {
            stroke = s;
            this.time = time;
        }

        float[] ends() {
            return new float[] {stroke.x(0), stroke.y(0), stroke.x(stroke.n - 1), stroke.y(stroke.n - 1)};
        }
    }

    /** Sólo valen las líneas recientes, de esta hoja y que sigan tal cual. */
    private void dropStaleLines(Ink.Page page) {
        long now = android.os.SystemClock.uptimeMillis();
        recentLines.removeIf(l -> now - l.time > LINE_MEMORY_MS || !page.strokes.contains(l.stroke));
    }

    /** Pega las puntas de la línea nueva a las de las líneas recientes si casi se tocan. */
    private ShapeRecognizer.Shape attachLine(ShapeRecognizer.Shape line) {
        float[] xy = line.xy;
        float x0 = xy[0], y0 = xy[1], x1 = xy[xy.length - 2], y1 = xy[xy.length - 1];
        boolean moved = false;
        for (RecentLine l : recentLines) {
            float[] e = l.ends();
            for (int i = 0; i < 4; i += 2) {
                if (Math.hypot(x0 - e[i], y0 - e[i + 1]) < ATTACH) { x0 = e[i]; y0 = e[i + 1]; moved = true; }
                if (Math.hypot(x1 - e[i], y1 - e[i + 1]) < ATTACH) { x1 = e[i]; y1 = e[i + 1]; moved = true; }
            }
        }
        if (!moved) return line;
        return new ShapeRecognizer.Shape(line.kind, ShapeTools.densify(new float[] {x0, y0, x1, y1}),
                new ShapeTools.Spec(ShapeTools.LINE, x0, y0, x1, y1, 0));
    }

    /** ¿Esta línea cierra un triángulo o un cuadrilátero con las anteriores? */
    private ShapeRecognizer.Shape closeWithRecentLines(ShapeRecognizer.Shape line) {
        ArrayList<RecentLine> all = new ArrayList<>(recentLines);
        float[] mine = {line.xy[0], line.xy[1], line.xy[line.xy.length - 2], line.xy[line.xy.length - 1]};
        for (int count = 3; count >= 2; count--) {
            if (all.size() < count) continue;
            List<RecentLine> last = all.subList(all.size() - count, all.size());
            float[][] lines = new float[count + 1][];
            for (int i = 0; i < count; i++) lines[i] = last.get(i).ends();
            lines[count] = mine;
            ShapeRecognizer.Shape closed = ShapeRecognizer.closeLines(lines);
            if (closed == null) continue;
            for (RecentLine l : last) absorbed.add(l.stroke);
            return closed;
        }
        return null;
    }

    /** ¿Este trazo es la punta de flecha de la última línea? */
    private ShapeRecognizer.Shape headOfLastLine(float[] xy, int n) {
        RecentLine last = recentLines.peekLast();
        if (last == null) return null;
        float[] e = last.ends();
        ShapeRecognizer.Shape arrow = ShapeRecognizer.arrowHead(e[0], e[1], e[2], e[3], xy, n);
        if (arrow != null) absorbed.add(last.stroke);
        return arrow;
    }

    private void finishStroke() {
        Ink.Stroke s = current;
        current = null;
        guides.end();
        boolean dragged = shaping;
        boolean free = circuitFree;
        shaping = false;
        circuitFree = false;
        if (s != null && (dragged || free) && (s.maxX - s.minX) + (s.maxY - s.minY) < 10 && tapComponent(s)) {
            renderLayer();
            return;
        }
        if (free && s != null) {
            drawnComponent(s);
            snapped = false;
        }
        // Una letra no es una figura: la conversión automática pide cierto tamaño.
        boolean bigEnough = s != null && ((s.maxX - s.minX) + (s.maxY - s.minY) >= MIN_AUTO_SHAPE
                || Math.max(s.maxX - s.minX, s.maxY - s.minY) >= MIN_AUTO_LINE);
        if (s != null && !dragged && !free && !snapped && autoShapes && bigEnough && !Ink.TAPE.equals(s.tool) && toShape(s)) {
            snapped = true;
            renderLayer();
        }
        boolean tiny = dragged && s != null && (s.maxX - s.minX) + (s.maxY - s.minY) < 6;
        if (s == null || s.n == 0 || tiny || (Ink.TAPE.equals(s.tool) && s.n < 2)) {
            renderLayer();
            return;
        }
        Ink.Page page = pages.get(currentPage);
        if (dragged) hookEnds(page, s);
        if (snapped && !absorbed.isEmpty()) {
            // Sustituye a los trazos sueltos: deshacer los devuelve.
            String old = page.toJson();
            page.strokes.removeAll(absorbed);
            absorbed.clear();
            recentLines.clear();
            page.strokes.add(s);
            commitSnapshot(page, old);
            return;
        }
        if (snapped && ShapeRecognizer.LINE.equals(snapKind)) {
            recentLines.addLast(new RecentLine(s, android.os.SystemClock.uptimeMillis()));
            while (recentLines.size() > 3) recentLines.removeFirst();
        }
        page.strokes.add(s);
        if (GPU) {
            strokeAdded(page);
        } else {
            onLayerPage(currentPage);
            renderer.drawStroke(layerCanvas, s, false);
            layerCanvas.restore();
        }
        invalidate();
        sendStroke(page, s);
        LinkedHashMap<Ink.Page, ArrayList<Ink.Stroke>> one = new LinkedHashMap<>();
        ArrayList<Ink.Stroke> list = new ArrayList<>();
        list.add(s);
        one.put(page, list);
        push(undo, new Object[] {"add", one});
        redo.clear();
        listener.onEdited(page);
        notifyUndo();
    }

    // ---- Goma ----

    private void eraseAt(Ink.Page page, float x, float y) {
        boolean partial = eraseMode == ERASE_PARTIAL && tool == ERASER;
        float r = partial ? 8 : 10;
        boolean any = false;
        for (int i = page.strokes.size() - 1; i >= 0; i--) {
            Ink.Stroke s = page.strokes.get(i);
            if (!s.hits(x, y, r)) continue;
            // Goma «sólo resaltador»: lo escrito con la pluma no se toca.
            if (eraseMode == ERASE_HIGHLIGHTER && tool == ERASER && !Ink.HIGHLIGHTER.equals(s.tool)) continue;
            any = true;
            if (partial) {
                if (!before.containsKey(page)) before.put(page, page.toJson());
                page.strokes.remove(i);
                page.strokes.addAll(i, split(s, x, y, r + s.width / 2));
            } else {
                page.strokes.remove(i);
                ArrayList<Ink.Stroke> list = erased.get(page);
                if (list == null) erased.put(page, list = new ArrayList<>());
                list.add(s);
            }
        }
        if (any) renderLayer();
    }

    /** Los trozos del trazo que quedan fuera del círculo de la goma. */
    private static List<Ink.Stroke> split(Ink.Stroke s, float x, float y, float r) {
        ArrayList<Ink.Stroke> out = new ArrayList<>();
        Ink.Stroke piece = null;
        for (int i = 0; i < s.n; i++) {
            if (Math.hypot(s.x(i) - x, s.y(i) - y) <= r) {
                if (piece != null && piece.n >= 2) out.add(piece);
                piece = null;
            } else {
                if (piece == null) piece = new Ink.Stroke(Ink.newId(), s.tool, s.color, s.width);
                piece.add(s.x(i), s.y(i), s.p(i));
            }
        }
        if (piece != null && piece.n >= 2) out.add(piece);
        return out;
    }

    private void finishErase() {
        if (!before.isEmpty()) {
            LinkedHashMap<Ink.Page, String[]> m = new LinkedHashMap<>();
            for (Map.Entry<Ink.Page, String> e : before.entrySet()) {
                m.put(e.getKey(), new String[] {e.getValue(), e.getKey().toJson()});
                sendPage(e.getKey());
            }
            before.clear();
            push(undo, new Object[] {"snap", m});
            redo.clear();
        }
        if (!erased.isEmpty()) {
            LinkedHashMap<Ink.Page, ArrayList<Ink.Stroke>> done = new LinkedHashMap<>(erased);
            erased.clear();
            for (Map.Entry<Ink.Page, ArrayList<Ink.Stroke>> e : done.entrySet()) {
                sendErase(e.getKey(), e.getValue());
                listener.onEdited(e.getKey());
            }
            push(undo, new Object[] {"erase", done});
            redo.clear();
        }
        notifyUndo();
    }

    // ---- Lazo y selección ----

    private void finishLasso() {
        if (lasso.size() < 2) return;
        Ink.Page page = pages.get(currentPage);
        clearSelection();
        float[] path = new float[lasso.size()];
        for (int i = 0; i < path.length; i++) path[i] = lasso.get(i);
        ArrayList<float[]> shapes = new ArrayList<>();
        for (Ink.Stroke s : page.strokes) {
            float[] xy = new float[2 * s.n];
            for (int i = 0; i < s.n; i++) {
                xy[2 * i] = s.x(i);
                xy[2 * i + 1] = s.y(i);
            }
            shapes.add(xy);
        }
        float pathX = path[0], pathY = path[1];
        boolean tapped = true;
        for (int i = 0; i + 1 < path.length; i += 2) tapped &= Math.hypot(path[i] - pathX, path[i + 1] - pathY) < 12;
        // Un elemento cuenta como una rejilla de puntos, salvo al tocar: ahí se mira si cae encima.
        if (!tapped) for (Ink.Item it : page.items) shapes.add(itemGrid(it));
        boolean[] chosen = LassoPicker.pick(path, shapes);
        for (int i = 0; i < page.strokes.size(); i++) if (chosen[i]) selStrokes.add(page.strokes.get(i));
        for (int i = 0; i < page.items.size(); i++) {
            Ink.Item it = page.items.get(i);
            boolean hit = tapped
                    ? selStrokes.isEmpty() && pathX >= it.x && pathX <= it.x + it.w && pathY >= it.y && pathY <= it.y + renderer.itemHeight(it)
                    : chosen[page.strokes.size() + i];
            if (hit) selItems.add(it);
        }
        lasso.clear();
        if (selStrokes.isEmpty() && selItems.isEmpty()) {
            invalidate();
            return;
        }
        selPage = currentPage;
        updateSelBox();
        if (lassoErases) {
            // Goma de lazo: fuera lo rodeado, sin pasar por la selección.
            deleteSelection();
            return;
        }
        listener.onSelection(true);
        invalidate();
    }

    private float[] itemGrid(Ink.Item it) {
        float h = renderer.itemHeight(it);
        float[] xy = new float[18];
        for (int i = 0; i < 9; i++) {
            xy[2 * i] = it.x + it.w * (i % 3) / 2;
            xy[2 * i + 1] = it.y + h * (i / 3) / 2;
        }
        return xy;
    }

    private void updateSelBox() {
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        float tl = l, tt = t, tr = r, tb = b;
        for (Ink.Stroke s : selStrokes) {
            l = Math.min(l, s.minX - s.width); t = Math.min(t, s.minY - s.width);
            r = Math.max(r, s.maxX + s.width); b = Math.max(b, s.maxY + s.width);
            tl = Math.min(tl, s.minX); tt = Math.min(tt, s.minY);
            tr = Math.max(tr, s.maxX); tb = Math.max(tb, s.maxY);
        }
        for (Ink.Item it : selItems) {
            float h = renderer.itemHeight(it);
            l = Math.min(l, it.x); t = Math.min(t, it.y);
            r = Math.max(r, it.x + it.w); b = Math.max(b, it.y + h);
            tl = Math.min(tl, it.x); tt = Math.min(tt, it.y);
            tr = Math.max(tr, it.x + it.w); tb = Math.max(tb, it.y + h);
        }
        selBox.set(l - 6, t - 6, r + 6, b + 6);
        selTight.set(tl, tt, tr, tb);
        // Una figura sola se cuenta por sus puntos: las barbas de unos ejes sobresalen.
        ShapeTools.Spec one = selStrokes.size() == 1 && selItems.isEmpty() ? selStrokes.get(0).shape : null;
        if (one != null && one.angle == 0 && one.kind != ShapeTools.CROSS_AXES) {
            selTight.set(Math.min(one.x0, one.x1), Math.min(one.y0, one.y1), Math.max(one.x0, one.x1), Math.max(one.y0, one.y1));
        }
    }

    /** La imagen seleccionada, si es lo único seleccionado. */
    Ink.Item selectedImage() {
        return selStrokes.isEmpty() && selItems.size() == 1 && selItems.get(0).isImage() ? selItems.get(0) : null;
    }

    /** Cambia la imagen seleccionada por otra, con su forma. Se puede deshacer. */
    void replaceSelectedImage(String asset, int width, int height) {
        Ink.Item it = selectedImage();
        if (it == null || selPage < 0 || width <= 0 || height <= 0) return;
        Ink.Page page = pages.get(selPage);
        String old = page.toJson();
        it.asset = asset;
        it.h = it.w * height / width;
        updateSelBox();
        commitSnapshot(page, old);
    }

    boolean hasSelection() {
        return selPage >= 0;
    }

    void clearSelection() {
        boolean had = selPage >= 0;
        selPage = -1;
        selStrokes.clear();
        selItems.clear();
        if (had && listener != null) listener.onSelection(false);
        invalidate();
    }

    private void startDrag(MotionEvent e, int idx, int hit) {
        pointerId = e.getPointerId(idx);
        mode = DRAG;
        dragHandle = hit;
        dragX = (e.getX(idx) - ox) / scale - pageLeft(selPage);
        dragY = (e.getY(idx) - oy) / scale - pageTop(selPage);
        dragBox = selArray();
        dragAngle = 0;
        boolean end = hit == SelectionMath.LINE_START || hit == SelectionMath.LINE_END;
        dragSpec = end ? selStrokes.get(0).shape : null;
        renderLayer();
    }

    /**
     * La transformación del arrastre. Al estirar en un sentido se cuenta desde el
     * dibujo y no desde la caja, que tiene margen: si no, el lado que debe quedar
     * quieto se correría.
     */
    private float[] dragAffine() {
        float[] box = selArray();
        boolean flat = selTight.width() < 1 || selTight.height() < 1;
        if (dragAngle != 0 || flat || SelectionMath.uniform(box, dragBox)) return SelectionMath.affine(box, dragBox, dragAngle);
        float[] from = {selTight.left, selTight.top, selTight.right, selTight.bottom};
        float[] to = {dragBox[0] + from[0] - box[0], dragBox[1] + from[1] - box[1], dragBox[2] + from[2] - box[2], dragBox[3] + from[3] - box[3]};
        if (to[2] - to[0] < 1 || to[3] - to[1] < 1) return SelectionMath.affine(box, dragBox, 0);
        return SelectionMath.affine(from, to, 0);
    }

    private void drag(float x, float y) {
        if (dragSpec != null) {
            dragSpec = lineWithEnd(dragSpec, dragHandle == SelectionMath.LINE_START, x, y);
        } else if (dragHandle == SelectionMath.ROTATE) {
            double a = Math.atan2(y - selBox.centerY(), x - selBox.centerX()) + Math.PI / 2;
            dragAngle = SelectionMath.snapAngle(Math.atan2(Math.sin(a), Math.cos(a)));
        } else {
            dragBox = SelectionMath.resize(dragHandle, selArray(), x - dragX, y - dragY);
        }
        invalidate();
    }

    private void finishDrag() {
        Ink.Page page = pages.get(selPage);
        if (dragSpec != null) {
            ShapeTools.Spec spec = dragSpec;
            dragSpec = null;
            Ink.Stroke s = selStrokes.get(0);
            if (spec != s.shape) {
                Ink.Stroke t = s.withShape(spec);
                Ink.Item label = labelOf(page, s);
                String before = page.toJson();
                if (label != null) placeLabel(label, spec);
                int at = page.strokes.indexOf(s);
                if (at >= 0) page.strokes.set(at, t);
                selStrokes.set(0, t);
                updateSelBox();
                commitSnapshot(page, before);
            } else {
                renderLayer();
            }
            return;
        }
        float[] box = selArray();
        if (dragAngle == 0 && java.util.Arrays.equals(box, dragBox)) {
            renderLayer();
            return;
        }
        String old = page.toJson();
        float[] m = dragAffine();
        boolean even = SelectionMath.uniform(box, dragBox);
        // Al estirar en un sentido, la línea conserva su grosor.
        float widthScale = even ? (dragBox[2] - dragBox[0]) / (box[2] - box[0]) : 1;
        for (int i = 0; i < selStrokes.size(); i++) {
            Ink.Stroke s = selStrokes.get(i), t = s.mapped(m, widthScale, dragAngle);
            int at = page.strokes.indexOf(s);
            if (at >= 0) page.strokes.set(at, t);
            selStrokes.set(i, t);
        }
        for (Ink.Item it : selItems) mapItem(it, m, even);
        moveLabels(page);
        updateSelBox();
        commitSnapshot(page, old);
    }

    /** El texto seleccionado, si es lo único seleccionado. */
    Ink.Item selectedText() {
        if (!selStrokes.isEmpty() || selItems.size() != 1) return null;
        Ink.Item it = selItems.get(0);
        return it.isImage() || it.isTable() ? null : it;
    }

    /** Abre el texto seleccionado para escribir en él. */
    void editSelectedText() {
        Ink.Item it = selectedText();
        if (it == null) return;
        int page = selPage;
        clearSelection();
        listener.onTextRequest(page, it, it.x, it.y);
    }

    /** Recolorea lo seleccionado (trazos y textos). */
    void recolorSelection(int color) {
        if (selPage < 0) return;
        Ink.Page page = pages.get(selPage);
        String old = page.toJson();
        for (int i = 0; i < selStrokes.size(); i++) {
            Ink.Stroke s = selStrokes.get(i);
            if (Ink.TAPE.equals(s.tool)) continue;
            Ink.Stroke t = s.copy(s.id, color);
            int at = page.strokes.indexOf(s);
            if (at >= 0) page.strokes.set(at, t);
            selStrokes.set(i, t);
        }
        for (Ink.Item it : selItems) if (!it.isImage()) it.color = color;
        commitSnapshot(page, old);
    }

    void duplicateSelection() {
        if (selPage < 0) return;
        Ink.Page page = pages.get(selPage);
        String old = page.toJson();
        ArrayList<Ink.Stroke> strokes = new ArrayList<>();
        ArrayList<Ink.Item> items = new ArrayList<>();
        for (Ink.Stroke s : selStrokes) strokes.add(s.copy(Ink.newId(), s.color).transformed(0, 0, 1, 30, 30));
        for (Ink.Item it : selItems) {
            Ink.Item c = it.copy(Ink.newId());
            c.x += 30;
            c.y += 30;
            items.add(c);
        }
        page.strokes.addAll(strokes);
        page.items.addAll(items);
        selStrokes.clear();
        selStrokes.addAll(strokes);
        selItems.clear();
        selItems.addAll(items);
        updateSelBox();
        commitSnapshot(page, old);
    }

    void deleteSelection() {
        if (selPage < 0) return;
        Ink.Page page = pages.get(selPage);
        String old = page.toJson();
        for (Ink.Stroke s : selStrokes) {
            Ink.Item label = labelOf(page, s);
            if (label != null) page.items.remove(label);
        }
        page.strokes.removeAll(selStrokes);
        page.items.removeAll(selItems);
        clearSelection();
        commitSnapshot(page, old);
    }

    // ---- PC ----

    /** ¿Está abierta esta página? */
    boolean hasPage(String id) {
        for (Ink.Page p : pages) if (p.id.equals(id)) return true;
        return false;
    }

    /** Un trazo hecho en el PC: entra como uno propio (se deshace igual y se manda con versión nueva). */
    void addRemoteStroke(String pageId, Ink.Stroke s) {
        Ink.Page page = null;
        for (Ink.Page p : pages) if (p.id.equals(pageId)) page = p;
        if (page == null) return;
        for (Ink.Stroke o : page.strokes) if (o.id.equals(s.id)) return;
        page.strokes.add(s);
        if (GPU) strokeAdded(page);
        else renderLayer();
        invalidate();
        sendStroke(page, s);
        LinkedHashMap<Ink.Page, ArrayList<Ink.Stroke>> one = new LinkedHashMap<>();
        ArrayList<Ink.Stroke> list = new ArrayList<>();
        list.add(s);
        one.put(page, list);
        push(undo, new Object[] {"add", one});
        redo.clear();
        listener.onEdited(page);
        notifyUndo();
    }

    private void sendStroke(Ink.Page page, Ink.Stroke s) {
        page.version++;
        listener.onOp("{\"t\":\"stroke\",\"page\":\"" + page.id + "\",\"version\":" + page.version
                + ",\"stroke\":" + s.toJson() + "}");
    }

    private void sendErase(Ink.Page page, ArrayList<Ink.Stroke> list) {
        page.version++;
        StringBuilder ids = new StringBuilder();
        for (Ink.Stroke s : list) ids.append(ids.length() > 0 ? "," : "").append('"').append(s.id).append('"');
        listener.onOp("{\"t\":\"erase\",\"page\":\"" + page.id + "\",\"version\":" + page.version
                + ",\"ids\":[" + ids + "]}");
    }

    // ---- Deshacer ----

    private static void push(ArrayDeque<Object[]> stack, Object[] a) {
        stack.push(a);
        while (stack.size() > 200) stack.removeLast();
    }

    void undo() {
        step(undo, redo);
    }

    void redo() {
        step(redo, undo);
    }

    @SuppressWarnings("unchecked")
    private void step(ArrayDeque<Object[]> from, ArrayDeque<Object[]> to) {
        if (pages.isEmpty() || from.isEmpty() || pointerId != -1) return;
        clearSelection();
        Object[] a = from.pop();
        if ("snap".equals(a[0])) {
            LinkedHashMap<Ink.Page, String[]> m = (LinkedHashMap<Ink.Page, String[]>) a[1];
            boolean undoing = from == undo;
            for (Map.Entry<Ink.Page, String[]> e : m.entrySet()) restore(e.getKey(), e.getValue()[undoing ? 0 : 1]);
            push(to, a);
        } else {
            LinkedHashMap<Ink.Page, ArrayList<Ink.Stroke>> byPage = (LinkedHashMap<Ink.Page, ArrayList<Ink.Stroke>>) a[1];
            boolean add = "add".equals(a[0]);
            for (Map.Entry<Ink.Page, ArrayList<Ink.Stroke>> e : byPage.entrySet()) {
                Ink.Page page = e.getKey();
                if (add) {
                    page.strokes.removeAll(e.getValue());
                    sendErase(page, e.getValue());
                } else {
                    for (Ink.Stroke s : e.getValue()) {
                        page.strokes.add(s);
                        sendStroke(page, s);
                    }
                }
                listener.onEdited(page);
            }
            push(to, new Object[] {add ? "erase" : "add", byPage});
        }
        renderLayer();
        notifyUndo();
    }

    private void notifyUndo() {
        if (listener != null) listener.onUndoState(!undo.isEmpty(), !redo.isEmpty());
    }
}
