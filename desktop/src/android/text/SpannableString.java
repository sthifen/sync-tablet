package android.text;

public class SpannableString extends SpannableStringInternal implements Spannable {
    public SpannableString(CharSequence source) { super(source, 0, source.length()); }
    public SpannableString(CharSequence source, int start, int end) { super(source, start, end); }
    public static SpannableString valueOf(CharSequence s) { return s instanceof SpannableString ? (SpannableString) s : new SpannableString(s); }
    @Override public void setSpan(Object what, int start, int end, int flags) { spans.setSpan(what, start, end, flags); }
    @Override public void removeSpan(Object what) { spans.removeSpan(what); }
    @Override public CharSequence subSequence(int start, int end) { return new SpannableString(this, start, end); }
}
