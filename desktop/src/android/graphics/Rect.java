package android.graphics;

public final class Rect {
    public int left, top, right, bottom;

    public Rect() {}
    public Rect(int l, int t, int r, int b) { left = l; top = t; right = r; bottom = b; }
    public Rect(Rect r) { if (r != null) set(r); }

    public void set(int l, int t, int r, int b) { left = l; top = t; right = r; bottom = b; }
    public void set(Rect r) { left = r.left; top = r.top; right = r.right; bottom = r.bottom; }
    public void setEmpty() { left = top = right = bottom = 0; }
    public boolean isEmpty() { return left >= right || top >= bottom; }
    public int width() { return right - left; }
    public int height() { return bottom - top; }
    public int centerX() { return (left + right) >> 1; }
    public int centerY() { return (top + bottom) >> 1; }
    public float exactCenterX() { return (left + right) * 0.5f; }
    public float exactCenterY() { return (top + bottom) * 0.5f; }
    public void offset(int dx, int dy) { left += dx; right += dx; top += dy; bottom += dy; }
    public void offsetTo(int x, int y) { right += x - left; bottom += y - top; left = x; top = y; }
    public void inset(int dx, int dy) { left += dx; top += dy; right -= dx; bottom -= dy; }
    public boolean contains(int x, int y) { return left < right && top < bottom && x >= left && x < right && y >= top && y < bottom; }
    public boolean contains(Rect r) { return left < right && top < bottom && left <= r.left && top <= r.top && right >= r.right && bottom >= r.bottom; }
    public boolean intersect(int l, int t, int r, int b) {
        if (left < r && l < right && top < b && t < bottom) {
            if (left < l) left = l; if (top < t) top = t; if (right > r) right = r; if (bottom > b) bottom = b;
            return true;
        }
        return false;
    }
    public boolean intersect(Rect r) { return intersect(r.left, r.top, r.right, r.bottom); }
    public boolean intersects(int l, int t, int r, int b) { return left < r && l < right && top < b && t < bottom; }
    public static boolean intersects(Rect a, Rect b) { return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom; }
    public void union(int l, int t, int r, int b) {
        if (l >= r || t >= b) return;
        if (left < right && top < bottom) {
            if (left > l) left = l; if (top > t) top = t; if (right < r) right = r; if (bottom < b) bottom = b;
        } else { left = l; top = t; right = r; bottom = b; }
    }
    public void union(Rect r) { union(r.left, r.top, r.right, r.bottom); }
    public void union(int x, int y) { if (x < left) left = x; else if (x > right) right = x; if (y < top) top = y; else if (y > bottom) bottom = y; }
    public void sort() { if (left > right) { int t = left; left = right; right = t; } if (top > bottom) { int t = top; top = bottom; bottom = t; } }
    @Override public boolean equals(Object o) { if (!(o instanceof Rect)) return false; Rect r = (Rect) o; return left == r.left && top == r.top && right == r.right && bottom == r.bottom; }
    @Override public int hashCode() { return 31 * (31 * (31 * left + top) + right) + bottom; }
    @Override public String toString() { return "Rect(" + left + ", " + top + " - " + right + ", " + bottom + ")"; }
}
