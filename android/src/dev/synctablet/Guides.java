package dev.synctablet;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * Regla, escuadras, transportador y compás sobre la hoja (en coordenadas de
 * pantalla, como una regla de verdad encima del papel). Con un dedo se mueven,
 * con dos se giran. El lápiz que empieza cerca de un borde lo sigue recto; con
 * el compás, el trazo sigue un círculo alrededor de la aguja.
 */
final class Guides {
    static final int NONE = 0, RULER = 1, SQUARE45 = 2, SQUARE60 = 3, PROTRACTOR = 4, COMPASS = 5;

    int kind = NONE;
    private final float density;
    /** Centro (o aguja del compás) y giro en grados. */
    float cx = Float.NaN, cy, angle = 0;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Gesto con los dedos sobre la guía.
    private int touchId = -1;
    private float lastX, lastY, lastAngle, lastDist;
    private boolean twoFingers;
    // Trazo pegado: un borde (x0,y0)→(x1,y1) o un círculo de radio r.
    private int edge = -1;
    private float radius;
    private float[] poly;

    Guides(float density) {
        this.density = density;
        line.setStyle(Paint.Style.STROKE);
        label.setTextAlign(Paint.Align.CENTER);
    }

    boolean active() {
        return kind != NONE;
    }

    void set(int k, int w, int h) {
        kind = k;
        if (Float.isNaN(cx) || k == NONE) {
            cx = w / 2f;
            cy = h / 2f;
            angle = 0;
        }
    }

    private float cm() {
        // 160 dp son una pulgada: 2,54 cm.
        return 160 * density / 2.54f;
    }

    /** El contorno en pantalla, como pares x,y. */
    private float[] outline() {
        float cm = cm();
        float[] local;
        switch (kind) {
            case RULER: {
                float l = 15 * cm, h = 2.2f * cm;
                local = new float[] {-l / 2, -h / 2, l / 2, -h / 2, l / 2, h / 2, -l / 2, h / 2};
                break;
            }
            case SQUARE45: {
                float a = 10 * cm;
                local = new float[] {-a / 3, -a / 3, -a / 3 + a, -a / 3, -a / 3, -a / 3 + a};
                break;
            }
            case SQUARE60: {
                float a = 12 * cm, b = a * (float) Math.tan(Math.toRadians(30));
                local = new float[] {-a / 3, -b / 3, -a / 3 + a, -b / 3, -a / 3, -b / 3 + b};
                break;
            }
            case PROTRACTOR: {
                float r = 6 * cm;
                local = new float[] {-r, 0, r, 0, r, 0.6f * cm, -r, 0.6f * cm};
                break;
            }
            default:
                return new float[0];
        }
        double a = Math.toRadians(angle);
        float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
        float[] out = new float[local.length];
        for (int i = 0; i < local.length; i += 2) {
            out[i] = cx + local[i] * cos - local[i + 1] * sin;
            out[i + 1] = cy + local[i] * sin + local[i + 1] * cos;
        }
        return out;
    }

    private boolean inside(float x, float y) {
        if (kind == COMPASS) return Math.hypot(x - cx, y - cy) < 34 * density;
        if (kind == PROTRACTOR) {
            float r = 6 * cm();
            double a = Math.toRadians(-angle);
            float lx = (float) ((x - cx) * Math.cos(a) - (y - cy) * Math.sin(a)), ly = (float) ((x - cx) * Math.sin(a) + (y - cy) * Math.cos(a));
            return (Math.hypot(lx, ly) <= r && ly <= 0) || (Math.abs(lx) <= r && ly >= 0 && ly <= 0.6f * cm());
        }
        float[] p = outline();
        boolean in = false;
        for (int i = 0, j = p.length - 2; i < p.length; j = i, i += 2) {
            if ((p[i + 1] > y) != (p[j + 1] > y) && x < (p[j] - p[i]) * (y - p[i + 1]) / (p[j + 1] - p[i + 1]) + p[i]) in = !in;
        }
        return in;
    }

    /** Dedos sobre la guía: uno la mueve, dos la giran. Devuelve si se los queda. */
    boolean touch(MotionEvent e) {
        if (kind == NONE) return false;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (e.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER || !inside(e.getX(), e.getY())) return false;
            touchId = e.getPointerId(0);
            lastX = e.getX();
            lastY = e.getY();
            twoFingers = false;
            return true;
        }
        if (touchId == -1) return false;
        if (action == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() == 2) {
            twoFingers = true;
            lastAngle = angleOf(e);
            lastDist = 0;
            lastX = (e.getX(0) + e.getX(1)) / 2;
            lastY = (e.getY(0) + e.getY(1)) / 2;
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (twoFingers && e.getPointerCount() >= 2) {
                float a = angleOf(e), mx = (e.getX(0) + e.getX(1)) / 2, my = (e.getY(0) + e.getY(1)) / 2;
                angle += a - lastAngle;
                // Se pega a los ángulos redondos (cada 15°).
                float snapped = Math.round(angle / 15f) * 15f;
                if (Math.abs(snapped - angle) < 1.5f) angle = snapped;
                cx += mx - lastX;
                cy += my - lastY;
                lastAngle = a;
                lastX = mx;
                lastY = my;
            } else if (!twoFingers) {
                int i = e.findPointerIndex(touchId);
                if (i < 0) return true;
                cx += e.getX(i) - lastX;
                cy += e.getY(i) - lastY;
                lastX = e.getX(i);
                lastY = e.getY(i);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touchId = -1;
            twoFingers = false;
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            twoFingers = false;
            int keep = e.getActionIndex() == 0 ? 1 : 0;
            touchId = e.getPointerId(keep);
            lastX = e.getX(keep);
            lastY = e.getY(keep);
        }
        return true;
    }

    boolean dragging() {
        return touchId != -1;
    }

    private static float angleOf(MotionEvent e) {
        return (float) Math.toDegrees(Math.atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0)));
    }

    /** Al empezar un trazo en (x, y) de pantalla: ¿se pega a algo? */
    void begin(float x, float y) {
        edge = -1;
        radius = 0;
        if (kind == NONE) return;
        float near = 36 * density;
        if (kind == COMPASS) {
            radius = (float) Math.hypot(x - cx, y - cy);
            if (radius < 10 * density) radius = 0;
            return;
        }
        if (kind == PROTRACTOR) {
            float r = 6 * cm(), d = (float) Math.hypot(x - cx, y - cy);
            if (Math.abs(d - r) < near) {
                radius = r;
                return;
            }
        }
        poly = outline();
        float best = near;
        for (int i = 0; i < poly.length; i += 2) {
            int j = (i + 2) % poly.length;
            float d = distToSegment(x, y, poly[i], poly[i + 1], poly[j], poly[j + 1]);
            if (d < best) {
                best = d;
                edge = i;
            }
        }
    }

    boolean snapping() {
        return edge >= 0 || radius > 0;
    }

    /** El punto de pantalla llevado al borde o al círculo. */
    void snap(float[] p) {
        if (radius > 0) {
            double a = Math.atan2(p[1] - cy, p[0] - cx);
            p[0] = cx + radius * (float) Math.cos(a);
            p[1] = cy + radius * (float) Math.sin(a);
            return;
        }
        if (edge < 0) return;
        int j = (edge + 2) % poly.length;
        float x0 = poly[edge], y0 = poly[edge + 1], dx = poly[j] - x0, dy = poly[j + 1] - y0;
        float len2 = dx * dx + dy * dy;
        float t = len2 == 0 ? 0 : ((p[0] - x0) * dx + (p[1] - y0) * dy) / len2;
        t = Math.max(-0.02f, Math.min(1.02f, t));
        p[0] = x0 + dx * t;
        p[1] = y0 + dy * t;
    }

    void end() {
        edge = -1;
        radius = 0;
    }

    private static float distToSegment(float x, float y, float x0, float y0, float x1, float y1) {
        float dx = x1 - x0, dy = y1 - y0, len2 = dx * dx + dy * dy;
        float t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - x0) * dx + (y - y0) * dy) / len2));
        return (float) Math.hypot(x - (x0 + dx * t), y - (y0 + dy * t));
    }

    // ---- dibujo ----

    void draw(Canvas c, boolean dark) {
        if (kind == NONE) return;
        int ink = dark ? 0xFFE5E7EB : 0xFF1F2937;
        fill.setColor(dark ? 0x553B82F6 : 0x40BFDBFE);
        line.setColor(ink & 0xFFFFFF | 0xCC000000);
        line.setStrokeWidth(1.2f * density);
        label.setColor(ink);
        label.setTextSize(11 * density);
        float cm = cm();
        if (kind == COMPASS) {
            fill.setColor(0x33F59E0B);
            c.drawCircle(cx, cy, 26 * density, fill);
            line.setColor(0xFFF59E0B);
            line.setStrokeWidth(2 * density);
            c.drawLine(cx - 10 * density, cy, cx + 10 * density, cy, line);
            c.drawLine(cx, cy - 10 * density, cx, cy + 10 * density, line);
            if (radius > 0) {
                line.setStrokeWidth(1 * density);
                line.setPathEffect(new android.graphics.DashPathEffect(new float[] {6 * density, 6 * density}, 0));
                c.drawCircle(cx, cy, radius, line);
                line.setPathEffect(null);
                c.drawText(String.format(java.util.Locale.ROOT, "r = %.1f cm", radius / cm), cx, cy - 32 * density, label);
            } else {
                c.drawText("Aguja: arrástrala con el dedo", cx, cy - 34 * density, label);
            }
            return;
        }
        c.save();
        c.translate(cx, cy);
        c.rotate(angle);
        if (kind == PROTRACTOR) {
            float r = 6 * cm;
            Path p = new Path();
            p.addArc(new RectF(-r, -r, r, r), 180, 180);
            p.lineTo(r, 0.6f * cm);
            p.lineTo(-r, 0.6f * cm);
            p.close();
            c.drawPath(p, fill);
            c.drawPath(p, line);
            for (int d = 0; d <= 180; d++) {
                double a = Math.toRadians(180 + d);
                float l = d % 10 == 0 ? 0.6f * cm : d % 5 == 0 ? 0.4f * cm : 0.22f * cm;
                float x0 = r * (float) Math.cos(a), y0 = r * (float) Math.sin(a);
                c.drawLine(x0, y0, (r - l) * (float) Math.cos(a), (r - l) * (float) Math.sin(a), line);
                if (d % 30 == 0) c.drawText(String.valueOf(d), (r - 1.0f * cm) * (float) Math.cos(a), (r - 1.0f * cm) * (float) Math.sin(a) + 4 * density, label);
            }
            c.drawCircle(0, 0, 3 * density, line);
            c.restore();
            return;
        }
        float[] p = outline();
        c.restore();
        Path path = new Path();
        path.moveTo(p[0], p[1]);
        for (int i = 2; i < p.length; i += 2) path.lineTo(p[i], p[i + 1]);
        path.close();
        c.drawPath(path, fill);
        c.drawPath(path, line);
        // Marcas en milímetros por el primer borde.
        float x0 = p[0], y0 = p[1], x1 = p[2], y1 = p[3];
        float len = (float) Math.hypot(x1 - x0, y1 - y0), ux = (x1 - x0) / len, uy = (y1 - y0) / len;
        float nx = -uy, ny = ux;
        int mm = (int) (len / (cm / 10));
        for (int i = 0; i <= mm; i++) {
            float d = i * cm / 10, l = i % 10 == 0 ? 0.45f * cm : i % 5 == 0 ? 0.3f * cm : 0.17f * cm;
            float sx = x0 + ux * d, sy = y0 + uy * d;
            c.drawLine(sx, sy, sx + nx * l, sy + ny * l, line);
            if (i % 10 == 0 && i > 0 && i < mm - 2) c.drawText(String.valueOf(i / 10), sx + nx * 0.75f * cm, sy + ny * 0.75f * cm + 4 * density, label);
        }
        float shown = ((angle % 360) + 360) % 360;
        c.drawText(String.format(java.util.Locale.ROOT, "%.0f°", shown), cx, cy + 4 * density, label);
    }
}
