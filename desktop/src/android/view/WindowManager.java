package android.view;

public interface WindowManager extends ViewManager {
    Display getDefaultDisplay();

    class LayoutParams extends ViewGroup.LayoutParams {
        public static final int FLAG_KEEP_SCREEN_ON = 0x80, FLAG_FULLSCREEN = 0x400, FLAG_LAYOUT_NO_LIMITS = 0x200, FLAG_SECURE = 0x2000,
                FLAG_DIM_BEHIND = 2, FLAG_NOT_FOCUSABLE = 8, FLAG_NOT_TOUCHABLE = 16, FLAG_LAYOUT_IN_SCREEN = 0x100, FLAG_TRANSLUCENT_STATUS = 0x04000000,
                FLAG_TRANSLUCENT_NAVIGATION = 0x08000000, FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS = 0x80000000, FLAG_SHOW_WHEN_LOCKED = 0x80000,
                FLAG_TURN_SCREEN_ON = 0x200000, FLAG_HARDWARE_ACCELERATED = 0x01000000;
        public static final int SOFT_INPUT_ADJUST_RESIZE = 0x10, SOFT_INPUT_ADJUST_PAN = 0x20, SOFT_INPUT_ADJUST_NOTHING = 0x30,
                SOFT_INPUT_STATE_HIDDEN = 2, SOFT_INPUT_STATE_ALWAYS_HIDDEN = 3, SOFT_INPUT_STATE_VISIBLE = 4, SOFT_INPUT_STATE_ALWAYS_VISIBLE = 5;
        public static final int LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT = 0, LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES = 1, LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS = 3;
        public static final float BRIGHTNESS_OVERRIDE_NONE = -1f;
        public static final int TYPE_APPLICATION = 2;
        public int flags, softInputMode, gravity, x, y, type, layoutInDisplayCutoutMode, windowAnimations;
        public float screenBrightness = -1f, dimAmount, alpha = 1f, preferredRefreshRate;
        public int preferredDisplayModeId;
        public LayoutParams() { super(MATCH_PARENT, MATCH_PARENT); }
        public LayoutParams(int w, int h) { super(w, h); }
    }
}
