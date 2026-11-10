package dev.synctablet;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Presentaciones al estilo de Canva: plantillas con estilo, diapositivas 16:9
 * con textos, imágenes, figuras, stickers y trazos a mano. Se presentan a
 * pantalla completa y se exportan a PowerPoint o PDF.
 */
public final class DeckActivity extends Activity {
    static final float SW = 1600, SH = 900;
    private static final int PICK_IMAGE = 81;

    // ---------------------------------------------------------------- modelo

    static final class El {
        String kind; // text, image, shape, sticker, ink
        float x, y, w, h;
        String text = "", shape = "rect", asset;
        float size = 48, stroke = 6;
        int color = 0xFF111827, fill = 0;
        boolean bold;
        int align; // 0 izquierda, 1 centro, 2 derecha
        float[] pts;

        El(String kind, float x, float y, float w, float h) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        JSONObject json() throws JSONException {
            JSONObject o = new JSONObject().put("k", kind).put("x", x).put("y", y).put("w", w).put("h", h).put("text", text)
                    .put("shape", shape).put("size", size).put("stroke", stroke).put("color", color).put("fill", fill).put("bold", bold).put("align", align);
            if (asset != null) o.put("asset", asset);
            if (pts != null) {
                JSONArray a = new JSONArray();
                for (float f : pts) a.put(Math.round(f * 10) / 10.0);
                o.put("pts", a);
            }
            return o;
        }

        static El from(JSONObject o) {
            El e = new El(o.optString("k", "text"), (float) o.optDouble("x"), (float) o.optDouble("y"), (float) o.optDouble("w"), (float) o.optDouble("h"));
            e.text = o.optString("text");
            e.shape = o.optString("shape", "rect");
            e.size = (float) o.optDouble("size", 48);
            e.stroke = (float) o.optDouble("stroke", 6);
            e.color = o.optInt("color", 0xFF111827);
            e.fill = o.optInt("fill", 0);
            e.bold = o.optBoolean("bold");
            e.align = o.optInt("align");
            e.asset = o.optString("asset", null);
            JSONArray a = o.optJSONArray("pts");
            if (a != null) {
                e.pts = new float[a.length()];
                for (int i = 0; i < a.length(); i++) e.pts[i] = (float) a.optDouble(i);
            }
            return e;
        }

        El copy() {
            try {
                El c = from(json());
                c.x += 30;
                c.y += 30;
                if (c.pts != null) for (int i = 0; i < c.pts.length; i++) c.pts[i] += 30;
                return c;
            } catch (JSONException e) {
                return this;
            }
        }
    }

    static final class Slide {
        final ArrayList<El> els = new ArrayList<>();
    }

    /** Lo que Inicio necesita saber sin abrirla. */
    static final class Info {
        String id, title, folder = "";
        long updated;
        int slides, accent, theme;
        Slide first;

        /** La portada en pequeño, para Inicio. */
        View cover(Context c) {
            return new View(c) {
                @Override
                protected void onDraw(Canvas cv) {
                    if (first == null) return;
                    // Entera, centrada sobre el color del estilo.
                    cv.drawColor(THEMES[theme].bg2);
                    float k = Math.min(getWidth() / SW, getHeight() / SH);
                    cv.save();
                    cv.translate((getWidth() - SW * k) / 2, (getHeight() - SH * k) / 2);
                    cv.scale(k, k);
                    drawSlide(cv, first, THEMES[theme], getContext(), null);
                    cv.restore();
                }
            };
        }
    }

    /** Un estilo de plantilla. deco: 0 nada, 1 círculos, 2 franja, 3 cuadrícula, 4 ondas, 5 marco. */
    static final class Theme {
        final String name;
        final int bg1, bg2, accent, title, body, deco;
        final boolean serif;

        Theme(String name, int bg1, int bg2, int accent, int title, int body, int deco, boolean serif) {
            this.name = name;
            this.bg1 = bg1;
            this.bg2 = bg2;
            this.accent = accent;
            this.title = title;
            this.body = body;
            this.deco = deco;
            this.serif = serif;
        }
    }

    static final Theme[] THEMES = {
        new Theme("Aurora", 0xFF0F766E, 0xFF312E81, 0xFF5EEAD4, 0xFFFFFFFF, 0xFFE0F2F1, 4, false),
        new Theme("Minimal", 0xFFFFFFFF, 0xFFF5F5F4, 0xFF0F766E, 0xFF111827, 0xFF374151, 2, false),
        new Theme("Noche", 0xFF0B1220, 0xFF1E293B, 0xFFFACC15, 0xFFFFFFFF, 0xFFCBD5E1, 1, false),
        new Theme("Coral", 0xFFFFF1EB, 0xFFFFE4D6, 0xFFF97316, 0xFF7C2D12, 0xFF9A3412, 1, true),
        new Theme("Ingeniería", 0xFFF0F7FF, 0xFFE0EEFF, 0xFF1D4ED8, 0xFF1E3A8A, 0xFF1E40AF, 3, false),
        new Theme("Pizarra", 0xFF1F3A2E, 0xFF14532D, 0xFFFDE68A, 0xFFF8FAFC, 0xFFE2E8F0, 5, false),
        new Theme("Pastel", 0xFFF5F3FF, 0xFFFCE7F3, 0xFF8B5CF6, 0xFF4C1D95, 0xFF6D28D9, 1, false),
        new Theme("Ejecutivo", 0xFFFFFFFF, 0xFFFFFFFF, 0xFF1F2937, 0xFF111827, 0xFF4B5563, 2, true),
        new Theme("Atardecer", 0xFFF97316, 0xFFDB2777, 0xFFFFF7ED, 0xFFFFFFFF, 0xFFFFEDD5, 4, false),
        new Theme("Océano", 0xFF0369A1, 0xFF0E7490, 0xFFBAE6FD, 0xFFFFFFFF, 0xFFE0F2FE, 1, false),
    };

    // ---------------------------------------------------------------- archivos

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "decks");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static ArrayList<Info> list(Context c) {
        ArrayList<Info> out = new ArrayList<>();
        File[] files = dir(c).listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return out;
        for (File f : files) {
            try {
                JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                Info i = new Info();
                i.id = o.getString("id");
                i.title = o.optString("title", "Presentación");
                i.folder = o.optString("folder");
                i.updated = o.optLong("updated");
                i.slides = o.optJSONArray("slides") == null ? 0 : o.optJSONArray("slides").length();
                i.theme = Math.max(0, Math.min(THEMES.length - 1, o.optInt("theme")));
                i.accent = THEMES[i.theme].bg1;
                JSONArray sl = o.optJSONArray("slides");
                if (sl != null && sl.length() > 0) {
                    i.first = new Slide();
                    JSONArray els = sl.getJSONObject(0).optJSONArray("els");
                    if (els != null) for (int k = 0; k < els.length(); k++) i.first.els.add(El.from(els.getJSONObject(k)));
                }
                if ((i.accent & 0xFFFFFF) == 0xFFFFFF) i.accent = THEMES[o.optInt("theme")].accent;
                out.add(i);
            } catch (IOException | JSONException e) {
                android.util.Log.w("SyncTablet", "presentación " + f, e);
            }
        }
        out.sort((a, b) -> Long.compare(b.updated, a.updated));
        return out;
    }

    static void delete(Context c, String id) {
        new File(dir(c), id + ".json").delete();
        File[] imgs = dir(c).listFiles((d, n) -> n.startsWith(id + "-"));
        if (imgs != null) for (File f : imgs) f.delete();
    }

    static void setFolder(Context c, String id, String folder) {
        File f = new File(dir(c), id + ".json");
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            o.put("folder", folder);
            write(f, o.toString());
        } catch (IOException | JSONException e) {
            android.util.Log.w("SyncTablet", "mover presentación", e);
        }
    }

    /** Una diapositiva nueva al final con la imagen (una gráfica de la hoja de cálculo, por ejemplo). */
    static boolean addImageSlide(Context c, String id, Bitmap img, String heading) {
        File f = new File(dir(c), id + ".json");
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            Theme th = THEMES[Math.max(0, Math.min(THEMES.length - 1, o.optInt("theme")))];
            String asset = id + "-" + Ink.newId() + ".jpg";
            try (FileOutputStream out = new FileOutputStream(new File(dir(c), asset))) {
                img.compress(Bitmap.CompressFormat.JPEG, 92, out);
            }
            Slide s = new Slide();
            s.els.add(text(th, heading, 110, 50, 1380, 110, 64, true, 0));
            float w = 1300, h = w * img.getHeight() / img.getWidth();
            if (h > 660) {
                h = 660;
                w = h * img.getWidth() / img.getHeight();
            }
            El e = new El("image", (SW - w) / 2, 190 + (680 - h) / 2, w, h);
            e.asset = asset;
            s.els.add(e);
            JSONArray els = new JSONArray();
            for (El x : s.els) els.put(x.json());
            o.getJSONArray("slides").put(new JSONObject().put("els", els));
            o.put("updated", System.currentTimeMillis());
            write(f, o.toString());
            return true;
        } catch (IOException | JSONException e) {
            return false;
        }
    }

    private static void write(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("no se pudo guardar");
    }

    /** Elegir plantilla y crear la presentación. */
    static void chooseTemplate(Activity a, OptionDock dock, String folder) {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < THEMES.length; i++) {
            int theme = i;
            tiles.add(new OptionDock.Tile(new ThemeSample(a, theme), THEMES[i].name, false, () -> {
                Intent in = new Intent(a, DeckActivity.class).putExtra("theme", theme);
                if (folder != null) in.putExtra("folder", folder);
                a.startActivity(in);
            }));
        }
        dock.tiles("Presentación nueva: elige un estilo", tiles);
    }

    /** Muestra de un estilo con su portada. */
    private static final class ThemeSample extends View {
        private final Slide slide;
        private final int theme;

        ThemeSample(Context c, int theme) {
            super(c);
            this.theme = theme;
            slide = titleSlide(THEMES[theme], "Título", "Subtítulo");
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = w * SH / SW, top = (getHeight() - h) / 2;
            c.save();
            c.translate(0, top);
            c.clipRect(0, 0, w, h);
            c.scale(w / SW, w / SW);
            drawSlide(c, slide, THEMES[theme], null, null);
            c.restore();
        }
    }

    // ---------------------------------------------------------------- plantillas

    private static El text(Theme t, String s, float x, float y, float w, float h, float size, boolean title, int align) {
        El e = new El("text", x, y, w, h);
        e.text = s;
        e.size = size;
        e.bold = title;
        e.color = title ? t.title : t.body;
        e.align = align;
        return e;
    }

    static Slide titleSlide(Theme t, String title, String sub) {
        Slide s = new Slide();
        s.els.add(text(t, title, 140, 300, 1320, 200, 104, true, 1));
        El bar = new El("shape", 720, 520, 160, 10);
        bar.fill = t.accent;
        bar.color = 0;
        s.els.add(bar);
        s.els.add(text(t, sub, 140, 570, 1320, 100, 44, false, 1));
        return s;
    }

    static Slide contentSlide(Theme t, String title, String bullets) {
        Slide s = new Slide();
        s.els.add(text(t, title, 110, 80, 1380, 120, 72, true, 0));
        El bar = new El("shape", 110, 210, 120, 8);
        bar.fill = t.accent;
        bar.color = 0;
        s.els.add(bar);
        s.els.add(text(t, bullets, 110, 270, 1380, 560, 46, false, 0));
        return s;
    }

    static Slide twoColumns(Theme t, String title, String left, String right) {
        Slide s = new Slide();
        s.els.add(text(t, title, 110, 80, 1380, 120, 72, true, 0));
        for (int i = 0; i < 2; i++) {
            El card = new El("shape", 110 + i * 710, 250, 670, 560);
            card.shape = "round";
            card.fill = t.accent & 0xFFFFFF | 0x26000000;
            card.color = 0;
            s.els.add(card);
            s.els.add(text(t, i == 0 ? left : right, 150 + i * 710, 290, 590, 480, 40, false, 0));
        }
        return s;
    }

    static Slide imageSlide(Theme t, String title) {
        Slide s = new Slide();
        s.els.add(text(t, title, 110, 80, 700, 200, 68, true, 0));
        s.els.add(text(t, "Explica aquí la imagen en dos o tres líneas.", 110, 330, 640, 400, 40, false, 0));
        El ph = new El("shape", 860, 120, 640, 660);
        ph.shape = "round";
        ph.fill = t.accent & 0xFFFFFF | 0x33000000;
        ph.color = t.accent;
        ph.stroke = 4;
        s.els.add(ph);
        s.els.add(text(t, "Toca «Imagen» para poner tu foto", 900, 420, 560, 80, 34, false, 1));
        return s;
    }

    static Slide sectionSlide(Theme t, String n, String title) {
        Slide s = new Slide();
        El num = text(t, n, 110, 260, 400, 260, 200, true, 0);
        num.color = t.accent;
        s.els.add(num);
        s.els.add(text(t, title, 110, 540, 1380, 140, 80, true, 0));
        return s;
    }

    static Slide closingSlide(Theme t) {
        Slide s = new Slide();
        s.els.add(text(t, "¡Gracias!", 140, 300, 1320, 200, 120, true, 1));
        s.els.add(text(t, "¿Preguntas?", 140, 540, 1320, 100, 48, false, 1));
        return s;
    }

    static final String[] LAYOUTS = {"Portada", "Título y lista", "Dos columnas", "Imagen y texto", "Sección", "Cierre", "En blanco"};

    static Slide layout(Theme t, int i) {
        switch (i) {
            case 0: return titleSlide(t, "Título de la presentación", "Nombre · Curso · Fecha");
            case 1: return contentSlide(t, "Título", "• Primera idea\n• Segunda idea\n• Tercera idea");
            case 2: return twoColumns(t, "Comparación", "Antes\n\n• Punto\n• Punto", "Después\n\n• Punto\n• Punto");
            case 3: return imageSlide(t, "Una imagen dice mucho");
            case 4: return sectionSlide(t, "01", "Nombre de la sección");
            case 5: return closingSlide(t);
            default: return new Slide();
        }
    }

    // ---------------------------------------------------------------- dibujo

    private static final HashMap<String, Bitmap> IMAGES = new HashMap<>();

    static Bitmap image(Context c, String asset) {
        if (asset == null) return null;
        Bitmap b = IMAGES.get(asset);
        if (b != null) return b;
        File f = new File(dir(c), asset);
        if (!f.isFile()) return null;
        b = BitmapFactory.decodeFile(f.getPath());
        if (b != null) IMAGES.put(asset, b);
        return b;
    }

    static void drawBackground(Canvas c, Theme t) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, SW, SH, t.bg1, t.bg2, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, SW, SH, p);
        p.setShader(null);
        switch (t.deco) {
            case 1:
                p.setColor(t.accent & 0xFFFFFF | 0x2E000000);
                c.drawCircle(SW - 120, 110, 260, p);
                p.setColor(t.accent & 0xFFFFFF | 0x1E000000);
                c.drawCircle(120, SH - 60, 200, p);
                break;
            case 2:
                p.setColor(t.accent);
                c.drawRect(0, 0, 28, SH, p);
                p.setColor(t.accent & 0xFFFFFF | 0x22000000);
                c.drawRect(0, SH - 18, SW, SH, p);
                break;
            case 3:
                p.setColor(t.accent & 0xFFFFFF | 0x18000000);
                p.setStrokeWidth(2);
                for (float x = 0; x <= SW; x += 50) c.drawLine(x, 0, x, SH, p);
                for (float y = 0; y <= SH; y += 50) c.drawLine(0, y, SW, y, p);
                break;
            case 4: {
                Path w = new Path();
                for (int k = 0; k < 2; k++) {
                    w.reset();
                    float base = SH - 140 + k * 60;
                    w.moveTo(0, base);
                    for (float x = 0; x <= SW; x += 20) w.lineTo(x, base + 40 * (float) Math.sin(x / 210f + k * 1.7f));
                    w.lineTo(SW, SH);
                    w.lineTo(0, SH);
                    w.close();
                    p.setColor(0xFFFFFF | (k == 0 ? 0x1A000000 : 0x14000000));
                    c.drawPath(w, p);
                }
                break;
            }
            case 5:
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(14);
                p.setColor(0xFF8B5E34);
                c.drawRect(7, 7, SW - 7, SH - 7, p);
                p.setStyle(Paint.Style.FILL);
                break;
            default:
                break;
        }
    }

    static void drawEl(Canvas c, El e, Context ctx, Theme t) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        switch (e.kind) {
            case "text": {
                TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                tp.setColor(e.color);
                tp.setTextSize(e.size);
                Typeface base = t != null && t.serif && e.bold ? Typeface.SERIF : Typeface.create("sans-serif", Typeface.NORMAL);
                tp.setTypeface(Typeface.create(base, e.bold ? Typeface.BOLD : Typeface.NORMAL));
                Layout.Alignment al = e.align == 1 ? Layout.Alignment.ALIGN_CENTER : e.align == 2 ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
                StaticLayout l = StaticLayout.Builder.obtain(e.text, 0, e.text.length(), tp, Math.max(10, Math.round(e.w))).setAlignment(al).setLineSpacing(0, 1.12f).build();
                c.save();
                c.translate(e.x, e.y);
                l.draw(c);
                c.restore();
                break;
            }
            case "shape": {
                RectF r = new RectF(e.x, e.y, e.x + e.w, e.y + e.h);
                for (int pass = 0; pass < 2; pass++) {
                    int color = pass == 0 ? e.fill : e.color;
                    if (color == 0) continue;
                    p.setStyle(pass == 0 ? Paint.Style.FILL : Paint.Style.STROKE);
                    p.setColor(color);
                    p.setStrokeWidth(e.stroke);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    switch (e.shape) {
                        case "ellipse": c.drawOval(r, p); break;
                        case "round": c.drawRoundRect(r, 36, 36, p); break;
                        case "line":
                            if (pass == 1) c.drawLine(e.x, e.y + e.h / 2, e.x + e.w, e.y + e.h / 2, p);
                            break;
                        case "arrow":
                            if (pass == 1) {
                                float y = e.y + e.h / 2;
                                c.drawLine(e.x, y, e.x + e.w, y, p);
                                c.drawLine(e.x + e.w, y, e.x + e.w - 40, y - 28, p);
                                c.drawLine(e.x + e.w, y, e.x + e.w - 40, y + 28, p);
                            }
                            break;
                        case "triangle": {
                            Path tri = new Path();
                            tri.moveTo(e.x + e.w / 2, e.y);
                            tri.lineTo(e.x + e.w, e.y + e.h);
                            tri.lineTo(e.x, e.y + e.h);
                            tri.close();
                            c.drawPath(tri, p);
                            break;
                        }
                        case "star": {
                            Path star = new Path();
                            for (int i = 0; i < 10; i++) {
                                double a = -Math.PI / 2 + i * Math.PI / 5;
                                float rr = i % 2 == 0 ? 0.5f : 0.21f;
                                float x = e.x + e.w / 2 + e.w * rr * (float) Math.cos(a), y = e.y + e.h / 2 + e.h * rr * (float) Math.sin(a);
                                if (i == 0) star.moveTo(x, y);
                                else star.lineTo(x, y);
                            }
                            star.close();
                            c.drawPath(star, p);
                            break;
                        }
                        default: c.drawRect(r, p); break;
                    }
                }
                break;
            }
            case "image": {
                Bitmap b = image(ctx, e.asset);
                if (b != null) c.drawBitmap(b, null, new RectF(e.x, e.y, e.x + e.w, e.y + e.h), new Paint(Paint.FILTER_BITMAP_FLAG));
                break;
            }
            case "sticker": {
                String key = "sticker:" + e.text;
                Bitmap b = IMAGES.get(key);
                if (b == null) {
                    b = Stickers.render(e.text, 256);
                    IMAGES.put(key, b);
                }
                c.drawBitmap(b, null, new RectF(e.x, e.y, e.x + e.w, e.y + e.h), new Paint(Paint.FILTER_BITMAP_FLAG));
                break;
            }
            case "ink": {
                if (e.pts == null || e.pts.length < 2) break;
                p.setStyle(Paint.Style.STROKE);
                p.setColor(e.color);
                p.setStrokeWidth(e.stroke);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                Path path = new Path();
                path.moveTo(e.pts[0], e.pts[1]);
                for (int i = 2; i + 1 < e.pts.length; i += 2) {
                    float mx = (e.pts[i - 2] + e.pts[i]) / 2, my = (e.pts[i - 1] + e.pts[i + 1]) / 2;
                    path.quadTo(e.pts[i - 2], e.pts[i - 1], mx, my);
                }
                path.lineTo(e.pts[e.pts.length - 2], e.pts[e.pts.length - 1]);
                c.drawPath(path, p);
                break;
            }
            default:
                break;
        }
    }

    /** Toda la diapositiva en coordenadas de 1600×900. */
    static void drawSlide(Canvas c, Slide s, Theme t, Context ctx, El selected) {
        drawBackground(c, t);
        for (El e : s.els) drawEl(c, e, ctx, t);
        if (selected != null) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4);
            p.setColor(0xFF2563EB);
            RectF r = bounds(selected);
            c.drawRect(r, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(r.right, r.bottom, 22, p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(0xFF2563EB);
            c.drawCircle(r.right, r.bottom, 22, p);
        }
    }

    static RectF bounds(El e) {
        if ("ink".equals(e.kind) && e.pts != null && e.pts.length >= 2) {
            RectF r = new RectF(e.pts[0], e.pts[1], e.pts[0], e.pts[1]);
            for (int i = 0; i + 1 < e.pts.length; i += 2) r.union(e.pts[i], e.pts[i + 1]);
            r.inset(-e.stroke, -e.stroke);
            return r;
        }
        return new RectF(e.x, e.y, e.x + e.w, e.y + e.h);
    }

    // ---------------------------------------------------------------- la pantalla

    private NoteTheme t;
    private String id, title, folder = "";
    private int theme;
    private final ArrayList<Slide> slides = new ArrayList<>();
    private int current;
    private El selected;
    private boolean drawing;
    private int penColor = 0xFF111827;
    private OptionDock dock;
    private SlideView view;
    private LinearLayout strip, selBar;
    private TextView titleView;
    private IconView penBtn;
    private FrameLayout frame;
    private PresentView presenter;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable autosave = this::save;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        t = new NoteTheme(this);
        id = getIntent().getStringExtra("id");
        if (id != null && load()) {
            // abierta
        } else {
            id = Ink.newId();
            theme = getIntent().getIntExtra("theme", 0);
            title = "Presentación";
            String f = getIntent().getStringExtra("folder");
            if (f != null) folder = f;
            Theme th = THEMES[theme];
            slides.add(titleSlide(th, "Título de la presentación", "Nombre · Curso · Fecha"));
            slides.add(contentSlide(th, "Agenda", "• Introducción\n• Desarrollo\n• Resultados\n• Conclusiones"));
            slides.add(sectionSlide(th, "01", "Introducción"));
            slides.add(twoColumns(th, "Comparación", "Ventajas\n\n• Punto\n• Punto", "Desventajas\n\n• Punto\n• Punto"));
            slides.add(imageSlide(th, "Resultados"));
            slides.add(closingSlide(th));
            save();
        }
        build();
        if (getIntent().getBooleanExtra("present", false)) main.post(this::present);
        else Tutorial.offerOnce(this, dock, "slides");
    }

    private boolean load() {
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(new File(dir(this), id + ".json").toPath()), StandardCharsets.UTF_8));
            title = o.optString("title", "Presentación");
            theme = Math.max(0, Math.min(THEMES.length - 1, o.optInt("theme")));
            folder = o.optString("folder");
            JSONArray a = o.getJSONArray("slides");
            for (int i = 0; i < a.length(); i++) {
                Slide s = new Slide();
                JSONArray els = a.getJSONObject(i).getJSONArray("els");
                for (int k = 0; k < els.length(); k++) s.els.add(El.from(els.getJSONObject(k)));
                slides.add(s);
            }
            if (slides.isEmpty()) slides.add(new Slide());
            return true;
        } catch (IOException | JSONException e) {
            return false;
        }
    }

    private void save() {
        main.removeCallbacks(autosave);
        try {
            JSONArray a = new JSONArray();
            for (Slide s : slides) {
                JSONArray els = new JSONArray();
                for (El e : s.els) els.put(e.json());
                a.put(new JSONObject().put("els", els));
            }
            JSONObject o = new JSONObject().put("id", id).put("title", title).put("theme", theme).put("folder", folder)
                    .put("updated", System.currentTimeMillis()).put("slides", a);
            write(new File(dir(this), id + ".json"), o.toString());
        } catch (IOException | JSONException e) {
            Ui.toast(this, "No se pudo guardar la presentación");
        }
    }

    private void changed() {
        main.removeCallbacks(autosave);
        main.postDelayed(autosave, 1200);
        view.invalidate();
        refreshStrip();
        paintSelBar();
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) Ui.applyFullscreen(this);
    }

    private IconView icon(int which, String label, View.OnClickListener click) {
        IconView v = new IconView(this, which, t.icon, label);
        v.setOnClickListener(click);
        return v;
    }

    @SuppressWarnings("deprecation")
    private void build() {
        getWindow().setStatusBarColor(t.bar);
        getWindow().setNavigationBarColor(t.bg);
        if (!t.dark) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        frame = new FrameLayout(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(t.paper);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));

        HorizontalScrollView topScroll = new HorizontalScrollView(this);
        topScroll.setHorizontalScrollBarEnabled(false);
        topScroll.setBackgroundColor(t.bar);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(t.dp(6), 0, t.dp(6), 0);
        topScroll.addView(top);
        top.addView(icon(IconView.BACK, "Volver", v -> finish()));
        titleView = Ui.text(this, title, 17, t.text, true);
        titleView.setPadding(t.dp(6), 0, t.dp(14), 0);
        titleView.setSingleLine(true);
        titleView.setMaxWidth(t.dp(260));
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setOnClickListener(v -> rename());
        top.addView(titleView);
        top.addView(icon(IconView.TEXT, "Texto", v -> addText()));
        top.addView(icon(IconView.IMAGE, "Imagen", v -> startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), PICK_IMAGE)));
        top.addView(icon(IconView.SHAPES, "Figura", v -> shapeMenu()));
        top.addView(icon(IconView.STICKER, "Stickers", v -> Stickers.choose(this, dock, 0, this::addSticker)));
        penBtn = icon(IconView.PEN, "Dibujar", v -> {
            drawing = !drawing;
            penBtn.setActive(drawing);
            if (drawing) {
                selected = null;
                paintSelBar();
                penMenu();
            }
        });
        top.addView(penBtn);
        top.addView(icon(IconView.WAND, "Estilo", v -> themeMenu()));
        top.addView(icon(IconView.PLAY, "Presentar", v -> present()));
        top.addView(icon(IconView.EXPORT, "Exportar", v -> exportMenu()));
        top.addView(icon(IconView.HELP, "Tutorial", v -> Tutorial.show(this, dock, "slides")));
        root.addView(topScroll, new LinearLayout.LayoutParams(-1, t.dp(54)));

        LinearLayout body = new LinearLayout(this);
        ScrollView ss = new ScrollView(this);
        ss.setBackgroundColor(t.bar);
        strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setPadding(t.dp(10), t.dp(10), t.dp(10), t.dp(10));
        ss.addView(strip);
        body.addView(ss, new LinearLayout.LayoutParams(t.dp(170), -1));
        FrameLayout stage = new FrameLayout(this);
        view = new SlideView();
        stage.addView(view, new FrameLayout.LayoutParams(-1, -1));
        selBar = new LinearLayout(this);
        selBar.setGravity(Gravity.CENTER_VERTICAL);
        selBar.setPadding(t.dp(10), 0, t.dp(10), 0);
        selBar.setBackground(Ui.round(t.dark ? 0xF2262626 : 0xF7FFFFFF, t.dp(26)));
        selBar.setElevation(t.px(10));
        HorizontalScrollView selScroll = new HorizontalScrollView(this);
        selScroll.setHorizontalScrollBarEnabled(false);
        selScroll.addView(selBar);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, t.dp(56), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        slp.bottomMargin = t.dp(18);
        stage.addView(selScroll, slp);
        body.addView(stage, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        root.setOnApplyWindowInsetsListener((v, in) -> {
            @SuppressWarnings("deprecation") int l = in.getSystemWindowInsetLeft(), tp = in.getSystemWindowInsetTop(), r = in.getSystemWindowInsetRight(), b = in.getSystemWindowInsetBottom();
            v.setPadding(l, tp, r, b);
            return in;
        });
        dock = new OptionDock(this, frame, null, t.dark ? 0xF7262626 : 0xF7FFFFFF, t.dark);
        setContentView(frame);
        refreshStrip();
        paintSelBar();
    }

    private void refreshStrip() {
        strip.removeAllViews();
        for (int i = 0; i < slides.size(); i++) {
            int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.TOP);
            TextView n = Ui.text(this, String.valueOf(i + 1), 12, i == current ? t.accent : t.faint, i == current);
            n.setPadding(0, t.dp(2), t.dp(6), 0);
            row.addView(n, new LinearLayout.LayoutParams(t.dp(18), -2));
            Thumb th = new Thumb(slides.get(i), i == current);
            row.addView(th, new LinearLayout.LayoutParams(-1, Math.round(t.dp(130) * SH / SW) + t.dp(6)));
            th.setOnClickListener(v -> {
                current = index;
                selected = null;
                changed();
            });
            th.setOnLongClickListener(v -> {
                slideMenu(index);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = t.dp(10);
            strip.addView(row, lp);
        }
        TextView add = Ui.text(this, "+ Diapositiva", 14, t.muted, true);
        add.setGravity(Gravity.CENTER);
        add.setBackground(Ui.round(t.card, t.dp(10)));
        add.setOnClickListener(v -> addSlideMenu());
        strip.addView(add, new LinearLayout.LayoutParams(-1, t.dp(48)));
    }

    private final class Thumb extends View {
        private final Slide slide;
        private final boolean on;

        Thumb(Slide s, boolean on) {
            super(DeckActivity.this);
            slide = s;
            this.on = on;
            setElevation(t.px(on ? 4 : 1));
        }

        @Override
        protected void onDraw(Canvas c) {
            float pad = t.px(3), w = getWidth() - 2 * pad, k = w / SW;
            c.save();
            c.translate(pad, pad);
            c.clipRect(0, 0, w, SH * k);
            c.scale(k, k);
            drawSlide(c, slide, THEMES[theme], DeckActivity.this, null);
            c.restore();
            if (on) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(t.px(2.5f));
                p.setColor(t.accent);
                c.drawRoundRect(pad / 2, pad / 2, getWidth() - pad / 2, SH * k + pad * 1.5f, t.px(4), t.px(4), p);
            }
        }
    }

    // ---- editar ----

    private Slide slide() {
        return slides.get(current);
    }

    private void addText() {
        Theme th = THEMES[theme];
        El e = text(th, "Escribe aquí", 400, 380, 800, 120, 54, false, 1);
        slide().els.add(e);
        selected = e;
        drawing = false;
        penBtn.setActive(false);
        changed();
        editText(e);
    }

    private void addSticker(String s) {
        El e = new El("sticker", 680, 330, s.startsWith("badge:") ? 360 : 240, s.startsWith("badge:") ? 120 : 240);
        e.text = s;
        if (s.startsWith("badge:")) {
            Bitmap b = Stickers.render(s, 120);
            e.w = b.getWidth();
            e.h = b.getHeight();
        }
        slide().els.add(e);
        selected = e;
        changed();
    }

    private void shapeMenu() {
        String[] kinds = {"rect", "round", "ellipse", "triangle", "star", "line", "arrow"};
        String[] names = {"Rectángulo", "Redondeado", "Círculo", "Triángulo", "Estrella", "Línea", "Flecha"};
        int[] icons = {IconView.S_RECT, IconView.S_RECT, IconView.S_ELLIPSE, IconView.S_TRIANGLE, IconView.STAR, IconView.S_LINE, IconView.S_ARROW};
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < kinds.length; i++) {
            String k = kinds[i];
            tiles.add(new OptionDock.Tile(dock.glyph(icons[i]), names[i], false, () -> {
                boolean line = "line".equals(k) || "arrow".equals(k);
                El e = new El("shape", 600, line ? 430 : 300, 400, line ? 40 : 300);
                e.shape = k;
                Theme th = THEMES[theme];
                e.fill = line ? 0 : th.accent;
                e.color = line ? th.title : 0;
                slide().els.add(e);
                selected = e;
                drawing = false;
                penBtn.setActive(false);
                changed();
            }));
        }
        dock.tiles("Figura", tiles);
    }

    private void penMenu() {
        int[] colors = {0xFF111827, 0xFFFFFFFF, 0xFFDC2626, 0xFF2563EB, 0xFF16A34A, 0xFFF59E0B, 0xFF7C3AED, THEMES[theme].accent};
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (int c : colors) tiles.add(new OptionDock.Tile(dock.swatch(c), "", c == penColor, () -> penColor = c));
        dock.tiles("Dibujar: elige el color", tiles);
    }

    private void editText(El e) {
        EditText f = dock.field(e.text, "Texto");
        f.setSingleLine(false);
        f.setMinLines(2);
        f.setMaxLines(8);
        dock.show("Texto", f, new OptionDock.Action("Cancelar", false, null), new OptionDock.Action("Listo", true, () -> {
            e.text = f.getText().toString();
            changed();
        }));
        dock.focus(f);
    }

    private void themeMenu() {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < THEMES.length; i++) {
            int k = i;
            tiles.add(new OptionDock.Tile(new ThemeSample(this, i), THEMES[i].name, i == theme, () -> applyTheme(k)));
        }
        dock.tiles("Estilo de la presentación", tiles);
    }

    /** Cambia el estilo y los colores de los textos que seguían al anterior. */
    private void applyTheme(int k) {
        Theme from = THEMES[theme], to = THEMES[k];
        for (Slide s : slides) {
            for (El e : s.els) {
                if (e.color == from.title) e.color = to.title;
                else if (e.color == from.body) e.color = to.body;
                else if (e.color == from.accent) e.color = to.accent;
                if (e.fill == from.accent) e.fill = to.accent;
                else if (e.fill == (from.accent & 0xFFFFFF | 0x26000000)) e.fill = to.accent & 0xFFFFFF | 0x26000000;
                else if (e.fill == (from.accent & 0xFFFFFF | 0x33000000)) e.fill = to.accent & 0xFFFFFF | 0x33000000;
            }
        }
        theme = k;
        changed();
    }

    private void addSlideMenu() {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < LAYOUTS.length; i++) {
            int k = i;
            Slide sample = layout(THEMES[theme], i);
            View prev = new View(this) {
                @Override
                protected void onDraw(Canvas c) {
                    float w = getWidth(), kk = w / SW;
                    c.save();
                    c.translate(0, (getHeight() - SH * kk) / 2);
                    c.clipRect(0, 0, w, SH * kk);
                    c.scale(kk, kk);
                    drawSlide(c, sample, THEMES[theme], DeckActivity.this, null);
                    c.restore();
                }
            };
            tiles.add(new OptionDock.Tile(prev, LAYOUTS[i], false, () -> {
                slides.add(current + 1, layout(THEMES[theme], k));
                current++;
                selected = null;
                changed();
            }));
        }
        dock.tiles("Diapositiva nueva", tiles);
    }

    private void slideMenu(int i) {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.COPY), "Duplicar", false, () -> {
            Slide copy = new Slide();
            for (El e : slides.get(i).els) {
                El c = e.copy();
                c.x -= 30;
                c.y -= 30;
                if (c.pts != null) for (int k = 0; k < c.pts.length; k++) c.pts[k] -= 30;
                copy.els.add(c);
            }
            slides.add(i + 1, copy);
            current = i + 1;
            changed();
        }));
        if (i > 0) tiles.add(new OptionDock.Tile(dock.glyph(IconView.PREV), "Subir", false, () -> {
            slides.add(i - 1, slides.remove(i));
            current = i - 1;
            changed();
        }));
        if (i < slides.size() - 1) tiles.add(new OptionDock.Tile(dock.glyph(IconView.NEXT), "Bajar", false, () -> {
            slides.add(i + 1, slides.remove(i));
            current = i + 1;
            changed();
        }));
        if (slides.size() > 1) tiles.add(new OptionDock.Tile(dock.glyph(IconView.TRASH), "Borrar", false, () -> {
            slides.remove(i);
            current = Math.max(0, Math.min(current, slides.size() - 1));
            selected = null;
            changed();
        }));
        dock.tiles("Diapositiva " + (i + 1), tiles);
    }

    private TextView pill(String s, Runnable r) {
        TextView v = Ui.text(this, s, 14, t.text, false);
        v.setGravity(Gravity.CENTER);
        v.setPadding(t.dp(12), 0, t.dp(12), 0);
        v.setOnClickListener(x -> r.run());
        v.setLayoutParams(new LinearLayout.LayoutParams(-2, t.dp(44)));
        return v;
    }

    private void paintSelBar() {
        selBar.removeAllViews();
        View parent = (View) selBar.getParent();
        parent.setVisibility(selected == null ? View.GONE : View.VISIBLE);
        if (selected == null) return;
        El e = selected;
        if ("text".equals(e.kind)) {
            selBar.addView(pill("Editar", () -> editText(e)));
            selBar.addView(pill("A−", () -> { e.size = Math.max(14, e.size - 6); changed(); }));
            selBar.addView(pill("A+", () -> { e.size = Math.min(260, e.size + 6); changed(); }));
            TextView b = pill("B", () -> { e.bold = !e.bold; changed(); });
            b.setTypeface(Typeface.DEFAULT_BOLD);
            selBar.addView(b);
            selBar.addView(pill(e.align == 0 ? "⇤" : e.align == 1 ? "↔" : "⇥", () -> { e.align = (e.align + 1) % 3; changed(); }));
        }
        if (!"image".equals(e.kind) && !"sticker".equals(e.kind)) selBar.addView(pill("Color", () -> colorMenu(e)));
        selBar.addView(pill("Al frente", () -> {
            slide().els.remove(e);
            slide().els.add(e);
            changed();
        }));
        selBar.addView(pill("Atrás", () -> {
            slide().els.remove(e);
            slide().els.add(0, e);
            changed();
        }));
        selBar.addView(pill("Duplicar", () -> {
            El c = e.copy();
            slide().els.add(c);
            selected = c;
            changed();
        }));
        TextView del = pill("Borrar", () -> {
            slide().els.remove(e);
            selected = null;
            changed();
        });
        del.setTextColor(0xFFDC2626);
        selBar.addView(del);
    }

    private void colorMenu(El e) {
        Theme th = THEMES[theme];
        int[] colors = {th.title, th.body, th.accent, 0xFFFFFFFF, 0xFF111827, 0xFFDC2626, 0xFF2563EB, 0xFF16A34A, 0xFFF59E0B, 0xFF7C3AED, 0xFFDB2777, 0xFF6B7280};
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        boolean fill = "shape".equals(e.kind) && e.fill != 0;
        for (int c : colors) tiles.add(new OptionDock.Tile(dock.swatch(c), "", false, () -> {
            if (fill) e.fill = c;
            else e.color = c;
            changed();
        }));
        if ("shape".equals(e.kind)) tiles.add(new OptionDock.Tile(dock.glyph(IconView.S_RECT), e.color == 0 ? "Con borde" : "Sin borde", false, () -> {
            e.color = e.color == 0 ? th.title : 0;
            changed();
        }));
        dock.tiles("Color", tiles);
    }

    private void rename() {
        EditText e = dock.field(title, "Nombre");
        dock.show("Renombrar", e, new OptionDock.Action("Cancelar", false, null), new OptionDock.Action("Guardar", true, () -> {
            String s = e.getText().toString().trim();
            if (s.isEmpty()) return;
            title = s;
            titleView.setText(s);
            save();
        }));
        dock.focus(e);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_IMAGE || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(in, null, o);
                }
                o.inSampleSize = 1;
                while (Math.max(o.outWidth, o.outHeight) / o.inSampleSize > 2400) o.inSampleSize *= 2;
                o.inJustDecodeBounds = false;
                Bitmap b;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    b = BitmapFactory.decodeStream(in, null, o);
                }
                if (b == null) throw new IOException("imagen");
                String asset = id + "-" + Ink.newId() + ".jpg";
                try (FileOutputStream out = new FileOutputStream(new File(dir(this), asset))) {
                    b.compress(Bitmap.CompressFormat.JPEG, 88, out);
                }
                IMAGES.put(asset, b);
                float w = 640, h = w * b.getHeight() / b.getWidth();
                if (h > 700) {
                    h = 700;
                    w = h * b.getWidth() / b.getHeight();
                }
                El e = new El("image", (SW - w) / 2, (SH - h) / 2, w, h);
                e.asset = asset;
                main.post(() -> {
                    // Si la diapositiva tiene el hueco de la plantilla, la foto va ahí.
                    El hole = null;
                    for (El x : slide().els) if ("text".equals(x.kind) && x.text.startsWith("Toca «Imagen»")) hole = x;
                    if (hole != null) {
                        slide().els.remove(hole);
                        for (El x : slide().els) {
                            if ("shape".equals(x.kind) && "round".equals(x.shape) && x.x > 800) {
                                float k = Math.min(x.w / e.w, x.h / e.h);
                                e.w *= k;
                                e.h *= k;
                                e.x = x.x + (x.w - e.w) / 2;
                                e.y = x.y + (x.h - e.h) / 2;
                            }
                        }
                    }
                    slide().els.add(e);
                    selected = e;
                    changed();
                });
            } catch (IOException | RuntimeException | OutOfMemoryError ex) {
                main.post(() -> Ui.toast(this, "No se pudo abrir la imagen"));
            }
        }).start();
    }

    // ---- el lienzo ----

    private final class SlideView extends View {
        private float k = 1, ox, oy;
        private int dragMode; // 0 nada, 1 mover, 2 estirar, 3 dibujar
        private float lastX, lastY;
        private final ArrayList<Float> inkPts = new ArrayList<>();
        private final GestureDetector taps;

        SlideView() {
            super(DeckActivity.this);
            taps = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDoubleTap(MotionEvent e) {
                    if (selected != null && "text".equals(selected.kind)) editText(selected);
                    return true;
                }
            });
        }

        @Override
        protected void onDraw(Canvas c) {
            float pad = t.dp(24);
            k = Math.min((getWidth() - 2 * pad) / SW, (getHeight() - 2 * pad - t.dp(60)) / SH);
            ox = (getWidth() - SW * k) / 2;
            oy = Math.max(pad, (getHeight() - t.dp(60) - SH * k) / 2);
            Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setColor(0x33000000);
            shadow.setShadowLayer(t.px(12), 0, t.px(4), 0x40000000);
            c.drawRect(ox, oy, ox + SW * k, oy + SH * k, shadow);
            c.save();
            c.translate(ox, oy);
            c.clipRect(0, 0, SW * k, SH * k);
            c.scale(k, k);
            drawSlide(c, slide(), THEMES[theme], DeckActivity.this, selected);
            if (dragMode == 3 && inkPts.size() >= 4) {
                El tmp = new El("ink", 0, 0, 0, 0);
                tmp.color = penColor;
                tmp.pts = new float[inkPts.size()];
                for (int i = 0; i < inkPts.size(); i++) tmp.pts[i] = inkPts.get(i);
                drawEl(c, tmp, DeckActivity.this, null);
            }
            c.restore();
        }

        private El hit(float x, float y) {
            ArrayList<El> els = slide().els;
            for (int i = els.size() - 1; i >= 0; i--) {
                RectF r = bounds(els.get(i));
                r.inset(-12, -12);
                if (r.contains(x, y)) return els.get(i);
            }
            return null;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            taps.onTouchEvent(ev);
            float x = (ev.getX() - ox) / k, y = (ev.getY() - oy) / k;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = x;
                    lastY = y;
                    if (drawing) {
                        dragMode = 3;
                        inkPts.clear();
                        inkPts.add(x);
                        inkPts.add(y);
                        break;
                    }
                    if (selected != null) {
                        RectF r = bounds(selected);
                        if (Math.hypot(x - r.right, y - r.bottom) < 48) {
                            dragMode = 2;
                            break;
                        }
                    }
                    El h = hit(x, y);
                    if (h != selected) {
                        selected = h;
                        paintSelBar();
                    }
                    dragMode = h == null ? 0 : 1;
                    invalidate();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = x - lastX, dy = y - lastY;
                    lastX = x;
                    lastY = y;
                    if (dragMode == 3) {
                        for (int i = 0; i < ev.getHistorySize(); i++) {
                            inkPts.add((ev.getHistoricalX(i) - ox) / k);
                            inkPts.add((ev.getHistoricalY(i) - oy) / k);
                        }
                        inkPts.add(x);
                        inkPts.add(y);
                    } else if (dragMode == 1 && selected != null) {
                        selected.x += dx;
                        selected.y += dy;
                        if (selected.pts != null) for (int i = 0; i < selected.pts.length; i += 2) {
                            selected.pts[i] += dx;
                            selected.pts[i + 1] += dy;
                        }
                    } else if (dragMode == 2 && selected != null) {
                        if ("ink".equals(selected.kind)) {
                            RectF r = bounds(selected);
                            float sx = Math.max(0.1f, (r.width() + dx) / r.width()), sy = Math.max(0.1f, (r.height() + dy) / r.height());
                            for (int i = 0; i < selected.pts.length; i += 2) {
                                selected.pts[i] = r.left + (selected.pts[i] - r.left) * sx;
                                selected.pts[i + 1] = r.top + (selected.pts[i + 1] - r.top) * sy;
                            }
                        } else if ("image".equals(selected.kind) || "sticker".equals(selected.kind)) {
                            float ratio = selected.h / Math.max(1, selected.w);
                            selected.w = Math.max(40, selected.w + dx);
                            selected.h = selected.w * ratio;
                        } else {
                            selected.w = Math.max(30, selected.w + dx);
                            selected.h = Math.max(10, selected.h + dy);
                        }
                    }
                    invalidate();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragMode == 3 && inkPts.size() >= 4) {
                        El e = new El("ink", 0, 0, 0, 0);
                        e.color = penColor;
                        e.stroke = 7;
                        e.pts = new float[inkPts.size()];
                        for (int i = 0; i < inkPts.size(); i++) e.pts[i] = inkPts.get(i);
                        slide().els.add(e);
                    }
                    if (dragMode != 0) changed();
                    dragMode = 0;
                    inkPts.clear();
                    break;
                default:
                    break;
            }
            return true;
        }
    }

    // ---- presentar ----

    private void present() {
        save();
        selected = null;
        presenter = new PresentView();
        presenter.index = current;
        frame.addView(presenter, new FrameLayout.LayoutParams(-1, -1));
        presenter.setElevation(t.px(40));
        presenter.requestFocus();
        boolean wasFull = Ui.fullscreen(this);
        presenter.restoreFull = wasFull;
        getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("fullscreen", true).apply();
        Ui.applyFullscreen(this);
        getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("fullscreen", wasFull).apply();
    }

    private void stopPresenting() {
        if (presenter == null) return;
        frame.removeView(presenter);
        current = presenter.index;
        presenter = null;
        Ui.applyFullscreen(this);
        changed();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (presenter != null) {
            switch (code) {
                case KeyEvent.KEYCODE_PAGE_DOWN: case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_SPACE:
                case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_F8: case KeyEvent.KEYCODE_ENTER:
                    presenter.go(1);
                    return true;
                case KeyEvent.KEYCODE_PAGE_UP: case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_F7:
                    presenter.go(-1);
                    return true;
                case KeyEvent.KEYCODE_ESCAPE: case KeyEvent.KEYCODE_BACK:
                    stopPresenting();
                    return true;
                default:
                    break;
            }
        }
        return super.onKeyDown(code, e);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (presenter != null) stopPresenting();
        else super.onBackPressed();
    }

    private final class PresentView extends View {
        int index;
        boolean restoreFull;
        private float downX;

        PresentView() {
            super(DeckActivity.this);
            setBackgroundColor(0xFF000000);
            setFocusable(true);
            setFocusableInTouchMode(true);
            if (Build.VERSION.SDK_INT >= 33) {
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, new android.window.OnBackInvokedCallback() {
                    @Override public void onBackInvoked() {
                        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(this);
                        stopPresenting();
                    }
                });
            }
        }

        void go(int d) {
            int next = index + d;
            if (next >= slides.size()) {
                stopPresenting();
                return;
            }
            index = Math.max(0, next);
            setAlpha(0.6f);
            animate().alpha(1).setDuration(220).start();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float k = Math.min(getWidth() / SW, getHeight() / SH);
            c.save();
            c.translate((getWidth() - SW * k) / 2, (getHeight() - SH * k) / 2);
            c.clipRect(0, 0, SW * k, SH * k);
            c.scale(k, k);
            drawSlide(c, slides.get(index), THEMES[theme], DeckActivity.this, null);
            c.restore();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0x80FFFFFF);
            p.setTextSize(t.px(13));
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText((index + 1) + " / " + slides.size(), getWidth() - t.px(16), getHeight() - t.px(14), p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) downX = e.getX();
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                float dx = e.getX() - downX;
                if (Math.abs(dx) > t.dp(60)) go(dx < 0 ? 1 : -1);
                else go(e.getX() > getWidth() / 3f ? 1 : -1);
            }
            return true;
        }
    }

    // ---- exportar ----

    private void exportMenu() {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        if (Native.available()) tiles.add(new OptionDock.Tile(dock.glyph(IconView.SLIDES), "PowerPoint (.pptx)", false, () -> export(true)));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.FILE), "PDF", false, () -> export(false)));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.PLAY), "Presentar", false, this::present));
        dock.tiles("Exportar", tiles);
    }

    private void export(boolean pptx) {
        save();
        Ui.toast(this, "Exportando…");
        new Thread(() -> {
            try {
                byte[] bytes;
                if (pptx) {
                    byte[][] jpegs = new byte[slides.size()][];
                    for (int i = 0; i < slides.size(); i++) {
                        Bitmap b = Bitmap.createBitmap((int) SW, (int) SH, Bitmap.Config.ARGB_8888);
                        drawSlide(new Canvas(b), slides.get(i), THEMES[theme], this, null);
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        b.compress(Bitmap.CompressFormat.JPEG, 90, out);
                        b.recycle();
                        jpegs[i] = out.toByteArray();
                    }
                    bytes = Native.imagesToPptx(jpegs, SW, SH);
                    if (bytes == null) throw new IOException("pptx");
                } else {
                    android.graphics.pdf.PdfDocument pdf = new android.graphics.pdf.PdfDocument();
                    for (int i = 0; i < slides.size(); i++) {
                        android.graphics.pdf.PdfDocument.Page page = pdf.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(960, 540, i + 1).create());
                        Canvas c = page.getCanvas();
                        c.scale(960 / SW, 960 / SW);
                        drawSlide(c, slides.get(i), THEMES[theme], this, null);
                        pdf.finishPage(page);
                    }
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    pdf.writeTo(out);
                    pdf.close();
                    bytes = out.toByteArray();
                }
                String mime = pptx ? "application/vnd.openxmlformats-officedocument.presentationml.presentation" : "application/pdf";
                String name = title.replaceAll("[\\\\/:*?\"<>|]", " ").trim() + (pptx ? ".pptx" : ".pdf");
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                if (Build.VERSION.SDK_INT >= 29) v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/SyncTablet");
                Uri uri = getContentResolver().insert(MediaStore.Files.getContentUri("external"), v);
                if (uri == null) throw new IOException("sin acceso a Descargas");
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    out.write(bytes);
                }
                main.post(() -> {
                    Ui.toast(this, "Guardado en Descargas/SyncTablet/" + name);
                    Intent share = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(share, "Compartir"));
                });
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                android.util.Log.w("SyncTablet", "exportar presentación", e);
                main.post(() -> Ui.toast(this, "No se pudo exportar"));
            }
        }).start();
    }
}
