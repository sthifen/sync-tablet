package dev.synctablet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Los bloques de una nota: texto, encabezados, listas, tareas, cita, llamada,
 * separador y páginas de la tablet. Atajos: "# ", "## ", "### ", "- ", "1. ",
 * "[] ", "> ", "---"; "/" en un bloque vacío abre el menú de tipos.
 * Enter crea un bloque nuevo y borrar al principio une con el anterior.
 */
final class BlockEditor {
    interface Host {
        void changed();
        /** La tarjeta de una página de la tablet incrustada. */
        View tabletBlock(JSONObject b);
        /** Elegir qué página de la tablet poner en el bloque (luego llamar a setTablet). */
        void pickTabletPage(JSONObject b);
        /** Muestra las opciones de un bloque: dibujo y nombre de cada una, y qué elegir. */
        void blockOptions(String[] samples, String[] names, int current, java.util.function.IntConsumer pick);
    }

    static final String[][] TYPES = {
        {"text", "Texto"}, {"h1", "Encabezado 1"}, {"h2", "Encabezado 2"}, {"h3", "Encabezado 3"},
        {"bullet", "Lista con viñetas"}, {"number", "Lista numerada"}, {"todo", "Lista de tareas"},
        {"quote", "Cita"}, {"callout", "Llamada"}, {"divider", "Separador"}, {"tablet", "Página de la tablet"},
    };

    private final Context ctx;
    private final NoteTheme t;
    private final LinearLayout box;
    private final Host host;
    private final List<JSONObject> blocks = new ArrayList<>();
    private JSONObject page;
    private boolean rebuilding;

    BlockEditor(Context ctx, NoteTheme t, LinearLayout box, Host host) {
        this.ctx = ctx;
        this.t = t;
        this.box = box;
        this.host = host;
    }

    void setPage(JSONObject p) {
        page = p;
        blocks.clear();
        JSONArray arr = p.optJSONArray("blocks");
        if (arr != null) for (int i = 0; i < arr.length(); i++) { JSONObject b = arr.optJSONObject(i); if (b != null) blocks.add(b); }
        if (blocks.isEmpty()) blocks.add(Notes.block("text", ""));
        render(null, 0);
    }

    boolean isEditing() { return box.findFocus() != null; }

    /** Repinta (p. ej. cuando cambia una página de la tablet incrustada). */
    void refresh() {
        if (!isEditing()) render(null, 0);
    }

    void focusFirst() {
        if (blocks.isEmpty()) blocks.add(Notes.block("text", ""));
        JSONObject first = blocks.get(0);
        if (!isText(first)) {
            first = Notes.block("text", "");
            blocks.add(0, first);
            changed();
        }
        render(first.optString("id"), 0);
    }

    void addAtEnd() {
        JSONObject last = blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
        if (last != null && "text".equals(type(last)) && last.optString("text").isEmpty()) {
            render(last.optString("id"), 0);
            return;
        }
        JSONObject b = Notes.block("text", "");
        blocks.add(b);
        changed();
        render(b.optString("id"), 0);
    }

    /** Convierte el bloque en una página de la tablet. */
    void setTablet(JSONObject b, String pageId, String label) {
        try {
            b.put("type", "tablet");
            b.put("text", "");
            b.put("checked", false);
            b.put("page", pageId);
            b.put("label", label);
        } catch (Exception ignored) { }
        int i = indexOf(b.optString("id"));
        String focus = null;
        if (i >= 0 && i == blocks.size() - 1) {
            JSONObject nb = Notes.block("text", "");
            blocks.add(nb);
            focus = nb.optString("id");
        }
        changed();
        render(focus, 0);
    }

    private static String type(JSONObject b) { return b.optString("type", "text"); }

    private static boolean isText(JSONObject b) {
        String k = type(b);
        return !"divider".equals(k) && !"tablet".equals(k);
    }

    private void changed() {
        try { page.put("blocks", new JSONArray(blocks)); } catch (Exception ignored) { }
        host.changed();
    }

    private int indexOf(String id) {
        for (int i = 0; i < blocks.size(); i++) if (blocks.get(i).optString("id").equals(id)) return i;
        return -1;
    }

    private void setType(JSONObject b, String type) {
        try {
            b.put("type", type);
            if (!"todo".equals(type)) b.put("checked", false);
            if ("divider".equals(type)) b.put("text", "");
            if (!"tablet".equals(type)) { b.remove("page"); b.remove("label"); }
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- dibujo de los bloques

    private void render(String focusId, int cursor) {
        rebuilding = true;
        box.removeAllViews();
        int number = 0;
        EditText toFocus = null;
        for (int i = 0; i < blocks.size(); i++) {
            JSONObject b = blocks.get(i);
            number = "number".equals(type(b)) ? number + 1 : 0;
            View row = row(b, number);
            box.addView(row);
            if (b.optString("id").equals(focusId)) toFocus = row.findViewWithTag("editor");
        }
        rebuilding = false;
        if (toFocus != null) {
            final EditText et = toFocus;
            et.requestFocus();
            et.setSelection(Math.max(0, Math.min(cursor, et.length())));
            et.post(() -> {
                InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(et, 0);
            });
        }
    }

    private View row(final JSONObject b, int number) {
        final String type = type(b);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = t.dp("h1".equals(type) ? 26 : "h2".equals(type) ? 18 : "h3".equals(type) ? 12 : "tablet".equals(type) ? 8 : 1);
        row.setLayoutParams(rlp);

        // Asa para cambiar el tipo o borrar el bloque.
        IconView handle = new IconView(ctx, IconView.DOTS, t.faint, "Opciones del bloque");
        handle.setAlpha(0.8f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(t.dp(26), t.dp("h1".equals(type) ? 44 : "h2".equals(type) ? 38 : 32));
        hlp.rightMargin = t.dp(4);
        row.addView(handle, hlp);
        handle.setOnClickListener(v -> blockMenu(v, b));

        if ("divider".equals(type)) {
            View line = new View(ctx);
            line.setBackgroundColor(t.line);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Math.max(1, t.dp(1)), 1);
            lp.topMargin = t.dp(15);
            lp.bottomMargin = t.dp(15);
            row.addView(line, lp);
            return row;
        }
        if ("tablet".equals(type)) {
            row.addView(host.tabletBlock(b), new LinearLayout.LayoutParams(0, -2, 1));
            return row;
        }

        LinearLayout body = row;
        if ("callout".equals(type)) {
            body = new LinearLayout(ctx);
            body.setOrientation(LinearLayout.HORIZONTAL);
            body.setPadding(t.dp(12), t.dp(12), t.dp(14), t.dp(12));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(t.callout);
            bg.setCornerRadius(t.px(6));
            body.setBackground(bg);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, -2, 1);
            blp.topMargin = t.dp(4);
            blp.bottomMargin = t.dp(4);
            row.addView(body, blp);
            TextView emoji = text("💡", 20);
            emoji.setPadding(0, 0, t.dp(8), 0);
            body.addView(emoji);
        } else if ("bullet".equals(type)) {
            TextView dot = text("•", 22);
            dot.setGravity(Gravity.CENTER_HORIZONTAL);
            row.addView(dot, new LinearLayout.LayoutParams(t.dp(24), -2));
        } else if ("number".equals(type)) {
            TextView n = text(number + ".", 16);
            n.setGravity(Gravity.END);
            n.setPadding(0, t.dp(3), t.dp(6), 0);
            row.addView(n, new LinearLayout.LayoutParams(t.dp(28), -2));
        } else if ("todo".equals(type)) {
            CheckView chk = new CheckView(b.optBoolean("checked"));
            chk.setContentDescription(b.optBoolean("checked") ? "Tarea hecha" : "Tarea pendiente");
            row.addView(chk, new LinearLayout.LayoutParams(t.dp(30), t.dp(32)));
            chk.setOnClickListener(v -> {
                try { b.put("checked", !b.optBoolean("checked")); } catch (Exception ignored) { }
                changed();
                render(null, 0);
            });
        } else if ("quote".equals(type)) {
            View bar = new View(ctx);
            bar.setBackgroundColor(t.text);
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(t.dp(3), -1);
            qlp.topMargin = t.dp(4);
            qlp.bottomMargin = t.dp(4);
            qlp.rightMargin = t.dp(12);
            row.addView(bar, qlp);
        }

        final EditText et = editor(b, type);
        body.addView(et, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private EditText editor(final JSONObject b, final String type) {
        final EditText et = new EditText(ctx);
        et.setTag("editor");
        et.setBackground(null);
        et.setPadding(t.dp(2), t.dp(4), t.dp(2), t.dp(4));
        et.setTextColor(t.text);
        et.setHintTextColor(t.faint);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setHorizontallyScrolling(false);
        et.setIncludeFontPadding(false);
        float size = 17;
        switch (type) {
            case "h1": size = 30; et.setTypeface(t.bold); break;
            case "h2": size = 24; et.setTypeface(t.bold); break;
            case "h3": size = 20; et.setTypeface(t.medium); break;
            default: et.setTypeface(t.regular); break;
        }
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        et.setLineSpacing(t.px(size * 0.3f), 1f);
        et.setText(b.optString("text"));
        if (b.optBoolean("checked")) {
            et.setPaintFlags(et.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            et.setTextColor(t.faint);
        }
        final String hint = placeholder(type);
        if (!"text".equals(type)) et.setHint(hint);
        et.setOnFocusChangeListener((v, has) -> { if ("text".equals(type)) et.setHint(has ? hint : null); });

        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable s) {
                if (rebuilding) return;
                if ("/".equals(s.toString()) && "text".equals(type)) {
                    // Menú de tipos, como "/" en el visor del PC.
                    et.post(() -> { et.setText(""); blockMenu(et, b); });
                    return;
                }
                onEdit(b, s.toString());
            }
        });
        et.setOnKeyListener((v, keyCode, ev) -> {
            if (keyCode == KeyEvent.KEYCODE_DEL && ev.getAction() == KeyEvent.ACTION_DOWN
                    && et.getSelectionStart() == 0 && et.getSelectionEnd() == 0) {
                return onBackspaceAtStart(b);
            }
            return false;
        });
        return et;
    }

    private static String placeholder(String type) {
        switch (type) {
            case "h1": return "Encabezado 1";
            case "h2": return "Encabezado 2";
            case "h3": return "Encabezado 3";
            case "bullet": case "number": return "Lista";
            case "todo": return "Tarea";
            case "quote": return "Cita vacía";
            case "callout": return "Escribe algo…";
            default: return "Escribe, o «/» para elegir el tipo de bloque";
        }
    }

    // ---------------------------------------------------------------- edición

    private void onEdit(JSONObject b, String v) {
        String type = type(b);
        String id = b.optString("id");
        try {
            if (v.indexOf('\n') >= 0) {
                String[] lines = v.split("\n", -1);
                boolean list = "bullet".equals(type) || "number".equals(type) || "todo".equals(type);
                if (list && lines[0].isEmpty() && lines.length == 2 && lines[1].isEmpty()) {
                    setType(b, "text");
                    b.put("text", "");
                    changed();
                    render(id, 0);
                    return;
                }
                b.put("text", lines[0]);
                int at = indexOf(id);
                String nextType = list ? type : "text";
                String focus = id;
                for (int k = 1; k < lines.length; k++) {
                    JSONObject nb = Notes.block(nextType, lines[k]);
                    blocks.add(at + k, nb);
                    focus = nb.optString("id");
                }
                changed();
                render(focus, 0);
                return;
            }
            String[][] shortcuts = {{"### ", "h3"}, {"## ", "h2"}, {"# ", "h1"}, {"- ", "bullet"}, {"* ", "bullet"},
                {"1. ", "number"}, {"[] ", "todo"}, {"> ", "quote"}};
            for (String[] sc : shortcuts) {
                if (v.startsWith(sc[0]) && !sc[1].equals(type) && "text".equals(type)) {
                    setType(b, sc[1]);
                    b.put("text", v.substring(sc[0].length()));
                    changed();
                    render(id, b.optString("text").length());
                    return;
                }
            }
            if ("---".equals(v) && "text".equals(type)) {
                setType(b, "divider");
                int at = indexOf(id);
                JSONObject nb = Notes.block("text", "");
                blocks.add(at + 1, nb);
                changed();
                render(nb.optString("id"), 0);
                return;
            }
            b.put("text", v);
            changed();
        } catch (Exception ignored) { }
    }

    private boolean onBackspaceAtStart(JSONObject b) {
        String id = b.optString("id");
        int i = indexOf(id);
        if (i < 0) return false;
        if (!"text".equals(type(b))) {
            setType(b, "text");
            changed();
            render(id, 0);
            return true;
        }
        if (i == 0) return false;
        JSONObject prev = blocks.get(i - 1);
        if ("divider".equals(type(prev))) {
            blocks.remove(i - 1);
            changed();
            render(id, 0);
            return true;
        }
        // Una página de la tablet no se borra sin querer: sólo desde su menú.
        if ("tablet".equals(type(prev))) return true;
        String pt = prev.optString("text");
        try { prev.put("text", pt + b.optString("text")); } catch (Exception ignored) { }
        blocks.remove(i);
        changed();
        render(prev.optString("id"), pt.length());
        return true;
    }

    private void blockMenu(View anchor, final JSONObject b) {
        String[] samples = {"Aa", "H1", "H2", "H3", "•", "1.", "☐", "❝", "💡", "—", "✏️", "🗑"};
        String[] names = new String[TYPES.length + 1];
        int current = -1;
        for (int k = 0; k < TYPES.length; k++) {
            boolean same = TYPES[k][0].equals(type(b));
            if (same) current = k;
            names[k] = "tablet".equals(TYPES[k][0]) && same ? "Cambiar de página" : TYPES[k][1];
        }
        names[TYPES.length] = "Eliminar bloque";
        host.blockOptions(samples, names, current, which -> {
            String id = b.optString("id");
            if (which == TYPES.length) {
                int i = indexOf(id);
                if (i >= 0) blocks.remove(i);
                if (blocks.isEmpty()) blocks.add(Notes.block("text", ""));
                changed();
                render(null, 0);
            } else if ("tablet".equals(TYPES[which][0])) {
                host.pickTabletPage(b);
            } else {
                setType(b, TYPES[which][0]);
                changed();
                render(isText(b) ? id : null, b.optString("text").length());
            }
        });
    }

    private TextView text(String s, float sp) {
        TextView v = new TextView(ctx);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(t.text);
        v.setIncludeFontPadding(false);
        return v;
    }

    /** Casilla de una tarea, dibujada a mano. */
    private final class CheckView extends View {
        private final boolean checked;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();

        CheckView(boolean checked) {
            super(ctx);
            this.checked = checked;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override protected void onDraw(Canvas c) {
            float s = t.px(18), x = (getWidth() - s) / 2f, y = t.px(6);
            r.set(x, y, x + s, y + s);
            if (checked) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(t.accent);
                c.drawRoundRect(r, t.px(3), t.px(3), p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(t.px(2));
                p.setColor(0xFFFFFFFF);
                path.reset();
                path.moveTo(x + s * 0.22f, y + s * 0.52f);
                path.lineTo(x + s * 0.42f, y + s * 0.72f);
                path.lineTo(x + s * 0.8f, y + s * 0.3f);
                c.drawPath(path, p);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(t.px(1.5f));
                p.setColor(t.text);
                r.inset(t.px(0.75f), t.px(0.75f));
                c.drawRoundRect(r, t.px(3), t.px(3), p);
            }
        }
    }
}
