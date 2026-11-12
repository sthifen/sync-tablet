package android.content;

import android.content.res.Resources;
import java.io.File;

/** El contexto de la app en el escritorio: carpetas en ~/.local/share/synctablet y servicios básicos. */
public abstract class Context {
    public static final int MODE_PRIVATE = 0, MODE_APPEND = 0x8000, MODE_MULTI_PROCESS = 4;
    public static final int BIND_AUTO_CREATE = 1;
    public static final int RECEIVER_EXPORTED = 2, RECEIVER_NOT_EXPORTED = 4;
    public static final String INPUT_METHOD_SERVICE = "input_method", CLIPBOARD_SERVICE = "clipboard", WINDOW_SERVICE = "window",
            VIBRATOR_SERVICE = "vibrator", CONNECTIVITY_SERVICE = "connectivity", WIFI_SERVICE = "wifi", POWER_SERVICE = "power",
            LAYOUT_INFLATER_SERVICE = "layout_inflater", NOTIFICATION_SERVICE = "notification", AUDIO_SERVICE = "audio",
            DISPLAY_SERVICE = "display", ACTIVITY_SERVICE = "activity", ALARM_SERVICE = "alarm", DOWNLOAD_SERVICE = "download",
            STORAGE_SERVICE = "storage", SENSOR_SERVICE = "sensor", USB_SERVICE = "usb", UI_MODE_SERVICE = "uimode",
            VIBRATOR_MANAGER_SERVICE = "vibrator_manager", ACCESSIBILITY_SERVICE = "accessibility";

    public abstract Context getApplicationContext();

    public Resources getResources() { return Resources.getSystem(); }
    public android.content.res.AssetManager getAssets() { return getResources().getAssets(); }
    public Resources.Theme getTheme() { return getResources().newTheme(); }
    public void setTheme(int id) {}
    public ContentResolver getContentResolver() { return ContentResolver.INSTANCE; }
    public android.os.Looper getMainLooper() { return android.os.Looper.getMainLooper(); }
    public java.util.concurrent.Executor getMainExecutor() { return r -> android.os.MainQueue.post(this, r, 0, null); }
    public String getPackageName() { return "dev.synctablet"; }
    public String getOpPackageName() { return getPackageName(); }
    public android.content.pm.ApplicationInfo getApplicationInfo() { return new android.content.pm.ApplicationInfo(); }
    public android.content.pm.PackageManager getPackageManager() { return android.content.pm.PackageManager.INSTANCE; }
    public final String getString(int id) { return getResources().getString(id); }
    public final String getString(int id, Object... args) { return getResources().getString(id, args); }
    public final CharSequence getText(int id) { return getResources().getText(id); }
    public final int getColor(int id) { return getResources().getColor(id); }
    public final android.graphics.drawable.Drawable getDrawable(int id) { return getResources().getDrawable(id, null); }
    public final android.content.res.ColorStateList getColorStateList(int id) { return getResources().getColorStateList(id); }

    // ---------------------------------------------------------------- carpetas

    public static File dataRoot() {
        String xdg = System.getenv("XDG_DATA_HOME");
        File base = xdg != null && !xdg.isEmpty() ? new File(xdg) : new File(System.getProperty("user.home"), ".local/share");
        String override = System.getProperty("synctablet.data");
        File d = override != null ? new File(override) : new File(base, "synctablet");
        d.mkdirs();
        return d;
    }

    public File getFilesDir() { File f = new File(dataRoot(), "files"); f.mkdirs(); return f; }
    public File getNoBackupFilesDir() { File f = new File(dataRoot(), "no_backup"); f.mkdirs(); return f; }
    public File getDataDir() { return dataRoot(); }
    public File getCacheDir() {
        String xdg = System.getenv("XDG_CACHE_HOME");
        File base = xdg != null && !xdg.isEmpty() ? new File(xdg) : new File(System.getProperty("user.home"), ".cache");
        String override = System.getProperty("synctablet.data");
        File f = override != null ? new File(override, "cache") : new File(base, "synctablet");
        f.mkdirs();
        return f;
    }
    public File getCodeCacheDir() { return getCacheDir(); }
    public File getExternalFilesDir(String type) { File f = type == null ? new File(dataRoot(), "external") : new File(new File(dataRoot(), "external"), type); f.mkdirs(); return f; }
    public File[] getExternalFilesDirs(String type) { return new File[] {getExternalFilesDir(type)}; }
    public File getExternalCacheDir() { return getCacheDir(); }
    public File getDir(String name, int mode) { File f = new File(dataRoot(), "app_" + name); f.mkdirs(); return f; }
    public File getFileStreamPath(String name) { return new File(getFilesDir(), name); }
    public File getDatabasePath(String name) { File d = new File(dataRoot(), "databases"); d.mkdirs(); return new File(d, name); }
    public String[] fileList() { String[] l = getFilesDir().list(); return l == null ? new String[0] : l; }
    public java.io.FileInputStream openFileInput(String name) throws java.io.FileNotFoundException { return new java.io.FileInputStream(new File(getFilesDir(), name)); }
    public java.io.FileOutputStream openFileOutput(String name, int mode) throws java.io.FileNotFoundException { return new java.io.FileOutputStream(new File(getFilesDir(), name), (mode & MODE_APPEND) != 0); }
    public boolean deleteFile(String name) { return new File(getFilesDir(), name).delete(); }

    public SharedPreferences getSharedPreferences(String name, int mode) { return Prefs.get(name); }
    public boolean deleteSharedPreferences(String name) { return Prefs.delete(name); }

    // ---------------------------------------------------------------- servicios

    public Object getSystemService(String name) {
        switch (name) {
            case INPUT_METHOD_SERVICE: return new android.view.inputmethod.InputMethodManager();
            case CLIPBOARD_SERVICE: return ClipboardManager.INSTANCE;
            case VIBRATOR_SERVICE: return new android.os.Vibrator();
            case POWER_SERVICE: return new android.os.PowerManager();
            case CONNECTIVITY_SERVICE: return new android.net.ConnectivityManager();
            case WIFI_SERVICE: return new android.net.wifi.WifiManager();
            case UI_MODE_SERVICE: return new android.app.UiModeManager();
            default: return null;
        }
    }

    @SuppressWarnings("unchecked")
    public final <T> T getSystemService(Class<T> c) {
        if (c == android.view.inputmethod.InputMethodManager.class) return (T) getSystemService(INPUT_METHOD_SERVICE);
        if (c == ClipboardManager.class) return (T) ClipboardManager.INSTANCE;
        if (c == android.os.Vibrator.class) return (T) new android.os.Vibrator();
        if (c == android.os.PowerManager.class) return (T) new android.os.PowerManager();
        if (c == android.net.ConnectivityManager.class) return (T) new android.net.ConnectivityManager();
        if (c == android.net.wifi.WifiManager.class) return (T) new android.net.wifi.WifiManager();
        if (c == android.app.UiModeManager.class) return (T) new android.app.UiModeManager();
        return null;
    }

    public String getSystemServiceName(Class<?> c) { return null; }

    // ---------------------------------------------------------------- actividades

    public void startActivity(Intent i) { android.app.Desktop.get().startActivity(null, i, -1); }
    public void startActivity(Intent i, android.os.Bundle opts) { startActivity(i); }
    public void sendBroadcast(Intent i) {}
    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f) { return null; }
    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f, int flags) { return null; }
    public void unregisterReceiver(BroadcastReceiver r) {}
    public ComponentName startService(Intent i) { return null; }
    public ComponentName startForegroundService(Intent i) { return null; }
    public boolean stopService(Intent i) { return false; }
    public int checkSelfPermission(String p) { return android.content.pm.PackageManager.PERMISSION_GRANTED; }
    public int checkCallingOrSelfPermission(String p) { return android.content.pm.PackageManager.PERMISSION_GRANTED; }
    public void grantUriPermission(String pkg, android.net.Uri uri, int flags) {}
    public void revokeUriPermission(android.net.Uri uri, int flags) {}
    public Context createConfigurationContext(android.content.res.Configuration c) { return this; }
    public Context createDeviceProtectedStorageContext() { return this; }
    public boolean isDeviceProtectedStorage() { return false; }
    public android.content.res.TypedArray obtainStyledAttributes(int[] attrs) { return new android.content.res.TypedArray(); }
}
