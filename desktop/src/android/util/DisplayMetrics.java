package android.util;

public class DisplayMetrics {
    public static final int DENSITY_DEFAULT = 160, DENSITY_MEDIUM = 160, DENSITY_HIGH = 240, DENSITY_XHIGH = 320, DENSITY_XXHIGH = 480;
    public float density = 1, scaledDensity = 1, xdpi = 160, ydpi = 160;
    public int densityDpi = 160, widthPixels, heightPixels;
    public void setTo(DisplayMetrics o) { density = o.density; scaledDensity = o.scaledDensity; xdpi = o.xdpi; ydpi = o.ydpi; densityDpi = o.densityDpi; widthPixels = o.widthPixels; heightPixels = o.heightPixels; }
    public void setToDefaults() { density = 1; scaledDensity = 1; densityDpi = 160; }
}
