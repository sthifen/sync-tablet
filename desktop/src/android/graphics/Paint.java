package android.graphics;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.Stroke;

public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1, FILTER_BITMAP_FLAG = 2, DITHER_FLAG = 4, UNDERLINE_TEXT_FLAG = 8,
            STRIKE_THRU_TEXT_FLAG = 16, FAKE_BOLD_TEXT_FLAG = 32, LINEAR_TEXT_FLAG = 64, SUBPIXEL_TEXT_FLAG = 128;

    public enum Style { FILL, STROKE, FILL_AND_STROKE }
    public enum Cap { BUTT, ROUND, SQUARE }
    public enum Join { MITER, ROUND, BEVEL }
    public enum Align { LEFT, CENTER, RIGHT }

    public static class FontMetrics {
        public float top, ascent, descent, bottom, leading;
    }

    public static class FontMetricsInt {
        public int top, ascent, descent, bottom, leading;
    }

    int flags;
    int color = 0xFF000000;
    Style style = Style.FILL;
    Cap cap = Cap.BUTT;
    Join join = Join.MITER;
    float strokeWidth, miter = 4, textSize = 12, skewX, letterSpacing, scaleX = 1;
    Align align = Align.LEFT;
    Typeface typeface;
    Shader shader;
    PathEffect pathEffect;
    ColorFilter colorFilter;
    MaskFilter maskFilter;
    Xfermode xfermode;
    float shadowRadius, shadowDx, shadowDy;
    int shadowColor;

    public Paint() { this(0); }
    public Paint(int flags) { this.flags = flags | DITHER_FLAG; }
    public Paint(Paint p) { set(p); }

    public void set(Paint p) {
        if (p == this) return;
        flags = p.flags; color = p.color; style = p.style; cap = p.cap; join = p.join; strokeWidth = p.strokeWidth; miter = p.miter;
        textSize = p.textSize; skewX = p.skewX; letterSpacing = p.letterSpacing; scaleX = p.scaleX; align = p.align; typeface = p.typeface;
        shader = p.shader; pathEffect = p.pathEffect; colorFilter = p.colorFilter; maskFilter = p.maskFilter; xfermode = p.xfermode;
        shadowRadius = p.shadowRadius; shadowDx = p.shadowDx; shadowDy = p.shadowDy; shadowColor = p.shadowColor;
        cachedFont = null;
    }

    public void reset() { set(new Paint()); }

    public int getFlags() { return flags; }
    public void setFlags(int f) { flags = f; }
    public boolean isAntiAlias() { return (flags & ANTI_ALIAS_FLAG) != 0; }
    public void setAntiAlias(boolean aa) { flags = aa ? flags | ANTI_ALIAS_FLAG : flags & ~ANTI_ALIAS_FLAG; }
    public boolean isFilterBitmap() { return (flags & FILTER_BITMAP_FLAG) != 0; }
    public void setFilterBitmap(boolean f) { flags = f ? flags | FILTER_BITMAP_FLAG : flags & ~FILTER_BITMAP_FLAG; }
    public void setDither(boolean d) { flags = d ? flags | DITHER_FLAG : flags & ~DITHER_FLAG; }
    public boolean isDither() { return (flags & DITHER_FLAG) != 0; }
    public void setSubpixelText(boolean s) {}
    public void setLinearText(boolean s) {}
    public boolean isUnderlineText() { return (flags & UNDERLINE_TEXT_FLAG) != 0; }
    public void setUnderlineText(boolean u) { flags = u ? flags | UNDERLINE_TEXT_FLAG : flags & ~UNDERLINE_TEXT_FLAG; }
    public boolean isStrikeThruText() { return (flags & STRIKE_THRU_TEXT_FLAG) != 0; }
    public void setStrikeThruText(boolean s) { flags = s ? flags | STRIKE_THRU_TEXT_FLAG : flags & ~STRIKE_THRU_TEXT_FLAG; }
    public boolean isFakeBoldText() { return (flags & FAKE_BOLD_TEXT_FLAG) != 0; }
    public void setFakeBoldText(boolean b) { flags = b ? flags | FAKE_BOLD_TEXT_FLAG : flags & ~FAKE_BOLD_TEXT_FLAG; }

    public int getColor() { return color; }
    public void setColor(int c) { color = c; }
    public void setColor(long c) { color = (int) c; }
    public int getAlpha() { return color >>> 24; }
    public void setAlpha(int a) { color = (Math.max(0, Math.min(255, a)) << 24) | (color & 0xFFFFFF); }
    public void setARGB(int a, int r, int g, int b) { color = (a << 24) | (r << 16) | (g << 8) | b; }

    public Style getStyle() { return style; }
    public void setStyle(Style s) { style = s; }
    public Cap getStrokeCap() { return cap; }
    public void setStrokeCap(Cap c) { cap = c; }
    public Join getStrokeJoin() { return join; }
    public void setStrokeJoin(Join j) { join = j; }
    public float getStrokeWidth() { return strokeWidth; }
    public void setStrokeWidth(float w) { strokeWidth = Math.max(0, w); }
    public float getStrokeMiter() { return miter; }
    public void setStrokeMiter(float m) { miter = m; }

    public Shader getShader() { return shader; }
    public Shader setShader(Shader s) { shader = s; return s; }
    public PathEffect getPathEffect() { return pathEffect; }
    public PathEffect setPathEffect(PathEffect e) { pathEffect = e; return e; }
    public ColorFilter getColorFilter() { return colorFilter; }
    public ColorFilter setColorFilter(ColorFilter f) { colorFilter = f; return f; }
    public MaskFilter getMaskFilter() { return maskFilter; }
    public MaskFilter setMaskFilter(MaskFilter m) { maskFilter = m; return m; }
    public Xfermode getXfermode() { return xfermode; }
    public Xfermode setXfermode(Xfermode x) { xfermode = x; return x; }
    public void setBlendMode(BlendMode m) { xfermode = m == null ? null : new PorterDuffXfermode(m == BlendMode.CLEAR ? PorterDuff.Mode.CLEAR : m == BlendMode.SRC ? PorterDuff.Mode.SRC : m == BlendMode.DST_OUT ? PorterDuff.Mode.DST_OUT : m == BlendMode.DST_IN ? PorterDuff.Mode.DST_IN : m == BlendMode.SRC_IN ? PorterDuff.Mode.SRC_IN : PorterDuff.Mode.SRC_OVER); }

    public void setShadowLayer(float radius, float dx, float dy, int color) { shadowRadius = radius; shadowDx = dx; shadowDy = dy; shadowColor = color; }
    public void clearShadowLayer() { shadowRadius = 0; }

    // ---------------------------------------------------------------- texto

    private Font cachedFont;
    private float cachedSize;
    private Typeface cachedFace;

    public float getTextSize() { return textSize; }
    public void setTextSize(float s) { if (s > 0) textSize = s; }
    public Typeface getTypeface() { return typeface; }
    public Typeface setTypeface(Typeface t) { typeface = t; return t; }
    public Align getTextAlign() { return align; }
    public void setTextAlign(Align a) { align = a; }
    public float getTextSkewX() { return skewX; }
    public void setTextSkewX(float s) { skewX = s; }
    public float getTextScaleX() { return scaleX; }
    public void setTextScaleX(float s) { scaleX = s; }
    public float getLetterSpacing() { return letterSpacing; }
    public void setLetterSpacing(float s) { letterSpacing = s; }
    public void setElegantTextHeight(boolean e) {}
    public void setFontFeatureSettings(String s) {}
    public void setHinting(int h) {}

    /** La letra de Java con su tamaño. */
    public Font font() {
        Typeface face = typeface == null ? Typeface.DEFAULT : typeface;
        if (cachedFont == null || cachedSize != textSize || cachedFace != face) {
            Font f = face.font(textSize);
            if (scaleX != 1) f = f.deriveFont(java.awt.geom.AffineTransform.getScaleInstance(scaleX, 1));
            cachedFont = f; cachedSize = textSize; cachedFace = face;
        }
        return cachedFont;
    }

    public float measureText(String s) { return s == null ? 0 : TextKit.width(s, font(), letterSpacing); }
    public float measureText(CharSequence s, int start, int end) { return measureText(s.subSequence(start, end).toString()); }
    public float measureText(String s, int start, int end) { return measureText(s.substring(start, end)); }
    public float measureText(char[] s, int index, int count) { return measureText(new String(s, index, count)); }

    public int getTextWidths(String s, float[] widths) { return getTextWidths(s, 0, s.length(), widths); }
    public int getTextWidths(String s, int start, int end, float[] widths) {
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < end) {
                widths[i - start] = measureText(s.substring(i, i + 2));
                widths[i - start + 1] = 0;
                i++;
            } else widths[i - start] = measureText(String.valueOf(c));
        }
        return end - start;
    }
    public int getTextWidths(CharSequence s, int start, int end, float[] widths) { return getTextWidths(s.toString(), start, end, widths); }

    public float getRunAdvance(CharSequence text, int start, int end, int contextStart, int contextEnd, boolean isRtl, int offset) {
        return measureText(text.subSequence(start, offset).toString());
    }

    public int breakText(String text, boolean measureForwards, float maxWidth, float[] measuredWidth) {
        return breakText(text, 0, text.length(), measureForwards, maxWidth, measuredWidth);
    }
    public int breakText(CharSequence text, int start, int end, boolean measureForwards, float maxWidth, float[] measuredWidth) {
        String s = text.subSequence(start, end).toString();
        int n = s.length();
        if (measureForwards) {
            int lo = 0, hi = n;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (measureText(s.substring(0, mid)) <= maxWidth) lo = mid; else hi = mid - 1;
            }
            if (measuredWidth != null) measuredWidth[0] = measureText(s.substring(0, lo));
            return lo;
        }
        int lo = 0, hi = n;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (measureText(s.substring(n - mid)) <= maxWidth) lo = mid; else hi = mid - 1;
        }
        if (measuredWidth != null) measuredWidth[0] = measureText(s.substring(n - lo));
        return lo;
    }
    public int breakText(char[] text, int index, int count, float maxWidth, float[] measuredWidth) {
        return breakText(new String(text, index, count), true, maxWidth, measuredWidth);
    }

    public void getTextBounds(String text, int start, int end, Rect bounds) {
        String s = text.substring(start, end);
        if (s.isEmpty()) { bounds.set(0, 0, 0, 0); return; }
        Font f = font();
        float x = 0;
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        for (TextKit.Run run : TextKit.runs(s, f)) {
            if (Emoji.isMarker(run.font)) {
                float w = Emoji.width(run.text, run.font.getSize2D()), box = run.font.getSize2D() * 1.12f;
                l = Math.min(l, x); r = Math.max(r, x + w); t = Math.min(t, -box * 0.86f); b = Math.max(b, box * 0.14f);
                x += w;
                continue;
            }
            java.awt.font.GlyphVector gv = run.font.createGlyphVector(TextKit.FRC, run.text);
            java.awt.geom.Rectangle2D vb = gv.getVisualBounds();
            if (!vb.isEmpty()) {
                l = Math.min(l, (float) vb.getMinX() + x); t = Math.min(t, (float) vb.getMinY());
                r = Math.max(r, (float) vb.getMaxX() + x); b = Math.max(b, (float) vb.getMaxY());
            }
            x += (float) gv.getLogicalBounds().getWidth();
        }
        if (l == Float.MAX_VALUE) { bounds.set(0, 0, Math.round(x), 0); return; }
        bounds.set((int) Math.floor(l), (int) Math.floor(t), (int) Math.ceil(r), (int) Math.ceil(b));
    }
    public void getTextBounds(CharSequence text, int start, int end, Rect bounds) { getTextBounds(text.toString(), start, end, bounds); }
    public void getTextBounds(char[] text, int index, int count, Rect bounds) { getTextBounds(new String(text, index, count), 0, count, bounds); }

    public void getTextPath(String text, int start, int end, float x, float y, Path path) {
        Font f = font();
        for (TextKit.Run run : TextKit.runs(text.substring(start, end), f)) {
            if (Emoji.isMarker(run.font)) { x += Emoji.width(run.text, run.font.getSize2D()); continue; }
            java.awt.font.GlyphVector gv = run.font.createGlyphVector(TextKit.FRC, run.text);
            path.p.append(gv.getOutline(x, y), false);
            x += (float) gv.getLogicalBounds().getWidth();
        }
    }

    public FontMetrics getFontMetrics() { FontMetrics m = new FontMetrics(); getFontMetrics(m); return m; }
    public float getFontMetrics(FontMetrics m) {
        if (m == null) m = new FontMetrics();
        TextKit.metrics(font(), m);
        return m.descent - m.ascent + m.leading;
    }
    public FontMetricsInt getFontMetricsInt() { FontMetricsInt m = new FontMetricsInt(); getFontMetricsInt(m); return m; }
    public int getFontMetricsInt(FontMetricsInt fmi) {
        FontMetrics m = getFontMetrics();
        fmi.top = (int) Math.floor(m.top); fmi.ascent = Math.round(m.ascent); fmi.descent = Math.round(m.descent);
        fmi.bottom = (int) Math.ceil(m.bottom); fmi.leading = Math.round(m.leading);
        return fmi.descent - fmi.ascent + fmi.leading;
    }
    public float getFontSpacing() { return getFontMetrics(null); }
    public float ascent() { return getFontMetrics().ascent; }
    public float descent() { return getFontMetrics().descent; }
    public boolean hasGlyph(String s) { return font().canDisplayUpTo(s) == -1; }

    // ---------------------------------------------------------------- Java2D

    /** El trazo de Java2D para este pincel. `hairline`: ancho de 1 px en pantalla. */
    Stroke stroke(float hairline) {
        float w = strokeWidth == 0 ? hairline : strokeWidth;
        int c = cap == Cap.ROUND ? BasicStroke.CAP_ROUND : cap == Cap.SQUARE ? BasicStroke.CAP_SQUARE : BasicStroke.CAP_BUTT;
        int j = join == Join.ROUND ? BasicStroke.JOIN_ROUND : join == Join.BEVEL ? BasicStroke.JOIN_BEVEL : BasicStroke.JOIN_MITER;
        if (pathEffect instanceof DashPathEffect) {
            DashPathEffect d = (DashPathEffect) pathEffect;
            float[] dash = d.intervals.clone();
            boolean ok = dash.length > 0;
            float sum = 0;
            for (int i = 0; i < dash.length; i++) { if (dash[i] < 0) ok = false; sum += dash[i]; }
            if (ok && sum > 0) {
                for (int i = 0; i < dash.length; i++) if (dash[i] == 0) dash[i] = 0.001f;
                float phase = d.phase % sum;
                if (phase < 0) phase += sum;
                return new BasicStroke(w, c, j, Math.max(1, miter), dash, phase);
            }
        }
        return new BasicStroke(w, c, j, Math.max(1, miter));
    }

    /** El color con el filtro aplicado. */
    int filteredColor() { return colorFilter == null ? color : colorFilter.filter(color); }
}
