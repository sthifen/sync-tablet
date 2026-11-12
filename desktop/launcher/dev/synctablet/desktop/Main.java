package dev.synctablet.desktop;

import android.app.Desktop;
import android.content.res.Resources;
import android.graphics.Bitmap;
import java.awt.Image;
import java.io.InputStream;
import javax.imageio.ImageIO;

/** Sync Tablet en el escritorio: la misma app de la tablet, en una ventana de Linux. */
public final class Main {
    public static void main(String[] args) throws Exception {
        // Java no escala por su cuenta: la app usa su densidad (dp) como en Android.
        System.setProperty("sun.java2d.uiScale", "1");
        System.setProperty("sun.java2d.uiScale.enabled", "false");
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        // Para la app: corre en el PC (el actualizador busca el AppImage, no el APK).
        System.setProperty("synctablet.desktop", "1");
        System.setProperty("sun.java2d.opengl", System.getProperty("sun.java2d.opengl", "false"));
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> android.util.Log.e("SyncTablet", "error sin atrapar en " + t.getName(), e));
        Image icon = null;
        try (InputStream in = Main.class.getResourceAsStream("/res/icon.png")) {
            if (in != null) icon = ImageIO.read(in);
        }
        final Image appIcon = icon;
        Resources.registerDrawable(dev.synctablet.R.drawable.ic_launcher, () -> appIcon == null
                ? Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                : Bitmap.wrap((java.awt.image.BufferedImage) appIcon));
        dev.synctablet.R.register();
        Server.start();
        Desktop.launch(dev.synctablet.MainActivity.class, "Sync Tablet", appIcon);
    }

    private Main() {}
}
