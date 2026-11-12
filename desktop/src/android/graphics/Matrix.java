package android.graphics;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;

/** Matriz afín (Android admite perspectiva; la app no la usa). */
public class Matrix {
    public static final int MSCALE_X = 0, MSKEW_X = 1, MTRANS_X = 2, MSKEW_Y = 3, MSCALE_Y = 4, MTRANS_Y = 5, MPERSP_0 = 6, MPERSP_1 = 7, MPERSP_2 = 8;
    public static final Matrix IDENTITY_MATRIX = new Matrix();

    public enum ScaleToFit { FILL, START, CENTER, END }

    final AffineTransform t = new AffineTransform();

    public Matrix() {}
    public Matrix(Matrix src) { if (src != null) t.setTransform(src.t); }

    public AffineTransform awt() { return new AffineTransform(t); }
    public void setAwt(AffineTransform a) { t.setTransform(a); }

    public boolean isIdentity() { return t.isIdentity(); }
    public boolean isAffine() { return true; }
    public boolean rectStaysRect() { return (t.getType() & (AffineTransform.TYPE_GENERAL_ROTATION | AffineTransform.TYPE_GENERAL_TRANSFORM)) == 0; }
    public void set(Matrix src) { if (src == null) t.setToIdentity(); else t.setTransform(src.t); }
    public void reset() { t.setToIdentity(); }

    public void setTranslate(float dx, float dy) { t.setToTranslation(dx, dy); }
    public void setScale(float sx, float sy) { t.setToScale(sx, sy); }
    public void setScale(float sx, float sy, float px, float py) { t.setToTranslation(px, py); t.scale(sx, sy); t.translate(-px, -py); }
    public void setRotate(float deg) { t.setToRotation(Math.toRadians(deg)); }
    public void setRotate(float deg, float px, float py) { t.setToRotation(Math.toRadians(deg), px, py); }
    public void setSkew(float kx, float ky) { t.setToShear(kx, ky); }
    public void setSkew(float kx, float ky, float px, float py) { t.setToTranslation(px, py); t.shear(kx, ky); t.translate(-px, -py); }
    public boolean setConcat(Matrix a, Matrix b) { AffineTransform r = new AffineTransform(a.t); r.concatenate(b.t); t.setTransform(r); return true; }

    private static AffineTransform scaleAt(float sx, float sy, float px, float py) { AffineTransform a = AffineTransform.getTranslateInstance(px, py); a.scale(sx, sy); a.translate(-px, -py); return a; }

    public boolean preTranslate(float dx, float dy) { t.translate(dx, dy); return true; }
    public boolean preScale(float sx, float sy) { t.scale(sx, sy); return true; }
    public boolean preScale(float sx, float sy, float px, float py) { t.concatenate(scaleAt(sx, sy, px, py)); return true; }
    public boolean preRotate(float deg) { t.rotate(Math.toRadians(deg)); return true; }
    public boolean preRotate(float deg, float px, float py) { t.rotate(Math.toRadians(deg), px, py); return true; }
    public boolean preSkew(float kx, float ky) { t.shear(kx, ky); return true; }
    public boolean preConcat(Matrix o) { t.concatenate(o.t); return true; }

    public boolean postTranslate(float dx, float dy) { t.preConcatenate(AffineTransform.getTranslateInstance(dx, dy)); return true; }
    public boolean postScale(float sx, float sy) { t.preConcatenate(AffineTransform.getScaleInstance(sx, sy)); return true; }
    public boolean postScale(float sx, float sy, float px, float py) { t.preConcatenate(scaleAt(sx, sy, px, py)); return true; }
    public boolean postRotate(float deg) { t.preConcatenate(AffineTransform.getRotateInstance(Math.toRadians(deg))); return true; }
    public boolean postRotate(float deg, float px, float py) { t.preConcatenate(AffineTransform.getRotateInstance(Math.toRadians(deg), px, py)); return true; }
    public boolean postSkew(float kx, float ky) { t.preConcatenate(AffineTransform.getShearInstance(kx, ky)); return true; }
    public boolean postConcat(Matrix o) { t.preConcatenate(o.t); return true; }

    public boolean invert(Matrix inverse) {
        try { inverse.t.setTransform(t.createInverse()); return true; } catch (NoninvertibleTransformException e) { return false; }
    }

    public void mapPoints(float[] pts) { mapPoints(pts, 0, pts, 0, pts.length / 2); }
    public void mapPoints(float[] dst, float[] src) { mapPoints(dst, 0, src, 0, src.length / 2); }
    public void mapPoints(float[] dst, int dstIndex, float[] src, int srcIndex, int pointCount) { t.transform(src, srcIndex, dst, dstIndex, pointCount); }
    public void mapVectors(float[] pts) {
        for (int i = 0; i + 1 < pts.length; i += 2) {
            Point2D p = t.deltaTransform(new Point2D.Float(pts[i], pts[i + 1]), null);
            pts[i] = (float) p.getX(); pts[i + 1] = (float) p.getY();
        }
    }
    public float mapRadius(float r) {
        Point2D a = t.deltaTransform(new Point2D.Float(r, 0), null), b = t.deltaTransform(new Point2D.Float(0, r), null);
        return (float) Math.sqrt(a.distance(0, 0) * b.distance(0, 0));
    }
    public boolean mapRect(RectF rect) { return mapRect(rect, rect); }
    public boolean mapRect(RectF dst, RectF src) {
        float[] p = {src.left, src.top, src.right, src.top, src.right, src.bottom, src.left, src.bottom};
        mapPoints(p);
        float l = Math.min(Math.min(p[0], p[2]), Math.min(p[4], p[6])), r = Math.max(Math.max(p[0], p[2]), Math.max(p[4], p[6]));
        float tp = Math.min(Math.min(p[1], p[3]), Math.min(p[5], p[7])), b = Math.max(Math.max(p[1], p[3]), Math.max(p[5], p[7]));
        dst.set(l, tp, r, b);
        return rectStaysRect();
    }

    public boolean setRectToRect(RectF src, RectF dst, ScaleToFit stf) {
        if (src.isEmpty()) { t.setToIdentity(); return false; }
        float sx = dst.width() / src.width(), sy = dst.height() / src.height();
        float tx, ty;
        if (stf == ScaleToFit.FILL) {
            tx = dst.left - src.left * sx; ty = dst.top - src.top * sy;
        } else {
            float s = Math.min(sx, sy);
            sx = sy = s;
            tx = dst.left - src.left * s; ty = dst.top - src.top * s;
            float dx = dst.width() - src.width() * s, dy = dst.height() - src.height() * s;
            if (stf == ScaleToFit.CENTER) { tx += dx / 2; ty += dy / 2; } else if (stf == ScaleToFit.END) { tx += dx; ty += dy; }
        }
        t.setTransform(sx, 0, 0, sy, tx, ty);
        return true;
    }

    public void getValues(float[] v) {
        v[0] = (float) t.getScaleX(); v[1] = (float) t.getShearX(); v[2] = (float) t.getTranslateX();
        v[3] = (float) t.getShearY(); v[4] = (float) t.getScaleY(); v[5] = (float) t.getTranslateY();
        v[6] = 0; v[7] = 0; v[8] = 1;
    }

    public void setValues(float[] v) { t.setTransform(v[0], v[3], v[1], v[4], v[2], v[5]); }

    @Override public boolean equals(Object o) { return o instanceof Matrix && ((Matrix) o).t.equals(t); }
    @Override public int hashCode() { return t.hashCode(); }
    @Override public String toString() { return "Matrix" + t; }
}
