package dev.synctablet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Stickers para las hojas y las presentaciones: emojis grandes y etiquetas de
 * estudio. Se vuelven una imagen normal de la página (se mueven, giran y
 * exportan como cualquier foto).
 */
final class Stickers {
    private Stickers() {}

    static final String[] GROUPS = {"Estudio", "Caritas", "Ciencia", "Objetos", "Naturaleza", "Etiquetas"};
    static final String[][] ITEMS = {
        {"⭐", "✅", "❗", "❓", "📌", "📎", "💡", "🔥", "🎯", "🏆", "📚", "✏️", "📝", "🗓️", "⏰", "🧠", "👀", "💯", "➡️", "⬅️", "⬆️", "⬇️", "🔁", "✔️"},
        {"😀", "😄", "😎", "🤓", "🥳", "😴", "🤯", "😅", "😍", "🤔", "😬", "😭", "🥲", "😤", "🙌", "👍", "👏", "💪", "🤝", "✌️", "🫶", "❤️", "💙", "💚"},
        {"🧪", "⚗️", "🔬", "🔭", "🧬", "⚛️", "🧲", "⚡", "🔋", "💻", "🖥️", "🤖", "📐", "📏", "🧮", "📊", "📈", "📉", "🛰️", "🚀", "⚙️", "🔧", "🔩", "🧯"},
        {"🎧", "📷", "🎨", "🎵", "🎮", "☕", "🍕", "🍎", "🎁", "🎈", "🎓", "💼", "🏠", "🚗", "✈️", "⚽", "🏀", "🎸", "📱", "⌚", "🔑", "💰", "🛒", "🧸"},
        {"🌱", "🌻", "🌸", "🌈", "☀️", "🌙", "⛅", "❄️", "🌊", "🍀", "🌵", "🍁", "🐶", "🐱", "🦊", "🐼", "🐸", "🦋", "🐢", "🐝", "🦄", "🐧", "🐙", "🦖"},
        {"badge:Importante:#DC2626", "badge:Examen:#7C3AED", "badge:Repasar:#EA580C", "badge:Hecho:#16A34A", "badge:Tarea:#2563EB",
         "badge:Duda:#CA8A04", "badge:Fórmula:#0F766E", "badge:Definición:#475569", "badge:Ejemplo:#DB2777", "badge:Resumen:#0891B2",
         "badge:Pendiente:#9333EA", "badge:¡Ojo!:#E11D48"},
    };

    /** El sticker como imagen (`size` de alto); fondo transparente. */
    static Bitmap render(String s, int size) {
        if (s.startsWith("badge:")) {
            String[] p = s.split(":", 3);
            Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
            text.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            text.setTextSize(size * 0.42f);
            float tw = text.measureText(p[1]);
            int w = Math.round(tw + size * 0.9f);
            Bitmap b = Bitmap.createBitmap(w, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(Ink.parseColor(p[2], 0xFF0F766E));
            float pad = size * 0.06f;
            RectF r = new RectF(pad, pad, w - pad, size - pad);
            bg.setShadowLayer(size * 0.05f, 0, size * 0.03f, 0x40000000);
            c.drawRoundRect(r, size * 0.3f, size * 0.3f, bg);
            text.setColor(0xFFFFFFFF);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText(p[1], w / 2f, size / 2f - (text.ascent() + text.descent()) / 2, text);
            return b;
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size * 0.8f);
        p.setTextAlign(Paint.Align.CENTER);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawText(s, size / 2f, size / 2f - (p.ascent() + p.descent()) / 2, p);
        return b;
    }

    /** Muestra de un sticker para el dock. */
    static View preview(Context ctx, String s) {
        return new View(ctx) {
            Bitmap cached;

            @Override
            protected void onDraw(Canvas c) {
                int h = Math.min(getWidth(), getHeight());
                if (h <= 0) return;
                if (cached == null) cached = render(s, s.startsWith("badge:") ? h / 2 : h);
                float k = Math.min(getWidth() * 0.9f / cached.getWidth(), getHeight() * 0.9f / cached.getHeight());
                k = Math.min(k, 1f);
                float w = cached.getWidth() * k, hh = cached.getHeight() * k;
                c.drawBitmap(cached, null, new RectF((getWidth() - w) / 2, (getHeight() - hh) / 2, (getWidth() + w) / 2, (getHeight() + hh) / 2), null);
            }
        };
    }

    /** Dock con las pestañas de stickers; `pick` recibe el elegido. */
    static void choose(android.app.Activity a, OptionDock dock, int group, java.util.function.Consumer<String> pick) {
        java.util.ArrayList<OptionDock.Tile> tiles = new java.util.ArrayList<>();
        for (int g = 0; g < GROUPS.length; g++) {
            int gg = g;
            if (g == group) continue;
            tiles.add(new OptionDock.Tile(dock.letter(ITEMS[g][0].startsWith("badge:") ? "🏷️" : ITEMS[g][0]), "› " + GROUPS[g], false, () -> choose(a, dock, gg, pick)).stays());
        }
        for (String s : ITEMS[group]) tiles.add(new OptionDock.Tile(preview(a, s), "", false, () -> pick.accept(s)));
        dock.tiles("Stickers · " + GROUPS[group], tiles);
    }
}
