package android.view.inputmethod;

import android.view.View;

/** No hay teclado en pantalla: sólo se lleva la cuenta de qué campo lo pidió. */
public final class InputMethodManager {
    public static final int SHOW_IMPLICIT = 1, SHOW_FORCED = 2, HIDE_IMPLICIT_ONLY = 1, HIDE_NOT_ALWAYS = 2, RESULT_SHOWN = 2;
    private static View active;

    public static void focusChanged(View v) { active = v; }

    public boolean showSoftInput(View v, int flags) { return true; }
    public boolean showSoftInput(View v, int flags, android.os.ResultReceiver r) { return true; }
    public boolean hideSoftInputFromWindow(android.os.IBinder token, int flags) { return true; }
    public boolean hideSoftInputFromWindow(android.os.IBinder token, int flags, android.os.ResultReceiver r) { return true; }
    public void toggleSoftInput(int show, int hide) {}
    public boolean isActive() { return active != null; }
    public boolean isActive(View v) { return active == v; }
    public boolean isAcceptingText() { return active != null; }
    public void restartInput(View v) {}
    public void showInputMethodPicker() {}
    public void updateSelection(View v, int a, int b, int c, int d) {}
}
