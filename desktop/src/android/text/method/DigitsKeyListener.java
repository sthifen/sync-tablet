package android.text.method;

public class DigitsKeyListener implements KeyListener {
    private final String accepted;
    private final int type;
    private DigitsKeyListener(String a, int t) { accepted = a; type = t; }
    public static DigitsKeyListener getInstance() { return new DigitsKeyListener("0123456789", android.text.InputType.TYPE_CLASS_NUMBER); }
    public static DigitsKeyListener getInstance(String accepted) { return new DigitsKeyListener(accepted, android.text.InputType.TYPE_CLASS_TEXT); }
    public static DigitsKeyListener getInstance(boolean sign, boolean decimal) {
        return new DigitsKeyListener("0123456789" + (sign ? "-+" : "") + (decimal ? ".," : ""), android.text.InputType.TYPE_CLASS_NUMBER
                | (sign ? android.text.InputType.TYPE_NUMBER_FLAG_SIGNED : 0) | (decimal ? android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
    }
    public static DigitsKeyListener getInstance(java.util.Locale l, boolean sign, boolean decimal) { return getInstance(sign, decimal); }
    @Override public int getInputType() { return type; }
    public String accept(String s) { StringBuilder b = new StringBuilder(); for (char c : s.toCharArray()) if (accepted.indexOf(c) >= 0) b.append(c); return b.toString(); }
}
