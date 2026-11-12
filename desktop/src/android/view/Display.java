package android.view;

import android.graphics.Point;

public class Display {
    public static final int DEFAULT_DISPLAY = 0;
    static final Display DEFAULT = new Display();
    public int getDisplayId() { return 0; }
    public int getRotation() { return Surface.ROTATION_0; }
    public float getRefreshRate() { return 60f; }
    public void getSize(Point p) { java.awt.Dimension d = screen(); p.set(d.width, d.height); }
    public void getRealSize(Point p) { getSize(p); }
    public void getMetrics(android.util.DisplayMetrics m) { m.setTo(android.content.res.Resources.getSystem().getDisplayMetrics()); }
    public void getRealMetrics(android.util.DisplayMetrics m) { getMetrics(m); }
    public int getWidth() { return screen().width; }
    public int getHeight() { return screen().height; }
    public int getState() { return 2; }
    public Mode getMode() { return new Mode(); }
    public Mode[] getSupportedModes() { return new Mode[] {new Mode()}; }
    public static final class Mode { public float getRefreshRate() { return 60f; } public int getModeId() { return 0; } public int getPhysicalWidth() { return screen().width; } public int getPhysicalHeight() { return screen().height; } }
    static java.awt.Dimension screen() {
        try { return java.awt.Toolkit.getDefaultToolkit().getScreenSize(); } catch (Throwable t) { return new java.awt.Dimension(1920, 1080); }
    }
}
