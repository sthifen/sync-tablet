package android.app;

public class UiModeManager {
    public static final int MODE_NIGHT_AUTO = 0, MODE_NIGHT_NO = 1, MODE_NIGHT_YES = 2;
    public int getNightMode() { return android.content.res.Resources.getSystem().getConfiguration().isNightModeActive() ? MODE_NIGHT_YES : MODE_NIGHT_NO; }
    public int getCurrentModeType() { return android.content.res.Configuration.UI_MODE_TYPE_DESK; }
    public void setApplicationNightMode(int m) {}
}
