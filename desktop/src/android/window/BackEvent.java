package android.window;

public final class BackEvent {
    public static final int EDGE_LEFT = 0, EDGE_RIGHT = 1;
    public float getProgress() { return 0; }
    public float getTouchX() { return 0; }
    public float getTouchY() { return 0; }
    public int getSwipeEdge() { return EDGE_LEFT; }
}
