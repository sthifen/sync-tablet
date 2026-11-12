package android.text;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.style.AlignmentSpan;
import android.text.style.CharacterStyle;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.ParagraphStyle;
import android.text.style.ReplacementSpan;
import java.util.ArrayList;

/**
 * Texto partido en líneas, con spans: tamaños, estilos, colores, viñetas,
 * alineación e imágenes. Mide con las mismas letras con las que dibuja.
 */
public abstract class Layout {
    public enum Alignment { ALIGN_NORMAL, ALIGN_OPPOSITE, ALIGN_CENTER, ALIGN_LEFT, ALIGN_RIGHT }

    public static final int DIR_LEFT_TO_RIGHT = 1, DIR_RIGHT_TO_LEFT = -1;
    public static final int BREAK_STRATEGY_SIMPLE = 0, BREAK_STRATEGY_HIGH_QUALITY = 1, BREAK_STRATEGY_BALANCED = 2;
    public static final int HYPHENATION_FREQUENCY_NONE = 0, HYPHENATION_FREQUENCY_NORMAL = 1, HYPHENATION_FREQUENCY_FULL = 2;
    public static final int JUSTIFICATION_MODE_NONE = 0, JUSTIFICATION_MODE_INTER_WORD = 1;

    protected final CharSequence text;
    protected final TextPaint paint;
    protected int width;
    protected final Alignment alignment;
    protected final float spacingMult, spacingAdd;

    /** Una línea: dónde empieza, su caja y sus anchos. */
    static final class Line {
        int start, end, top, bottom, ascent, descent, tTop, tBottom;
        float width;
        int margin;
        Alignment align;
        boolean paragraphStart;
        int ellipsisStart = -1, ellipsisCount;
        /** Avances de cada carácter desde el inicio de la línea. */
        float[] x;
    }

    final ArrayList<Line> lines = new ArrayList<>();
    int height;

    protected Layout(CharSequence text, TextPaint paint, int width, Alignment align, float spacingMult, float spacingAdd) {
        this.text = text == null ? "" : text;
        this.paint = paint;
        this.width = Math.max(0, width);
        this.alignment = align == null ? Alignment.ALIGN_NORMAL : align;
        this.spacingMult = spacingMult;
        this.spacingAdd = spacingAdd;
    }

    public final CharSequence getText() { return text; }
    public final TextPaint getPaint() { return paint; }
    public final int getWidth() { return width; }
    public int getEllipsizedWidth() { return width; }
    public final Alignment getAlignment() { return alignment; }
    public final float getSpacingMultiplier() { return spacingMult; }
    public final float getSpacingAdd() { return spacingAdd; }
    public int getHeight() { return height; }
    public int getHeight(boolean cap) { return height; }
    public int getLineCount() { return lines.size(); }
    public int getLineTop(int line) { return line >= lines.size() ? height : lines.get(Math.max(0, line)).top; }
    public int getLineBottom(int line) { return line >= lines.size() ? height : lines.get(Math.max(0, line)).bottom; }
    public final int getLineBottom(int line, boolean includeLineSpacing) { return getLineBottom(line); }
    public int getLineDescent(int line) { return lines.get(line).descent; }
    public int getLineAscent(int line) { return lines.get(line).ascent; }
    public final int getLineBaseline(int line) { Line l = lines.get(Math.max(0, Math.min(line, lines.size() - 1))); return l.top - l.ascent; }
    public int getLineStart(int line) { return line >= lines.size() ? text.length() : lines.get(line).start; }
    public final int getLineEnd(int line) { return line + 1 >= lines.size() ? text.length() : lines.get(line + 1).start; }
    public int getLineVisibleEnd(int line) {
        int end = getLineEnd(line), start = getLineStart(line);
        while (end > start && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '\t')) end--;
        return end;
    }
    public int getParagraphDirection(int line) { return DIR_LEFT_TO_RIGHT; }
    public boolean getLineContainsTab(int line) { return false; }
    public final boolean isRtlCharAt(int offset) { return false; }
    public boolean isLevelBoundary(int offset) { return false; }
    public int getEllipsisStart(int line) { Line l = lines.get(line); return l.ellipsisStart < 0 ? 0 : l.ellipsisStart - l.start; }
    public int getEllipsisCount(int line) { return lines.get(line).ellipsisCount; }
    public int getTopPadding() { return topPad; }
    public int getBottomPadding() { return bottomPad; }
    public final Alignment getParagraphAlignment(int line) { return lines.get(line).align; }
    public final int getParagraphLeft(int line) { return 0; }
    public final int getParagraphRight(int line) { return width; }
    public float getLineWidth(int line) { return lines.get(line).width; }
    public float getLineMax(int line) { return lines.get(line).width + lines.get(line).margin; }
    public float getLineLeft(int line) { return lineX(line); }
    public float getLineRight(int line) { return lineX(line) + lines.get(line).width; }
    public int getIndentAdjust(int line, Alignment a) { return 0; }

    int topPad, bottomPad;

    /** Desde dónde se dibuja la línea (margen de viñeta y alineación). */
    float lineX(int i) {
        Line l = lines.get(i);
        float avail = width - l.margin;
        switch (l.align) {
            case ALIGN_CENTER: return l.margin + Math.max(0, (avail - l.width) / 2f);
            case ALIGN_OPPOSITE: case ALIGN_RIGHT: return l.margin + Math.max(0, avail - l.width);
            default: return l.margin;
        }
    }

    public int getLineForVertical(int v) {
        int lo = 0, hi = lines.size() - 1;
        if (hi < 0) return 0;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lines.get(mid).top > v) hi = mid - 1; else lo = mid;
        }
        return lo;
    }

    public int getLineForOffset(int offset) {
        int lo = 0, hi = lines.size() - 1;
        if (hi < 0) return 0;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lines.get(mid).start > offset) hi = mid - 1; else lo = mid;
        }
        return lo;
    }

    public float getPrimaryHorizontal(int offset) {
        if (lines.isEmpty()) return lineXEmpty();
        int line = getLineForOffset(offset);
        Line l = lines.get(line);
        int i = Math.max(0, Math.min(offset - l.start, l.x.length - 1));
        return lineX(line) + l.x[i];
    }

    public float getPrimaryHorizontal(int offset, boolean clamped) { return getPrimaryHorizontal(offset); }
    public float getSecondaryHorizontal(int offset) { return getPrimaryHorizontal(offset); }

    private float lineXEmpty() {
        switch (alignment) {
            case ALIGN_CENTER: return width / 2f;
            case ALIGN_OPPOSITE: case ALIGN_RIGHT: return width;
            default: return 0;
        }
    }

    public int getOffsetForHorizontal(int line, float horiz) {
        if (lines.isEmpty()) return 0;
        line = Math.max(0, Math.min(line, lines.size() - 1));
        Line l = lines.get(line);
        float x = horiz - lineX(line);
        int end = line + 1 < lines.size() ? lines.get(line + 1).start : text.length();
        // Al final de una línea partida, el cursor queda antes del salto.
        int last = end;
        if (line + 1 < lines.size() && last > l.start) {
            char c = text.charAt(last - 1);
            if (c == '\n' || c == ' ') last--;
        } else if (last > l.start && text.charAt(last - 1) == '\n') last--;
        int best = l.start;
        float bestD = Float.MAX_VALUE;
        for (int o = l.start; o <= last; o++) {
            int i = Math.min(o - l.start, l.x.length - 1);
            if (o > l.start && o < text.length() && Character.isLowSurrogate(text.charAt(o))) continue;
            float d = Math.abs(l.x[i] - x);
            if (d < bestD) { bestD = d; best = o; }
        }
        return best;
    }

    public int getOffsetToLeftOf(int offset) { return Math.max(0, offset - (offset >= 2 && Character.isLowSurrogate(text.charAt(offset - 1)) ? 2 : 1)); }
    public int getOffsetToRightOf(int offset) { return Math.min(text.length(), offset + (offset + 1 < text.length() && Character.isHighSurrogate(text.charAt(offset)) ? 2 : 1)); }

    public void getLineBounds(int line, Rect r) {
        r.left = 0;
        r.top = getLineTop(line);
        r.right = width;
        r.bottom = getLineBottom(line);
    }

    public void getCursorPath(int point, Path dest, CharSequence editingBuffer) {
        dest.reset();
        int line = getLineForOffset(point);
        float h = getPrimaryHorizontal(point) - 0.5f;
        dest.moveTo(h, getLineTop(line));
        dest.lineTo(h, getLineBottom(line));
    }

    public void getSelectionPath(int start, int end, Path dest) {
        dest.reset();
        if (start == end) return;
        if (end < start) { int t = start; start = end; end = t; }
        int first = getLineForOffset(start), last = getLineForOffset(end);
        for (int line = first; line <= last; line++) {
            float l = line == first ? getPrimaryHorizontal(start) : lineX(line);
            float r = line == last ? getPrimaryHorizontal(end) : Math.max(lineX(line) + lines.get(line).width + (line < last ? paint.getTextSize() * 0.3f : 0), l);
            if (r > l) dest.addRect(l, getLineTop(line), r, getLineBottom(line), Path.Direction.CW);
        }
    }

    public void getSelection(int start, int end, Object consumer) {}

    // ---------------------------------------------------------------- medir

    public static float getDesiredWidth(CharSequence source, TextPaint paint) { return getDesiredWidth(source, 0, source.length(), paint); }

    public static float getDesiredWidth(CharSequence source, int start, int end, TextPaint paint) {
        float max = 0;
        int ps = start;
        for (int i = start; i <= end; i++) {
            if (i == end || source.charAt(i) == '\n') {
                float w = measureRange(source, ps, i, paint, null);
                if (w > max) max = w;
                ps = i + 1;
            }
        }
        return max;
    }

    /** Ancho del tramo con sus spans; si `adv` no es null, guarda el avance acumulado de cada carácter (adv[0] = 0). */
    static float measureRange(CharSequence text, int start, int end, TextPaint base, float[] adv) {
        if (adv != null) adv[0] = 0;
        if (end <= start) return 0;
        float x = 0;
        Spanned sp = text instanceof Spanned ? (Spanned) text : null;
        TextPaint wp = new TextPaint();
        int i = start;
        while (i < end) {
            int next = sp == null ? end : sp.nextSpanTransition(i, end, MetricAffectingSpan.class);
            ReplacementSpan repl = null;
            wp.set(base);
            if (sp != null) {
                for (MetricAffectingSpan s : sp.getSpans(i, next, MetricAffectingSpan.class)) {
                    if (sp.getSpanStart(s) == sp.getSpanEnd(s)) continue;
                    if (s instanceof ReplacementSpan) repl = (ReplacementSpan) s;
                    else s.updateMeasureState(wp);
                }
            }
            if (repl != null) {
                int rs = Math.max(start, sp.getSpanStart(repl)), re = Math.min(end, sp.getSpanEnd(repl));
                if (rs < i) rs = i;
                float w = repl.getSize(wp, text, sp.getSpanStart(repl), sp.getSpanEnd(repl), null);
                if (adv != null) for (int k = i; k < re; k++) adv[k - start + 1] = x + (k == re - 1 ? w : 0);
                x += w;
                i = Math.max(re, i + 1);
                continue;
            }
            String s = text.subSequence(i, next).toString();
            if (adv != null) {
                float[] a = new float[s.length() + 1];
                charAdvances(s, wp, a);
                for (int k = 0; k < s.length(); k++) adv[i - start + k + 1] = x + a[k + 1];
                x += a[s.length()];
            } else {
                x += wp.measureText(s);
            }
            i = next;
        }
        return x;
    }

    /** Avance acumulado de cada carácter dentro de `s` (sin spans). */
    static void charAdvances(String s, Paint p, float[] out) {
        out[0] = 0;
        java.awt.Font f = p.font();
        float ls = p.getLetterSpacing() * p.getTextSize();
        int pos = 0;
        float x = 0;
        for (android.graphics.TextKit.Run run : android.graphics.TextKit.runs(s, f)) {
            String t = run.text;
            if (android.graphics.Emoji.isMarker(run.font)) {
                for (int k = 0; k < t.length(); ) {
                    int cp = t.codePointAt(k), n = Character.charCount(cp);
                    if (n == 2) out[pos + k + 1] = x;
                    x += android.graphics.Emoji.advance(cp, run.font.getSize2D());
                    out[pos + k + n] = x;
                    k += n;
                }
                pos += t.length();
                continue;
            }
            java.awt.font.GlyphVector gv = run.font.createGlyphVector(android.graphics.TextKit.FRC, t);
            int ng = gv.getNumGlyphs();
            if (ng == t.length()) {
                for (int k = 0; k < t.length(); k++) {
                    x += (float) (gv.getGlyphPosition(k + 1).getX() - gv.getGlyphPosition(k).getX());
                    if (!Character.isLowSurrogate(t.charAt(k))) x += ls;
                    out[pos + k + 1] = x;
                }
            } else {
                for (int k = 0; k < t.length(); ) {
                    int n = Character.charCount(t.codePointAt(k));
                    if (n == 2) out[pos + k + 1] = x;
                    x += (float) run.font.getStringBounds(t.substring(k, k + n), android.graphics.TextKit.FRC).getWidth() + ls;
                    out[pos + k + n] = x;
                    k += n;
                }
            }
            pos += t.length();
        }
    }

    // ---------------------------------------------------------------- partir en líneas

    /** Parte el texto en líneas de `width`; el resto lo afina StaticLayout. */
    void build(boolean includePad, int maxLines, TextUtils.TruncateAt ellipsize, int ellipsizedWidth) {
        lines.clear();
        Spanned sp = text instanceof Spanned ? (Spanned) text : null;
        int len = text.length();
        int y = 0;
        Paint.FontMetricsInt baseFm = paint.getFontMetricsInt();
        int pStart = 0;
        boolean stop = false;
        while (pStart <= len && !stop) {
            int pEnd = pStart;
            while (pEnd < len && text.charAt(pEnd) != '\n') pEnd++;
            int pNext = pEnd < len ? pEnd + 1 : pEnd;
            // Estilo del párrafo.
            Alignment align = alignment;
            int firstMargin = 0, restMargin = 0;
            if (sp != null) {
                AlignmentSpan[] as = sp.getSpans(pStart, pNext, AlignmentSpan.class);
                for (AlignmentSpan a : as) if (sp.getSpanStart(a) <= pStart || pStart == pEnd) align = a.getAlignment();
                for (LeadingMarginSpan m : sp.getSpans(pStart, pNext, LeadingMarginSpan.class)) {
                    if (sp.getSpanStart(m) > pStart && pEnd > pStart) continue;
                    firstMargin += m.getLeadingMargin(true);
                    restMargin += m.getLeadingMargin(false);
                }
            }
            int n = pNext - pStart;
            float[] adv = new float[n + 1];
            measureRange(text, pStart, pNext, paint, adv);
            int lineStart = pStart;
            boolean first = true;
            while (true) {
                int margin = first ? firstMargin : restMargin;
                float avail = Math.max(1, width - margin);
                int end = lineStart;
                int lastBreak = -1;
                float base = adv[lineStart - pStart];
                // Hasta donde cabe.
                while (end < pNext) {
                    char c = text.charAt(end);
                    if (c == '\n') { end++; break; }
                    float w = adv[end + 1 - pStart] - base;
                    if (w > avail && c != ' ' && c != '\t' && end > lineStart) break;
                    if (c == ' ' || c == '\t' || c == '-' || c == '/' || c == '​' || isCjk(c)) lastBreak = end + 1;
                    end++;
                }
                if (end < pNext && text.charAt(end - 1) != '\n') {
                    // No cupo todo: se parte en el último espacio (los espacios cuelgan).
                    if (lastBreak > lineStart) end = lastBreak;
                    while (end < pNext && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) end++;
                    if (end < len && end > lineStart && Character.isLowSurrogate(text.charAt(end)) && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                }
                // Cada línea avanza al menos un carácter entero (un emoji son dos char):
                // si no, con un ancho mínimo la línea queda vacía y el bucle no acaba.
                if (end <= lineStart && lineStart < pNext) end = Math.min(pNext, lineStart + Character.charCount(Character.codePointAt(text, lineStart)));
                Line l = new Line();
                l.start = lineStart;
                l.end = end;
                l.margin = margin;
                l.align = align;
                l.paragraphStart = first;
                int count = end - lineStart;
                l.x = new float[count + 1];
                for (int k = 0; k <= count; k++) l.x[k] = adv[lineStart - pStart + k] - base;
                // Ancho sin los espacios ni el salto del final.
                int vis = end;
                while (vis > lineStart && (text.charAt(vis - 1) == ' ' || text.charAt(vis - 1) == '\n' || text.charAt(vis - 1) == '\t')) vis--;
                l.width = adv[vis - pStart] - base;
                lineMetrics(l, sp, baseFm);
                lines.add(l);
                first = false;
                lineStart = end;
                if (maxLines > 0 && lines.size() >= maxLines) {
                    if (lineStart < len && ellipsize != null) ellipsizeLast(l, ellipsize, ellipsizedWidth > 0 ? ellipsizedWidth : width);
                    stop = true;
                    break;
                }
                if (lineStart >= pNext) break;
            }
            if (stop) break;
            if (pNext == pEnd) {
                // Último párrafo: si el texto termina en salto, hay una línea vacía más.
                break;
            }
            pStart = pNext;
            if (pStart == len) {
                Line l = new Line();
                l.start = len;
                l.end = len;
                l.align = alignment;
                l.paragraphStart = true;
                l.x = new float[] {0};
                lineMetrics(l, sp, baseFm);
                if (maxLines <= 0 || lines.size() < maxLines) lines.add(l);
                break;
            }
        }
        if (lines.isEmpty()) {
            Line l = new Line();
            l.start = 0;
            l.end = 0;
            l.align = alignment;
            l.paragraphStart = true;
            l.x = new float[] {0};
            lineMetrics(l, sp, baseFm);
            lines.add(l);
        }
        // Alturas: interlineado en todas menos la última, y el relleno arriba y abajo.
        topPad = 0;
        bottomPad = 0;
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            int above = l.ascent, below = l.descent;
            if (includePad && i == 0) { topPad = l.tTop - l.ascent; above = l.tTop; }
            if (includePad && i == lines.size() - 1) { bottomPad = l.tBottom - l.descent; below = l.tBottom; }
            int extra = 0;
            boolean last = i == lines.size() - 1;
            if ((spacingMult != 1 || spacingAdd != 0) && !last) {
                double ex = (below - above) * (spacingMult - 1) + spacingAdd;
                extra = ex >= 0 ? (int) (ex + 0.5) : -(int) (-ex + 0.5);
            }
            l.top = y;
            if (includePad && i == 0) { l.ascent = above; }
            y += below - above + extra;
            l.descent = below + extra;
            l.bottom = y;
        }
        height = y;
    }

    private static boolean isCjk(char c) { return c >= 0x3000 && c <= 0x9FFF; }

    private void lineMetrics(Line l, Spanned sp, Paint.FontMetricsInt baseFm) {
        int ascent = baseFm.ascent, descent = baseFm.descent, top = baseFm.top, bottom = baseFm.bottom;
        if (sp != null && l.end > l.start) {
            TextPaint wp = new TextPaint();
            Paint.FontMetricsInt fm = new Paint.FontMetricsInt();
            int i = l.start;
            while (i < l.end) {
                int next = sp.nextSpanTransition(i, l.end, MetricAffectingSpan.class);
                MetricAffectingSpan[] ms = sp.getSpans(i, next, MetricAffectingSpan.class);
                if (ms.length > 0) {
                    wp.set(paint);
                    ReplacementSpan repl = null;
                    for (MetricAffectingSpan s : ms) {
                        if (sp.getSpanStart(s) == sp.getSpanEnd(s)) continue;
                        if (s instanceof ReplacementSpan) repl = (ReplacementSpan) s; else s.updateMeasureState(wp);
                    }
                    if (repl != null) {
                        wp.getFontMetricsInt(fm);
                        repl.getSize(wp, text, sp.getSpanStart(repl), sp.getSpanEnd(repl), fm);
                    } else wp.getFontMetricsInt(fm);
                    // Si el tramo es sólo el salto de línea, no cuenta.
                    if (!(next - i == 1 && text.charAt(i) == '\n')) {
                        ascent = Math.min(ascent, fm.ascent);
                        descent = Math.max(descent, fm.descent);
                        top = Math.min(top, fm.top);
                        bottom = Math.max(bottom, fm.bottom);
                    }
                }
                i = next;
            }
        }
        l.ascent = ascent;
        l.descent = descent;
        l.tTop = top;
        l.tBottom = bottom;
    }

    private void ellipsizeLast(Line l, TextUtils.TruncateAt where, int avail) {
        float dots = paint.measureText("…");
        int count = l.end - l.start;
        int keep = count;
        while (keep > 0 && l.x[keep] + dots > avail - l.margin) keep--;
        // Quita también espacios del final.
        while (keep > 0 && text.charAt(l.start + keep - 1) == ' ') keep--;
        l.ellipsisStart = l.start + keep;
        l.ellipsisCount = count - keep;
        l.width = l.x[keep] + dots;
    }

    // ---------------------------------------------------------------- dibujar

    public void draw(Canvas c) { draw(c, null, null, 0); }

    public void draw(Canvas c, Path highlight, Paint highlightPaint, int cursorOffsetVertical) {
        Rect clip = new Rect();
        int first = 0, last = lines.size() - 1;
        if (c.getClipBounds(clip)) {
            first = getLineForVertical(clip.top);
            last = getLineForVertical(clip.bottom);
        }
        if (highlight != null && highlightPaint != null) c.drawPath(highlight, highlightPaint);
        drawText(c, first, last);
    }

    public void drawBackground(Canvas c) {}

    public void drawText(Canvas c) { drawText(c, 0, lines.size() - 1); }

    void drawText(Canvas c, int first, int last) {
        Spanned sp = text instanceof Spanned ? (Spanned) text : null;
        TextPaint wp = new TextPaint();
        for (int li = Math.max(0, first); li <= last && li < lines.size(); li++) {
            Line l = lines.get(li);
            int baseline = getLineBaseline(li);
            float x0 = lineX(li);
            int lineEnd = l.ellipsisStart >= 0 ? l.ellipsisStart : l.end;
            if (sp != null) {
                // Fondos de línea y márgenes (viñetas).
                int pStart = paragraphStartOf(l.start);
                int pEnd = paragraphEndOf(l.start);
                for (LineBackgroundSpan bg : sp.getSpans(l.start, l.end, LineBackgroundSpan.class))
                    bg.drawBackground(c, paint, 0, width, l.top, baseline, l.bottom, text, l.start, l.end, li);
                int mx = 0;
                for (LeadingMarginSpan m : sp.getSpans(pStart, pEnd, LeadingMarginSpan.class)) {
                    if (sp.getSpanStart(m) > pStart && pEnd > pStart) continue;
                    m.drawLeadingMargin(c, paint, mx, 1, l.top, baseline, l.bottom, text, l.start, l.end, l.paragraphStart, this);
                    mx += m.getLeadingMargin(l.paragraphStart);
                }
            }
            int i = l.start;
            while (i < lineEnd) {
                int next = sp == null ? lineEnd : sp.nextSpanTransition(i, lineEnd, CharacterStyle.class);
                wp.set(paint);
                wp.bgColor = 0;
                ReplacementSpan repl = null;
                if (sp != null) {
                    for (CharacterStyle s : sp.getSpans(i, next, CharacterStyle.class)) {
                        if (sp.getSpanStart(s) == sp.getSpanEnd(s)) continue;
                        if (s instanceof ReplacementSpan) { repl = (ReplacementSpan) s; continue; }
                        s.updateDrawState(wp);
                    }
                }
                float x = x0 + l.x[i - l.start];
                if (repl != null) {
                    int rs = sp.getSpanStart(repl), re = sp.getSpanEnd(repl);
                    if (rs >= i) repl.draw(c, text, rs, re, x, l.top, baseline, l.bottom, wp);
                    i = Math.max(Math.min(re, lineEnd), i + 1);
                    continue;
                }
                int drawEnd = next;
                while (drawEnd > i && text.charAt(drawEnd - 1) == '\n') drawEnd--;
                if (drawEnd > i) {
                    float w = l.x[drawEnd - l.start] - l.x[i - l.start];
                    if (wp.bgColor != 0) {
                        Paint bgp = new Paint();
                        bgp.setColor(wp.bgColor);
                        c.drawRect(x, l.top, x + w, l.bottom, bgp);
                    }
                    String s = text.subSequence(i, drawEnd).toString();
                    boolean under = wp.isUnderlineText(), strike = wp.isStrikeThruText();
                    wp.setUnderlineText(false);
                    wp.setStrikeThruText(false);
                    c.drawText(s, x, baseline + wp.baselineShift, wp);
                    if (under || strike) {
                        Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
                        lp.setColor(under && wp.underlineColor != 0 ? wp.underlineColor : wp.getColor());
                        float th = Math.max(1, wp.getTextSize() / 16f);
                        if (under) c.drawRect(x, baseline + wp.getTextSize() * 0.11f, x + w, baseline + wp.getTextSize() * 0.11f + th, lp);
                        if (strike) {
                            lp.setColor(wp.getColor());
                            float sy = baseline - wp.getTextSize() * 0.28f;
                            c.drawRect(x, sy, x + w, sy + th, lp);
                        }
                    }
                }
                i = next;
            }
            if (l.ellipsisStart >= 0) {
                wp.set(paint);
                c.drawText("…", x0 + l.x[l.ellipsisStart - l.start], baseline, wp);
            }
        }
    }

    private int paragraphStartOf(int off) {
        int i = off;
        while (i > 0 && text.charAt(i - 1) != '\n') i--;
        return i;
    }

    private int paragraphEndOf(int off) {
        int i = off;
        while (i < text.length() && text.charAt(i) != '\n') i++;
        return i;
    }

    public final void increaseWidthTo(int w) { if (w > width) width = w; }

    /** Ancho más grande de las líneas. */
    float maxLineWidth() {
        float m = 0;
        for (Line l : lines) m = Math.max(m, l.width + l.margin);
        return m;
    }

    public static class Directions {}
}
