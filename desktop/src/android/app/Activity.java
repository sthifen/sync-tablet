package android.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.MainQueue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.window.OnBackInvokedDispatcher;

public class Activity extends ContextWrapper implements KeyEvent.Callback, Window.Callback, android.content.ComponentCallbacks2 {
    public static final int RESULT_OK = -1, RESULT_CANCELED = 0, RESULT_FIRST_USER = 1;
    public static final int DEFAULT_KEYS_DISABLE = 0;
    public static final int OVERRIDE_TRANSITION_OPEN = 0, OVERRIDE_TRANSITION_CLOSE = 1;

    Window window;
    Intent intent;
    Activity caller;
    int requestCode = -1;
    int resultCode = RESULT_CANCELED;
    Intent resultData;
    boolean finishing, destroyed, resumed, started, created;
    Object nonConfig;
    final OnBackInvokedDispatcher backDispatcher = new OnBackInvokedDispatcher();
    private int orientation = -1;
    private CharSequence title;

    public Activity() { super(null); }

    void attach(Intent i) {
        intent = i;
        window = new Window(this);
    }

    @Override public Context getApplicationContext() { return Application.get(); }
    public final Application getApplication() { return Application.get(); }

    // ---------------------------------------------------------------- ciclo de vida

    protected void onCreate(Bundle saved) { created = true; }
    protected void onPostCreate(Bundle saved) {}
    protected void onStart() {}
    protected void onRestart() {}
    protected void onResume() {}
    protected void onPostResume() {}
    protected void onPause() {}
    protected void onStop() {}
    protected void onDestroy() {}
    protected void onSaveInstanceState(Bundle out) {}
    protected void onRestoreInstanceState(Bundle saved) {}
    protected void onNewIntent(Intent i) {}
    protected void onActivityResult(int request, int result, Intent data) {}
    public void onRequestPermissionsResult(int request, String[] perms, int[] results) {}
    public void onConfigurationChanged(Configuration c) {}
    public void onWindowFocusChanged(boolean f) {}
    public void onAttachedToWindow() {}
    public void onDetachedFromWindow() {}
    public void onContentChanged() {}
    public void onUserInteraction() {}
    protected void onUserLeaveHint() {}
    public void onLowMemory() {}
    public void onTrimMemory(int level) {}
    public void onMultiWindowModeChanged(boolean m) {}
    public void onPictureInPictureModeChanged(boolean p) {}
    public void onTopResumedActivityChanged(boolean top) {}
    public Object onRetainNonConfigurationInstance() { return null; }
    public Object getLastNonConfigurationInstance() { return nonConfig; }

    // ---------------------------------------------------------------- ventana y contenido

    public Window getWindow() { return window; }
    public android.view.WindowManager getWindowManager() { return null; }
    public void setContentView(View v) { window.setContentView(v); onContentChanged(); }
    public void setContentView(View v, ViewGroup.LayoutParams p) { window.setContentView(v, p); onContentChanged(); }
    public void setContentView(int layout) { throw new UnsupportedOperationException("sin XML"); }
    public void addContentView(View v, ViewGroup.LayoutParams p) { window.addContentView(v, p); }
    @SuppressWarnings("unchecked")
    public <T extends View> T findViewById(int id) { return (T) window.findViewById(id); }
    public View getCurrentFocus() { return window.getCurrentFocus(); }
    public void setTitle(CharSequence t) { title = t; Desktop.get().titleChanged(this); }
    public void setTitle(int id) { setTitle(getString(id)); }
    public CharSequence getTitle() { return title; }
    public void setTitleColor(int c) {}
    public void setTaskDescription(Object d) {}
    public ActionBar getActionBar() { return null; }
    public void invalidateOptionsMenu() {}
    public boolean onCreateOptionsMenu(android.view.Menu m) { return false; }
    public boolean onOptionsItemSelected(android.view.MenuItem i) { return false; }
    public void openOptionsMenu() {}
    public void registerForContextMenu(View v) {}
    public void setRequestedOrientation(int o) { orientation = o; }
    public int getRequestedOrientation() { return orientation; }
    public void setShowWhenLocked(boolean s) {}
    public void setTurnScreenOn(boolean t) {}
    public void setFinishOnTouchOutside(boolean f) {}
    public void setVolumeControlStream(int s) {}
    public void reportFullyDrawn() {}
    public void setImmersive(boolean i) {}
    public boolean isInMultiWindowMode() { return false; }
    public boolean isInPictureInPictureMode() { return false; }
    public void enterPictureInPictureMode() {}
    public void overridePendingTransition(int enter, int exit) {}
    public void overrideActivityTransition(int type, int enter, int exit) {}
    public void overrideActivityTransition(int type, int enter, int exit, int bg) {}
    public void setDefaultKeyMode(int m) {}
    public boolean isChangingConfigurations() { return false; }
    public boolean isTaskRoot() { return Desktop.get().isRoot(this); }
    public boolean moveTaskToBack(boolean nonRoot) { Desktop.get().minimize(); return true; }
    public final boolean isResumed() { return resumed; }
    public ComponentName getComponentName() { return new ComponentName(this, getClass()); }
    public ComponentName getCallingActivity() { return caller == null ? null : caller.getComponentName(); }
    public String getCallingPackage() { return caller == null ? null : getPackageName(); }
    public android.view.LayoutInflater getLayoutInflater() { return new android.view.LayoutInflater(this); }
    public SharedPreferences getPreferences(int mode) { return getSharedPreferences(getClass().getSimpleName(), mode); }
    public OnBackInvokedDispatcher getOnBackInvokedDispatcher() { return backDispatcher; }
    public final void runOnUiThread(Runnable r) { if (MainQueue.isMain()) r.run(); else MainQueue.post(this, r, 0, null); }
    public final void requestPermissions(String[] perms, int request) {
        int[] granted = new int[perms.length];
        MainQueue.post(this, () -> onRequestPermissionsResult(request, perms, granted), 0, null);
    }
    public boolean shouldShowRequestPermissionRationale(String p) { return false; }
    public void startPostponedEnterTransition() {}
    public void postponeEnterTransition() {}
    public void setEnterSharedElementCallback(Object cb) {}

    // ---------------------------------------------------------------- intents

    public Intent getIntent() { return intent; }
    public void setIntent(Intent i) { intent = i; }
    @Override public void startActivity(Intent i) { Desktop.get().startActivity(this, i, -1); }
    @Override public void startActivity(Intent i, Bundle opts) { startActivity(i); }
    public void startActivityForResult(Intent i, int request) { Desktop.get().startActivity(this, i, request); }
    public void startActivityForResult(Intent i, int request, Bundle opts) { startActivityForResult(i, request); }
    public boolean startActivityIfNeeded(Intent i, int request) { startActivityForResult(i, request); return true; }
    public void startIntentSenderForResult(android.content.IntentSender s, int request, Intent fill, int mask, int values, int extra) {}
    public final void setResult(int code) { resultCode = code; resultData = null; }
    public final void setResult(int code, Intent data) { resultCode = code; resultData = data; }
    public void finish() { Desktop.get().finish(this); }
    public void finishAffinity() { Desktop.get().finishAll(); }
    public void finishAndRemoveTask() { finish(); }
    public void finishActivity(int request) {}
    public boolean isFinishing() { return finishing; }
    public boolean isDestroyed() { return destroyed; }
    public void recreate() { Desktop.get().recreate(this); }

    // ---------------------------------------------------------------- entrada

    public boolean dispatchKeyEvent(KeyEvent e) {
        onUserInteraction();
        if (window.decor().dispatchKeyEvent(e)) return true;
        return e.dispatch(this, null, this);
    }

    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) onUserInteraction();
        if (window.decor().dispatchTouchEvent(e)) return true;
        return onTouchEvent(e);
    }

    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (window.decor().dispatchGenericMotionEvent(e)) return true;
        return onGenericMotionEvent(e);
    }

    public boolean dispatchKeyShortcutEvent(KeyEvent e) { return false; }
    public boolean onTouchEvent(MotionEvent e) { return false; }
    public boolean onGenericMotionEvent(MotionEvent e) { return false; }
    public boolean onTrackballEvent(MotionEvent e) { return false; }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) { e.startTracking(); return true; }
        return false;
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) { Desktop.get().back(this); return true; }
        return false;
    }

    @Override public boolean onKeyLongPress(int code, KeyEvent e) { return false; }
    @Override public boolean onKeyMultiple(int code, int count, KeyEvent e) { return false; }
    public boolean onKeyShortcut(int code, KeyEvent e) { return false; }

    /** Atrás por defecto: cerrar. La primera actividad no se cierra (como Android con la de inicio). */
    public void onBackPressed() {
        if (Desktop.get().isRoot(this)) return;
        finish();
    }

    public void onBackInvoked() { onBackPressed(); }
}
