package android.graphics.pdf;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Paint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ImageObserver;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.RenderableImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.AttributedCharacterIterator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Lienzo de una página de PDF: lo que se dibuja se escribe como operadores de PDF. */
final class PdfCanvas extends android.graphics.Canvas {
    private final Recorder rec;

    PdfCanvas(int w, int h) { this(new Recorder(w, h), w, h); }

    private PdfCanvas(Recorder r, int w, int h) {
        super(r, w, h);
        rec = r;
        setHardwareAccelerated(false);
    }

    void finish() { rec.endClip(); }

    void writePage(PdfWriter w, int pw, int ph) {
        StringBuilder res = new StringBuilder("<< ");
        if (!rec.images.isEmpty()) {
            res.append("/XObject << ");
            for (Map.Entry<BufferedImage, String> e : rec.images.entrySet()) {
                int id = writeImage(w, e.getKey());
                res.append("/").append(e.getValue()).append(" ").append(id).append(" 0 R ");
            }
            res.append(">> ");
        }
        if (!rec.alphas.isEmpty()) {
            res.append("/ExtGState << ");
            for (Map.Entry<String, String> e : rec.alphas.entrySet()) {
                int id = w.add("<< /Type /ExtGState /ca " + e.getKey() + " /CA " + e.getKey() + " >>");
                res.append("/").append(e.getValue()).append(" ").append(id).append(" 0 R ");
            }
            res.append(">> ");
        }
        res.append(">>");
        byte[] content = ("1 0 0 -1 0 " + ph + " cm\n" + rec.out).getBytes(StandardCharsets.ISO_8859_1);
        int contents = w.stream("", content, true);
        w.page(pw, ph, contents, res.toString());
    }

    private static int writeImage(PdfWriter w, BufferedImage img) {
        int iw = img.getWidth(), ih = img.getHeight();
        int[] px = img.getRGB(0, 0, iw, ih, null, 0, iw);
        boolean alpha = false;
        for (int v : px) if ((v >>> 24) != 0xFF) { alpha = true; break; }
        byte[] rgb = new byte[iw * ih * 3];
        byte[] a = alpha ? new byte[iw * ih] : null;
        for (int i = 0; i < px.length; i++) {
            int v = px[i];
            rgb[i * 3] = (byte) (v >> 16); rgb[i * 3 + 1] = (byte) (v >> 8); rgb[i * 3 + 2] = (byte) v;
            if (a != null) a[i] = (byte) (v >>> 24);
        }
        String smask = "";
        if (a != null) {
            int sid = w.stream("/Type /XObject /Subtype /Image /Width " + iw + " /Height " + ih + " /ColorSpace /DeviceGray /BitsPerComponent 8", a, true);
            smask = " /SMask " + sid + " 0 R";
        }
        return w.stream("/Type /XObject /Subtype /Image /Width " + iw + " /Height " + ih + " /ColorSpace /DeviceRGB /BitsPerComponent 8" + smask, rgb, true);
    }

    /** Un Graphics2D que anota en vez de pintar. */
    private static final class Recorder extends Graphics2D {
        final StringBuilder out = new StringBuilder();
        final IdentityHashMap<BufferedImage, String> images = new IdentityHashMap<>();
        final LinkedHashMap<String, String> alphas = new LinkedHashMap<>();
        private final int w, h;
        private AffineTransform transform = new AffineTransform();
        private Shape clip; // en coordenadas de la página
        private Shape emittedClip;
        private boolean inClip;
        private Paint paint = Color.BLACK;
        private Color background = Color.WHITE;
        private Stroke stroke = new BasicStroke();
        private Composite composite = AlphaComposite.SrcOver;
        private Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        private final RenderingHints hints = new RenderingHints(null);

        Recorder(int w, int h) { this.w = w; this.h = h; }

        private Recorder(Recorder o) {
            this(o.w, o.h);
        }

        private static String n(double v) {
            if (Math.abs(v) < 1e-6) return "0";
            String s = String.format(Locale.ROOT, "%.3f", v);
            if (s.indexOf('.') >= 0) { s = s.replaceAll("0+$", ""); if (s.endsWith(".")) s = s.substring(0, s.length() - 1); }
            return s;
        }

        private void path(Shape s, AffineTransform t) {
            PathIterator it = s.getPathIterator(t);
            double[] c = new double[6];
            double lx = 0, ly = 0;
            for (; !it.isDone(); it.next()) {
                switch (it.currentSegment(c)) {
                    case PathIterator.SEG_MOVETO: out.append(n(c[0])).append(' ').append(n(c[1])).append(" m\n"); lx = c[0]; ly = c[1]; break;
                    case PathIterator.SEG_LINETO: out.append(n(c[0])).append(' ').append(n(c[1])).append(" l\n"); lx = c[0]; ly = c[1]; break;
                    case PathIterator.SEG_QUADTO: {
                        double x1 = lx + 2.0 / 3 * (c[0] - lx), y1 = ly + 2.0 / 3 * (c[1] - ly);
                        double x2 = c[2] + 2.0 / 3 * (c[0] - c[2]), y2 = c[3] + 2.0 / 3 * (c[1] - c[3]);
                        out.append(n(x1)).append(' ').append(n(y1)).append(' ').append(n(x2)).append(' ').append(n(y2)).append(' ').append(n(c[2])).append(' ').append(n(c[3])).append(" c\n");
                        lx = c[2]; ly = c[3];
                        break;
                    }
                    case PathIterator.SEG_CUBICTO:
                        out.append(n(c[0])).append(' ').append(n(c[1])).append(' ').append(n(c[2])).append(' ').append(n(c[3])).append(' ').append(n(c[4])).append(' ').append(n(c[5])).append(" c\n");
                        lx = c[4]; ly = c[5];
                        break;
                    case PathIterator.SEG_CLOSE: out.append("h\n"); break;
                    default:
                }
            }
        }

        void endClip() { if (inClip) { out.append("Q\n"); inClip = false; } emittedClip = null; }

        private void applyClip() {
            if (clip == emittedClip) return;
            endClip();
            if (clip != null) {
                out.append("q\n");
                path(clip, null);
                out.append(clip instanceof Area || windingEvenOdd(clip) ? "W* n\n" : "W n\n");
                inClip = true;
            }
            emittedClip = clip;
        }

        private static boolean windingEvenOdd(Shape s) { return s.getPathIterator(null).getWindingRule() == PathIterator.WIND_EVEN_ODD; }

        private float alpha() {
            float a = 1;
            if (composite instanceof AlphaComposite) {
                AlphaComposite ac = (AlphaComposite) composite;
                if (ac.getRule() == AlphaComposite.CLEAR || ac.getRule() == AlphaComposite.DST) return 0;
                a = ac.getAlpha();
            }
            return a;
        }

        private boolean setColor(Color c, boolean fill) {
            float a = alpha() * c.getAlpha() / 255f;
            if (a <= 0.001f) return false;
            String key = n(Math.round(a * 1000) / 1000.0);
            if (a < 0.999f) {
                String name = alphas.computeIfAbsent(key, k -> "GA" + alphas.size());
                out.append('/').append(name).append(" gs\n");
            } else if (!alphas.isEmpty()) {
                String name = alphas.computeIfAbsent("1", k -> "GA" + alphas.size());
                out.append('/').append(name).append(" gs\n");
            }
            out.append(n(c.getRed() / 255.0)).append(' ').append(n(c.getGreen() / 255.0)).append(' ').append(n(c.getBlue() / 255.0)).append(fill ? " rg\n" : " RG\n");
            return true;
        }

        @Override
        public void fill(Shape s) {
            if (paint instanceof Color) {
                applyClip();
                out.append("q\n");
                if (!setColor((Color) paint, true)) { out.append("Q\n"); return; }
                path(s, transform);
                out.append(windingEvenOdd(s) ? "f*\n" : "f\n");
                out.append("Q\n");
            } else rasterFill(s);
        }

        /** Degradados y demás: se pinta la figura en una imagen y se pone en su sitio. */
        private void rasterFill(Shape s) {
            Shape dev = transform.createTransformedShape(s);
            Rectangle2D b = dev.getBounds2D().createIntersection(new Rectangle2D.Double(0, 0, w, h));
            if (b.isEmpty()) return;
            double k = 2;
            int iw = (int) Math.ceil(b.getWidth() * k), ih = (int) Math.ceil(b.getHeight() * k);
            if (iw <= 0 || ih <= 0 || (long) iw * ih > 16_000_000L) return;
            BufferedImage img = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.scale(k, k);
            g.translate(-b.getX(), -b.getY());
            g.transform(transform);
            g.setPaint(paint);
            g.setComposite(composite);
            g.fill(s);
            g.dispose();
            AffineTransform t = AffineTransform.getTranslateInstance(b.getX(), b.getY());
            t.scale(1 / k, 1 / k);
            AffineTransform keep = transform;
            transform = new AffineTransform();
            drawImage(img, t, null);
            transform = keep;
        }

        @Override
        public void draw(Shape s) {
            if (!(stroke instanceof BasicStroke) || !(paint instanceof Color)) {
                Paint p = paint;
                fill(stroke.createStrokedShape(s));
                paint = p;
                return;
            }
            BasicStroke bs = (BasicStroke) stroke;
            applyClip();
            out.append("q\n");
            if (!setColor((Color) paint, false)) { out.append("Q\n"); return; }
            double[] m = new double[6];
            transform.getMatrix(m);
            out.append(n(m[0])).append(' ').append(n(m[1])).append(' ').append(n(m[2])).append(' ').append(n(m[3])).append(' ').append(n(m[4])).append(' ').append(n(m[5])).append(" cm\n");
            out.append(n(bs.getLineWidth())).append(" w ");
            out.append(bs.getEndCap() == BasicStroke.CAP_ROUND ? 1 : bs.getEndCap() == BasicStroke.CAP_SQUARE ? 2 : 0).append(" J ");
            out.append(bs.getLineJoin() == BasicStroke.JOIN_ROUND ? 1 : bs.getLineJoin() == BasicStroke.JOIN_BEVEL ? 2 : 0).append(" j ");
            out.append(n(bs.getMiterLimit())).append(" M\n");
            float[] dash = bs.getDashArray();
            if (dash != null) {
                out.append('[');
                for (float d : dash) out.append(n(d)).append(' ');
                out.append("] ").append(n(bs.getDashPhase())).append(" d\n");
            }
            path(s, null);
            out.append("S\nQ\n");
        }

        @Override
        public boolean drawImage(Image img, AffineTransform xform, ImageObserver obs) {
            if (!(img instanceof BufferedImage)) return false;
            BufferedImage bi = (BufferedImage) img;
            float a = alpha();
            if (a <= 0.001f) return true;
            String name = images.computeIfAbsent(bi, k -> "Im" + images.size());
            applyClip();
            AffineTransform t = new AffineTransform(transform);
            if (xform != null) t.concatenate(xform);
            t.concatenate(new AffineTransform(bi.getWidth(), 0, 0, -bi.getHeight(), 0, bi.getHeight()));
            double[] m = new double[6];
            t.getMatrix(m);
            out.append("q\n");
            if (a < 0.999f) {
                String key = n(Math.round(a * 1000) / 1000.0);
                out.append('/').append(alphas.computeIfAbsent(key, k -> "GA" + alphas.size())).append(" gs\n");
            }
            out.append(n(m[0])).append(' ').append(n(m[1])).append(' ').append(n(m[2])).append(' ').append(n(m[3])).append(' ').append(n(m[4])).append(' ').append(n(m[5])).append(" cm\n");
            out.append('/').append(name).append(" Do\nQ\n");
            return true;
        }

        @Override public void drawImage(BufferedImage img, BufferedImageOp op, int x, int y) { drawImage(op == null ? img : op.filter(img, null), AffineTransform.getTranslateInstance(x, y), null); }
        @Override public void drawRenderedImage(RenderedImage img, AffineTransform t) { if (img instanceof BufferedImage) drawImage((BufferedImage) img, t, null); }
        @Override public void drawRenderableImage(RenderableImage img, AffineTransform t) {}
        @Override public boolean drawImage(Image img, int x, int y, ImageObserver o) { return drawImage(img, AffineTransform.getTranslateInstance(x, y), o); }
        @Override public boolean drawImage(Image img, int x, int y, int w, int h, ImageObserver o) {
            AffineTransform t = AffineTransform.getTranslateInstance(x, y);
            t.scale(w / (double) img.getWidth(o), h / (double) img.getHeight(o));
            return drawImage(img, t, o);
        }
        @Override public boolean drawImage(Image img, int x, int y, Color bg, ImageObserver o) { return drawImage(img, x, y, o); }
        @Override public boolean drawImage(Image img, int x, int y, int w, int h, Color bg, ImageObserver o) { return drawImage(img, x, y, w, h, o); }
        @Override public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, ImageObserver o) {
            if (!(img instanceof BufferedImage)) return false;
            BufferedImage sub = ((BufferedImage) img).getSubimage(Math.min(sx1, sx2), Math.min(sy1, sy2), Math.abs(sx2 - sx1), Math.abs(sy2 - sy1));
            return drawImage(sub, Math.min(dx1, dx2), Math.min(dy1, dy2), Math.abs(dx2 - dx1), Math.abs(dy2 - dy1), o);
        }
        @Override public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, Color bg, ImageObserver o) { return drawImage(img, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, o); }

        @Override public void drawString(String s, int x, int y) { drawString(s, (float) x, (float) y); }
        @Override public void drawString(String s, float x, float y) {
            if (s.isEmpty()) return;
            GlyphVector gv = font.createGlyphVector(getFontRenderContext(), s);
            fill(gv.getOutline(x, y));
        }
        @Override public void drawString(AttributedCharacterIterator it, int x, int y) { drawString(it, (float) x, (float) y); }
        @Override public void drawString(AttributedCharacterIterator it, float x, float y) {
            StringBuilder b = new StringBuilder();
            for (char c = it.first(); c != AttributedCharacterIterator.DONE; c = it.next()) b.append(c);
            drawString(b.toString(), x, y);
        }
        @Override public void drawGlyphVector(GlyphVector g, float x, float y) { fill(g.getOutline(x, y)); }
        @Override public boolean hit(Rectangle r, Shape s, boolean onStroke) { return s.intersects(r); }
        @Override public GraphicsConfiguration getDeviceConfiguration() {
            try { return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration(); } catch (Throwable t) { return null; }
        }

        @Override public void setComposite(Composite c) { composite = c; }
        @Override public void setPaint(Paint p) { if (p != null) paint = p; }
        @Override public void setStroke(Stroke s) { stroke = s; }
        @Override public void setRenderingHint(RenderingHints.Key k, Object v) { hints.put(k, v); }
        @Override public Object getRenderingHint(RenderingHints.Key k) { return hints.get(k); }
        @Override public void setRenderingHints(Map<?, ?> m) { hints.clear(); hints.putAll(m); }
        @Override public void addRenderingHints(Map<?, ?> m) { hints.putAll(m); }
        @Override public RenderingHints getRenderingHints() { return (RenderingHints) hints.clone(); }
        @Override public void translate(int x, int y) { transform.translate(x, y); }
        @Override public void translate(double x, double y) { transform.translate(x, y); }
        @Override public void rotate(double t) { transform.rotate(t); }
        @Override public void rotate(double t, double x, double y) { transform.rotate(t, x, y); }
        @Override public void scale(double sx, double sy) { transform.scale(sx, sy); }
        @Override public void shear(double x, double y) { transform.shear(x, y); }
        @Override public void transform(AffineTransform t) { transform.concatenate(t); }
        @Override public void setTransform(AffineTransform t) { transform = new AffineTransform(t); }
        @Override public AffineTransform getTransform() { return new AffineTransform(transform); }
        @Override public Paint getPaint() { return paint; }
        @Override public Composite getComposite() { return composite; }
        @Override public void setBackground(Color c) { background = c; }
        @Override public Color getBackground() { return background; }
        @Override public Stroke getStroke() { return stroke; }

        @Override
        public void clip(Shape s) {
            Shape dev = transform.createTransformedShape(s);
            if (clip == null) clip = dev;
            else {
                Area a = new Area(clip);
                a.intersect(new Area(dev));
                clip = a;
            }
        }

        @Override public FontRenderContext getFontRenderContext() { return android.graphics.TextKit.FRC; }
        @Override public Graphics create() { Recorder r = new Recorder(this); r.transform = new AffineTransform(transform); r.clip = clip; r.paint = paint; r.stroke = stroke; r.font = font; return r; }
        @Override public Color getColor() { return paint instanceof Color ? (Color) paint : Color.BLACK; }
        @Override public void setColor(Color c) { if (c != null) paint = c; }
        @Override public void setPaintMode() { composite = AlphaComposite.SrcOver; }
        @Override public void setXORMode(Color c) {}
        @Override public Font getFont() { return font; }
        @Override public void setFont(Font f) { if (f != null) font = f; }
        @Override public FontMetrics getFontMetrics(Font f) {
            BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = tmp.createGraphics();
            FontMetrics m = g.getFontMetrics(f);
            g.dispose();
            return m;
        }

        @Override
        public Rectangle getClipBounds() {
            Shape c = getClip();
            return c == null ? null : c.getBounds();
        }

        @Override public void clipRect(int x, int y, int w, int h) { clip(new Rectangle(x, y, w, h)); }
        @Override public void setClip(int x, int y, int w, int h) { setClip(new Rectangle(x, y, w, h)); }

        @Override
        public Shape getClip() {
            if (clip == null) return null;
            try { return transform.createInverse().createTransformedShape(clip); } catch (NoninvertibleTransformException e) { return null; }
        }

        @Override public void setClip(Shape s) { clip = s == null ? null : transform.createTransformedShape(s); }
        @Override public void copyArea(int x, int y, int w, int h, int dx, int dy) {}
        @Override public void drawLine(int x1, int y1, int x2, int y2) { draw(new java.awt.geom.Line2D.Float(x1, y1, x2, y2)); }
        @Override public void fillRect(int x, int y, int w, int h) { fill(new Rectangle(x, y, w, h)); }
        @Override public void clearRect(int x, int y, int w, int h) { Paint p = paint; paint = background; fill(new Rectangle(x, y, w, h)); paint = p; }
        @Override public void drawRoundRect(int x, int y, int w, int h, int aw, int ah) { draw(new java.awt.geom.RoundRectangle2D.Float(x, y, w, h, aw, ah)); }
        @Override public void fillRoundRect(int x, int y, int w, int h, int aw, int ah) { fill(new java.awt.geom.RoundRectangle2D.Float(x, y, w, h, aw, ah)); }
        @Override public void drawOval(int x, int y, int w, int h) { draw(new java.awt.geom.Ellipse2D.Float(x, y, w, h)); }
        @Override public void fillOval(int x, int y, int w, int h) { fill(new java.awt.geom.Ellipse2D.Float(x, y, w, h)); }
        @Override public void drawArc(int x, int y, int w, int h, int s, int a) { draw(new java.awt.geom.Arc2D.Float(x, y, w, h, s, a, java.awt.geom.Arc2D.OPEN)); }
        @Override public void fillArc(int x, int y, int w, int h, int s, int a) { fill(new java.awt.geom.Arc2D.Float(x, y, w, h, s, a, java.awt.geom.Arc2D.PIE)); }
        @Override public void drawPolyline(int[] xs, int[] ys, int n) { java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float(); for (int i = 0; i < n; i++) { if (i == 0) p.moveTo(xs[i], ys[i]); else p.lineTo(xs[i], ys[i]); } draw(p); }
        @Override public void drawPolygon(int[] xs, int[] ys, int n) { draw(new java.awt.Polygon(xs, ys, n)); }
        @Override public void fillPolygon(int[] xs, int[] ys, int n) { fill(new java.awt.Polygon(xs, ys, n)); }
        @Override public void dispose() {}
    }
}
