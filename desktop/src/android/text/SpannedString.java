package android.text;

public final class SpannedString extends SpannableStringInternal implements Spanned {
    public SpannedString(CharSequence source) { super(source, 0, source.length()); }
    private SpannedString(CharSequence source, int start, int end) { super(source, start, end); }
    public static SpannedString valueOf(CharSequence s) { return s instanceof SpannedString ? (SpannedString) s : new SpannedString(s); }
    @Override public CharSequence subSequence(int start, int end) { return new SpannedString(this, start, end); }
}
