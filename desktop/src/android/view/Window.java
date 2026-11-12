package android.view;

import android.graphics.drawable.Drawable;

/** La ventana de una actividad: su vista raíz (decor) y lo que Android deja tocar de ella. */
public class Window {
    public static final int FEATURE_NO_TITLE = 1, FEATURE_ACTION_BAR = 8;
    public static final int DECOR_CAPTION_SHADE_AUTO = 0;

    public interface Callback {}

    private final android.content.Context context;
    private final DecorView decor;
    private final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams();
    private int statusBarColor, navigationBarColor;
    private final WindowInsetsController insets = new WindowInsetsController() {
        private int behavior, appearance;
        @Override public void show(int types) {}
        @Override public void hide(int types) {}
        @Override public void setSystemBarsBehavior(int b) { behavior = b; }
        @Override public int getSystemBarsBehavior() { return behavior; }
        @Override public void setSystemBarsAppearance(int a, int m) { appearance = (appearance & ~m) | (a & m); }
        @Override public int getSystemBarsAppearance() { return appearance; }
    };

    public Window(android.content.Context c) {
        context = c;
        decor = new DecorView(c);
    }

    public final android.content.Context getContext() { return context; }
    public View getDecorView() { return decor; }
    public DecorView decor() { return decor; }
    public View peekDecorView() { return decor; }
    public View getCurrentFocus() { return decor.findFocus(); }
    @SuppressWarnings("unchecked")
    public <T extends View> T findViewById(int id) { return (T) decor.findViewById(id); }
    public void setContentView(View v) { decor.setContent(v, new ViewGroup.LayoutParams(-1, -1)); }
    public void setContentView(View v, ViewGroup.LayoutParams p) { decor.setContent(v, p); }
    public void addContentView(View v, ViewGroup.LayoutParams p) { decor.addView(v, p); }
    public WindowInsetsController getInsetsController() { return insets; }
    public void setDecorFitsSystemWindows(boolean f) {}
    public void setStatusBarColor(int c) { statusBarColor = c; }
    public int getStatusBarColor() { return statusBarColor; }
    public void setNavigationBarColor(int c) { navigationBarColor = c; }
    public int getNavigationBarColor() { return navigationBarColor; }
    public void setNavigationBarDividerColor(int c) {}
    public void setNavigationBarContrastEnforced(boolean e) {}
    public void setStatusBarContrastEnforced(boolean e) {}
    public void setBackgroundDrawable(Drawable d) { decor.setBackground(d); }
    public void setBackgroundDrawableResource(int id) {}
    public void addFlags(int f) { attrs.flags |= f; }
    public void clearFlags(int f) { attrs.flags &= ~f; }
    public void setFlags(int f, int mask) { attrs.flags = (attrs.flags & ~mask) | (f & mask); }
    public void setSoftInputMode(int m) { attrs.softInputMode = m; }
    public WindowManager.LayoutParams getAttributes() { return attrs; }
    public void setAttributes(WindowManager.LayoutParams a) {}
    public void setLayout(int w, int h) {}
    public void setGravity(int g) {}
    public void setDimAmount(float d) {}
    public void setWindowAnimations(int a) {}
    public boolean requestFeature(int f) { return true; }
    public void setElevation(float e) {}
    public void setSustainedPerformanceMode(boolean e) {}
    public void setHideOverlayWindows(boolean h) {}
    public void setPreferMinimalPostProcessing(boolean p) {}
    public void setColorMode(int m) {}
    public void setFrameRateBoostOnTouchEnabled(boolean e) {}
    public WindowManager getWindowManager() { return null; }
    public void takeSurface(Object cb) {}
    public void setTitle(CharSequence t) {}
}
