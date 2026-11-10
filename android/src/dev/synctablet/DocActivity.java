package dev.synctablet;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.Html;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.style.AlignmentSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.BulletSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Documentos con formato, como en Word, en una hoja A4: negrita, cursiva,
 * subrayado, tachado, títulos, tamaños, colores, resaltado, alineación y listas.
 * Se guardan con sus estilos (no se pierden al reabrir) y se exportan a Word
 * (WordCraft) o a PDF.
 */
public final class DocActivity extends Activity {
    private static final int SAVE_DOCX = 91;
    private static final float PAGE_W = 595, PAGE_H = 842, MARGIN = 64;

    // ---------------------------------------------------------------- archivos

    static final class Info {
        String id, title, folder = "";
        long updated;
        String preview = "";
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "docs");
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
                i.title = o.optString("title", "Documento");
                i.folder = o.optString("folder");
                i.updated = o.optLong("updated");
                String text = o.optString("text");
                i.preview = text.length() > 300 ? text.substring(0, 300) : text;
                out.add(i);
            } catch (IOException | JSONException e) {
                android.util.Log.w("SyncTablet", "documento " + f, e);
            }
        }
        out.sort((a, b) -> Long.compare(b.updated, a.updated));
        return out;
    }

    static void delete(Context c, String id) {
        new File(dir(c), id + ".json").delete();
    }

    static void setFolder(Context c, String id, String folder) {
        File f = new File(dir(c), id + ".json");
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            o.put("folder", folder);
            write(f, o.toString());
        } catch (IOException | JSONException e) {
            android.util.Log.w("SyncTablet", "mover documento", e);
        }
    }

    private static void write(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("no se pudo guardar");
    }

    /** Un documento nuevo desde HTML (plantilla o Word importado); devuelve su id. */
    static String create(Context c, String title, String html, String folder) {
        String id = Ink.newId();
        Spanned text = fromHtml(c, id, html);
        try {
            JSONObject o = new JSONObject().put("id", id).put("title", title).put("folder", folder == null ? "" : folder)
                    .put("updated", System.currentTimeMillis()).put("text", text.toString()).put("spans", spans(text));
            write(new File(dir(c), id + ".json"), o.toString());
            return id;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- estilos a JSON y vuelta

    private static JSONArray spans(Spanned s) throws JSONException {
        JSONArray a = new JSONArray();
        for (Object span : s.getSpans(0, s.length(), Object.class)) {
            int st = s.getSpanStart(span), en = s.getSpanEnd(span);
            if (st < 0 || en <= st) continue;
            JSONObject o = new JSONObject().put("s", st).put("e", en);
            if (span instanceof StyleSpan) o.put("k", "style").put("v", ((StyleSpan) span).getStyle());
            else if (span instanceof UnderlineSpan) o.put("k", "u");
            else if (span instanceof StrikethroughSpan) o.put("k", "strike");
            else if (span instanceof RelativeSizeSpan) o.put("k", "size").put("v", ((RelativeSizeSpan) span).getSizeChange());
            else if (span instanceof ForegroundColorSpan) o.put("k", "color").put("v", ((ForegroundColorSpan) span).getForegroundColor());
            else if (span instanceof BackgroundColorSpan) o.put("k", "bg").put("v", ((BackgroundColorSpan) span).getBackgroundColor());
            else if (span instanceof AlignmentSpan) o.put("k", "align").put("v", ((AlignmentSpan) span).getAlignment().name());
            else if (span instanceof BulletSpan) o.put("k", "bullet");
            else if (span instanceof TableSpan) o.put("k", "table").put("v", ((TableSpan) span).rows.toString());
            else if (span instanceof android.text.style.ImageSpan && String.valueOf(((android.text.style.ImageSpan) span).getSource()).startsWith("asset:"))
                o.put("k", "img").put("v", ((android.text.style.ImageSpan) span).getSource().substring(6));
            else continue;
            a.put(o);
        }
        return a;
    }

    private static Object make(JSONObject o) {
        switch (o.optString("k")) {
            case "style": return new StyleSpan(o.optInt("v"));
            case "u": return new UnderlineSpan();
            case "strike": return new StrikethroughSpan();
            case "size": return new RelativeSizeSpan((float) o.optDouble("v", 1));
            case "color": return new ForegroundColorSpan(o.optInt("v"));
            case "bg": return new BackgroundColorSpan(o.optInt("v"));
            case "align":
                try {
                    return new AlignmentSpan.Standard(Layout.Alignment.valueOf(o.optString("v")));
                } catch (IllegalArgumentException e) {
                    return null;
                }
            case "bullet": return new BulletSpan(18, 0xFF374151);
            default: return null;
        }
    }

    /** HTML (de WordCraft o de una plantilla) a texto con estilos; los tamaños en pt pasan a relativos. */
    static Spanned fromHtml(Context ctx, String docId, String html) {
        // Las tablas salen antes: Android no sabe leerlas. Su sitio lo guarda una marca.
        ArrayList<JSONArray> tables = new ArrayList<>();
        Matcher tm = Pattern.compile("(?is)<table.*?</table>").matcher(html);
        StringBuffer tb = new StringBuffer();
        while (tm.find()) {
            tables.add(TableSpan.parse(tm.group()));
            tm.appendReplacement(tb, "<p>" + TABLE_MARK + "</p>");
        }
        tm.appendTail(tb);
        html = tb.toString();
        // Android no lee font-size: se cambia por etiquetas propias que lee el TagHandler.
        Matcher m = Pattern.compile("<span style=\"([^\"]*)\">(.*?)</span>", Pattern.DOTALL).matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String style = m.group(1), inner = m.group(2);
            Matcher size = Pattern.compile("font-size:\\s*([\\d.]+)(pt|em|px)").matcher(style);
            Matcher color = Pattern.compile("(?<!-)color:\\s*(#[0-9A-Fa-f]{6})").matcher(style);
            String out = inner;
            if (color.find()) out = "<font color=\"" + color.group(1) + "\">" + out + "</font>";
            if (size.find()) {
                float v = Float.parseFloat(size.group(1));
                float rel = "em".equals(size.group(2)) ? v : "px".equals(size.group(2)) ? v / 16f : v / 12f;
                int pct = Math.round(rel * 100);
                if (Math.abs(pct - 100) > 8) out = "<sz" + pct + ">" + out + "</sz" + pct + ">";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(out));
        }
        m.appendTail(sb);
        String body = sb.toString().replaceAll("(?s).*<body[^>]*>", "").replaceAll("(?s)</body>.*", "");
        Html.TagHandler sizes = (opening, tag, output, reader) -> {
            if (!tag.startsWith("sz")) return;
            int pct;
            try {
                pct = Integer.parseInt(tag.substring(2));
            } catch (NumberFormatException e) {
                return;
            }
            if (opening) {
                output.setSpan(new SizeMark(pct), output.length(), output.length(), Spannable.SPAN_MARK_MARK);
            } else {
                SizeMark[] marks = output.getSpans(0, output.length(), SizeMark.class);
                if (marks.length == 0) return;
                SizeMark mark = marks[marks.length - 1];
                int start = output.getSpanStart(mark);
                output.removeSpan(mark);
                if (start < output.length()) output.setSpan(new RelativeSizeSpan(pct / 100f), start, output.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        };
        // Las imágenes de un Word llegan en base64: se guardan junto al documento.
        Html.ImageGetter images = src -> {
            if (src == null || !src.startsWith("data:image")) return null;
            try {
                byte[] data = android.util.Base64.decode(src.substring(src.indexOf(',') + 1), android.util.Base64.DEFAULT);
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bmp == null) return null;
                String name = saveImage(ctx, docId, bmp);
                return name == null ? null : new Pending(name);
            } catch (IllegalArgumentException e) {
                return null;
            }
        };
        Spanned sp = Html.fromHtml(body, Html.FROM_HTML_MODE_COMPACT, images, sizes);
        // ImageSpan con fuente "asset:…", para guardarla y exportarla igual.
        SpannableStringBuilder fixed = new SpannableStringBuilder(sp);
        for (android.text.style.ImageSpan im : fixed.getSpans(0, fixed.length(), android.text.style.ImageSpan.class)) {
            if (!(im.getDrawable() instanceof Pending)) continue;
            int st = fixed.getSpanStart(im), en = fixed.getSpanEnd(im);
            fixed.removeSpan(im);
            fixed.setSpan(new android.text.style.ImageSpan(ctx, android.net.Uri.EMPTY) {
                @Override public String getSource() { return "asset:" + ((Pending) im.getDrawable()).name; }
            }, st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int ti = 0;
        for (int i = 0; i < fixed.length() && ti < tables.size(); i++) {
            if (fixed.charAt(i) != TABLE_MARK) continue;
            fixed.setSpan(new TableSpan(tables.get(ti++), 600), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        sp = fixed;
        // Html deja un salto de línea al final.
        SpannableStringBuilder b = new SpannableStringBuilder(sp);
        while (b.length() > 0 && b.charAt(b.length() - 1) == '\n') b.delete(b.length() - 1, b.length());
        return b;
    }

    /** Carácter que ocupa el lugar de una tabla en el texto. */
    static final char TABLE_MARK = '\u25A6';

    /**
     * Una tabla dentro del texto: se dibuja con bordes y encabezado, a lo ancho
     * de la hoja; cada celda parte su texto en líneas. Se toca para editarla.
     */
    static final class TableSpan extends android.text.style.ReplacementSpan {
        final JSONArray rows;
        int width;
        private int[] rowHeights;
        private StaticLayout[][] cells;
        private float lastSize;

        TableSpan(JSONArray rows, int width) {
            this.rows = rows;
            this.width = width;
        }

        int cols() {
            int n = 1;
            for (int r = 0; r < rows.length(); r++) n = Math.max(n, rows.optJSONArray(r) == null ? 0 : rows.optJSONArray(r).length());
            return n;
        }

        private void layout(android.graphics.Paint base) {
            if (cells != null && lastSize == base.getTextSize()) return;
            lastSize = base.getTextSize();
            int cols = cols(), cw = Math.max(20, width / cols), pad = Math.round(base.getTextSize() * 0.35f);
            cells = new StaticLayout[rows.length()][cols];
            rowHeights = new int[rows.length()];
            for (int r = 0; r < rows.length(); r++) {
                JSONArray line = rows.optJSONArray(r);
                int h = Math.round(base.getTextSize() * 1.6f);
                for (int c = 0; c < cols; c++) {
                    String txt = line == null ? "" : line.optString(c, "");
                    TextPaint tp = new TextPaint(base);
                    tp.setTextSize(base.getTextSize() * 0.9f);
                    tp.setFakeBoldText(r == 0);
                    tp.setColor(0xFF111827);
                    StaticLayout l = StaticLayout.Builder.obtain(txt, 0, txt.length(), tp, Math.max(10, cw - 2 * pad))
                            .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
                    cells[r][c] = l;
                    h = Math.max(h, l.getHeight() + 2 * pad);
                }
                rowHeights[r] = h;
            }
        }

        int height(android.graphics.Paint p) {
            layout(p);
            int h = 0;
            for (int x : rowHeights) h += x;
            return h + Math.round(p.getTextSize() * 0.6f);
        }

        @Override
        public int getSize(android.graphics.Paint paint, CharSequence text, int start, int end, android.graphics.Paint.FontMetricsInt fm) {
            int h = height(paint);
            if (fm != null) {
                fm.ascent = fm.top = -h;
                fm.descent = fm.bottom = 0;
            }
            return width;
        }

        @Override
        public void draw(Canvas c, CharSequence text, int start, int end, float x, int top, int y, int bottom, android.graphics.Paint paint) {
            layout(paint);
            int cols = cols(), cw = Math.max(20, width / cols), pad = Math.round(paint.getTextSize() * 0.35f);
            float yy = y - height(paint) + paint.getTextSize() * 0.3f;
            android.graphics.Paint line = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            line.setStyle(android.graphics.Paint.Style.STROKE);
            line.setStrokeWidth(Math.max(1, paint.getTextSize() / 16));
            line.setColor(0xFF9CA3AF);
            android.graphics.Paint head = new android.graphics.Paint();
            head.setColor(0xFFE8EEFC);
            for (int r = 0; r < rows.length(); r++) {
                int h = rowHeights[r];
                if (r == 0) c.drawRect(x, yy, x + cw * cols, yy + h, head);
                for (int col = 0; col < cols; col++) {
                    float cx = x + col * cw;
                    c.drawRect(cx, yy, cx + cw, yy + h, line);
                    StaticLayout l = cells[r][col];
                    c.save();
                    c.translate(cx + pad, yy + (h - l.getHeight()) / 2f);
                    l.draw(c);
                    c.restore();
                }
                yy += h;
            }
        }

        /** La tabla como HTML para el Word. */
        String html() {
            StringBuilder b = new StringBuilder("<table style=\"border-collapse:collapse\">");
            for (int r = 0; r < rows.length(); r++) {
                b.append("<tr>");
                JSONArray line = rows.optJSONArray(r);
                for (int c = 0; c < cols(); c++) {
                    String tag = r == 0 ? "th" : "td";
                    String txt = line == null ? "" : line.optString(c, "");
                    b.append('<').append(tag).append(" style=\"border:1px solid #999;padding:4px 6px;text-align:center\">")
                            .append(txt.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")).append("</").append(tag).append('>');
                }
                b.append("</tr>");
            }
            return b.append("</table>").toString();
        }

        /** Una tabla de HTML (de WordCraft) a filas de texto. */
        static JSONArray parse(String html) {
            JSONArray rows = new JSONArray();
            Matcher tr = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>").matcher(html);
            while (tr.find()) {
                JSONArray line = new JSONArray();
                Matcher td = Pattern.compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>").matcher(tr.group(1));
                while (td.find()) line.put(Html.fromHtml(td.group(1), Html.FROM_HTML_MODE_COMPACT).toString().trim());
                if (line.length() > 0) rows.put(line);
            }
            return rows;
        }
    }

    /** Marca de una imagen recién guardada mientras se lee el HTML. */
    private static final class Pending extends android.graphics.drawable.ColorDrawable {
        final String name;

        Pending(String name) {
            super(0);
            this.name = name;
            setBounds(0, 0, 1, 1);
        }
    }

    /** Guarda la imagen junto al documento (como mucho 1400 px) y devuelve su nombre. */
    static String saveImage(Context c, String docId, android.graphics.Bitmap bmp) {
        int max = 1400;
        if (Math.max(bmp.getWidth(), bmp.getHeight()) > max) {
            float k = max / (float) Math.max(bmp.getWidth(), bmp.getHeight());
            bmp = android.graphics.Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * k), Math.round(bmp.getHeight() * k), true);
        }
        String name = docId + "-" + Ink.newId() + ".png";
        try (FileOutputStream out = new FileOutputStream(new File(dir(c), name))) {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            return name;
        } catch (IOException e) {
            return null;
        }
    }

    /** La imagen como span, a lo ancho de `width` píxeles como mucho. */
    static android.text.style.ImageSpan imageSpan(Context c, String name, int width) {
        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(new File(dir(c), name).getPath());
        android.graphics.drawable.Drawable d = bmp == null ? new android.graphics.drawable.ColorDrawable(0xFFE5E7EB)
                : new android.graphics.drawable.BitmapDrawable(c.getResources(), bmp);
        int iw = bmp == null ? width : bmp.getWidth(), ih = bmp == null ? width / 2 : bmp.getHeight();
        float k = Math.min(1f, width / (float) iw);
        d.setBounds(0, 0, Math.round(iw * k), Math.round(ih * k));
        return new android.text.style.ImageSpan(d, "asset:" + name);
    }

    /** Una imagen al final del documento (una gráfica de la hoja de cálculo, por ejemplo). */
    static boolean appendImage(Context c, String id, android.graphics.Bitmap bmp) {
        File f = new File(dir(c), id + ".json");
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            String name = saveImage(c, id, bmp);
            if (name == null) return false;
            String text = o.optString("text");
            String add = (text.isEmpty() ? "" : "\n") + "\uFFFC";
            int at = text.length() + add.length() - 1;
            o.put("text", text + add);
            JSONArray spans = o.optJSONArray("spans");
            if (spans == null) spans = new JSONArray();
            spans.put(new JSONObject().put("k", "img").put("v", name).put("s", at).put("e", at + 1));
            o.put("spans", spans);
            o.put("updated", System.currentTimeMillis());
            write(f, o.toString());
            return true;
        } catch (IOException | JSONException e) {
            return false;
        }
    }

    private static final class SizeMark {
        final int pct;

        SizeMark(int pct) {
            this.pct = pct;
        }
    }

    // ---------------------------------------------------------------- la pantalla

    private NoteTheme t;
    private String id, title, folder = "";
    private EditText body;
    private TextView titleView, state, wordStatus;
    private OptionDock dock;
    private boolean loading;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable autosave = this::save;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        t = new NoteTheme(this);
        id = getIntent().getStringExtra("id");
        build();
        if (!load()) {
            finish();
            return;
        }
        Tutorial.offerOnce(this, dock, "doc");
    }

    private boolean load() {
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(new File(dir(this), id + ".json").toPath()), StandardCharsets.UTF_8));
            title = o.optString("title", "Documento");
            folder = o.optString("folder");
            restore(o);
            last = snapshot();
            titleView.setText(title);
            return true;
        } catch (IOException | JSONException | RuntimeException e) {
            android.util.Log.w("SyncTablet", "abrir documento", e);
            return false;
        }
    }

    /** Pone el texto con sus estilos desde {text, spans}. */
    private void restore(JSONObject o) throws JSONException {
        SpannableStringBuilder b = new SpannableStringBuilder(o.optString("text"));
        JSONArray a = o.optJSONArray("spans");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject s = a.getJSONObject(i);
            Object span = "img".equals(s.optString("k")) ? imageSpan(this, s.optString("v"), pageWidth())
                    : "table".equals(s.optString("k")) ? new TableSpan(new JSONArray(s.optString("v", "[]")), pageWidth()) : make(s);
            int st = s.optInt("s"), en = Math.min(b.length(), s.optInt("e"));
            if (span != null && st < en) b.setSpan(span, st, en, span instanceof AlignmentSpan || span instanceof BulletSpan
                    ? Spanned.SPAN_PARAGRAPH : span instanceof android.text.style.ImageSpan || span instanceof TableSpan ? Spanned.SPAN_EXCLUSIVE_EXCLUSIVE : Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
        }
        loading = true;
        int cursor = body.getSelectionStart();
        body.setText(b);
        body.setSelection(Math.max(0, Math.min(b.length(), cursor)));
        loading = false;
    }

    private int pageWidth() {
        return Math.min(getResources().getDisplayMetrics().widthPixels - t.dp(32), t.dp(820)) - 2 * t.dp(56);
    }

    private static final int PICK_IMAGE = 92;

    private void insertImage(Uri uri) {
        new Thread(() -> {
            android.graphics.Bitmap bmp = null;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                android.graphics.BitmapFactory.Options op = new android.graphics.BitmapFactory.Options();
                op.inSampleSize = 1;
                bmp = android.graphics.BitmapFactory.decodeStream(in, null, op);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                android.util.Log.w("SyncTablet", "imagen del documento", e);
            }
            android.graphics.Bitmap img = bmp;
            String name = img == null ? null : saveImage(this, id, img);
            main.post(() -> {
                if (name == null) {
                    Ui.toast(this, "No se pudo abrir la imagen");
                    return;
                }
                Editable e = body.getText();
                int at = Math.max(0, body.getSelectionStart());
                String before = at > 0 && e.charAt(at - 1) != '\n' ? "\n" : "";
                e.insert(at, before + "\uFFFC\n");
                int pos = at + before.length();
                e.setSpan(imageSpan(this, name, pageWidth()), pos, pos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                changed();
            });
        }).start();
    }

    // ---- tablas ----

    private void insertTable(int rowsN, int colsN) {
        JSONArray rows = new JSONArray();
        for (int r = 0; r < rowsN; r++) {
            JSONArray line = new JSONArray();
            for (int c = 0; c < colsN; c++) line.put(r == 0 ? "Columna " + (c + 1) : "");
            rows.put(line);
        }
        Editable e = body.getText();
        int at = Math.max(0, body.getSelectionStart());
        String before = at > 0 && e.charAt(at - 1) != '\n' ? "\n" : "";
        e.insert(at, before + TABLE_MARK + "\n");
        int pos = at + before.length();
        TableSpan ts = new TableSpan(rows, pageWidth());
        e.setSpan(ts, pos, pos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        changed();
        editTable(ts);
    }

    private void tableMenu() {
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        int[][] sizes = {{2, 2}, {3, 3}, {4, 3}, {5, 4}, {6, 2}, {8, 4}, {11, 4}, {12, 6}};
        for (int[] z : sizes) tiles.add(new OptionDock.Tile(dock.letter(z[0] + "×" + z[1]), z[0] + " filas, " + z[1] + " columnas", false, () -> insertTable(z[0], z[1])).stays());
        dock.tiles("Insertar tabla", tiles);
    }

    /** Editar las celdas de una tabla (la primera fila es el encabezado). */
    private void editTable(TableSpan ts) {
        JSONArray rows = ts.rows;
        int rN = rows.length(), cN = ts.cols();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(t.dp(12), 0, t.dp(12), t.dp(8));
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(cN);
        ArrayList<EditText> fields = new ArrayList<>();
        for (int r = 0; r < rN; r++) {
            JSONArray line = rows.optJSONArray(r);
            for (int c = 0; c < cN; c++) {
                EditText f = dock.field(line == null ? "" : line.optString(c, ""), r == 0 ? "Título" : "");
                f.setTextSize(14);
                if (r == 0) f.setTypeface(Typeface.DEFAULT_BOLD);
                android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
                lp.width = t.dp(Math.max(90, 560 / cN));
                lp.setMargins(t.dp(2), t.dp(2), t.dp(2), t.dp(2));
                grid.addView(f, lp);
                fields.add(f);
            }
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(grid);
        box.addView(hs);
        Runnable read = () -> {
            JSONArray out = new JSONArray();
            for (int r = 0; r < rN; r++) {
                JSONArray line = new JSONArray();
                for (int c = 0; c < cN; c++) line.put(fields.get(r * cN + c).getText().toString());
                out.put(line);
            }
            replaceTable(ts, out);
        };
        LinearLayout more = new LinearLayout(this);
        String[] names = {"+ Fila", "− Fila", "+ Columna", "− Columna", "Borrar tabla"};
        for (int k = 0; k < names.length; k++) {
            int which = k;
            TextView b = dock.chip(names[k], false);
            b.setOnClickListener(v -> {
                read.run();
                TableSpan now = current(ts);
                if (now == null) return;
                JSONArray rr = now.rows;
                try {
                    if (which == 0) {
                        JSONArray line = new JSONArray();
                        for (int c = 0; c < cN; c++) line.put("");
                        rr.put(line);
                    } else if (which == 1 && rr.length() > 1) {
                        rr.remove(rr.length() - 1);
                    } else if (which == 2) {
                        for (int r = 0; r < rr.length(); r++) rr.getJSONArray(r).put(r == 0 ? "Columna " + (cN + 1) : "");
                    } else if (which == 3 && cN > 1) {
                        for (int r = 0; r < rr.length(); r++) rr.getJSONArray(r).remove(rr.getJSONArray(r).length() - 1);
                    } else if (which == 4) {
                        Editable e = body.getText();
                        int st = e.getSpanStart(now), en = e.getSpanEnd(now);
                        e.removeSpan(now);
                        if (st >= 0) e.delete(st, Math.min(e.length(), en + (en < e.length() && e.charAt(en) == '\n' ? 1 : 0)));
                        changed();
                        dock.close();
                        return;
                    }
                } catch (JSONException ignored) {
                }
                replaceTable(now, rr);
                editTable(current(now) == null ? now : current(now));
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, t.dp(42));
            lp.rightMargin = t.dp(6);
            more.addView(b, lp);
        }
        HorizontalScrollView ms = new HorizontalScrollView(this);
        ms.addView(more);
        box.addView(ms);
        dock.show("Tabla", box, new OptionDock.Action("Cancelar", false, null), new OptionDock.Action("Guardar", true, read));
    }

    private TableSpan lastReplaced;

    /** La tabla que hay ahora en lugar de `old` (al editar se cambia el span). */
    private TableSpan current(TableSpan old) {
        Editable e = body.getText();
        if (e.getSpanStart(old) >= 0) return old;
        return lastReplaced;
    }

    private void replaceTable(TableSpan ts, JSONArray rows) {
        Editable e = body.getText();
        int st = e.getSpanStart(ts), en = e.getSpanEnd(ts);
        if (st < 0) return;
        e.removeSpan(ts);
        TableSpan fresh = new TableSpan(rows, pageWidth());
        lastReplaced = fresh;
        e.setSpan(fresh, st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        // Que el texto se vuelva a medir con la tabla nueva.
        e.replace(st, en, String.valueOf(TABLE_MARK));
        if (e.getSpanStart(fresh) < 0) e.setSpan(fresh, st, st + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        changed();
    }

    // ---- deshacer ----

    private final java.util.ArrayDeque<String> undo = new java.util.ArrayDeque<>(), redo = new java.util.ArrayDeque<>();
    private String last;
    private final Runnable record = this::record;

    private String snapshot() {
        try {
            Editable e = body.getText();
            return new JSONObject().put("text", e.toString()).put("spans", spans(e)).toString();
        } catch (JSONException e) {
            return last;
        }
    }

    private void record() {
        main.removeCallbacks(record);
        String now = snapshot();
        if (now == null || now.equals(last)) return;
        if (last != null) undo.push(last);
        while (undo.size() > 100) undo.removeLast();
        redo.clear();
        last = now;
    }

    private void step(boolean back) {
        record();
        java.util.ArrayDeque<String> from = back ? undo : redo, to = back ? redo : undo;
        if (from.isEmpty()) {
            Ui.toast(this, back ? "Nada que deshacer" : "Nada que rehacer");
            return;
        }
        to.push(last);
        last = from.pop();
        try {
            restore(new JSONObject(last));
        } catch (JSONException ignored) {
        }
        state.setText("Sin guardar");
        main.removeCallbacks(autosave);
        main.postDelayed(autosave, 800);
    }

    private void paintWordStatus() {
        if (wordStatus == null) return;
        String txt = body.getText().toString().trim();
        int words = txt.isEmpty() ? 0 : txt.split("\\s+").length;
        // Una hoja A4 a lo ancho de la página en pantalla (como en Word, «Página 1 de N»).
        int pageH = Math.max(1, Math.round(body.getWidth() * PAGE_H / PAGE_W));
        int pages = Math.max(1, (int) Math.ceil(body.getHeight() / (float) pageH));
        wordStatus.setText("Página 1 de " + pages + "      " + words + (words == 1 ? " palabra" : " palabras") + "      Español (Costa Rica)");
    }

    private void save() {
        main.removeCallbacks(autosave);
        try {
            Editable e = body.getText();
            JSONObject o = new JSONObject().put("id", id).put("title", title).put("folder", folder)
                    .put("updated", System.currentTimeMillis()).put("text", e.toString()).put("spans", spans(e));
            write(new File(dir(this), id + ".json"), o.toString());
            state.setText("Guardado");
        } catch (IOException | JSONException ex) {
            state.setText("No se pudo guardar");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (body == null) return;
        save();
        // Una copia en Word para el PC.
        if (Native.available() && SyncClient.get(this).isConnected()) {
            String html = html(), name = title;
            Context app = getApplicationContext();
            new Thread(() -> SyncClient.get(app).sendFile("doc", name, "docx", Native.htmlToDocx(html)), "doc-pc").start();
        }
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
        FrameLayout frame = new FrameLayout(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(t.paper);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(t.dp(6), 0, t.dp(6), 0);
        top.setBackgroundColor(t.bar);
        top.addView(icon(IconView.BACK, "Volver", v -> finish()));
        titleView = Ui.text(this, "", 17, t.text, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setPadding(t.dp(6), 0, t.dp(6), 0);
        titleView.setOnClickListener(v -> rename());
        top.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
        state = Ui.text(this, "", 13, t.faint, false);
        state.setPadding(t.dp(8), 0, t.dp(8), 0);
        top.addView(state);
        top.addView(icon(IconView.UNDO, "Deshacer", v -> step(true)));
        top.addView(icon(IconView.REDO, "Rehacer", v -> step(false)));
        top.addView(icon(IconView.EXPORT, "Exportar a Word o PDF", v -> exportMenu()));
        top.addView(icon(IconView.HELP, "Tutorial", v -> Tutorial.show(this, dock, "doc")));
        root.addView(top, new LinearLayout.LayoutParams(-1, t.dp(54)));
        Ribbon.paintTitleBar(this, top, Ribbon.WORD);
        for (int i = 0; i < top.getChildCount(); i++) {
            View v = top.getChildAt(i);
            if (v instanceof TextView) ((TextView) v).setTextColor(0xFFFFFFFF);
            if (v instanceof IconView) ((IconView) v).setColor(0xFFFFFFFF);
        }

        // La cinta de WordCraft: Inicio (Fuente, Párrafo, Estilos), Insertar, Vista.
        Ribbon rb = new Ribbon(this, t, Ribbon.WORD);
        rb.fileTab(this::exportMenu);
        rb.tab("Inicio");
        Ribbon.Group f = rb.group("Fuente");
        f.letter("B", "Negrita", () -> fmt(() -> toggleStyle(Typeface.BOLD))).setTypeface(Typeface.DEFAULT_BOLD);
        f.letter("I", "Cursiva", () -> fmt(() -> toggleStyle(Typeface.ITALIC))).setTypeface(Typeface.defaultFromStyle(Typeface.ITALIC));
        TextView u = f.letter("U", "Subrayado", () -> fmt(() -> toggle(UnderlineSpan.class, UnderlineSpan::new)));
        u.setPaintFlags(u.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        TextView st = f.letter("ab", "Tachado", () -> fmt(() -> toggle(StrikethroughSpan.class, StrikethroughSpan::new)));
        st.setPaintFlags(st.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        f.letter("A+", "Agrandar fuente", () -> fmt(() -> size(1.18f)));
        f.letter("A−", "Reducir fuente", () -> fmt(() -> size(0.85f)));
        f.button(IconView.HIGHLIGHTER, "Resaltar", () -> colorMenu(true)).button(IconView.TEXT, "Color", () -> colorMenu(false))
                .button(IconView.ERASER, "Borrar\nformato", () -> fmt(this::clear));
        rb.group("Párrafo")
                .button(IconView.DOTS, "Viñetas", () -> fmt(this::bullets))
                .button(IconView.PAGES, "Numeración", () -> fmt(this::numbers))
                .button(IconView.TEXT, "Izquierda", () -> fmt(() -> align(Layout.Alignment.ALIGN_NORMAL)))
                .button(IconView.TEXT, "Centrar", () -> fmt(() -> align(Layout.Alignment.ALIGN_CENTER)))
                .button(IconView.TEXT, "Derecha", () -> fmt(() -> align(Layout.Alignment.ALIGN_OPPOSITE)));
        Ribbon.Group styles = rb.group("Estilos");
        styles.word("Normal", () -> fmt(() -> heading(1f)));
        styles.word("Título 1", () -> fmt(() -> heading(1.8f))).setTypeface(Typeface.DEFAULT_BOLD);
        styles.word("Título 2", () -> fmt(() -> heading(1.35f))).setTypeface(Typeface.DEFAULT_BOLD);
        rb.tab("Insertar");
        rb.group("Tablas").button(IconView.TABLE, "Tabla", this::tableMenu);
        rb.group("Ilustraciones").button(IconView.IMAGE, "Imágenes", () -> startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), PICK_IMAGE));
        rb.tab("Diseño");
        rb.group("Formato del documento").button(IconView.TEXT, "Título 1", () -> fmt(() -> heading(1.8f)))
                .button(IconView.TEXT, "Título 2", () -> fmt(() -> heading(1.35f)));
        rb.tab("Revisar");
        rb.group("Revisión").button(IconView.SEARCH, "Contar\npalabras", () -> Ui.toast(this, wordStatus.getText().toString()));
        rb.tab("Vista");
        rb.group("Ver").button(IconView.EXPAND, "Pantalla\ncompleta", () -> Ui.setFullscreen(this, !Ui.fullscreen(this)));
        rb.group("Ayuda").button(IconView.HELP, "Tutorial", () -> Tutorial.show(this, dock, "doc"));
        rb.select(0);
        root.addView(rb.view, new LinearLayout.LayoutParams(-1, -2));

        // La hoja: blanca, de ancho A4 a escala, sobre el escritorio.
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        FrameLayout desk = new FrameLayout(this);
        desk.setPadding(t.dp(16), t.dp(24), t.dp(16), t.dp(80));
        sv.addView(desk);
        body = new EditText(this);
        body.setBackgroundColor(0xFFFFFFFF);
        body.setTextColor(0xFF1F2937);
        body.setHintTextColor(0xFF9CA3AF);
        body.setHint("Escribe tu documento…");
        body.setTextSize(16);
        body.setGravity(Gravity.TOP | Gravity.START);
        body.setLineSpacing(0, 1.2f);
        body.setElevation(t.px(3));
        int pad = t.dp(56);
        body.setPadding(pad, pad, pad, pad);
        body.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        body.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> paintWordStatus());
        // Tocar una tabla abre su editor.
        body.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() != android.view.MotionEvent.ACTION_UP) return false;
            int off = body.getOffsetForPosition(ev.getX(), ev.getY());
            Editable e = body.getText();
            for (TableSpan ts : e.getSpans(Math.max(0, off - 1), Math.min(e.length(), off + 1), TableSpan.class)) {
                android.text.Layout l = body.getLayout();
                int line = l == null ? 0 : l.getLineForOffset(e.getSpanStart(ts));
                float y = ev.getY() - body.getTotalPaddingTop() + body.getScrollY();
                if (l != null && (y < l.getLineTop(line) || y > l.getLineBottom(line))) continue;
                editTable(ts);
                return true;
            }
            return false;
        });
        body.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                paintWordStatus();
                if (loading) return;
                state.setText("Sin guardar");
                main.removeCallbacks(autosave);
                main.postDelayed(autosave, 1200);
                main.removeCallbacks(record);
                main.postDelayed(record, 700);
            }
        });
        int w = Math.min(getResources().getDisplayMetrics().widthPixels - t.dp(32), t.dp(820));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(w, -2, Gravity.CENTER_HORIZONTAL);
        body.setMinHeight(Math.round(w * PAGE_H / PAGE_W));
        desk.addView(body, blp);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        // Barra de estado como la de Word: página y palabras.
        wordStatus = Ui.text(this, "", 12, t.muted, false);
        wordStatus.setGravity(Gravity.CENTER_VERTICAL);
        wordStatus.setPadding(t.dp(14), 0, t.dp(14), 0);
        wordStatus.setBackgroundColor(t.dark ? 0xFF202020 : 0xFFF3F3F3);
        root.addView(wordStatus, new LinearLayout.LayoutParams(-1, t.dp(30)));
        root.setOnApplyWindowInsetsListener((v, in) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets s = in.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());
                v.setPadding(s.left, s.top, s.right, s.bottom);
            }
            return in;
        });
        dock = new OptionDock(this, frame, null, t.dark ? 0xF7262626 : 0xF7FFFFFF, t.dark);
        setContentView(frame);
    }

    private void fmt(Runnable r) {
        r.run();
        body.requestFocus();
    }

    private TextView chip(LinearLayout parent, String text, Runnable run) {
        TextView c = Ui.text(this, text, 14, t.text, false);
        c.setGravity(Gravity.CENTER);
        c.setPadding(t.dp(14), 0, t.dp(14), 0);
        c.setMinWidth(t.dp(44));
        c.setBackground(Ui.round(t.card, t.dp(10)));
        c.setOnClickListener(v -> {
            run.run();
            body.requestFocus();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, t.dp(40));
        lp.rightMargin = t.dp(6);
        parent.addView(c, lp);
        return c;
    }

    // ---- formato ----

    private int[] sel() {
        int a = Math.max(0, body.getSelectionStart()), b = Math.max(0, body.getSelectionEnd());
        return new int[] {Math.min(a, b), Math.max(a, b)};
    }

    /** Los párrafos que toca la selección. */
    private int[] paragraphs() {
        Editable e = body.getText();
        int[] s = sel();
        int start = s[0], end = s[1];
        while (start > 0 && e.charAt(start - 1) != '\n') start--;
        while (end < e.length() && e.charAt(end) != '\n') end++;
        return new int[] {start, end};
    }

    private void changed() {
        record();
        state.setText("Sin guardar");
        main.removeCallbacks(autosave);
        main.postDelayed(autosave, 800);
    }

    private void toggleStyle(int style) {
        Editable e = body.getText();
        int[] s = sel();
        if (s[0] == s[1]) {
            Ui.toast(this, "Elige primero el texto");
            return;
        }
        boolean had = false;
        for (StyleSpan sp : e.getSpans(s[0], s[1], StyleSpan.class)) {
            if (sp.getStyle() != style) continue;
            had = true;
            int st = e.getSpanStart(sp), en = e.getSpanEnd(sp);
            e.removeSpan(sp);
            if (st < s[0]) e.setSpan(new StyleSpan(style), st, s[0], Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
            if (en > s[1]) e.setSpan(new StyleSpan(style), s[1], en, Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
        }
        if (!had) e.setSpan(new StyleSpan(style), s[0], s[1], Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
        changed();
    }

    private <T> void toggle(Class<T> kind, java.util.function.Supplier<Object> make) {
        Editable e = body.getText();
        int[] s = sel();
        if (s[0] == s[1]) {
            Ui.toast(this, "Elige primero el texto");
            return;
        }
        T[] have = e.getSpans(s[0], s[1], kind);
        if (have.length > 0) {
            for (T sp : have) e.removeSpan(sp);
        } else {
            e.setSpan(make.get(), s[0], s[1], Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
        }
        changed();
    }

    /** Título, subtítulo o normal: tamaño y negrita para todo el párrafo. */
    private void heading(float k) {
        Editable e = body.getText();
        int[] p = paragraphs();
        if (p[0] == p[1]) return;
        for (RelativeSizeSpan sp : e.getSpans(p[0], p[1], RelativeSizeSpan.class)) e.removeSpan(sp);
        for (StyleSpan sp : e.getSpans(p[0], p[1], StyleSpan.class)) if (sp.getStyle() == Typeface.BOLD) e.removeSpan(sp);
        if (k != 1f) {
            e.setSpan(new RelativeSizeSpan(k), p[0], p[1], Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
            e.setSpan(new StyleSpan(Typeface.BOLD), p[0], p[1], Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
        }
        changed();
    }

    private void size(float factor) {
        Editable e = body.getText();
        int[] s = sel();
        if (s[0] == s[1]) {
            Ui.toast(this, "Elige primero el texto");
            return;
        }
        float now = 1f;
        for (RelativeSizeSpan sp : e.getSpans(s[0], s[1], RelativeSizeSpan.class)) {
            now = sp.getSizeChange();
            e.removeSpan(sp);
        }
        float next = Math.max(0.5f, Math.min(4f, now * factor));
        if (Math.abs(next - 1f) > 0.02f) e.setSpan(new RelativeSizeSpan(next), s[0], s[1], Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
        changed();
    }

    private void align(Layout.Alignment a) {
        Editable e = body.getText();
        int[] p = paragraphs();
        for (AlignmentSpan sp : e.getSpans(p[0], p[1], AlignmentSpan.class)) e.removeSpan(sp);
        if (a != Layout.Alignment.ALIGN_NORMAL && p[0] < p[1]) e.setSpan(new AlignmentSpan.Standard(a), p[0], p[1], Spanned.SPAN_PARAGRAPH);
        changed();
    }

    private void bullets() {
        Editable e = body.getText();
        int[] p = paragraphs();
        BulletSpan[] have = e.getSpans(p[0], p[1], BulletSpan.class);
        if (have.length > 0) {
            for (BulletSpan b : have) e.removeSpan(b);
        } else {
            // Una viñeta por párrafo.
            int start = p[0];
            while (start <= p[1]) {
                int end = start;
                while (end < e.length() && e.charAt(end) != '\n') end++;
                if (end > start) e.setSpan(new BulletSpan(18, 0xFF374151), start, end, Spanned.SPAN_PARAGRAPH);
                start = end + 1;
            }
        }
        changed();
    }

    private void numbers() {
        Editable e = body.getText();
        int[] p = paragraphs();
        int start = p[0], n = 1;
        while (start <= p[1] && start <= e.length()) {
            int end = start;
            while (end < e.length() && e.charAt(end) != '\n') end++;
            String prefix = n + ". ";
            e.insert(start, prefix);
            p[1] += prefix.length();
            start = end + prefix.length() + 1;
            n++;
        }
        changed();
    }

    private void clear() {
        Editable e = body.getText();
        int[] s = sel();
        if (s[0] == s[1]) return;
        for (Object sp : e.getSpans(s[0], s[1], Object.class)) {
            if (sp instanceof StyleSpan || sp instanceof UnderlineSpan || sp instanceof StrikethroughSpan || sp instanceof RelativeSizeSpan
                    || sp instanceof ForegroundColorSpan || sp instanceof BackgroundColorSpan) e.removeSpan(sp);
        }
        changed();
    }

    private void colorMenu(boolean highlight) {
        int[] colors = highlight ? new int[] {0xFFFEF08A, 0xFFBBF7D0, 0xFFBFDBFE, 0xFFFBCFE8, 0xFFFED7AA, 0xFFE9D5FF}
                : new int[] {0xFF111827, 0xFFDC2626, 0xFF2563EB, 0xFF16A34A, 0xFF7C3AED, 0xFFEA580C, 0xFF0F766E, 0xFF6B7280};
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.ERASER), "Quitar", false, () -> {
            Editable e = body.getText();
            int[] s = sel();
            for (Object sp : e.getSpans(s[0], s[1], (Class<?>) (highlight ? BackgroundColorSpan.class : ForegroundColorSpan.class))) e.removeSpan(sp);
            changed();
        }));
        for (int c : colors) tiles.add(new OptionDock.Tile(dock.swatch(c), "", false, () -> {
            Editable e = body.getText();
            int[] s = sel();
            if (s[0] == s[1]) {
                Ui.toast(this, "Elige primero el texto");
                return;
            }
            for (Object sp : e.getSpans(s[0], s[1], (Class<?>) (highlight ? BackgroundColorSpan.class : ForegroundColorSpan.class))) e.removeSpan(sp);
            e.setSpan(highlight ? new BackgroundColorSpan(c) : new ForegroundColorSpan(c), s[0], s[1], Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
            changed();
        }));
        dock.tiles(highlight ? "Resaltar" : "Color del texto", tiles);
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

    // ---- exportar ----

    private void exportMenu() {
        save();
        ArrayList<OptionDock.Tile> tiles = new ArrayList<>();
        if (Native.available()) tiles.add(new OptionDock.Tile(dock.glyph(IconView.DOC), "Word (.docx)", false, () -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                    .putExtra(Intent.EXTRA_TITLE, safe(title) + ".docx");
            startActivityForResult(i, SAVE_DOCX);
        }));
        tiles.add(new OptionDock.Tile(dock.glyph(IconView.FILE), "PDF", false, this::exportPdf));
        dock.tiles("Exportar", tiles);
    }

    private static String safe(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|]", "-");
    }

    private String html() {
        SpannableStringBuilder copy = new SpannableStringBuilder(body.getText());
        ArrayList<String> tables = new ArrayList<>();
        TableSpan[] ts = copy.getSpans(0, copy.length(), TableSpan.class);
        java.util.Arrays.sort(ts, (x, y) -> Integer.compare(copy.getSpanStart(y), copy.getSpanStart(x)));
        for (TableSpan t0 : ts) {
            int st = copy.getSpanStart(t0), en = copy.getSpanEnd(t0);
            copy.removeSpan(t0);
            tables.add(0, t0.html());
            copy.replace(st, en, "[[TABLA]]");
        }
        String h = Html.toHtml(copy, Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL);
        for (String table : tables) {
            int i = h.indexOf("[[TABLA]]");
            if (i < 0) break;
            int ps = h.lastIndexOf("<p", i), pe = h.indexOf("</p>", i);
            if (ps >= 0 && pe > i && !h.substring(ps, i).contains("</p>")) h = h.substring(0, ps) + table + h.substring(pe + 4);
            else h = h.substring(0, i) + table + h.substring(i + 9);
        }
        // Las imágenes van dentro del Word: en base64 y con su tamaño en la hoja.
        Matcher m = Pattern.compile("<img src=\"asset:([^\"]+)\"\\s*/?>").matcher(h);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            File f = new File(dir(this), m.group(1));
            String rep = "";
            try {
                byte[] data = Files.readAllBytes(f.toPath());
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                float w = Math.min(600, o.outWidth), hgt = w * o.outHeight / Math.max(1, o.outWidth);
                rep = "<img src=\"data:image/png;base64," + android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
                        + "\" width=\"" + Math.round(w) + "\" height=\"" + Math.round(hgt) + "\">";
            } catch (IOException ignored) {
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_IMAGE && result == RESULT_OK && data != null && data.getData() != null) {
            insertImage(data.getData());
            return;
        }
        if (request != SAVE_DOCX || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String html = html();
        new Thread(() -> {
            byte[] docx = Native.htmlToDocx(html);
            boolean ok = false;
            if (docx != null) {
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    out.write(docx);
                    ok = true;
                } catch (IOException | RuntimeException e) {
                    android.util.Log.w("SyncTablet", "exportar docx", e);
                }
            }
            boolean done = ok;
            main.post(() -> Ui.toast(this, done ? "Guardado como Word" : "No se pudo crear el Word"));
        }).start();
    }

    private static float usableHeight() {
        return PAGE_H - 2 * MARGIN - 20;
    }

    /** PDF en hojas A4, con el mismo formato que en pantalla. */
    private void exportPdf() {
        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFF111827);
        paint.setTextSize(11.5f);
        float width = PAGE_W - 2 * MARGIN;
        SpannableStringBuilder text = new SpannableStringBuilder(body.getText());
        for (android.text.style.ImageSpan im : text.getSpans(0, text.length(), android.text.style.ImageSpan.class)) {
            String src = im.getSource();
            if (src == null || !src.startsWith("asset:")) continue;
            int st = text.getSpanStart(im), en = text.getSpanEnd(im);
            text.removeSpan(im);
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(new File(dir(this), src.substring(6)).getPath());
            if (bmp == null) continue;
            android.graphics.drawable.BitmapDrawable d = new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
            float k = Math.min(1f, width / bmp.getWidth());
            float h = Math.min(usableHeight(), bmp.getHeight() * k);
            k = h / bmp.getHeight();
            d.setBounds(0, 0, Math.round(bmp.getWidth() * k), Math.round(h));
            text.setSpan(new android.text.style.ImageSpan(d), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        for (TableSpan t0 : text.getSpans(0, text.length(), TableSpan.class)) {
            int st = text.getSpanStart(t0), en = text.getSpanEnd(t0);
            text.removeSpan(t0);
            text.setSpan(new TableSpan(t0.rows, Math.round(width)), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.round(width)).setLineSpacing(0, 1.2f).build();
        PdfDocument pdf = new PdfDocument();
        float usable = PAGE_H - 2 * MARGIN;
        int line = 0, page = 1;
        while (line < layout.getLineCount()) {
            int first = line;
            float top = layout.getLineTop(first);
            while (line < layout.getLineCount() && layout.getLineBottom(line) - top <= usable) line++;
            if (line == first) line++;
            PdfDocument.Page p = pdf.startPage(new PdfDocument.PageInfo.Builder((int) PAGE_W, (int) PAGE_H, page++).create());
            Canvas c = p.getCanvas();
            c.save();
            c.translate(MARGIN, MARGIN - top);
            c.clipRect(0, top, width, layout.getLineBottom(line - 1));
            layout.draw(c);
            c.restore();
            pdf.finishPage(p);
        }
        if (layout.getLineCount() == 0) pdf.finishPage(pdf.startPage(new PdfDocument.PageInfo.Builder((int) PAGE_W, (int) PAGE_H, 1).create()));
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.writeTo(out);
            pdf.close();
            String name = safe(title) + ".pdf";
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
            if (Build.VERSION.SDK_INT >= 29) v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/SyncTablet");
            Uri uri = getContentResolver().insert(MediaStore.Files.getContentUri("external"), v);
            if (uri == null) throw new IOException("sin acceso a Descargas");
            try (OutputStream o = getContentResolver().openOutputStream(uri)) {
                o.write(out.toByteArray());
            }
            Ui.toast(this, "Guardado en Descargas/SyncTablet/" + name);
            Intent share = new Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Compartir"));
        } catch (IOException | RuntimeException e) {
            Ui.toast(this, "No se pudo crear el PDF");
        }
    }
}
