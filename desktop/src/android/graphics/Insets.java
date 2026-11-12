package android.graphics;

public final class Insets {
    public static final Insets NONE = new Insets(0, 0, 0, 0);
    public final int left, top, right, bottom;
    private Insets(int l, int t, int r, int b) { left = l; top = t; right = r; bottom = b; }
    public static Insets of(int l, int t, int r, int b) { return l == 0 && t == 0 && r == 0 && b == 0 ? NONE : new Insets(l, t, r, b); }
    public static Insets max(Insets a, Insets b) { return of(Math.max(a.left, b.left), Math.max(a.top, b.top), Math.max(a.right, b.right), Math.max(a.bottom, b.bottom)); }
}
