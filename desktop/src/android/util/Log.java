package android.util;

/** Registro a la salida de errores. */
public final class Log {
    public static final int VERBOSE = 2, DEBUG = 3, INFO = 4, WARN = 5, ERROR = 6;
    private static final boolean VERBOSE_ON = System.getenv("SYNCTABLET_DEBUG") != null;

    private static int print(String level, String tag, String msg, Throwable tr) {
        if (!VERBOSE_ON && (level.equals("D") || level.equals("V"))) return 0;
        System.err.println(level + "/" + tag + ": " + msg);
        if (tr != null) tr.printStackTrace();
        return 0;
    }

    public static int v(String t, String m) { return print("V", t, m, null); }
    public static int v(String t, String m, Throwable e) { return print("V", t, m, e); }
    public static int d(String t, String m) { return print("D", t, m, null); }
    public static int d(String t, String m, Throwable e) { return print("D", t, m, e); }
    public static int i(String t, String m) { return print("I", t, m, null); }
    public static int i(String t, String m, Throwable e) { return print("I", t, m, e); }
    public static int w(String t, String m) { return print("W", t, m, null); }
    public static int w(String t, String m, Throwable e) { return print("W", t, m, e); }
    public static int w(String t, Throwable e) { return print("W", t, "", e); }
    public static int e(String t, String m) { return print("E", t, m, null); }
    public static int e(String t, String m, Throwable e) { return print("E", t, m, e); }
    public static String getStackTraceString(Throwable tr) {
        if (tr == null) return "";
        java.io.StringWriter sw = new java.io.StringWriter();
        tr.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private Log() {}
}
