package android.util;

public class TypedValue {
    public static final int COMPLEX_UNIT_PX = 0, COMPLEX_UNIT_DIP = 1, COMPLEX_UNIT_SP = 2, COMPLEX_UNIT_PT = 3, COMPLEX_UNIT_IN = 4, COMPLEX_UNIT_MM = 5;
    public static final int TYPE_NULL = 0, TYPE_DIMENSION = 5;
    public int type, data, resourceId;

    public static float applyDimension(int unit, float value, DisplayMetrics m) {
        switch (unit) {
            case COMPLEX_UNIT_PX: return value;
            case COMPLEX_UNIT_DIP: return value * m.density;
            case COMPLEX_UNIT_SP: return value * m.scaledDensity;
            case COMPLEX_UNIT_PT: return value * m.xdpi * (1.0f / 72);
            case COMPLEX_UNIT_IN: return value * m.xdpi;
            case COMPLEX_UNIT_MM: return value * m.xdpi * (1.0f / 25.4f);
            default: return 0;
        }
    }

    public static float deriveDimension(int unit, float px, DisplayMetrics m) {
        switch (unit) {
            case COMPLEX_UNIT_DIP: return px / m.density;
            case COMPLEX_UNIT_SP: return px / m.scaledDensity;
            default: return px;
        }
    }

    public static float convertPixelsToDimension(int unit, float px, DisplayMetrics m) { return deriveDimension(unit, px, m); }
}
