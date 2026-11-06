package dev.synctablet;

import java.util.ArrayList;

/**
 * Figuras perfectas a partir de dos puntos (arrastrar con el lápiz): línea,
 * flechas, rectángulo, elipse, triángulo y ejes. Cada figura sale como una sola
 * línea de puntos, así que para el PC es un trazo normal.
 *
 * Java puro (sin Android) para poder probarlo en el PC.
 */
final class ShapeTools {
    static final int LINE = 0, ARROW = 1, DOUBLE_ARROW = 2, RECT = 3, ELLIPSE = 4, TRIANGLE = 5, AXES = 6, CROSS_AXES = 7;
    /** Componentes de circuito: de terminal a terminal, como una línea. */
    static final int RESISTOR = 8, CAPACITOR = 9, INDUCTOR = 10, VOLTAGE = 11, CURRENT = 12, AC = 13, DIODE = 14, GROUND = 15, SWITCH = 16;
    /** Con la herramienta de figuras: dibujar a mano y que se entienda el componente. */
    static final int CIRCUIT_FREE = 100;
    /** En una lista de puntos, marca que ahí se levanta el lápiz. */
    static final float JUMP = Float.NaN;

    /** Separación de la cuadrícula y los puntos de la hoja. */
    static final float GRID = 40;

    private static final String[] NAMES = {"line", "arrow", "double-arrow", "rect", "ellipse", "triangle", "axes", "cross-axes",
            "resistor", "capacitor", "inductor", "voltage", "current", "ac", "diode", "ground", "switch"};

    /**
     * Lo que hace editable a una figura: de qué tipo es, sus dos puntos y su giro
     * (radianes, alrededor de su centro). Los puntos del trazo salen de aquí.
     * No cambia: cada edición da otra.
     */
    static final class Spec {
        final int kind;
        final float x0, y0, x1, y1, angle;
        /** Id del texto con el valor del componente; viaja con él. */
        final String label;

        Spec(int kind, float x0, float y0, float x1, float y1, float angle) {
            this(kind, x0, y0, x1, y1, angle, null);
        }

        Spec(int kind, float x0, float y0, float x1, float y1, float angle, String label) {
            this.label = label;
            this.kind = kind;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.angle = angle;
        }

        /** Se edita por sus puntas, no por una caja. */
        boolean isLine() {
            return kind == LINE || kind == ARROW || kind == DOUBLE_ARROW || isComponent();
        }

        boolean isComponent() {
            return kind >= RESISTOR && kind <= SWITCH;
        }

        Spec withLabel(String id) {
            return new Spec(kind, x0, y0, x1, y1, angle, id);
        }

        /** Dónde va su valor: al lado del componente. */
        float[] labelPosition() {
            float mx = (x0 + x1) / 2, my = (y0 + y1) / 2, dx = x1 - x0, dy = y1 - y0;
            float len = Math.max(1, (float) Math.hypot(dx, dy)), off = 46;
            // Del lado de arriba, o del derecho si va vertical.
            float nx = -dy / len, ny = dx / len;
            if (ny > 0 || (ny == 0 && nx < 0)) { nx = -nx; ny = -ny; }
            return new float[] {mx + nx * off, my + ny * off};
        }

        String name() {
            return NAMES[kind];
        }

        static int kindOf(String name) {
            for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i;
            return -1;
        }

        /** Los ejes cruzados nacen de su origen; lo demás, de la caja. */
        private float centerX() {
            return kind == CROSS_AXES ? x0 : (x0 + x1) / 2;
        }

        private float centerY() {
            return kind == CROSS_AXES ? y0 : (y0 + y1) / 2;
        }

        float[] points() {
            float[] xy = shape(kind, x0, y0, x1, y1);
            if (angle == 0) return xy;
            float cx = centerX(), cy = centerY();
            double cos = Math.cos(angle), sin = Math.sin(angle);
            for (int i = 0; i + 1 < xy.length; i += 2) {
                double dx = xy[i] - cx, dy = xy[i + 1] - cy;
                xy[i] = cx + (float) (dx * cos - dy * sin);
                xy[i + 1] = cy + (float) (dx * sin + dy * cos);
            }
            return xy;
        }

        /**
         * La figura tras la transformación afín {a, b, c, d, e, f}, que incluye el
         * giro `turn`. Null si deja de ser esta figura (estirar una caja girada la
         * sesgaría): el trazo sigue como dibujo libre.
         */
        Spec mapped(float[] m, float turn) {
            if (isLine() || (angle == 0 && turn == 0)) {
                return new Spec(kind, m[0] * x0 + m[2] * y0 + m[4], m[1] * x0 + m[3] * y0 + m[5],
                        m[0] * x1 + m[2] * y1 + m[4], m[1] * x1 + m[3] * y1 + m[5], angle, label);
            }
            float sx = (float) Math.hypot(m[0], m[1]), sy = (float) Math.hypot(m[2], m[3]);
            if (Math.abs(sx - sy) > 0.001f) return null;
            float cx = centerX(), cy = centerY();
            float nx = m[0] * cx + m[2] * cy + m[4], ny = m[1] * cx + m[3] * cy + m[5];
            return new Spec(kind, nx + (x0 - cx) * sx, ny + (y0 - cy) * sx, nx + (x1 - cx) * sx, ny + (y1 - cy) * sx, angle + turn, label);
        }

        Spec withEnd(boolean first, float x, float y) {
            return first ? new Spec(kind, x, y, x1, y1, angle, label) : new Spec(kind, x0, y0, x, y, angle, label);
        }

        Spec withKind(int other) {
            return new Spec(other, x0, y0, x1, y1, angle, label);
        }

        /** Los puntos donde otra línea se puede enganchar: puntas, o esquinas y lados. */
        float[] anchors() {
            if (isLine()) return new float[] {x0, y0, x1, y1};
            float cx = centerX(), cy = centerY(), hw = Math.abs(x1 - x0) / 2, hh = Math.abs(y1 - y0) / 2;
            float[] u = {-hw, 0, hw, -hw, hw, hw, 0, -hw}, v = {-hh, -hh, -hh, 0, 0, hh, hh, hh};
            float[] out = new float[16];
            double cos = Math.cos(angle), sin = Math.sin(angle);
            for (int i = 0; i < 8; i++) {
                out[2 * i] = cx + (float) (u[i] * cos - v[i] * sin);
                out[2 * i + 1] = cy + (float) (u[i] * sin + v[i] * cos);
            }
            return out;
        }
    }

    /** Siguiente aspecto de una línea: línea, flecha, flecha doble. */
    static int nextLineKind(int kind) {
        return kind == LINE ? ARROW : kind == ARROW ? DOUBLE_ARROW : LINE;
    }

    /**
     * La figura de (x0, y0) a (x1, y1). Con `grid` > 0 los puntos caen en la
     * cuadrícula; si no, se enderezan los ángulos y las proporciones casi exactos.
     */
    static float[] build(int kind, float x0, float y0, float x1, float y1, float grid) {
        return spec(kind, x0, y0, x1, y1, grid).points();
    }

    static Spec spec(int kind, float x0, float y0, float x1, float y1, float grid) {
        if (grid > 0) {
            x0 = snap(x0, grid); y0 = snap(y0, grid);
            x1 = snap(x1, grid); y1 = snap(y1, grid);
        } else if (kind == LINE || kind == ARROW || kind == DOUBLE_ARROW || kind >= RESISTOR) {
            float[] end = snapAngle(x0, y0, x1, y1, kind >= RESISTOR ? 8 : 4);
            x1 = end[0]; y1 = end[1];
        } else if (kind == RECT || kind == ELLIPSE) {
            float w = Math.abs(x1 - x0), h = Math.abs(y1 - y0);
            if (Math.abs(w - h) < 0.06f * Math.max(w, h)) {
                float side = (w + h) / 2;
                x1 = x0 + Math.signum(x1 - x0) * side;
                y1 = y0 + Math.signum(y1 - y0) * side;
            }
        }
        return new Spec(kind, x0, y0, x1, y1, 0);
    }

    private static float[] shape(int kind, float x0, float y0, float x1, float y1) {
        if (kind >= RESISTOR) return component(kind, x0, y0, x1, y1);
        switch (kind) {
            case ARROW: return arrow(x0, y0, x1, y1, false);
            case DOUBLE_ARROW: return arrow(x0, y0, x1, y1, true);
            case RECT: return densify(new float[] {x0, y0, x1, y0, x1, y1, x0, y1, x0, y0});
            case ELLIPSE: return ellipse((x0 + x1) / 2, (y0 + y1) / 2, Math.abs(x1 - x0) / 2, Math.abs(y1 - y0) / 2, 0, 0, 1);
            case TRIANGLE: return densify(new float[] {x0, y1, (x0 + x1) / 2, y0, x1, y1, x0, y1});
            case AXES: {
                // Se arrastra de la punta del eje vertical a la del horizontal.
                float[] a = head(x0, y1, x0, y0), b = head(x0, y1, x1, y1);
                return densify(new float[] {a[0], a[1], x0, y0, a[2], a[3], x0, y0, x0, y1, x1, y1, b[0], b[1], x1, y1, b[2], b[3]});
            }
            case CROSS_AXES: {
                // Se arrastra del origen a la punta de un cuadrante.
                float dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
                float[] a = head(x0, y0, x0, y0 - dy), b = head(x0, y0, x0 + dx, y0);
                return densify(new float[] {x0, y0 + dy, x0, y0 - dy, a[0], a[1], x0, y0 - dy, a[2], a[3], x0, y0 - dy,
                        x0, y0, x0 - dx, y0, x0 + dx, y0, b[0], b[1], x0 + dx, y0, b[2], b[3]});
            }
            default: return densify(new float[] {x0, y0, x1, y1});
        }
    }

    /** Un componente de A a B: sus patas y su símbolo, en el centro. */
    private static float[] component(int kind, float x0, float y0, float x1, float y1) {
        float len = (float) Math.hypot(x1 - x0, y1 - y0);
        if (len < 1) return new float[] {x0, y0, x1, y1};
        Pen p = new Pen(x0, y0, (x1 - x0) / len, (y1 - y0) / len);
        float mid = len / 2;
        switch (kind) {
            case RESISTOR: {
                float half = Math.min(len * 0.36f, 52), amp = Math.min(15, len * 0.14f);
                p.to(0, 0).to(mid - half, 0);
                for (int i = 0; i < 6; i++) p.to(mid - half + half * (2 * i + 1) / 6, i % 2 == 0 ? -amp : amp);
                p.to(mid + half, 0).to(len, 0);
                break;
            }
            case CAPACITOR: {
                float gap = Math.min(7, len * 0.08f), plate = Math.min(26, len * 0.3f);
                p.to(0, 0).to(mid - gap, 0).to(mid - gap, -plate).to(mid - gap, plate);
                p.up().to(len, 0).to(mid + gap, 0).to(mid + gap, -plate).to(mid + gap, plate);
                break;
            }
            case INDUCTOR: {
                float half = Math.min(len * 0.36f, 52), r = half / 4;
                p.to(0, 0).to(mid - half, 0);
                for (int loop = 0; loop < 4; loop++) {
                    float cx = mid - half + r * (2 * loop + 1);
                    for (int i = 1; i <= 12; i++) {
                        double a = Math.PI * i / 12;
                        p.to(cx - r * (float) Math.cos(a), -r * 1.15f * (float) Math.sin(a));
                    }
                }
                p.to(len, 0);
                break;
            }
            case VOLTAGE: case CURRENT: case AC: {
                float r = Math.min(34, len * 0.34f);
                p.to(0, 0).to(mid - r, 0);
                p.up();
                for (int i = 0; i <= 40; i++) {
                    double a = 2 * Math.PI * i / 40;
                    p.to(mid - r * (float) Math.cos(a), r * (float) Math.sin(a));
                }
                p.up().to(mid + r, 0).to(len, 0);
                float s = r * 0.32f;
                if (kind == VOLTAGE) {
                    // El + mira a la segunda punta.
                    p.up().to(mid + r * 0.5f - s, 0).to(mid + r * 0.5f + s, 0);
                    p.up().to(mid + r * 0.5f, -s).to(mid + r * 0.5f, s);
                    p.up().to(mid - r * 0.5f, -s).to(mid - r * 0.5f, s);
                } else if (kind == CURRENT) {
                    p.up().to(mid - r * 0.6f, 0).to(mid + r * 0.6f, 0).to(mid + r * 0.2f, -s).up().to(mid + r * 0.6f, 0).to(mid + r * 0.2f, s);
                } else {
                    p.up();
                    for (int i = 0; i <= 16; i++) {
                        double a = 2 * Math.PI * i / 16;
                        p.to(mid - r * 0.6f + r * 1.2f * i / 16, -r * 0.34f * (float) Math.sin(a));
                    }
                }
                break;
            }
            case DIODE: {
                float s = Math.min(22, len * 0.26f);
                p.to(0, 0).to(mid - s, 0).to(mid - s, -s).to(mid + s, 0).to(mid - s, s).to(mid - s, 0);
                p.up().to(mid + s, -s).to(mid + s, s);
                p.up().to(mid + s, 0).to(len, 0);
                break;
            }
            case GROUND: {
                float w = Math.min(26, len * 0.5f);
                p.to(0, 0).to(len, 0).to(len, -w).to(len, w);
                p.up().to(len + 9, -w * 0.62f).to(len + 9, w * 0.62f);
                p.up().to(len + 18, -w * 0.26f).to(len + 18, w * 0.26f);
                break;
            }
            default: { // interruptor
                float half = Math.min(len * 0.28f, 34);
                p.to(0, 0).to(mid - half, 0).to(mid + half * 0.8f, -half * 0.9f);
                p.up().to(mid + half, 0).to(len, 0);
                break;
            }
        }
        return p.points();
    }

    /** Traza en coordenadas del componente: t a lo largo, u de lado. */
    private static final class Pen {
        private final float ox, oy, dx, dy;
        private final ArrayList<Float> out = new ArrayList<>();

        Pen(float ox, float oy, float dx, float dy) {
            this.ox = ox;
            this.oy = oy;
            this.dx = dx;
            this.dy = dy;
        }

        Pen to(float t, float u) {
            out.add(ox + dx * t - dy * u);
            out.add(oy + dy * t + dx * u);
            return this;
        }

        Pen up() {
            out.add(JUMP);
            out.add(JUMP);
            return this;
        }

        float[] points() {
            float[] r = new float[out.size()];
            for (int i = 0; i < r.length; i++) r[i] = out.get(i);
            return r;
        }
    }

    static float snap(float v, float grid) {
        return Math.round(v / grid) * grid;
    }

    /** El punto final, girado al múltiplo de 15° más cercano si está a menos de `tolerance` grados. */
    static float[] snapAngle(float x0, float y0, float x1, float y1, double tolerance) {
        double angle = Math.toDegrees(Math.atan2(y1 - y0, x1 - x0));
        double near = Math.round(angle / 15) * 15.0;
        if (Math.abs(angle - near) >= tolerance) return new float[] {x1, y1};
        double len = Math.hypot(x1 - x0, y1 - y0), r = Math.toRadians(near);
        return new float[] {x0 + (float) (len * Math.cos(r)), y0 + (float) (len * Math.sin(r))};
    }

    /** Flecha de ida y vuelta por la punta, para que sea una sola línea. */
    static float[] arrow(float x0, float y0, float x1, float y1, boolean both) {
        float[] end = head(x0, y0, x1, y1);
        if (!both) return densify(new float[] {x0, y0, x1, y1, end[0], end[1], x1, y1, end[2], end[3]});
        float[] start = head(x1, y1, x0, y0);
        return densify(new float[] {start[0], start[1], x0, y0, start[2], start[3], x0, y0,
                x1, y1, end[0], end[1], x1, y1, end[2], end[3]});
    }

    /** Las dos puntas de la cabeza de una flecha que llega a (x1, y1). */
    private static float[] head(float x0, float y0, float x1, float y1) {
        double len = Math.hypot(x1 - x0, y1 - y0);
        double size = Math.max(6, Math.min(26, len * 0.25));
        double back = Math.atan2(y0 - y1, x0 - x1), open = Math.toRadians(26);
        return new float[] {
            x1 + (float) (size * Math.cos(back + open)), y1 + (float) (size * Math.sin(back + open)),
            x1 + (float) (size * Math.cos(back - open)), y1 + (float) (size * Math.sin(back - open)),
        };
    }

    /** Elipse girada `tilt` radianes, desde el ángulo `start` y en sentido `dir` (1 o −1). */
    static float[] ellipse(float cx, float cy, float rx, float ry, double tilt, double start, double dir) {
        int m = 72;
        float[] out = new float[2 * (m + 1)];
        double cos = Math.cos(tilt), sin = Math.sin(tilt);
        for (int i = 0; i <= m; i++) {
            double t = start + dir * 2 * Math.PI * i / m;
            double u = rx * Math.cos(t), v = ry * Math.sin(t);
            out[2 * i] = cx + (float) (u * cos - v * sin);
            out[2 * i + 1] = cy + (float) (u * sin + v * cos);
        }
        return out;
    }

    /** Puntos cada ~8 unidades sobre los lados (el grosor sale uniforme). */
    static float[] densify(float[] v) {
        ArrayList<Float> out = new ArrayList<>();
        int verts = v.length / 2;
        for (int i = 0; i + 1 < verts; i++) {
            float x0 = v[2 * i], y0 = v[2 * i + 1], x1 = v[2 * i + 2], y1 = v[2 * i + 3];
            int steps = Math.max(1, (int) (Math.hypot(x1 - x0, y1 - y0) / 8));
            for (int s = 0; s < steps; s++) {
                out.add(x0 + (x1 - x0) * s / steps);
                out.add(y0 + (y1 - y0) * s / steps);
            }
        }
        out.add(v[v.length - 2]);
        out.add(v[v.length - 1]);
        float[] r = new float[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    private ShapeTools() {}
}
