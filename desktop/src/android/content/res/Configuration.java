package android.content.res;

import java.util.Locale;

public final class Configuration {
    public static final int ORIENTATION_UNDEFINED = 0, ORIENTATION_PORTRAIT = 1, ORIENTATION_LANDSCAPE = 2;
    public static final int UI_MODE_NIGHT_MASK = 0x30, UI_MODE_NIGHT_UNDEFINED = 0, UI_MODE_NIGHT_NO = 0x10, UI_MODE_NIGHT_YES = 0x20;
    public static final int UI_MODE_TYPE_MASK = 0x0f, UI_MODE_TYPE_NORMAL = 1, UI_MODE_TYPE_DESK = 2;
    public static final int SCREENLAYOUT_SIZE_MASK = 0x0f, SCREENLAYOUT_SIZE_XLARGE = 4, SCREENLAYOUT_SIZE_LARGE = 3, SCREENLAYOUT_SIZE_NORMAL = 2;
    public static final int KEYBOARD_QWERTY = 2, KEYBOARDHIDDEN_NO = 1, KEYBOARDHIDDEN_YES = 2, HARDKEYBOARDHIDDEN_NO = 1, HARDKEYBOARDHIDDEN_YES = 2;
    public static final int TOUCHSCREEN_NOTOUCH = 1, TOUCHSCREEN_FINGER = 3, NAVIGATION_NONAV = 1;
    public static final int SCREEN_WIDTH_DP_UNDEFINED = 0, DENSITY_DPI_UNDEFINED = 0;

    public int orientation = ORIENTATION_LANDSCAPE, uiMode = UI_MODE_TYPE_NORMAL | UI_MODE_NIGHT_NO, screenWidthDp, screenHeightDp, smallestScreenWidthDp,
            densityDpi = 160, screenLayout = SCREENLAYOUT_SIZE_XLARGE, keyboard = KEYBOARD_QWERTY, keyboardHidden = KEYBOARDHIDDEN_NO,
            hardKeyboardHidden = HARDKEYBOARDHIDDEN_NO, touchscreen = TOUCHSCREEN_FINGER, navigation = NAVIGATION_NONAV;
    public float fontScale = 1f;
    public Locale locale = Locale.getDefault();

    public Configuration() {}
    public Configuration(Configuration o) { setTo(o); }

    public void setTo(Configuration o) {
        orientation = o.orientation; uiMode = o.uiMode; screenWidthDp = o.screenWidthDp; screenHeightDp = o.screenHeightDp;
        smallestScreenWidthDp = o.smallestScreenWidthDp; densityDpi = o.densityDpi; fontScale = o.fontScale; locale = o.locale;
        screenLayout = o.screenLayout;
    }

    public boolean isNightModeActive() { return (uiMode & UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES; }
    public int getLayoutDirection() { return 0; }
    public android.os.LocaleList getLocales() { return new android.os.LocaleList(locale); }
    public void setLocale(Locale l) { locale = l; }
    public boolean isScreenRound() { return false; }
    public int diff(Configuration o) { return equals(o) ? 0 : -1; }
}
