package android.text;

public interface InputFilter {
    CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend);

    class LengthFilter implements InputFilter {
        private final int max;
        public LengthFilter(int max) { this.max = max; }
        public int getMax() { return max; }
        @Override public CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
            int keep = max - (dest.length() - (dend - dstart));
            if (keep <= 0) return "";
            if (keep >= end - start) return null;
            keep += start;
            if (Character.isHighSurrogate(source.charAt(keep - 1))) { --keep; if (keep == start) return ""; }
            return source.subSequence(start, keep);
        }
    }

    class AllCaps implements InputFilter {
        @Override public CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
            String s = source.subSequence(start, end).toString();
            String up = s.toUpperCase();
            return up.equals(s) ? null : up;
        }
    }
}
