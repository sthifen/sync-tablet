package android.text;

/** Texto fijo con spans (SpannableString y SpannedString). */
abstract class SpannableStringInternal implements CharSequence, GetChars {
    final String text;
    final SpannableStringBuilder spans;

    SpannableStringInternal(CharSequence source, int start, int end) {
        text = source.subSequence(start, end).toString();
        spans = new SpannableStringBuilder(text);
        if (source instanceof Spanned) {
            Spanned sp = (Spanned) source;
            for (Object o : sp.getSpans(start, end, Object.class)) {
                if (o instanceof NoCopySpan) continue;
                int s = Math.max(start, sp.getSpanStart(o)), e = Math.min(end, sp.getSpanEnd(o));
                if (s > e) continue;
                spans.setSpan(o, s - start, e - start, sp.getSpanFlags(o));
            }
        }
    }

    @Override public final int length() { return text.length(); }
    @Override public final char charAt(int i) { return text.charAt(i); }
    @Override public final String toString() { return text; }
    @Override public final void getChars(int start, int end, char[] dest, int off) { text.getChars(start, end, dest, off); }

    public <T> T[] getSpans(int s, int e, Class<T> kind) { return spans.getSpans(s, e, kind); }
    public int getSpanStart(Object o) { return spans.getSpanStart(o); }
    public int getSpanEnd(Object o) { return spans.getSpanEnd(o); }
    public int getSpanFlags(Object o) { return spans.getSpanFlags(o); }
    public int nextSpanTransition(int s, int limit, Class kind) { return spans.nextSpanTransition(s, limit, kind); }
}
