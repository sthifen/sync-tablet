package android.text;

public class BoringLayout extends Layout {
    public static class Metrics extends android.graphics.Paint.FontMetricsInt { public int width; }
    public BoringLayout(CharSequence source, TextPaint paint, int width, Alignment align, float mult, float add, Metrics m, boolean includePad) {
        super(source, paint, width, align, mult, add);
        build(includePad, 0, null, width);
    }
    public static Metrics isBoring(CharSequence text, TextPaint paint) {
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') return null;
        Metrics m = new Metrics();
        paint.getFontMetricsInt(m);
        m.width = (int) Math.ceil(Layout.getDesiredWidth(text, paint));
        return m;
    }
}
