package dev.synctablet;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/** Colores y piezas de interfaz comunes (sin XML de layouts). */
final class Ui {
    static final int BG = 0xFF0F1417;
    static final int BAR = 0xFF151C20;
    static final int SURFACE = 0xFF1C252A;
    static final int TEXT = 0xFFEEF3F4;
    static final int MUTED = 0xFF9AA9AE;
    static final int ACCENT = 0xFF2DD4BF;
    static final int ACCENT_TEXT = 0xFF062A26;
    static final int ON = 0xFF34D399;
    /** Sobre el cristal claro de la hoja. */
    static final int GLASS_LIGHT = 0x9EFFFFFF;
    static final int GLASS_DARK = 0x24FFFFFF;
    static final int ICON = 0xFF1F2937;
    static final int ICON_MUTED = 0xFF4B5563;
    /** Herramienta activa: grafito, el icono en blanco. */
    static final int ACCENT_DEEP = 0xFF1F2937;

    static final int[] COVERS = {0xFF0F766E, 0xFF2563EB, 0xFF7C3AED, 0xFFDB2777, 0xFFEA580C, 0xFFCA8A04, 0xFF16A34A, 0xFF475569};

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /**
     * Desde Android 15 las apps se dibujan bajo las barras del sistema: deja
     * su hueco sumándolo al relleno base de la vista.
     */
    @SuppressWarnings("deprecation")
    static void fitSystemBars(android.view.View v, int left, int top, int right, int bottom) {
        v.setOnApplyWindowInsetsListener((view, in) -> {
            view.setPadding(left + in.getSystemWindowInsetLeft(), top + in.getSystemWindowInsetTop(),
                    right + in.getSystemWindowInsetRight(), bottom + in.getSystemWindowInsetBottom());
            return in.consumeSystemWindowInsets();
        });
    }

    static void toast(Context c, String s) {
        android.widget.Toast.makeText(c, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** Botón de la barra: 44 dp de alto como mínimo, resaltado si está activo. */
    static TextView button(Context c, String s) {
        TextView b = text(c, s, 15, TEXT, false);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 44));
        b.setMinWidth(dp(c, 44));
        int h = dp(c, 14);
        b.setPadding(h, 0, h, 0);
        b.setClickable(true);
        b.setFocusable(true);
        setActive(b, false);
        return b;
    }

    static void setActive(TextView b, boolean active) {
        Context c = b.getContext();
        b.setBackground(round(active ? ACCENT : 0x00000000, dp(c, 12)));
        b.setTextColor(active ? ACCENT_TEXT : TEXT);
        b.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    /** Pantalla completa: sin barra de estado ni barra de apps abajo (se asoman deslizando desde el borde). */
    static boolean fullscreen(Context c) {
        return c.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("fullscreen", false);
    }

    static void setFullscreen(android.app.Activity a, boolean on) {
        a.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("fullscreen", on).apply();
        applyFullscreen(a);
    }

    @SuppressWarnings("deprecation")
    static void applyFullscreen(android.app.Activity a) {
        boolean on = fullscreen(a);
        android.view.View decor = a.getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController ic = a.getWindow().getInsetsController();
            if (ic == null) return;
            if (on) {
                ic.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                ic.hide(android.view.WindowInsets.Type.systemBars());
            } else {
                ic.show(android.view.WindowInsets.Type.systemBars());
            }
        } else {
            int keep = decor.getSystemUiVisibility() & (android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            int full = android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            decor.setSystemUiVisibility(keep | (on ? full : 0));
        }
    }

    private Ui() {}
}
