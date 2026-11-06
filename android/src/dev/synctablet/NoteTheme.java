package dev.synctablet;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;

/** Colores del editor de notas (los mismos que el visor web del PC), en modo claro y oscuro según el sistema. */
final class NoteTheme {
    final boolean dark;
    final int bg, card, bar, edge, text, muted, faint, icon, line, accent, callout, hover, paper, error;
    final float density;
    final Typeface regular, medium, bold;

    NoteTheme(Context c) {
        // 0 = como el sistema, 1 = claro, 2 = oscuro (se cambia desde la barra lateral).
        int forced = c.getSharedPreferences("ui", Context.MODE_PRIVATE).getInt("theme", 0);
        dark = forced == 2 || (forced == 0
                && (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        density = c.getResources().getDisplayMetrics().density;
        if (dark) {
            bg = 0xFF191919; card = 0xFF252525; bar = 0xFF202020; edge = 0xFF2F2F2F; text = 0xFFE3E2E0; muted = 0xFF9B9A97; faint = 0xFF7F7D7A;
            icon = 0xFF8F8D89; line = 0xFF373737; accent = 0xFF2DD4BF; callout = 0xFF2A2A2A; hover = 0x12FFFFFF; paper = 0xFF121212;
        } else {
            bg = 0xFFFFFEFC; card = 0xFFFFFFFF; bar = 0xFFF9F8F7; edge = 0xFFF0EFED; text = 0xFF2C2C2B; muted = 0xFF7D7A75; faint = 0xFFA19E99;
            icon = 0xFF8E8B86; line = 0xFFE6E5E3; accent = 0xFF0F766E; callout = 0xFFF0EFED; hover = 0x0F333333; paper = 0xFFECEBE8;
        }
        error = 0xFFEB5757;
        regular = Typeface.create("sans-serif", Typeface.NORMAL);
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        bold = Typeface.create("sans-serif", Typeface.BOLD);
    }

    int dp(float v) { return Math.round(v * density); }
    float px(float v) { return v * density; }
}
