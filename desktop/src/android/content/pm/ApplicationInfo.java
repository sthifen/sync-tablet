package android.content.pm;

public class ApplicationInfo {
    public static final int FLAG_DEBUGGABLE = 2;
    public int flags, targetSdkVersion = 36, minSdkVersion = 26;
    public String packageName = "dev.synctablet", nativeLibraryDir = System.getProperty("java.library.path", ""), dataDir = android.content.Context.dataRoot().getPath(), sourceDir = "";
}
