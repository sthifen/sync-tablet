package android.graphics;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/** Tipo de letra: una familia de Java más el estilo. */
public class Typeface {
    public static final int NORMAL = 0, BOLD = 1, ITALIC = 2, BOLD_ITALIC = 3;

    private static final HashSet<String> INSTALLED = new HashSet<>();
    static {
        try {
            for (String f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT)) INSTALLED.add(f.toLowerCase(Locale.ROOT));
        } catch (Throwable ignored) {}
    }

    static boolean installed(String family) { return INSTALLED.contains(family.toLowerCase(Locale.ROOT)); }

    /** La letra de la interfaz: Roboto si está (como en Android); si no, Noto Sans o la de Java. */
    static final String SANS = installed("Roboto") ? "Roboto" : installed("Noto Sans") ? "Noto Sans" : Font.SANS_SERIF;
    static final String SANS_MEDIUM = installed("Roboto Medium") ? "Roboto Medium" : installed("Noto Sans Medium") ? "Noto Sans Medium" : SANS;
    static final String SERIF_FAMILY = installed("Noto Serif") ? "Noto Serif" : installed("DejaVu Serif") ? "DejaVu Serif" : Font.SERIF;
    static final String MONO = installed("Noto Sans Mono") ? "Noto Sans Mono" : installed("DejaVu Sans Mono") ? "DejaVu Sans Mono" : Font.MONOSPACED;

    public static final Typeface DEFAULT = new Typeface(SANS, NORMAL);
    public static final Typeface DEFAULT_BOLD = new Typeface(SANS, BOLD);
    public static final Typeface SANS_SERIF = DEFAULT;
    public static final Typeface SERIF = new Typeface(SERIF_FAMILY, NORMAL);
    public static final Typeface MONOSPACE = new Typeface(MONO, NORMAL);

    private static final HashMap<String, Typeface> CACHE = new HashMap<>();

    final String family;
    final int style;
    /** Letra base de tamaño 1. */
    final Font base;

    private Typeface(String family, int style) {
        this.family = family;
        this.style = style;
        int s = (style & BOLD) != 0 ? Font.BOLD : Font.PLAIN;
        if ((style & ITALIC) != 0) s |= Font.ITALIC;
        this.base = new Font(family, s, 1);
    }

    public Font font(float size) { return base.deriveFont(size); }
    public String javaFamily() { return family; }

    private static Typeface of(String family, int style) {
        String key = family + "/" + style;
        synchronized (CACHE) {
            Typeface t = CACHE.get(key);
            if (t == null) { t = new Typeface(family, style); CACHE.put(key, t); }
            return t;
        }
    }

    public static Typeface create(String familyName, int style) {
        if (familyName == null) return of(SANS, style);
        String f = familyName.toLowerCase(Locale.ROOT);
        switch (f) {
            case "sans-serif": case "sans": case "roboto": return of(SANS, style);
            case "sans-serif-medium": return of(SANS_MEDIUM, style);
            case "sans-serif-light": return of(installed("Noto Sans Light") ? "Noto Sans Light" : SANS, style);
            case "sans-serif-thin": return of(installed("Noto Sans Thin") ? "Noto Sans Thin" : SANS, style);
            case "sans-serif-black": return of(installed("Noto Sans Black") ? "Noto Sans Black" : SANS, style | BOLD);
            case "sans-serif-condensed": return of(installed("Noto Sans Condensed") ? "Noto Sans Condensed" : SANS, style);
            case "serif": return of(SERIF_FAMILY, style);
            case "monospace": return of(MONO, style);
            case "cursive": case "casual": return of(installed("Comic Neue") ? "Comic Neue" : SANS, style);
            default: return of(installed(familyName) ? familyName : SANS, style);
        }
    }

    public static Typeface create(Typeface family, int style) { return of(family == null ? SANS : family.family, style); }
    public static Typeface create(Typeface family, int weight, boolean italic) { return of(family == null ? SANS : family.family, (weight >= 600 ? BOLD : 0) | (italic ? ITALIC : 0)); }
    public static Typeface defaultFromStyle(int style) { return of(SANS, style); }
    public static Typeface createFromFile(String path) { return createFromFile(new java.io.File(path)); }
    public static Typeface createFromFile(java.io.File f) {
        try {
            Font font = Font.createFont(Font.TRUETYPE_FONT, f);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
            INSTALLED.add(font.getFamily().toLowerCase(Locale.ROOT));
            return of(font.getFamily(), NORMAL);
        } catch (Exception e) {
            return DEFAULT;
        }
    }

    public int getStyle() { return style; }
    public final boolean isBold() { return (style & BOLD) != 0; }
    public final boolean isItalic() { return (style & ITALIC) != 0; }
    public int getWeight() { return isBold() ? 700 : family.equals(SANS_MEDIUM) ? 500 : 400; }

    @Override public boolean equals(Object o) { return o instanceof Typeface && ((Typeface) o).family.equals(family) && ((Typeface) o).style == style; }
    @Override public int hashCode() { return family.hashCode() * 31 + style; }
}
