package android.provider;

import android.content.ContentResolver;

public final class Settings {
    public static final String ACTION_SETTINGS = "android.settings.SETTINGS", ACTION_WIFI_SETTINGS = "android.settings.WIFI_SETTINGS",
            ACTION_APPLICATION_DETAILS_SETTINGS = "android.settings.APPLICATION_DETAILS_SETTINGS",
            ACTION_MANAGE_UNKNOWN_APP_SOURCES = "android.settings.MANAGE_UNKNOWN_APP_SOURCES", ACTION_DISPLAY_SETTINGS = "android.settings.DISPLAY_SETTINGS",
            ACTION_INPUT_METHOD_SETTINGS = "android.settings.INPUT_METHOD_SETTINGS", ACTION_INTERNAL_STORAGE_SETTINGS = "android.settings.INTERNAL_STORAGE_SETTINGS",
            ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION = "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION", ACTION_BLUETOOTH_SETTINGS = "android.settings.BLUETOOTH_SETTINGS";

    public static final class Global {
        public static final String DEVICE_NAME = "device_name", ANIMATOR_DURATION_SCALE = "animator_duration_scale";
        public static String getString(ContentResolver r, String name) {
            if ("device_name".equals(name)) return android.os.Build.MODEL;
            return null;
        }
        public static float getFloat(ContentResolver r, String name, float def) { return def; }
        public static int getInt(ContentResolver r, String name, int def) { return def; }
    }

    public static final class Secure {
        public static final String ANDROID_ID = "android_id";
        public static String getString(ContentResolver r, String name) {
            if (ANDROID_ID.equals(name)) {
                try { return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/etc/machine-id"))).trim().substring(0, 16); }
                catch (Exception e) { return "0000000000000000"; }
            }
            return null;
        }
        public static int getInt(ContentResolver r, String name, int def) { return def; }
    }

    public static final class System {
        public static final String SCREEN_BRIGHTNESS = "screen_brightness";
        public static int getInt(ContentResolver r, String name, int def) { return def; }
        public static boolean canWrite(android.content.Context c) { return false; }
    }

    public static boolean canDrawOverlays(android.content.Context c) { return false; }
    private Settings() {}
}
