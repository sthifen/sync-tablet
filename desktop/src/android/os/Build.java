package android.os;

public class Build {
    public static final String MANUFACTURER = "Linux", BRAND = "linux", MODEL = System.getProperty("synctablet.device", hostname()), DEVICE = "desktop", PRODUCT = "desktop", HARDWARE = "x86_64", FINGERPRINT = "linux/desktop";
    public static final String[] SUPPORTED_ABIS = {"x86_64"};

    public static class VERSION {
        public static final int SDK_INT = 36;
        public static final String RELEASE = "16", CODENAME = "REL";
    }

    public static class VERSION_CODES {
        public static final int O = 26, O_MR1 = 27, P = 28, Q = 29, R = 30, S = 31, S_V2 = 32, TIRAMISU = 33, UPSIDE_DOWN_CAKE = 34, VANILLA_ICE_CREAM = 35, BAKLAVA = 36;
    }

    static String hostname() {
        try {
            String h = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/etc/hostname"))).trim();
            if (!h.isEmpty()) return h;
        } catch (Exception ignored) {}
        try { return java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception e) { return "PC"; }
    }
}
