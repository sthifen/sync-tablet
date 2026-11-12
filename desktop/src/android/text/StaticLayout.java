package android.text;

public class StaticLayout extends Layout {
    public static final class Builder {
        private final CharSequence text;
        private final int start, end;
        private final TextPaint paint;
        private final int width;
        private Alignment align = Alignment.ALIGN_NORMAL;
        private float mult = 1, add = 0;
        private boolean includePad = true;
        private int maxLines = Integer.MAX_VALUE, ellipsizedWidth;
        private TextUtils.TruncateAt ellipsize;

        private Builder(CharSequence t, int s, int e, TextPaint p, int w) { text = t; start = s; end = e; paint = p; width = w; ellipsizedWidth = w; }
        public static Builder obtain(CharSequence source, int start, int end, TextPaint paint, int width) { return new Builder(source, start, end, paint, width); }
        public Builder setText(CharSequence t) { return this; }
        public Builder setAlignment(Alignment a) { align = a; return this; }
        public Builder setLineSpacing(float spacingAdd, float spacingMult) { add = spacingAdd; mult = spacingMult; return this; }
        public Builder setIncludePad(boolean p) { includePad = p; return this; }
        public Builder setMaxLines(int m) { maxLines = m; return this; }
        public Builder setEllipsize(TextUtils.TruncateAt e) { ellipsize = e; return this; }
        public Builder setEllipsizedWidth(int w) { ellipsizedWidth = w; return this; }
        public Builder setTextDirection(TextDirectionHeuristic d) { return this; }
        public Builder setBreakStrategy(int b) { return this; }
        public Builder setHyphenationFrequency(int h) { return this; }
        public Builder setJustificationMode(int j) { return this; }
        public Builder setIndents(int[] l, int[] r) { return this; }
        public Builder setUseLineSpacingFromFallbacks(boolean u) { return this; }
        public Builder setLineBreakConfig(Object c) { return this; }
        public Builder setUseBoundsForWidth(boolean u) { return this; }
        public StaticLayout build() {
            CharSequence t = start == 0 && end == text.length() ? text : text.subSequence(start, end);
            return new StaticLayout(t, paint, width, align, mult, add, includePad, maxLines == Integer.MAX_VALUE ? 0 : maxLines, ellipsize, ellipsizedWidth);
        }
    }

    public StaticLayout(CharSequence source, TextPaint paint, int width, Alignment align, float spacingMult, float spacingAdd, boolean includePad) {
        this(source, paint, width, align, spacingMult, spacingAdd, includePad, 0, null, width);
    }

    public StaticLayout(CharSequence source, int start, int end, TextPaint paint, int width, Alignment align, float spacingMult, float spacingAdd, boolean includePad) {
        this(source.subSequence(start, end), paint, width, align, spacingMult, spacingAdd, includePad, 0, null, width);
    }

    private final int ellipsizedWidth;

    StaticLayout(CharSequence source, TextPaint paint, int width, Alignment align, float mult, float add, boolean includePad, int maxLines, TextUtils.TruncateAt ellipsize, int ellipsizedWidth) {
        super(source, paint, width, align, mult, add);
        this.ellipsizedWidth = ellipsizedWidth;
        build(includePad, maxLines, ellipsize, ellipsizedWidth);
    }

    @Override public int getEllipsizedWidth() { return ellipsizedWidth; }
}
