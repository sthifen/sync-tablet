package dev.synctablet;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.format.DateUtils;
import android.widget.LinearLayout;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Copia en Google Drive sin pedir cuentas ni claves: Android deja elegir un
 * archivo en Drive (o donde sea) y la app lo reescribe sola con un .zip de todo.
 * Recuperar agrega lo que falta, sin borrar nada de lo que ya hay.
 */
final class Backup {
    private static final int REQ_DEST = 47, REQ_RESTORE = 48;
    private static final long EVERY_MS = 10 * 60 * 1000L;
    private static volatile boolean running;

    private Backup() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("backup", Context.MODE_PRIVATE);
    }

    static void menu(Activity a, OptionDock dock) {
        SharedPreferences p = prefs(a);
        String uri = p.getString("uri", null);
        long last = p.getLong("last", 0);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), Ui.dp(a, 8));
        String status;
        if (uri == null) {
            status = "Elige un archivo en Google Drive y la tablet guardará ahí una copia de tus cuadernos, notas, hojas de cálculo y presentaciones, y la mantendrá al día sola.";
        } else {
            status = "Copia en «" + p.getString("name", "Drive") + "».\n"
                    + (last == 0 ? "Aún no se ha hecho." : "Última copia: " + DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) + ".")
                    + (p.getString("error", null) != null ? "\nÚltimo intento falló: " + p.getString("error", "") : "");
        }
        box.addView(dock.message(status));
        box.addView(dock.row(uri == null ? "Elegir archivo en Drive…" : "Cambiar de archivo…", v -> {
            dock.close();
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, "Sync Tablet - copia.zip");
            a.startActivityForResult(i, REQ_DEST);
        }));
        if (uri != null) box.addView(dock.row("Hacer la copia ahora", v -> {
            dock.close();
            now(a, true);
        }));
        box.addView(dock.row("Recuperar una copia…", v -> {
            dock.close();
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/x-zip-compressed", "application/octet-stream"});
            a.startActivityForResult(i, REQ_RESTORE);
        }));
        if (uri != null) box.addView(dock.row("Dejar de copiar", v -> {
            p.edit().clear().apply();
            dock.close();
            Ui.toast(a, "Ya no se hace copia");
        }));
        box.addView(dock.row("¿Cómo funciona?", v -> Tutorial.show(a, dock, "drive")));
        dock.show("Copia en Google Drive", box, new OptionDock.Action("Listo", true, null));
    }

    /** El usuario eligió dónde: se guarda el permiso para escribir siempre ahí. */
    static void chosen(Activity a, Uri uri) {
        try {
            a.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            android.util.Log.w("SyncTablet", "permiso de la copia", e);
        }
        String name = "Drive";
        try (android.database.Cursor c = a.getContentResolver().query(uri, new String[] {android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (RuntimeException ignored) {
        }
        prefs(a).edit().putString("uri", uri.toString()).putString("name", name).putLong("last", 0).remove("error").apply();
        now(a, true);
    }

    /** Sola, al volver a Inicio, como mucho cada 10 minutos. */
    static void auto(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getString("uri", null) == null) return;
        if (System.currentTimeMillis() - p.getLong("last", 0) < EVERY_MS) return;
        now(c, false);
    }

    static void now(Context c, boolean tell) {
        Context app = c.getApplicationContext();
        String uri = prefs(app).getString("uri", null);
        if (uri == null || running) return;
        running = true;
        if (tell) Ui.toast(c, "Haciendo la copia…");
        new Thread(() -> {
            String error = null;
            try (OutputStream out = app.getContentResolver().openOutputStream(Uri.parse(uri), "wt")) {
                if (out == null) throw new IOException("Drive no dejó escribir");
                write(app, out);
            } catch (IOException | RuntimeException e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                android.util.Log.w("SyncTablet", "copia", e);
            } finally {
                running = false;
            }
            SharedPreferences.Editor ed = prefs(app).edit();
            if (error == null) ed.putLong("last", System.currentTimeMillis()).remove("error");
            else ed.putString("error", error);
            ed.apply();
            String err = error;
            if (tell && c instanceof Activity) ((Activity) c).runOnUiThread(() -> Ui.toast(c, err == null ? "Copia guardada en Drive" : "No se pudo copiar: " + err));
        }, "copia").start();
    }

    /** Todo files/ (cuadernos, notas, hojas, presentaciones, fotos) y las carpetas de la app. */
    private static void write(Context c, OutputStream raw) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new java.io.BufferedOutputStream(raw, 1 << 16))) {
            File root = c.getFilesDir();
            add(zip, root, root);
            SharedPreferences ui = c.getSharedPreferences("ui", Context.MODE_PRIVATE);
            JSONObject extra = new JSONObject();
            try {
                extra.put("folders", new JSONArray(ui.getStringSet("folders", new java.util.HashSet<>())));
                extra.put("noteFolders", ui.getString("noteFolders", "{}"));
                extra.put("made", System.currentTimeMillis());
            } catch (JSONException ignored) {
            }
            zip.putNextEntry(new ZipEntry("app.json"));
            zip.write(extra.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static void add(ZipOutputStream zip, File root, File f) throws IOException {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) add(zip, root, k);
            return;
        }
        String name = f.getName();
        if (name.endsWith(".tmp") || name.endsWith(".apk")) return;
        String rel = root.toURI().relativize(f.toURI()).getPath();
        if (rel.startsWith("token") || rel.contains("auth")) return; // la clave con el PC no sale de la tablet
        zip.putNextEntry(new ZipEntry("files/" + rel));
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            for (int k; (k = in.read(buf)) > 0; ) zip.write(buf, 0, k);
        }
        zip.closeEntry();
    }

    /** Agrega de la copia lo que aquí falta. */
    static void restore(Activity a, Uri uri, Runnable done) {
        Ui.toast(a, "Recuperando…");
        new Thread(() -> {
            int[] count = new int[4];
            String error = null;
            HashMap<String, String> pages = new HashMap<>();
            String notebooks = null, textNotes = null, sheetIndex = null, appJson = null;
            HashMap<String, byte[]> sheets = new HashMap<>(), decks = new HashMap<>();
            Store store = Store.get(a);
            try (ZipInputStream zip = new ZipInputStream(a.getContentResolver().openInputStream(uri))) {
                for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                    String n = e.getName();
                    if (e.isDirectory() || n.contains("..")) continue;
                    if (n.equals("app.json")) appJson = text(zip);
                    else if (n.equals("files/notes/notebooks.json")) notebooks = text(zip);
                    else if (n.equals("files/notes/text-notes.json")) textNotes = text(zip);
                    else if (n.startsWith("files/notes/pages/") && n.endsWith(".json")) pages.put(n.substring(18, n.length() - 5), text(zip));
                    else if (n.startsWith("files/notes/assets/")) {
                        File f = new File(store.assetsDir(), n.substring(19));
                        if (!f.exists()) copy(zip, f);
                    } else if (n.equals("files/sheets/index.json")) sheetIndex = text(zip);
                    else if (n.startsWith("files/sheets/") && n.endsWith(".xlsx")) sheets.put(n.substring(13, n.length() - 5), bytes(zip));
                    else if (n.startsWith("files/decks/")) decks.put(n.substring(12), bytes(zip));
                    else if (n.startsWith("files/looks/")) {
                        File f = new File(a.getFilesDir(), n.substring(6));
                        if (!f.exists()) copy(zip, f);
                    }
                }
                if (notebooks != null) {
                    JSONArray nbs = new JSONArray(notebooks);
                    for (int i = 0; i < nbs.length(); i++) if (store.adopt(nbs.getJSONObject(i), pages)) count[0]++;
                }
                if (textNotes != null) {
                    JSONArray ns = new JSONObject(textNotes).optJSONArray("notes");
                    JSONArray list = ns == null ? new JSONArray() : ns;
                    a.runOnUiThread(() -> {
                        for (int i = 0; i < list.length(); i++) {
                            JSONObject note = list.optJSONObject(i);
                            if (note != null) Notes.get(a).adopt(note);
                        }
                    });
                    count[1] = list.length();
                }
                if (sheetIndex != null) {
                    JSONArray idx = new JSONArray(sheetIndex);
                    for (int i = 0; i < idx.length(); i++) {
                        JSONObject o = idx.getJSONObject(i);
                        String id = o.getString("id");
                        byte[] x = sheets.get(id);
                        if (x == null || SheetFiles.get(a, id) != null) continue;
                        SheetFiles.save(a, id, o.optString("title"), x);
                        SheetFiles.Doc d = SheetFiles.get(a, id);
                        if (d != null) {
                            d.folder = o.optString("folder");
                            SheetFiles.update(a, d);
                        }
                        count[2]++;
                    }
                }
                for (java.util.Map.Entry<String, byte[]> d : decks.entrySet()) {
                    File f = new File(new File(a.getFilesDir(), "decks"), d.getKey());
                    if (f.exists()) continue;
                    f.getParentFile().mkdirs();
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(d.getValue());
                    }
                    if (d.getKey().endsWith(".json")) count[3]++;
                }
                if (appJson != null) {
                    JSONObject app = new JSONObject(appJson);
                    SharedPreferences ui = a.getSharedPreferences("ui", Context.MODE_PRIVATE);
                    java.util.HashSet<String> folders = new java.util.HashSet<>(ui.getStringSet("folders", new java.util.HashSet<>()));
                    JSONArray fs = app.optJSONArray("folders");
                    if (fs != null) for (int i = 0; i < fs.length(); i++) folders.add(fs.getString(i));
                    JSONObject mine = new JSONObject(ui.getString("noteFolders", "{}")), theirs = new JSONObject(app.optString("noteFolders", "{}"));
                    for (java.util.Iterator<String> it = theirs.keys(); it.hasNext(); ) {
                        String k = it.next();
                        if (!mine.has(k)) mine.put(k, theirs.get(k));
                    }
                    ui.edit().putStringSet("folders", folders).putString("noteFolders", mine.toString()).apply();
                }
            } catch (IOException | JSONException | RuntimeException e) {
                error = e.getMessage();
                android.util.Log.w("SyncTablet", "recuperar", e);
            }
            String err = error;
            a.runOnUiThread(() -> {
                if (err != null) Ui.toast(a, "No se pudo leer la copia");
                else Ui.toast(a, "Recuperado: " + count[0] + " cuadernos nuevos, " + count[2] + " hojas de cálculo y " + count[3] + " presentaciones");
                SyncClient.get(a);
                done.run();
            });
        }, "recuperar").start();
    }

    private static byte[] bytes(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        for (int k; (k = in.read(buf)) > 0; ) out.write(buf, 0, k);
        return out.toByteArray();
    }

    private static String text(InputStream in) throws IOException {
        return new String(bytes(in), StandardCharsets.UTF_8);
    }

    private static void copy(InputStream in, File f) throws IOException {
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            for (int k; (k = in.read(buf)) > 0; ) out.write(buf, 0, k);
        }
        tmp.renameTo(f);
    }
}
