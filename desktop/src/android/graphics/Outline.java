package android.graphics;

public final class Outline {
    public final Rect rect = new Rect();
    public float radius, alpha = 1;
    public Path path;
    public boolean empty = true;

    public void setRect(int l, int t, int r, int b) { rect.set(l, t, r, b); radius = 0; empty = false; path = null; }
    public void setRect(Rect r) { setRect(r.left, r.top, r.right, r.bottom); }
    public void setRoundRect(int l, int t, int r, int b, float radius) { rect.set(l, t, r, b); this.radius = radius; empty = false; path = null; }
    public void setRoundRect(Rect r, float radius) { setRoundRect(r.left, r.top, r.right, r.bottom, radius); }
    public void setOval(int l, int t, int r, int b) { rect.set(l, t, r, b); radius = Math.min(r - l, b - t) / 2f; empty = false; path = null; }
    public void setPath(Path p) { path = p; empty = false; }
    public void setConvexPath(Path p) { setPath(p); }
    public void setAlpha(float a) { alpha = a; }
    public float getAlpha() { return alpha; }
    public void setEmpty() { empty = true; }
    public boolean isEmpty() { return empty; }
    public boolean canClip() { return true; }
}
