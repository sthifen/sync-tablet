package android.view;

public class LayoutInflater {
    private final android.content.Context context;
    public LayoutInflater(android.content.Context c) { context = c; }
    public static LayoutInflater from(android.content.Context c) { return new LayoutInflater(c); }
    public android.content.Context getContext() { return context; }
    public View inflate(int resource, ViewGroup root) { throw new UnsupportedOperationException("sin XML"); }
    public View inflate(int resource, ViewGroup root, boolean attach) { throw new UnsupportedOperationException("sin XML"); }
}
