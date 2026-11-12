package android.graphics;

import java.util.HashMap;
import java.util.Locale;

public class Color {
    public static final int BLACK = 0xFF000000, DKGRAY = 0xFF444444, GRAY = 0xFF888888, LTGRAY = 0xFFCCCCCC,
            WHITE = 0xFFFFFFFF, RED = 0xFFFF0000, GREEN = 0xFF00FF00, BLUE = 0xFF0000FF, YELLOW = 0xFFFFFF00,
            CYAN = 0xFF00FFFF, MAGENTA = 0xFFFF00FF, TRANSPARENT = 0;

    public static int alpha(int c) { return c >>> 24; }
    public static int red(int c) { return (c >> 16) & 0xFF; }
    public static int green(int c) { return (c >> 8) & 0xFF; }
    public static int blue(int c) { return c & 0xFF; }
    public static int rgb(int r, int g, int b) { return 0xFF000000 | (r << 16) | (g << 8) | b; }
    public static int argb(int a, int r, int g, int b) { return (a << 24) | (r << 16) | (g << 8) | b; }
    public static int rgb(float r, float g, float b) { return argb(1f, r, g, b); }
    public static int argb(float a, float r, float g, float b) {
        return ((int) (a * 255 + 0.5f) << 24) | ((int) (r * 255 + 0.5f) << 16) | ((int) (g * 255 + 0.5f) << 8) | (int) (b * 255 + 0.5f);
    }
    public static float luminance(int c) {
        double r = lin(red(c) / 255.0), g = lin(green(c) / 255.0), b = lin(blue(c) / 255.0);
        return (float) (0.2126 * r + 0.7152 * g + 0.0722 * b);
    }
    private static double lin(double v) { return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }

    private static final HashMap<String, Integer> NAMES = new HashMap<>();
    static {
        NAMES.put("black", BLACK); NAMES.put("darkgray", DKGRAY); NAMES.put("gray", GRAY); NAMES.put("lightgray", LTGRAY);
        NAMES.put("white", WHITE); NAMES.put("red", RED); NAMES.put("green", GREEN); NAMES.put("blue", BLUE);
        NAMES.put("yellow", YELLOW); NAMES.put("cyan", CYAN); NAMES.put("magenta", MAGENTA); NAMES.put("aqua", 0xFF00FFFF);
        NAMES.put("fuchsia", 0xFFFF00FF); NAMES.put("darkgrey", DKGRAY); NAMES.put("grey", GRAY); NAMES.put("lightgrey", LTGRAY);
        NAMES.put("lime", 0xFF00FF00); NAMES.put("maroon", 0xFF800000); NAMES.put("navy", 0xFF000080); NAMES.put("olive", 0xFF808000);
        NAMES.put("purple", 0xFF800080); NAMES.put("silver", 0xFFC0C0C0); NAMES.put("teal", 0xFF008080); NAMES.put("transparent", 0);
    }

    public static int parseColor(String s) {
        if (s.charAt(0) == '#') {
            long color = Long.parseLong(s.substring(1), 16);
            if (s.length() == 7) color |= 0xFF000000L;
            else if (s.length() != 9) throw new IllegalArgumentException("Unknown color");
            return (int) color;
        }
        Integer c = NAMES.get(s.toLowerCase(Locale.ROOT));
        if (c != null) return c;
        throw new IllegalArgumentException("Unknown color");
    }

    public static void RGBToHSV(int r, int g, int b, float[] hsv) {
        float[] t = java.awt.Color.RGBtoHSB(r, g, b, null);
        hsv[0] = t[0] * 360f; hsv[1] = t[1]; hsv[2] = t[2];
    }
    public static void colorToHSV(int c, float[] hsv) { RGBToHSV(red(c), green(c), blue(c), hsv); }
    public static int HSVToColor(float[] hsv) { return HSVToColor(0xFF, hsv); }
    public static int HSVToColor(int alpha, float[] hsv) {
        float h = hsv[0] % 360f; if (h < 0) h += 360f;
        int rgb = java.awt.Color.HSBtoRGB(h / 360f, Math.max(0, Math.min(1, hsv[1])), Math.max(0, Math.min(1, hsv[2])));
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }
}
