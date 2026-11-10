package dev.synctablet;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SweepGradient;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Selector de color como el de Krita: un anillo con todos los tonos y, dentro,
 * un triángulo con el tono puro, el blanco y el negro. Se arrastra en el anillo
 * para el tono y en el triángulo para lo claro y lo vivo; debajo, la muestra
 * antigua y la nueva, el código #RRGGBB y los últimos colores usados.
 */
final class ColorWheel extends View {
    interface Listener {
        void onColor(int color);
    }

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tri = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final float density;
    /** Tono (0–360), saturación y brillo (0–1). */
    private float hue, sat, val;
    private Bitmap triangle;
    private float triangleHue = -1;
    private int dragging; // 0 nada, 1 anillo, 2 triángulo
    private Listener listener;

    ColorWheel(Context c, int color) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        setColor(color);
        ring.setStyle(Paint.Style.STROKE);
        knob.setStyle(Paint.Style.STROKE);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        // Un gris no tiene tono: se queda el que había.
        if (hsv[1] > 0.001f && hsv[2] > 0.001f) hue = hsv[0];
        sat = hsv[1];
        val = hsv[2];
        invalidate();
    }

    int color() {
        return Color.HSVToColor(new float[] {hue, sat, val});
    }

    @Override
    protected void onMeasure(int w, int h) {
        int size = Math.min(MeasureSpec.getSize(w), Math.round(320 * density));
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        // Cambió el tamaño (el dock se anima al abrirse): repintar anillo y triángulo.
        ring.setShader(null);
        triangleHue = -1;
    }

    private float cx() { return getWidth() / 2f; }
    private float cy() { return getHeight() / 2f; }
    private float outer() { return Math.min(getWidth(), getHeight()) / 2f - 4 * density; }
    private float thickness() { return outer() * 0.16f; }
    private float inner() { return outer() - thickness() - 6 * density; }

    /** Vértices del triángulo: tono, blanco, negro (el del tono apunta a su sitio en el anillo). */
    private float[] vertices() {
        float r = inner(), a = (float) Math.toRadians(-hue);
        float[] v = new float[6];
        for (int k = 0; k < 3; k++) {
            double t = a + k * 2 * Math.PI / 3;
            v[2 * k] = cx() + r * (float) Math.cos(t);
            v[2 * k + 1] = cy() + r * (float) Math.sin(t);
        }
        return v;
    }

    @Override
    protected void onDraw(Canvas c) {
        float r = outer() - thickness() / 2;
        if (ring.getShader() == null || ring.getStrokeWidth() != thickness()) {
            int[] colors = new int[13];
            // El ángulo de la pantalla crece en sentido horario; el tono, al revés.
            for (int i = 0; i <= 12; i++) colors[i] = Color.HSVToColor(new float[] {(360 - i * 30) % 360, 1, 1});
            ring.setShader(new SweepGradient(cx(), cy(), colors, null));
            ring.setStrokeWidth(thickness());
        }
        c.drawCircle(cx(), cy(), r, ring);
        // Triángulo, pintado píxel a píxel cuando cambia el tono.
        float[] v = vertices();
        if (triangle == null || triangleHue != hue || triangle.getWidth() != getWidth() || triangle.getHeight() != getHeight()) paintTriangle(v);
        c.drawBitmap(triangle, 0, 0, tri);
        // Marcas.
        float a = (float) Math.toRadians(-hue);
        drawKnob(c, cx() + r * (float) Math.cos(a), cy() + r * (float) Math.sin(a), thickness() * 0.42f);
        float[] p = pointFor(v, sat, val);
        drawKnob(c, p[0], p[1], 9 * density);
    }

    private void drawKnob(Canvas c, float x, float y, float rad) {
        knob.setStrokeWidth(4 * density);
        knob.setColor(0x99000000);
        c.drawCircle(x, y, rad, knob);
        knob.setStrokeWidth(2.2f * density);
        knob.setColor(0xFFFFFFFF);
        c.drawCircle(x, y, rad, knob);
    }

    private void paintTriangle(float[] v) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (triangle == null || triangle.getWidth() != w || triangle.getHeight() != h) {
            if (triangle != null) triangle.recycle();
            triangle = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        int[] px = new int[w * h];
        int pure = Color.HSVToColor(new float[] {hue, 1, 1});
        int pr = Color.red(pure), pg = Color.green(pure), pb = Color.blue(pure);
        float x0 = v[0], y0 = v[1], x1 = v[2], y1 = v[3], x2 = v[4], y2 = v[5];
        float det = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
        int minX = (int) Math.max(0, Math.floor(Math.min(x0, Math.min(x1, x2))) - 1), maxX = (int) Math.min(w - 1, Math.ceil(Math.max(x0, Math.max(x1, x2))) + 1);
        int minY = (int) Math.max(0, Math.floor(Math.min(y0, Math.min(y1, y2))) - 1), maxY = (int) Math.min(h - 1, Math.ceil(Math.max(y0, Math.max(y1, y2))) + 1);
        float edge = 1.2f / Math.max(1, inner());
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                float wh = ((y1 - y2) * (x + 0.5f - x2) + (x2 - x1) * (y + 0.5f - y2)) / det;
                float ww = ((y2 - y0) * (x + 0.5f - x2) + (x0 - x2) * (y + 0.5f - y2)) / det;
                float wb = 1 - wh - ww;
                float m = Math.min(wh, Math.min(ww, wb));
                if (m < -edge) continue;
                int alpha = m >= 0 ? 255 : Math.round(255 * (1 + m / edge));
                wh = Math.max(0, wh);
                ww = Math.max(0, ww);
                float sum = wh + ww + Math.max(0, wb);
                if (sum <= 0) continue;
                wh /= sum;
                ww /= sum;
                int r = Math.min(255, Math.round(wh * pr + ww * 255));
                int g = Math.min(255, Math.round(wh * pg + ww * 255));
                int b = Math.min(255, Math.round(wh * pb + ww * 255));
                px[y * w + x] = alpha << 24 | r << 16 | g << 8 | b;
            }
        }
        triangle.setPixels(px, 0, w, 0, 0, w, h);
        triangleHue = hue;
    }

    /** Punto del triángulo para (s, v): negro + v · (blanco + s · (tono − blanco) − negro). */
    private static float[] pointFor(float[] t, float s, float v) {
        float wx = t[2] + s * (t[0] - t[2]), wy = t[3] + s * (t[1] - t[3]);
        return new float[] {t[4] + v * (wx - t[4]), t[5] + v * (wy - t[5])};
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        float d = (float) Math.hypot(x - cx(), y - cy());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = d >= outer() - thickness() - 10 * density && d <= outer() + 16 * density ? 1 : d < inner() + 6 * density ? 2 : 0;
                if (dragging == 0) return false;
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE:
                if (dragging == 1) {
                    float a = (float) Math.toDegrees(Math.atan2(y - cy(), x - cx()));
                    hue = ((-a) % 360 + 360) % 360;
                } else if (dragging == 2) {
                    pickInTriangle(x, y);
                }
                invalidate();
                if (listener != null) listener.onColor(color());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = 0;
                return true;
            default:
                return true;
        }
    }

    private void pickInTriangle(float x, float y) {
        float[] v = vertices();
        float x0 = v[0], y0 = v[1], x1 = v[2], y1 = v[3], x2 = v[4], y2 = v[5];
        float det = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
        float wh = ((y1 - y2) * (x - x2) + (x2 - x1) * (y - y2)) / det;
        float ww = ((y2 - y0) * (x - x2) + (x0 - x2) * (y - y2)) / det;
        float wb = 1 - wh - ww;
        // Fuera del triángulo: al punto más cercano del borde.
        wh = Math.max(0, wh);
        ww = Math.max(0, ww);
        wb = Math.max(0, wb);
        float sum = wh + ww + wb;
        wh /= sum;
        ww /= sum;
        wb /= sum;
        val = Math.max(0, Math.min(1, 1 - wb));
        sat = val <= 0.0001f ? sat : Math.max(0, Math.min(1, wh / val));
    }

    // ---- Diálogo ----

    private static final int RECENT = 12;

    /** Colores usados hace poco (los más nuevos primero). */
    static ArrayList<Integer> recent(Context c) {
        ArrayList<Integer> list = new ArrayList<>();
        String s = c.getSharedPreferences("tools", Context.MODE_PRIVATE).getString("recentColors", "");
        for (String part : s.split(",")) {
            if (part.isEmpty()) continue;
            try {
                list.add((int) Long.parseLong(part, 16));
            } catch (NumberFormatException ignored) {
            }
        }
        return list;
    }

    static void remember(Context c, int color) {
        ArrayList<Integer> list = recent(c);
        list.remove(Integer.valueOf(color));
        list.add(0, color);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(RECENT, list.size()); i++) {
            if (i > 0) b.append(',');
            b.append(Integer.toHexString(list.get(i)));
        }
        c.getSharedPreferences("tools", Context.MODE_PRIVATE).edit().putString("recentColors", b.toString()).apply();
    }

    /**
     * El selector en el dock de cristal: muestra antigua y nueva, código y
     * recientes. `use` recibe el color elegido al tocar «Usar».
     */
    static void show(Activity a, OptionDock dock, String heading, int initial, int textColor, int mutedColor, Listener use) {
        float dens = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Math.round(16 * dens);
        box.setPadding(pad, 0, pad, Math.round(6 * dens));

        ColorWheel wheel = new ColorWheel(a, initial);
        box.addView(wheel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(12 * dens), 0, Math.round(4 * dens));
        View old = new View(a), now = new View(a);
        float rad = 12 * dens;
        android.graphics.drawable.GradientDrawable od = new android.graphics.drawable.GradientDrawable();
        od.setColor(initial);
        od.setCornerRadii(new float[] {rad, rad, 0, 0, 0, 0, rad, rad});
        old.setBackground(od);
        old.setContentDescription("Color de antes");
        android.graphics.drawable.GradientDrawable nd = new android.graphics.drawable.GradientDrawable();
        nd.setColor(initial);
        nd.setCornerRadii(new float[] {0, 0, rad, rad, rad, rad, 0, 0});
        now.setBackground(nd);
        int sw = Math.round(56 * dens), sh = Math.round(40 * dens);
        row.addView(old, new LinearLayout.LayoutParams(sw, sh));
        row.addView(now, new LinearLayout.LayoutParams(sw, sh));
        EditText hex = new EditText(a);
        hex.setSingleLine(true);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setTextSize(16);
        hex.setTextColor(textColor);
        hex.setTypeface(android.graphics.Typeface.MONOSPACE);
        hex.setText(String.format(Locale.ROOT, "#%06X", initial & 0xFFFFFF));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(Math.round(130 * dens), LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.leftMargin = Math.round(14 * dens);
        row.addView(hex, hlp);
        box.addView(row);

        final boolean[] typing = {false};
        Listener changed = col -> {
            nd.setColor(col);
            if (!typing[0]) {
                typing[0] = true;
                hex.setText(String.format(Locale.ROOT, "#%06X", col & 0xFFFFFF));
                typing[0] = false;
            }
        };
        wheel.setListener(changed);
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                if (typing[0]) return;
                String s = e.toString().trim();
                if (!s.startsWith("#")) s = "#" + s;
                if (s.length() != 7) return;
                try {
                    int col = 0xFF000000 | Integer.parseInt(s.substring(1), 16);
                    typing[0] = true;
                    wheel.setColor(col);
                    nd.setColor(col);
                    typing[0] = false;
                } catch (NumberFormatException ignored) {
                }
            }
        });

        ArrayList<Integer> recent = recent(a);
        if (!recent.isEmpty()) {
            TextView label = Ui.text(a, "Recientes", 13, mutedColor, false);
            label.setPadding(0, Math.round(8 * dens), 0, Math.round(4 * dens));
            box.addView(label);
            LinearLayout rr = new LinearLayout(a);
            rr.setGravity(Gravity.CENTER);
            int dot = Math.round(28 * dens);
            for (int col : recent) {
                FrameLayout cell = new FrameLayout(a);
                View d = new View(a);
                d.setBackground(Ui.round(col, dot / 2f));
                cell.addView(d, new FrameLayout.LayoutParams(dot, dot, Gravity.CENTER));
                cell.setContentDescription(String.format(Locale.ROOT, "Color #%06X", col & 0xFFFFFF));
                cell.setOnClickListener(v -> {
                    wheel.setColor(col);
                    changed.onColor(col);
                });
                rr.addView(cell, new LinearLayout.LayoutParams(Math.round(40 * dens), Math.round(40 * dens)));
            }
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(rr);
            box.addView(hs);
        }
        dock.show(heading, box, new OptionDock.Action("Cancelar", false, null), new OptionDock.Action("Usar", true, () -> {
            int col = wheel.color();
            remember(a, col);
            use.onColor(col);
        }));
    }
}
