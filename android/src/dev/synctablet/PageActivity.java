package dev.synctablet;

import android.app.Activity;
import android.app.AlertDialog;
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
            ShapeTools.ELLIPSE, ShapeTools.TRIANGLE, ShapeTools.AXES, ShapeTools.CROSS_AXES};
    private static final int[] SHAPE_ICONS = {IconView.S_LINE, IconView.S_ARROW, IconView.S_ARROW2, IconView.S_RECT,
            IconView.S_ELLIPSE, IconView.S_TRIANGLE, IconView.S_AXES, IconView.S_CROSS};
    private static final String[] SHAPE_NAMES = {"Línea", "Flecha", "Flecha doble", "Rectángulo", "Elipse", "Triángulo",
            "Ejes", "Ejes cruzados"};
    /** Ancho en píxeles de las páginas de un PDF importado. */
    private static final int PDF_WIDTH = 1600;
    private static final int[] PEN_COLORS = {0xFF111827, 0xFF2563EB, 0xFFDC2626, 0xFF16A34A, 0xFF7C3AED, 0xFFEA580C};
    private static final int[] HL_COLORS = {0xFFFFD60A, 0xFF4ADE80, 0xFFF472B6, 0xFF60A5FA};
    private static final float[] PEN_WIDTHS = {2f, 3.5f, 6f};
    private static final float[] HL_WIDTHS = {16f, 24f, 36f};
    private static final String[] PEN_KINDS = {Ink.PEN, Ink.PENCIL, Ink.BRUSH, Ink.CALLIGRAPHY};
    private static final String[] PEN_NAMES = {"Pluma", "Lápiz", "Pincel", "Caligrafía"};
    private static final String[] BACKGROUNDS = {Ink.LINES, Ink.GRID, Ink.DOTS, Ink.CORNELL, Ink.TODO, Ink.MUSIC, Ink.PLAIN};
    private static final String[] BACKGROUND_NAMES = {"Rayas", "Cuadros", "Puntos", "Cornell (apuntes)", "Lista de tareas", "Pentagrama", "Lisa"};
    private static final int PICK_IMAGE = 1, PICK_PDF = 2, TAKE_PHOTO = 3, PICK_TEXT = 4, PICK_PPTX = 5, EDIT_IMAGE = 6;
    private static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    /** Acciones para el botón del S Pen (clic y doble clic con el lápiz en el aire). */
    private static final String[] PEN_ACTIONS = {"Cambiar entre pluma y goma", "Deshacer", "Lazo", "Puntero láser", "Resaltador", "Nada"};
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
    private IconView autoShapeButton, editImage, editText;
    private final IconView[] shapeButtons = new IconView[SHAPE_KINDS.length];
    private TextView title;
    private IconView pen, highlighter, eraser, lasso, shapes, text, tape, laser, undo, redo, finger;
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

        ink = new InkView(this);
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

        FrameLayout root = new FrameLayout(this);
        root.addView(ink, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bar = toolbar();
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                Ui.dp(this, 56), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        root.addView(bar, barLp);
        palette = palette();
        FrameLayout.LayoutParams palLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
        root.addView(palette, palLp);
        selectionBar = selectionBar();
        FrameLayout.LayoutParams selLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                Ui.dp(this, 56), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        root.addView(selectionBar, selLp);
        selectionBar.setVisibility(View.GONE);
        shapeBar = shapeBar();
        root.addView(shapeBar, selLp);
        shapeBar.setVisibility(View.GONE);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            @SuppressWarnings("deprecation") int top = in.getSystemWindowInsetTop();
            @SuppressWarnings("deprecation") int left = in.getSystemWindowInsetLeft();
            @SuppressWarnings("deprecation") int bottom = in.getSystemWindowInsetBottom();
            int gap = Ui.dp(this, 10);
            barLp.topMargin = top + gap;
            palLp.leftMargin = left + Ui.dp(this, 14);
            selLp.bottomMargin = bottom + Ui.dp(this, 18);
            bar.setLayoutParams(barLp);
            palette.setLayoutParams(palLp);
            selectionBar.setLayoutParams(selLp);
            shapeBar.setLayoutParams(selLp);
            bar.maxWidth = getResources().getDisplayMetrics().widthPixels - 2 * gap;
            ink.topInset = top + gap + Ui.dp(this, 56);
            return in;
        });
        // Cada fotograma de la hoja se ve a través del cristal.
        ink.onFrame = () -> {
            bar.postInvalidateOnAnimation();
            palette.postInvalidateOnAnimation();
            if (selectionBar.getVisibility() == View.VISIBLE) selectionBar.postInvalidateOnAnimation();
            if (shapeBar.getVisibility() == View.VISIBLE) shapeBar.postInvalidateOnAnimation();
        };
        setContentView(root);
        loadPages(index);
        refreshTools();
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
        ink.setPages(list, index);
        showTitle();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("index", index);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sync != null) sync.addListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sync == null) return;
        sync.removeListener(this);
        save();
    }

    // ---- Barras ----

    private IconView icon(int which, String label, View.OnClickListener click) {
        IconView v = new IconView(this, which, Ui.ICON, label);
        v.setOnClickListener(click);
        return v;
    }

    private View sep(boolean vertical) {
        View v = new View(this);
        v.setBackgroundColor(0x26111827);
        LinearLayout.LayoutParams lp = vertical
                ? new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 1))
                : new LinearLayout.LayoutParams(Ui.dp(this, 1), Ui.dp(this, 26));
        if (vertical) lp.topMargin = lp.bottomMargin = Ui.dp(this, 6);
        else lp.leftMargin = lp.rightMargin = Ui.dp(this, 6);
        lp.gravity = Gravity.CENTER;
        v.setLayoutParams(lp);
        return v;
    }

    private GlassLayout toolbar() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 16), 0);

        row.addView(icon(IconView.BACK, "Volver a los cuadernos", v -> finish()));
        title = Ui.text(this, "", 15, Ui.ICON, true);
        title.setPadding(Ui.dp(this, 2), 0, Ui.dp(this, 8), 0);
        title.setMinHeight(Ui.dp(this, 44));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setContentDescription("Opciones de la página");
        title.setOnClickListener(v -> pageMenu());
        row.addView(title);
        row.addView(sep(false));

        // Tocar otra vez la herramienta activa abre sus opciones.
        pen = icon(IconView.PEN, "Pluma (toca otra vez para el tipo)", v -> {
            if (ink.tool == InkView.PEN) chooseKind();
            else setTool(InkView.PEN);
        });
        highlighter = icon(IconView.HIGHLIGHTER, "Resaltador", v -> setTool(InkView.HIGHLIGHTER));
        eraser = icon(IconView.ERASER, "Goma (toca otra vez para el modo)", v -> {
            if (ink.tool == InkView.ERASER) chooseEraser();
            else setTool(InkView.ERASER);
        });
        lasso = icon(IconView.LASSO, "Lazo: toca una palabra, rodea o pasa por encima", v -> setTool(InkView.LASSO));
        shapes = icon(IconView.SHAPES, "Figuras (toca otra vez para la galería)", v -> {
            if (ink.tool == InkView.SHAPE) shapeGallery();
            else setTool(InkView.SHAPE);
        });
        text = icon(IconView.TEXT, "Texto: toca la hoja para escribir", v -> setTool(InkView.TEXT));
        tape = icon(IconView.TAPE, "Cinta: tapa algo; tócala con el dedo para destaparla", v -> setTool(InkView.TAPE));
        laser = icon(IconView.LASER, "Puntero láser (también en el PC)", v -> setTool(InkView.LASER));
        for (View t : new View[] {pen, highlighter, eraser, lasso, shapes, text, tape, laser}) row.addView(t);
        row.addView(sep(false));

        undo = icon(IconView.UNDO, "Deshacer", v -> ink.undo());
        redo = icon(IconView.REDO, "Rehacer", v -> ink.redo());
        row.addView(undo);
        row.addView(redo);
        row.addView(sep(false));

        row.addView(icon(IconView.PREV, "Página anterior", v -> ink.scrollToPage(index - 1, true)));
        row.addView(icon(IconView.NEXT, "Página siguiente", v -> ink.scrollToPage(index + 1, true)));
        row.addView(icon(IconView.PLUS, "Página nueva", v -> addPage()));
        row.addView(icon(IconView.PAGES, "Páginas: ordenar, duplicar, borrar", v -> pageManager()));
        row.addView(icon(IconView.PAGE, "Plantilla de la hoja", v -> chooseTemplate()));
        row.addView(icon(IconView.IMAGE, "Insertar foto o PDF", v -> insertMenu()));
        row.addView(icon(IconView.EXPORT, "Exportar", v -> exportMenu()));
        row.addView(icon(IconView.PLAY, "Presentar desde esta página", v -> present()));
        row.addView(sep(false));
        finger = icon(IconView.HAND, "Dibujar con el dedo", v -> {
            ink.fingerDraws = !ink.fingerDraws;
            prefs.edit().putBoolean("finger", ink.fingerDraws).apply();
            refreshTools();
            Ui.toast(this, ink.fingerDraws ? "El dedo dibuja (dos dedos mueven la hoja)" : "El dedo mueve la hoja; dibuja el lápiz");
        });
        row.addView(finger);

        dot = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(this, 10), Ui.dp(this, 10));
        dlp.leftMargin = Ui.dp(this, 8);
        row.addView(dot, dlp);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        GlassLayout g = new GlassLayout(this, ink, Ui.GLASS_LIGHT, Ui.dp(this, 28));
        g.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return g;
    }

    private GlassLayout palette() {
        GlassLayout g = new GlassLayout(this, ink, Ui.GLASS_LIGHT, Ui.dp(this, 28));
        g.setOrientation(LinearLayout.VERTICAL);
        g.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(this, 6);
        g.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 8));
        colors = new LinearLayout(this);
        colors.setOrientation(LinearLayout.VERTICAL);
        widths = new LinearLayout(this);
        widths.setOrientation(LinearLayout.VERTICAL);
        // Con su propio ancho: si no, toman el de la línea separadora.
        g.addView(colors, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        g.addView(sep(true));
        g.addView(widths, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return g;
    }

    private GlassLayout selectionBar() {
        GlassLayout g = new GlassLayout(this, ink, Ui.GLASS_LIGHT, Ui.dp(this, 28));
        g.setGravity(Gravity.CENTER_VERTICAL);
        g.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        TextView hint = Ui.text(this, "Lados: estirar · esquinas: agrandar · arriba: girar", 13, Ui.ICON_MUTED, false);
        hint.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        g.addView(hint);
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

    /** Barra de la herramienta de figuras: un toque elige qué se arrastra. */
    private GlassLayout shapeBar() {
        GlassLayout g = new GlassLayout(this, ink, Ui.GLASS_LIGHT, Ui.dp(this, 28));
        g.setGravity(Gravity.CENTER_VERTICAL);
        g.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        for (int i = 0; i < SHAPE_KINDS.length; i++) {
            int kind = SHAPE_KINDS[i];
            shapeButtons[i] = icon(SHAPE_ICONS[i], SHAPE_NAMES[i], v -> {
                ink.shapeKind = kind;
                prefs.edit().putInt("shapeKind", kind).apply();
                refreshTools();
            });
            g.addView(shapeButtons[i]);
        }
        g.addView(sep(false));
        autoShapeButton = icon(IconView.WAND, "Convertir mis trazos en figuras", v -> {
            ink.autoShapes = !ink.autoShapes;
            prefs.edit().putBoolean("shapes", ink.autoShapes).apply();
            refreshTools();
            Ui.toast(this, ink.autoShapes ? "Tus trazos se convierten en figuras" : "Deja el lápiz quieto al final del trazo para convertirlo");
        });
        g.addView(autoShapeButton);
        g.addView(icon(IconView.MORE, "Galería de figuras", v -> shapeGallery()));
        return g;
    }

    private void setTool(int t) {
        ink.tool = t;
        if (t != InkView.LASSO) ink.clearSelection();
        refreshTools();
    }

    private void refreshTools() {
        pen.setActive(ink.tool == InkView.PEN);
        highlighter.setActive(ink.tool == InkView.HIGHLIGHTER);
        eraser.setActive(ink.tool == InkView.ERASER);
        lasso.setActive(ink.tool == InkView.LASSO);
        text.setActive(ink.tool == InkView.TEXT);
        tape.setActive(ink.tool == InkView.TAPE);
        laser.setActive(ink.tool == InkView.LASER);
        shapes.setActive(ink.tool == InkView.SHAPE);
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
            GradientDrawable d = Ui.round(c, size / 2f);
            boolean on = c == currentColor && !ink.hasSelection();
            d.setStroke(Ui.dp(this, on ? 3 : 1), on ? 0xFFFFFFFF : 0x33111827);
            sw.setBackground(d);
            if (on) sw.setElevation(Ui.dp(this, 3));
            cell(colors, sw, size, "Color " + Ink.hex(c), v -> pickColor(c, hl));
        }
        // «+»: color a medida.
        TextView plus = Ui.text(this, "+", 20, Ui.ICON, true);
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
            sw.setBackground(Ui.round(w == currentWidth ? Ui.ACCENT_DEEP : 0x66111827, size / 2f));
            cell(widths, sw, size, new String[] {"Fino", "Medio", "Grueso"}[i], v -> {
                if (hl) ink.highlighterWidth = w;
                else ink.penWidth = w;
                prefs.edit().putFloat(hl ? "hlWidth" : "penWidth", w).apply();
                refreshTools();
            });
        }
    }

    private void pickColor(int c, boolean hl) {
        if (ink.hasSelection()) {
            ink.recolorSelection(c);
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
        int current = 0;
        for (int i = 0; i < PEN_KINDS.length; i++) if (PEN_KINDS[i].equals(ink.penKind)) current = i;
        new AlertDialog.Builder(this)
                .setTitle("Tipo de lápiz")
                .setSingleChoiceItems(PEN_NAMES, current, (d, which) -> {
                    ink.penKind = PEN_KINDS[which];
                    prefs.edit().putString("penKind", ink.penKind).apply();
                    d.dismiss();
                    Ui.toast(this, PEN_NAMES[which]);
                })
                .show();
    }

    private void chooseEraser() {
        String[] modes = {"Trazos enteros", "Sólo lo que toca (parcial)", "Sólo el resaltador", "Lo que rodees (lazo)"};
        new AlertDialog.Builder(this)
                .setTitle("Goma")
                .setSingleChoiceItems(modes, ink.eraseMode, (d, which) -> {
                    ink.eraseMode = which;
                    prefs.edit().putInt("eraseMode", which).apply();
                    d.dismiss();
                    Ui.toast(this, "Goma: " + modes[which].toLowerCase());
                })
                .setNeutralButton("Borrar toda esta página", (d, w) -> new AlertDialog.Builder(this)
                        .setTitle("¿Vaciar la página " + (index + 1) + "?")
                        .setMessage("Se puede deshacer.")
                        .setPositiveButton("Vaciar", (d2, w2) -> ink.clearVisiblePage())
                        .setNegativeButton("Cancelar", null)
                        .show())
                .show();
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
                paint.setColor(pass == 0 ? 0x66FFFFFF : 0xFFFFFFFF);
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

    /** Galería: geométricas, cartesianas, 3D y ondas; y convertir trazos solos. */
    private void shapeGallery() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 18);
        box.setPadding(pad, pad / 2, pad, pad);
        android.widget.CheckBox auto = new android.widget.CheckBox(this);
        auto.setText("Convertir mis trazos en figuras al dibujarlos");
        auto.setChecked(ink.autoShapes);
        auto.setOnCheckedChangeListener((b, on) -> {
            ink.autoShapes = on;
            prefs.edit().putBoolean("shapes", on).apply();
            refreshTools();
        });
        box.addView(auto);
        box.addView(Ui.text(this, "Sin activarlo: deja el lápiz quieto al final de un trazo y se convierte; sin soltar, lo ajustas. "
                + "Varias líneas seguidas cierran un polígono, y una «V» en la punta de una línea la hace flecha. "
                + "O toca una figura para insertarla (después la mueves y cambias de tamaño).", 13, 0xFF9AA9AE, false));
        AlertDialog[] dialog = new AlertDialog[1];
        int cell = Ui.dp(this, 86);
        int cols = Math.max(4, (getResources().getDisplayMetrics().widthPixels * 7 / 10) / (cell + Ui.dp(this, 8)));
        for (int g = 0; g < ShapeLibrary.GROUPS.length; g++) {
            TextView head = Ui.text(this, ShapeLibrary.GROUPS[g], 16, 0xFFFFFFFF, true);
            head.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 6));
            box.addView(head);
            GridLayout grid = new GridLayout(this);
            grid.setColumnCount(cols);
            for (ShapeLibrary.Shape s : ShapeLibrary.group(g)) {
                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setGravity(Gravity.CENTER_HORIZONTAL);
                item.setBackground(Ui.round(0x1AFFFFFF, Ui.dp(this, 14)));
                item.addView(new ShapePreview(this, s), new LinearLayout.LayoutParams(cell, cell));
                TextView name = Ui.text(this, s.name, 11, 0xCCFFFFFF, false);
                name.setGravity(Gravity.CENTER);
                name.setMaxWidth(cell);
                item.addView(name);
                item.setPadding(0, 0, 0, Ui.dp(this, 6));
                item.setContentDescription(s.name);
                item.setOnClickListener(v -> {
                    dialog[0].dismiss();
                    if (ink.tool != InkView.LASSO) setTool(InkView.LASSO);
                    ink.insertShape(s);
                });
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
                grid.addView(item, lp);
            }
            box.addView(grid);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        dialog[0] = new AlertDialog.Builder(this).setTitle("Figuras").setView(scroll).setNegativeButton("Cerrar", null).show();
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
            default:
        }
    }

    private void penButtons() {
        String[] hold = {"Borrar", "Lazo (seleccionar)", "Resaltador", "Nada"};
        String[] rows = {
            "Mantener el botón mientras escribes: " + hold[ink.buttonHold],
            "Un clic con el lápiz en el aire: " + PEN_ACTIONS[prefs.getInt("penClick", 0)],
            "Doble clic con el lápiz en el aire: " + PEN_ACTIONS[prefs.getInt("penDouble", 1)],
        };
        new AlertDialog.Builder(this)
                .setTitle("Botones del S Pen")
                .setItems(rows, (d, which) -> {
                    if (which == 0) {
                        new AlertDialog.Builder(this).setTitle("Mantener el botón mientras escribes")
                                .setSingleChoiceItems(hold, ink.buttonHold, (d2, k) -> {
                                    ink.buttonHold = k;
                                    prefs.edit().putInt("buttonHold", k).apply();
                                    d2.dismiss();
                                    penButtons();
                                }).show();
                    } else {
                        String key = which == 1 ? "penClick" : "penDouble";
                        new AlertDialog.Builder(this).setTitle(which == 1 ? "Un clic" : "Doble clic")
                                .setSingleChoiceItems(PEN_ACTIONS, prefs.getInt(key, which == 1 ? 0 : 1), (d2, k) -> {
                                    prefs.edit().putInt(key, k).apply();
                                    d2.dismiss();
                                    penButtons();
                                }).show();
                    }
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void chooseTemplate() {
        Ink.Page p = ink.visiblePage();
        if (p == null) return;
        int current = 0;
        for (int i = 0; i < BACKGROUNDS.length; i++) if (BACKGROUNDS[i].equals(p.background)) current = i;
        new AlertDialog.Builder(this)
                .setTitle("Plantilla de la página " + (index + 1))
                .setSingleChoiceItems(BACKGROUND_NAMES, current, (d, which) -> {
                    ink.setBackground(BACKGROUNDS[which]);
                    d.dismiss();
                })
                .show();
    }

    /** Selector de color a medida: tono, saturación y brillo, con muestra. */
    private void customColorDialog(boolean hl) {
        float[] hsv = new float[3];
        Color.colorToHSV(customColor, hsv);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, pad / 2, pad, 0);
        View preview = new View(this);
        box.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));
        String[] names = {"Tono", "Saturación", "Brillo"};
        int[] max = {360, 100, 100};
        SeekBar[] bars = new SeekBar[3];
        Runnable update = () -> {
            float[] v = {bars[0].getProgress(), bars[1].getProgress() / 100f, bars[2].getProgress() / 100f};
            preview.setBackground(Ui.round(Color.HSVToColor(v), Ui.dp(this, 12)));
        };
        for (int i = 0; i < 3; i++) {
            box.addView(Ui.text(this, names[i], 14, 0xFF9AA9AE, false));
            bars[i] = new SeekBar(this);
            bars[i].setMax(max[i]);
            bars[i].setProgress(Math.round(i == 0 ? hsv[0] : hsv[i] * 100));
            bars[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int v, boolean user) { update.run(); }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
            box.addView(bars[i]);
        }
        update.run();
        new AlertDialog.Builder(this)
                .setTitle("Color a medida")
                .setView(box)
                .setPositiveButton("Usar", (d, w) -> {
                    float[] v = {bars[0].getProgress(), bars[1].getProgress() / 100f, bars[2].getProgress() / 100f};
                    customColor = Color.HSVToColor(v);
                    prefs.edit().putInt("customColor", customColor).apply();
                    pickColor(customColor, hl);
                    refreshTools();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---- Texto ----

    @Override
    public void onTextRequest(int page, Ink.Item item, float x, float y) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setMinLines(3);
        field.setGravity(Gravity.TOP);
        if (item != null) field.setText(item.text);
        box.addView(field);
        float[] sizes = {20, 28, 40, 56};
        String[] sizeNames = {"Pequeño", "Normal", "Grande", "Título"};
        final float[] chosen = {item != null ? item.size : 28};
        LinearLayout row = new LinearLayout(this);
        ArrayList<TextView> buttons = new ArrayList<>();
        for (int i = 0; i < sizes.length; i++) {
            TextView b = Ui.button(this, sizeNames[i]);
            final float s = sizes[i];
            b.setOnClickListener(v -> {
                chosen[0] = s;
                for (TextView o : buttons) Ui.setActive(o, o == v);
            });
            buttons.add(b);
            Ui.setActive(b, Math.abs(s - chosen[0]) < 1);
            row.addView(b);
        }
        box.addView(row);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(item == null ? "Texto nuevo" : "Editar texto")
                .setView(box)
                .setPositiveButton("Guardar", (d, w) -> ink.putText(page, item, field.getText().toString(), chosen[0],
                        item != null ? item.color : ink.penColor, x, y))
                .setNegativeButton("Cancelar", null);
        if (item != null) b.setNeutralButton("Borrar", (d, w) -> ink.putText(page, item, "", item.size, item.color, x, y));
        b.show();
        field.requestFocus();
    }

    // ---- Páginas ----

    private void showTitle() {
        title.setText(notebook.title + "  " + (index + 1) + "/" + notebook.pages.size());
    }

    private void addPage() {
        save();
        Ink.Page p = store.addPage(notebook);
        sync.sendNotebook(notebook);
        sync.sendPage(p);
        ink.appendPage(p);
        showTitle();
    }

    private void pageMenu() {
        Ink.Page visible = ink.visiblePage();
        String text = visible == null ? null : visible.bgText;
        ArrayList<String> items = new ArrayList<>(java.util.Arrays.asList("Página nueva al final", "Páginas (ordenar, duplicar, borrar)",
                "Plantilla", "Botones del S Pen", "Borrar esta página"));
        if (text != null) items.add("Copiar el texto de la página");
        new AlertDialog.Builder(this)
                .setTitle("Página " + (index + 1) + " de " + notebook.pages.size())
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) addPage();
                    else if (which == 1) pageManager();
                    else if (which == 2) chooseTemplate();
                    else if (which == 3) penButtons();
                    else if (which == 4) confirmDeletePage(index, null);
                    else copyText(text);
                })
                .show();
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
        new AlertDialog.Builder(this)
                .setTitle("¿Borrar la página " + (i + 1) + "?")
                .setMessage("Se borra de la tablet y del PC.")
                .setPositiveButton("Borrar", (d, w) -> {
                    save();
                    String id = notebook.pages.get(i);
                    if (!store.deletePage(notebook, id)) return;
                    dirty.removeIf(p -> p.id.equals(id));
                    sync.sendNotebook(notebook);
                    sync.send("{\"t\":\"delete_page\",\"page\":\"" + id + "\"}");
                    ink.removePage(i);
                    showTitle();
                    if (after != null) after.run();
                })
                .setNegativeButton("Cancelar", null)
                .show();
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
                cell.addView(Ui.text(this, String.valueOf(i + 1), 14, 0xFF9AA9AE, false));
                final int at = i;
                cell.setOnClickListener(v -> {
                    chosen[0] = at;
                    for (int k = 0; k < cells.size(); k++) {
                        cells.get(k).setBackground(k == at ? Ui.round(0x332DD4BF, Ui.dp(this, 10)) : null);
                    }
                });
                if (i == chosen[0]) cell.setBackground(Ui.round(0x332DD4BF, Ui.dp(this, 10)));
                cells.add(cell);
                grid.addView(cell);
            }
        };
        fill[0].run();
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        String[] names = {"◀ Mover", "Mover ▶", "Duplicar", "Borrar"};
        for (int k = 0; k < names.length; k++) {
            TextView b = Ui.button(this, names[k]);
            Ui.setActive(b, false);
            b.setTextColor(0xFFEEF3F4);
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
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 420)));
        new AlertDialog.Builder(this)
                .setTitle("Páginas de «" + notebook.title + "»")
                .setView(box)
                .setPositiveButton("Ir a la página", (d, w) -> loadPages(chosen[0]))
                .setOnDismissListener(d -> {
                    // Orden o páginas cambiados: recargar la vista.
                    loadPages(Math.min(chosen[0], notebook.pages.size() - 1));
                })
                .show();
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
        ArrayList<String> list = new ArrayList<>(java.util.Arrays.asList("Foto o imagen de la galería", "Hacer una foto con la cámara",
                "PDF (sus páginas al final del cuaderno)", "Hoja de cálculo", "Archivo de texto o Markdown", "Pegar del portapapeles"));
        if (Native.available()) list.add("PowerPoint (sus diapositivas al final del cuaderno)");
        String[] options = list.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Insertar")
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0: pick("image/*", PICK_IMAGE); break;
                        case 1: takePhoto(); break;
                        case 2: pick("application/pdf", PICK_PDF); break;
                        case 3: askTableSize(); break;
                        case 4: pick("text/*", PICK_TEXT); break;
                        case 6: pick(PPTX, PICK_PPTX); break;
                        default: paste();
                    }
                })
                .show();
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
        String[] sizes = {"3 × 3", "4 × 3", "5 × 4", "8 × 4", "10 × 6"};
        int[][] rc = {{3, 3}, {4, 3}, {5, 4}, {8, 4}, {10, 6}};
        new AlertDialog.Builder(this)
                .setTitle("Hoja de cálculo (filas × columnas)")
                .setItems(sizes, (d, which) -> {
                    if (ink.tool != InkView.LASSO) setTool(InkView.LASSO);
                    ink.insertTable(rc[which][0], rc[which][1]);
                    Ui.toast(this, "Con la herramienta de texto (T) tocas la tabla para escribir en ella");
                })
                .show();
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
        ScrollView scroll = new ScrollView(this);
        HorizontalScrollView hs = new HorizontalScrollView(this);
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
                TextView h = Ui.text(this, String.valueOf((char) ('A' + c)), 13, 0xFF9AA9AE, true);
                h.setGravity(Gravity.CENTER);
                grid.addView(h);
            }
            for (int r = 0; r < size[0]; r++) {
                grid.addView(Ui.text(this, String.valueOf(r + 1), 13, 0xFF9AA9AE, true));
                for (int c = 0; c < size[1]; c++) {
                    EditText e = new EditText(this);
                    e.setSingleLine(true);
                    e.setMinWidth(Ui.dp(this, 110));
                    e.setTextSize(14);
                    int i = r * size[1] + c;
                    e.setText(i < cells[0].length && cells[0][i] != null ? cells[0][i] : "");
                    fields.add(e);
                    grid.addView(e);
                }
            }
        };
        fill[0].run();
        LinearLayout actions = new LinearLayout(this);
        String[] names = {"+ Fila", "− Fila", "+ Columna", "− Columna"};
        for (int k = 0; k < names.length; k++) {
            TextView b = Ui.button(this, names[k]);
            b.setTextColor(0xFFEEF3F4);
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
        box.addView(Ui.text(this, "Fórmulas: =A1+B2, =SUMA(A1:A5), =PROMEDIO(…), =MIN, =MAX, =CONTAR", 12, 0xFF9AA9AE, false));
        box.addView(grid);
        hs.addView(box);
        scroll.addView(hs);
        new AlertDialog.Builder(this)
                .setTitle("Hoja de cálculo")
                .setView(scroll)
                .setPositiveButton("Guardar", (d, w) -> {
                    collect.run();
                    ink.putTable(page, table, cells[0], size[0], size[1]);
                })
                .setNegativeButton("Cancelar", null)
                .show();
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
        int first = notebook.pages.size();
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
        new AlertDialog.Builder(this)
                .setTitle("Exportar")
                .setItems(exportOptions(), (d, which) -> {
                    if (which == 0) exportPdf();
                    else if (which == 1) exportPng();
                    else if (which == 2) exportMarkdown();
                    else exportPptx();
                })
                .show();
    }

    private String[] exportOptions() {
        ArrayList<String> list = new ArrayList<>(java.util.Arrays.asList("Cuaderno entero en PDF", "Esta página como imagen (PNG)",
                "Los textos del cuaderno (Markdown)"));
        if (Native.available()) list.add("Cuaderno entero en PowerPoint");
        return list.toArray(new String[0]);
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
    public void onPullAddPage() {
        addPage();
        Ui.toast(this, "Página " + notebook.pages.size());
    }

    @Override
    public void onSelection(boolean active) {
        selectionBar.setVisibility(active ? View.VISIBLE : View.GONE);
        editImage.setVisibility(active && ink.selectedImage() != null && Native.available() ? View.VISIBLE : View.GONE);
        editText.setVisibility(active && ink.selectedText() != null ? View.VISIBLE : View.GONE);
        refreshTools();
    }

    // ---- SyncClient.Listener ----

    @Override
    public void onStatus(boolean connected, String where) {
        dot.setBackground(Ui.round(connected ? 0xFF10B981 : 0x55111827, Ui.dp(this, 5)));
        dot.setContentDescription(connected ? "Conectado al PC (" + where + ")" : "Sin conexión al PC");
    }
}
