package android.graphics;

public class PorterDuffColorFilter extends ColorFilter {
    private final int color;
    private final PorterDuff.Mode mode;
    public PorterDuffColorFilter(int color, PorterDuff.Mode mode) { this.color = color; this.mode = mode; }

    @Override
    public int filter(int c) {
        int a = c >>> 24;
        switch (mode) {
            case SRC_IN: case SRC_ATOP: return ((a * (color >>> 24) / 255) << 24) | (color & 0xFFFFFF);
            case SRC: return color;
            case MULTIPLY: {
                int r = ((c >> 16) & 0xFF) * ((color >> 16) & 0xFF) / 255, g = ((c >> 8) & 0xFF) * ((color >> 8) & 0xFF) / 255, b = (c & 0xFF) * (color & 0xFF) / 255;
                return (a << 24) | (r << 16) | (g << 8) | b;
            }
            default: return c;
        }
    }
}
