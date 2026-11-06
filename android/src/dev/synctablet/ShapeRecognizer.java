package dev.synctablet;

import java.util.ArrayList;

/**
 * Reconoce figuras dibujadas a mano y devuelve su versión perfecta: línea,
 * flecha, círculo, elipse (también inclinada), rectángulo, triángulo y polígonos.
 * También une trazos seguidos: líneas que cierran un polígono, o una línea y
 * la «V» de su punta.
 *
 * Java puro (sin Android) para poder probarlo en el PC.
 *
 * 1. Se remuestrea el trazo a puntos equidistantes.
 * 2. Abierto: con cabeza → flecha; casi recto → línea (ángulos de 15° exactos).
 * 3. Cerrado: se buscan las esquinas (Douglas-Peucker sobre el contorno).
 *    Contorno suave que se ajusta a una elipse → círculo o elipse;
 *    3 esquinas → triángulo; 4 → rectángulo (si los ángulos son rectos) o
 *    cuadrilátero; 5 o 6 → polígono.
 */
final class ShapeRecognizer {
    static final String LINE = "línea", CIRCLE = "círculo", ELLIPSE = "elipse",
            RECTANGLE = "rectángulo", TRIANGLE = "triángulo", QUAD = "cuadrilátero",
            POLYGON = "polígono", ARROW = "flecha";

    static final class Shape {
        final String kind;
        /** x, y seguidos, en orden de dibujo. */
        final float[] xy;

        Shape(String kind, float[] xy) {
            this.kind = kind;
            this.xy = xy;
        }
    }

    private static final int N = 64;

    /** `xy`: x, y seguidos (n puntos). Null si no parece ninguna figura. */
    static Shape recognize(float[] xy, int n) {
        if (n < 2) return null;
        float length = pathLength(xy, n);
        if (length < 24) return null;
        float[] p = resample(xy, n, N);
        float gap = dist(p[0], p[1], p[2 * N - 2], p[2 * N - 1]);

        // Flecha de un solo trazo: la cabeza va después de la línea.
        Shape arrow = gap > 0.5f * length ? singleArrow(p, length) : null;
        if (arrow != null) return arrow;

        // Línea: casi recta y abierta.
        float dev = maxDeviation(p, N, 0, N - 1);
        if (gap > 0.6f * length && dev < 0.05f * length) return line(p[0], p[1], p[2 * N - 2], p[2 * N - 1]);
        if (gap > 0.22f * length) return null;

        // Contorno cerrado: esquinas por Douglas-Peucker.
        ArrayList<Integer> corners = closedCorners(p, N, 0.045f * length);
        float ellipseError = ellipseError(p, N);
        // Un hexágono se parece a un círculo: gana lo que mejor se ajuste al trazo.
        if ((corners.size() == 5 || corners.size() == 6) && evenSides(p, corners, length)
                && polygonError(p, corners) < 0.95f * ellipseDistance(p, ellipseError)) {
            return closed(POLYGON, vertices(p, corners));
        }
        if (ellipseError < 0.11f && corners.size() >= 5) return ellipse(p, N);
        // Polígono sólo si sus esquinas son afiladas: los extremos de una
        // elipse alargada también salen como «esquinas», pero suaves.
        boolean sharp = true;
        for (int c : corners) sharp &= turn(p, N, c) < 125;
        if (sharp && corners.size() == 3) return triangle(vertices(p, corners));
        if (sharp && corners.size() == 4) return quad(vertices(p, corners));
        // Con 3 o 4 esquinas dudosas, elipse sólo si lo es claramente; si no,
        // mejor no tocar el trazo que convertirlo en otra cosa.
        boolean fewCorners = corners.size() == 3 || corners.size() == 4;
        if (ellipseError < (fewCorners ? 0.08f : 0.2f)) return ellipse(p, N);
        return null;
    }

    // ---- Figuras ----

    /** Línea recta; casi a un múltiplo de 15°, exacta (a 6° si es horizontal o vertical). */
    static Shape line(float x0, float y0, float x1, float y1) {
        double angle = Math.toDegrees(Math.atan2(y1 - y0, x1 - x0));
        double a = ((angle % 90) + 90) % 90;
        if (a < 6 || a > 84) {
            if (Math.abs(x1 - x0) > Math.abs(y1 - y0)) y1 = y0 = (y0 + y1) / 2;
            else x1 = x0 = (x0 + x1) / 2;
        } else {
            float[] end = ShapeTools.snapAngle(x0, y0, x1, y1, 3);
            x1 = end[0];
            y1 = end[1];
        }
        return new Shape(LINE, ShapeTools.densify(new float[] {x0, y0, x1, y1}));
    }

    /** Elipse ajustada por sus ejes principales, así que puede salir inclinada. */
    private static Shape ellipse(float[] p, int n) {
        float[] e = ellipseFit(p, n);
        float cx = e[0], cy = e[1], rx = e[2], ry = e[3];
        double tilt = e[4];
        String kind = ELLIPSE;
        if (Math.abs(rx - ry) < 0.15f * Math.max(rx, ry)) {
            rx = ry = (rx + ry) / 2;
            tilt = 0;
            kind = CIRCLE;
        } else {
            // Casi derecha: derecha.
            double d = Math.toDegrees(tilt) % 90;
            if (Math.abs(d) < 7 || Math.abs(d) > 83) {
                double q = Math.round(Math.toDegrees(tilt) / 90) * 90;
                tilt = Math.toRadians(q);
            }
        }
        // Empieza donde empezó el trazo y gira en el mismo sentido.
        double dx = p[0] - cx, dy = p[1] - cy, cos = Math.cos(tilt), sin = Math.sin(tilt);
        double start = Math.atan2((-dx * sin + dy * cos) / ry, (dx * cos + dy * sin) / rx);
        double dir = signedArea(p, n) >= 0 ? 1 : -1;
        return new Shape(kind, ShapeTools.ellipse(cx, cy, rx, ry, tilt, start, dir));
    }

    /** Las esquinas como x, y seguidos. */
    private static float[] vertices(float[] p, ArrayList<Integer> corners) {
        float[] v = new float[2 * corners.size()];
        for (int i = 0; i < corners.size(); i++) {
            v[2 * i] = p[2 * corners.get(i)];
            v[2 * i + 1] = p[2 * corners.get(i) + 1];
        }
        return v;
    }

    private static Shape closed(String kind, float[] v) {
        float[] c = java.util.Arrays.copyOf(v, v.length + 2);
        c[v.length] = v[0];
        c[v.length + 1] = v[1];
        return new Shape(kind, ShapeTools.densify(c));
    }

    /** ¿Ningún lado es diminuto? Si lo hay, es un garabato con picos. */
    private static boolean evenSides(float[] p, ArrayList<Integer> corners, float length) {
        for (int k = 0; k < corners.size(); k++) {
            int a = corners.get(k), b = corners.get((k + 1) % corners.size());
            if (dist(p[2 * a], p[2 * a + 1], p[2 * b], p[2 * b + 1]) < 0.08f * length) return false;
        }
        return true;
    }

    /** Lados casi horizontales o verticales, exactos; casi recto, recto. */
    private static Shape triangle(float[] v) {
        for (int i = 0; i < 3; i++) {
            int j = (i + 1) % 3;
            double a = ((Math.toDegrees(Math.atan2(v[2 * j + 1] - v[2 * i + 1], v[2 * j] - v[2 * i])) % 90) + 90) % 90;
            if (a >= 6 && a <= 84) continue;
            int axis = Math.abs(v[2 * j] - v[2 * i]) > Math.abs(v[2 * j + 1] - v[2 * i + 1]) ? 1 : 0;
            v[2 * i + axis] = v[2 * j + axis] = (v[2 * i + axis] + v[2 * j + axis]) / 2;
        }
        for (int i = 0; i < 3; i++) {
            int a = (i + 2) % 3, b = (i + 1) % 3;
            double ang = angle(v[2 * a] - v[2 * i], v[2 * a + 1] - v[2 * i + 1], v[2 * b] - v[2 * i], v[2 * b + 1] - v[2 * i + 1]);
            if (Math.abs(ang - 90) > 8 || Math.abs(ang - 90) < 0.01) continue;
            // Ángulo recto exacto: el vértice cae en el círculo cuyo diámetro es el lado opuesto.
            float mx = (v[2 * a] + v[2 * b]) / 2, my = (v[2 * a + 1] + v[2 * b + 1]) / 2;
            float k = dist(v[2 * a], v[2 * a + 1], v[2 * b], v[2 * b + 1]) / 2 / Math.max(1, dist(mx, my, v[2 * i], v[2 * i + 1]));
            v[2 * i] = mx + (v[2 * i] - mx) * k;
            v[2 * i + 1] = my + (v[2 * i + 1] - my) * k;
            break;
        }
        return closed(TRIANGLE, v);
    }

    /** Cuatro esquinas: rectángulo exacto (girado o no) si los ángulos son casi rectos. */
    private static Shape quad(float[] c) {
        for (int i = 0; i < 4; i++) {
            int a = (i + 3) % 4, b = (i + 1) % 4;
            double ang = angle(c[2 * a] - c[2 * i], c[2 * a + 1] - c[2 * i + 1], c[2 * b] - c[2 * i], c[2 * b + 1] - c[2 * i + 1]);
            if (Math.abs(ang - 90) > 18) return closed(QUAD, c);
        }
        // Giro medio de los cuatro lados (módulo 90°).
        double sx = 0, sy = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            double t = 4 * Math.atan2(c[2 * j + 1] - c[2 * i + 1], c[2 * j] - c[2 * i]);
            sx += Math.cos(t);
            sy += Math.sin(t);
        }
        double rot = Math.atan2(sy, sx) / 4;
        if (Math.abs(Math.toDegrees(rot)) < 10) rot = 0;
        double cos = Math.cos(rot), sin = Math.sin(rot);
        // Las esquinas en los ejes del rectángulo.
        float[] u = new float[4], w = new float[4];
        float mu = 0, mw = 0;
        for (int i = 0; i < 4; i++) {
            u[i] = (float) (c[2 * i] * cos + c[2 * i + 1] * sin);
            w[i] = (float) (-c[2 * i] * sin + c[2 * i + 1] * cos);
            mu += u[i] / 4;
            mw += w[i] / 4;
        }
        float halfU = 0, halfW = 0;
        for (int i = 0; i < 4; i++) {
            halfU += Math.abs(u[i] - mu) / 4;
            halfW += Math.abs(w[i] - mw) / 4;
        }
        if (Math.abs(halfU - halfW) < 0.08f * Math.max(halfU, halfW)) halfU = halfW = (halfU + halfW) / 2;
        float[] v = new float[8];
        for (int i = 0; i < 4; i++) {
            float a = mu + Math.signum(u[i] - mu) * halfU, b = mw + Math.signum(w[i] - mw) * halfW;
            v[2 * i] = (float) (a * cos - b * sin);
            v[2 * i + 1] = (float) (a * sin + b * cos);
        }
        return closed(RECTANGLE, v);
    }

    // ---- Flechas y figuras de varios trazos ----

    /** Una línea y, en el mismo trazo, su cabeza: inicio, punta, barba, (punta), barba. */
    private static Shape singleArrow(float[] p, float length) {
        boolean[] keep = new boolean[N];
        keep[0] = keep[N - 1] = true;
        simplify(p, 0, N - 1, 0.03f * length, keep);
        ArrayList<Integer> v = new ArrayList<>();
        for (int i = 0; i < N; i++) if (keep[i]) v.add(i);
        if (v.size() != 4 && v.size() != 5) return null;
        int s = 0, t = v.get(1), a = v.get(2), b = N - 1;
        float sx = p[0], sy = p[1], tx = p[2 * t], ty = p[2 * t + 1];
        float shaft = dist(sx, sy, tx, ty);
        if (shaft < 0.55f * length) return null;
        if (!barbs(sx, sy, tx, ty, p[2 * a], p[2 * a + 1], p[2 * b], p[2 * b + 1], shaft)) return null;
        return arrowShape(sx, sy, tx, ty);
    }

    /**
     * La «V» dibujada en otro trazo, en una punta de la línea (x0, y0)–(x1, y1).
     * Null si no es la cabeza de esa línea.
     */
    static Shape arrowHead(float x0, float y0, float x1, float y1, float[] xy, int n) {
        if (n < 3) return null;
        float length = pathLength(xy, n), shaft = dist(x0, y0, x1, y1);
        if (length < 12 || length > 1.2f * shaft) return null;
        float[] p = resample(xy, n, N);
        // La esquina de la V es el punto más lejano a la recta entre sus extremos.
        int apex = 0;
        float best = -1;
        for (int i = 1; i < N - 1; i++) {
            float d = segmentDistance(p[2 * i], p[2 * i + 1], p[0], p[1], p[2 * N - 2], p[2 * N - 1]);
            if (d > best) { best = d; apex = i; }
        }
        float ax = p[2 * apex], ay = p[2 * apex + 1];
        if (maxDeviation(p, N, 0, apex) > 0.15f * length || maxDeviation(p, N, apex, N - 1) > 0.15f * length) return null;
        float reach = Math.max(18, 0.2f * shaft);
        boolean atEnd = dist(ax, ay, x1, y1) <= dist(ax, ay, x0, y0);
        float tx = atEnd ? x1 : x0, ty = atEnd ? y1 : y0, sx = atEnd ? x0 : x1, sy = atEnd ? y0 : y1;
        if (dist(ax, ay, tx, ty) > reach) return null;
        if (!barbs(sx, sy, tx, ty, p[0], p[1], p[2 * N - 2], p[2 * N - 1], shaft)) return null;
        return arrowShape(sx, sy, tx, ty);
    }

    /** ¿(ax, ay) y (bx, by) salen de la punta hacia atrás, uno a cada lado de la línea? */
    private static boolean barbs(float sx, float sy, float tx, float ty, float ax, float ay, float bx, float by, float shaft) {
        float dx = sx - tx, dy = sy - ty;
        double angA = angle(dx, dy, ax - tx, ay - ty), angB = angle(dx, dy, bx - tx, by - ty);
        if (angA < 10 || angA > 75 || angB < 10 || angB > 75) return false;
        if (dist(tx, ty, ax, ay) > 0.6f * shaft || dist(tx, ty, bx, by) > 0.6f * shaft) return false;
        return (dx * (ay - ty) - dy * (ax - tx)) * (dx * (by - ty) - dy * (bx - tx)) < 0;
    }

    private static Shape arrowShape(float sx, float sy, float tx, float ty) {
        float[] end = ShapeTools.snapAngle(sx, sy, tx, ty, 4);
        return new Shape(ARROW, ShapeTools.arrow(sx, sy, end[0], end[1], false));
    }

    /**
     * Líneas sueltas {x0, y0, x1, y1} que se tocan por las puntas y cierran un
     * triángulo o un cuadrilátero, en cualquier orden. Null si no cierran.
     */
    static Shape closeLines(float[][] lines) {
        int n = lines.length;
        if (n != 3 && n != 4) return null;
        float perimeter = 0;
        for (float[] l : lines) perimeter += dist(l[0], l[1], l[2], l[3]);
        float tol = Math.max(16, 0.1f * perimeter);
        boolean[] used = new boolean[n];
        used[0] = true;
        float[] v = new float[2 * n];
        float tailX = lines[0][2], tailY = lines[0][3];
        for (int k = 1; k < n; k++) {
            int next = -1;
            boolean flip = false;
            float best = tol;
            for (int i = 0; i < n; i++) {
                if (used[i]) continue;
                float a = dist(tailX, tailY, lines[i][0], lines[i][1]), b = dist(tailX, tailY, lines[i][2], lines[i][3]);
                if (a <= best) { best = a; next = i; flip = false; }
                if (b <= best) { best = b; next = i; flip = true; }
            }
            if (next < 0) return null;
            used[next] = true;
            float[] l = lines[next];
            float headX = flip ? l[2] : l[0], headY = flip ? l[3] : l[1];
            v[2 * k] = (tailX + headX) / 2;
            v[2 * k + 1] = (tailY + headY) / 2;
            tailX = flip ? l[0] : l[2];
            tailY = flip ? l[1] : l[3];
        }
        if (dist(tailX, tailY, lines[0][0], lines[0][1]) > tol) return null;
        v[0] = (tailX + lines[0][0]) / 2;
        v[1] = (tailY + lines[0][1]) / 2;
        return n == 3 ? triangle(v) : quad(v);
    }

    // ---- Geometría ----

    private static ArrayList<Integer> closedCorners(float[] p, int n, float eps) {
        // El punto más lejano al inicio parte el contorno en dos tramos abiertos.
        int far = 0;
        float best = -1;
        for (int i = 0; i < n; i++) {
            float d = dist(p[0], p[1], p[2 * i], p[2 * i + 1]);
            if (d > best) { best = d; far = i; }
        }
        boolean[] keep = new boolean[n];
        keep[0] = keep[far] = true;
        simplify(p, 0, far, eps, keep);
        simplify(p, far, n - 1, eps, keep);
        ArrayList<Integer> out = new ArrayList<>();
        for (int i = 0; i < n - 1; i++) if (keep[i]) out.add(i);
        // Quitar «esquinas» casi rectas (180°) que deja Douglas-Peucker.
        for (boolean changed = true; changed && out.size() > 2; ) {
            changed = false;
            for (int k = 0; k < out.size(); k++) {
                int a = out.get((k + out.size() - 1) % out.size()), i = out.get(k), b = out.get((k + 1) % out.size());
                double ang = angle(p[2 * a] - p[2 * i], p[2 * a + 1] - p[2 * i + 1], p[2 * b] - p[2 * i], p[2 * b + 1] - p[2 * i + 1]);
                if (ang > 150) {
                    out.remove(k);
                    changed = true;
                    break;
                }
            }
        }
        return out;
    }

    private static void simplify(float[] p, int a, int b, float eps, boolean[] keep) {
        if (b <= a + 1) return;
        int idx = -1;
        float best = eps;
        for (int i = a + 1; i < b; i++) {
            float d = segmentDistance(p[2 * i], p[2 * i + 1], p[2 * a], p[2 * a + 1], p[2 * b], p[2 * b + 1]);
            if (d > best) { best = d; idx = i; }
        }
        if (idx < 0) return;
        keep[idx] = true;
        simplify(p, a, idx, eps, keep);
        simplify(p, idx, b, eps, keep);
    }

    /** Ángulo en el punto i del contorno cerrado, mirando 4 puntos a cada lado. */
    private static double turn(float[] p, int n, int i) {
        int m = n - 1; // el último repite el primero
        int a = (i - 4 + m) % m, b = (i + 4) % m;
        return angle(p[2 * a] - p[2 * i], p[2 * a + 1] - p[2 * i + 1], p[2 * b] - p[2 * i], p[2 * b + 1] - p[2 * i + 1]);
    }

    /** Elipse de ejes principales: centro x, y, radios y giro en radianes. */
    private static float[] ellipseFit(float[] p, int n) {
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) { mx += p[2 * i]; my += p[2 * i + 1]; }
        mx /= n;
        my /= n;
        double sxx = 0, syy = 0, sxy = 0;
        for (int i = 0; i < n; i++) {
            double dx = p[2 * i] - mx, dy = p[2 * i + 1] - my;
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy;
        }
        double tilt = 0.5 * Math.atan2(2 * sxy, sxx - syy);
        double cos = Math.cos(tilt), sin = Math.sin(tilt);
        double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE, minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double dx = p[2 * i] - mx, dy = p[2 * i + 1] - my;
            double u = dx * cos + dy * sin, v = -dx * sin + dy * cos;
            minU = Math.min(minU, u); maxU = Math.max(maxU, u);
            minV = Math.min(minV, v); maxV = Math.max(maxV, v);
        }
        // El centro de la caja girada: el trazo puede repetir un trozo y mover la media.
        double cu = (minU + maxU) / 2, cv = (minV + maxV) / 2;
        return new float[] {(float) (mx + cu * cos - cv * sin), (float) (my + cu * sin + cv * cos),
                (float) Math.max(1, (maxU - minU) / 2), (float) Math.max(1, (maxV - minV) / 2), (float) tilt};
    }

    /** Distancia media del trazo al polígono de sus esquinas. */
    private static float polygonError(float[] p, ArrayList<Integer> corners) {
        float sum = 0;
        for (int i = 0; i < N; i++) {
            float best = Float.MAX_VALUE;
            for (int k = 0; k < corners.size(); k++) {
                int a = corners.get(k), b = corners.get((k + 1) % corners.size());
                best = Math.min(best, segmentDistance(p[2 * i], p[2 * i + 1], p[2 * a], p[2 * a + 1], p[2 * b], p[2 * b + 1]));
            }
            sum += best;
        }
        return sum / N;
    }

    /** El error de la elipse en las mismas unidades que polygonError. */
    private static float ellipseDistance(float[] p, float error) {
        float[] e = ellipseFit(p, N);
        return error * (float) Math.sqrt(e[2] * e[3]);
    }

    /** Error medio respecto a la elipse ajustada (0 = perfecta). */
    private static float ellipseError(float[] p, int n) {
        float[] e = ellipseFit(p, n);
        float rx = e[2], ry = e[3];
        if (Math.min(rx, ry) / Math.max(rx, ry) < 0.15f) return 1;
        double cos = Math.cos(e[4]), sin = Math.sin(e[4]);
        float sum = 0;
        for (int i = 0; i < n; i++) {
            double dx = p[2 * i] - e[0], dy = p[2 * i + 1] - e[1];
            double u = (dx * cos + dy * sin) / rx, v = (-dx * sin + dy * cos) / ry;
            sum += Math.abs((float) Math.sqrt(u * u + v * v) - 1);
        }
        return sum / n;
    }

    private static float signedArea(float[] p, int n) {
        float a = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            a += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1];
        }
        return a / 2;
    }

    private static double angle(float ax, float ay, float bx, float by) {
        double d = Math.sqrt((ax * ax + ay * ay) * (bx * bx + by * by));
        if (d == 0) return 180;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (ax * bx + ay * by) / d))));
    }

    private static float maxDeviation(float[] p, int n, int a, int b) {
        float best = 0;
        for (int i = a; i <= b; i++) {
            best = Math.max(best, segmentDistance(p[2 * i], p[2 * i + 1], p[2 * a], p[2 * a + 1], p[2 * b], p[2 * b + 1]));
        }
        return best;
    }

    private static float pathLength(float[] xy, int n) {
        float l = 0;
        for (int i = 1; i < n; i++) l += dist(xy[2 * i - 2], xy[2 * i - 1], xy[2 * i], xy[2 * i + 1]);
        return l;
    }

    /** m puntos a la misma distancia unos de otros a lo largo del trazo. */
    static float[] resample(float[] xy, int n, int m) {
        float total = pathLength(xy, n);
        float step = total / (m - 1);
        float[] out = new float[2 * m];
        out[0] = xy[0];
        out[1] = xy[1];
        int k = 1;
        float acc = 0, px = xy[0], py = xy[1];
        for (int i = 1; i < n && k < m; i++) {
            float qx = xy[2 * i], qy = xy[2 * i + 1];
            float d = dist(px, py, qx, qy);
            while (acc + d >= step && k < m && d > 0) {
                float t = (step - acc) / d;
                px = px + t * (qx - px);
                py = py + t * (qy - py);
                out[2 * k] = px;
                out[2 * k + 1] = py;
                k++;
                d = dist(px, py, qx, qy);
                acc = 0;
            }
            acc += d;
            px = qx;
            py = qy;
        }
        for (; k < m; k++) {
            out[2 * k] = xy[2 * n - 2];
            out[2 * k + 1] = xy[2 * n - 1];
        }
        return out;
    }

    static float dist(float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    static float segmentDistance(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float len2 = dx * dx + dy * dy;
        float t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        return dist(px, py, ax + t * dx, ay + t * dy);
    }

    private ShapeRecognizer() {}
}
