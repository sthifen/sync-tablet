package android.view.animation;

/** Curva de Bézier cúbica de (0,0) a (1,1), como la de CSS. */
public class PathInterpolator extends BaseInterpolator {
    private final float x1, y1, x2, y2;
    private final boolean quad;

    public PathInterpolator(float cx, float cy) { x1 = cx; y1 = cy; x2 = cx; y2 = cy; quad = true; }
    public PathInterpolator(float cx1, float cy1, float cx2, float cy2) { x1 = cx1; y1 = cy1; x2 = cx2; y2 = cy2; quad = false; }
    public PathInterpolator(android.graphics.Path p) { x1 = 0.4f; y1 = 0f; x2 = 0.2f; y2 = 1f; quad = false; }

    private float bx(float t) {
        if (quad) { float u = 1 - t; return 2 * u * t * x1 + t * t; }
        float u = 1 - t;
        return 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t;
    }

    private float by(float t) {
        if (quad) { float u = 1 - t; return 2 * u * t * y1 + t * t; }
        float u = 1 - t;
        return 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t;
    }

    @Override
    public float getInterpolation(float x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        float lo = 0, hi = 1, t = x;
        for (int i = 0; i < 30; i++) {
            t = (lo + hi) / 2;
            float v = bx(t);
            if (Math.abs(v - x) < 1e-5f) break;
            if (v < x) lo = t; else hi = t;
        }
        return by(t);
    }
}
