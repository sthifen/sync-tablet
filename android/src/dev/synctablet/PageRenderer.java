package dev.synctablet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
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
 */
final class PageRenderer {
    static final int TAPE_COLOR = 0xFFE9C46A;

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
            c.drawBitmap(bg, null, new RectF((Ink.W - w) / 2, (Ink.H - h) / 2, (Ink.W + w) / 2, (Ink.H + h) / 2), bitmap);
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
        header.setColor(0xFFF1F5F9);
        c.drawRect(it.x, it.y, it.x + it.w, it.y + ch, header);
        grid.setStyle(Paint.Style.STROKE);
        grid.setColor(0xFF94A3B8);
        grid.setStrokeWidth(1.2f);
        for (int r = 0; r <= rows; r++) c.drawLine(it.x, it.y + r * ch, it.x + it.w, it.y + r * ch, grid);
        for (int k = 0; k <= cols; k++) c.drawLine(it.x + k * cw, it.y, it.x + k * cw, it.y + it.h, grid);
        text.setColor(0xFF111827);
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
                dots.setColor(0xFFEEF2F7);
                c.drawRect(r, dots);
                dots.setColor(0xFFB8C4D6);
            }
            return;
        }
        text.setColor(it.color);
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
        ink.setColor(s.color);
        if (Ink.HIGHLIGHTER.equals(s.tool) || Ink.TAPE.equals(s.tool)) {
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
        if (s.n == 1) {
            ink.setStrokeWidth(Ink.segWidth(s.tool, s.width, s.p(0), 1, 0));
            ink.setAlpha(Ink.segAlpha(s.tool, s.p(0)));
            c.drawPoint(s.x(0), s.y(0), ink);
            ink.setAlpha(255);
            return;
        }
        for (int i = 1; i < s.n; i++) drawSegment(c, s, i);
        ink.setAlpha(255);
    }

    void drawSegment(Canvas c, Ink.Stroke s, int i) {
        float p = (s.p(i - 1) + s.p(i)) / 2;
        ink.setColor(s.color);
        ink.setAlpha(Ink.segAlpha(s.tool, p));
        ink.setStrokeWidth(Ink.segWidth(s.tool, s.width, p, s.x(i) - s.x(i - 1), s.y(i) - s.y(i - 1)));
        c.drawLine(s.x(i - 1), s.y(i - 1), s.x(i), s.y(i), ink);
    }
}
