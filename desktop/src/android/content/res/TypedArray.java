package android.content.res;

public class TypedArray implements AutoCloseable {
    public int getColor(int i, int def) { return def; }
    public android.graphics.drawable.Drawable getDrawable(int i) { return null; }
    public int getDimensionPixelSize(int i, int def) { return def; }
    public float getDimension(int i, float def) { return def; }
    public void recycle() {}
    @Override public void close() {}
}
