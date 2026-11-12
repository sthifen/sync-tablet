package android.graphics;

public class Point {
    public int x, y;
    public Point() {}
    public Point(int x, int y) { this.x = x; this.y = y; }
    public void set(int x, int y) { this.x = x; this.y = y; }
    public void offset(int dx, int dy) { x += dx; y += dy; }
    @Override public boolean equals(Object o) { return o instanceof Point && ((Point) o).x == x && ((Point) o).y == y; }
    @Override public int hashCode() { return 31 * x + y; }
}
