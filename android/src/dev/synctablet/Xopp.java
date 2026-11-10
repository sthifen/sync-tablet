package dev.synctablet;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Xml;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.zip.GZIPInputStream;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * Importa apuntes de Xournal++ (.xopp: XML comprimido con gzip, también .xoj).
 * Hecho a partir del formato del archivo: páginas, fondo, trazos con grosor por
 * punto, textos e imágenes. Los fondos de PDF de Xournal++ apuntan a un archivo
 * aparte y salen en blanco.
 */
final class Xopp {
    private Xopp() {}

    /** Páginas nuevas en `nb`; devuelve cuántas. */
    static int read(InputStream raw, Store store, Ink.Notebook nb) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw);
        in.mark(2);
        int b0 = in.read(), b1 = in.read();
        in.reset();
        InputStream xml = b0 == 0x1f && b1 == 0x8b ? new GZIPInputStream(in) : in;
        int count = 0;
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(xml, "UTF-8");
            Ink.Page page = null;
            float k = 1;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev != XmlPullParser.START_TAG) {
                    if (ev == XmlPullParser.END_TAG && "page".equals(p.getName()) && page != null) {
                        store.savePage(page);
                        page = null;
                    }
                    continue;
                }
                switch (p.getName()) {
                    case "page": {
                        float w = num(p.getAttributeValue(null, "width"), 595.27f);
                        k = Ink.W / Math.max(1, w);
                        page = store.addPage(nb);
                        page.background = Ink.PLAIN;
                        count++;
                        break;
                    }
                    case "background":
                        if (page != null) {
                            String style = p.getAttributeValue(null, "style");
                            if ("lined".equals(style) || "ruled".equals(style)) page.background = Ink.LINES;
                            else if ("graph".equals(style)) page.background = Ink.GRID;
                            else if ("dotted".equals(style)) page.background = Ink.DOTS;
                            else if ("staves".equals(style)) page.background = Ink.MUSIC;
                            else page.background = Ink.PLAIN;
                        }
                        break;
                    case "stroke":
                        if (page != null) stroke(p, page, k);
                        break;
                    case "text":
                        if (page != null) text(p, page, k);
                        break;
                    case "image":
                        if (page != null) image(p, page, k, store);
                        break;
                    default:
                        break;
                }
            }
            if (page != null) store.savePage(page);
        } catch (XmlPullParserException e) {
            throw new IOException("Xournal++ ilegible: " + e.getMessage(), e);
        }
        store.saveNotebooks();
        return count;
    }

    private static float num(String s, float fallback) {
        try {
            return s == null ? fallback : Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** #rrggbbaa o un nombre de los viejos .xoj. */
    private static int color(String s) {
        if (s == null) return 0xFF000000;
        s = s.trim();
        if (s.startsWith("#") && s.length() >= 7) {
            long v = Long.parseLong(s.substring(1, 7), 16);
            int a = s.length() >= 9 ? Integer.parseInt(s.substring(7, 9), 16) : 255;
            return a << 24 | (int) v;
        }
        switch (s) {
            case "blue": return 0xFF3333CC;
            case "red": return 0xFFFF0000;
            case "green": return 0xFF008000;
            case "gray": return 0xFF808080;
            case "lightblue": return 0xFF00C0FF;
            case "lightgreen": return 0xFF00FF00;
            case "magenta": return 0xFFFF00FF;
            case "orange": return 0xFFFF8000;
            case "yellow": return 0xFFFFFF00;
            case "white": return 0xFFFFFFFF;
            default: return 0xFF000000;
        }
    }

    private static void stroke(XmlPullParser p, Ink.Page page, float k) throws IOException, XmlPullParserException {
        String tool = p.getAttributeValue(null, "tool");
        int c = color(p.getAttributeValue(null, "color"));
        String[] widths = p.getAttributeValue(null, "width") == null ? new String[] {"1.41"} : p.getAttributeValue(null, "width").trim().split("\\s+");
        float base = num(widths[0], 1.41f);
        String body = p.nextText();
        String[] xy = body.trim().split("\\s+");
        boolean hl = "highlighter".equals(tool);
        if (hl) c = c & 0xFFFFFF | 0xFF000000;
        float width = Math.max(0.5f, base * k * (hl ? 1 : 0.9f));
        Ink.Stroke s = new Ink.Stroke(Ink.newId(), hl ? Ink.HIGHLIGHTER : Ink.PEN, c, width);
        for (int i = 0; i + 1 < xy.length; i += 2) {
            float pr = 0.7f;
            int wi = i / 2 + 1;
            if (widths.length > 1 && wi < widths.length) pr = Math.max(0.05f, Math.min(1f, num(widths[wi], base) / base * 0.7f));
            try {
                s.add(Float.parseFloat(xy[i]) * k, Float.parseFloat(xy[i + 1]) * k, pr);
            } catch (NumberFormatException ignored) {
                // un número roto no tumba la página
            }
        }
        if (s.n > 0) page.strokes.add(s);
    }

    private static void text(XmlPullParser p, Ink.Page page, float k) throws IOException, XmlPullParserException {
        Ink.Item it = new Ink.Item(Ink.newId(), "text");
        it.x = num(p.getAttributeValue(null, "x"), 0) * k;
        it.y = num(p.getAttributeValue(null, "y"), 0) * k;
        it.size = num(p.getAttributeValue(null, "size"), 12) * k;
        it.color = color(p.getAttributeValue(null, "color")) | 0xFF000000;
        it.text = p.nextText();
        it.w = Math.max(120, Math.min(Ink.W - it.x, it.text.length() * it.size * 0.55f + 20));
        it.h = it.size * 1.4f;
        page.items.add(it);
    }

    private static void image(XmlPullParser p, Ink.Page page, float k, Store store) throws IOException, XmlPullParserException {
        float l = num(p.getAttributeValue(null, "left"), 0), t = num(p.getAttributeValue(null, "top"), 0);
        float r = num(p.getAttributeValue(null, "right"), l + 100), b = num(p.getAttributeValue(null, "bottom"), t + 100);
        String data = p.nextText();
        byte[] bytes;
        try {
            bytes = Base64.decode(data.trim(), Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return;
        }
        Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bmp == null) return;
        Ink.Item it = new Ink.Item(Ink.newId(), "image");
        it.asset = store.putAsset(bmp);
        bmp.recycle();
        it.x = l * k;
        it.y = t * k;
        it.w = (r - l) * k;
        it.h = (b - t) * k;
        page.items.add(it);
    }

    static ArrayList<String> extensions() {
        ArrayList<String> e = new ArrayList<>();
        e.add(".xopp");
        e.add(".xoj");
        return e;
    }
}
