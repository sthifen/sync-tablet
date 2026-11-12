package android.text;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.IdentityHashMap;

/** Texto editable con spans, con las reglas de Android para mover los extremos al editar. */
public class SpannableStringBuilder implements CharSequence, GetChars, Spannable, Editable, Appendable {
    static final class Span {
        final Object what;
        int start, end, flags;
        final long order;
        Span(Object w, int s, int e, int f, long o) { what = w; start = s; end = e; flags = f; order = o; }
    }

    private final StringBuilder text;
    final ArrayList<Span> spans = new ArrayList<>();
    private final IdentityHashMap<Object, Span> index = new IdentityHashMap<>();
    private long nextOrder;
    private InputFilter[] filters = new InputFilter[0];

    public SpannableStringBuilder() { this(""); }
    public SpannableStringBuilder(CharSequence text) { this(text, 0, text == null ? 0 : text.length()); }
    public SpannableStringBuilder(CharSequence src, int start, int end) {
        this.text = new StringBuilder(src == null ? "" : src.toString().substring(start, end));
        if (src instanceof Spanned) copySpans((Spanned) src, start, end, 0);
    }

    public static SpannableStringBuilder valueOf(CharSequence s) { return s instanceof SpannableStringBuilder ? (SpannableStringBuilder) s : new SpannableStringBuilder(s); }

    private void copySpans(Spanned src, int start, int end, int at) {
        Object[] all = src.getSpans(start, end, Object.class);
        for (Object o : all) {
            if (o instanceof NoCopySpan) continue;
            int s = src.getSpanStart(o), e = src.getSpanEnd(o), f = src.getSpanFlags(o);
            if (s < start) s = start;
            if (e > end) e = end;
            if (s > e) continue;
            // Un span vacío al borde no se copia, salvo que sea un punto.
            setSpanInternal(o, s - start + at, e - start + at, f, false);
        }
    }

    // ---------------------------------------------------------------- CharSequence

    @Override public int length() { return text.length(); }
    @Override public char charAt(int i) { return text.charAt(i); }
    @Override public String toString() { return text.toString(); }
    @Override public CharSequence subSequence(int start, int end) { return new SpannableStringBuilder(this, start, end); }
    @Override public void getChars(int start, int end, char[] dest, int off) { text.getChars(start, end, dest, off); }
    public String substring(int start, int end) { return text.substring(start, end); }
    public int getTextRunCursor(int contextStart, int contextEnd, boolean isRtl, int offset, int cursorOpt, android.graphics.Paint p) { return offset; }

    // ---------------------------------------------------------------- edición

    @Override public SpannableStringBuilder replace(int st, int en, CharSequence tb) { return replace(st, en, tb, 0, tb.length()); }

    @Override
    public SpannableStringBuilder replace(final int start, final int end, CharSequence tb, int tbstart, int tbend) {
        checkRange("replace", start, end);
        for (InputFilter f : filters) {
            CharSequence repl = f.filter(tb, tbstart, tbend, this, start, end);
            if (repl != null) { tb = repl; tbstart = 0; tbend = repl.length(); }
        }
        final int origLen = end - start, newLen = tbend - tbstart;
        if (origLen == 0 && newLen == 0) return this;
        TextWatcher[] watchers = getSpans(start, start + origLen, TextWatcher.class);
        for (TextWatcher w : watchers) w.beforeTextChanged(this, start, origLen, newLen);
        CharSequence insert = tb.subSequence(tbstart, tbend);
        change(start, end, insert);
        if (insert instanceof Spanned) copySpans((Spanned) insert, 0, insert.length(), start);
        for (TextWatcher w : watchers) w.onTextChanged(this, start, origLen, newLen);
        for (TextWatcher w : watchers) w.afterTextChanged(this);
        return this;
    }

    private void change(int start, int end, CharSequence insert) {
        int n = insert.length();
        int delta = n - (end - start);
        text.replace(start, end, insert.toString());
        ArrayList<Span> dead = null;
        for (Span s : new ArrayList<>(spans)) {
            int os = s.start, oe = s.end;
            boolean touched = os <= end && oe >= start;
            s.start = move(s.start, (s.flags & START_MASK) >> START_SHIFT, start, end, n, delta, true);
            s.end = move(s.end, s.flags & END_MASK, start, end, n, delta, false);
            if (s.end < s.start) s.end = s.start;
            if ((s.flags & SPAN_PARAGRAPH) == SPAN_PARAGRAPH) {
                // Los de párrafo siguen pegados a límites de párrafo.
                s.start = paragraphStart(s.start);
                s.end = paragraphEnd(s.end);
            }
            if (touched && s.start == s.end && (s.flags & SPAN_POINT_MARK_MASK) == SPAN_EXCLUSIVE_EXCLUSIVE && (end - start) > 0 && !(os == oe)) {
                if (dead == null) dead = new ArrayList<>();
                dead.add(s);
            }
        }
        if (dead != null) for (Span s : dead) removeSpanInternal(s.what, true);
    }

    private static final int START_MASK = 0xF0, END_MASK = 0x0F, START_SHIFT = 4, MARK = 1, POINT = 2, PARAGRAPH = 3;

    private static int move(int pos, int kind, int start, int end, int n, int delta, boolean isStart) {
        if (pos < start) return pos;
        if (pos > end) return pos + delta;
        if (start == end) {
            // Inserción pura: la marca se queda, el punto pasa detrás de lo nuevo.
            if (kind == POINT) return pos + n;
            if (kind == PARAGRAPH) return isStart ? pos : pos + n;
            return pos;
        }
        if (kind == POINT) return pos == start && n > 0 ? start : start + n;
        if (kind == PARAGRAPH) return isStart ? start : start + n;
        return pos == end && n > 0 ? start + n : start;
    }

    private int paragraphStart(int p) {
        if (p <= 0 || p > text.length()) return Math.max(0, Math.min(p, text.length()));
        return p;
    }

    private int paragraphEnd(int p) { return Math.max(0, Math.min(p, text.length())); }

    @Override public SpannableStringBuilder insert(int where, CharSequence tb, int start, int end) { return replace(where, where, tb, start, end); }
    @Override public SpannableStringBuilder insert(int where, CharSequence tb) { return replace(where, where, tb, 0, tb.length()); }
    @Override public SpannableStringBuilder delete(int start, int end) { return replace(start, end, "", 0, 0); }
    @Override public SpannableStringBuilder append(CharSequence t) { int n = length(); return replace(n, n, t, 0, t.length()); }
    public SpannableStringBuilder append(CharSequence t, Object what, int flags) { int s = length(); append(t); setSpan(what, s, length(), flags); return this; }
    @Override public SpannableStringBuilder append(CharSequence t, int start, int end) { int n = length(); return replace(n, n, t, start, end); }
    @Override public SpannableStringBuilder append(char c) { return append(String.valueOf(c)); }
    @Override public void clear() { replace(0, length(), "", 0, 0); }
    @Override public void clearSpans() { for (Span s : new ArrayList<>(spans)) removeSpanInternal(s.what, true); }
    @Override public void setFilters(InputFilter[] f) { if (f == null) throw new IllegalArgumentException(); filters = f; }
    @Override public InputFilter[] getFilters() { return filters; }

    private void checkRange(String op, int start, int end) {
        if (end < start) throw new IndexOutOfBoundsException(op + " " + start + " ... " + end + " has end before start");
        int len = length();
        if (start > len || end > len) throw new IndexOutOfBoundsException(op + " " + start + " ... " + end + " ends beyond length " + len);
        if (start < 0 || end < 0) throw new IndexOutOfBoundsException(op + " " + start + " ... " + end + " starts before 0");
    }

    // ---------------------------------------------------------------- spans

    @Override public void setSpan(Object what, int start, int end, int flags) { setSpanInternal(what, start, end, flags, true); }

    private void setSpanInternal(Object what, int start, int end, int flags, boolean notify) {
        checkRange("setSpan", start, end);
        Span s = index.get(what);
        if (s != null) {
            int os = s.start, oe = s.end;
            s.start = start; s.end = end; s.flags = flags;
            if (notify) for (SpanWatcher w : getSpans(Math.min(os, start), Math.max(oe, end), SpanWatcher.class)) w.onSpanChanged(this, what, os, oe, start, end);
            return;
        }
        s = new Span(what, start, end, flags, nextOrder++);
        spans.add(s);
        index.put(what, s);
        if (notify) for (SpanWatcher w : getSpans(start, end, SpanWatcher.class)) if (w != what) w.onSpanAdded(this, what, start, end);
    }

    @Override public void removeSpan(Object what) { removeSpanInternal(what, true); }
    public void removeSpan(Object what, int flags) { removeSpanInternal(what, true); }

    private void removeSpanInternal(Object what, boolean notify) {
        Span s = index.remove(what);
        if (s == null) return;
        spans.remove(s);
        if (notify) for (SpanWatcher w : getSpans(s.start, s.end, SpanWatcher.class)) w.onSpanRemoved(this, what, s.start, s.end);
    }

    @Override public int getSpanStart(Object what) { Span s = index.get(what); return s == null ? -1 : s.start; }
    @Override public int getSpanEnd(Object what) { Span s = index.get(what); return s == null ? -1 : s.end; }
    @Override public int getSpanFlags(Object what) { Span s = index.get(what); return s == null ? 0 : s.flags; }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T[] getSpans(int qs, int qe, Class<T> kind) {
        if (kind == null) kind = (Class<T>) Object.class;
        ArrayList<Span> hits = new ArrayList<>();
        for (Span s : spans) {
            if (!kind.isInstance(s.what)) continue;
            if (s.start > qe || s.end < qs) continue;
            if (s.start != s.end && qs != qe) {
                if (s.start == qe || s.end == qs) continue;
            }
            hits.add(s);
        }
        // Prioridad primero; luego el orden en que se pusieron.
        hits.sort((a, b) -> {
            int pa = (a.flags & SPAN_PRIORITY) >>> SPAN_PRIORITY_SHIFT, pb = (b.flags & SPAN_PRIORITY) >>> SPAN_PRIORITY_SHIFT;
            if (pa != pb) return Integer.compare(pb, pa);
            return Long.compare(a.order, b.order);
        });
        T[] out = (T[]) Array.newInstance(kind, hits.size());
        for (int i = 0; i < out.length; i++) out[i] = (T) hits.get(i).what;
        return out;
    }

    public <T> T[] getSpans(int qs, int qe, Class<T> kind, boolean sort) { return getSpans(qs, qe, kind); }

    @Override
    public int nextSpanTransition(int start, int limit, Class kind) {
        if (kind == null) kind = Object.class;
        for (Span s : spans) {
            if (!kind.isInstance(s.what)) continue;
            if (s.start > start && s.start < limit) limit = s.start;
            if (s.end > start && s.end < limit) limit = s.end;
        }
        return limit;
    }

    @Override public boolean equals(Object o) { return o instanceof Spanned && toString().equals(o.toString()) && o == this; }
    @Override public int hashCode() { return System.identityHashCode(this); }
}
