package dev.synctablet;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Cómo es la barra del cuaderno: estilo (un ajuste rápido), plegable con
 * pestañas, posición, dónde van los colores, forma, color, tamaño y qué
 * botones se ven (los demás quedan en «⋯»). También el formato con que nacen
 * los cuadernos nuevos. Se guarda en las preferencias «tools», las que lee
 * PageActivity; la pantalla para cambiarlo es BarDesigner.
 */
final class BarSettings {
    private BarSettings() {}

    static final String PREFS = "tools";
    static final String STYLE = "barStyle", COMPACT = "barCompact", SHAPE = "barShape", COLOR = "barColor", HIDDEN = "barHidden", PAPER = "defaultPaper";
    /** Cuánto espera la barra plegable para esconderse sola (ms; 0: nunca). */
    static final String HIDE_MS = "barHideMs";
    static final String FOLD = "barFold", FOLD_PEN = "barFoldPen", POS = "barPos", COLORS = "barColors", FLOAT_X = "barFloatX", FLOAT_Y = "barFloatY";

    /** Estilos: ajustes rápidos de todo lo demás. */
    static final String CLASSIC = "clasica", SAMSUNG = "samsung", MINIMAL = "minima", PEN = "lapiz";
    static final String[] STYLES = {CLASSIC, SAMSUNG, MINIMAL, PEN};
    static final String[] STYLE_NAMES = {"Clásica", "Samsung Notes", "Mínima", "Con el lápiz"};

    /** Posiciones de la barra. */
    static final String TOP = "top", BOTTOM = "bottom", LEFT = "left", RIGHT = "right";
    /** Dónde van los colores: al lado, dentro de la barra o flotando donde se suelten. */
    static final String C_SIDE = "side", C_BAR = "bar", C_FLOAT = "float";

    /** Lo que deja a la vista el estilo «Mínima». */
    private static final Set<String> ESSENTIAL = new HashSet<>(Arrays.asList("pen", "highlighter", "eraser", "lasso", "undo", "redo"));

    /** Los botones que se pueden esconder, en el orden de la barra («|» separa grupos). */
    static final String[] KEYS = {"pen", "highlighter", "eraser", "lasso", "shapes", "ruler", "text", "note", "neuron", "tape", "laser", "|",
        "undo", "redo", "|", "prev", "next", "add", "pages", "template", "insert", "fit", "export", "present", "dark", "|", "finger"};
    static final String[] LABELS = {"Pluma", "Resaltador", "Goma", "Lazo", "Figuras", "Regla y escuadra", "Texto", "Notas", "Lápiz neurona",
        "Cinta", "Puntero láser", "", "Deshacer", "Rehacer", "", "Página anterior", "Página siguiente", "Página nueva", "Páginas",
        "Plantilla de la hoja", "Insertar foto o PDF", "Ver la página entera", "Exportar", "Presentar", "Hoja oscura", "", "Dibujar con el dedo"};
    static final int[] ICONS = {IconView.PEN, IconView.HIGHLIGHTER, IconView.ERASER, IconView.LASSO, IconView.SHAPES, IconView.RULER, IconView.TEXT,
        IconView.NOTE, IconView.NEURON, IconView.TAPE, IconView.LASER, 0, IconView.UNDO, IconView.REDO, 0, IconView.PREV, IconView.NEXT,
        IconView.PLUS, IconView.PAGES, IconView.PAGE, IconView.IMAGE, IconView.FIT, IconView.EXPORT, IconView.PLAY, IconView.MOON, 0, IconView.HAND};

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** El estilo (los de la versión anterior «plegable» se ven como Clásica plegable). */
    static String style(Context c) {
        String s = prefs(c).getString(STYLE, CLASSIC);
        return Arrays.asList(STYLES).contains(s) ? s : CLASSIC;
    }

    static boolean fold(Context c) {
        SharedPreferences p = prefs(c);
        if (p.contains(FOLD)) return p.getBoolean(FOLD, false);
        String old = p.getString(STYLE, "");
        return "plegable".equals(old) || PEN.equals(old);
    }

    /** Plegada, ¿se abre al acercar el S Pen? (si no, con un toque del dedo). */
    static boolean foldByPen(Context c) {
        SharedPreferences p = prefs(c);
        if (p.contains(FOLD_PEN)) return p.getBoolean(FOLD_PEN, false);
        return PEN.equals(p.getString(STYLE, ""));
    }

    static int hideMs(Context c) {
        return prefs(c).getInt(HIDE_MS, 2500);
    }

    static String pos(Context c) {
        String s = prefs(c).getString(POS, TOP);
        return Arrays.asList(TOP, BOTTOM, LEFT, RIGHT).contains(s) ? s : TOP;
    }

    static String colorsMode(Context c) {
        String s = prefs(c).getString(COLORS, "");
        if (Arrays.asList(C_SIDE, C_BAR, C_FLOAT).contains(s)) return s;
        return SAMSUNG.equals(style(c)) ? C_BAR : C_SIDE;
    }

    /** Dónde quedó la burbuja de colores flotantes (fracción de la pantalla). */
    static float floatX(Context c) {
        return prefs(c).getFloat(FLOAT_X, 0.82f);
    }

    static float floatY(Context c) {
        return prefs(c).getFloat(FLOAT_Y, 0.35f);
    }

    /** Compacta por defecto: deja más hoja a la vista. */
    static boolean compact(Context c) {
        return prefs(c).getBoolean(COMPACT, true);
    }

    static int shape(Context c) {
        return Math.max(0, Math.min(2, prefs(c).getInt(SHAPE, 0)));
    }

    /** 0: cristal automático (claro u oscuro según la hoja). */
    static int color(Context c) {
        return prefs(c).getInt(COLOR, 0);
    }

    /** Lo que el usuario mandó a «⋯». */
    static Set<String> hidden(Context c) {
        String s = prefs(c).getString(HIDDEN, "");
        HashSet<String> out = new HashSet<>();
        for (String k : s.split(",")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    /** Lo que no se ve en la barra: lo escondido, o todo menos lo básico en «Mínima». */
    static Set<String> effectiveHidden(Context c) {
        if (!MINIMAL.equals(style(c))) return hidden(c);
        HashSet<String> out = new HashSet<>();
        for (String k : KEYS) if (!k.equals("|") && !ESSENTIAL.contains(k)) out.add(k);
        return out;
    }

    static void toggleHidden(Context c, String key) {
        HashSet<String> h = new HashSet<>(hidden(c));
        if (!h.remove(key)) h.add(key);
        SharedPreferences.Editor e = prefs(c).edit().putString(HIDDEN, String.join(",", h));
        // Tocar un botón en «Mínima» es querer elegir uno a uno: pasa a Clásica.
        if (MINIMAL.equals(style(c))) e.putString(STYLE, CLASSIC);
        e.apply();
    }

    /** Un estilo es un punto de partida: fija lo que lo define y deja lo demás. */
    static void preset(Context c, String style) {
        SharedPreferences.Editor e = prefs(c).edit().putString(STYLE, style);
        switch (style) {
            case SAMSUNG:
                e.putString(COLORS, C_BAR).putBoolean(FOLD, false);
                break;
            case MINIMAL:
                e.putString(COLORS, C_SIDE).putBoolean(FOLD, false);
                break;
            case PEN:
                e.putBoolean(FOLD, true).putBoolean(FOLD_PEN, true).putString(COLORS, C_SIDE);
                break;
            default:
                e.putString(COLORS, C_SIDE).putBoolean(FOLD, false);
        }
        e.apply();
    }

    static String label(String key) {
        int i = Arrays.asList(KEYS).indexOf(key);
        return i < 0 ? key : LABELS[i];
    }

    static int icon(String key) {
        int i = Arrays.asList(KEYS).indexOf(key);
        return i < 0 ? IconView.DOTS : ICONS[i];
    }

    /** Formato (plantilla de hoja) con que nacen los cuadernos nuevos. */
    static String paper(Context c) {
        return prefs(c).getString(PAPER, Ink.LINES);
    }

    /** Al arrancar la app: que el almacén sepa con qué hoja crear cuadernos. */
    static void applyPaper(Context c) {
        Store.defaultPaper = paper(c);
    }

    /** Si el color de la barra es claro, los iconos van oscuros (y al revés). */
    static boolean isLight(int color) {
        int r = (color >> 16) & 0xff, g = (color >> 8) & 0xff, b = color & 0xff;
        return 0.299 * r + 0.587 * g + 0.114 * b > 150;
    }

    /** Personalizar → Formato de cuaderno: la hoja con que nacen los nuevos. */
    static void paperMenu(Activity a, OptionDock dock) {
        String cur = paper(a);
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < PageActivity.BACKGROUNDS.length; i++) {
            String bg = PageActivity.BACKGROUNDS[i];
            String name = PageActivity.BACKGROUND_NAMES[i];
            tiles.add(new OptionDock.Tile(new PageActivity.TemplateSample(a, bg), name, bg.equals(cur), () -> {
                prefs(a).edit().putString(PAPER, bg).apply();
                applyPaper(a);
                Ui.toast(a, "Los cuadernos nuevos empiezan con hoja «" + name + "»");
            }));
        }
        dock.tiles("Formato de los cuadernos nuevos", tiles);
    }
}
