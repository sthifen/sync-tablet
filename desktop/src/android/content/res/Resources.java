package android.content.res;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import java.util.HashMap;

public class Resources {
    public static final int ID_NULL = 0;

    public static class NotFoundException extends RuntimeException {
        public NotFoundException() {}
        public NotFoundException(String s) { super(s); }
    }

    public final class Theme {
        public void applyStyle(int id, boolean force) {}
        public boolean resolveAttribute(int attr, android.util.TypedValue out, boolean resolve) { return false; }
    }

    private static final Resources SYSTEM = new Resources();
    private static final HashMap<Integer, String> STRINGS = new HashMap<>();
    private static final HashMap<Integer, java.util.function.Supplier<Bitmap>> DRAWABLES = new HashMap<>();
    private static final HashMap<Integer, Bitmap> BITMAPS = new HashMap<>();

    final DisplayMetrics metrics = new DisplayMetrics();
    final Configuration config = new Configuration();

    public static Resources getSystem() { return SYSTEM; }

    /** El lanzador fija la densidad y el modo oscuro. */
    public static void setup(float density, boolean night, int widthPx, int heightPx) {
        DisplayMetrics m = SYSTEM.metrics;
        m.density = density;
        m.scaledDensity = density;
        m.densityDpi = Math.round(160 * density);
        m.xdpi = m.ydpi = m.densityDpi;
        resize(widthPx, heightPx);
        SYSTEM.config.uiMode = Configuration.UI_MODE_TYPE_NORMAL | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        SYSTEM.config.densityDpi = m.densityDpi;
    }

    public static void setNight(boolean night) {
        SYSTEM.config.uiMode = Configuration.UI_MODE_TYPE_NORMAL | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
    }

    public static void resize(int widthPx, int heightPx) {
        DisplayMetrics m = SYSTEM.metrics;
        m.widthPixels = widthPx;
        m.heightPixels = heightPx;
        Configuration c = SYSTEM.config;
        c.screenWidthDp = Math.round(widthPx / m.density);
        c.screenHeightDp = Math.round(heightPx / m.density);
        c.smallestScreenWidthDp = Math.min(c.screenWidthDp, c.screenHeightDp);
        c.orientation = widthPx >= heightPx ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
    }

    public static void registerString(int id, String s) { STRINGS.put(id, s); }
    public static void registerDrawable(int id, java.util.function.Supplier<Bitmap> s) { DRAWABLES.put(id, s); }

    public static Bitmap drawableBitmap(int id) {
        synchronized (BITMAPS) {
            Bitmap b = BITMAPS.get(id);
            if (b == null && DRAWABLES.containsKey(id)) { b = DRAWABLES.get(id).get(); BITMAPS.put(id, b); }
            return b;
        }
    }

    public DisplayMetrics getDisplayMetrics() { return metrics; }
    public Configuration getConfiguration() { return config; }
    public void updateConfiguration(Configuration c, DisplayMetrics m) { if (c != null) config.setTo(c); }

    public String getString(int id) {
        String s = STRINGS.get(id);
        if (s == null) throw new NotFoundException("String resource ID #0x" + Integer.toHexString(id));
        return s;
    }
    public String getString(int id, Object... args) { return String.format(getString(id), args); }
    public CharSequence getText(int id) { return getString(id); }
    public String getQuantityString(int id, int q, Object... args) { return getString(id, args); }
    public String[] getStringArray(int id) { return new String[0]; }
    public int getIdentifier(String name, String type, String pkg) { return 0; }
    public String getResourceName(int id) { return "id/" + id; }
    public String getResourceEntryName(int id) { return String.valueOf(id); }

    public Drawable getDrawable(int id) { return getDrawable(id, null); }
    public Drawable getDrawable(int id, Theme theme) {
        Bitmap b = drawableBitmap(id);
        if (b == null) throw new NotFoundException("Drawable resource ID #0x" + Integer.toHexString(id));
        return new BitmapDrawable(this, b);
    }
    public int getColor(int id) { return 0; }
    public int getColor(int id, Theme t) { return 0; }
    public ColorStateList getColorStateList(int id) { return ColorStateList.valueOf(0); }
    public float getDimension(int id) { return 0; }
    public int getDimensionPixelSize(int id) { return 0; }
    public int getDimensionPixelOffset(int id) { return 0; }
    public int getInteger(int id) { return 0; }
    public boolean getBoolean(int id) { return false; }
    public java.io.InputStream openRawResource(int id) { throw new NotFoundException(); }
    public Theme newTheme() { return new Theme(); }
    public AssetManager getAssets() { return new AssetManager(); }
}
