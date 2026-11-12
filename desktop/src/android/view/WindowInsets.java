package android.view;

import android.graphics.Insets;

/** En el escritorio no hay barras del sistema: todo a cero. */
public final class WindowInsets {
    public static final WindowInsets NONE = new WindowInsets(false);
    public static final WindowInsets CONSUMED = new WindowInsets(true);
    private final boolean consumed;
    private WindowInsets(boolean c) { consumed = c; }
    public WindowInsets(WindowInsets src) { consumed = src.consumed; }

    public static final class Type {
        public static int statusBars() { return 1; }
        public static int navigationBars() { return 2; }
        public static int captionBar() { return 4; }
        public static int ime() { return 8; }
        public static int systemGestures() { return 16; }
        public static int mandatorySystemGestures() { return 32; }
        public static int tappableElement() { return 64; }
        public static int displayCutout() { return 128; }
        public static int systemBars() { return 7; }
        private Type() {}
    }

    public static final class Builder {
        public Builder() {}
        public Builder(WindowInsets i) {}
        public Builder setInsets(int types, Insets i) { return this; }
        public Builder setSystemWindowInsets(Insets i) { return this; }
        public WindowInsets build() { return NONE; }
    }

    public Insets getInsets(int types) { return Insets.NONE; }
    public Insets getInsetsIgnoringVisibility(int types) { return Insets.NONE; }
    public boolean isVisible(int types) { return (types & Type.ime()) == 0; }
    public Insets getSystemWindowInsets() { return Insets.NONE; }
    public Insets getSystemGestureInsets() { return Insets.NONE; }
    public Insets getMandatorySystemGestureInsets() { return Insets.NONE; }
    public Insets getTappableElementInsets() { return Insets.NONE; }
    public Insets getStableInsets() { return Insets.NONE; }
    public int getSystemWindowInsetLeft() { return 0; }
    public int getSystemWindowInsetTop() { return 0; }
    public int getSystemWindowInsetRight() { return 0; }
    public int getSystemWindowInsetBottom() { return 0; }
    public int getStableInsetTop() { return 0; }
    public int getStableInsetBottom() { return 0; }
    public boolean hasSystemWindowInsets() { return false; }
    public boolean hasInsets() { return false; }
    public boolean isConsumed() { return consumed; }
    public WindowInsets consumeSystemWindowInsets() { return CONSUMED; }
    public WindowInsets consumeStableInsets() { return CONSUMED; }
    public WindowInsets consumeDisplayCutout() { return this; }
    public WindowInsets replaceSystemWindowInsets(int l, int t, int r, int b) { return this; }
    public WindowInsets inset(int l, int t, int r, int b) { return this; }
    public Object getDisplayCutout() { return null; }
}
