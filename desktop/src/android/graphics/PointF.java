package android.graphics;

public class PointF {
    public float x, y;
    public PointF() {}
    public PointF(float x, float y) { this.x = x; this.y = y; }
    public void set(float x, float y) { this.x = x; this.y = y; }
    public void offset(float dx, float dy) { x += dx; y += dy; }
    public float length() { return (float) Math.hypot(x, y); }
    public static float length(float x, float y) { return (float) Math.hypot(x, y); }
}
