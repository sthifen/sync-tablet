package android.graphics;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;

/**
 * El lienzo de Android sobre un Graphics2D: el de la ventana o el de un
 * mapa de bits. Guarda y recupera la transformación y el recorte, y las
 * capas (saveLayer) son imágenes aparte que se funden al cerrar.
 */
public class Canvas {
    public static final int ALL_SAVE_FLAG = 0x1F;

    public enum EdgeType { BW, AA }
    public enum VertexMode { TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN }

    private Graphics2D g;
    private int width, height;
    private Bitmap bitmap;
    boolean hardware;

    private static final class State {
        final AffineTransform transform;
        final Shape deviceClip;
        final Layer layer;
        State(AffineTransform t, Shape c, Layer l) { transform = t; deviceClip = c; layer = l; }
    }

    private static final class Layer {
        final Graphics2D parent;
        final BufferedImage image;
        final float alpha;
        final Composite composite;
        Layer(Graphics2D parent, BufferedImage image, float alpha, Composite composite) { this.parent = parent; this.image = image; this.alpha = alpha; this.composite = composite; }
    }

    private final ArrayList<State> stack = new ArrayList<>();
    private AffineTransform base = new AffineTransform();

    public Canvas() {}

    public Canvas(Bitmap b) { setBitmap(b); }

    /** El lienzo de la ventana: `g` ya viene en píxeles de la ventana. */
    public Canvas(Graphics2D g, int width, int height) {
        this.g = g;
        this.width = width;
        this.height = height;
        this.hardware = true;
        base = g.getTransform();
        hints(g);
    }

    static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    public void setBitmap(Bitmap b) {
        release();
        stack.clear();
        bitmap = b;
        if (b == null) { g = null; width = height = 0; return; }
        g = b.image.createGraphics();
        hints(g);
        width = b.getWidth();
        height = b.getHeight();
        base = new AffineTransform();
    }

    /** Suelta el Graphics2D de un mapa de bits. */
    public void release() {
        if (bitmap != null && g != null) g.dispose();
    }

    public Graphics2D graphics() { return g; }
    public boolean isHardwareAccelerated() { return hardware; }
    /** Para lienzos especiales (PDF): no se comportan como la pantalla. */
    public void setHardwareAccelerated(boolean h) { hardware = h; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getDensity() { return 160; }
    public void setDensity(int d) {}
    public int getMaximumBitmapWidth() { return 16384; }
    public int getMaximumBitmapHeight() { return 16384; }
    public void enableZ() {}
    public void disableZ() {}

    private void touch() { if (bitmap != null) bitmap.touched(); }

    // ---------------------------------------------------------------- estado

    public int save() {
        AffineTransform t = g.getTransform();
        Shape c = g.getClip();
        stack.add(new State(t, c == null ? null : t.createTransformedShape(c), null));
        return stack.size();
    }

    public int save(int flags) { return save(); }

    public int getSaveCount() { return stack.size() + 1; }

    public void restore() {
        if (stack.isEmpty()) throw new IllegalStateException("Underflow in restore - more restores than saves");
        State s = stack.remove(stack.size() - 1);
        if (s.layer != null) {
            Layer l = s.layer;
            g.dispose();
            g = l.parent;
            AffineTransform keep = g.getTransform();
            Shape keepClip = g.getClip();
            g.setTransform(new AffineTransform());
            g.setClip(null);
            Composite old = g.getComposite();
            g.setComposite(l.composite != null ? l.composite : AlphaComposite.getInstance(AlphaComposite.SRC_OVER, l.alpha));
            g.drawImage(l.image, 0, 0, null);
            g.setComposite(old);
            g.setTransform(keep);
            g.setClip(keepClip);
            touch();
        }
        g.setTransform(new AffineTransform());
        g.setClip(s.deviceClip);
        g.setTransform(s.transform);
    }

    public void restoreToCount(int count) {
        if (count < 1) throw new IllegalArgumentException("Underflow in restoreToCount");
        while (stack.size() >= count) restore();
    }

    public int saveLayer(RectF bounds, Paint paint) { return layer(paint == null ? 1f : paint.getAlpha() / 255f, paint); }
    public int saveLayer(RectF bounds, Paint paint, int flags) { return saveLayer(bounds, paint); }
    public int saveLayer(float l, float t, float r, float b, Paint paint) { return saveLayer(null, paint); }
    public int saveLayer(float l, float t, float r, float b, Paint paint, int flags) { return saveLayer(null, paint); }
    public int saveLayerAlpha(RectF bounds, int alpha) { return layer(alpha / 255f, null); }
    public int saveLayerAlpha(RectF bounds, int alpha, int flags) { return saveLayerAlpha(bounds, alpha); }
    public int saveLayerAlpha(float l, float t, float r, float b, int alpha) { return layer(alpha / 255f, null); }
    public int saveLayerAlpha(float l, float t, float r, float b, int alpha, int flags) { return layer(alpha / 255f, null); }

    private int layer(float alpha, Paint paint) {
        AffineTransform t = g.getTransform();
        Shape c = g.getClip();
        Shape deviceClip = c == null ? null : t.createTransformedShape(c);
        // La capa cubre el recorte actual, en píxeles del destino.
        BufferedImage img = new BufferedImage(Math.max(1, deviceWidth()), Math.max(1, deviceHeight()), BufferedImage.TYPE_INT_ARGB_PRE);
        Composite comp = null;
        if (paint != null && paint.xfermode instanceof PorterDuffXfermode) comp = PorterDuff.composite(((PorterDuffXfermode) paint.xfermode).mode, alpha);
        Layer l = new Layer(g, img, alpha, comp);
        stack.add(new State(t, deviceClip, l));
        Graphics2D lg = img.createGraphics();
        hints(lg);
        lg.setClip(deviceClip);
        lg.setTransform(t);
        g = lg;
        return stack.size();
    }

    private int deviceWidth() {
        if (bitmap != null) return width;
        return (int) Math.ceil(width * base.getScaleX()) + (int) Math.abs(base.getTranslateX());
    }

    private int deviceHeight() {
        if (bitmap != null) return height;
        return (int) Math.ceil(height * base.getScaleY()) + (int) Math.abs(base.getTranslateY());
    }

    // ---------------------------------------------------------------- transformación

    public void translate(float dx, float dy) { if (dx != 0 || dy != 0) g.translate(dx, dy); }
    public void scale(float sx, float sy) { if (sx != 1 || sy != 1) g.scale(sx, sy); }
    public final void scale(float sx, float sy, float px, float py) { translate(px, py); scale(sx, sy); translate(-px, -py); }
    public void rotate(float degrees) { if (degrees != 0) g.rotate(Math.toRadians(degrees)); }
    public final void rotate(float degrees, float px, float py) { translate(px, py); rotate(degrees); translate(-px, -py); }
    public void skew(float sx, float sy) { g.shear(sx, sy); }
    public void concat(Matrix m) { if (m != null) g.transform(m.t); }

    public void setMatrix(Matrix m) {
        AffineTransform t = new AffineTransform(base);
        if (m != null) t.concatenate(m.t);
        g.setTransform(t);
    }

    public void getMatrix(Matrix m) {
        try {
            AffineTransform t = base.createInverse();
            t.concatenate(g.getTransform());
            m.t.setTransform(t);
        } catch (NoninvertibleTransformException e) {
            m.reset();
        }
    }

    public final Matrix getMatrix() { Matrix m = new Matrix(); getMatrix(m); return m; }

    /** Escala actual (para el ancho de 1 px de las líneas finas). */
    private float hairline() {
        AffineTransform t = g.getTransform();
        double s = Math.sqrt(Math.abs(t.getDeterminant()));
        return s > 0 ? (float) (1 / s) : 1;
    }

    // ---------------------------------------------------------------- recorte

    public boolean clipRect(RectF r) { return clipRect(r.left, r.top, r.right, r.bottom); }
    public boolean clipRect(Rect r) { return clipRect(r.left, r.top, r.right, r.bottom); }
    public boolean clipRect(int l, int t, int r, int b) { return clipRect((float) l, (float) t, (float) r, (float) b); }

    public boolean clipRect(float l, float t, float r, float b) {
        g.clip(new Rectangle2D.Float(l, t, Math.max(0, r - l), Math.max(0, b - t)));
        return !isClipEmpty();
    }

    public boolean clipRect(RectF r, Region.Op op) { return clip(new Rectangle2D.Float(r.left, r.top, r.width(), r.height()), op); }
    public boolean clipRect(Rect r, Region.Op op) { return clip(new Rectangle2D.Float(r.left, r.top, r.width(), r.height()), op); }
    public boolean clipRect(float l, float t, float r, float b, Region.Op op) { return clip(new Rectangle2D.Float(l, t, r - l, b - t), op); }

    public boolean clipOutRect(RectF r) { return clipRect(r, Region.Op.DIFFERENCE); }
    public boolean clipOutRect(Rect r) { return clipRect(r, Region.Op.DIFFERENCE); }
    public boolean clipOutRect(float l, float t, float r, float b) { return clipRect(l, t, r, b, Region.Op.DIFFERENCE); }
    public boolean clipOutRect(int l, int t, int r, int b) { return clipRect((float) l, (float) t, (float) r, (float) b, Region.Op.DIFFERENCE); }

    public boolean clipPath(Path p) { g.clip(p.p); return !isClipEmpty(); }
    public boolean clipPath(Path p, Region.Op op) { return clip(p.p, op); }
    public boolean clipOutPath(Path p) { return clip(p.p, Region.Op.DIFFERENCE); }

    private boolean clip(Shape s, Region.Op op) {
        if (op == Region.Op.INTERSECT) { g.clip(s); return !isClipEmpty(); }
        Shape cur = g.getClip();
        Area a = new Area(cur != null ? cur : new Rectangle2D.Float(-1e6f, -1e6f, 2e6f, 2e6f));
        Area b = new Area(s);
        switch (op) {
            case DIFFERENCE: a.subtract(b); break;
            case UNION: a.add(b); break;
            case XOR: a.exclusiveOr(b); break;
            case REVERSE_DIFFERENCE: b.subtract(a); a = b; break;
            case REPLACE: a = b; break;
            default: a.intersect(b);
        }
        g.setClip(a);
        return !isClipEmpty();
    }

    private boolean isClipEmpty() {
        Shape c = g.getClip();
        return c != null && c.getBounds2D().isEmpty();
    }

    public boolean getClipBounds(Rect out) {
        Shape c = g.getClip();
        Rectangle2D b;
        if (c == null) {
            try {
                b = g.getTransform().createInverse().createTransformedShape(new Rectangle2D.Float(0, 0, deviceWidth(), deviceHeight())).getBounds2D();
            } catch (NoninvertibleTransformException e) {
                out.setEmpty();
                return false;
            }
        } else b = c.getBounds2D();
        out.set((int) Math.floor(b.getMinX()), (int) Math.floor(b.getMinY()), (int) Math.ceil(b.getMaxX()), (int) Math.ceil(b.getMaxY()));
        return !out.isEmpty();
    }

    public final Rect getClipBounds() { Rect r = new Rect(); getClipBounds(r); return r; }

    public boolean quickReject(RectF r) { return quickReject(r.left, r.top, r.right, r.bottom); }
    public boolean quickReject(RectF r, EdgeType t) { return quickReject(r); }
    public boolean quickReject(Path p) { RectF r = new RectF(); p.computeBounds(r, true); return quickReject(r); }
    public boolean quickReject(Path p, EdgeType t) { return quickReject(p); }
    public boolean quickReject(float l, float t, float r, float b, EdgeType e) { return quickReject(l, t, r, b); }

    public boolean quickReject(float l, float t, float r, float b) {
        Rect c = getClipBounds();
        return !(l < c.right && c.left < r && t < c.bottom && c.top < b);
    }

    // ---------------------------------------------------------------- pintar

    private Composite applyPaint(Paint p, int colorOverride, boolean useShader) {
        Composite old = g.getComposite();
        int color = p == null ? colorOverride : p.filteredColor();
        int alpha = color >>> 24;
        if (p != null) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, p.isAntiAlias() ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
            if (p.xfermode instanceof PorterDuffXfermode) {
                PorterDuff.Mode m = ((PorterDuffXfermode) p.xfermode).mode;
                g.setComposite(PorterDuff.composite(m, 1f));
            }
        }
        if (useShader && p != null && p.shader != null) {
            g.setPaint(p.shader.awt(alpha));
        } else {
            g.setColor(new java.awt.Color((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, alpha));
        }
        return old;
    }

    private void drawShape(Shape s, Paint p, boolean forceStroke) {
        if (p.maskFilter instanceof BlurMaskFilter && !(p.shader != null)) {
            drawBlurred(s, p, forceStroke);
            return;
        }
        if (p.shadowRadius > 0 && (p.shadowColor >>> 24) != 0) drawShadow(s, p, forceStroke);
        Composite old = applyPaint(p, 0, true);
        Paint.Style st = forceStroke ? Paint.Style.STROKE : p.style;
        if (st == Paint.Style.FILL || st == Paint.Style.FILL_AND_STROKE) g.fill(s);
        if (st == Paint.Style.STROKE || st == Paint.Style.FILL_AND_STROKE) {
            g.setStroke(p.stroke(hairline()));
            g.draw(s);
        }
        g.setComposite(old);
        touch();
    }

    private void drawShadow(Shape s, Paint p, boolean forceStroke) {
        AffineTransform t = g.getTransform();
        Paint sp = new Paint(p);
        sp.shadowRadius = 0;
        sp.shader = null;
        sp.maskFilter = new BlurMaskFilter(p.shadowRadius, BlurMaskFilter.Blur.NORMAL);
        sp.color = (((p.shadowColor >>> 24) * p.getAlpha() / 255) << 24) | (p.shadowColor & 0xFFFFFF);
        g.translate(p.shadowDx, p.shadowDy);
        drawBlurred(s, sp, forceStroke);
        g.setTransform(t);
    }

    /** Figura difuminada: se pinta en una imagen aparte y se desenfoca. */
    private void drawBlurred(Shape s, Paint p, boolean forceStroke) {
        float radius = ((BlurMaskFilter) p.maskFilter).radius;
        AffineTransform t = g.getTransform();
        double scale = Math.sqrt(Math.abs(t.getDeterminant()));
        Paint.Style st = forceStroke ? Paint.Style.STROKE : p.style;
        Shape dev = st == Paint.Style.FILL ? t.createTransformedShape(s)
                : t.createTransformedShape(st == Paint.Style.STROKE ? p.stroke(hairline()).createStrokedShape(s) : new Area(s) {{ add(new Area(p.stroke(hairline()).createStrokedShape(s))); }});
        float r = (float) (radius * scale);
        Rectangle2D b = dev.getBounds2D();
        int pad = (int) Math.ceil(r * 2) + 2;
        int x0 = (int) Math.floor(b.getMinX()) - pad, y0 = (int) Math.floor(b.getMinY()) - pad;
        int w = (int) Math.ceil(b.getWidth()) + pad * 2, h = (int) Math.ceil(b.getHeight()) + pad * 2;
        if (w <= 0 || h <= 0 || (long) w * h > 16_000_000L) return;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D ig = img.createGraphics();
        hints(ig);
        int color = p.filteredColor();
        ig.setColor(new java.awt.Color((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, color >>> 24));
        ig.translate(-x0, -y0);
        ig.fill(dev);
        ig.dispose();
        BufferedImage blurred = r >= 1 ? Blur.blur(img, r) : img;
        AffineTransform keep = g.getTransform();
        g.setTransform(new AffineTransform());
        g.drawImage(blurred, x0, y0, null);
        g.setTransform(keep);
        touch();
    }

    public void drawColor(int color) {
        drawColor(color, PorterDuff.Mode.SRC_OVER);
    }

    public void drawColor(int color, PorterDuff.Mode mode) {
        Composite old = g.getComposite();
        g.setComposite(PorterDuff.composite(mode, 1f));
        g.setColor(new java.awt.Color((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, color >>> 24));
        fillAll();
        g.setComposite(old);
        touch();
    }

    public void drawColor(int color, BlendMode mode) { drawColor(color, mode == BlendMode.CLEAR ? PorterDuff.Mode.CLEAR : mode == BlendMode.SRC ? PorterDuff.Mode.SRC : PorterDuff.Mode.SRC_OVER); }

    private void fillAll() {
        AffineTransform t = g.getTransform();
        g.setTransform(new AffineTransform());
        g.fillRect(0, 0, deviceWidth(), deviceHeight());
        g.setTransform(t);
    }

    public void drawARGB(int a, int r, int gr, int b) { drawColor((a << 24) | (r << 16) | (gr << 8) | b); }
    public void drawRGB(int r, int gr, int b) { drawColor(0xFF000000 | (r << 16) | (gr << 8) | b); }

    public void drawPaint(Paint p) {
        Composite old = applyPaint(p, 0, true);
        fillAll();
        g.setComposite(old);
        touch();
    }

    public void drawLine(float x0, float y0, float x1, float y1, Paint p) { drawShape(new Line2D.Float(x0, y0, x1, y1), p, true); }

    public void drawLines(float[] pts, Paint p) { drawLines(pts, 0, pts.length, p); }

    public void drawLines(float[] pts, int offset, int count, Paint p) {
        java.awt.geom.Path2D.Float path = new java.awt.geom.Path2D.Float();
        for (int i = offset; i + 3 < offset + count; i += 4) {
            path.moveTo(pts[i], pts[i + 1]);
            path.lineTo(pts[i + 2], pts[i + 3]);
        }
        drawShape(path, p, true);
    }

    public void drawPoint(float x, float y, Paint p) {
        float w = p.strokeWidth == 0 ? hairline() : p.strokeWidth;
        Paint fill = new Paint(p);
        fill.style = Paint.Style.FILL;
        Shape s = p.cap == Paint.Cap.ROUND ? new Ellipse2D.Float(x - w / 2, y - w / 2, w, w) : new Rectangle2D.Float(x - w / 2, y - w / 2, w, w);
        drawShape(s, fill, false);
    }

    public void drawPoints(float[] pts, Paint p) { for (int i = 0; i + 1 < pts.length; i += 2) drawPoint(pts[i], pts[i + 1], p); }
    public void drawPoints(float[] pts, int offset, int count, Paint p) { for (int i = offset; i + 1 < offset + count; i += 2) drawPoint(pts[i], pts[i + 1], p); }

    public void drawRect(RectF r, Paint p) { drawRect(r.left, r.top, r.right, r.bottom, p); }
    public void drawRect(Rect r, Paint p) { drawRect(r.left, r.top, r.right, r.bottom, p); }
    public void drawRect(float l, float t, float r, float b, Paint p) {
        if (r < l) { float x = l; l = r; r = x; }
        if (b < t) { float x = t; t = b; b = x; }
        drawShape(new Rectangle2D.Float(l, t, r - l, b - t), p, false);
    }

    public void drawRoundRect(RectF r, float rx, float ry, Paint p) { drawRoundRect(r.left, r.top, r.right, r.bottom, rx, ry, p); }
    public void drawRoundRect(float l, float t, float r, float b, float rx, float ry, Paint p) {
        float w = r - l, h = b - t;
        if (w < 0 || h < 0) return;
        rx = Math.min(rx, w / 2); ry = Math.min(ry, h / 2);
        drawShape(new RoundRectangle2D.Float(l, t, w, h, rx * 2, ry * 2), p, false);
    }

    public void drawCircle(float cx, float cy, float r, Paint p) {
        if (r <= 0) return;
        drawShape(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2), p, false);
    }

    public void drawOval(RectF r, Paint p) { drawOval(r.left, r.top, r.right, r.bottom, p); }
    public void drawOval(float l, float t, float r, float b, Paint p) { drawShape(new Ellipse2D.Float(l, t, r - l, b - t), p, false); }

    public void drawArc(RectF oval, float start, float sweep, boolean useCenter, Paint p) { drawArc(oval.left, oval.top, oval.right, oval.bottom, start, sweep, useCenter, p); }
    public void drawArc(float l, float t, float r, float b, float start, float sweep, boolean useCenter, Paint p) {
        if (sweep >= 360 || sweep <= -360) { drawOval(l, t, r, b, p); return; }
        drawShape(new Arc2D.Float(l, t, r - l, b - t, -start, -sweep, useCenter ? Arc2D.PIE : Arc2D.OPEN), p, false);
    }

    public void drawPath(Path path, Paint p) {
        if (path.isInverseFillType()) {
            Area a = new Area(new Rectangle2D.Float(-1e5f, -1e5f, 2e5f, 2e5f));
            a.subtract(new Area(path.p));
            drawShape(a, p, false);
            return;
        }
        drawShape(path.p, p, false);
    }

    // ---------------------------------------------------------------- texto

    public void drawText(String text, float x, float y, Paint p) { drawTextImpl(text, x, y, p); }
    public void drawText(String text, int start, int end, float x, float y, Paint p) { drawTextImpl(text.substring(start, end), x, y, p); }
    public void drawText(CharSequence text, int start, int end, float x, float y, Paint p) { drawTextImpl(text.subSequence(start, end).toString(), x, y, p); }
    public void drawText(char[] text, int index, int count, float x, float y, Paint p) { drawTextImpl(new String(text, index, count), x, y, p); }
    public void drawTextRun(CharSequence text, int start, int end, int cs, int ce, float x, float y, boolean rtl, Paint p) { drawTextImpl(text.subSequence(start, end).toString(), x, y, p); }
    public void drawTextRun(char[] text, int index, int count, int cs, int cc, float x, float y, boolean rtl, Paint p) { drawTextImpl(new String(text, index, count), x, y, p); }

    public void drawTextOnPath(String text, Path path, float hOffset, float vOffset, Paint p) {
        float[] pts = path.approximate(0.5f);
        if (pts.length < 6) return;
        drawTextImpl(text, pts[1] + hOffset, pts[2] + vOffset, p);
    }

    private void drawTextImpl(String s, float x, float y, Paint p) {
        if (s.isEmpty()) return;
        Font f = p.font();
        float w = 0;
        if (p.align != Paint.Align.LEFT || p.isUnderlineText() || p.isStrikeThruText()) {
            w = TextKit.width(s, f, p.letterSpacing);
            if (p.align == Paint.Align.CENTER) x -= w / 2;
            else if (p.align == Paint.Align.RIGHT) x -= w;
        }
        AffineTransform keep = null;
        if (p.skewX != 0) {
            keep = g.getTransform();
            g.translate(x, y);
            g.shear(p.skewX, 0);
            g.translate(-x, -y);
        }
        if (p.shadowRadius > 0 && (p.shadowColor >>> 24) != 0) {
            Path outline = new Path();
            p.getTextPath(s, 0, s.length(), x, y, outline);
            Paint sp = new Paint(p);
            sp.style = Paint.Style.FILL;
            sp.shader = null;
            drawShadow(outline.p, sp, false);
        }
        if (p.style != Paint.Style.FILL || p.isFakeBoldText() || p.maskFilter != null) {
            Path outline = new Path();
            Paint q = new Paint(p);
            q.shadowRadius = 0;
            q.align = Paint.Align.LEFT;
            q.getTextPath(s, 0, s.length(), x, y, outline);
            if (p.isFakeBoldText() && p.style == Paint.Style.FILL) {
                q.style = Paint.Style.FILL_AND_STROKE;
                q.strokeWidth = p.textSize / 28f;
                q.join = Paint.Join.ROUND;
            }
            drawShape(outline.p, q, false);
        } else {
            Composite old = applyPaint(p, 0, true);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            TextKit.draw(g, s, x, y, f, p.letterSpacing);
            g.setComposite(old);
        }
        if (p.isUnderlineText() || p.isStrikeThruText()) {
            Paint line = new Paint(p);
            line.style = Paint.Style.FILL;
            float th = Math.max(1, p.textSize / 18f);
            if (p.isUnderlineText()) drawShape(new Rectangle2D.Float(x, y + p.textSize * 0.11f, w, th), line, false);
            if (p.isStrikeThruText()) drawShape(new Rectangle2D.Float(x, y - p.textSize * 0.28f, w, th), line, false);
        }
        if (keep != null) g.setTransform(keep);
        touch();
    }

    // ---------------------------------------------------------------- imágenes

    private BufferedImage image(Bitmap b, Paint p) {
        BufferedImage img = b.image;
        if (p != null && p.colorFilter != null) img = Filtered.apply(b, p.colorFilter);
        return img;
    }

    private Composite bitmapComposite(Paint p) {
        Composite old = g.getComposite();
        float a = p == null ? 1f : p.getAlpha() / 255f;
        if (p != null && p.xfermode instanceof PorterDuffXfermode) g.setComposite(PorterDuff.composite(((PorterDuffXfermode) p.xfermode).mode, a));
        else if (a < 1f) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, p == null || p.isFilterBitmap() || hardware
                ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        return old;
    }

    public void drawBitmap(Bitmap b, float left, float top, Paint p) {
        if (b == null) return;
        if (p != null && p.shader == null && b.getConfig() == Bitmap.Config.ALPHA_8) { tintAlpha(b, left, top, p); return; }
        Composite old = bitmapComposite(p);
        g.drawImage(image(b, p), AffineTransform.getTranslateInstance(left, top), null);
        g.setComposite(old);
        touch();
    }

    private void tintAlpha(Bitmap b, float left, float top, Paint p) {
        Composite old = bitmapComposite(p);
        g.drawImage(Filtered.apply(b, new PorterDuffColorFilter(p.color | 0xFF000000, PorterDuff.Mode.SRC_IN)), AffineTransform.getTranslateInstance(left, top), null);
        g.setComposite(old);
        touch();
    }

    public void drawBitmap(Bitmap b, Rect src, RectF dst, Paint p) {
        if (b == null) return;
        drawBitmapRect(b, src == null ? 0 : src.left, src == null ? 0 : src.top, src == null ? b.getWidth() : src.right, src == null ? b.getHeight() : src.bottom,
                dst.left, dst.top, dst.right, dst.bottom, p);
    }

    public void drawBitmap(Bitmap b, Rect src, Rect dst, Paint p) {
        if (b == null) return;
        drawBitmapRect(b, src == null ? 0 : src.left, src == null ? 0 : src.top, src == null ? b.getWidth() : src.right, src == null ? b.getHeight() : src.bottom,
                dst.left, dst.top, dst.right, dst.bottom, p);
    }

    private void drawBitmapRect(Bitmap b, int sl, int st, int sr, int sb, float dl, float dt, float dr, float db, Paint p) {
        sl = Math.max(0, sl); st = Math.max(0, st); sr = Math.min(b.getWidth(), sr); sb = Math.min(b.getHeight(), sb);
        if (sr <= sl || sb <= st || dr == dl || db == dt) return;
        BufferedImage img = image(b, p);
        if (sl != 0 || st != 0 || sr != img.getWidth() || sb != img.getHeight()) img = img.getSubimage(sl, st, sr - sl, sb - st);
        AffineTransform t = AffineTransform.getTranslateInstance(dl, dt);
        t.scale((dr - dl) / (sr - sl), (db - dt) / (sb - st));
        Composite old = bitmapComposite(p);
        // Al encoger mucho, la interpolación bilineal sola se ve dentada.
        double shrink = Math.sqrt(Math.abs(g.getTransform().getDeterminant() * t.getDeterminant()));
        if (shrink < 0.5 && img.getWidth() * img.getHeight() > 4096) {
            img = Filtered.downscaled(img, shrink);
            t.scale((double) (sr - sl) / img.getWidth(), (double) (sb - st) / img.getHeight());
        }
        g.drawImage(img, t, null);
        g.setComposite(old);
        touch();
    }

    public void drawBitmap(Bitmap b, Matrix m, Paint p) {
        if (b == null) return;
        Composite old = bitmapComposite(p);
        g.drawImage(image(b, p), m == null ? new AffineTransform() : m.t, null);
        g.setComposite(old);
        touch();
    }

    public void drawBitmap(int[] colors, int offset, int stride, float x, float y, int width, int height, boolean hasAlpha, Paint p) {
        drawBitmap(Bitmap.createBitmap(colors, offset, stride, width, height, Bitmap.Config.ARGB_8888), x, y, p);
    }

    /** Para RenderNode. */
    void drawImage(BufferedImage img, float x, float y, float alpha) {
        Composite old = g.getComposite();
        if (alpha < 1) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, AffineTransform.getTranslateInstance(x, y), null);
        g.setComposite(old);
        touch();
    }

    public void drawRenderNode(RenderNode node) { node.drawOn(this); }

    public void drawPicture(Picture picture) { picture.draw(this); }
    public void drawPicture(Picture picture, RectF dst) {
        save();
        translate(dst.left, dst.top);
        if (picture.getWidth() > 0 && picture.getHeight() > 0) scale(dst.width() / picture.getWidth(), dst.height() / picture.getHeight());
        picture.draw(this);
        restore();
    }

    public void drawVertices(VertexMode mode, int vertexCount, float[] verts, int vertOffset, float[] texs, int texOffset, int[] colors, int colorOffset,
                             short[] indices, int indexOffset, int indexCount, Paint p) {
        java.awt.geom.Path2D.Float path = new java.awt.geom.Path2D.Float();
        for (int i = 0; i + 5 < vertexCount * 2; i += 6) {
            path.moveTo(verts[vertOffset + i], verts[vertOffset + i + 1]);
            path.lineTo(verts[vertOffset + i + 2], verts[vertOffset + i + 3]);
            path.lineTo(verts[vertOffset + i + 4], verts[vertOffset + i + 5]);
            path.closePath();
        }
        drawShape(path, p, false);
    }
}
