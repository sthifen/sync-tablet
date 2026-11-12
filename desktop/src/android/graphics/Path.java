package android.graphics;

import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/** Trazado sobre Path2D. */
public class Path {
    public enum Direction { CW, CCW }
    public enum FillType { WINDING, EVEN_ODD, INVERSE_WINDING, INVERSE_EVEN_ODD }
    public enum Op { DIFFERENCE, INTERSECT, UNION, XOR, REVERSE_DIFFERENCE }

    Path2D.Float p = new Path2D.Float();
    private FillType fill = FillType.WINDING;
    private float lastX, lastY, startX, startY;
    private boolean hasMove;

    public Path() {}
    public Path(Path src) { set(src); }

    /** Para el lienzo. */
    public Path2D.Float shape() { return p; }

    public void set(Path src) {
        p = (Path2D.Float) src.p.clone();
        fill = src.fill;
        lastX = src.lastX; lastY = src.lastY; startX = src.startX; startY = src.startY; hasMove = src.hasMove;
    }

    public void reset() { p.reset(); fill = FillType.WINDING; hasMove = false; lastX = lastY = 0; }
    public void rewind() { p.reset(); hasMove = false; lastX = lastY = 0; }
    public boolean isEmpty() { return p.getCurrentPoint() == null; }
    public boolean isRect(RectF r) { return false; }
    public boolean isConvex() { return false; }
    public FillType getFillType() { return fill; }
    public void setFillType(FillType ft) { fill = ft; p.setWindingRule(ft == FillType.EVEN_ODD || ft == FillType.INVERSE_EVEN_ODD ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO); }
    public boolean isInverseFillType() { return fill == FillType.INVERSE_WINDING || fill == FillType.INVERSE_EVEN_ODD; }
    public void toggleInverseFillType() {
        switch (fill) {
            case WINDING: fill = FillType.INVERSE_WINDING; break;
            case EVEN_ODD: fill = FillType.INVERSE_EVEN_ODD; break;
            case INVERSE_WINDING: fill = FillType.WINDING; break;
            default: fill = FillType.EVEN_ODD;
        }
    }
    public void incReserve(int extra) {}

    private void ensureMove() { if (!hasMove || p.getCurrentPoint() == null) { p.moveTo(lastX, lastY); startX = lastX; startY = lastY; hasMove = true; } }

    public void moveTo(float x, float y) { p.moveTo(x, y); lastX = startX = x; lastY = startY = y; hasMove = true; }
    public void rMoveTo(float dx, float dy) { moveTo(lastX + dx, lastY + dy); }
    public void lineTo(float x, float y) { ensureMove(); p.lineTo(x, y); lastX = x; lastY = y; }
    public void rLineTo(float dx, float dy) { lineTo(lastX + dx, lastY + dy); }
    public void quadTo(float x1, float y1, float x2, float y2) { ensureMove(); p.quadTo(x1, y1, x2, y2); lastX = x2; lastY = y2; }
    public void rQuadTo(float dx1, float dy1, float dx2, float dy2) { quadTo(lastX + dx1, lastY + dy1, lastX + dx2, lastY + dy2); }
    public void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3) { ensureMove(); p.curveTo(x1, y1, x2, y2, x3, y3); lastX = x3; lastY = y3; }
    public void rCubicTo(float x1, float y1, float x2, float y2, float x3, float y3) { cubicTo(lastX + x1, lastY + y1, lastX + x2, lastY + y2, lastX + x3, lastY + y3); }
    public void close() { if (p.getCurrentPoint() != null) { p.closePath(); lastX = startX; lastY = startY; } }
    public void setLastPoint(float dx, float dy) { lastX = dx; lastY = dy; }

    public void arcTo(RectF oval, float startAngle, float sweepAngle, boolean forceMoveTo) {
        arcTo(oval.left, oval.top, oval.right, oval.bottom, startAngle, sweepAngle, forceMoveTo);
    }
    public void arcTo(RectF oval, float startAngle, float sweepAngle) { arcTo(oval, startAngle, sweepAngle, false); }
    public void arcTo(float l, float t, float r, float b, float startAngle, float sweepAngle, boolean forceMoveTo) {
        Arc2D.Float a = new Arc2D.Float(l, t, r - l, b - t, -startAngle, -sweepAngle, Arc2D.OPEN);
        boolean connect = !forceMoveTo && p.getCurrentPoint() != null;
        p.append(a, connect);
        Point2D end = a.getEndPoint();
        if (!connect) { Point2D s = a.getStartPoint(); startX = (float) s.getX(); startY = (float) s.getY(); }
        lastX = (float) end.getX(); lastY = (float) end.getY();
        hasMove = true;
    }

    public void addArc(RectF oval, float startAngle, float sweepAngle) { addArc(oval.left, oval.top, oval.right, oval.bottom, startAngle, sweepAngle); }
    public void addArc(float l, float t, float r, float b, float startAngle, float sweepAngle) { arcTo(l, t, r, b, startAngle, sweepAngle, true); }

    private void addClosed(java.awt.Shape s) {
        p.append(s, false);
        Point2D cp = p.getCurrentPoint();
        if (cp != null) { lastX = (float) cp.getX(); lastY = (float) cp.getY(); }
        hasMove = true;
    }

    public void addRect(RectF r, Direction d) { addRect(r.left, r.top, r.right, r.bottom, d); }
    public void addRect(float l, float t, float r, float b, Direction d) {
        if (d == Direction.CCW) { moveTo(l, t); lineTo(l, b); lineTo(r, b); lineTo(r, t); close(); }
        else { moveTo(l, t); lineTo(r, t); lineTo(r, b); lineTo(l, b); close(); }
        lastX = l; lastY = t;
    }

    public void addOval(RectF r, Direction d) { addOval(r.left, r.top, r.right, r.bottom, d); }
    public void addOval(float l, float t, float r, float b, Direction d) {
        Arc2D.Float a = new Arc2D.Float(l, t, r - l, b - t, 0, d == Direction.CW ? -360 : 360, Arc2D.CHORD);
        addClosed(a);
    }

    public void addCircle(float x, float y, float radius, Direction d) { addOval(x - radius, y - radius, x + radius, y + radius, d); }

    public void addRoundRect(RectF rect, float rx, float ry, Direction d) { addRoundRect(rect.left, rect.top, rect.right, rect.bottom, rx, ry, d); }
    public void addRoundRect(float l, float t, float r, float b, float rx, float ry, Direction d) {
        float w = r - l, h = b - t;
        rx = Math.min(Math.max(0, rx), w / 2); ry = Math.min(Math.max(0, ry), h / 2);
        addClosed(new RoundRectangle2D.Float(l, t, w, h, rx * 2, ry * 2));
    }
    public void addRoundRect(RectF rect, float[] radii, Direction d) { addRoundRect(rect.left, rect.top, rect.right, rect.bottom, radii, d); }
    public void addRoundRect(float l, float t, float r, float b, float[] radii, Direction d) {
        float w = r - l, h = b - t;
        float[] k = new float[8];
        for (int i = 0; i < 8; i++) k[i] = Math.max(0, radii[i]);
        // Encoge si los radios no caben (como Skia).
        float scale = 1;
        scale = Math.min(scale, fit(w, k[0] + k[2]));
        scale = Math.min(scale, fit(w, k[4] + k[6]));
        scale = Math.min(scale, fit(h, k[1] + k[7]));
        scale = Math.min(scale, fit(h, k[3] + k[5]));
        for (int i = 0; i < 8; i++) k[i] *= scale;
        final float c = 0.5522848f;
        moveTo(l + k[0], t);
        lineTo(r - k[2], t);
        if (k[2] > 0 || k[3] > 0) cubicTo(r - k[2] + k[2] * c, t, r, t + k[3] - k[3] * c, r, t + k[3]);
        lineTo(r, b - k[5]);
        if (k[4] > 0 || k[5] > 0) cubicTo(r, b - k[5] + k[5] * c, r - k[4] + k[4] * c, b, r - k[4], b);
        lineTo(l + k[6], b);
        if (k[6] > 0 || k[7] > 0) cubicTo(l + k[6] - k[6] * c, b, l, b - k[7] + k[7] * c, l, b - k[7]);
        lineTo(l, t + k[1]);
        if (k[0] > 0 || k[1] > 0) cubicTo(l, t + k[1] - k[1] * c, l + k[0] - k[0] * c, t, l + k[0], t);
        close();
    }
    private static float fit(float side, float sum) { return sum > side && sum > 0 ? side / sum : 1; }

    public void addPath(Path src) { p.append(src.p, false); hasMove = true; updateLast(); }
    public void addPath(Path src, float dx, float dy) { p.append(src.p.getPathIterator(AffineTransform.getTranslateInstance(dx, dy)), false); hasMove = true; updateLast(); }
    public void addPath(Path src, Matrix m) { p.append(src.p.getPathIterator(m.t), false); hasMove = true; updateLast(); }
    private void updateLast() { Point2D cp = p.getCurrentPoint(); if (cp != null) { lastX = (float) cp.getX(); lastY = (float) cp.getY(); } }

    public void offset(float dx, float dy) { p.transform(AffineTransform.getTranslateInstance(dx, dy)); lastX += dx; lastY += dy; startX += dx; startY += dy; }
    public void offset(float dx, float dy, Path dst) { if (dst == null) { offset(dx, dy); return; } dst.set(this); dst.offset(dx, dy); }
    public void transform(Matrix m) { p.transform(m.t); updateLast(); }
    public void transform(Matrix m, Path dst) { if (dst == null) { transform(m); return; } dst.set(this); dst.transform(m); }

    public void computeBounds(RectF bounds, boolean exact) {
        Rectangle2D b = p.getBounds2D();
        if (p.getCurrentPoint() == null) { bounds.set(0, 0, 0, 0); return; }
        bounds.set((float) b.getMinX(), (float) b.getMinY(), (float) b.getMaxX(), (float) b.getMaxY());
    }

    public boolean op(Path path, Op op) { return op(this, path, op); }
    public boolean op(Path a, Path b, Op op) {
        Area x = new Area(a.p), y = new Area(b.p);
        switch (op) {
            case DIFFERENCE: x.subtract(y); break;
            case INTERSECT: x.intersect(y); break;
            case UNION: x.add(y); break;
            case XOR: x.exclusiveOr(y); break;
            case REVERSE_DIFFERENCE: y.subtract(x); x = y; break;
        }
        Path2D.Float r = new Path2D.Float(x);
        p = r;
        hasMove = true;
        updateLast();
        return true;
    }

    /** Puntos del trazado (x, y por cada uno) con un paso de error `tol`. */
    public float[] approximate(float tol) {
        java.util.ArrayList<float[]> pts = new java.util.ArrayList<>();
        java.awt.geom.PathIterator it = p.getPathIterator(null, Math.max(0.01, tol));
        float[] c = new float[6];
        float len = 0, px = 0, py = 0;
        for (; !it.isDone(); it.next()) {
            int s = it.currentSegment(c);
            if (s == java.awt.geom.PathIterator.SEG_CLOSE) continue;
            if (!pts.isEmpty()) len += (float) Math.hypot(c[0] - px, c[1] - py);
            pts.add(new float[] {len, c[0], c[1]});
            px = c[0]; py = c[1];
        }
        float[] out = new float[pts.size() * 3];
        for (int i = 0; i < pts.size(); i++) { float[] q = pts.get(i); out[i * 3] = len > 0 ? q[0] / len : 0; out[i * 3 + 1] = q[1]; out[i * 3 + 2] = q[2]; }
        return out;
    }
}
