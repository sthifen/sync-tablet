package dev.synctablet;

import java.util.List;

/**
 * Decide qué agarra el lazo, sin pedir precisión:
 *
 * - Un toque agarra el trazo tocado y los de su misma palabra.
 * - Un contorno agarra lo que tenga dentro al menos un 30 %, o su centro.
 * - Una línea abierta agarra lo que toca. Lo mismo si el contorno no rodeó nada.
 *
 * Java puro (sin Android) para poder probarlo en el PC.
 */
final class LassoPicker {
    /** Hasta este tamaño, el recorrido del lazo cuenta como un toque. */
    private static final float TAP = 12;
    /** Distancia a la que un toque o una línea alcanzan un trazo. */
    private static final float REACH = 14;
    /** Hueco máximo entre dos trazos de una misma palabra. */
    private static final float WORD_GAP = 24;
    private static final float MIN_INSIDE = 0.3f;
    /** Lo que mide de más que esto es una figura, no una letra: no se agrupa. */
    private static final float MAX_LETTER_HEIGHT = 90, MAX_LETTER_WIDTH = 160;

    /** `path`: x, y seguidos. Cada trazo: x, y seguidos. Devuelve cuáles quedan elegidos. */
    static boolean[] pick(float[] path, List<float[]> strokes) {
        boolean[] chosen = new boolean[strokes.size()];
        if (path.length < 2) return chosen;
        float[] box = bounds(path);
        if (Math.hypot(box[2] - box[0], box[3] - box[1]) < TAP) {
            pickWord(path[0], path[1], strokes, chosen);
            return chosen;
        }
        float length = 0;
        for (int i = 2; i + 1 < path.length; i += 2) length += Math.hypot(path[i] - path[i - 2], path[i + 1] - path[i - 1]);
        float gap = (float) Math.hypot(path[0] - path[path.length - 2], path[1] - path[path.length - 1]);
        boolean any = false;
        if (gap < 0.35f * length) {
            for (int i = 0; i < chosen.length; i++) {
                chosen[i] = surrounded(path, strokes.get(i));
                any |= chosen[i];
            }
        }
        if (!any) {
            for (int i = 0; i < chosen.length; i++) chosen[i] = touched(path, strokes.get(i));
        }
        return chosen;
    }

    private static boolean surrounded(float[] path, float[] xy) {
        int n = xy.length / 2, in = 0;
        if (n == 0) return false;
        for (int i = 0; i < n; i++) if (inside(path, xy[2 * i], xy[2 * i + 1])) in++;
        if (in >= MIN_INSIDE * n) return true;
        float[] b = bounds(xy);
        return inside(path, (b[0] + b[2]) / 2, (b[1] + b[3]) / 2);
    }

    private static boolean touched(float[] path, float[] xy) {
        for (int i = 0; i + 3 < path.length; i += 2) {
            for (int k = 0; k + 1 < xy.length; k += 2) {
                if (ShapeRecognizer.segmentDistance(xy[k], xy[k + 1], path[i], path[i + 1], path[i + 2], path[i + 3]) <= REACH) return true;
            }
        }
        return false;
    }

    /** El trazo más cercano al toque y, por vecindad en su renglón, el resto de la palabra. */
    private static void pickWord(float x, float y, List<float[]> strokes, boolean[] chosen) {
        int best = -1;
        float nearest = REACH;
        for (int i = 0; i < chosen.length; i++) {
            float[] xy = strokes.get(i);
            for (int k = 0; k + 1 < xy.length; k += 2) {
                float x1 = k + 3 < xy.length ? xy[k + 2] : xy[k], y1 = k + 3 < xy.length ? xy[k + 3] : xy[k + 1];
                float d = ShapeRecognizer.segmentDistance(x, y, xy[k], xy[k + 1], x1, y1);
                if (d <= nearest) { nearest = d; best = i; }
            }
        }
        if (best < 0) return;
        chosen[best] = true;
        float[][] boxes = new float[chosen.length][];
        for (int i = 0; i < chosen.length; i++) boxes[i] = bounds(strokes.get(i));
        if (!letterSized(boxes[best])) return;
        for (boolean grew = true; grew; ) {
            grew = false;
            for (int i = 0; i < chosen.length; i++) {
                if (chosen[i] || !letterSized(boxes[i])) continue;
                for (int k = 0; k < chosen.length && !chosen[i]; k++) {
                    if (chosen[k] && sameWord(boxes[i], boxes[k])) {
                        chosen[i] = true;
                        grew = true;
                    }
                }
            }
        }
    }

    private static boolean letterSized(float[] box) {
        return box[3] - box[1] <= MAX_LETTER_HEIGHT && box[2] - box[0] <= MAX_LETTER_WIDTH;
    }

    /** ¿Casi se tocan a lo ancho y comparten renglón? */
    private static boolean sameWord(float[] a, float[] b) {
        float gapX = Math.max(a[0], b[0]) - Math.min(a[2], b[2]);
        float overlapY = Math.min(a[3], b[3]) - Math.max(a[1], b[1]);
        float shorter = Math.min(a[3] - a[1], b[3] - b[1]);
        return gapX <= WORD_GAP && overlapY >= -6 && overlapY >= 0.3f * shorter - 6;
    }

    /** Izquierda, arriba, derecha, abajo. */
    private static float[] bounds(float[] xy) {
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        for (int i = 0; i + 1 < xy.length; i += 2) {
            l = Math.min(l, xy[i]); r = Math.max(r, xy[i]);
            t = Math.min(t, xy[i + 1]); b = Math.max(b, xy[i + 1]);
        }
        return new float[] {l, t, r, b};
    }

    static boolean inside(float[] poly, float x, float y) {
        boolean in = false;
        int n = poly.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = poly[2 * i], yi = poly[2 * i + 1], xj = poly[2 * j], yj = poly[2 * j + 1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) in = !in;
        }
        return in;
    }

    private LassoPicker() {}
}
