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
        Spanned text = fromHtml(html);
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
    static Spanned fromHtml(String html) {
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
        Spanned sp = Html.fromHtml(body, Html.FROM_HTML_MODE_COMPACT, null, sizes);
        // Html deja un salto de línea al final.
        SpannableStringBuilder b = new SpannableStringBuilder(sp);
        while (b.length() > 0 && b.charAt(b.length() - 1) == '\n') b.delete(b.length() - 1, b.length());
        return b;
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
    private TextView titleView, state;
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
            Object span = make(s);
            int st = s.optInt("s"), en = Math.min(b.length(), s.optInt("e"));
            if (span != null && st < en) b.setSpan(span, st, en, span instanceof AlignmentSpan || span instanceof BulletSpan
                    ? Spanned.SPAN_PARAGRAPH : Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
        }
        loading = true;
        int cursor = body.getSelectionStart();
        body.setText(b);
        body.setSelection(Math.max(0, Math.min(b.length(), cursor)));
        loading = false;
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
        if (body != null) save();
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

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackgroundColor(t.bar);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(t.dp(8), 0, t.dp(8), t.dp(6));
        hs.addView(bar);
        chip(bar, "Título", () -> heading(1.8f)).setTypeface(Typeface.DEFAULT_BOLD);
        chip(bar, "Subtítulo", () -> heading(1.35f)).setTypeface(Typeface.DEFAULT_BOLD);
        chip(bar, "Normal", () -> heading(1f));
        chip(bar, "B", () -> toggleStyle(Typeface.BOLD)).setTypeface(Typeface.DEFAULT_BOLD);
        chip(bar, "I", () -> toggleStyle(Typeface.ITALIC)).setTypeface(Typeface.defaultFromStyle(Typeface.ITALIC));
        TextView u = chip(bar, "U", () -> toggle(UnderlineSpan.class, UnderlineSpan::new));
        u.setPaintFlags(u.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        TextView st = chip(bar, "S", () -> toggle(StrikethroughSpan.class, StrikethroughSpan::new));
        st.setPaintFlags(st.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        chip(bar, "A−", () -> size(0.85f));
        chip(bar, "A+", () -> size(1.18f));
        chip(bar, "Color", () -> colorMenu(false));
        chip(bar, "Resaltar", () -> colorMenu(true));
        chip(bar, "⇤", () -> align(Layout.Alignment.ALIGN_NORMAL));
        chip(bar, "↔", () -> align(Layout.Alignment.ALIGN_CENTER));
        chip(bar, "⇥", () -> align(Layout.Alignment.ALIGN_OPPOSITE));
        chip(bar, "• Lista", this::bullets);
        chip(bar, "1. Lista", this::numbers);
        chip(bar, "Sin formato", this::clear);
        root.addView(hs, new LinearLayout.LayoutParams(-1, -2));

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
        body.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
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
        return Html.toHtml(body.getText(), Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
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

    /** PDF en hojas A4, con el mismo formato que en pantalla. */
    private void exportPdf() {
        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFF111827);
        paint.setTextSize(11.5f);
        float width = PAGE_W - 2 * MARGIN;
        Editable text = body.getText();
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
