package dev.synctablet;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * El cuaderno a pantalla completa, con sus páginas una debajo de otra.
 * Encima flotan, en cristal líquido, la barra de herramientas (arriba), la
 * paleta de colores y grosores (izquierda) y, con algo seleccionado, sus
 * acciones (abajo).
 */
public final class PageActivity extends Activity implements InkView.Listener, SyncClient.Listener {
    private static final int[] SHAPE_KINDS = {ShapeTools.LINE, ShapeTools.ARROW, ShapeTools.DOUBLE_ARROW, ShapeTools.RECT,
            ShapeTools.ELLIPSE, ShapeTools.TRIANGLE, ShapeTools.AXES, ShapeTools.CROSS_AXES, ShapeTools.BRACE, ShapeTools.BRACKET};
    private static final int[] SHAPE_ICONS = {IconView.S_LINE, IconView.S_ARROW, IconView.S_ARROW2, IconView.S_RECT,
            IconView.S_ELLIPSE, IconView.S_TRIANGLE, IconView.S_AXES, IconView.S_CROSS, IconView.S_BRACE, IconView.S_BRACKET};
    private static final String[] SHAPE_NAMES = {"Línea", "Flecha", "Flecha doble", "Rectángulo", "Elipse", "Triángulo",
            "Ejes", "Ejes cruzados", "Llave { } (arrastra desde la punta)", "Corchete [ ] (arrastra desde el lado recto)"};
    private static final int[] CIRCUIT_KINDS = {ShapeTools.CIRCUIT_FREE, ShapeTools.LINE, ShapeTools.RESISTOR, ShapeTools.CAPACITOR, ShapeTools.INDUCTOR,
            ShapeTools.VOLTAGE, ShapeTools.AC, ShapeTools.CURRENT, ShapeTools.DIODE, ShapeTools.SWITCH, ShapeTools.GROUND,
            ShapeTools.NPN, ShapeTools.PNP, ShapeTools.NMOS, ShapeTools.PMOS, ShapeTools.OPAMP,
            ShapeTools.AND, ShapeTools.NAND, ShapeTools.OR, ShapeTools.NOR, ShapeTools.XOR, ShapeTools.XNOR, ShapeTools.NOT, ShapeTools.BUFFER};
    private static final String[] CIRCUIT_NAMES = {"Dibujar a mano", "Cable", "Resistencia", "Condensador", "Bobina", "Fuente de tensión",
            "Fuente alterna", "Fuente de corriente", "Diodo", "Interruptor", "Tierra",
            "Transistor NPN", "Transistor PNP", "MOSFET canal N", "MOSFET canal P", "Amplificador operacional",
            "Compuerta AND", "Compuerta NAND", "Compuerta OR", "Compuerta NOR", "Compuerta XOR", "Compuerta XNOR", "Compuerta NOT", "Búfer"};
    /** Hoja oscura y los colores de la barra que van con ella. Se fijan al crear la pantalla. */
    private static boolean paperDark;
    private static int fg = Ui.ICON, fgMuted = Ui.ICON_MUTED;
    private static int glass = Ui.GLASS_LIGHT;
    private static final String BOX_HINT = "Lados: estirar · esquinas: agrandar · arriba: girar";
    /** Ancho en píxeles de las páginas de un PDF importado. */
    private static final int PDF_WIDTH = 1600;
    private static final int[] PEN_COLORS = {0xFF111827, 0xFF2563EB, 0xFFDC2626, 0xFF16A34A, 0xFF7C3AED, 0xFFEA580C};
    private static final int[] HL_COLORS = {0xFFFFD60A, 0xFF4ADE80, 0xFFF472B6, 0xFF60A5FA};
    private static final float[] PEN_WIDTHS = {2f, 3.5f, 6f};
    private static final float[] HL_WIDTHS = {16f, 24f, 36f};
    private static final String[] PEN_KINDS = {Ink.PEN, Ink.PENCIL, Ink.BRUSH, Ink.CALLIGRAPHY, Ink.MARKER, Ink.FOUNTAIN, Ink.DOTTED, Ink.DASHED};
    private static final String[] PEN_NAMES = {"Pluma", "Lápiz", "Pincel", "Caligrafía", "Marcador", "Estilográfica", "Punteado", "Rayado"};
    static final String[] BACKGROUNDS = {Ink.LINES, Ink.GRID, Ink.DOTS, Ink.HALF, Ink.HALF_GRID, Ink.HALF_H, Ink.CORNELL, Ink.WIDE, Ink.MM,
            Ink.ENGINEERING, Ink.SEMILOG, Ink.LOGLOG, Ink.SMITH, Ink.POLAR, Ink.ISO, Ink.HEX, Ink.HANDWRITING, Ink.WEEK, Ink.STORY, Ink.TODO, Ink.MUSIC, Ink.PLAIN};
    static final String[] BACKGROUND_NAMES = {"Rayas", "Cuadros", "Puntos", "Partida a la mitad", "Cuadros partida", "Mitad arriba y abajo",
            "Cornell (apuntes)", "Rayas anchas", "Milimetrada", "Ingeniería", "Semilogarítmica", "Logarítmica (log-log)", "Carta de Smith", "Polar", "Isométrica", "Hexagonal", "Caligrafía", "Semana",
            "Storyboard", "Lista de tareas", "Pentagrama", "Lisa"};
    private static final int PICK_IMAGE = 1, PICK_PDF = 2, TAKE_PHOTO = 3, PICK_TEXT = 4, PICK_PPTX = 5, EDIT_IMAGE = 6;
    private static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    /** Acciones para el botón del S Pen (clic y doble clic con el lápiz en el aire). */
    /** El número de cada acción se guarda en las preferencias: las nuevas van al final. */
    private static final String[] PEN_ACTIONS = {"Pluma y goma", "Deshacer", "Lazo", "Puntero láser", "Resaltador", "Nada",
            "Rehacer", "Página siguiente", "Página anterior", "Página nueva", "Figuras", "Texto", "Nota", "Presentar",
            "Dibujar con el dedo", "Siguiente color", "Siguiente grosor", "Hoja oscura o clara", "Lápiz neurona"};
    private static final int[] PEN_ICONS = {IconView.ERASER, IconView.UNDO, IconView.LASSO, IconView.LASER, IconView.HIGHLIGHTER, IconView.MORE,
            IconView.REDO, IconView.NEXT, IconView.PREV, IconView.PLUS, IconView.SHAPES, IconView.TEXT, IconView.NOTE, IconView.PLAY,
            IconView.HAND, IconView.DOTS, IconView.SLIDERS, IconView.MOON, IconView.NEURON};
    /** En presentación; PresentActivity.act usa estos mismos números. */
    private static final String[] SLIDE_ACTIONS = {"Siguiente", "Anterior", "Primera", "Salir", "Nada"};
    private static final int[] SLIDE_ICONS = {IconView.NEXT, IconView.PREV, IconView.HOME, IconView.BACK, IconView.MORE};
    private Uri photoUri;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable saver = this::save;
    /** Páginas cambiadas que falta guardar. */
    private final LinkedHashSet<Ink.Page> dirty = new LinkedHashSet<>();
    private Store store;
    private SyncClient sync;
    private SharedPreferences prefs;
    private Ink.Notebook notebook;
    private int index;

    private InkView ink;
    private GlassLayout bar, palette, selectionBar, shapeBar;
    private OptionDock dock;
    private LinearLayout shapeRow, circuitRow;
    private HorizontalScrollView circuitDock;
    private final ComponentButton[] circuitButtons = new ComponentButton[CIRCUIT_KINDS.length];
    private IconView autoShapeButton, editImage, editText, editLine, editFlip, dockMore;
    private TextView selectionHint;
    private ScrollView dockPanel;
    private android.animation.ValueAnimator dockAnimator;
    private boolean dockOpen;
    private float dockProgress;
    private int dockCollapsedWidth;
    private final IconView[] shapeButtons = new IconView[SHAPE_KINDS.length];
    private TextView title;
    /** Etiqueta abajo a la izquierda con el nombre del cuaderno (ya no en la barra). */
    private GlassLayout titleChip;
    // ---- Cómo es la barra (Personalizar la barra) ----
    private String barStyle = BarSettings.CLASSIC, barPos = BarSettings.TOP, colorsMode = BarSettings.C_SIDE;
    private boolean compactBar, darkBar, fold, foldByPen;
    private int barH, iconPx;
    /** Todo lo de la hoja (se encoge a la izquierda al personalizar) y la raíz. */
    private FrameLayout root, stage;
    /** Bordes del sistema (barra de estado, navegación). */
    private int inTop, inLeft, inBottom, inRight;
    /** Pestañas de la barra y de los colores cuando se pliegan. */
    private GlassLayout barTab, colorsTab;
    private IconView barChevron, colorsChevron;
    private View colorsDot;
    private android.animation.ValueAnimator barAnim, palAnim;
    private BarDesigner designer;
    /** Colores y grosores dentro de la barra. */
    private LinearLayout inlinePalette;
    private View paletteSep;
    private boolean barOpen = true, paletteOpen = true;
    private final java.util.LinkedHashMap<String, View> barItems = new java.util.LinkedHashMap<>();
    private long lastBarUse;
    private final Runnable hideBars = this::hideBarsNow;
    private IconView pen, highlighter, eraser, lasso, shapes, text, note, neuron, tape, laser, undo, redo, finger;
    private View dot;
    private LinearLayout colors, widths;
    private int customColor;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = Store.get(this);
        sync = SyncClient.get(this);
        prefs = getSharedPreferences("tools", MODE_PRIVATE);
        notebook = store.notebook(getIntent().getStringExtra("notebook"));
        if (notebook == null) {
            finish();
            return;
        }
        index = saved != null ? saved.getInt("index", notebook.pages.size() - 1) : notebook.pages.size() - 1;
        // Abierta desde una nota: en la página que la nota enseña.
        int asked = notebook.pages.indexOf(getIntent().getStringExtra("page"));
        if (saved == null && asked >= 0) index = asked;

        paperDark = prefs.getBoolean("darkPaper", false);
        loadBarLook();
        ink = new InkView(this);
        if (paperDark) {
            ink.setDarkPaper(true);
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF0B0D10));
            getWindow().setNavigationBarColor(0xFF0B0D10);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                getWindow().getDecorView().post(() -> getWindow().getInsetsController().setSystemBarsAppearance(0,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS));
            }
        }
        ink.setListener(this);
        ink.penColor = prefs.getInt("penColor", PEN_COLORS[0]);
        ink.penWidth = prefs.getFloat("penWidth", PEN_WIDTHS[1]);
        ink.penKind = prefs.getString("penKind", Ink.PEN);
        ink.highlighterColor = prefs.getInt("hlColor", HL_COLORS[0]);
        ink.highlighterWidth = prefs.getFloat("hlWidth", HL_WIDTHS[1]);
        ink.fingerDraws = prefs.getBoolean("finger", false);
        ink.autoShapes = prefs.getBoolean("shapes", false);
        ink.shapeKind = prefs.getInt("shapeKind", ShapeTools.RECT);
        ink.eraseMode = prefs.getInt("eraseMode", InkView.ERASE_STROKES);
        ink.buttonHold = prefs.getInt("buttonHold", InkView.HOLD_ERASE);
        customColor = prefs.getInt("customColor", 0xFF0EA5E9);

        root = new FrameLayout(this);
        stage = new FrameLayout(this);
        root.addView(stage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.addView(ink, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        selectionBar = selectionBar();
        stage.addView(selectionBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, barH));
        selectionBar.setVisibility(View.GONE);
        shapeBar = shapeBar();
        stage.addView(shapeBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shapeBar.setVisibility(View.GONE);
        // El nombre del cuaderno, abajo a la izquierda: en la barra empujaba los botones.
        titleChip = new GlassLayout(this, ink, glass, Ui.dp(this, 15));
        titleChip.setGravity(Gravity.CENTER_VERTICAL);
        title = Ui.text(this, "", 13, fg, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels * 0.4f));
        title.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setContentDescription("Opciones de la página");
        title.setOnClickListener(v -> pageMenu());
        titleChip.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.addView(titleChip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 30)));
        dock = paperDark ? new OptionDock(this, root, ink, 0xF7262A31, true) : new OptionDock(this, root, ink);
        buildBars();
        root.setOnApplyWindowInsetsListener((v, in) -> {
            @SuppressWarnings("deprecation") int top = in.getSystemWindowInsetTop();
            @SuppressWarnings("deprecation") int left = in.getSystemWindowInsetLeft();
            @SuppressWarnings("deprecation") int bottom = in.getSystemWindowInsetBottom();
            @SuppressWarnings("deprecation") int right = in.getSystemWindowInsetRight();
            inTop = top;
            inLeft = left;
            inBottom = bottom;
            inRight = right;
            layoutBars();
            animateBar(barOpen, false);
            animatePalette(paletteOpen, false);
            dock.setInsets(bottom, Build.VERSION.SDK_INT >= 30 ? in.getInsets(android.view.WindowInsets.Type.ime()).bottom : 0);
            return in;
        });
        // Cada fotograma de la hoja se ve a través del cristal.
        ink.onFrame = () -> {
            bar.postInvalidateOnAnimation();
            palette.postInvalidateOnAnimation();
            if (barTab != null) barTab.postInvalidateOnAnimation();
            if (colorsTab != null) colorsTab.postInvalidateOnAnimation();
            if (selectionBar.getVisibility() == View.VISIBLE) selectionBar.postInvalidateOnAnimation();
            if (shapeBar.getVisibility() == View.VISIBLE) shapeBar.postInvalidateOnAnimation();
            titleChip.postInvalidateOnAnimation();
            dock.invalidateGlass();
        };
        setContentView(root);
        // Desde Inicio → Personalizar: se abre ya con el panel.
        if (saved == null && getIntent().getBooleanExtra("design", false)) root.post(this::openDesigner);
        // Se vuelve al dock como se dejó.
        if (ShapeTools.isComponentKind(ink.shapeKind)) {
            shapeRow.setVisibility(View.GONE);
            circuitDock.setVisibility(View.VISIBLE);
        }
        loadPages(index);
        // Después de cargar: cargar páginas vacía el historial.
        ink.setDesk(Looks.bitmap(this, Looks.DESK), Looks.dim(this, Looks.DESK));
        Object history = getLastNonConfigurationInstance();
        if (history != null) ink.restoreHistory(history);
        refreshTools();
        // Abierto desde Inicio con un archivo para convertir en cuaderno.
        Uri imported = getIntent().getData();
        if (saved == null && imported != null) {
            if ("pptx".equals(getIntent().getStringExtra("importKind"))) importDeck(imported);
            else importPdf(imported);
        }
        // Creado desde Inicio para poner una imagen o una foto.
        String start = getIntent().getStringExtra("start");
        if (saved == null && "image".equals(start)) pick("image/*", PICK_IMAGE);
        else if (saved == null && "photo".equals(start)) takePhoto();
        Tutorial.offerOnce(this, dock, "pen");
    }

    private void loadPages(int show) {
        ArrayList<Ink.Page> list = new ArrayList<>();
        for (String id : notebook.pages) {
            Ink.Page p = store.page(id);
            if (p == null) {
                p = new Ink.Page(id, notebook.id);
                store.savePage(p);
            }
            list.add(p);
        }
        index = Math.max(0, Math.min(list.size() - 1, show));
        ink.setRightPages(notebook.right);
        ink.setPages(list, index);
        showTitle();
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return ink.history();
    }

    /** Se ve oscura aquí; lo exportado sigue en blanco y con tinta negra. */
    private void togglePaper() {
        save();
        prefs.edit().putBoolean("darkPaper", !paperDark).apply();
        recreate();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("index", index);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SyncClient.open = ink;
        if (sync != null) sync.addListener(this);
        if (ink != null) ink.redrawPages();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (SyncClient.open == ink) SyncClient.open = null;
        if (sync == null) return;
        sync.removeListener(this);
        save();
    }

    // ---- Barras ----

    private IconView icon(int which, String label, View.OnClickListener click) {
        IconView v = new IconView(this, which, fg, label);
        if (darkBar) v.activeColors(0xFFE5E7EB, 0xFF111827);
        v.setOnClickListener(click);
        return v;
    }

    private View sep(boolean vertical) {
        View v = new View(this);
        v.setBackgroundColor(fg & 0xFFFFFF | 0x26000000);
        LinearLayout.LayoutParams lp = vertical
                ? new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 1))
                : new LinearLayout.LayoutParams(Ui.dp(this, 1), Ui.dp(this, 26));
        if (vertical) lp.topMargin = lp.bottomMargin = Ui.dp(this, 6);
        else lp.leftMargin = lp.rightMargin = Ui.dp(this, 6);
        lp.gravity = Gravity.CENTER;
        v.setLayoutParams(lp);
        return v;
    }

    /** Un botón de la barra, al tamaño elegido (compacta o normal). */
    private View sized(View v) {
        v.setLayoutParams(new LinearLayout.LayoutParams(iconPx, iconPx));
        return v;
    }

    private boolean vertical() {
        return BarSettings.LEFT.equals(barPos) || BarSettings.RIGHT.equals(barPos);
    }

    private boolean colorsInBar() {
        return BarSettings.C_BAR.equals(colorsMode);
    }

    private boolean colorsFloat() {
        return BarSettings.C_FLOAT.equals(colorsMode);
    }

    /** Radio de las esquinas según la forma elegida. */
    private int corner() {
        int shape = BarSettings.shape(this);
        return shape == 0 ? barH / 2 : Ui.dp(this, shape == 1 ? 14 : 6);
    }

    /** Colores, tamaño, posición y plegado según Personalizar. */
    private void loadBarLook() {
        fg = paperDark ? 0xFFF3F4F6 : Ui.ICON;
        fgMuted = paperDark ? 0xFFB6BEC9 : Ui.ICON_MUTED;
        glass = paperDark ? 0xB81E2228 : Ui.GLASS_LIGHT;
        barStyle = BarSettings.style(this);
        compactBar = BarSettings.compact(this);
        barH = Ui.dp(this, compactBar ? 46 : 56);
        iconPx = Ui.dp(this, compactBar ? 38 : 44);
        darkBar = paperDark;
        int barColor = BarSettings.color(this);
        if (barColor != 0) {
            boolean light = BarSettings.isLight(barColor);
            glass = (barColor & 0x00FFFFFF) | 0xE6000000;
            fg = light ? Ui.ICON : 0xFFF3F4F6;
            fgMuted = light ? Ui.ICON_MUTED : 0xFFB6BEC9;
            darkBar = !light;
        }
        barPos = BarSettings.pos(this);
        fold = BarSettings.fold(this);
        foldByPen = BarSettings.foldByPen(this);
        colorsMode = BarSettings.colorsMode(this);
    }

    /** Arma (o rehace, si se cambió algo en Personalizar) la barra, los colores y sus pestañas. */
    private void buildBars() {
        for (View v : new View[] {bar, palette, barTab, colorsTab}) if (v != null) stage.removeView(v);
        if (barAnim != null) barAnim.cancel();
        if (palAnim != null) palAnim.cancel();
        loadBarLook();
        inlinePalette = null;
        palette = palette();
        bar = toolbar();
        int at = stage.indexOfChild(ink) + 1;
        stage.addView(palette, at, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        stage.addView(bar, at + 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        barTab = fold ? barTab() : null;
        colorsTab = fold && !colorsInBar() ? colorsTab() : null;
        if (barTab != null) stage.addView(barTab, at + 2, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (colorsTab != null) stage.addView(colorsTab, at + 3, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        barOpen = paletteOpen = !fold;
        layoutBars();
        refreshTools();
        animateBar(barOpen, false);
        animatePalette(paletteOpen, false);
    }

    /** Dónde va cada cosa según la posición elegida y los bordes del sistema. */
    private void layoutBars() {
        if (bar == null) return;
        int gap = Ui.dp(this, 10), side = Ui.dp(this, 14);
        int w = stage.getWidth() > 0 ? stage.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int h = stage.getHeight() > 0 ? stage.getHeight() : getResources().getDisplayMetrics().heightPixels;
        boolean v = vertical();
        FrameLayout.LayoutParams b = new FrameLayout.LayoutParams(v ? barH : ViewGroup.LayoutParams.WRAP_CONTENT, v ? ViewGroup.LayoutParams.WRAP_CONTENT : barH);
        switch (barPos) {
            case BarSettings.BOTTOM: b.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; b.bottomMargin = inBottom + gap; break;
            case BarSettings.LEFT: b.gravity = Gravity.START | Gravity.CENTER_VERTICAL; b.leftMargin = inLeft + gap; b.topMargin = inTop + gap; b.bottomMargin = inBottom + gap; break;
            case BarSettings.RIGHT: b.gravity = Gravity.END | Gravity.CENTER_VERTICAL; b.rightMargin = inRight + gap; b.topMargin = inTop + gap; b.bottomMargin = inBottom + gap; break;
            default: b.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; b.topMargin = inTop + gap;
        }
        bar.setLayoutParams(b);
        bar.maxWidth = v ? Integer.MAX_VALUE : w - 2 * gap;
        // Los colores: del lado contrario a una barra de pie; flotando donde se dejaron.
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (colorsFloat()) {
            p.gravity = Gravity.TOP | Gravity.START;
            p.leftMargin = Math.round(Math.max(inLeft, Math.min(w - Ui.dp(this, 60), BarSettings.floatX(this) * w)));
            p.topMargin = Math.round(Math.max(inTop, Math.min(h - Ui.dp(this, 200), BarSettings.floatY(this) * h)));
        } else if (paletteAtEnd()) {
            p.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            p.rightMargin = inRight + side;
        } else {
            p.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            p.leftMargin = inLeft + side;
        }
        palette.setLayoutParams(p);
        // Las pestañas, pegadas a su borde.
        if (barTab != null) {
            FrameLayout.LayoutParams t = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            switch (barPos) {
                case BarSettings.BOTTOM: t.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; t.bottomMargin = inBottom; break;
                case BarSettings.LEFT: t.gravity = Gravity.START | Gravity.CENTER_VERTICAL; t.leftMargin = inLeft; break;
                case BarSettings.RIGHT: t.gravity = Gravity.END | Gravity.CENTER_VERTICAL; t.rightMargin = inRight; break;
                default: t.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; t.topMargin = inTop;
            }
            barTab.setLayoutParams(t);
        }
        if (colorsTab != null) {
            FrameLayout.LayoutParams t = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (colorsFloat()) {
                t.gravity = Gravity.TOP | Gravity.START;
                t.leftMargin = p.leftMargin;
                t.topMargin = p.topMargin;
            } else if (paletteAtEnd()) {
                t.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
                t.rightMargin = inRight;
            } else {
                t.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
                t.leftMargin = inLeft;
            }
            colorsTab.setLayoutParams(t);
        }
        // Lo de abajo (selección, figuras, nombre) sube si la barra está abajo.
        boolean barBottom = BarSettings.BOTTOM.equals(barPos);
        FrameLayout.LayoutParams sel = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, barH,
                (barBottom ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL);
        if (barBottom) sel.topMargin = inTop + gap;
        else sel.bottomMargin = inBottom + Ui.dp(this, 18);
        selectionBar.setLayoutParams(sel);
        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                (barBottom ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL);
        if (barBottom) dockLp.topMargin = sel.topMargin;
        else dockLp.bottomMargin = sel.bottomMargin;
        shapeBar.setLayoutParams(dockLp);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 30),
                (barBottom ? Gravity.TOP : Gravity.BOTTOM) | (BarSettings.LEFT.equals(barPos) ? Gravity.END : Gravity.START));
        tl.leftMargin = inLeft + side;
        tl.rightMargin = inRight + side;
        if (barBottom) tl.topMargin = inTop + Ui.dp(this, 12);
        else tl.bottomMargin = inBottom + Ui.dp(this, 12);
        titleChip.setLayoutParams(tl);
        ink.topInset = BarSettings.TOP.equals(barPos) ? inTop + gap + barH : inTop + gap;
    }

    private boolean paletteAtEnd() {
        return BarSettings.LEFT.equals(barPos);
    }

    private GlassLayout toolbar() {
        boolean v = vertical();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(v ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setGravity(v ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
        if (v) row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 12));
        else row.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 12), 0);
        row.addView(sized(icon(IconView.BACK, "Volver a los cuadernos", x -> finish())));

        // Tocar otra vez la herramienta activa abre sus opciones.
        pen = icon(IconView.PEN, "Pluma (toca otra vez para el tipo)", x -> {
            if (ink.tool == InkView.PEN) chooseKind();
            else setTool(InkView.PEN);
        });
        highlighter = icon(IconView.HIGHLIGHTER, "Resaltador", x -> setTool(InkView.HIGHLIGHTER));
        eraser = icon(IconView.ERASER, "Goma (toca otra vez para el modo)", x -> {
            if (ink.tool == InkView.ERASER) chooseEraser();
            else setTool(InkView.ERASER);
        });
        lasso = icon(IconView.LASSO, "Lazo: toca una palabra, rodea o pasa por encima", x -> setTool(InkView.LASSO));
        shapes = icon(IconView.SHAPES, "Figuras (toca otra vez para la galería)", x -> {
            if (ink.tool == InkView.SHAPE) toggleDock();
            else setTool(InkView.SHAPE);
        });
        text = icon(IconView.TEXT, "Texto: toca la hoja para escribir", x -> setTool(InkView.TEXT));
        tape = icon(IconView.TAPE, "Cinta: tapa algo; tócala con el dedo para destaparla", x -> setTool(InkView.TAPE));
        laser = icon(IconView.LASER, "Puntero láser (también en el PC)", x -> setTool(InkView.LASER));
        note = icon(IconView.NOTE, "Notas: no salen al exportar; pueden ir fuera de la hoja", x -> setTool(InkView.NOTE));
        neuron = icon(IconView.NEURON, "Lápiz neurona: rodea lo que se relaciona", x -> setTool(InkView.LINK));
        IconView ruler = icon(IconView.RULER, "Regla, escuadra, transportador y compás", x -> guideMenu());
        undo = icon(IconView.UNDO, "Deshacer", x -> ink.undo());
        redo = icon(IconView.REDO, "Rehacer", x -> ink.redo());
        finger = icon(IconView.HAND, "Dibujar con el dedo", x -> {
            ink.fingerDraws = !ink.fingerDraws;
            prefs.edit().putBoolean("finger", ink.fingerDraws).apply();
            refreshTools();
            Ui.toast(this, ink.fingerDraws ? "El dedo dibuja (dos dedos mueven la hoja)" : "El dedo mueve la hoja; dibuja el lápiz");
        });
        IconView fit = icon(IconView.FIT, "Ver la página entera (otra vez: al ancho)", x -> {
            if (ink.fittedWhole()) ink.fitWidth();
            else ink.fitWholePage();
        });
        // En el mismo orden que BarSettings.KEYS (null donde va un separador).
        View[] views = {pen, highlighter, eraser, lasso, shapes, ruler, text, note, neuron, tape, laser, null,
            undo, redo, null,
            icon(IconView.PREV, "Página anterior", x -> ink.scrollToPage(index - 1, true)),
            icon(IconView.NEXT, "Página siguiente", x -> ink.scrollToPage(index + 1, true)),
            icon(IconView.PLUS, "Página nueva", x -> addPageMenu()),
            icon(IconView.PAGES, "Páginas: ordenar, duplicar, borrar", x -> pageManager()),
            icon(IconView.PAGE, "Plantilla de la hoja", x -> chooseTemplate()),
            icon(IconView.IMAGE, "Insertar foto o PDF", x -> insertMenu()),
            fit,
            icon(IconView.EXPORT, "Exportar", x -> exportMenu()),
            icon(IconView.PLAY, "Presentar desde esta página", x -> present()),
            icon(IconView.MOON, paperDark ? "Hoja clara" : "Hoja oscura (al exportar sale blanca)", x -> togglePaper()),
            null, finger};
        java.util.Set<String> hidden = BarSettings.effectiveHidden(this);
        barItems.clear();
        boolean needSep = true, inlineDone = false;
        for (int i = 0; i < BarSettings.KEYS.length; i++) {
            String k = BarSettings.KEYS[i];
            if (k.equals("|")) {
                // «En la barra»: colores y grosores justo después de las herramientas.
                if (colorsInBar() && !inlineDone) {
                    inlineDone = true;
                    inlinePalette = new LinearLayout(this);
                    inlinePalette.setOrientation(v ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
                    inlinePalette.setGravity(Gravity.CENTER);
                    palette.removeView(colors);
                    palette.removeView(widths);
                    palette.removeView(paletteSep);
                    colors.setOrientation(v ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
                    widths.setOrientation(v ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
                    inlinePalette.addView(sep(v));
                    inlinePalette.addView(colors);
                    inlinePalette.addView(widths);
                    row.addView(inlinePalette);
                }
                needSep = true;
                continue;
            }
            barItems.put(k, views[i]);
            if (hidden.contains(k)) continue;
            if (needSep) {
                row.addView(sep(v));
                needSep = false;
            }
            row.addView(sized(views[i]));
        }
        // Lo escondido, a mano en «⋯» (y desde ahí, Personalizar).
        row.addView(sized(icon(IconView.DOTS, hidden.isEmpty() ? "Más: personalizar la barra" : "Más herramientas y personalizar", x -> overflowMenu())));

        dot = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(this, 9), Ui.dp(this, 9));
        if (v) dlp.topMargin = Ui.dp(this, 6);
        else dlp.leftMargin = Ui.dp(this, 6);
        row.addView(dot, dlp);

        GlassLayout g = new GlassLayout(this, ink, glass, corner());
        g.setOrientation(v ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        g.setGravity(Gravity.CENTER);
        if (v) {
            ScrollView scroll = new ScrollView(this);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.addView(row, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            g.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.addView(row, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            g.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        return g;
    }

    // ---- Pestañas: plegar y desplegar con animación ----

    /** Hacia dónde apunta la flecha de la pestaña plegada (hacia dentro de la pantalla). */
    private float tabRotation() {
        switch (barPos) {
            case BarSettings.BOTTOM: return -90;
            case BarSettings.LEFT: return 0;
            case BarSettings.RIGHT: return 180;
            default: return 90;
        }
    }

    private GlassLayout barTab() {
        boolean v = vertical();
        GlassLayout t = new GlassLayout(this, ink, glass, Ui.dp(this, 13));
        t.setGravity(Gravity.CENTER);
        barChevron = icon(IconView.NEXT, "Abrir la barra", x -> toggleBar());
        barChevron.setRotation(tabRotation());
        t.addView(barChevron, new LinearLayout.LayoutParams(Ui.dp(this, v ? 26 : 64), Ui.dp(this, v ? 64 : 26)));
        t.setContentDescription("Abrir o plegar la barra");
        return t;
    }

    private GlassLayout colorsTab() {
        GlassLayout t = new GlassLayout(this, ink, glass, Ui.dp(this, colorsFloat() ? 22 : 13));
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        colorsDot = new View(this);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16));
        dp.bottomMargin = colorsFloat() ? 0 : Ui.dp(this, 2);
        t.addView(colorsDot, dp);
        if (!colorsFloat()) {
            colorsChevron = new IconView(this, IconView.NEXT, fg, "");
            colorsChevron.setRotation(paletteAtEnd() ? 180 : 0);
            colorsChevron.setClickable(false);
            t.addView(colorsChevron, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        } else {
            colorsChevron = null;
        }
        int pad = Ui.dp(this, colorsFloat() ? 12 : 4);
        t.setPadding(pad, Ui.dp(this, colorsFloat() ? 12 : 10), pad, Ui.dp(this, colorsFloat() ? 12 : 8));
        t.setClickable(true);
        t.setContentDescription("Abrir los colores");
        t.setOnClickListener(x -> togglePalette());
        return t;
    }

    private void toggleBar() {
        animateBar(!barOpen, true);
        keepBarsOpen();
    }

    private void togglePalette() {
        animatePalette(!paletteOpen, true);
        keepBarsOpen();
    }

    /** Fuera de la pantalla, del lado de su borde. */
    private float barHidden() {
        int gap = Ui.dp(this, 10);
        switch (barPos) {
            case BarSettings.BOTTOM: return inBottom + gap + barH + Ui.dp(this, 6);
            case BarSettings.LEFT: return -(inLeft + gap + barH + Ui.dp(this, 6));
            case BarSettings.RIGHT: return inRight + gap + barH + Ui.dp(this, 6);
            default: return -(inTop + gap + barH + Ui.dp(this, 6));
        }
    }

    /** Abierta, la pestaña queda colgando justo después de la barra. */
    private float tabOpenOffset() {
        float d = Ui.dp(this, 10) + barH - Ui.dp(this, 2);
        return BarSettings.BOTTOM.equals(barPos) || BarSettings.RIGHT.equals(barPos) ? -d : d;
    }

    /** La barra baja (o entra de lado) con un rebote suave; al plegarse sale rápida. */
    private void animateBar(boolean open, boolean animate) {
        barOpen = open;
        if (barAnim != null) barAnim.cancel();
        if (!fold) {
            bar.setVisibility(View.VISIBLE);
            bar.setTranslationX(0);
            bar.setTranslationY(0);
            bar.setAlpha(1);
            return;
        }
        boolean v = vertical();
        float hidden = barHidden(), tabOpen = tabOpenOffset(), base = tabRotation();
        float from = v ? bar.getTranslationX() : bar.getTranslationY();
        float start = hidden == 0 ? 0 : 1 - from / hidden;
        float end = open ? 1 : 0;
        bar.setVisibility(View.VISIBLE);
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(animate ? start : end, end);
        a.setDuration(animate ? (open ? 320 : 220) : 0);
        a.setInterpolator(open ? new android.view.animation.OvershootInterpolator(1.1f) : new android.view.animation.DecelerateInterpolator(1.6f));
        a.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            float off = hidden * (1 - f);
            if (v) bar.setTranslationX(off);
            else bar.setTranslationY(off);
            bar.setAlpha(Math.max(0, Math.min(1, f * 1.4f)));
            if (barTab != null) {
                if (v) barTab.setTranslationX(tabOpen * f);
                else barTab.setTranslationY(tabOpen * f);
                barChevron.setRotation(base + 180 * Math.min(1, f));
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator an) {
                if (!barOpen) bar.setVisibility(View.INVISIBLE);
            }
        });
        barAnim = a;
        a.start();
    }

    /** Los colores salen de su borde (o crecen desde la burbuja si flotan). */
    private void animatePalette(boolean open, boolean animate) {
        paletteOpen = open;
        if (palAnim != null) palAnim.cancel();
        if (!fold || colorsInBar()) {
            palette.setTranslationX(0);
            palette.setScaleX(1);
            palette.setScaleY(1);
            palette.setAlpha(1);
            applyPaletteState();
            return;
        }
        boolean end = paletteAtEnd();
        int width = palette.getWidth() > 0 ? palette.getWidth() : Ui.dp(this, 56);
        float hidden = (end ? 1 : -1) * (width + Ui.dp(this, 20));
        float tabOpen = (end ? -1 : 1) * (width + Ui.dp(this, 8));
        boolean floating = colorsFloat();
        float target = open ? 1 : 0;
        float startF = floating ? palette.getAlpha() : (hidden == 0 ? 0 : 1 - palette.getTranslationX() / hidden);
        if (open && palette.getVisibility() != View.VISIBLE) palette.setVisibility(View.VISIBLE);
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(animate ? startF : target, target);
        a.setDuration(animate ? (open ? 300 : 200) : 0);
        a.setInterpolator(open ? new android.view.animation.OvershootInterpolator(1.0f) : new android.view.animation.DecelerateInterpolator(1.6f));
        a.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            if (floating) {
                float s = 0.4f + 0.6f * f;
                palette.setPivotX(0);
                palette.setPivotY(0);
                palette.setScaleX(s);
                palette.setScaleY(s);
                palette.setAlpha(Math.max(0, Math.min(1, f)));
                if (colorsTab != null) colorsTab.setAlpha(1 - Math.min(1, f));
            } else {
                palette.setTranslationX(hidden * (1 - f));
                palette.setAlpha(Math.max(0, Math.min(1, f * 1.4f)));
                if (colorsTab != null) colorsTab.setTranslationX(tabOpen * f);
                if (colorsChevron != null) colorsChevron.setRotation((end ? 180 : 0) + 180 * Math.min(1, f));
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator an) {
                applyPaletteState();
            }
        });
        palAnim = a;
        a.start();
    }

    /** Con «se abre con el lápiz»: se pliega sola un rato después. */
    private void keepBarsOpen() {
        if (!fold || !foldByPen) return;
        lastBarUse = android.os.SystemClock.uptimeMillis();
        main.removeCallbacks(hideBars);
        int wait = BarSettings.hideMs(this);
        // «Nunca»: se queda abierta hasta que se toque su pestaña.
        if (wait > 0) main.postDelayed(hideBars, wait + 100);
    }

    private void hideBarsNow() {
        if (!fold || !foldByPen) return;
        int wait = BarSettings.hideMs(this);
        if (wait <= 0) return;
        if (dock.isOpen() || designer != null || android.os.SystemClock.uptimeMillis() - lastBarUse < wait) {
            main.postDelayed(hideBars, Math.max(400, wait / 2));
            return;
        }
        if (barOpen) animateBar(false, true);
        if (paletteOpen) animatePalette(false, true);
    }

    /** El S Pen cerca de la pantalla (sin tocarla): cerca de una pestaña, se abre. */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (fold && foldByPen && designer == null && e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
                && (a == MotionEvent.ACTION_HOVER_ENTER || a == MotionEvent.ACTION_HOVER_MOVE)) {
            int zone = Ui.dp(this, 70);
            if (near(barTab, e, zone) || (barOpen && near(bar, e, zone))) {
                if (!barOpen) animateBar(true, true);
                keepBarsOpen();
            }
            if (near(colorsTab, e, zone) || (paletteOpen && near(palette, e, zone))) {
                if (!paletteOpen) animatePalette(true, true);
                keepBarsOpen();
            }
        }
        return super.dispatchGenericMotionEvent(e);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        // Mientras se usa la barra o los colores, no se pliegan.
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && fold && foldByPen && (near(bar, e, 0) || near(palette, e, 0))) keepBarsOpen();
        return super.dispatchTouchEvent(e);
    }

    /** ¿El punto está sobre la vista (con su desplazamiento) o a menos de `zone`? */
    private static boolean near(View v, MotionEvent e, int zone) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        float l = v.getLeft() + v.getTranslationX(), t = v.getTop() + v.getTranslationY();
        return e.getX() >= l - zone && e.getX() <= l + v.getWidth() + zone && e.getY() >= t - zone && e.getY() <= t + v.getHeight() + zone;
    }

    /** «⋯»: lo escondido de la barra y, bien visible, Personalizar. */
    private void overflowMenu() {
        java.util.Set<String> hidden = BarSettings.effectiveHidden(this);
        java.util.List<String> opensDock = java.util.Arrays.asList("ruler", "add", "pages", "template", "insert", "export");
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (String k : BarSettings.KEYS) {
            View v = barItems.get(k);
            if (v == null || !hidden.contains(k)) continue;
            boolean stays = opensDock.contains(k) || (k.equals("pen") && ink.tool == InkView.PEN) || (k.equals("eraser") && ink.tool == InkView.ERASER);
            OptionDock.Tile t = new OptionDock.Tile(glyph(BarSettings.icon(k)), BarSettings.label(k), false, v::performClick);
            tiles.add(stays ? t.stays() : t);
        }
        OptionDock.Action personalize = new OptionDock.Action("✦  Personalizar la barra", true, this::openDesigner);
        if (tiles.isEmpty()) {
            LinearLayout box = new LinearLayout(this);
            box.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), Ui.dp(this, 8));
            box.addView(dock.message("Todos los botones están en la barra. Puedes esconder los que no uses, moverla de lado, plegarla con pestañas y cambiarle el color."));
            dock.show("Barra", box, new OptionDock.Action("Cerrar", false, null), personalize);
        } else {
            dock.tiles("Más herramientas", tiles, personalize);
        }
    }

    // ---- Personalizar (concepto A): la página se encoge a la izquierda ----

    private void openDesigner() {
        if (designer != null) return;
        BarDesigner.ColorWheelHost.dock = dock;
        designer = new BarDesigner(this, new BarDesigner.Host() {
            @Override
            public void barChanged() {
                buildBars();
            }

            @Override
            public void closeDesigner() {
                PageActivity.this.closeDesigner();
            }
        });
        int w = root.getWidth(), h = root.getHeight(), m = Ui.dp(this, 22);
        int panelW = Math.min(Ui.dp(this, 440), Math.round(w * 0.42f));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(panelW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        lp.setMargins(m, inTop + m, inRight + m, inBottom + m);
        root.addView(designer.view, root.indexOfChild(stage) + 1, lp);
        root.setBackgroundColor(paperDark ? 0xFF0B0D10 : 0xFFE5E9EF);
        float s = Math.max(0.3f, (w - panelW - 3f * m) / w);
        stage.setPivotX(0);
        stage.setPivotY(h / 2f);
        stage.setClipToOutline(true);
        stage.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dp(PageActivity.this, 28));
            }
        });
        stage.animate().scaleX(s).scaleY(s).translationX(m).setDuration(320)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f)).start();
        designer.view.setTranslationX(panelW + m);
        designer.view.animate().translationX(0).setDuration(320).setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f)).start();
    }

    private void closeDesigner() {
        if (designer == null) return;
        View panel = designer.view;
        designer = null;
        stage.animate().scaleX(1).scaleY(1).translationX(0).setDuration(260)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                .withEndAction(() -> stage.setClipToOutline(false)).start();
        panel.animate().translationX(panel.getWidth() + Ui.dp(this, 22)).setDuration(240).withEndAction(() -> root.removeView(panel)).start();
    }

    // ---- Dedo quieto sobre la hoja ----

    @Override
    public void onPageLongPress(int page, float x, float y) {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        tiles.add(new OptionDock.Tile(glyph(IconView.PASTE), "Pegar", false, this::paste));
        tiles.add(new OptionDock.Tile(glyph(IconView.TEXT), "Cuadro de texto", false, () -> onTextRequest(page, null, x, y)).stays());
        tiles.add(new OptionDock.Tile(glyph(IconView.NOTE), "Nota adhesiva", false, () -> onNoteRequest(page, null, x, y)).stays());
        tiles.add(new OptionDock.Tile(glyph(IconView.COPY), "Portapapeles", false, this::showClipboard).stays());
        tiles.add(new OptionDock.Tile(glyph(IconView.IMAGE), "Foto o PDF", false, this::insertMenu).stays());
        tiles.add(new OptionDock.Tile(glyph(IconView.PAGE), "Plantilla de la hoja", false, this::chooseTemplate).stays());
        dock.tiles("En la hoja", tiles);
    }

    /** Lo que hay copiado (texto o imagen), para verlo antes de pegarlo. */
    private void showClipboard() {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData clip = cm == null ? null : cm.getPrimaryClip();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 18);
        box.setPadding(pad, 0, pad, Ui.dp(this, 8));
        if (clip == null || clip.getItemCount() == 0) {
            box.addView(dock.message("El portapapeles está vacío. Copia un texto o una imagen (también en el PC) y vuelve aquí."));
            dock.show("Portapapeles", box, new OptionDock.Action("Cerrar", true, null));
            return;
        }
        android.content.ClipData.Item item = clip.getItemAt(0);
        boolean image = false;
        if (item.getUri() != null) {
            try {
                Bitmap b = loadImage(item.getUri(), 900);
                if (b != null) {
                    ImageView iv = new ImageView(this);
                    iv.setAdjustViewBounds(true);
                    iv.setImageBitmap(b);
                    box.addView(iv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 220)));
                    image = true;
                }
            } catch (IOException | RuntimeException ignored) {
                // No es una imagen: se enseña como texto.
            }
        }
        if (!image) {
            CharSequence t = item.coerceToText(this);
            TextView tv = dock.message(t == null || t.length() == 0 ? "(vacío)" : t.length() > 600 ? t.subSequence(0, 600) + "…" : t.toString());
            tv.setMaxLines(12);
            box.addView(tv);
        }
        dock.show("Portapapeles", box, new OptionDock.Action("Cerrar", false, null), new OptionDock.Action("Pegar aquí", true, this::paste));
    }

    private GlassLayout palette() {
        GlassLayout g = new GlassLayout(this, ink, glass, BarSettings.shape(this) == 0 ? Ui.dp(this, 28) : corner());
        g.setOrientation(LinearLayout.VERTICAL);
        g.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(this, 6);
        g.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 8));
        colors = new LinearLayout(this);
        colors.setOrientation(LinearLayout.VERTICAL);
        widths = new LinearLayout(this);
        widths.setOrientation(LinearLayout.VERTICAL);
        // Flotantes: un asa para arrastrarlos a donde estorben menos.
        if (colorsFloat()) {
            View grip = new View(this);
            grip.setBackground(Ui.round(fg & 0xFFFFFF | 0x66000000, Ui.dp(this, 2)));
            FrameLayout gripCell = new FrameLayout(this);
            gripCell.addView(grip, new FrameLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 4), Gravity.CENTER));
            gripCell.setContentDescription("Arrastrar los colores");
            gripCell.setOnTouchListener(new View.OnTouchListener() {
                float dx, dy;

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) g.getLayoutParams();
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            dx = e.getRawX() - lp.leftMargin;
                            dy = e.getRawY() - lp.topMargin;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            lp.leftMargin = Math.round(Math.max(0, Math.min(stage.getWidth() - g.getWidth(), e.getRawX() - dx)));
                            lp.topMargin = Math.round(Math.max(inTop, Math.min(stage.getHeight() - g.getHeight(), e.getRawY() - dy)));
                            g.setLayoutParams(lp);
                            if (colorsTab != null) {
                                FrameLayout.LayoutParams tl = (FrameLayout.LayoutParams) colorsTab.getLayoutParams();
                                tl.leftMargin = lp.leftMargin;
                                tl.topMargin = lp.topMargin;
                                colorsTab.setLayoutParams(tl);
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            BarSettings.prefs(PageActivity.this).edit()
                                    .putFloat(BarSettings.FLOAT_X, lp.leftMargin / (float) Math.max(1, stage.getWidth()))
                                    .putFloat(BarSettings.FLOAT_Y, lp.topMargin / (float) Math.max(1, stage.getHeight())).apply();
                            return true;
                        default:
                            return false;
                    }
                }
            });
            g.addView(gripCell, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 24)));
        }
        // Con su propio ancho: si no, toman el de la línea separadora.
        g.addView(colors, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        paletteSep = sep(true);
        g.addView(paletteSep);
        g.addView(widths, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return g;
    }

    private GlassLayout selectionBar() {
        GlassLayout g = new GlassLayout(this, ink, glass, corner());
        g.setGravity(Gravity.CENTER_VERTICAL);
        g.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        selectionHint = Ui.text(this, BOX_HINT, 13, fgMuted, false);
        selectionHint.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        g.addView(selectionHint);
        editLine = icon(IconView.S_ARROW, "Cambiar de tipo", v -> ink.cycleSelectedLine());
        g.addView(editLine);
        editFlip = icon(IconView.FLIP, "Voltear", v -> ink.flipSelected());
        g.addView(editFlip);
        editImage = icon(IconView.SLIDERS, "Editar la imagen", v -> {
            Ink.Item it = ink.selectedImage();
            if (it != null) {
                startActivityForResult(new Intent(this, ImageEditActivity.class).putExtra("asset", it.asset), EDIT_IMAGE);
            }
        });
        g.addView(editImage);
        editText = icon(IconView.TEXT, "Editar el texto", v -> ink.editSelectedText());
        g.addView(editText);
        g.addView(icon(IconView.COPY, "Duplicar", v -> ink.duplicateSelection()));
        g.addView(icon(IconView.TRASH, "Borrar", v -> ink.deleteSelection()));
        g.addView(icon(IconView.CHECK, "Listo", v -> ink.clearSelection()));
        return g;
    }

    /**
     * Dock de la herramienta de figuras: un toque elige qué se arrastra. El botón
     * de más lo hace crecer, con el mismo cristal, hasta enseñar toda la galería.
     */
    private GlassLayout shapeBar() {
        GlassLayout g = new GlassLayout(this, ink, glass, Ui.dp(this, 28));
        g.setOrientation(LinearLayout.VERTICAL);
        g.setGravity(Gravity.CENTER_HORIZONTAL);
        dockPanel = new ScrollView(this);
        dockPanel.setVerticalScrollBarEnabled(false);
        dockPanel.setAlpha(0);
        dockPanel.setVisibility(View.GONE);
        g.addView(dockPanel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        for (int i = 0; i < SHAPE_KINDS.length; i++) {
            int kind = SHAPE_KINDS[i];
            shapeButtons[i] = icon(SHAPE_ICONS[i], SHAPE_NAMES[i], v -> {
                ink.shapeKind = kind;
                prefs.edit().putInt("shapeKind", kind).apply();
                refreshTools();
                if (dockOpen) toggleDock();
            });
            row.addView(shapeButtons[i]);
        }
        row.addView(icon(IconView.CHIP, "Circuitos", v -> setCircuitMode(true)));
        row.addView(sep(false));
        autoShapeButton = icon(IconView.WAND, "Convertir mis trazos en figuras", v -> {
            ink.autoShapes = !ink.autoShapes;
            prefs.edit().putBoolean("shapes", ink.autoShapes).apply();
            refreshTools();
            Ui.toast(this, ink.autoShapes ? "Tus trazos se convierten en figuras" : "Deja el lápiz quieto al final del trazo para convertirlo");
        });
        row.addView(autoShapeButton);
        dockMore = icon(IconView.MORE, "Más figuras", v -> toggleDock());
        row.addView(dockMore);
        shapeRow = row;
        g.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 56)));
        circuitRow = new LinearLayout(this);
        circuitRow.setGravity(Gravity.CENTER_VERTICAL);
        circuitRow.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        circuitRow.addView(icon(IconView.BACK_ROW, "Volver a las figuras", v -> setCircuitMode(false)));
        circuitRow.addView(sep(false));
        for (int i = 0; i < CIRCUIT_KINDS.length; i++) {
            int kind = CIRCUIT_KINDS[i];
            circuitButtons[i] = new ComponentButton(this, kind, CIRCUIT_NAMES[i]);
            circuitButtons[i].setOnClickListener(v -> {
                ink.shapeKind = kind;
                prefs.edit().putInt("shapeKind", kind).apply();
                refreshTools();
            });
            circuitRow.addView(circuitButtons[i]);
        }
        // En una tablet pequeña no caben todos: el dock se desliza.
        circuitDock = new HorizontalScrollView(this);
        circuitDock.setHorizontalScrollBarEnabled(false);
        circuitDock.addView(circuitRow, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        circuitDock.setVisibility(View.GONE);
        g.addView(circuitDock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 56)));
        g.maxWidth = getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 24);
        return g;
    }

    /** Un aviso que sólo hace falta la primera vez. */
    private void hintOnce(String key, String text) {
        if (prefs.getBoolean(key, false)) return;
        prefs.edit().putBoolean(key, true).apply();
        Ui.toast(this, text);
    }

    /** El complemento de circuitos: el dock pasa a tener componentes. */
    private void setCircuitMode(boolean on) {
        closeDockNow();
        dockCollapsedWidth = 0;
        android.transition.ChangeBounds grow = new android.transition.ChangeBounds();
        grow.setDuration(280);
        grow.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0.9f, 0.2f, 1f));
        android.transition.TransitionManager.beginDelayedTransition((ViewGroup) shapeBar.getParent(), grow);
        shapeRow.setVisibility(on ? View.GONE : View.VISIBLE);
        circuitDock.setVisibility(on ? View.VISIBLE : View.GONE);
        ink.shapeKind = on ? ShapeTools.CIRCUIT_FREE : ShapeTools.RECT;
        prefs.edit().putInt("shapeKind", ink.shapeKind).apply();
        if (on) hintOnce("circuitHint", "Dibuja: zigzag, resistencia · círculo, fuente · espiral, bobina · triángulo, operacional · línea, cable. Toca un componente para su valor");
        refreshTools();
    }

    /** Abre o cierra el dock, con el mismo gesto de ida y de vuelta. */
    private void toggleDock() {
        if (dockAnimator != null) dockAnimator.cancel();
        dockOpen = !dockOpen;
        dockMore.setActive(dockOpen);
        int gap = Ui.dp(this, 24);
        ViewGroup root = (ViewGroup) shapeBar.getParent();
        if (dockCollapsedWidth == 0) dockCollapsedWidth = shapeBar.getWidth();
        int openWidth = Math.max(dockCollapsedWidth, Math.min(root.getWidth() - 2 * gap, Ui.dp(this, 860)));
        if (dockOpen && dockPanel.getChildCount() == 0) dockPanel.addView(dockGallery(openWidth));
        int openHeight = Math.min(root.getHeight() * 11 / 20, Ui.dp(this, 460));
        ViewGroup.LayoutParams glass = shapeBar.getLayoutParams();
        LinearLayout.LayoutParams panel = (LinearLayout.LayoutParams) dockPanel.getLayoutParams();
        float from = dockProgress, to = dockOpen ? 1 : 0;
        dockPanel.setVisibility(View.VISIBLE);
        dockAnimator = android.animation.ValueAnimator.ofFloat(from, to);
        dockAnimator.setDuration(dockOpen ? 380 : 280);
        dockAnimator.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0.9f, 0.2f, 1f));
        dockAnimator.addUpdateListener(a -> {
            dockProgress = (float) a.getAnimatedValue();
            glass.width = Math.round(dockCollapsedWidth + (openWidth - dockCollapsedWidth) * dockProgress);
            panel.height = Math.round(openHeight * dockProgress);
            // El contenido llega un poco después que el cristal.
            float content = Math.max(0, (dockProgress - 0.35f) / 0.65f);
            dockPanel.setAlpha(content);
            dockPanel.setTranslationY((1 - content) * Ui.dp(this, 18));
            shapeBar.requestLayout();
        });
        dockAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (cancelled || dockOpen) return;
                dockPanel.setVisibility(View.GONE);
                glass.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                shapeBar.requestLayout();
            }
        });
        dockAnimator.start();
    }

    /** Cierra el dock de golpe: se va con la herramienta. */
    private void closeDockNow() {
        if (!dockOpen && dockProgress == 0) return;
        if (dockAnimator != null) dockAnimator.cancel();
        dockOpen = false;
        dockProgress = 0;
        dockMore.setActive(false);
        dockPanel.setVisibility(View.GONE);
        dockPanel.setAlpha(0);
        dockPanel.getLayoutParams().height = 0;
        shapeBar.getLayoutParams().width = ViewGroup.LayoutParams.WRAP_CONTENT;
        shapeBar.requestLayout();
    }

    /** La galería entera, por grupos. */
    private View dockGallery(int width) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 16), pad, Ui.dp(this, 6));
        int cell = Ui.dp(this, 72), space = Ui.dp(this, 4);
        int cols = Math.max(4, (width - 2 * pad) / (cell + 2 * space));
        for (int g = 0; g < ShapeLibrary.GROUPS.length; g++) {
            TextView head = Ui.text(this, ShapeLibrary.GROUPS[g], 13, fgMuted, true);
            head.setPadding(Ui.dp(this, 4), g == 0 ? 0 : Ui.dp(this, 14), 0, Ui.dp(this, 4));
            box.addView(head);
            GridLayout grid = new GridLayout(this);
            grid.setColumnCount(cols);
            for (ShapeLibrary.Shape s : ShapeLibrary.group(g)) {
                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setGravity(Gravity.CENTER_HORIZONTAL);
                item.setBackground(Ui.round(fg & 0xFFFFFF | 0x14000000, Ui.dp(this, 14)));
                item.addView(new ShapePreview(this, s), new LinearLayout.LayoutParams(cell, cell));
                TextView name = Ui.text(this, s.name, 10, fgMuted, false);
                name.setGravity(Gravity.CENTER);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setMaxWidth(cell);
                item.addView(name);
                item.setPadding(0, 0, 0, Ui.dp(this, 5));
                item.setContentDescription(s.name);
                item.setOnClickListener(v -> {
                    if (ink.tool != InkView.LASSO) setTool(InkView.LASSO);
                    ink.insertShape(s);
                });
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.setMargins(space, space, space, space);
                grid.addView(item, lp);
            }
            box.addView(grid);
        }
        return box;
    }

    private void setTool(int t) {
        if (t == InkView.NOTE && ink.tool != InkView.NOTE) {
            ink.roomForNotes(true);
            hintOnce("noteHint", "Toca para poner una nota, dentro o fuera de la hoja. Su punto oscuro saca una flecha");
        } else if (t != InkView.NOTE && ink.tool == InkView.NOTE) {
            ink.roomForNotes(false);
        }
        if (t == InkView.LINK) hintOnce("neuronHint", "Rodea algo para marcarlo con palabras clave. De una marca a otra, trázalas para enlazarlas. Tócala para editarla");
        ink.tool = t;
        ink.invalidate();
        if (t != InkView.SHAPE) closeDockNow();
        if (t != InkView.LASSO) ink.clearSelection();
        refreshTools();
    }

    private void refreshTools() {
        refreshToolsInner();
        applyPaletteState();
    }

    /** Dónde y cómo se ven los colores: al lado, dentro de la barra, flotando o plegados. */
    private void applyPaletteState() {
        if (palette == null) return;
        boolean hl = ink.tool == InkView.HIGHLIGHTER;
        boolean coloring = ink.tool == InkView.PEN || ink.tool == InkView.SHAPE || hl || ink.tool == InkView.TEXT || ink.hasSelection();
        if (colorsInBar()) {
            palette.setVisibility(View.GONE);
            if (inlinePalette != null) inlinePalette.setVisibility(coloring ? View.VISIBLE : View.GONE);
            return;
        }
        paletteSep.setVisibility(widths.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
        if (colorsTab != null) {
            colorsTab.setVisibility(coloring ? View.VISIBLE : View.GONE);
            int c = hl ? ink.highlighterColor : ink.penColor;
            GradientDrawable d = Ui.round(paperDark ? PageRenderer.onDark(c) : c, Ui.dp(this, 8));
            d.setStroke(Ui.dp(this, 2), 0xFFFFFFFF);
            colorsDot.setBackground(d);
        }
        if (!coloring) {
            palette.setVisibility(View.GONE);
            return;
        }
        boolean shown = !fold || paletteOpen || (palAnim != null && palAnim.isRunning());
        palette.setVisibility(shown ? View.VISIBLE : View.INVISIBLE);
    }

    private void refreshToolsInner() {
        pen.setActive(ink.tool == InkView.PEN);
        highlighter.setActive(ink.tool == InkView.HIGHLIGHTER);
        eraser.setActive(ink.tool == InkView.ERASER);
        lasso.setActive(ink.tool == InkView.LASSO);
        text.setActive(ink.tool == InkView.TEXT);
        tape.setActive(ink.tool == InkView.TAPE);
        laser.setActive(ink.tool == InkView.LASER);
        shapes.setActive(ink.tool == InkView.SHAPE);
        note.setActive(ink.tool == InkView.NOTE);
        neuron.setActive(ink.tool == InkView.LINK);
        for (int i = 0; i < CIRCUIT_KINDS.length; i++) circuitButtons[i].setActive(ink.shapeKind == CIRCUIT_KINDS[i]);
        shapeBar.setVisibility(ink.tool == InkView.SHAPE ? View.VISIBLE : View.GONE);
        for (int i = 0; i < SHAPE_KINDS.length; i++) shapeButtons[i].setActive(ink.shapeKind == SHAPE_KINDS[i]);
        autoShapeButton.setActive(ink.autoShapes);
        finger.setActive(ink.fingerDraws);
        colors.removeAllViews();
        widths.removeAllViews();
        boolean hl = ink.tool == InkView.HIGHLIGHTER;
        boolean coloring = ink.tool == InkView.PEN || ink.tool == InkView.SHAPE || hl || ink.tool == InkView.TEXT || ink.hasSelection();
        palette.setVisibility(coloring ? View.VISIBLE : View.GONE);
        if (!coloring) return;
        int currentColor = hl ? ink.highlighterColor : ink.penColor;
        ArrayList<Integer> list = new ArrayList<>();
        for (int c : hl ? HL_COLORS : PEN_COLORS) list.add(c);
        if (!hl) list.add(customColor);
        for (int c : list) {
            View sw = new View(this);
            int size = Ui.dp(this, 26);
            GradientDrawable d = Ui.round(paperDark ? PageRenderer.onDark(c) : c, size / 2f);
            boolean on = c == currentColor && !ink.hasSelection();
            d.setStroke(Ui.dp(this, on ? 3 : 1), on ? (paperDark ? 0xFF60A5FA : 0xFFFFFFFF) : fg & 0xFFFFFF | 0x33000000);
            sw.setBackground(d);
            if (on) sw.setElevation(Ui.dp(this, 3));
            cell(colors, sw, size, "Color " + Ink.hex(c), v -> pickColor(c, hl));
        }
        // «+»: color a medida.
        TextView plus = Ui.text(this, "+", 20, fg, true);
        plus.setGravity(Gravity.CENTER);
        FrameLayout f = new FrameLayout(this);
        f.addView(plus, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        f.setContentDescription("Color a medida");
        f.setOnClickListener(v -> customColorDialog(hl));
        colors.addView(f, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        if (ink.hasSelection() || ink.tool == InkView.TEXT) {
            widths.setVisibility(View.GONE);
            return;
        }
        widths.setVisibility(View.VISIBLE);
        float[] sizes = hl ? HL_WIDTHS : PEN_WIDTHS;
        float currentWidth = hl ? ink.highlighterWidth : ink.penWidth;
        for (int i = 0; i < sizes.length; i++) {
            final float w = sizes[i];
            View sw = new View(this);
            int size = Ui.dp(this, 6 + 6 * i);
            sw.setBackground(Ui.round(w == currentWidth ? fg : fg & 0xFFFFFF | 0x66000000, size / 2f));
            cell(widths, sw, size, new String[] {"Fino", "Medio", "Grueso"}[i], v -> {
                if (hl) ink.highlighterWidth = w;
                else ink.penWidth = w;
                prefs.edit().putFloat(hl ? "hlWidth" : "penWidth", w).apply();
                if (fold && paletteOpen && !colorsInBar()) animatePalette(false, true);
                refreshTools();
            });
        }
    }

    private void pickColor(int c, boolean hl) {
        if (fold && paletteOpen && !colorsInBar()) animatePalette(false, true);
        if (ink.hasSelection()) {
            ink.recolorSelection(c);
            refreshTools();
            return;
        }
        if (hl) ink.highlighterColor = c;
        else ink.penColor = c;
        prefs.edit().putInt(hl ? "hlColor" : "penColor", c).apply();
        refreshTools();
    }

    /** Una muestra centrada en una casilla tocable de 44 dp. */
    private void cell(LinearLayout parent, View swatch, int size, String label, View.OnClickListener click) {
        FrameLayout f = new FrameLayout(this);
        f.addView(swatch, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        f.setContentDescription(label);
        f.setOnClickListener(click);
        parent.addView(f, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
    }

    // ---- Opciones de herramientas ----

    private void chooseKind() {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < PEN_KINDS.length; i++) {
            String kind = PEN_KINDS[i];
            tiles.add(new OptionDock.Tile(new PenSample(this, kind, ink.penColor), PEN_NAMES[i], kind.equals(ink.penKind), () -> {
                ink.penKind = kind;
                prefs.edit().putString("penKind", kind).apply();
            }));
        }
        // Los lápices del usuario: trazo, color y grosor guardados con un nombre.
        org.json.JSONArray mine = myPens();
        for (int i = 0; i < mine.length(); i++) {
            org.json.JSONObject o = mine.optJSONObject(i);
            if (o == null) continue;
            int at = i;
            String kind = o.optString("kind", Ink.PEN);
            int color = o.optInt("color", ink.penColor);
            float width = (float) o.optDouble("width", ink.penWidth);
            boolean on = kind.equals(ink.penKind) && color == ink.penColor && width == ink.penWidth;
            tiles.add(new OptionDock.Tile(new PenSample(this, kind, color, width), "★ " + o.optString("name", "Mi lápiz"), on, () -> {
                ink.penKind = kind;
                ink.penColor = color;
                ink.penWidth = width;
                if (ink.tool != InkView.PEN) setTool(InkView.PEN);
                prefs.edit().putString("penKind", kind).putInt("penColor", color).putFloat("penWidth", width).apply();
                refreshTools();
            }));
        }
        tiles.add(new OptionDock.Tile(glyph(IconView.PLUS), "Crear lápiz", false, () -> penEditor(-1)).stays());
        if (mine.length() > 0) tiles.add(new OptionDock.Tile(glyph(IconView.SLIDERS), "Editar mis lápices", false, this::editPens).stays());
        dock.tiles("Tipo de lápiz", tiles);
    }

    private org.json.JSONArray myPens() {
        try {
            return new org.json.JSONArray(prefs.getString("myPens", "[]"));
        } catch (org.json.JSONException e) {
            return new org.json.JSONArray();
        }
    }

    private void editPens() {
        org.json.JSONArray mine = myPens();
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < mine.length(); i++) {
            org.json.JSONObject o = mine.optJSONObject(i);
            int at = i;
            tiles.add(new OptionDock.Tile(new PenSample(this, o.optString("kind"), o.optInt("color"), (float) o.optDouble("width", 3.5)), o.optString("name"), false, () -> penEditor(at)).stays());
        }
        dock.tiles("Toca uno para cambiarlo", tiles);
    }

    /** Crear (at = -1) o cambiar un lápiz propio: base, grosor, color, opacidad y nombre, con muestra en vivo. */
    private void penEditor(int at) {
        org.json.JSONArray mine = myPens();
        org.json.JSONObject start = at >= 0 ? mine.optJSONObject(at) : null;
        String[] kind = {start != null ? start.optString("kind", Ink.PEN) : ink.penKind};
        int[] color = {start != null ? start.optInt("color") : ink.penColor};
        float[] width = {start != null ? (float) start.optDouble("width", 3.5) : ink.penWidth};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 8));
        android.widget.EditText name = dock.field(start != null ? start.optString("name") : "", "Nombre: Repaso azul, Firma, Títulos…");
        box.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout sampleBox = new FrameLayout(this);
        box.addView(sampleBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 110)));
        Runnable[] redraw = new Runnable[1];
        redraw[0] = () -> {
            sampleBox.removeAllViews();
            PenSample sample = new PenSample(this, kind[0], color[0], width[0]);
            sampleBox.addView(sample, new FrameLayout.LayoutParams(Ui.dp(this, 110), Ui.dp(this, 110), Gravity.CENTER));
        };
        redraw[0].run();
        LinearLayout kinds = new LinearLayout(this);
        android.widget.HorizontalScrollView ks = new android.widget.HorizontalScrollView(this);
        ks.setHorizontalScrollBarEnabled(false);
        ks.addView(kinds);
        ArrayList<TextView> chips = new ArrayList<>();
        for (int i = 0; i < PEN_KINDS.length; i++) {
            String k = PEN_KINDS[i];
            TextView c = dock.chip(PEN_NAMES[i], false);
            dock.setChip(c, k.equals(kind[0]));
            c.setOnClickListener(v -> {
                kind[0] = k;
                for (int j = 0; j < chips.size(); j++) dock.setChip(chips.get(j), PEN_KINDS[j].equals(k));
                redraw[0].run();
            });
            chips.add(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 42));
            lp.rightMargin = Ui.dp(this, 6);
            kinds.addView(c, lp);
        }
        box.addView(ks);
        TextView wl = dock.message("Grosor");
        box.addView(wl);
        SeekBar wb = new SeekBar(this);
        wb.setMax(200);
        wb.setProgress(Math.round((width[0] - 0.5f) * 10));
        box.addView(wb);
        TextView ol = dock.message("Opacidad");
        box.addView(ol);
        SeekBar ob = new SeekBar(this);
        ob.setMax(100);
        ob.setProgress(Math.round((color[0] >>> 24) * 100 / 255f));
        box.addView(ob);
        LinearLayout swatches = new LinearLayout(this);
        int[] palette = {0xFF111827, 0xFF2563EB, 0xFFDC2626, 0xFF16A34A, 0xFF7C3AED, 0xFFEA580C, 0xFF0EA5E9, 0xFFDB2777, 0xFFCA8A04, 0xFF6B7280, customColor};
        for (int c : palette) {
            View sw = new View(this);
            sw.setBackground(Ui.round(c, Ui.dp(this, 15)));
            sw.setOnClickListener(v -> {
                color[0] = (color[0] & 0xFF000000) | (c & 0xFFFFFF);
                redraw[0].run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30));
            lp.setMargins(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 4));
            swatches.addView(sw, lp);
        }
        android.widget.HorizontalScrollView ss = new android.widget.HorizontalScrollView(this);
        ss.setHorizontalScrollBarEnabled(false);
        ss.addView(swatches);
        box.addView(ss);
        SeekBar.OnSeekBarChangeListener live = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (s == wb) width[0] = 0.5f + v / 10f;
                else color[0] = (Math.max(25, Math.round(v * 2.55f)) << 24) | (color[0] & 0xFFFFFF);
                redraw[0].run();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        };
        wb.setOnSeekBarChangeListener(live);
        ob.setOnSeekBarChangeListener(live);
        ArrayList<OptionDock.Action> actions = new ArrayList<>();
        if (at >= 0) actions.add(new OptionDock.Action("Borrar", false, () -> {
            mine.remove(at);
            prefs.edit().putString("myPens", mine.toString()).apply();
        }));
        else actions.add(new OptionDock.Action("Cancelar", false, null));
        actions.add(new OptionDock.Action("Guardar y usar", true, () -> {
            try {
                String n = name.getText().toString().trim();
                org.json.JSONObject o = new org.json.JSONObject().put("name", n.isEmpty() ? "Mi lápiz " + (mine.length() + 1) : n)
                        .put("kind", kind[0]).put("color", color[0]).put("width", width[0]);
                if (at >= 0) mine.put(at, o);
                else mine.put(o);
                prefs.edit().putString("myPens", mine.toString()).putString("penKind", kind[0]).putInt("penColor", color[0]).putFloat("penWidth", width[0]).apply();
                ink.penKind = kind[0];
                ink.penColor = color[0];
                ink.penWidth = width[0];
                if (ink.tool != InkView.PEN) setTool(InkView.PEN);
                refreshTools();
                Ui.toast(this, "Lápiz guardado: lo tienes en «Tipo de lápiz»");
            } catch (org.json.JSONException ignored) {
            }
        }));
        dock.show(at >= 0 ? "Cambiar mi lápiz" : "Crear mi lápiz", box, actions.toArray(new OptionDock.Action[0]));
    }

    private View glyph(int icon) {
        return dock.glyph(icon);
    }

    /** Muestra de cada lápiz: su trazo de verdad. */
    private static final class PenSample extends View {
        private final Ink.Stroke stroke;
        private final PageRenderer renderer;

        PenSample(android.content.Context c, String kind, int color) {
            this(c, kind, color, 5);
        }

        PenSample(android.content.Context c, String kind, int color, float width) {
            super(c);
            renderer = new PageRenderer(c);
            renderer.setDark(paperDark);
            stroke = new Ink.Stroke("muestra", kind, color, width * 1.4f);
            for (int i = 0; i <= 40; i++) {
                float t = i / 40f;
                stroke.add(8 + 84 * t, 50 - 30 * (float) Math.sin(t * Math.PI * 2), 0.25f + 0.75f * (float) Math.sin(t * Math.PI));
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float k = getWidth() / 100f;
            c.save();
            c.scale(k, k);
            renderer.drawStroke(c, stroke, false);
            c.restore();
        }
    }

    /** Muestra de una plantilla: la hoja en pequeño. */
    static final class TemplateSample extends View {
        private final String background;
        private final PageRenderer renderer;
        private final android.graphics.Paint paper = new android.graphics.Paint();

        TemplateSample(android.content.Context c, String background) {
            super(c);
            this.background = background;
            renderer = new PageRenderer(c);
            renderer.setDark(paperDark);
            paper.setColor(paperDark ? PageRenderer.DARK_SHEET : 0xFFFFFFFF);
            setElevation(Ui.dp(c, 1));
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), w = h * Ink.W / Ink.H, k = h / Ink.H;
            c.save();
            c.translate((getWidth() - w) / 2, 0);
            c.clipRect(0, 0, w, h);
            c.drawRect(0, 0, w, h, paper);
            c.scale(k, k);
            renderer.drawBackground(c, background, k * 0.6f);
            c.restore();
        }
    }

    /** Muestra de una tabla de filas × columnas. */
    private static final class GridSample extends View {
        private final int rows, cols;
        private final android.graphics.Paint line = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        GridSample(android.content.Context c, int rows, int cols) {
            super(c);
            this.rows = rows;
            this.cols = cols;
            line.setStyle(android.graphics.Paint.Style.STROKE);
            line.setColor(fg);
            line.setStrokeWidth(Ui.dp(c, 1));
        }

        @Override
        protected void onDraw(Canvas c) {
            float pad = getWidth() * 0.12f, w = getWidth() - 2 * pad, h = getHeight() - 2 * pad;
            for (int r = 0; r <= rows; r++) c.drawLine(pad, pad + h * r / rows, pad + w, pad + h * r / rows, line);
            for (int k = 0; k <= cols; k++) c.drawLine(pad + w * k / cols, pad, pad + w * k / cols, pad + h, line);
        }
    }

    private IconView ruler;

    private void guideMenu() {
        String[] names = {"Regla", "Escuadra 45°", "Escuadra 30° y 60°", "Transportador", "Compás"};
        int[] kinds = {Guides.RULER, Guides.SQUARE45, Guides.SQUARE60, Guides.PROTRACTOR, Guides.COMPASS};
        int[] icons = {IconView.RULER, IconView.S_TRIANGLE, IconView.S_TRIANGLE, IconView.S_ELLIPSE, IconView.S_CROSS};
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            int k = kinds[i];
            tiles.add(new OptionDock.Tile(glyph(icons[i]), names[i], ink.guides.kind == k, () -> {
                ink.guides.set(k, ink.getWidth(), ink.getHeight());
                ruler.setActive(true);
                if (ink.tool != InkView.PEN && ink.tool != InkView.HIGHLIGHTER) setTool(InkView.PEN);
                ink.invalidate();
                hintOnce("guideHint", k == Guides.COMPASS
                        ? "Arrastra la aguja con el dedo. Con el lápiz, la distancia a la aguja es el radio: el trazo sigue el círculo"
                        : "Un dedo la mueve, dos la giran. Empieza a escribir cerca de un borde y la línea sale recta");
            }));
        }
        if (ink.guides.active()) tiles.add(new OptionDock.Tile(glyph(IconView.TRASH), "Quitar", false, () -> {
            ink.guides.set(Guides.NONE, 0, 0);
            ruler.setActive(false);
            ink.invalidate();
        }));
        dock.tiles("Guías para dibujar", tiles);
    }

    private void chooseEraser() {
        String[] modes = {"Trazos enteros", "Sólo lo que toca", "Sólo el resaltador", "Lo que rodees"};
        int[] icons = {IconView.ERASER, IconView.ERASER, IconView.HIGHLIGHTER, IconView.LASSO};
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < modes.length; i++) {
            int mode = i;
            tiles.add(new OptionDock.Tile(glyph(icons[i]), modes[i], ink.eraseMode == i, () -> {
                ink.eraseMode = mode;
                prefs.edit().putInt("eraseMode", mode).apply();
            }));
        }
        tiles.add(new OptionDock.Tile(glyph(IconView.TRASH), "Vaciar esta página", false, () ->
                dock.confirm("¿Vaciar la página " + (index + 1) + "?", "Se puede deshacer.", "Vaciar", () -> ink.clearVisiblePage())).stays());
        dock.tiles("Goma", tiles);
    }

    // ---- Figuras ----

    /** Una figura de la galería dibujada en pequeño. */
    private static final class ShapePreview extends View {
        private final ShapeLibrary.Shape shape;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        ShapePreview(android.content.Context c, ShapeLibrary.Shape s) {
            super(c);
            shape = s;
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float pad = getWidth() * 0.16f, box = getWidth() - 2 * pad;
            float w = shape.aspect >= 1 ? box : box * shape.aspect, h = w / shape.aspect;
            float x0 = (getWidth() - w) / 2, y0 = (getHeight() - h) / 2;
            for (int pass = 0; pass < 2; pass++) {
                paint.setColor(pass == 0 ? fg & 0xFFFFFF | 0x66000000 : fg);
                paint.setStrokeWidth(pass == 0 ? 1.5f : 3);
                for (float[] l : pass == 0 ? shape.faint : shape.lines) {
                    android.graphics.Path p = new android.graphics.Path();
                    for (int i = 0; i + 1 < l.length; i += 2) {
                        if (i == 0) p.moveTo(x0 + l[i] * w, y0 + l[i + 1] * h);
                        else p.lineTo(x0 + l[i] * w, y0 + l[i + 1] * h);
                    }
                    c.drawPath(p, paint);
                }
            }
        }
    }

    // ---- Botones del S Pen ----

    @Override
    public void onPenButton(boolean twice) {
        int action = prefs.getInt(twice ? "penDouble" : "penClick", twice ? 1 : 0);
        switch (action) {
            case 0:
                setTool(ink.tool == InkView.ERASER ? InkView.PEN : InkView.ERASER);
                Ui.toast(this, ink.tool == InkView.ERASER ? "Goma" : "Pluma");
                break;
            case 1:
                ink.undo();
                break;
            case 2:
                setTool(InkView.LASSO);
                break;
            case 3:
                setTool(ink.tool == InkView.LASER ? InkView.PEN : InkView.LASER);
                break;
            case 4:
                setTool(ink.tool == InkView.HIGHLIGHTER ? InkView.PEN : InkView.HIGHLIGHTER);
                break;
            case 6:
                ink.redo();
                break;
            case 7:
                ink.scrollToPage(index + 1, true);
                break;
            case 8:
                ink.scrollToPage(index - 1, true);
                break;
            case 9:
                addPage();
                break;
            case 10:
                setTool(InkView.SHAPE);
                break;
            case 11:
                setTool(InkView.TEXT);
                break;
            case 12:
                setTool(InkView.NOTE);
                break;
            case 13:
                present();
                break;
            case 14:
                finger.performClick();
                break;
            case 15: {
                int at = 0;
                for (int i = 0; i < PEN_COLORS.length; i++) if (PEN_COLORS[i] == ink.penColor) at = i;
                pickColor(PEN_COLORS[(at + 1) % PEN_COLORS.length], false);
                break;
            }
            case 16: {
                int at = 0;
                for (int i = 0; i < PEN_WIDTHS.length; i++) if (PEN_WIDTHS[i] == ink.penWidth) at = i;
                ink.penWidth = PEN_WIDTHS[(at + 1) % PEN_WIDTHS.length];
                prefs.edit().putFloat("penWidth", ink.penWidth).apply();
                refreshTools();
                Ui.toast(this, new String[] {"Fino", "Medio", "Grueso"}[(at + 1) % PEN_WIDTHS.length]);
                break;
            }
            case 17:
                togglePaper();
                break;
            case 18:
                setTool(InkView.LINK);
                break;
            default:
        }
    }

    private void penButtons() {
        String[] hold = {"Borrar", "Lazo (seleccionar)", "Resaltador", "Nada"};
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        tiles.add(new OptionDock.Tile(glyph(IconView.PEN), "Mantener: " + hold[ink.buttonHold], false, () -> {
            ArrayList<OptionDock.Tile> options = new ArrayList<>();
            for (int k = 0; k < hold.length; k++) {
                int choice = k;
                options.add(new OptionDock.Tile(glyph(IconView.PEN), hold[k], ink.buttonHold == k, () -> {
                    ink.buttonHold = choice;
                    prefs.edit().putInt("buttonHold", choice).apply();
                    penButtons();
                }).stays());
            }
            dock.tiles("Mantener el botón mientras escribes", options);
        }).stays());
        tiles.add(buttonTile("penClick", 0, "Un clic", " con el lápiz en el aire", PEN_ACTIONS, PEN_ICONS));
        tiles.add(buttonTile("penDouble", 1, "Doble clic", " con el lápiz en el aire", PEN_ACTIONS, PEN_ICONS));
        tiles.add(buttonTile("slideClick", 0, "Al presentar, clic", "", SLIDE_ACTIONS, SLIDE_ICONS));
        tiles.add(buttonTile("slideDouble", 1, "Al presentar, doble", "", SLIDE_ACTIONS, SLIDE_ICONS));
        tiles.add(new OptionDock.Tile(glyph(IconView.PLAY), "Presentar a distancia", false, this::remoteHelp).stays());
        dock.tiles("Botones del lápiz", tiles);
    }

    private OptionDock.Tile buttonTile(String key, int fallback, String head, String tail, String[] names, int[] icons) {
        int now = Math.min(prefs.getInt(key, fallback), names.length - 1);
        return new OptionDock.Tile(glyph(icons[now]), head + ": " + names[now], false, () -> {
            ArrayList<OptionDock.Tile> options = new ArrayList<>();
            for (int k = 0; k < names.length; k++) {
                int choice = k;
                options.add(new OptionDock.Tile(glyph(icons[k]), names[k], now == k, () -> {
                    prefs.edit().putInt(key, choice).apply();
                    penButtons();
                }).stays());
            }
            dock.tiles(head + tail, options);
        }).stays();
    }

    /** Lejos de la pantalla el botón va por Bluetooth, y Samsung lo deja apagado para cada app nueva. */
    private void remoteHelp() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, 0, pad, Ui.dp(this, 8));
        box.addView(Ui.text(this, "Cerca de la pantalla ya funciona. Para usar el botón de lejos, activa una vez «Sync Tablet» dentro de Acciones aéreas, en los ajustes del S Pen. "
                + "Después, al presentar, cada clic hace lo que elegiste aquí y mover el lápiz a un lado también cambia de diapositiva.", 15, fg, false));
        dock.show("Presentar a distancia", box, new OptionDock.Action("Cerrar", false, null), new OptionDock.Action("Abrir ajustes del S Pen", true, () -> {
            // Acciones aéreas pide un permiso de sistema; esta es la pantalla anterior.
            try {
                startActivity(new Intent("com.samsung.android.service.aircommand.action.SPEN_SETTINGS"));
            } catch (android.content.ActivityNotFoundException | SecurityException e) {
                Ui.toast(this, "Búscalas en Ajustes: Funciones avanzadas, S Pen, Acciones aéreas");
            }
        }));
    }

    private void chooseTemplate() {
        Ink.Page p = ink.visiblePage();
        if (p == null) return;
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < BACKGROUNDS.length; i++) {
            String bg = BACKGROUNDS[i];
            tiles.add(new OptionDock.Tile(new TemplateSample(this, bg), BACKGROUND_NAMES[i], bg.equals(p.background), () -> ink.setBackground(bg)));
        }
        dock.tiles("Plantilla de la página " + (index + 1), tiles);
    }

    /** Selector de color a medida: anillo de tonos y triángulo, como en Krita. */
    private void customColorDialog(boolean hl) {
        int start = hl ? ink.highlighterColor | 0xFF000000 : ink.penColor;
        ColorWheel.show(this, dock, "Color a medida", start, fg, fgMuted, c -> {
            if (hl) {
                // El resaltador guarda su transparencia.
                pickColor(c & 0xFFFFFF | (ink.highlighterColor & 0xFF000000), true);
            } else {
                customColor = c;
                prefs.edit().putInt("customColor", customColor).apply();
                pickColor(customColor, false);
            }
            refreshTools();
        });
    }

    // ---- Texto ----

    /** Botón del dock con el símbolo del componente. */
    private static final class ComponentButton extends View {
        private final float[] xy;
        private final boolean free;
        private final android.graphics.Paint line = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint disc = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private boolean active;

        ComponentButton(android.content.Context c, int kind, String name) {
            super(c);
            free = kind == ShapeTools.CIRCUIT_FREE;
            boolean three = kind >= ShapeTools.NPN;
            xy = free ? new float[0] : new ShapeTools.Spec(kind, three ? 8 : 6, 24, kind == ShapeTools.GROUND ? 26 : three ? 38 : 42, 24, 0).points();
            if (kind == ShapeTools.GROUND) {
                float[] g = new ShapeTools.Spec(kind, 24, 8, 24, 30, 0).points();
                System.arraycopy(g, 0, xy, 0, Math.min(g.length, xy.length));
            }
            line.setStyle(android.graphics.Paint.Style.STROKE);
            line.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            line.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            disc.setColor(paperDark ? 0xFFE5E7EB : Ui.ACCENT_DEEP);
            setContentDescription(name);
            setClickable(true);
            setFocusable(true);
        }

        void setActive(boolean a) {
            if (a != active) {
                active = a;
                invalidate();
            }
        }

        @Override
        protected void onMeasure(int w, int h) {
            int s = Ui.dp(getContext(), 44);
            setMeasuredDimension(s, s);
        }

        @Override
        protected void onDraw(Canvas c) {
            float k = getWidth() / 48f;
            if (active) c.drawCircle(getWidth() / 2f, getHeight() / 2f, getWidth() * 0.46f, disc);
            line.setColor(active ? (paperDark ? 0xFF111827 : 0xFFFFFFFF) : fg);
            line.setStrokeWidth(1.7f * k);
            c.save();
            c.scale(k, k);
            if (free) {
                // Un trazo a mano.
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo(10, 30); p.cubicTo(16, 12, 20, 36, 26, 20); p.cubicTo(29, 12, 34, 28, 38, 18);
                c.drawPath(p, line);
            } else {
                for (int i = 2; i + 1 < xy.length; i += 2) {
                    if (Float.isNaN(xy[i]) || Float.isNaN(xy[i - 2])) continue;
                    c.drawLine(xy[i - 2], xy[i - 1], xy[i], xy[i + 1], line);
                }
            }
            c.restore();
        }
    }

    @Override
    public void onMarksLinked() {
        Ui.toast(this, "Enlazadas");
    }

    /** Palabras clave de una marca: las sugiere lo escrito ahí y lo que ya usaste. */
    @Override
    public void onMarkRequest(int page, Ink.Mark mark, float x, float y, float w, float h) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, 0, pad, Ui.dp(this, 8));
        EditText field = dock.field(mark != null ? String.join(", ", mark.words) : "", "Palabras clave, separadas por coma");
        box.addView(field);
        java.util.LinkedHashSet<String> ideas = new java.util.LinkedHashSet<>(Topics.keywords(ink.textNear(page, x, y, w, h), 6));
        java.util.Set<String> used = prefs.getStringSet("markWords", java.util.Collections.emptySet());
        ideas.addAll(used);
        if (!ideas.isEmpty()) {
            LinearLayout chips = new LinearLayout(this);
            int shown = 0;
            for (String idea : ideas) {
                if (shown++ == 12) break;
                TextView chip = dock.chip(idea, false);
                chip.setOnClickListener(v -> {
                    String now = field.getText().toString().trim();
                    field.setText(now.isEmpty() ? idea : now + ", " + idea);
                    field.setSelection(field.length());
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = Ui.dp(this, 6);
                chips.addView(chip, lp);
            }
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.addView(chips);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = Ui.dp(this, 8);
            box.addView(scroll, slp);
        }
        OptionDock.Action keep = new OptionDock.Action("Guardar", true, () -> {
            String words = field.getText().toString();
            ink.putMark(page, mark, x, y, w, h, words);
            java.util.HashSet<String> all = new java.util.HashSet<>(prefs.getStringSet("markWords", java.util.Collections.emptySet()));
            for (String word : words.split(",")) if (!word.trim().isEmpty()) all.add(word.trim());
            prefs.edit().putStringSet("markWords", all).apply();
        });
        if (mark == null) dock.show("Marca nueva", box, new OptionDock.Action("Cancelar", false, null), keep);
        else dock.show("Marca", box, new OptionDock.Action("Quitar", false, () -> ink.removeMark(page, mark)), keep);
        dock.focus(field);
    }

    @Override
    public void onNoteRequest(int page, Ink.Note item, float x, float y) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 18);
        box.setPadding(pad, 0, pad, Ui.dp(this, 8));
        EditText field = dock.field(item != null ? item.text : "", "Escribe tu nota");
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setMinLines(3);
        field.setGravity(Gravity.TOP);
        box.addView(field);
        LinearLayout swatches = new LinearLayout(this);
        swatches.setGravity(Gravity.CENTER);
        swatches.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        int[] chosen = {item != null ? item.color : prefs.getInt("noteColor", Ink.NOTE_COLORS[0])};
        ArrayList<View> dots = new ArrayList<>();
        int size = Ui.dp(this, 40);
        for (int c : Ink.NOTE_COLORS) {
            View dot = new View(this);
            dot.setBackground(Ui.round(c, size / 2));
            dot.setElevation(Ui.dp(this, 2));
            dot.setScaleX(c == chosen[0] ? 1.1f : 0.8f);
            dot.setScaleY(c == chosen[0] ? 1.1f : 0.8f);
            dot.setOnClickListener(v -> {
                chosen[0] = c;
                prefs.edit().putInt("noteColor", c).apply();
                for (View o : dots) o.animate().scaleX(o == v ? 1.1f : 0.8f).scaleY(o == v ? 1.1f : 0.8f).setDuration(160).start();
            });
            dots.add(dot);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            swatches.addView(dot, lp);
        }
        box.addView(swatches);
        OptionDock.Action save = new OptionDock.Action("Guardar", true, () -> ink.putNote(page, item, x, y, field.getText().toString(), chosen[0]));
        OptionDock.Action cancel = new OptionDock.Action("Cancelar", false, null);
        if (item != null) {
            dock.show("Nota", box, new OptionDock.Action("Borrar", false, () -> ink.putNote(page, item, x, y, "", chosen[0])), cancel, save);
        } else {
            dock.show("Nota nueva", box, cancel, save);
        }
        dock.focus(field);
    }

    @Override
    public void onValueRequest(int page, Ink.Stroke component, String current) {
        EditText field = dock.field(current, "10 kΩ");
        field.setSingleLine(true);
        field.setSelection(current.length());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 18);
        box.setPadding(pad, 0, pad, Ui.dp(this, 8));
        box.addView(field);
        LinearLayout units = new LinearLayout(this);
        units.setGravity(Gravity.CENTER);
        units.setPadding(0, Ui.dp(this, 8), 0, 0);
        for (String u : new String[] {"Ω", "kΩ", "MΩ", "µF", "nF", "mH", "V", "A"}) {
            TextView b = dock.chip(u, false);
            b.setOnClickListener(v -> field.getText().insert(field.getSelectionStart(), " " + u));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            units.addView(b, lp);
        }
        HorizontalScrollView unitScroll = new HorizontalScrollView(this);
        unitScroll.setHorizontalScrollBarEnabled(false);
        unitScroll.addView(units);
        box.addView(unitScroll);
        dock.show("Valor", box, new OptionDock.Action("Cancelar", false, null),
                new OptionDock.Action("Guardar", true, () -> ink.setValue(page, component, field.getText().toString())));
        dock.focus(field);
    }

    @Override
    public void onTextRequest(int page, Ink.Item item, float x, float y) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 18);
        box.setPadding(pad, 0, pad, Ui.dp(this, 8));
        EditText field = dock.field(item != null ? item.text : "", "Escribe aquí");
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setMinLines(3);
        field.setGravity(Gravity.TOP);
        box.addView(field);
        float[] sizes = {20, 28, 40, 56};
        String[] sizeNames = {"Pequeño", "Normal", "Grande", "Título"};
        final float[] chosen = {item != null ? item.size : 28};
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, Ui.dp(this, 8), 0, 0);
        ArrayList<TextView> buttons = new ArrayList<>();
        for (int i = 0; i < sizes.length; i++) {
            TextView b = dock.chip(sizeNames[i], false);
            final float s = sizes[i];
            b.setOnClickListener(v -> {
                chosen[0] = s;
                for (TextView o : buttons) dock.setChip(o, o == v);
            });
            dock.setChip(b, Math.abs(s - chosen[0]) < 1);
            buttons.add(b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            row.addView(b, lp);
        }
        box.addView(row);
        OptionDock.Action save = new OptionDock.Action("Guardar", true, () -> ink.putText(page, item, field.getText().toString(), chosen[0],
                item != null ? item.color : ink.penColor, x, y));
        OptionDock.Action cancel = new OptionDock.Action("Cancelar", false, null);
        if (item != null) {
            dock.show("Editar texto", box, new OptionDock.Action("Borrar", false, () -> ink.putText(page, item, "", item.size, item.color, x, y)), cancel, save);
        } else {
            dock.show("Texto nuevo", box, cancel, save);
        }
        dock.focus(field);
    }

    // ---- Páginas ----

    private void showTitle() {
        title.setText(notebook.title + " · " + (index + 1) + "/" + notebook.pages.size());
    }

    private void addPage() {
        save();
        Ink.Page p = store.addPage(notebook);
        sync.sendNotebook(notebook);
        sync.sendPage(p);
        ink.appendPage(p);
        showTitle();
    }

    /** Página nueva a la derecha de la que se ve (o debajo de ella, en su sitio). */
    private void insertPage(boolean right) {
        save();
        int at = index + 1;
        if (!right) {
            // Debajo: tras las que están a su lado en la misma fila.
            while (at < notebook.pages.size() && notebook.right.contains(notebook.pages.get(at))) at++;
        }
        Ink.Page p = store.insertPage(notebook, at, right);
        sync.sendNotebook(notebook);
        sync.sendPage(p);
        loadPages(at);
        Ui.toast(this, right ? "Página a la derecha" : "Página debajo");
    }

    private void addPageMenu() {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        tiles.add(new OptionDock.Tile(new PlaceIcon(this, false, fg), "Debajo", false, () -> insertPage(false)));
        tiles.add(new OptionDock.Tile(new PlaceIcon(this, true, fg), "A la derecha", false, () -> insertPage(true)));
        tiles.add(new OptionDock.Tile(glyph(IconView.PLUS), "Al final", false, this::addPage));
        dock.tiles("Página nueva", tiles);
    }

    /** Dibujo de dónde cae la página nueva: debajo o a la derecha. */
    private static final class PlaceIcon extends View {
        private final boolean right;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int color;

        PlaceIcon(android.content.Context c, boolean right, int color) {
            super(c);
            this.right = right;
            this.color = color;
        }

        @Override
        protected void onDraw(Canvas c) {
            float u = Math.min(getWidth(), getHeight()) / 10f, cx = getWidth() / 2f, cy = getHeight() / 2f;
            float w = 2.6f * u, h = 3.6f * u;
            float ax = right ? cx - w - 0.3f * u : cx - w / 2, ay = right ? cy - h / 2 : cy - h - 0.3f * u;
            float bx = right ? cx + 0.3f * u : ax, by = right ? ay : cy + 0.3f * u;
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(color & 0xFFFFFF | 0x33000000);
            c.drawRoundRect(ax, ay, ax + w, ay + h, u / 3, u / 3, paint);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(u / 3);
            paint.setColor(color);
            paint.setPathEffect(new android.graphics.DashPathEffect(new float[] {u / 2, u / 3}, 0));
            c.drawRoundRect(bx, by, bx + w, by + h, u / 3, u / 3, paint);
            paint.setPathEffect(null);
            float px = bx + w / 2, py = by + h / 2;
            c.drawLine(px - u * 0.7f, py, px + u * 0.7f, py, paint);
            c.drawLine(px, py - u * 0.7f, px, py + u * 0.7f, paint);
        }
    }

    private void pageMenu() {
        Ink.Page visible = ink.visiblePage();
        String text = visible == null ? null : visible.bgText;
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        tiles.add(new OptionDock.Tile(new PlaceIcon(this, false, fg), "Página debajo", false, () -> insertPage(false)));
        tiles.add(new OptionDock.Tile(new PlaceIcon(this, true, fg), "Página a la derecha", false, () -> insertPage(true)));
        tiles.add(new OptionDock.Tile(glyph(IconView.FILE), "Exportar PDF", false, this::exportPdfPicker));
        tiles.add(new OptionDock.Tile(glyph(IconView.PAGES), "Ordenar páginas", false, this::pageManager).stays());
        tiles.add(new OptionDock.Tile(glyph(IconView.PAGE), "Plantilla", false, this::chooseTemplate).stays());
        tiles.add(new OptionDock.Tile(glyph(IconView.MOON), paperDark ? "Hoja clara" : "Hoja oscura", paperDark, this::togglePaper));
        tiles.add(new OptionDock.Tile(glyph(IconView.PEN), "Botones del lápiz", false, this::penButtons).stays());
        boolean full = Ui.fullscreen(this);
        tiles.add(new OptionDock.Tile(glyph(IconView.EXPAND), full ? "Salir de pantalla completa" : "Pantalla completa", full, () -> Ui.setFullscreen(this, !full)));
        if (text != null) tiles.add(new OptionDock.Tile(glyph(IconView.COPY), "Copiar el texto", false, () -> copyText(text)));
        tiles.add(new OptionDock.Tile(glyph(IconView.TRASH), "Borrar esta página", false, () -> confirmDeletePage(index, null)).stays());
        dock.tiles("Página " + (index + 1) + " de " + notebook.pages.size(), tiles);
    }

    private void copyText(String text) {
        android.content.ClipboardManager clip = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clip.setPrimaryClip(android.content.ClipData.newPlainText("PDF", text));
        Ui.toast(this, "Texto copiado");
    }

    private void confirmDeletePage(int i, Runnable after) {
        if (notebook.pages.size() <= 1) {
            Ui.toast(this, "Es la única página del cuaderno");
            return;
        }
        dock.confirm("¿Borrar la página " + (i + 1) + "?", "Se borra de la tablet y del PC.", "Borrar", () -> {
            save();
            String id = notebook.pages.get(i);
            if (!store.deletePage(notebook, id)) return;
            dirty.removeIf(p -> p.id.equals(id));
            sync.sendNotebook(notebook);
            sync.send("{\"t\":\"delete_page\",\"page\":\"" + id + "\"}");
            ink.removePage(i);
            showTitle();
            if (after != null) after.run();
        });
    }

    /** Miniaturas de todas las páginas: tocar una la elige; ir, mover, duplicar o borrar. */
    private void pageManager() {
        save();
        final int[] chosen = {index};
        ScrollView scroll = new ScrollView(this);
        GridLayout grid = new GridLayout(this);
        int cols = Math.max(3, getResources().getDisplayMetrics().widthPixels / Ui.dp(this, 170));
        grid.setColumnCount(cols);
        int pad = Ui.dp(this, 12);
        grid.setPadding(pad, pad, pad, pad);
        scroll.addView(grid);
        int tw = Ui.dp(this, 120), th = Math.round(tw * Ink.H / Ink.W);
        ArrayList<View> cells = new ArrayList<>();
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            grid.removeAllViews();
            cells.clear();
            for (int i = 0; i < notebook.pages.size(); i++) {
                Ink.Page p = store.page(notebook.pages.get(i));
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
                ImageView img = new ImageView(this);
                if (p != null) img.setImageBitmap(thumbnail(p, tw, th));
                img.setElevation(Ui.dp(this, 2));
                cell.addView(img, new LinearLayout.LayoutParams(tw, th));
                cell.addView(Ui.text(this, (notebook.right.contains(notebook.pages.get(i)) ? "⇢ " : "") + (i + 1), 14, fgMuted, false));
                final int at = i;
                cell.setOnClickListener(v -> {
                    chosen[0] = at;
                    for (int k = 0; k < cells.size(); k++) {
                        cells.get(k).setBackground(k == at ? Ui.round(0x331F2937, Ui.dp(this, 10)) : null);
                    }
                });
                if (i == chosen[0]) cell.setBackground(Ui.round(0x331F2937, Ui.dp(this, 10)));
                cells.add(cell);
                grid.addView(cell);
            }
        };
        fill[0].run();
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        String[] names = {"◀ Mover", "Mover ▶", "Duplicar", "Borrar", "Al lado ⇄"};
        for (int k = 0; k < names.length; k++) {
            TextView b = dock.chip(names[k], false);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            b.setLayoutParams(blp);
            final int action = k;
            b.setOnClickListener(v -> {
                int i = chosen[0];
                if (action == 0 && i > 0) {
                    java.util.Collections.swap(notebook.pages, i, i - 1);
                    chosen[0] = i - 1;
                } else if (action == 1 && i < notebook.pages.size() - 1) {
                    java.util.Collections.swap(notebook.pages, i, i + 1);
                    chosen[0] = i + 1;
                } else if (action == 2) {
                    Ink.Page copy = store.duplicatePage(notebook, store.page(notebook.pages.get(i)), i + 1);
                    sync.sendPage(copy);
                    chosen[0] = i + 1;
                } else if (action == 4) {
                    String id = notebook.pages.get(i);
                    if (i == 0) return;
                    if (!notebook.right.remove(id)) notebook.right.add(id);
                    Ui.toast(this, notebook.right.contains(id) ? "Va a la derecha de la anterior" : "Va debajo");
                } else if (action == 3) {
                    confirmDeletePage(i, () -> {
                        chosen[0] = Math.min(i, notebook.pages.size() - 1);
                        fill[0].run();
                    });
                    return;
                } else {
                    return;
                }
                store.saveNotebooks();
                sync.sendNotebook(notebook);
                fill[0].run();
            });
            actions.addView(b);
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(actions);
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 380)));
        dock.show("Páginas de «" + notebook.title + "»", box, new OptionDock.Action("Ir a la página", true, null));
        // Orden o páginas cambiados: recargar la vista.
        dock.onClose(() -> loadPages(Math.min(chosen[0], notebook.pages.size() - 1)));
    }

    private Bitmap thumbnail(Ink.Page p, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float k = w / Ink.W;
        c.scale(k, k);
        ink.renderer().drawPage(c, p, k, null, null);
        return b;
    }

    // ---- Fotos y PDF ----

    private void insertMenu() {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        tiles.add(new OptionDock.Tile(glyph(IconView.IMAGE), "Foto de la galería", false, () -> pick("image/*", PICK_IMAGE)));
        tiles.add(new OptionDock.Tile(glyph(IconView.CAMERA), "Hacer una foto", false, this::takePhoto));
        tiles.add(new OptionDock.Tile(glyph(IconView.FILE), "PDF", false, () -> pick("application/pdf", PICK_PDF)));
        if (Native.available()) tiles.add(new OptionDock.Tile(glyph(IconView.SLIDES), "PowerPoint", false, () -> pick(PPTX, PICK_PPTX)));
        tiles.add(new OptionDock.Tile(glyph(IconView.TABLE), "Hoja de cálculo", false, this::askTableSize).stays());
        tiles.add(new OptionDock.Tile(glyph(IconView.GRID_DOC), "Texto o Markdown", false, () -> pick("text/*", PICK_TEXT)));
        tiles.add(new OptionDock.Tile(glyph(IconView.STICKER), "Stickers", false, () -> Stickers.choose(this, dock, 0, this::insertSticker)).stays());
        tiles.add(new OptionDock.Tile(glyph(IconView.PASTE), "Pegar", false, this::paste));
        dock.tiles("Insertar", tiles);
    }

    /** El sticker como imagen PNG (con transparencia) en la página visible. */
    private void insertSticker(String s) {
        new Thread(() -> {
            try {
                Bitmap b = Stickers.render(s, 360);
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.PNG, 100, png);
                String id = store.putAssetBytes(png.toByteArray());
                int w = b.getWidth(), h = b.getHeight();
                float width = s.startsWith("badge:") ? 90f * w / h : 150;
                main.post(() -> {
                    ink.insertImage(id, w, h, width);
                    sync.sendAsset(id);
                    Tutorial.offerOnce(this, dock, "stickers");
                });
            } catch (IOException | RuntimeException e) {
                main.post(() -> Ui.toast(this, "No se pudo poner el sticker"));
            }
        }).start();
    }

    private void pick(String type, int request) {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type);
        startActivityForResult(pick, request);
    }

    /** La cámara guarda la foto en Imágenes/SyncTablet y después se inserta. */
    private void takePhoto() {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, "SyncTablet " + System.currentTimeMillis() + ".jpg");
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= 29) v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SyncTablet");
        photoUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
        try {
            startActivityForResult(cam, TAKE_PHOTO);
        } catch (RuntimeException e) {
            Ui.toast(this, "No hay app de cámara");
        }
    }

    private void askTableSize() {
        int[][] rc = {{3, 3}, {4, 3}, {5, 4}, {8, 4}, {10, 6}};
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (int[] size : rc) {
            tiles.add(new OptionDock.Tile(new GridSample(this, size[0], size[1]), size[0] + " × " + size[1], false, () -> {
                if (ink.tool != InkView.LASSO) setTool(InkView.LASSO);
                ink.insertTable(size[0], size[1]);
                Ui.toast(this, "Con la herramienta de texto (T) tocas la tabla para escribir en ella");
            }));
        }
        dock.tiles("Hoja de cálculo (filas × columnas)", tiles);
    }

    /** Del portapapeles: una imagen se inserta como foto; el texto, como cuadro de texto. */
    private void paste() {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            Ui.toast(this, "El portapapeles está vacío");
            return;
        }
        android.content.ClipData.Item item = clip.getItemAt(0);
        String type = clip.getDescription().getMimeTypeCount() > 0 ? clip.getDescription().getMimeType(0) : "";
        if (item.getUri() != null && (type.startsWith("image/") || String.valueOf(getContentResolver().getType(item.getUri())).startsWith("image/"))) {
            insertImage(item.getUri());
            return;
        }
        CharSequence text = item.coerceToText(this);
        if (text == null || text.toString().trim().isEmpty()) {
            Ui.toast(this, "No hay texto ni imagen que pegar");
            return;
        }
        if (ink.tool != InkView.LASSO) setTool(InkView.LASSO);
        ink.pasteText(text.toString());
    }

    private void importText(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 20000) out.write(buf, 0, n);
            String text = out.toString("UTF-8");
            if (ink.tool != InkView.LASSO) setTool(InkView.LASSO);
            ink.pasteText(text.length() > 20000 ? text.substring(0, 20000) : text);
        } catch (IOException | RuntimeException e) {
            Ui.toast(this, "No se pudo leer el archivo");
        }
    }

    // ---- Hojas de cálculo ----

    @Override
    public void onTableRequest(int page, Ink.Item table) {
        final int[] size = {table.rows, table.cols};
        final String[][] cells = {table.cells.clone()};
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 14);
        box.setPadding(pad, pad / 2, pad, 0);
        GridLayout grid = new GridLayout(this);
        ArrayList<EditText> fields = new ArrayList<>();
        Runnable[] fill = new Runnable[1];
        Runnable collect = () -> {
            for (int i = 0; i < fields.size() && i < cells[0].length; i++) cells[0][i] = fields.get(i).getText().toString();
        };
        fill[0] = () -> {
            grid.removeAllViews();
            fields.clear();
            grid.setColumnCount(size[1] + 1);
            grid.addView(new View(this), new GridLayout.LayoutParams());
            for (int c = 0; c < size[1]; c++) {
                TextView h = Ui.text(this, String.valueOf((char) ('A' + c)), 13, fgMuted, true);
                h.setGravity(Gravity.CENTER);
                grid.addView(h);
            }
            for (int r = 0; r < size[0]; r++) {
                grid.addView(Ui.text(this, String.valueOf(r + 1), 13, fgMuted, true));
                for (int c = 0; c < size[1]; c++) {
                    EditText e = dock.field("", "");
                    e.setSingleLine(true);
                    e.setTextSize(14);
                    e.setHorizontallyScrolling(true);
                    int i = r * size[1] + c;
                    e.setText(i < cells[0].length && cells[0][i] != null ? cells[0][i] : "");
                    fields.add(e);
                    GridLayout.LayoutParams cell = new GridLayout.LayoutParams();
                    // Ancho fijo: una fórmula larga se desliza dentro sin mover las columnas.
                    cell.width = Ui.dp(this, 120);
                    cell.setMargins(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
                    grid.addView(e, cell);
                }
            }
        };
        fill[0].run();
        LinearLayout actions = new LinearLayout(this);
        String[] names = {"+ Fila", "− Fila", "+ Columna", "− Columna"};
        for (int k = 0; k < names.length; k++) {
            TextView b = dock.chip(names[k], false);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), Ui.dp(this, 6));
            b.setLayoutParams(blp);
            final int a = k;
            b.setOnClickListener(v -> {
                collect.run();
                int rows = size[0] + (a == 0 ? 1 : a == 1 ? -1 : 0), cols = size[1] + (a == 2 ? 1 : a == 3 ? -1 : 0);
                if (rows < 1 || cols < 1 || rows > 60 || cols > 20) return;
                String[] next = new String[rows * cols];
                for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
                    next[r * cols + c] = r < size[0] && c < size[1] && r * size[1] + c < cells[0].length ? cells[0][r * size[1] + c] : "";
                }
                size[0] = rows;
                size[1] = cols;
                cells[0] = next;
                fill[0].run();
            });
            actions.addView(b);
        }
        box.addView(actions);
        box.addView(Ui.text(this, "Fórmulas de Excel en español o inglés: =SUMA(A1:A5), =SI(A1>5;\"sí\";\"no\"), =BUSCARV(…), =RAIZ(…)…", 12, fgMuted, false));
        hs.addView(grid);
        box.addView(hs);
        dock.show("Hoja de cálculo", box, new OptionDock.Action("Cancelar", false, null), new OptionDock.Action("Guardar", true, () -> {
            collect.run();
            ink.putTable(page, table, cells[0], size[0], size[1]);
        }));
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request == EDIT_IMAGE) {
            if (result == RESULT_OK && data != null) {
                String asset = data.getStringExtra("asset");
                sync.sendAsset(asset);
                ink.replaceSelectedImage(asset, data.getIntExtra("w", 0), data.getIntExtra("h", 0));
            }
            return;
        }
        super.onActivityResult(request, result, data);
        if (request == TAKE_PHOTO) {
            if (result == RESULT_OK && photoUri != null) insertImage(photoUri);
            else if (photoUri != null) getContentResolver().delete(photoUri, null, null);
            photoUri = null;
            return;
        }
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == PICK_IMAGE) insertImage(uri);
        else if (request == PICK_PDF) importPdf(uri);
        else if (request == PICK_PPTX) importDeck(uri);
        else if (request == PICK_TEXT) importText(uri);
    }

    private void insertImage(Uri uri) {
        new Thread(() -> {
            try {
                Bitmap b = loadImage(uri, 1600);
                String id = store.putAsset(b);
                main.post(() -> {
                    ink.insertImage(id, b.getWidth(), b.getHeight());
                    sync.sendAsset(id);
                });
            } catch (IOException | RuntimeException e) {
                main.post(() -> Ui.toast(this, "No se pudo abrir la imagen"));
            }
        }).start();
    }

    /** Lee una imagen reducida a `max` px y girada según su EXIF. */
    private Bitmap loadImage(Uri uri, int max) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        o.inSampleSize = 1;
        while (Math.max(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= max) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        Bitmap b;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            b = BitmapFactory.decodeStream(in, null, o);
        }
        if (b == null) throw new IOException("imagen ilegible");
        int rotation = 0;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            int ori = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            rotation = ori == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : ori == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : ori == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (IOException ignored) {
        }
        float k = Math.min(1f, max / (float) Math.max(b.getWidth(), b.getHeight()));
        if (rotation != 0 || k < 1) {
            Matrix m = new Matrix();
            m.postScale(k, k);
            m.postRotate(rotation);
            b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        }
        return b;
    }

    /** Cada página del PDF, como página nueva con su imagen de fondo para escribir encima. */
    private void importPdf(Uri uri) {
        Ui.toast(this, "Importando el PDF…");
        save();
        new Thread(() -> {
            ArrayList<String> assets = new ArrayList<>(), texts = new ArrayList<>();
            if (!importPdfNative(uri, assets, texts)) {
                assets.clear();
                texts.clear();
                try {
                    importPdfAndroid(uri, assets, texts);
                } catch (IOException | RuntimeException e) {
                    main.post(() -> Ui.toast(this, "No se pudo abrir el PDF"));
                    return;
                }
            }
            main.post(() -> addImportedPages(assets, texts, " página importada", " páginas importadas"));
        }).start();
    }

    /** Cada imagen, como página nueva al final y con su texto. */
    private void addImportedPages(ArrayList<String> assets, ArrayList<String> texts, String one, String many) {
        // Un cuaderno recién creado para el archivo: su hoja en blanco sobra.
        String blank = null;
        if (notebook.pages.size() == 1 && !assets.isEmpty()) {
            Ink.Page only = store.page(notebook.pages.get(0));
            if (only != null && only.strokes.isEmpty() && only.items.isEmpty() && only.bgAsset == null) blank = only.id;
        }
        int first = blank != null ? 0 : notebook.pages.size();
        for (int i = 0; i < assets.size(); i++) {
            String a = assets.get(i);
            Ink.Page p = store.addPage(notebook);
            p.bgAsset = a;
            p.bgText = texts.get(i);
            p.background = Ink.PLAIN;
            store.savePage(p);
            sync.sendAsset(a);
            sync.sendPage(p);
        }
        String gone = blank;
        if (gone != null && store.deletePage(notebook, gone)) {
            dirty.removeIf(p -> p.id.equals(gone));
            sync.send("{\"t\":\"delete_page\",\"page\":\"" + blank + "\"}");
        }
        sync.sendNotebook(notebook);
        loadPages(first);
        Ui.toast(this, assets.size() + (assets.size() == 1 ? one : many));
    }

    /** Cada diapositiva, como página con su imagen y sitio para escribir alrededor. */
    private void importDeck(Uri uri) {
        Ui.toast(this, "Importando la presentación…");
        save();
        new Thread(() -> {
            ArrayList<String> assets = new ArrayList<>(), texts = new ArrayList<>();
            long deck = 0;
            boolean ok = false;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16];
                for (int k; (k = in.read(buf)) > 0; ) bytes.write(buf, 0, k);
                deck = Native.deckOpen(bytes.toByteArray());
                if (deck != 0) {
                    int[] size = new int[2];
                    ok = true;
                    for (int i = 0, n = Native.deckSlides(deck); i < n && ok; i++) {
                        byte[] rgba = Native.deckRender(deck, i, PDF_WIDTH, size);
                        if (rgba == null) {
                            ok = false;
                            break;
                        }
                        Bitmap b = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
                        b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgba));
                        assets.add(store.putAsset(b));
                        b.recycle();
                        String text = Native.deckText(deck, i);
                        texts.add(text == null || text.trim().isEmpty() ? null : text);
                    }
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                android.util.Log.w("SyncTablet", "importar PowerPoint", e);
                ok = false;
            } finally {
                if (deck != 0) Native.deckClose(deck);
            }
            boolean done = ok;
            main.post(() -> {
                if (done) addImportedPages(assets, texts, " diapositiva importada", " diapositivas importadas");
                else Ui.toast(this, "No se pudo abrir la presentación");
            });
        }).start();
    }

    private void present() {
        save();
        startActivity(new Intent(this, PresentActivity.class).putExtra("notebook", notebook.id).putExtra("index", index));
    }

    /** Con el motor propio: páginas más fieles y, además, su texto. Falso si no se pudo. */
    private boolean importPdfNative(Uri uri, ArrayList<String> assets, ArrayList<String> texts) {
        if (!Native.available()) return false;
        long pdf = 0;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            for (int k; (k = in.read(buf)) > 0; ) bytes.write(buf, 0, k);
            pdf = Native.pdfOpen(bytes.toByteArray());
            if (pdf == 0) return false;
            int[] size = new int[2];
            for (int i = 0, n = Native.pdfPages(pdf); i < n; i++) {
                byte[] rgba = Native.pdfRender(pdf, i, PDF_WIDTH, size);
                if (rgba == null) return false;
                Bitmap b = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
                b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgba));
                assets.add(store.putAsset(b));
                b.recycle();
                String text = Native.pdfText(pdf, i);
                texts.add(text == null || text.trim().isEmpty() ? null : text);
            }
            return true;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            android.util.Log.w("SyncTablet", "PDF con el motor propio", e);
            return false;
        } finally {
            if (pdf != 0) Native.pdfClose(pdf);
        }
    }

    /** Con lo que trae Android: sin texto. */
    private void importPdfAndroid(Uri uri, ArrayList<String> assets, ArrayList<String> texts) throws IOException {
        try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r");
             PdfRenderer pdf = new PdfRenderer(fd)) {
            for (int i = 0; i < pdf.getPageCount(); i++) {
                try (PdfRenderer.Page page = pdf.openPage(i)) {
                    int w = PDF_WIDTH, h = Math.round(w * page.getHeight() / (float) page.getWidth());
                    Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    b.eraseColor(Color.WHITE);
                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    assets.add(store.putAsset(b));
                    texts.add(null);
                    b.recycle();
                }
            }
        }
    }

    private void exportMenu() {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        tiles.add(new OptionDock.Tile(glyph(IconView.PAGES), "PDF: elegir páginas", false, this::exportPdfPicker));
        tiles.add(new OptionDock.Tile(glyph(IconView.FILE), "Cuaderno entero en PDF", false, this::exportPdf));
        tiles.add(new OptionDock.Tile(glyph(IconView.IMAGE), "Esta página como imagen", false, this::exportPng));
        tiles.add(new OptionDock.Tile(glyph(IconView.GRID_DOC), "Textos en Markdown", false, this::exportMarkdown));
        if (Native.available()) tiles.add(new OptionDock.Tile(glyph(IconView.SLIDES), "Cuaderno en PowerPoint", false, this::exportPptx));
        dock.tiles("Exportar", tiles);
    }

    /** Una diapositiva por página, con lo escrito encima, en Descargas/SyncTablet. */
    private void exportPptx() {
        save();
        Ui.toast(this, "Creando la presentación…");
        new Thread(() -> {
            PageRenderer renderer = new PageRenderer(this);
            ArrayList<byte[]> jpegs = new ArrayList<>();
            float shape = 0;
            int w = PDF_WIDTH, h = 0;
            try {
                for (String id : notebook.pages) {
                    Ink.Page p = store.page(id);
                    if (p == null) continue;
                    RectF area = renderer.contentRect(p);
                    // La forma de la primera página manda; las demás caben dentro.
                    if (shape == 0) {
                        shape = area.width() / area.height();
                        h = Math.round(w / shape);
                    }
                    Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    b.eraseColor(Color.WHITE);
                    Canvas c = new Canvas(b);
                    float k = Math.min(w / area.width(), h / area.height());
                    c.translate((w - area.width() * k) / 2, (h - area.height() * k) / 2);
                    c.clipRect(0, 0, area.width() * k, area.height() * k);
                    c.scale(k, k);
                    c.translate(-area.left, -area.top);
                    renderer.drawPage(c, p, k, null, null);
                    ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                    b.compress(Bitmap.CompressFormat.JPEG, 88, jpeg);
                    b.recycle();
                    jpegs.add(jpeg.toByteArray());
                }
                byte[] pptx = Native.imagesToPptx(jpegs.toArray(new byte[0][]), w, h);
                if (pptx == null) throw new IOException("sin presentación");
                String name = notebook.title.replaceAll("[\\\\/:*?\"<>|]", " ").trim() + ".pptx";
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, PPTX);
                if (Build.VERSION.SDK_INT >= 29) v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/SyncTablet");
                Uri uri = getContentResolver().insert(MediaStore.Files.getContentUri("external"), v);
                if (uri == null) throw new IOException("sin acceso a Descargas");
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    out.write(pptx);
                }
                main.post(() -> {
                    Ui.toast(this, "Guardado en Descargas/SyncTablet/" + name);
                    share(uri, PPTX);
                });
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                android.util.Log.w("SyncTablet", "exportar PowerPoint", e);
                main.post(() -> Ui.toast(this, "No se pudo crear la presentación"));
            }
        }).start();
    }

    /** La página que se ve, a 2000 px de ancho, en Imágenes/SyncTablet; y compartirla. */
    private void exportPng() {
        save();
        Ink.Page p = ink.visiblePage();
        if (p == null) return;
        int page = index + 1;
        new Thread(() -> {
            int w = 2000, h = Math.round(w * Ink.H / Ink.W);
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(w / Ink.W, w / Ink.W);
            new PageRenderer(this).drawPage(c, p, w / Ink.W, null, null);
            String name = notebook.title.replaceAll("[\\\\/:*?\"<>|]", " ").trim() + " - página " + page + ".png";
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= 29) v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SyncTablet");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            try (OutputStream out = uri == null ? null : getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("sin acceso a Imágenes");
                b.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (IOException | RuntimeException e) {
                main.post(() -> Ui.toast(this, "No se pudo guardar la imagen"));
                return;
            }
            main.post(() -> {
                Ui.toast(this, "Guardada en Imágenes/SyncTablet");
                share(uri, "image/png");
            });
        }).start();
    }

    /** Todos los cuadros de texto (y hojas de cálculo) del cuaderno, como Markdown. */
    private void exportMarkdown() {
        save();
        StringBuilder md = new StringBuilder("# ").append(notebook.title).append("\n");
        for (int i = 0; i < notebook.pages.size(); i++) {
            Ink.Page p = store.page(notebook.pages.get(i));
            if (p == null || p.items.isEmpty()) continue;
            StringBuilder page = new StringBuilder();
            for (Ink.Item it : p.items) {
                if (it.isTable()) {
                    for (int r = 0; r < it.rows; r++) {
                        page.append('|');
                        for (int c = 0; c < it.cols; c++) {
                            int k = r * it.cols + c;
                            page.append(' ').append(k < it.values.length ? it.values[k].replace("|", "\\|") : "").append(" |");
                        }
                        page.append('\n');
                        if (r == 0) {
                            page.append('|');
                            for (int c = 0; c < it.cols; c++) page.append(" --- |");
                            page.append('\n');
                        }
                    }
                    page.append('\n');
                } else if (!it.isImage() && it.text != null && !it.text.trim().isEmpty()) {
                    page.append(it.text.trim()).append("\n\n");
                }
            }
            if (page.length() > 0) md.append("\n## Página ").append(i + 1).append("\n\n").append(page);
        }
        if (md.indexOf("## ") < 0) {
            Ui.toast(this, "Este cuaderno aún no tiene textos ni tablas");
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/markdown")
                .putExtra(Intent.EXTRA_SUBJECT, notebook.title).putExtra(Intent.EXTRA_TEXT, md.toString());
        startActivity(Intent.createChooser(send, "Compartir los textos"));
    }

    private void share(Uri uri, String type) {
        Intent share = new Intent(Intent.ACTION_SEND).setType(type)
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Compartir"));
    }

    /** Exportar eligiendo páginas (la que se ve, ya marcada como referencia). */
    private void exportPdfPicker() {
        save();
        PdfActivity.open(this, java.util.Collections.singletonList(notebook.id), index);
    }

    /** El cuaderno entero a PDF en Descargas/SyncTablet, y compartirlo. */
    private void exportPdf() {
        save();
        Ui.toast(this, "Creando el PDF…");
        new Thread(() -> {
            PdfDocument doc = new PdfDocument();
            float pw = 595, ph = 842;
            for (int i = 0; i < notebook.pages.size(); i++) {
                Ink.Page p = store.page(notebook.pages.get(i));
                PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder((int) pw, (int) ph, i + 1).create());
                Canvas c = page.getCanvas();
                float k = Math.min(pw / Ink.W, ph / Ink.H);
                c.translate((pw - Ink.W * k) / 2, (ph - Ink.H * k) / 2);
                c.scale(k, k);
                if (p != null) new PageRenderer(this).drawPage(c, p, 1, null, null);
                doc.finishPage(page);
            }
            String name = notebook.title.replaceAll("[\\\\/:*?\"<>|]", " ").trim() + ".pdf";
            Uri uri = null;
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
                if (Build.VERSION.SDK_INT >= 29) v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/SyncTablet");
                uri = getContentResolver().insert(MediaStore.Files.getContentUri("external"), v);
                if (uri == null) throw new IOException("sin acceso a Descargas");
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    doc.writeTo(out);
                }
            } catch (IOException | RuntimeException e) {
                main.post(() -> Ui.toast(this, "No se pudo guardar el PDF"));
                return;
            } finally {
                doc.close();
            }
            final Uri done = uri;
            main.post(() -> {
                Ui.toast(this, "Guardado en Descargas/SyncTablet/" + name);
                share(done, "application/pdf");
            });
        }).start();
    }

    private void save() {
        main.removeCallbacks(saver);
        if (dirty.isEmpty()) return;
        for (Ink.Page p : dirty) store.savePage(p);
        dirty.clear();
        notebook.updated = System.currentTimeMillis();
        store.saveNotebooks();
    }

    // ---- InkView.Listener ----

    @Override
    public void onOp(String json) {
        sync.send(json);
    }

    @Override
    public void onEdited(Ink.Page page) {
        dirty.add(page);
        main.removeCallbacks(saver);
        main.postDelayed(saver, 500);
    }

    @Override
    public void onUndoState(boolean canUndo, boolean canRedo) {
        undo.setEnabled(canUndo);
        redo.setEnabled(canRedo);
    }

    @Override
    public void onPageVisible(int i) {
        index = i;
        showTitle();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (dock.isOpen()) dock.close();
        else if (designer != null) closeDesigner();
        else super.onBackPressed();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) ink.redrawPages();
    }

    @Override
    public void onPullAddPage() {
        addPage();
        Ui.toast(this, "Página " + notebook.pages.size());
    }

    @Override
    public void onSelection(boolean active) {
        selectionBar.setVisibility(active ? View.VISIBLE : View.GONE);
        editImage.setVisibility(active && ink.selectedImage() != null && Native.available() ? View.VISIBLE : View.GONE);
        editText.setVisibility(active && ink.selectedText() != null ? View.VISIBLE : View.GONE);
        boolean line = active && ink.selectedLine() != null;
        editLine.setVisibility(active && ink.selectedHasVariants() ? View.VISIBLE : View.GONE);
        editFlip.setVisibility(active && ink.selectedFlips() ? View.VISIBLE : View.GONE);
        selectionHint.setText(line ? "Arrastra las puntas · se enganchan a otras figuras" : BOX_HINT);
        refreshTools();
    }

    /** Ctrl+V con teclado (en el PC o con funda de teclado): pega la imagen o el texto copiado. */
    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_V && event.isCtrlPressed() && !event.isAltPressed()) {
            android.view.View f = getCurrentFocus();
            if (!(f instanceof EditText)) {
                paste();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- SyncClient.Listener ----

    /** Otro aparato cambió este cuaderno: páginas nuevas o quitadas se ven al momento. */
    @Override
    public void onRemote(String notebookId) {
        if (notebookId == null || !notebookId.equals(notebook.id)) return;
        if (store.notebook(notebook.id) == null) {
            Ui.toast(this, "Este cuaderno se tiró desde otro aparato");
            finish();
            return;
        }
        if (ink.pageCount() != notebook.pages.size() || !ink.hasPagesInOrder(notebook.pages)) loadPages(index);
    }

    @Override
    public void onStatus(boolean connected, String where) {
        dot.setBackground(Ui.round(connected ? 0xFF10B981 : fg & 0xFFFFFF | 0x55000000, Ui.dp(this, 5)));
        dot.setContentDescription(connected ? "Conectado al PC (" + where + ")" : "Sin conexión al PC");
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) Clip.check(this);
        if (focus) Ui.applyFullscreen(this);
    }
}
