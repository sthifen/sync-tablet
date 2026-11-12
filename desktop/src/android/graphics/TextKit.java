package android.graphics;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Texto con Java2D: parte la cadena en tramos según qué letra tiene cada
 * carácter (la de la interfaz, y si no, emoji o símbolos), mide y dibuja.
 */
public final class TextKit {
    public static final FontRenderContext FRC = new FontRenderContext(new AffineTransform(), true, true);
    private static final String[] FALLBACKS = {"Noto Color Emoji", "Noto Sans Symbols", "Noto Sans Symbols 2", "Noto Sans Math", "DejaVu Sans", Font.SANS_SERIF};
    private static Font[] fallbacks;

    public static final class Run {
        public final String text;
        public final Font font;
        Run(String text, Font font) { this.text = text; this.font = font; }
    }

    private static Font[] fallbacks() {
        if (fallbacks == null) {
            ArrayList<Font> fs = new ArrayList<>();
            for (String f : FALLBACKS) if (Typeface.installed(f) || f.equals(Font.SANS_SERIF)) fs.add(new Font(f, Font.PLAIN, 1));
            fallbacks = fs.toArray(new Font[0]);
        }
        return fallbacks;
    }

    private static final Map<String, List<Run>> RUNS = new LinkedHashMap<String, List<Run>>(512, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<Run>> e) { return size() > 2000; }
    };

    /** Tramos de `s` con la letra `f` (ya con su tamaño). */
    public static List<Run> runs(String s, Font f) {
        String key = f.getFontName() + "|" + f.getStyle() + "|" + f.getSize2D() + "|" + s;
        synchronized (RUNS) {
            List<Run> r = RUNS.get(key);
            if (r != null) return r;
        }
        List<Run> out = new ArrayList<>(1);
        if (f.canDisplayUpTo(s) == -1 && !mayHaveEmoji(s)) {
            out.add(new Run(s, f));
        } else {
            StringBuilder cur = new StringBuilder();
            Font curFont = null;
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                int n = Character.charCount(cp);
                Font use = f;
                // Los selectores de variante y uniones se quedan con su letra.
                boolean joiner = Emoji.invisible(cp);
                int next = i + n < s.length() ? s.codePointAt(i + n) : 0;
                if (joiner && curFont != null) use = curFont;
                else if (emojiPresentation(cp, next) && Emoji.has(cp)) use = Emoji.marker(f.getSize2D());
                else if (!f.canDisplay(cp)) {
                    for (Font fb : fallbacks()) {
                        if (fb.canDisplay(cp)) { use = fb.deriveFont(f.getStyle(), f.getSize2D()); break; }
                    }
                }
                if (curFont != null && !use.equals(curFont)) { out.add(new Run(cur.toString(), curFont)); cur.setLength(0); }
                curFont = use;
                cur.append(s, i, i + n);
                i += n;
            }
            if (cur.length() > 0) out.add(new Run(cur.toString(), curFont));
        }
        synchronized (RUNS) { RUNS.put(key, out); }
        return out;
    }

    private static final String BMP_EMOJI = "\u231A\u231B\u23E9\u23EA\u23EB\u23EC\u23F0\u23F3\u25FD\u25FE\u2614\u2615\u267F\u2693\u26A1\u26AA\u26AB\u26BD\u26BE\u26C4\u26C5\u26CE\u26D4\u26EA\u26F2\u26F3\u26F5\u26FA\u26FD\u2705\u270A\u270B\u2728\u274C\u274E\u2753\u2754\u2755\u2757\u2795\u2796\u2797\u27B0\u27BF\u2B1B\u2B1C\u2B50\u2B55";

    /** ¿Se ve como emoji (en color)? Como en Android: los de presentación emoji o seguidos de FE0F. */
    static boolean emojiPresentation(int cp, int next) {
        if (cp >= 0x1F000 && cp <= 0x1FAFF) return true;
        if (next == 0xFE0F && cp > 0x7F) return true;
        if (cp >= 0x2648 && cp <= 0x2653) return true;
        return cp < 0x10000 && BMP_EMOJI.indexOf((char) cp) >= 0;
    }

    private static boolean mayHaveEmoji(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) || c == '\uFE0F' || (c >= 0x2300 && c <= 0x2BFF)) return true;
        }
        return false;
    }

    private static final Map<String, Float> WIDTHS = new LinkedHashMap<String, Float>(1024, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Float> e) { return size() > 8000; }
    };

    /** Ancho de avance de `s`. */
    public static float width(String s, Font f, float letterSpacingEm) {
        if (s.isEmpty()) return 0;
        String key = f.getFontName() + "|" + f.getStyle() + "|" + f.getSize2D() + "|" + s;
        Float w;
        synchronized (WIDTHS) { w = WIDTHS.get(key); }
        if (w == null) {
            float sum = 0;
            for (Run r : runs(s, f)) sum += Emoji.isMarker(r.font) ? Emoji.width(r.text, r.font.getSize2D()) : (float) r.font.getStringBounds(r.text, FRC).getWidth();
            w = sum;
            synchronized (WIDTHS) { WIDTHS.put(key, w); }
        }
        if (letterSpacingEm != 0) w += letterSpacingEm * f.getSize2D() * s.codePointCount(0, s.length());
        return w;
    }

    /** Dibuja en la línea base; devuelve el avance. */
    public static float draw(Graphics2D g, String s, float x, float y, Font f, float letterSpacingEm) {
        float start = x;
        for (Run r : runs(s, f)) {
            if (Emoji.isMarker(r.font)) { x += Emoji.draw(g, r.text, x, y, r.font.getSize2D()); continue; }
            g.setFont(r.font);
            if (letterSpacingEm == 0) {
                g.drawString(r.text, x, y);
                x += (float) r.font.getStringBounds(r.text, FRC).getWidth();
            } else {
                float extra = letterSpacingEm * f.getSize2D();
                for (int i = 0; i < r.text.length(); ) {
                    int n = Character.charCount(r.text.codePointAt(i));
                    String ch = r.text.substring(i, i + n);
                    g.drawString(ch, x, y);
                    x += (float) r.font.getStringBounds(ch, FRC).getWidth() + extra;
                    i += n;
                }
            }
        }
        return x - start;
    }

    /** Métricas como las de Android (negativas hacia arriba). */
    public static void metrics(Font f, Paint.FontMetrics out) {
        if (isUiSans(f)) {
            // Las de Roboto, la letra de Android: así las alturas de las líneas son las mismas.
            float s = f.getSize2D();
            out.top = -1.0566f * s; out.ascent = -0.928f * s; out.descent = 0.244f * s; out.bottom = 0.2709f * s; out.leading = 0;
            return;
        }
        LineMetrics lm = f.getLineMetrics("Ag", FRC);
        out.ascent = -lm.getAscent();
        out.descent = lm.getDescent();
        out.leading = lm.getLeading();
        out.top = out.ascent * 1.1f;
        out.bottom = out.descent * 1.1f;
    }

    static boolean isUiSans(Font f) {
        String fam = f.getFamily();
        return fam.equals(Typeface.SANS) || fam.equals(Typeface.SANS_MEDIUM) || fam.startsWith("Noto Sans") && !fam.contains("Mono") || fam.startsWith("Roboto");
    }

    private TextKit() {}
}
