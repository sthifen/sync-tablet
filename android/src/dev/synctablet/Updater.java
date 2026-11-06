package dev.synctablet;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.IntConsumer;

/**
 * Actualizar desde la app: mira el último release de GitHub, baja el APK,
 * comprueba su SHA-256 y se lo pasa al instalador de Android, que además exige
 * la misma firma y pide confirmación. A GitHub solo se le pide el release.
 */
final class Updater {
    static final String DONE = "dev.synctablet.UPDATE_STATUS";
    private static final String LATEST = "https://api.github.com/repos/sthifen/sync-tablet/releases/latest";
    private static final String ASSETS = "https://github.com/sthifen/sync-tablet/releases/download/";

    static final class Release {
        String version, notes, apk, apkName, sums;
        long size;
    }

    private Updater() {
    }

    static String installed(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        h.setRequestProperty("User-Agent", "SyncTablet");
        if (h.getResponseCode() != 200) throw new IOException("GitHub respondió " + h.getResponseCode());
        return h;
    }

    private static String text(String url) throws IOException {
        HttpURLConnection h = open(url);
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            h.disconnect();
        }
    }

    /** El último release si trae APK y sumas; null si no sirve. */
    static Release latest() throws IOException, JSONException {
        JSONObject o = new JSONObject(text(LATEST));
        Release r = new Release();
        r.version = o.getString("tag_name").replaceFirst("^[vV]", "");
        String body = o.optString("body");
        int end = body.indexOf("\n\n");
        r.notes = (end > 0 ? body.substring(0, end) : body).replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1").trim();
        JSONArray assets = o.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String name = a.getString("name"), url = a.getString("browser_download_url");
            if (!url.startsWith(ASSETS)) continue;
            if (name.endsWith(".apk")) {
                r.apk = url;
                r.apkName = name;
                r.size = a.optLong("size");
            } else if (name.equals("SHA256SUMS")) {
                r.sums = url;
            }
        }
        return r.apk == null || r.sums == null ? null : r;
    }

    /** Baja el APK y solo lo devuelve si su suma coincide. `progress`: 0–100. */
    static File download(Context c, Release r, IntConsumer progress, java.util.concurrent.atomic.AtomicBoolean cancelled)
            throws IOException, NoSuchAlgorithmException {
        String want = Version.sum(text(r.sums), r.apkName);
        if (want == null) throw new IOException("el release no trae la suma del APK");
        File f = new File(c.getCacheDir(), "update.apk");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        HttpURLConnection h = open(r.apk);
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[1 << 16];
            long got = 0, total = r.size > 0 ? r.size : h.getContentLengthLong();
            int n, shown = -1;
            while ((n = in.read(buf)) > 0) {
                if (cancelled.get()) throw new IOException("cancelado");
                out.write(buf, 0, n);
                digest.update(buf, 0, n);
                got += n;
                int percent = total > 0 ? (int) (got * 100 / total) : 0;
                if (percent != shown) progress.accept(shown = percent);
            }
        } finally {
            h.disconnect();
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        if (!hex.toString().equals(want)) {
            f.delete();
            throw new IOException("la descarga no coincide con su suma");
        }
        return f;
    }

    /** Se lo entrega a Android; el resultado vuelve a MainActivity con la acción DONE. */
    static void install(Activity a, File apk) throws IOException {
        PackageInstaller installer = a.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(a.getPackageName());
        params.setSize(apk.length());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("update.apk", 0, apk.length())) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent back = new Intent(a, MainActivity.class).setAction(DONE).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getActivity(a, 7, back, flags).getIntentSender());
        }
    }
}
