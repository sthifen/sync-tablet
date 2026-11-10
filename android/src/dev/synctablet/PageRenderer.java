package dev.synctablet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.LruCache;

import java.io.File;
import java.util.Collections;
import java.util.Set;

/**
 * Pinta una página (fondo, plantilla o PDF, imágenes, textos y trazos) en
 * unidades de página. Lo usan la hoja, las miniaturas y la exportación a
 * PDF, así las tres se ven igual. Las fórmulas de los lápices están en
 * Ink.segWidth / segAlpha (las mismas que el PC).
 *
 * La hoja oscura es solo de este objeto: los colores guardados no cambian y
 * la exportación usa otro renderizador, en claro, para no gastar tinta.
 */
final class PageRenderer {
    static final int TAPE_COLOR = 0xFFE9C46A;
    static final int DARK_SHEET = 0xFF16191E;
    /** Invierte el brillo sin voltear los tonos, y deja el blanco en el gris de la hoja. */
    private static final ColorMatrixColorFilter DARK_SCAN = new ColorMatrixColorFilter(new ColorMatrix(new float[] {
            0.3f, -0.6f, -0.6f, 0, 251,
            -0.6f, 0.3f, -0.6f, 0, 251,
            -0.6f, -0.6f, 0.3f, 0, 251,
            0, 0, 0, 1, 0}));

    private final Context context;
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lines = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint margin = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheet = new Paint();
    private final Paint bitmap = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    /** Imágenes ya decodificadas, hasta ~1/8 de la memoria de la app. */
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getAllocationByteCount();
                }
            };

    private boolean dark;

    /** Solo para la pantalla; nunca en lo que se exporta. */
    void setDark(boolean on) {
        dark = on;
        lines.setColor(on ? 0xFF343B47 : 0xFFC9D3E3);
        dots.setColor(on ? 0xFF475062 : 0xFFB8C4D6);
        margin.setColor(on ? 0xFF8A4B4B : 0xFFE28B8B);
        sheet.setColor(on ? DARK_SHEET : Color.WHITE);
    }

    /** Cómo se ve un color de tinta sobre la hoja oscura: lo negro pasa a blanco y lo oscuro se aclara. */
    static int onDark(int c) {
        int r = c >> 16 & 0xFF, g = c >> 8 & 0xFF, b = c & 0xFF;
        int luma = (30 * r + 59 * g + 11 * b) / 100;
        if (luma < 60) return c & 0xFF000000 | 0xF3F4F6;
        if (luma >= 130) return c;
        return c & 0xFF000000 | (r + (255 - r) * 2 / 5) << 16 | (g + (255 - g) * 2 / 5) << 8 | (b + (255 - b) * 2 / 5);
    }

    private int shown(int c) {
        return dark ? onDark(c) : c;
    }

    PageRenderer(Context c) {
        context = c.getApplicationContext();
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        lines.setStyle(Paint.Style.STROKE);
        lines.setColor(0xFFC9D3E3);
        dots.setColor(0xFFB8C4D6);
        margin.setStyle(Paint.Style.STROKE);
        margin.setColor(0xFFE28B8B);
        margin.setStrokeWidth(2.5f);
        sheet.setColor(Color.WHITE);
    }

    /** Imagen guardada (null si aún no está o no se puede leer). */
    Bitmap asset(String id) {
        if (id == null) return null;
        Bitmap b = CACHE.get(id);
        if (b != null) return b;
        File f = Store.get(context).assetFile(id);
        if (!f.isFile()) return null;
        b = BitmapFactory.decodeFile(f.getPath());
        if (b != null) CACHE.put(id, b);
        return b;
    }

    /** Lo que importa de la página: la imagen de fondo si la tiene, o la hoja. */
    RectF contentRect(Ink.Page p) {
        Bitmap bg = asset(p.bgAsset);
        if (bg == null) return new RectF(0, 0, Ink.W, Ink.H);
        float k = Math.min(Ink.W / bg.getWidth(), Ink.H / bg.getHeight());
        float w = bg.getWidth() * k, h = bg.getHeight() * k;
        return new RectF((Ink.W - w) / 2, (Ink.H - h) / 2, (Ink.W + w) / 2, (Ink.H + h) / 2);
    }

    /**
     * Página entera. `hidden`: trazos e imágenes que no se pintan (los que se
     * están moviendo); `revealed`: cintas destapadas.
     */
    void drawPage(Canvas c, Ink.Page p, float lineScale, Set<String> hidden, Set<String> revealed) {
        if (hidden == null) hidden = Collections.emptySet();
        c.drawRect(0, 0, Ink.W, Ink.H, sheet);
        Bitmap bg = asset(p.bgAsset);
        if (bg != null) {
            float k = Math.min(Ink.W / bg.getWidth(), Ink.H / bg.getHeight());
            float w = bg.getWidth() * k, h = bg.getHeight() * k;
            // Un PDF blanco bajo tinta blanca no dejaría ver nada.
            bitmap.setColorFilter(dark ? DARK_SCAN : null);
            c.drawBitmap(bg, null, new RectF((Ink.W - w) / 2, (Ink.H - h) / 2, (Ink.W + w) / 2, (Ink.H + h) / 2), bitmap);
            bitmap.setColorFilter(null);
        } else if (p.bgAsset == null) {
            drawBackground(c, p.background, lineScale);
        }
        for (Ink.Item it : p.items) if (!hidden.contains(it.id)) drawItem(c, it);
        for (Ink.Stroke s : p.strokes) if (!hidden.contains(s.id)) drawStroke(c, s, revealed != null && revealed.contains(s.id));
    }

    /** Plantillas: las mismas medidas que el PC (svg.rs). */
    void drawBackground(Canvas c, String bg, float lineScale) {
        if (bg == null || Ink.PLAIN.equals(bg)) return;
        lines.setStrokeWidth(1 / Math.max(lineScale, 0.01f));
        switch (bg) {
            case Ink.LINES:
            case Ink.GRID:
            case Ink.CORNELL:
                for (float y = 80; y < Ink.H; y += 40) c.drawLine(0, y, Ink.W, y, lines);
                if (Ink.GRID.equals(bg)) for (float x = 40; x < Ink.W; x += 40) c.drawLine(x, 0, x, Ink.H, lines);
                if (Ink.CORNELL.equals(bg)) {
                    float bottom = Ink.H - 280;
                    c.drawLine(250, 40, 250, bottom, margin);
                    c.drawLine(0, bottom, Ink.W, bottom, margin);
                }
                break;
            case Ink.DOTS:
                for (float y = 40; y < Ink.H; y += 40) for (float x = 40; x < Ink.W; x += 40) c.drawCircle(x, y, 2, dots);
                break;
            case Ink.MUSIC:
                for (float top = 120; top + 48 < Ink.H - 40; top += 110) {
                    for (int k = 0; k < 5; k++) c.drawLine(60, top + 12 * k, Ink.W - 60, top + 12 * k, lines);
                }
                break;
            case Ink.TODO:
                for (float y = 100; y < Ink.H - 40; y += 64) {
                    c.drawLine(90, y, Ink.W - 40, y, lines);
                    c.drawRoundRect(new RectF(40, y - 32, 66, y - 6), 5, 5, lines);
                }
                break;
            case Ink.HALF:
                // Partida a la mitad: dos columnas con rayas y una línea al centro.
                for (float y = 80; y < Ink.H; y += 40) {
                    c.drawLine(30, y, Ink.W / 2 - 20, y, lines);
                    c.drawLine(Ink.W / 2 + 20, y, Ink.W - 30, y, lines);
                }
                c.drawLine(Ink.W / 2, 40, Ink.W / 2, Ink.H - 40, margin);
                break;
            case Ink.HALF_GRID:
                for (float y = 40; y < Ink.H; y += 40) c.drawLine(0, y, Ink.W, y, lines);
                for (float x = 40; x < Ink.W; x += 40) c.drawLine(x, 0, x, Ink.H, lines);
                c.drawLine(Ink.W / 2, 0, Ink.W / 2, Ink.H, margin);
                break;
            case Ink.HALF_H:
                for (float y = 80; y < Ink.H; y += 40) if (Math.abs(y - Ink.H / 2) > 30) c.drawLine(0, y, Ink.W, y, lines);
                c.drawLine(30, Ink.H / 2, Ink.W - 30, Ink.H / 2, margin);
                break;
            case Ink.MM: {
                float base = lines.getStrokeWidth();
                for (int i = 0; i * 10 < Ink.W; i++) {
                    lines.setStrokeWidth(base * (i % 10 == 0 ? 2.2f : i % 5 == 0 ? 1.4f : 0.6f));
                    c.drawLine(i * 10, 0, i * 10, Ink.H, lines);
                }
                for (int i = 0; i * 10 < Ink.H; i++) {
                    lines.setStrokeWidth(base * (i % 10 == 0 ? 2.2f : i % 5 == 0 ? 1.4f : 0.6f));
                    c.drawLine(0, i * 10, Ink.W, i * 10, lines);
                }
                lines.setStrokeWidth(base);
                break;
            }
            case Ink.ISO: {
                float step = 40, dx = (float) (Ink.H / Math.tan(Math.toRadians(30)));
                for (float x = -dx; x < Ink.W + dx; x += step / (float) Math.sin(Math.toRadians(60))) {
                    c.drawLine(x, 0, x + dx, Ink.H, lines);
                    c.drawLine(x, Ink.H, x + dx, 0, lines);
                }
                for (float x = 0; x < Ink.W; x += step / (float) Math.sin(Math.toRadians(60)) / 2) c.drawLine(x, 0, x, Ink.H, dots);
                break;
            }
            case Ink.ENGINEERING: {
                int old = lines.getColor();
                lines.setColor(0x5522A06B);
                for (float y = 140; y < Ink.H - 40; y += 25) c.drawLine(40, y, Ink.W - 40, y, lines);
                for (float x = 40; x <= Ink.W - 40; x += 25) c.drawLine(x, 140, x, Ink.H - 50, lines);
                lines.setColor(old);
                c.drawRect(40, 40, Ink.W - 40, 120, lines);
                c.drawLine(Ink.W * 0.55f, 40, Ink.W * 0.55f, 120, lines);
                c.drawLine(Ink.W * 0.8f, 40, Ink.W * 0.8f, 120, lines);
                break;
            }
            case Ink.HEX: {
                float r = 30, h = r * (float) Math.sqrt(3);
                android.graphics.Path hex = new android.graphics.Path();
                int row = 0;
                for (float y = 0; y < Ink.H + h; y += h / 2, row++) {
                    for (float x = (row % 2) * r * 1.5f; x < Ink.W + 2 * r; x += r * 3) {
                        hex.reset();
                        for (int k = 0; k < 6; k++) {
                            double a = Math.PI / 3 * k;
                            float px = x + r * (float) Math.cos(a), py = y + r * (float) Math.sin(a);
                            if (k == 0) hex.moveTo(px, py);
                            else hex.lineTo(px, py);
                        }
                        hex.close();
                        c.drawPath(hex, lines);
                    }
                }
                break;
            }
            case Ink.WIDE:
                for (float y = 100; y < Ink.H; y += 56) c.drawLine(0, y, Ink.W, y, lines);
                c.drawLine(110, 0, 110, Ink.H, margin);
                break;
            case Ink.WEEK: {
                String[] days = {"Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo"};
                float top = 60, rowH = (Ink.H - top - 40) / 7;
                android.graphics.Paint label = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                label.setColor(lines.getColor() | 0xFF000000);
                label.setAlpha(150);
                label.setTextSize(22);
                for (int i = 0; i <= 7; i++) c.drawLine(40, top + i * rowH, Ink.W - 40, top + i * rowH, i == 0 || i == 7 ? margin : lines);
                for (int i = 0; i < 7; i++) c.drawText(days[i], 50, top + i * rowH + 30, label);
                break;
            }
            case Ink.STORY:
                for (int r = 0; r < 3; r++) for (int k = 0; k < 2; k++) {
                    float x = 60 + k * 460, y = 70 + r * 440;
                    c.drawRoundRect(new RectF(x, y, x + 420, y + 250), 10, 10, margin);
                    for (int l = 0; l < 3; l++) c.drawLine(x, y + 300 + l * 40, x + 420, y + 300 + l * 40, lines);
                }
                break;
            case Ink.SEMILOG:
                drawLogPaper(c, false);
                break;
            case Ink.LOGLOG:
                drawLogPaper(c, true);
                break;
            case Ink.SMITH:
                drawSmith(c);
                break;
            case Ink.POLAR:
                drawPolar(c);
                break;
            case Ink.HANDWRITING:
                for (float y = 110; y + 60 < Ink.H; y += 100) {
                    c.drawLine(40, y, Ink.W - 40, y, lines);
                    c.drawLine(40, y + 60, Ink.W - 40, y + 60, margin);
                    for (float x = 40; x < Ink.W - 40; x += 24) c.drawLine(x, y + 30, x + 10, y + 30, lines);
                }
                break;
        }
    }

    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Paint label(float size) {
        // Más oscuras que las líneas: se tienen que poder leer.
        label.setColor(dark ? 0xFF8B95A5 : 0xFF6B7A90);
        label.setTextSize(size);
        label.setTextAlign(Paint.Align.CENTER);
        return label;
    }

    /** Papel logarítmico: el eje y en 4 décadas; con `both`, el x en 3 décadas. */
    private void drawLogPaper(Canvas c, boolean both) {
        float left = 70, right = Ink.W - 40, top = 60, bottom = Ink.H - 60;
        float base = lines.getStrokeWidth();
        int yDecades = 4, xDecades = 3;
        float dh = (bottom - top) / yDecades;
        Paint t = label(15);
        for (int d = 0; d < yDecades; d++) {
            float y0 = bottom - d * dh;
            for (int k = 1; k < 10; k++) {
                float y = y0 - dh * (float) Math.log10(k);
                lines.setStrokeWidth(base * (k == 1 ? 2.2f : k == 5 ? 1.3f : 0.8f));
                c.drawLine(left, y, right, y, k == 1 ? margin : lines);
                if (k == 1 || k == 2 || k == 3 || k == 5) c.drawText(String.valueOf(k), left - 18, y + 5, t);
                if (k < 5) {
                    // Medios: 1,5; 2,5; 3,5; 4,5 (más finos).
                    float yh = y0 - dh * (float) Math.log10(k + 0.5);
                    lines.setStrokeWidth(base * 0.45f);
                    c.drawLine(left, yh, right, yh, lines);
                }
            }
        }
        lines.setStrokeWidth(base * 2.2f);
        c.drawLine(left, top, right, top, margin);
        c.drawText("1", left - 18, top + 5, t);
        if (both) {
            float dw = (right - left) / xDecades;
            for (int d = 0; d < xDecades; d++) {
                float x0 = left + d * dw;
                for (int k = 1; k < 10; k++) {
                    float x = x0 + dw * (float) Math.log10(k);
                    lines.setStrokeWidth(base * (k == 1 ? 2.2f : k == 5 ? 1.3f : 0.8f));
                    c.drawLine(x, top, x, bottom, k == 1 ? margin : lines);
                    if (k == 1 || k == 2 || k == 3 || k == 5) c.drawText(String.valueOf(k), x, bottom + 26, t);
                    if (k < 5) {
                        float xh = x0 + dw * (float) Math.log10(k + 0.5);
                        lines.setStrokeWidth(base * 0.45f);
                        c.drawLine(xh, top, xh, bottom, lines);
                    }
                }
            }
            c.drawLine(right, top, right, bottom, margin);
            c.drawText("1", right, bottom + 26, t);
        } else {
            // x lineal: 10 divisiones grandes de 5 cada una.
            int n = 50;
            for (int i = 0; i <= n; i++) {
                float x = left + (right - left) * i / n;
                lines.setStrokeWidth(base * (i % 5 == 0 ? 1.6f : 0.6f));
                c.drawLine(x, top, x, bottom, i % 10 == 0 ? margin : lines);
                if (i % 5 == 0) c.drawText(String.valueOf(i / 5), x, bottom + 26, t);
            }
        }
        lines.setStrokeWidth(base);
        c.drawLine(left, top, left, bottom, margin);
        if (!both) c.drawLine(right, top, right, bottom, margin);
    }

    private static final float[] SMITH_R = {0, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1, 1.2f, 1.4f, 1.6f, 1.8f, 2, 3, 4, 5, 10, 20};
    private static final float[] SMITH_X = {0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1, 1.2f, 1.4f, 1.6f, 1.8f, 2, 3, 4, 5, 10, 20};

    private static boolean smithMajor(float v) {
        return v == 0.2f || v == 0.5f || v == 1 || v == 2 || v == 5;
    }

    private static String smithLabel(float v) {
        return v == (int) v ? String.valueOf((int) v) : String.valueOf(v).replace('.', ',');
    }

    /**
     * Carta de Smith de impedancia normalizada: círculos de resistencia,
     * arcos de reactancia, escala de ángulos de Γ y de longitudes de onda
     * hacia el generador. Debajo, rayas para los cálculos.
     */
    private void drawSmith(Canvas c) {
        float cx = Ink.W / 2, cy = 500, R = 400;
        float base = lines.getStrokeWidth();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addCircle(cx, cy, R, android.graphics.Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        for (float r : SMITH_R) {
            if (r == 0) continue;
            lines.setStrokeWidth(base * (smithMajor(r) ? 1.5f : 0.7f));
            float u = r / (1 + r), rad = 1 / (1 + r);
            c.drawCircle(cx + R * u, cy, R * rad, smithMajor(r) && r == 1 ? margin : lines);
        }
        for (float x : SMITH_X) {
            lines.setStrokeWidth(base * (smithMajor(x) ? 1.5f : 0.7f));
            float rad = R / x;
            c.drawCircle(cx + R, cy - rad, rad, x == 1 ? margin : lines);
            c.drawCircle(cx + R, cy + rad, rad, x == 1 ? margin : lines);
        }
        c.restore();
        lines.setStrokeWidth(base * 1.6f);
        c.drawLine(cx - R, cy, cx + R, cy, margin);
        c.drawCircle(cx, cy, R, margin);
        // Escalas exteriores: ángulo de Γ (dentro) y λ hacia el generador (fuera).
        float r1 = R + 8, r2 = R + 46;
        lines.setStrokeWidth(base);
        c.drawCircle(cx, cy, r1 + 22, lines);
        c.drawCircle(cx, cy, r2, lines);
        Paint t = label(13);
        for (int deg = -180; deg < 180; deg += 2) {
            double a = Math.toRadians(deg);
            float co = (float) Math.cos(a), si = (float) -Math.sin(a);
            boolean big = deg % 10 == 0;
            c.drawLine(cx + co * R, cy + si * R, cx + co * (R + (big ? 10 : 5)), cy + si * (R + (big ? 10 : 5)), lines);
            if (deg % 30 == 0) c.drawText(deg + "°", cx + co * (r1 + 12), cy + si * (r1 + 12) + 5, t);
        }
        for (int k = 0; k < 250; k++) {
            float wl = k * 0.002f;
            // λ = (180° − θ) / 720°: 0 en el corto (izquierda), creciendo en sentido horario.
            double a = Math.toRadians(180 - wl * 720);
            float co = (float) Math.cos(a), si = (float) -Math.sin(a);
            boolean big = k % 5 == 0;
            c.drawLine(cx + co * r2, cy + si * r2, cx + co * (r2 - (big ? 10 : 5)), cy + si * (r2 - (big ? 10 : 5)), lines);
            if (k % 25 == 0) c.drawText(String.format(java.util.Locale.ROOT, "%.2f", wl).replace('.', ','), cx + co * (r2 + 16), cy + si * (r2 + 16) + 5, t);
        }
        // Valores de r sobre el eje real y de x en el borde.
        Paint small = label(12);
        for (float r : SMITH_R) {
            if (!smithMajor(r) && r != 0.1f && r != 0.3f && r != 3 && r != 10) continue;
            float u = (r - 1) / (r + 1);
            c.save();
            c.rotate(-90, cx + R * u, cy);
            c.drawText(smithLabel(r), cx + R * u - 14, cy - 4, small);
            c.restore();
        }
        for (float x : SMITH_X) {
            if (!smithMajor(x) && x != 10) continue;
            // Γ en z = j·x: (x² − 1 + 2jx) / (x² + 1).
            float gu = (x * x - 1) / (x * x + 1), gv = 2 * x / (x * x + 1);
            float px = cx + (R - 16) * gu, py = cy - (R - 16) * gv, qy = cy + (R - 16) * gv;
            c.drawText("+j" + smithLabel(x), px, py + 4, small);
            c.drawText("−j" + smithLabel(x), px, qy + 8, small);
        }
        c.drawCircle(cx, cy, 4, dots);
        Paint title = label(20);
        title.setTextAlign(Paint.Align.LEFT);
        c.drawText("Carta de Smith · z = Z / Z₀", 40, 40, title);
        title.setTextAlign(Paint.Align.RIGHT);
        title.setTextSize(15);
        c.drawText("λ hacia el generador →", Ink.W - 40, 40, title);
        for (float y = 1010; y < Ink.H - 30; y += 40) c.drawLine(40, y, Ink.W - 40, y, lines);
        lines.setStrokeWidth(base);
    }

    /** Polar: círculos cada décima y radios cada 15°, para diagramas de radiación y fasores. */
    private void drawPolar(Canvas c) {
        float cx = Ink.W / 2, cy = 520, R = 440;
        float base = lines.getStrokeWidth();
        for (int i = 1; i <= 10; i++) {
            lines.setStrokeWidth(base * (i % 5 == 0 ? 1.5f : 0.8f));
            c.drawCircle(cx, cy, R * i / 10f, i == 10 ? margin : lines);
        }
        Paint t = label(14);
        for (int deg = 0; deg < 360; deg += 5) {
            double a = Math.toRadians(deg);
            float co = (float) Math.cos(a), si = (float) -Math.sin(a);
            boolean big = deg % 15 == 0;
            lines.setStrokeWidth(base * (deg % 90 == 0 ? 1.5f : 0.7f));
            if (big) c.drawLine(cx + co * R * 0.1f, cy + si * R * 0.1f, cx + co * R, cy + si * R, deg % 90 == 0 ? margin : lines);
            else c.drawLine(cx + co * (R - 10), cy + si * (R - 10), cx + co * R, cy + si * R, lines);
            if (deg % 30 == 0) c.drawText(deg + "°", cx + co * (R + 24), cy + si * (R + 24) + 5, t);
        }
        lines.setStrokeWidth(base);
        for (float y = 1040; y < Ink.H - 30; y += 40) c.drawLine(40, y, Ink.W - 40, y, lines);
    }

    private final Paint note = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint noteText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private static final float NOTE_PAD = 16;

    private StaticLayout noteLayout(Ink.Note n) {
        noteText.setTextSize(24);
        noteText.setColor(0xFF1F2937);
        String s = n.text == null ? "" : n.text;
        return StaticLayout.Builder.obtain(s, 0, s.length(), noteText, Math.max(1, (int) (n.w - 2 * NOTE_PAD))).setLineSpacing(0, 1.1f).build();
    }

    float noteHeight(Ink.Note n) {
        return Math.max(84, noteLayout(n).getHeight() + 2 * NOTE_PAD);
    }

    /** Del color de la nota, más oscuro: su flecha. */
    private static int darker(int c) {
        return 0xFF000000 | (((c >> 16 & 0xFF) * 5 / 8) << 16) | (((c >> 8 & 0xFF) * 5 / 8) << 8) | ((c & 0xFF) * 5 / 8);
    }

    /** Una nota. `active`: con su asa para sacar la flecha. */
    void drawNote(Canvas c, Ink.Note n, boolean active) {
        float h = noteHeight(n);
        if (n.arrow) {
            float cx = n.x + n.w / 2, cy = n.y + h / 2;
            note.setStyle(Paint.Style.STROKE);
            note.setStrokeWidth(5);
            note.setStrokeCap(Paint.Cap.ROUND);
            note.setColor(darker(n.color));
            float[] a = ShapeTools.arrow(cx, cy, n.ax, n.ay, false);
            for (int i = 2; i + 1 < a.length; i += 2) c.drawLine(a[i - 2], a[i - 1], a[i], a[i + 1], note);
        }
        note.setStyle(Paint.Style.FILL);
        note.setColor(0x33000000);
        c.drawRoundRect(n.x + 3, n.y + 5, n.x + n.w + 3, n.y + h + 5, 14, 14, note);
        note.setColor(n.color);
        c.drawRoundRect(n.x, n.y, n.x + n.w, n.y + h, 14, 14, note);
        c.save();
        c.translate(n.x + NOTE_PAD, n.y + NOTE_PAD);
        noteLayout(n).draw(c);
        c.restore();
        if (active) {
            note.setColor(darker(n.color));
            c.drawCircle(n.x + n.w, n.y + h, 11, note);
            if (n.arrow) c.drawCircle(n.ax, n.ay, 11, note);
        }
    }

    /** Alto de un elemento (para seleccionarlo). */
    float itemHeight(Ink.Item it) {
        return it.isImage() || it.isTable() ? it.h : textHeight(it);
    }

    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint header = new Paint();

    /** Hoja de cálculo: rejilla, primera fila resaltada y los valores calculados. */
    private void drawTable(Canvas c, Ink.Item it) {
        int rows = Math.max(1, it.rows), cols = Math.max(1, it.cols);
        float cw = it.w / cols, ch = it.h / rows;
        header.setColor(dark ? 0xFF262B33 : 0xFFF1F5F9);
        c.drawRect(it.x, it.y, it.x + it.w, it.y + ch, header);
        grid.setStyle(Paint.Style.STROKE);
        grid.setColor(dark ? 0xFF566072 : 0xFF94A3B8);
        grid.setStrokeWidth(1.2f);
        for (int r = 0; r <= rows; r++) c.drawLine(it.x, it.y + r * ch, it.x + it.w, it.y + r * ch, grid);
        for (int k = 0; k <= cols; k++) c.drawLine(it.x + k * cw, it.y, it.x + k * cw, it.y + it.h, grid);
        text.setColor(shown(0xFF111827));
        text.setTextSize(Math.min(32, Math.max(10, ch * 0.5f)));
        for (int r = 0; r < rows; r++) {
            text.setFakeBoldText(r == 0);
            for (int k = 0; k < cols; k++) {
                int i = r * cols + k;
                if (i >= it.values.length || it.values[i] == null || it.values[i].isEmpty()) continue;
                c.save();
                c.clipRect(it.x + k * cw, it.y + r * ch, it.x + (k + 1) * cw, it.y + (r + 1) * ch);
                c.drawText(it.values[i], it.x + k * cw + 6, it.y + r * ch + ch * 0.68f, text);
                c.restore();
            }
        }
        text.setFakeBoldText(false);
    }

    void drawItem(Canvas c, Ink.Item it) {
        if (it.isTable()) {
            drawTable(c, it);
            return;
        }
        if (it.isImage()) {
            Bitmap b = asset(it.asset);
            RectF r = new RectF(it.x, it.y, it.x + it.w, it.y + it.h);
            if (b != null) c.drawBitmap(b, null, r, bitmap);
            else {
                int was = dots.getColor();
                dots.setColor(dark ? 0xFF262B33 : 0xFFEEF2F7);
                c.drawRect(r, dots);
                dots.setColor(was);
            }
            return;
        }
        text.setColor(shown(it.color));
        text.setTextSize(it.size);
        String s = it.text == null ? "" : it.text;
        StaticLayout layout = StaticLayout.Builder.obtain(s, 0, s.length(), text, Math.max(1, (int) it.w))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0, 1.15f).build();
        c.save();
        c.translate(it.x, it.y);
        layout.draw(c);
        c.restore();
    }

    /** Alto que ocupa un cuadro de texto (para seleccionarlo). */
    float textHeight(Ink.Item it) {
        text.setTextSize(it.size);
        String s = it.text == null ? "" : it.text;
        return StaticLayout.Builder.obtain(s, 0, s.length(), text, Math.max(1, (int) it.w))
                .setLineSpacing(0, 1.15f).build().getHeight();
    }

    void drawStroke(Canvas c, Ink.Stroke s, boolean revealed) {
        if (s.n == 0) return;
        ink.setColor(shown(s.color));
        if (Ink.HIGHLIGHTER.equals(s.tool) || Ink.TAPE.equals(s.tool)) {
            ink.setColor(s.color);
            boolean tape = Ink.TAPE.equals(s.tool);
            ink.setAlpha(tape ? (revealed ? 60 : 255) : 90);
            ink.setStrokeCap(tape ? Paint.Cap.SQUARE : Paint.Cap.ROUND);
            ink.setStrokeWidth(s.width);
            path.reset();
            path.moveTo(s.x(0), s.y(0));
            for (int i = 1; i < s.n; i++) path.lineTo(s.x(i), s.y(i));
            if (s.n == 1) path.lineTo(s.x(0) + 0.01f, s.y(0));
            c.drawPath(path, ink);
            ink.setAlpha(255);
            ink.setStrokeCap(Paint.Cap.ROUND);
            return;
        }
        if (Ink.patterned(s.tool) && s.n > 1) {
            // Punteado: puntos redondos separados; rayado: rayas cortas.
            boolean dots = Ink.DOTTED.equals(s.tool);
            float w = s.width;
            ink.setStrokeWidth(dots ? w * 1.3f : w);
            ink.setPathEffect(new android.graphics.DashPathEffect(dots ? new float[] {0.01f, w * 2.6f} : new float[] {w * 3.5f, w * 2.8f}, 0));
            path.reset();
            boolean up = true;
            for (int i = 0; i < s.n; i++) {
                if (s.p(i) < 0) up = true;
                if (up) path.moveTo(s.x(i), s.y(i));
                else path.lineTo(s.x(i), s.y(i));
                up = false;
            }
            c.drawPath(path, ink);
            ink.setPathEffect(null);
            return;
        }
        if (s.n == 1) {
            ink.setStrokeWidth(Ink.segWidth(s.tool, s.width, s.p(0), 1, 0));
            ink.setAlpha(Ink.segAlpha(s.tool, s.p(0)));
            c.drawPoint(s.x(0), s.y(0), ink);
            ink.setAlpha(255);
            return;
        }
        int alpha = s.color >>> 24;
        if (alpha > 0 && alpha < 255) {
            // Lápiz propio con opacidad: el trazo entero en una capa, así las uniones no se oscurecen.
            int keep = c.saveLayerAlpha(null, alpha);
            for (int i = 1; i < s.n; i++) drawSegment(c, s, i);
            c.restoreToCount(keep);
        } else {
            for (int i = 1; i < s.n; i++) drawSegment(c, s, i);
        }
        ink.setAlpha(255);
    }

    void drawSegment(Canvas c, Ink.Stroke s, int i) {
        // Presión negativa: aquí se levantó el lápiz.
        if (s.p(i) < 0) return;
        float p = (Math.max(s.p(i - 1), s.p(i)) + s.p(i)) / 2;
        ink.setColor(shown(s.color));
        ink.setAlpha(Ink.segAlpha(s.tool, p));
        ink.setStrokeWidth(Ink.segWidth(s.tool, s.width, p, s.x(i) - s.x(i - 1), s.y(i) - s.y(i - 1)));
        c.drawLine(s.x(i - 1), s.y(i - 1), s.x(i), s.y(i), ink);
    }
}
