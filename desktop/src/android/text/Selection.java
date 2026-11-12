package android.text;

/** Inicio y fin de la selección guardados como spans (como en Android). */
public final class Selection {
    private static final class Start implements NoCopySpan {}
    private static final class End implements NoCopySpan {}
    public static final Object SELECTION_START = new Start(), SELECTION_END = new End();

    public static int getSelectionStart(CharSequence text) { return text instanceof Spanned ? ((Spanned) text).getSpanStart(SELECTION_START) : -1; }
    public static int getSelectionEnd(CharSequence text) { return text instanceof Spanned ? ((Spanned) text).getSpanStart(SELECTION_END) : -1; }

    public static void setSelection(Spannable text, int start, int stop) {
        int len = text.length();
        start = Math.max(0, Math.min(len, start));
        stop = Math.max(0, Math.min(len, stop));
        text.setSpan(SELECTION_START, start, start, Spanned.SPAN_POINT_POINT);
        text.setSpan(SELECTION_END, stop, stop, Spanned.SPAN_POINT_POINT);
    }

    public static void setSelection(Spannable text, int index) { setSelection(text, index, index); }
    public static void selectAll(Spannable text) { setSelection(text, 0, text.length()); }
    public static void extendSelection(Spannable text, int index) { text.setSpan(SELECTION_END, index, index, Spanned.SPAN_POINT_POINT); }
    public static void removeSelection(Spannable text) { text.removeSpan(SELECTION_START); text.removeSpan(SELECTION_END); }

    private Selection() {}
}
