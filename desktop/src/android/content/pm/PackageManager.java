package android.content.pm;

public class PackageManager {
    public static final int PERMISSION_GRANTED = 0, PERMISSION_DENIED = -1, GET_META_DATA = 128, MATCH_DEFAULT_ONLY = 0x10000;
    public static final String FEATURE_CAMERA = "android.hardware.camera", FEATURE_CAMERA_ANY = "android.hardware.camera.any",
            FEATURE_TOUCHSCREEN = "android.hardware.touchscreen", FEATURE_PC = "android.hardware.type.pc";
    public static final PackageManager INSTANCE = new PackageManager();

    public static class NameNotFoundException extends Exception {
        public NameNotFoundException() {}
        public NameNotFoundException(String s) { super(s); }
    }

    public PackageInfo getPackageInfo(String pkg, int flags) throws NameNotFoundException {
        PackageInfo p = new PackageInfo();
        p.packageName = pkg;
        p.versionName = System.getProperty("synctablet.version", "0.3.0");
        String[] parts = p.versionName.split("\\.");
        try { p.versionCode = Integer.parseInt(parts[0]) * 10000 + Integer.parseInt(parts[1]) * 100 + Integer.parseInt(parts[2]); } catch (Exception ignored) {}
        p.firstInstallTime = p.lastUpdateTime = System.currentTimeMillis();
        return p;
    }
    public PackageInfo getPackageInfo(String pkg, PackageInfoFlags flags) throws NameNotFoundException { return getPackageInfo(pkg, 0); }
    public static final class PackageInfoFlags { public static PackageInfoFlags of(long v) { return new PackageInfoFlags(); } }
    public PackageInstaller getPackageInstaller() { return new PackageInstaller(); }
    public boolean canRequestPackageInstalls() { return true; }
    public boolean hasSystemFeature(String f) { return FEATURE_PC.equals(f); }
    public ApplicationInfo getApplicationInfo(String pkg, int flags) { return new ApplicationInfo(); }
    public java.util.List<ResolveInfo> queryIntentActivities(android.content.Intent i, int flags) { return new java.util.ArrayList<>(); }
    public ResolveInfo resolveActivity(android.content.Intent i, int flags) { return null; }
    public android.content.Intent getLaunchIntentForPackage(String pkg) { return null; }
    public CharSequence getApplicationLabel(ApplicationInfo i) { return "Sync Tablet"; }
    public int checkPermission(String perm, String pkg) { return PERMISSION_GRANTED; }
}
