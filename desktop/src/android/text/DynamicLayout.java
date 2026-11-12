package android.text;

/** Igual que StaticLayout; TextView la vuelve a hacer cuando cambia el texto. */
public class DynamicLayout extends Layout {
    public DynamicLayout(CharSequence base, TextPaint paint, int width, Alignment align, float mult, float add, boolean includePad) {
        super(base, paint, width, align, mult, add);
        build(includePad, 0, null, width);
    }
}
