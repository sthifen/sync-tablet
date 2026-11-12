package android.os;

import java.io.File;

public class Environment {
    public static final String DIRECTORY_DOWNLOADS = "Download", DIRECTORY_PICTURES = "Pictures", DIRECTORY_DOCUMENTS = "Documents", DIRECTORY_DCIM = "DCIM";
    public static final String MEDIA_MOUNTED = "mounted";

    public static File getExternalStorageDirectory() { return new File(System.getProperty("user.home")); }
    public static File getExternalStoragePublicDirectory(String type) { return new File(xdg(type)); }
    public static String getExternalStorageState() { return MEDIA_MOUNTED; }

    /** Las carpetas del usuario (Descargas, Imágenes, Documentos). */
    public static String xdg(String type) {
        String key = "Pictures".equals(type) || "DCIM".equals(type) ? "PICTURES" : "Documents".equals(type) ? "DOCUMENTS" : "DOWNLOAD";
        try {
            Process p = new ProcessBuilder("xdg-user-dir", key).redirectErrorStream(true).start();
            String s = new String(p.getInputStream().readAllBytes()).trim();
            if (!s.isEmpty() && new File(s).isDirectory()) return s;
        } catch (Exception ignored) {}
        File home = new File(System.getProperty("user.home"));
        String[] names = key.equals("PICTURES") ? new String[] {"Imágenes", "Pictures"} : key.equals("DOCUMENTS") ? new String[] {"Documentos", "Documents"} : new String[] {"Descargas", "Downloads"};
        for (String n : names) if (new File(home, n).isDirectory()) return new File(home, n).getPath();
        return home.getPath();
    }
}
