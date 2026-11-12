package android.view;

public interface WindowInsetsController {
    int BEHAVIOR_DEFAULT = 1, BEHAVIOR_SHOW_BARS_BY_TOUCH = 0, BEHAVIOR_SHOW_BARS_BY_SWIPE = 1, BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE = 2;
    int APPEARANCE_LIGHT_STATUS_BARS = 8, APPEARANCE_LIGHT_NAVIGATION_BARS = 16;
    void show(int types);
    void hide(int types);
    void setSystemBarsBehavior(int b);
    int getSystemBarsBehavior();
    void setSystemBarsAppearance(int appearance, int mask);
    int getSystemBarsAppearance();
}
