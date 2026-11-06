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

    static final int PEN = 0, HIGHLIGHTER = 1, ERASER = 2, LASSO = 3, TEXT = 4, LASER = 5, TAPE = 6, SHAPE = 7;

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
    private static final int NONE = 0, STROKE = 1, ERASE = 2, LASSO_DRAW = 3, DRAG = 4, LASER_DRAW = 5, TEXT_TAP = 6;
    private int mode = NONE;
    private int pointerId = -1;
    private int currentPage;
    private Ink.Stroke current;
    private boolean snapped;
    /** Se está arrastrando una figura con la herramienta: de (shapeX, shapeY) al lápiz. */
    private boolean shaping;
    private float shapeX, shapeY;
    /** Figura ya reconocida y con el lápiz aún apoyado: se ajusta hasta soltarlo. */
    private float[] snapBase;
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
    /** Arrastre de la selección: qué tirador, y a qué caja y ángulo va. */
    private int dragHandle;
    private float dragX, dragY, dragAngle;
    private float[] dragBox = new float[4];

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
        scrollToPage(pages.size() - 1, true);
    }

    /** Quita la página i de la vista (ya borrada del cuaderno). */
    void removePage(int i) {
        if (i < 0 || i >= pages.size()) return;
        Ink.Page gone = pages.remove(i);
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
        Ink.Page page = visiblePage();
        if (page == null) return;
        int index = visible;
        String old = page.toJson();
        Ink.Item it = new Ink.Item(Ink.newId(), "image");
        it.asset = asset;
        it.w = Math.min(560, Ink.W - 80);
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

    private static float pageTop(int i) {
        return i * (Ink.H + GAP);
    }

    private float docHeight() {
        return pages.isEmpty() ? Ink.H : pageTop(pages.size()) - GAP;
    }

    /** La página en la altura y del documento (la más cercana). */
    private int pageAt(float docY) {
        int i = (int) Math.floor((docY + GAP / 2) / (Ink.H + GAP));
        return Math.max(0, Math.min(pages.size() - 1, i));
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
            pendingScroll = -1;
        }
        clampView(false);
        pad = h / 2;
        if (layer == null || layer.getWidth() != w || layer.getHeight() != h + 2 * pad) {
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
        float w = getWidth(), pw = Ink.W * scale, m = margin();
        if (pw <= w - 2 * m) ox = (w - pw) / 2;
        else ox = Math.max(w - m - pw, Math.min(m, ox));
        float pullMax = pulling ? PULL_MAX_DP * density : 0;
        oy = Math.max(minOy() - pullMax, Math.min(maxOy(), oy));
        notifyVisible();
    }

    private void notifyVisible() {
        if (pages.isEmpty() || getHeight() == 0) return;
        int v = pageAt((getHeight() / 2f - oy) / scale);
        if (v != visible) {
            visible = v;
            if (listener != null) listener.onPageVisible(v);
        }
    }

    void scrollToPage(int i, boolean animate) {
        if (i < 0 || i >= pages.size()) return;
        float save = oy;
        oy = topInset + margin() - pageTop(i) * scale;
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
        if (layerScale != scale || Math.abs(ox - layerOx) > 1 || Math.abs(oy - layerOy) > pad * 0.7f) renderLayer();
        else invalidate();
    }

    private boolean layerAligned() {
        return layerScale == scale && layerOx == ox && layerOy == oy;
    }

    // ---- Dibujo ----

    private void onLayerPage(int i) {
        layerCanvas.save();
        layerCanvas.translate(layerOx, layerOy + pad);
        layerCanvas.scale(layerScale, layerScale);
        layerCanvas.translate(0, pageTop(i));
    }

    /** Pantalla → página i en el canvas dado. */
    private void onScreenPage(Canvas c, int i) {
        c.save();
        c.translate(ox, oy);
        c.scale(scale, scale);
        c.translate(0, pageTop(i));
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
        if (layer == null) return;
        Canvas c = layerCanvas;
        c.drawColor(DESK);
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
        if (layer == null) return;
        if (layerScale == scale) {
            c.drawBitmap(layer, ox - layerOx, oy - layerOy - pad, null);
        } else {
            c.drawColor(DESK);
            float k = scale / layerScale;
            c.save();
            c.translate(ox - layerOx * k, oy - (layerOy + pad) * k);
            c.scale(k, k);
            c.drawBitmap(layer, 0, 0, null);
            c.restore();
        }
        if (mode == STROKE && current != null && current.n > 0
                && (Ink.HIGHLIGHTER.equals(current.tool) || Ink.TAPE.equals(current.tool) || shaping || snapped)) {
            onScreenPage(c, currentPage);
            renderer.drawStroke(c, current, false);
            c.restore();
        }
        drawLasso(c);
        drawSelection(c);
        drawLasers(c);
        drawPull(c);
        if (onFrame != null && c.isHardwareAccelerated()) onFrame.run();
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
            float[] box = selArray(), m = SelectionMath.affine(box, dragBox, dragAngle);
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
            c.concat(matrix);
            for (Ink.Stroke s : selStrokes) renderer.drawStroke(c, s, revealed.contains(s.id));
            c.restore();
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
                || ((fingerDraws || tool == LASSO) && t == MotionEvent.TOOL_TYPE_FINGER && fingers == 0);
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
        float x = (sx - ox) / scale, y = (sy - oy) / scale - pageTop(selPage), k = density / scale;
        return SelectionMath.hit(selArray(), x, y, 22 * k, 14 * k, stretchesVertically(), rotates(), ROTATE_GAP * k);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (pages.isEmpty()) return false;
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
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

    private void start(MotionEvent e, int idx) {
        float docY = (e.getY(idx) - oy) / scale;
        currentPage = pageAt(docY);
        if (erases(e, idx)) {
            mode = ERASE;
            erased.clear();
            before.clear();
        } else if (tool == LASSO || (tool == ERASER && eraseMode == ERASE_LASSO)
                || (button(e) && buttonHold == HOLD_LASSO)) {
            mode = LASSO_DRAW;
            lassoErases = tool == ERASER;
            lasso.clear();
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
            String kind = hl ? Ink.HIGHLIGHTER : tool == TAPE ? Ink.TAPE : penKind;
            int color = hl ? highlighterColor : tool == TAPE ? PageRenderer.TAPE_COLOR : penColor;
            float width = hl ? highlighterWidth : tool == TAPE ? 34 : penWidth;
            current = new Ink.Stroke(Ink.newId(), kind, color, width);
            snapped = false;
            shaping = tool == SHAPE && !hl;
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
                int p = pageAt(docY);
                eraseAt(pages.get(p), docX, docY - pageTop(p));
                return;
            }
            case LASSO_DRAW:
                lasso.add(docX);
                lasso.add(docY - pageTop(currentPage));
                invalidate();
                return;
            case DRAG:
                drag(docX, docY - pageTop(selPage));
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
                addPoint(docX, docY - pageTop(currentPage), pressure(e, idx, h));
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
            case DRAG:
                finishDrag();
                break;
            case LASER_DRAW: {
                laserEnds.set(laserEnds.size() - 1, SystemClock.uptimeMillis());
                // En vivo al PC (en coordenadas de página), por si se presenta.
                int p = currentPage;
                StringBuilder pts = new StringBuilder();
                for (int i = 0; i < 2 * laserN; i++) {
                    if (i > 0) pts.append(',');
                    pts.append(Math.round(i % 2 == 0 ? laser[i] : laser[i] - pageTop(p)));
                }
                listener.onOp("{\"t\":\"laser\",\"page\":\"" + pages.get(p).id + "\",\"points\":[" + pts + "]}");
                invalidate();
                break;
            }
            case TEXT_TAP: {
                float docX = (e.getX(idx) - ox) / scale, docY = (e.getY(idx) - oy) / scale;
                int page = pageAt(docY);
                float x = docX, y = docY - pageTop(page);
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
        int p = pageAt(docY);
        float x = docX, y = docY - pageTop(p);
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
                s.replace(ShapeTools.build(shapeKind, shapeX, shapeY, x, y, gridOf(pages.get(currentPage))), SHAPE_PRESSURE);
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
        if (Float.isNaN(holdX) || Math.hypot(x - holdX, y - holdY) > 4) {
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
        if (Ink.HIGHLIGHTER.equals(s.tool)) {
            invalidate();
        } else if (s.n > 1 && layerAligned()) {
            onLayerPage(currentPage);
            renderer.drawSegment(layerCanvas, s, s.n - 1);
            layerCanvas.restore();
            invalidate();
        }
    }

    private void snapIfHeld() {
        if (current == null || mode != STROKE || snapped || shaping || Ink.TAPE.equals(current.tool)) return;
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
            s.replace(ShapeRecognizer.line(snapBase[0], snapBase[1], x, y).xy, snapPressure);
        } else {
            float k = (float) Math.hypot(x - snapCx, y - snapCy) / snapReach;
            k = Math.max(0.2f, Math.min(5, k));
            float[] xy = new float[snapBase.length];
            for (int i = 0; i < xy.length; i += 2) {
                xy[i] = snapCx + (snapBase[i] - snapCx) * k;
                xy[i + 1] = snapCy + (snapBase[i + 1] - snapCy) * k;
            }
            s.replace(xy, snapPressure);
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
        return moved ? new ShapeRecognizer.Shape(line.kind, ShapeTools.densify(new float[] {x0, y0, x1, y1})) : line;
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
        boolean dragged = shaping;
        shaping = false;
        // Una letra no es una figura: la conversión automática pide cierto tamaño.
        boolean bigEnough = s != null && ((s.maxX - s.minX) + (s.maxY - s.minY) >= MIN_AUTO_SHAPE
                || Math.max(s.maxX - s.minX, s.maxY - s.minY) >= MIN_AUTO_LINE);
        if (s != null && !dragged && !snapped && autoShapes && bigEnough && !Ink.TAPE.equals(s.tool) && toShape(s)) {
            snapped = true;
            renderLayer();
        }
        boolean tiny = dragged && s != null && (s.maxX - s.minX) + (s.maxY - s.minY) < 6;
        if (s == null || s.n == 0 || tiny || (Ink.TAPE.equals(s.tool) && s.n < 2)) {
            renderLayer();
            return;
        }
        Ink.Page page = pages.get(currentPage);
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
        onLayerPage(currentPage);
        renderer.drawStroke(layerCanvas, s, false);
        layerCanvas.restore();
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
        for (Ink.Stroke s : selStrokes) {
            l = Math.min(l, s.minX - s.width); t = Math.min(t, s.minY - s.width);
            r = Math.max(r, s.maxX + s.width); b = Math.max(b, s.maxY + s.width);
        }
        for (Ink.Item it : selItems) {
            float h = renderer.itemHeight(it);
            l = Math.min(l, it.x); t = Math.min(t, it.y);
            r = Math.max(r, it.x + it.w); b = Math.max(b, it.y + h);
        }
        selBox.set(l - 6, t - 6, r + 6, b + 6);
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
        dragX = (e.getX(idx) - ox) / scale;
        dragY = (e.getY(idx) - oy) / scale - pageTop(selPage);
        dragBox = selArray();
        dragAngle = 0;
        renderLayer();
    }

    private void drag(float x, float y) {
        if (dragHandle == SelectionMath.ROTATE) {
            double a = Math.atan2(y - selBox.centerY(), x - selBox.centerX()) + Math.PI / 2;
            dragAngle = SelectionMath.snapAngle(Math.atan2(Math.sin(a), Math.cos(a)));
        } else {
            dragBox = SelectionMath.resize(dragHandle, selArray(), x - dragX, y - dragY);
        }
        invalidate();
    }

    private void finishDrag() {
        Ink.Page page = pages.get(selPage);
        float[] box = selArray();
        if (dragAngle == 0 && java.util.Arrays.equals(box, dragBox)) {
            renderLayer();
            return;
        }
        String old = page.toJson();
        float[] m = SelectionMath.affine(box, dragBox, dragAngle);
        boolean even = SelectionMath.uniform(box, dragBox);
        // Al estirar en un sentido, la línea conserva su grosor.
        float widthScale = even ? (dragBox[2] - dragBox[0]) / (box[2] - box[0]) : 1;
        for (int i = 0; i < selStrokes.size(); i++) {
            Ink.Stroke s = selStrokes.get(i), t = s.mapped(m, widthScale);
            int at = page.strokes.indexOf(s);
            if (at >= 0) page.strokes.set(at, t);
            selStrokes.set(i, t);
        }
        for (Ink.Item it : selItems) mapItem(it, m, even);
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
        page.strokes.removeAll(selStrokes);
        page.items.removeAll(selItems);
        clearSelection();
        commitSnapshot(page, old);
    }

    // ---- PC ----

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
