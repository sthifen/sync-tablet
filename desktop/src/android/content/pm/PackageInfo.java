package android.content.pm;

public class PackageInfo {
    public String packageName, versionName;
    public int versionCode;
    public long firstInstallTime, lastUpdateTime;
    public long getLongVersionCode() { return versionCode; }
}
