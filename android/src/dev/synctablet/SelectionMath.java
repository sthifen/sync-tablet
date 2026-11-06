package dev.synctablet;

/**
 * Las cuentas de mover, estirar y girar lo seleccionado. Una caja es
 * {izquierda, arriba, derecha, abajo}.
 *
 * Los lados estiran en un solo sentido; las esquinas guardan la proporción, para
 * no deformar sin querer. Estirando, casi cuadrado pasa a cuadrado; girando, casi
 * un múltiplo de 15° pasa a serlo.
 *
 * Java puro (sin Android) para poder probarlo en el PC.
 */
final class SelectionMath {
    static final int NONE = 0, MOVE = 1, TOP_LEFT = 2, TOP = 3, TOP_RIGHT = 4, RIGHT = 5, BOTTOM_RIGHT = 6, BOTTOM = 7,
            BOTTOM_LEFT = 8, LEFT = 9, ROTATE = 10, LINE_START = 11, LINE_END = 12;

    /** Lo más pequeña que puede quedar la caja. */
    static final float MIN_SIZE = 16;
    private static final float SQUARE_SNAP = 0.03f;
    private static final double ANGLE_SNAP = Math.toRadians(3), ANGLE_STEP = Math.toRadians(15);

    /** La caja tras arrastrar `handle` (dx, dy). */
    static float[] resize(int handle, float[] box, float dx, float dy) {
        float l = box[0], t = box[1], r = box[2], b = box[3], w = r - l, h = b - t;
        switch (handle) {
            case MOVE: return new float[] {l + dx, t + dy, r + dx, b + dy};
            case LEFT: return squared(new float[] {Math.min(l + dx, r - MIN_SIZE), t, r, b}, LEFT);
            case RIGHT: return squared(new float[] {l, t, Math.max(r + dx, l + MIN_SIZE), b}, RIGHT);
            case TOP: return squared(new float[] {l, Math.min(t + dy, b - MIN_SIZE), r, b}, TOP);
            case BOTTOM: return squared(new float[] {l, t, r, Math.max(b + dy, t + MIN_SIZE)}, BOTTOM);
            default: break;
        }
        boolean left = handle == TOP_LEFT || handle == BOTTOM_LEFT, top = handle == TOP_LEFT || handle == TOP_RIGHT;
        float kx = (w + (left ? -dx : dx)) / w, ky = (h + (top ? -dy : dy)) / h;
        float k = Math.max(Math.max(kx, ky), MIN_SIZE / Math.min(w, h));
        float nw = w * k, nh = h * k;
        return new float[] {left ? r - nw : l, top ? b - nh : t, left ? r : l + nw, top ? b : t + nh};
    }

    /** Casi cuadrada: exacta, moviendo sólo el lado arrastrado. */
    private static float[] squared(float[] box, int handle) {
        float w = box[2] - box[0], h = box[3] - box[1];
        if (Math.abs(w - h) >= SQUARE_SNAP * Math.max(w, h)) return box;
        if (handle == LEFT) box[0] = box[2] - h;
        else if (handle == RIGHT) box[2] = box[0] + h;
        else if (handle == TOP) box[1] = box[3] - w;
        else box[3] = box[1] + w;
        return box;
    }

    /** El ángulo (radianes) con el ajuste a múltiplos de 15°. */
    static float snapAngle(double angle) {
        double near = Math.round(angle / ANGLE_STEP) * ANGLE_STEP;
        return (float) (Math.abs(angle - near) < ANGLE_SNAP ? near : angle);
    }

    /**
     * Transformación afín {a, b, c, d, e, f}, con x' = a·x + c·y + e e
     * y' = b·x + d·y + f, que lleva la caja `from` a `to` y la gira `angle`
     * alrededor del centro de `to`.
     */
    static float[] affine(float[] from, float[] to, float angle) {
        double sx = (to[2] - to[0]) / (from[2] - from[0]), sy = (to[3] - to[1]) / (from[3] - from[1]);
        double tx = to[0] - from[0] * sx, ty = to[1] - from[1] * sy;
        double cx = (to[0] + to[2]) / 2.0, cy = (to[1] + to[3]) / 2.0, cos = Math.cos(angle), sin = Math.sin(angle);
        return new float[] {(float) (cos * sx), (float) (sin * sx), (float) (-sin * sy), (float) (cos * sy),
                (float) (cos * (tx - cx) - sin * (ty - cy) + cx), (float) (sin * (tx - cx) + cos * (ty - cy) + cy)};
    }

    /** ¿Estira por igual en los dos sentidos? */
    static boolean uniform(float[] from, float[] to) {
        float sx = (to[2] - to[0]) / (from[2] - from[0]), sy = (to[3] - to[1]) / (from[3] - from[1]);
        return Math.abs(sx - sy) < 0.001f;
    }

    /**
     * Qué hay en (x, y). `reach`: alcance del dedo sobre un tirador; `band`:
     * sobre un borde. `vertical`: se puede estirar de arriba abajo; `rotate`: hay
     * tirador de giro, a `rotateGap` sobre la caja.
     */
    static int hit(float[] box, float x, float y, float reach, float band, boolean vertical, boolean rotate, float rotateGap) {
        float l = box[0], t = box[1], r = box[2], b = box[3], mx = (l + r) / 2;
        if (rotate && dist(x, y, mx, t - rotateGap) < reach) return ROTATE;
        if (dist(x, y, r, b) < reach) return BOTTOM_RIGHT;
        if (dist(x, y, l, b) < reach) return BOTTOM_LEFT;
        if (dist(x, y, r, t) < reach) return TOP_RIGHT;
        if (dist(x, y, l, t) < reach) return TOP_LEFT;
        boolean inRows = y >= t && y <= b, inColumns = x >= l && x <= r;
        if (inRows && Math.abs(x - l) < band) return LEFT;
        if (inRows && Math.abs(x - r) < band) return RIGHT;
        if (vertical && inColumns && Math.abs(y - t) < band) return TOP;
        if (vertical && inColumns && Math.abs(y - b) < band) return BOTTOM;
        return inRows && inColumns ? MOVE : NONE;
    }

    private static float dist(float ax, float ay, float bx, float by) {
        return (float) Math.hypot(ax - bx, ay - by);
    }

    private SelectionMath() {}
}
