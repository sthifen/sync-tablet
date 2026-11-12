package dev.synctablet;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Set;

/**
 * Personalizar la barra (concepto A): el panel de la derecha. La página, con su
 * barra de verdad, se ve en pequeño a la izquierda (PageActivity la encoge) y
 * cada toque aquí la cambia al momento.
 */
final class BarDesigner {
    interface Host {
        /** Algo cambió: rehacer la barra ya. */
        void barChanged();

        void closeDesigner();
    }

    static final int ACCENT = 0xFF0F766E, TEXT = 0xFF111827, MUTED = 0xFF4B5563, LINE = 0xFFE5E7EB, SOFT = 0xFFF0FDFA;

    private final Activity a;
    private final Host host;
    private final LinearLayout content;
    final ScrollView view;

    BarDesigner(Activity a, Host host) {
        this.a = a;
        this.host = host;
        view = new ScrollView(a);
        view.setVerticalScrollBarEnabled(false);
        view.setBackground(Ui.round(0xFFFFFFFF, dp(24)));
        view.setElevation(dp(10));
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(20), dp(22), dp(24));
        view.addView(content, new ScrollView.LayoutParams(-1, -2));
        render();
    }

    private int dp(float v) {
        return Ui.dp(a, v);
    }

    private void changed() {
        host.barChanged();
        render();
    }

    // ------------------------------------------------------------ armado

    private void render() {
        int scroll = view.getScrollY();
        content.removeAllViews();
        // Cabecera: título y «Listo».
        LinearLayout head = row();
        TextView title = Ui.text(a, "Personalizar la barra", 20, TEXT, true);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView done = pill("Listo", true);
        done.setOnClickListener(v -> host.closeDesigner());
        head.addView(done);
        content.addView(head);
        TextView hint = Ui.text(a, "Cada toque cambia la página de la izquierda al instante.", 13, MUTED, false);
        hint.setPadding(0, dp(4), 0, dp(6));
        content.addView(hint);

        // Estilo: cuatro tarjetas con dibujo.
        section("Estilo");
        String style = BarSettings.style(a);
        for (int r = 0; r < 2; r++) {
            LinearLayout line = row();
            for (int k = 0; k < 2; k++) {
                int i = r * 2 + k;
                String s = BarSettings.STYLES[i];
                LinearLayout card = new LinearLayout(a);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(10), dp(10), dp(10), dp(10));
                android.graphics.drawable.GradientDrawable bg = Ui.round(0xFFFFFFFF, dp(14));
                bg.setStroke(dp(2), s.equals(style) ? ACCENT : LINE);
                card.setBackground(bg);
                card.addView(new StyleSketch(a, s), new LinearLayout.LayoutParams(-1, dp(56)));
                TextView name = Ui.text(a, BarSettings.STYLE_NAMES[i], 13, TEXT, true);
                name.setPadding(0, dp(8), 0, 0);
                card.addView(name);
                card.setClickable(true);
                card.setContentDescription("Estilo " + BarSettings.STYLE_NAMES[i]);
                card.setOnClickListener(v -> {
                    BarSettings.preset(a, s);
                    changed();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                if (k == 0) lp.rightMargin = dp(10);
                line.addView(card, lp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(10);
            content.addView(line, lp);
        }

        // Plegable con pestañas: vale para cualquier estilo.
        boolean fold = BarSettings.fold(a);
        LinearLayout foldBox = new LinearLayout(a);
        foldBox.setOrientation(LinearLayout.VERTICAL);
        foldBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        foldBox.setBackground(Ui.round(SOFT, dp(14)));
        LinearLayout foldRow = row();
        LinearLayout foldText = new LinearLayout(a);
        foldText.setOrientation(LinearLayout.VERTICAL);
        foldText.addView(Ui.text(a, "Plegable con pestañas", 14, TEXT, true));
        foldText.addView(Ui.text(a, "Funciona con cualquier estilo", 12, MUTED, false));
        foldRow.addView(foldText, new LinearLayout.LayoutParams(0, -2, 1));
        Toggle t = new Toggle(a, fold);
        t.setContentDescription("Plegable con pestañas");
        t.setOnClickListener(v -> {
            BarSettings.prefs(a).edit().putBoolean(BarSettings.FOLD, !fold).apply();
            changed();
        });
        foldRow.addView(t, new LinearLayout.LayoutParams(dp(48), dp(28)));
        foldBox.addView(foldRow);
        if (fold) {
            boolean byPen = BarSettings.foldByPen(a);
            LinearLayout by = row();
            by.setPadding(0, dp(10), 0, 0);
            by.addView(Ui.text(a, "Se abre con", 12, MUTED, false), new LinearLayout.LayoutParams(0, -2, 1));
            TextView finger = pill("El dedo", !byPen), pen = pill("El lápiz cerca", byPen);
            finger.setOnClickListener(v -> {
                BarSettings.prefs(a).edit().putBoolean(BarSettings.FOLD_PEN, false).apply();
                changed();
            });
            pen.setOnClickListener(v -> {
                BarSettings.prefs(a).edit().putBoolean(BarSettings.FOLD_PEN, true).apply();
                changed();
            });
            by.addView(finger);
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
            gap.leftMargin = dp(6);
            by.addView(pen, gap);
            foldBox.addView(by);
            // Cuánto tarda en esconderse sola cuando ya no se usa.
            int hide = BarSettings.hideMs(a);
            LinearLayout after = row();
            after.setPadding(0, dp(10), 0, 0);
            after.addView(Ui.text(a, "Se esconde tras", 12, MUTED, false), new LinearLayout.LayoutParams(0, -2, 1));
            int[] times = {1000, 2500, 4000, 8000, 0};
            String[] names = {"1 s", "2,5 s", "4 s", "8 s", "Nunca"};
            for (int i = 0; i < times.length; i++) {
                int ms = times[i];
                TextView chip = pill(names[i], ms == hide);
                chip.setPadding(dp(8), dp(6), dp(8), dp(6));
                chip.setContentDescription(ms == 0 ? "No esconderse sola" : "Esconderse tras " + names[i]);
                chip.setOnClickListener(v -> {
                    BarSettings.prefs(a).edit().putInt(BarSettings.HIDE_MS, ms).apply();
                    changed();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.leftMargin = dp(4);
                after.addView(chip, lp);
            }
            foldBox.addView(after);
        }
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = dp(4);
        content.addView(foldBox, fl);

        // Posición (minimapa) a la izquierda; forma y colores a la derecha.
        LinearLayout mid = row();
        mid.setGravity(Gravity.TOP);
        LinearLayout posCol = new LinearLayout(a);
        posCol.setOrientation(LinearLayout.VERTICAL);
        posCol.addView(sectionLabel("Posición"));
        PosMap map = new PosMap(a, BarSettings.pos(a), p -> {
            BarSettings.prefs(a).edit().putString(BarSettings.POS, p).apply();
            changed();
        });
        posCol.addView(map, new LinearLayout.LayoutParams(dp(132), dp(94)));
        posCol.addView(Ui.text(a, "Toca un borde", 12, MUTED, false));
        mid.addView(posCol);
        LinearLayout right = new LinearLayout(a);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, -2, 1);
        rl.leftMargin = dp(18);
        mid.addView(right, rl);
        right.addView(sectionLabel("Forma"));
        LinearLayout shapes = row();
        int shape = BarSettings.shape(a);
        for (int i = 0; i < 3; i++) {
            int at = i;
            ShapeChip chip = new ShapeChip(a, i, i == shape);
            chip.setContentDescription("Forma " + new String[] {"píldora", "redondeada", "recta"}[i]);
            chip.setOnClickListener(v -> {
                BarSettings.prefs(a).edit().putInt(BarSettings.SHAPE, at).apply();
                changed();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(32), 1);
            if (i < 2) lp.rightMargin = dp(8);
            shapes.addView(chip, lp);
        }
        right.addView(shapes);
        TextView colorsLabel = sectionLabel("Colores");
        colorsLabel.setPadding(0, dp(12), 0, dp(6));
        right.addView(colorsLabel);
        LinearLayout cm = row();
        String mode = BarSettings.colorsMode(a);
        String[] modes = {BarSettings.C_SIDE, BarSettings.C_BAR, BarSettings.C_FLOAT};
        String[] modeNames = {"Al lado", "En barra", "Flotando"};
        for (int i = 0; i < 3; i++) {
            String m = modes[i];
            TextView p = pill(modeNames[i], m.equals(mode));
            p.setOnClickListener(v -> {
                BarSettings.prefs(a).edit().putString(BarSettings.COLORS, m).apply();
                changed();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = dp(6);
            cm.addView(p, lp);
        }
        right.addView(cm);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
        ml.topMargin = dp(16);
        content.addView(mid, ml);

        // Color de la barra: espectro, rápidos y el selector completo.
        section("Color de la barra");
        int color = BarSettings.color(a);
        HueStrip strip = new HueStrip(a, color, c -> {
            BarSettings.prefs(a).edit().putInt(BarSettings.COLOR, c).apply();
            host.barChanged();
        }, this::render);
        content.addView(strip, new LinearLayout.LayoutParams(-1, dp(30)));
        LinearLayout presets = row();
        presets.setPadding(0, dp(10), 0, 0);
        int[] quick = {0, 0xFFFFFFFF, 0xFF111827, 0xFF134E4A, 0xFF1E3A8A, 0xFF7C2D12, 0xFF831843};
        for (int q : quick) {
            View sw = new View(a);
            android.graphics.drawable.GradientDrawable d = Ui.round(q == 0 ? 0xFFF1F5F9 : q, dp(16));
            d.setStroke(dp(q == color ? 3 : 1), q == color ? ACCENT : LINE);
            sw.setBackground(d);
            sw.setContentDescription(q == 0 ? "Color automático" : "Color " + Ink.hex(q));
            sw.setOnClickListener(v -> {
                if (q == 0) BarSettings.prefs(a).edit().remove(BarSettings.COLOR).apply();
                else BarSettings.prefs(a).edit().putInt(BarSettings.COLOR, q).apply();
                changed();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
            lp.rightMargin = dp(8);
            presets.addView(sw, lp);
        }
        TextView more = pill("Más…", false);
        more.setOnClickListener(v -> ColorWheelHost.pick(a, color == 0 ? ACCENT : color, c -> {
            BarSettings.prefs(a).edit().putInt(BarSettings.COLOR, c | 0xFF000000).apply();
            changed();
        }));
        presets.addView(more);
        content.addView(presets);

        // Tamaño.
        section("Tamaño");
        LinearLayout size = row();
        boolean compact = BarSettings.compact(a);
        TextView small = pill("Compacta", compact), normal = pill("Normal", !compact);
        small.setOnClickListener(v -> {
            BarSettings.prefs(a).edit().putBoolean(BarSettings.COMPACT, true).apply();
            changed();
        });
        normal.setOnClickListener(v -> {
            BarSettings.prefs(a).edit().putBoolean(BarSettings.COMPACT, false).apply();
            changed();
        });
        size.addView(small);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
        gap.leftMargin = dp(6);
        size.addView(normal, gap);
        content.addView(size);

        // Botones: encendido = en la barra; apagado = en «⋯».
        section("Botones de la barra");
        TextView bh = Ui.text(a, "Apagados quedan en «⋯».", 12, MUTED, false);
        bh.setPadding(0, 0, 0, dp(6));
        content.addView(bh);
        Set<String> hidden = BarSettings.effectiveHidden(a);
        LinearLayout line = null;
        int n = 0;
        for (int i = 0; i < BarSettings.KEYS.length; i++) {
            String k = BarSettings.KEYS[i];
            if (k.equals("|")) continue;
            if (n % 6 == 0) {
                line = row();
                content.addView(line);
            }
            n++;
            boolean on = !hidden.contains(k);
            IconView ic = new IconView(a, BarSettings.ICONS[i], on ? TEXT : 0xFF9CA3AF, (on ? "Quitar de la barra: " : "Poner en la barra: ") + BarSettings.LABELS[i]);
            ic.setActive(on);
            ic.setOnClickListener(v -> {
                BarSettings.toggleHidden(a, k);
                changed();
            });
            line.addView(ic, new LinearLayout.LayoutParams(0, dp(46), 1));
        }
        for (int pad = n % 6; pad != 0 && pad < 6; pad++) line.addView(new View(a), new LinearLayout.LayoutParams(0, dp(46), 1));
        view.post(() -> view.scrollTo(0, scroll));
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private TextView sectionLabel(String s) {
        TextView t = Ui.text(a, s, 13, MUTED, true);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private void section(String s) {
        TextView t = sectionLabel(s);
        t.setPadding(0, dp(18), 0, dp(8));
        content.addView(t);
    }

    private TextView pill(String label, boolean on) {
        TextView t = Ui.text(a, label, 13, on ? 0xFFFFFFFF : TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        android.graphics.drawable.GradientDrawable d = Ui.round(on ? TEXT : 0xFFFFFFFF, dp(10));
        if (!on) d.setStroke(dp(1), 0xFFCBD5E1);
        t.setBackground(d);
        t.setClickable(true);
        return t;
    }

    // ------------------------------------------------------------ dibujos

    /** Una tablet en miniatura con la forma de cada estilo. */
    static final class StyleSketch extends View {
        private final String style;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        StyleSketch(Context c, String style) {
            super(c);
            this.style = style;
            setClickable(false);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFEEF2F6);
            c.drawRoundRect(new RectF(0, 0, w, h), 8 * d, 8 * d, p);
            p.setColor(0xFFFFFFFF);
            c.drawRect(14 * d, 14 * d, w - 14 * d, h, p);
            switch (style) {
                case BarSettings.SAMSUNG: {
                    p.setColor(0xFF134E4A);
                    RectF r = new RectF(w * 0.1f, 4 * d, w * 0.9f, 14 * d);
                    c.drawRoundRect(r, 5 * d, 5 * d, p);
                    int[] cs = {0xFFFFFFFF, 0xFF3B82F6, 0xFFEF4444, 0xFF22C55E};
                    for (int i = 0; i < cs.length; i++) {
                        p.setColor(cs[i]);
                        c.drawCircle(w * 0.55f + i * 8 * d, 9 * d, 2.6f * d, p);
                    }
                    break;
                }
                case BarSettings.MINIMAL:
                    p.setColor(0xFF94A3B8);
                    c.drawRoundRect(new RectF(w * 0.36f, 4 * d, w * 0.64f, 13 * d), 5 * d, 5 * d, p);
                    c.drawRoundRect(new RectF(4 * d, h * 0.35f, 10 * d, h * 0.85f), 3 * d, 3 * d, p);
                    break;
                case BarSettings.PEN: {
                    p.setColor(0xFFCBD5E1);
                    c.drawRoundRect(new RectF(w * 0.44f, 0, w * 0.56f, 7 * d), 3 * d, 3 * d, p);
                    c.drawRoundRect(new RectF(0, h * 0.45f, 5 * d, h * 0.75f), 2 * d, 2 * d, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.4f * d);
                    p.setColor(0x990F766E);
                    p.setPathEffect(new DashPathEffect(new float[] {4 * d, 3 * d}, 0));
                    c.drawRoundRect(new RectF(w * 0.2f, 9 * d, w * 0.8f, 21 * d), 6 * d, 6 * d, p);
                    p.setPathEffect(null);
                    break;
                }
                default:
                    p.setColor(0xFF94A3B8);
                    c.drawRoundRect(new RectF(w * 0.18f, 4 * d, w * 0.82f, 13 * d), 5 * d, 5 * d, p);
                    c.drawRoundRect(new RectF(4 * d, h * 0.35f, 10 * d, h * 0.85f), 3 * d, 3 * d, p);
            }
        }
    }

    interface Pick {
        void on(String pos);
    }

    /** La tablet vista de frente: tocar un borde pone ahí la barra. */
    static final class PosMap extends View {
        private final String pos;
        private final Pick pick;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        PosMap(Context c, String pos, Pick pick) {
            super(c);
            this.pos = pos;
            this.pick = pick;
            setContentDescription("Posición de la barra: " + pos);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            p.setColor(0xFFCBD5E1);
            c.drawRoundRect(new RectF(d, d, w - d, h - d), 12 * d, 12 * d, p);
            RectF[] r = {
                new RectF(w * 0.25f, 6 * d, w * 0.75f, 16 * d), new RectF(w * 0.25f, h - 16 * d, w * 0.75f, h - 6 * d),
                new RectF(6 * d, h * 0.25f, 16 * d, h * 0.75f), new RectF(w - 16 * d, h * 0.25f, w - 6 * d, h * 0.75f)};
            String[] names = {BarSettings.TOP, BarSettings.BOTTOM, BarSettings.LEFT, BarSettings.RIGHT};
            for (int i = 0; i < 4; i++) {
                boolean on = names[i].equals(pos);
                p.setStyle(on ? Paint.Style.FILL : Paint.Style.STROKE);
                p.setColor(on ? ACCENT : 0xFF94A3B8);
                p.setPathEffect(on ? null : new DashPathEffect(new float[] {4 * d, 3 * d}, 0));
                c.drawRoundRect(r[i], 5 * d, 5 * d, p);
            }
            p.setPathEffect(null);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
            float x = e.getX() / getWidth(), y = e.getY() / getHeight();
            // El borde más cercano al dedo.
            float[] dist = {y, 1 - y, x, 1 - x};
            String[] names = {BarSettings.TOP, BarSettings.BOTTOM, BarSettings.LEFT, BarSettings.RIGHT};
            int best = 0;
            for (int i = 1; i < 4; i++) if (dist[i] < dist[best]) best = i;
            pick.on(names[best]);
            return true;
        }
    }

    /** Muestra de la forma: píldora, redondeada o recta. */
    static final class ShapeChip extends View {
        private final int shape;
        private final boolean on;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        ShapeChip(Context c, int shape, boolean on) {
            super(c);
            this.shape = shape;
            this.on = on;
            setClickable(true);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            float r = shape == 0 ? h / 2 : shape == 1 ? 9 * d : 3 * d;
            p.setStyle(on ? Paint.Style.FILL : Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            p.setColor(on ? 0xFF134E4A : 0xFFCBD5E1);
            c.drawRoundRect(new RectF(d, d, w - d, h - d), r, r, p);
        }
    }

    /** Interruptor de encendido. */
    static final class Toggle extends View {
        private final boolean on;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Toggle(Context c, boolean on) {
            super(c);
            this.on = on;
            setClickable(true);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            p.setColor(on ? ACCENT : 0xFFCBD5E1);
            c.drawRoundRect(new RectF(0, 0, w, h), h / 2, h / 2, p);
            p.setColor(0xFFFFFFFF);
            float r = h / 2 - 3 * d;
            c.drawCircle(on ? w - h / 2 : h / 2, h / 2, r, p);
        }
    }

    interface ColorPick {
        void on(int color);
    }

    /** Espectro de tonos: se arrastra y la barra cambia mientras se arrastra. */
    static final class HueStrip extends View {
        private final ColorPick live;
        private final Runnable done;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float hue;
        private boolean has;

        HueStrip(Context c, int color, ColorPick live, Runnable done) {
            super(c);
            this.live = live;
            this.done = done;
            has = color != 0;
            float[] hsv = new float[3];
            Color.colorToHSV(color == 0 ? ACCENT : color, hsv);
            hue = hsv[0];
            setContentDescription("Tono de la barra");
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            int[] cs = new int[7];
            for (int i = 0; i < 7; i++) cs[i] = Color.HSVToColor(new float[] {i * 60f, 0.75f, 0.85f});
            p.setShader(new LinearGradient(0, 0, w, 0, cs, null, Shader.TileMode.CLAMP));
            float bar = h * 0.6f;
            c.drawRoundRect(new RectF(h / 2, (h - bar) / 2, w - h / 2, (h + bar) / 2), bar / 2, bar / 2, p);
            p.setShader(null);
            if (!has) return;
            float x = h / 2 + (w - h) * hue / 360f;
            p.setColor(0xFFFFFFFF);
            c.drawCircle(x, h / 2, h / 2 - d, p);
            p.setColor(color());
            c.drawCircle(x, h / 2, h / 2 - 4 * d, p);
        }

        private int color() {
            return Color.HSVToColor(new float[] {hue, 0.62f, 0.42f});
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float h = getHeight();
            hue = Math.max(0, Math.min(359.9f, (e.getX() - h / 2) / Math.max(1, getWidth() - h) * 360f));
            has = true;
            invalidate();
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            live.on(color());
            if (e.getActionMasked() == MotionEvent.ACTION_UP) done.run();
            return true;
        }
    }

    /** El selector completo (anillo y triángulo) en su propia ventana del dock. */
    static final class ColorWheelHost {
        static OptionDock dock;

        static void pick(Activity a, int initial, ColorPick use) {
            if (dock == null) return;
            ColorWheel.show(a, dock, "Color de la barra", initial, TEXT, MUTED, use::on);
        }
    }
}
