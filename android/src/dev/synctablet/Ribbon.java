package dev.synctablet;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * La cinta de las Craft apps (GridCraft, WordCraft, DeckCraft), con sus mismas
 * pestañas y grupos en el mismo orden: arriba la barra del título con el color
 * de la app, debajo las pestañas y, para la elegida, sus grupos con el nombre
 * debajo (Portapapeles, Fuente, Alineación…).
 */
final class Ribbon {
    /** Colores de las apps: GridCraft, WordCraft y DeckCraft. */
    static final int GRID = 0xFF1F9D55, WORD = 0xFF3B5BDB, DECK = 0xFFF26B1D;

    final LinearLayout view;
    private final Activity a;
    private final NoteTheme t;
    private final int accent;
    private final LinearLayout tabRow, content;
    private final ArrayList<TextView> tabViews = new ArrayList<>();
    private final ArrayList<ArrayList<Group>> tabs = new ArrayList<>();
    private int current;

    final class Group {
        final String label;
        final ArrayList<View> buttons = new ArrayList<>();

        Group(String label) {
            this.label = label;
        }

        /** Botón con icono y nombre corto debajo. */
        Group button(int icon, String name, Runnable run) {
            LinearLayout b = new LinearLayout(a);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setGravity(Gravity.CENTER);
            b.setPadding(t.dp(6), t.dp(2), t.dp(6), t.dp(2));
            b.setBackground(press());
            IconView ic = new IconView(a, icon, t.icon, name);
            ic.setClickable(false);
            b.addView(ic, new LinearLayout.LayoutParams(t.dp(30), t.dp(30)));
            TextView tv = Ui.text(a, name, 11, t.muted, false);
            tv.setGravity(Gravity.CENTER);
            tv.setMaxLines(2);
            tv.setLineSpacing(0, 0.9f);
            b.setMinimumWidth(t.dp(54));
            b.addView(tv, new LinearLayout.LayoutParams(-2, -2));
            b.setContentDescription(name);
            b.setOnClickListener(v -> run.run());
            buttons.add(b);
            return this;
        }

        /** Botón con letras (B, I, U, A+…) como en la fuente de Office. */
        TextView letter(String text, String name, Runnable run) {
            TextView b = Ui.text(a, text, 17, t.text, false);
            b.setGravity(Gravity.CENTER);
            b.setMinWidth(t.dp(40));
            b.setPadding(t.dp(8), 0, t.dp(8), 0);
            b.setBackground(press());
            b.setContentDescription(name);
            b.setOnClickListener(v -> run.run());
            buttons.add(b);
            return b;
        }

        /** Botón de texto (Normal, Título 1…), como la galería de estilos. */
        TextView word(String text, Runnable run) {
            TextView b = Ui.text(a, text, 13, t.text, false);
            b.setGravity(Gravity.CENTER);
            b.setPadding(t.dp(12), 0, t.dp(12), 0);
            GradientDrawable d = Ui.round(t.card, t.dp(6));
            d.setStroke(Math.max(1, t.dp(1)), t.line);
            b.setBackground(d);
            b.setOnClickListener(v -> run.run());
            buttons.add(b);
            return b;
        }
    }

    Ribbon(Activity a, NoteTheme t, int accent) {
        this.a = a;
        this.t = t;
        this.accent = accent;
        view = new LinearLayout(a);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackgroundColor(t.bar);
        HorizontalScrollView ts = new HorizontalScrollView(a);
        ts.setHorizontalScrollBarEnabled(false);
        tabRow = new LinearLayout(a);
        tabRow.setPadding(t.dp(8), 0, t.dp(8), 0);
        ts.addView(tabRow);
        view.addView(ts, new LinearLayout.LayoutParams(-1, t.dp(38)));
        HorizontalScrollView cs = new HorizontalScrollView(a);
        cs.setHorizontalScrollBarEnabled(false);
        cs.setBackgroundColor(t.dark ? 0xFF262626 : 0xFFFFFFFF);
        content = new LinearLayout(a);
        content.setPadding(t.dp(6), t.dp(4), t.dp(6), 0);
        cs.addView(content);
        view.addView(cs, new LinearLayout.LayoutParams(-1, t.dp(92)));
        View line = new View(a);
        line.setBackgroundColor(t.line);
        view.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, t.dp(1))));
    }

    private android.graphics.drawable.Drawable press() {
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(accent & 0xFFFFFF | 0x33000000), null,
                Ui.round(0xFFFFFFFF, t.dp(6)));
    }

    /** La pestaña «Archivo» del color de la app, que abre su menú (guardar, exportar…). */
    Ribbon fileTab(Runnable open) {
        TextView tv = Ui.text(a, "Archivo", 14, 0xFFFFFFFF, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(t.dp(16), 0, t.dp(16), 0);
        tv.setBackground(Ui.round(accent, t.dp(4)));
        tv.setOnClickListener(v -> open.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -1);
        lp.setMargins(0, t.dp(4), t.dp(6), t.dp(4));
        tabRow.addView(tv, lp);
        return this;
    }

    /** Una pestaña nueva; sus grupos se añaden con group(). */
    Ribbon tab(String name) {
        int index = tabs.size();
        tabs.add(new ArrayList<>());
        TextView tv = Ui.text(a, name, 14, t.muted, false);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(t.dp(14), 0, t.dp(14), 0);
        tv.setOnClickListener(v -> select(index));
        tabViews.add(tv);
        tabRow.addView(tv, new LinearLayout.LayoutParams(-2, -1));
        return this;
    }

    Group group(String label) {
        Group g = new Group(label);
        tabs.get(tabs.size() - 1).add(g);
        return g;
    }

    void select(int index) {
        current = index;
        for (int i = 0; i < tabViews.size(); i++) {
            TextView tv = tabViews.get(i);
            boolean on = i == index;
            tv.setTextColor(on ? accent : t.muted);
            tv.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            GradientDrawable under = new GradientDrawable();
            under.setColor(0);
            if (on) {
                // Raya del color de la app bajo la pestaña elegida.
                android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {
                        new android.graphics.drawable.ColorDrawable(accent)});
                ld.setLayerGravity(0, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
                ld.setLayerHeight(0, t.dp(3));
                ld.setLayerInset(0, t.dp(10), 0, t.dp(10), 0);
                tv.setBackground(ld);
            } else {
                tv.setBackground(null);
            }
        }
        content.removeAllViews();
        ArrayList<Group> groups = tabs.get(index);
        for (int gi = 0; gi < groups.size(); gi++) {
            Group g = groups.get(gi);
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            for (View b : g.buttons) {
                if (b.getParent() != null) ((LinearLayout) b.getParent()).removeView(b);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, t.dp(64));
                lp.rightMargin = t.dp(2);
                row.addView(b, lp);
            }
            col.addView(row, new LinearLayout.LayoutParams(-2, t.dp(66)));
            TextView label = Ui.text(a, g.label, 11, t.faint, false);
            label.setGravity(Gravity.CENTER);
            col.addView(label, new LinearLayout.LayoutParams(-1, -2));
            content.addView(col);
            if (gi < groups.size() - 1) {
                View sep = new View(a);
                sep.setBackgroundColor(t.line);
                LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(Math.max(1, t.dp(1)), t.dp(62));
                sl.setMargins(t.dp(8), t.dp(4), t.dp(8), 0);
                content.addView(sep, sl);
            }
        }
    }

    /** La barra del título con el color de la app (texto e iconos blancos). */
    static void paintTitleBar(Activity a, View bar, int accent) {
        bar.setBackgroundColor(accent);
        a.getWindow().setStatusBarColor(accent);
        // Iconos claros en la barra de estado, cuando la ventana ya existe.
        View decor = a.getWindow().getDecorView();
        decor.post(() -> {
            if (android.os.Build.VERSION.SDK_INT >= 30 && a.getWindow().getInsetsController() != null) {
                a.getWindow().getInsetsController().setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        });
    }
}
