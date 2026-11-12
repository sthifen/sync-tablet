package dev.synctablet;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Portapapeles compartido con el PC, como en Encaje: lo que copias en el PC
 * (texto o una captura) queda copiado en la tablet al momento, y lo que copias
 * en la tablet pasa al PC. El PC lee y pone el suyo (el de X11, que GNOME
 * mantiene igual que el de Wayland); la tablet, el de Android.
 *
 * Android sólo deja leer el portapapeles a la app que está delante: lo de la
 * tablet se manda al copiar dentro de la app o al volver a ella.
 */
final class Clip {
    /** Más grande no se manda: por el cable tardaría demasiado. */
    private static final int MAX = 24 << 20;
    /** Huella de lo último que pasó en cualquier sentido (para no devolverlo). */
    private static int last;
    private static boolean listening;

    private Clip() {
    }

    private static int print(byte[] b) {
        return java.util.Arrays.hashCode(b);
    }

    /** Lo copiado en el PC: se pone en el portapapeles de Android. */
    static void fromPc(Context app, JSONObject m) {
        // En el PC la app ya comparte el portapapeles del sistema.
        if (Updater.DESKTOP) return;
        ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
        try {
            if ("image".equals(m.optString("kind"))) {
                byte[] png = android.util.Base64.decode(m.getString("data"), android.util.Base64.DEFAULT);
                last = print(png);
                Uri uri = ClipProvider.save(app, png);
                ClipData clip = new ClipData(new ClipDescription("PC", new String[] {"image/png"}), new ClipData.Item(uri));
                cm.setPrimaryClip(clip);
            } else {
                String text = m.getString("data");
                last = print(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                cm.setPrimaryClip(ClipData.newPlainText("PC", text));
            }
        } catch (JSONException | IOException | IllegalArgumentException | SecurityException e) {
            android.util.Log.w("SyncTablet", "portapapeles del PC", e);
        }
    }

    /** Vigila lo que se copie mientras la app está delante. */
    static void listen(Context app) {
        if (listening || Updater.DESKTOP) return;
        listening = true;
        ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.addPrimaryClipChangedListener(() -> check(app));
    }

    /** Si hay algo nuevo copiado en la tablet, al PC. Sólo funciona con la app delante (Android). */
    static void check(Context app) {
        if (Updater.DESKTOP) return;
        SyncClient sync = SyncClient.get(app);
        if (!sync.isConnected()) return;
        ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip;
        try {
            clip = cm.getPrimaryClip();
        } catch (SecurityException e) {
            return;
        }
        if (clip == null || clip.getItemCount() == 0) return;
        ClipData.Item item = clip.getItemAt(0);
        ClipDescription d = clip.getDescription();
        String mime = d != null && d.getMimeTypeCount() > 0 ? d.getMimeType(0) : "";
        Uri uri = item.getUri();
        if (uri != null && (mime.startsWith("image/") || String.valueOf(app.getContentResolver().getType(uri)).startsWith("image/"))) {
            new Thread(() -> {
                byte[] img = read(app, uri);
                if (img == null) return;
                int p = print(img);
                if (p == last) return;
                last = p;
                send(sync, "image", android.util.Base64.encodeToString(img, android.util.Base64.NO_WRAP));
            }, "clip").start();
            return;
        }
        CharSequence text = item.getText();
        if (text == null) return;
        String s = text.toString();
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (b.length == 0 || b.length > MAX || print(b) == last) return;
        last = print(b);
        send(sync, "text", s);
    }

    private static void send(SyncClient sync, String kind, String data) {
        try {
            sync.send(new JSONObject().put("t", "clip").put("kind", kind).put("data", data).toString());
        } catch (JSONException ignored) {
        }
    }

    private static byte[] read(Context app, Uri uri) {
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > MAX) return null;
            }
            return out.toByteArray();
        } catch (IOException | SecurityException e) {
            return null;
        }
    }
}
