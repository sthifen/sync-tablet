package dev.synctablet;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Las notas de bloques (las mismas que se escriben en el visor del PC),
 * guardadas en la tablet para usarlas sin conexión: `notes/text-notes.json`.
 *
 * Con el PC conectado se sincronizan por HTTP (`/api/notes`):
 * primero se suben los cambios hechos aquí (borrados y notas editadas; si las
 * dos partes cambiaron la misma nota, gana la tablet) y después se bajan las
 * que cambiaron en el PC. Para saber cuáles, cada nota recuerda el `updated`
 * que el PC le dio la última vez (así no se comparan relojes distintos).
 *
 * Todo se toca en el hilo de la interfaz; la red y el disco van en un hilo aparte.
 */
final class Notes {
    /** Quién hace los cambios: el PC no nos devuelve los avisos de los nuestros. */
    static final String CLIENT = "tablet";

    interface Listener {
        /** Cambió la lista o la nota `id` (null = varias). */
        void onNotesChanged(String id);
    }

    private static Notes instance;

    static synchronized Notes get(Context c) {
        if (instance == null) instance = new Notes(c.getApplicationContext().getFilesDir());
        return instance;
    }

    private final File file;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    /** Notas completas, en orden. Campos propios: dirty, synced, remote. */
    private final ArrayList<JSONObject> notes = new ArrayList<>();
    /** Borradas aquí sin conexión: se borran en el PC al volver. */
    private final HashSet<String> deleted = new HashSet<>();
    private boolean syncing, again;

    private Notes(File root) {
        File dir = new File(root, "notes");
        dir.mkdirs();
        file = new File(dir, "text-notes.json");
        try {
            JSONObject o = new JSONObject(file.exists() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "{}");
            JSONArray a = o.optJSONArray("notes");
            if (a != null) for (int i = 0; i < a.length(); i++) notes.add(a.getJSONObject(i));
            JSONArray d = o.optJSONArray("deleted");
            if (d != null) for (int i = 0; i < d.length(); i++) deleted.add(d.getString(i));
        } catch (IOException | JSONException e) {
            android.util.Log.e("SyncTablet", "notas ilegibles", e);
        }
    }

    // ---------------------------------------------------------------- datos

    static JSONObject block(String type, String text) {
        JSONObject b = new JSONObject();
        try {
            b.put("id", "b" + Ink.newId());
            b.put("type", type);
            b.put("text", text);
            b.put("checked", false);
        } catch (JSONException ignored) {
        }
        return b;
    }

    ArrayList<JSONObject> list() {
        return new ArrayList<>(notes);
    }

    JSONObject note(String id) {
        for (JSONObject n : notes) if (n.optString("id").equals(id)) return n;
        return null;
    }

    /** Todo el texto de la nota (para buscar y para el resumen). */
    static String text(JSONObject n) {
        StringBuilder sb = new StringBuilder();
        JSONArray b = n.optJSONArray("blocks");
        if (b != null) for (int i = 0; i < b.length(); i++) {
            String t = b.optJSONObject(i) == null ? "" : b.optJSONObject(i).optString("text");
            if (!t.isEmpty()) sb.append(sb.length() > 0 ? " " : "").append(t);
        }
        return sb.toString();
    }

    JSONObject create() {
        JSONObject n = new JSONObject();
        try {
            n.put("id", "n" + Ink.newId());
            n.put("title", "");
            n.put("icon", "📄");
            n.put("blocks", new JSONArray().put(block("text", "")));
            n.put("updated", System.currentTimeMillis());
            n.put("dirty", true);
        } catch (JSONException ignored) {
        }
        notes.add(n);
        changed(n.optString("id"));
        return n;
    }

    /** Nota nueva con el contenido de un documento: JSON {title, blocks}. */
    JSONObject createFrom(String json, String fallbackTitle) throws JSONException {
        JSONObject doc = new JSONObject(json);
        JSONArray from = doc.getJSONArray("blocks"), blocks = new JSONArray();
        for (int i = 0; i < from.length(); i++) {
            JSONObject b = from.getJSONObject(i);
            JSONObject made = block(b.optString("type", "text"), b.optString("text"));
            made.put("checked", b.optBoolean("checked"));
            blocks.put(made);
        }
        if (blocks.length() == 0) blocks.put(block("text", ""));
        JSONObject n = create();
        String title = doc.optString("title").trim();
        n.put("title", title.isEmpty() ? fallbackTitle : title);
        n.put("blocks", blocks);
        edited(n);
        return n;
    }

    /** La nota cambió (título, icono o bloques): guardarla y subirla. */
    void edited(JSONObject n) {
        try {
            n.put("updated", System.currentTimeMillis());
            n.put("dirty", true);
        } catch (JSONException ignored) {
        }
        changed(n.optString("id"));
    }

    /** Recuperar de una copia: la nota si aquí no existe. */
    boolean adopt(JSONObject n) {
        if (note(n.optString("id")) != null) return false;
        try {
            n.put("dirty", true);
            n.remove("synced");
        } catch (JSONException ignored) {
        }
        notes.add(n);
        changed(n.optString("id"));
        return true;
    }

    /** Vuelve de la papelera: con el mismo id, y se sube otra vez al PC. */
    boolean restore(JSONObject n) {
        deleted.remove(n.optString("id"));
        try {
            n.put("updated", System.currentTimeMillis());
        } catch (JSONException ignored) {
        }
        return adopt(n);
    }

    void delete(String id) {
        JSONObject n = note(id);
        if (n == null) return;
        notes.remove(n);
        if (n.optBoolean("synced")) deleted.add(id);
        changed(null);
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void changed(String id) {
        save();
        for (Listener l : listeners) l.onNotesChanged(id);
        sync();
    }

    private void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("notes", new JSONArray(notes));
            o.put("deleted", new JSONArray(deleted));
            final String json = o.toString();
            disk.execute(() -> {
                File tmp = new File(file.getPath() + ".tmp");
                try {
                    Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
                    if (!tmp.renameTo(file)) throw new IOException("rename");
                } catch (IOException e) {
                    android.util.Log.e("SyncTablet", "no se pudieron guardar las notas", e);
                }
            });
        } catch (JSONException ignored) {
        }
    }

    // ---------------------------------------------------------------- sincronización

    /** Sincroniza con el PC si hay conexión (si ya está en marcha, repite al terminar). */
    void sync() {
        String host = SyncClient.currentHost();
        if (host == null) return;
        if (syncing) {
            again = true;
            return;
        }
        syncing = true;
        again = false;
        // Copia de lo que hay que subir, hecha aquí (dueño de las notas).
        final ArrayList<String> toDelete = new ArrayList<>(deleted);
        final ArrayList<JSONObject> toPush = new ArrayList<>();
        for (JSONObject n : notes) {
            if (!n.optBoolean("dirty")) continue;
            try {
                toPush.add(new JSONObject(n.toString()));
            } catch (JSONException ignored) {
            }
        }
        final HashMap<String, Long> known = new HashMap<>();
        for (JSONObject n : notes) known.put(n.optString("id"), n.optLong("remote", -1));
        net.execute(() -> {
            Result r = new Result();
            try {
                talk(host, toDelete, toPush, known, r);
            } catch (IOException | JSONException e) {
                r.failed = true;
                android.util.Log.i("SyncTablet", "notas: sin sincronizar (" + e + ")");
            }
            main.post(() -> apply(r));
        });
    }

    /** Lo que trajo una sincronización, para aplicarlo en el hilo de la interfaz. */
    private static final class Result {
        boolean failed;
        final ArrayList<String> deletedOk = new ArrayList<>();
        /** id → (updated de la tablet cuando se subió, updated del PC). */
        final HashMap<String, long[]> pushed = new HashMap<>();
        final ArrayList<JSONObject> pulled = new ArrayList<>();
        HashSet<String> remoteIds;
    }

    private static void talk(String host, ArrayList<String> toDelete, ArrayList<JSONObject> toPush,
                             HashMap<String, Long> known, Result r) throws IOException, JSONException {
        String base = "http://" + host + ":" + SyncClient.PORT + "/api/notes";
        for (String id : toDelete) {
            int code = http("DELETE", base + "/" + enc(id), null).code;
            if (code == 204 || code == 404) r.deletedOk.add(id);
        }
        for (JSONObject n : toPush) {
            JSONObject body = new JSONObject();
            body.put("title", n.optString("title"));
            body.put("icon", n.optString("icon"));
            body.put("blocks", n.optJSONArray("blocks"));
            body.put("client", CLIENT);
            Resp resp = http("PUT", base + "/" + enc(n.optString("id")), body.toString());
            if (resp.code != 200) throw new IOException("PUT " + resp.code);
            long remote = new JSONObject(resp.body).optLong("updated");
            r.pushed.put(n.optString("id"), new long[] {n.optLong("updated"), remote});
            known.put(n.optString("id"), remote);
        }
        Resp list = http("GET", base, null);
        if (list.code != 200) throw new IOException("GET " + list.code);
        JSONArray a = new JSONArray(list.body);
        r.remoteIds = new HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject s = a.getJSONObject(i);
            String id = s.optString("id");
            r.remoteIds.add(id);
            Long have = known.get(id);
            if (have != null && have == s.optLong("updated")) continue;
            Resp full = http("GET", base + "/" + enc(id), null);
            if (full.code == 200) r.pulled.add(new JSONObject(full.body));
        }
    }

    private void apply(Result r) {
        syncing = false;
        if (!r.failed || !r.deletedOk.isEmpty() || !r.pushed.isEmpty()) {
            boolean any = false;
            for (String id : r.deletedOk) any |= deleted.remove(id);
            for (java.util.Map.Entry<String, long[]> e : r.pushed.entrySet()) {
                JSONObject n = note(e.getKey());
                if (n == null) continue;
                try {
                    n.put("synced", true);
                    n.put("remote", e.getValue()[1]);
                    // Si se siguió editando mientras subía, sigue pendiente.
                    if (n.optLong("updated") == e.getValue()[0]) n.put("dirty", false);
                } catch (JSONException ignored) {
                }
                any = true;
            }
            HashSet<String> touched = new HashSet<>();
            for (JSONObject p : r.pulled) {
                String id = p.optString("id");
                JSONObject n = note(id);
                if (n != null && n.optBoolean("dirty")) continue; // se editó aquí entretanto: gana la tablet
                try {
                    p.put("remote", p.optLong("updated"));
                    p.put("synced", true);
                    p.put("dirty", false);
                } catch (JSONException ignored) {
                }
                if (n == null) notes.add(p);
                else notes.set(notes.indexOf(n), p);
                touched.add(id);
            }
            // Las que el PC ya no tiene se borraron allí.
            if (r.remoteIds != null) {
                for (int i = notes.size() - 1; i >= 0; i--) {
                    JSONObject n = notes.get(i);
                    if (n.optBoolean("synced") && !n.optBoolean("dirty") && !r.remoteIds.contains(n.optString("id"))) {
                        touched.add(n.optString("id"));
                        touched.add(null); // varias cosas: que se repinte todo
                        notes.remove(i);
                    }
                }
            }
            if (any || !touched.isEmpty()) save();
            if (!touched.isEmpty() || any) {
                // Lo subido también avisa, para que se vea «Guardado en el PC».
                final String id = touched.size() == 1 && !any ? touched.iterator().next() : null;
                for (Listener l : listeners) l.onNotesChanged(id);
            }
        }
        if (again && !r.failed) sync();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private static final class Resp {
        int code;
        String body;
    }

    private static Resp http(String method, String url, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(8000);
        c.setRequestMethod(method);
        String token = SyncClient.token;
        if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream o = c.getOutputStream()) {
                    o.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            Resp r = new Resp();
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            if (in != null) {
                byte[] b = new byte[8192];
                int k;
                while ((k = in.read(b)) > 0) buf.write(b, 0, k);
                in.close();
            }
            r.body = buf.toString("UTF-8");
            return r;
        } finally {
            c.disconnect();
        }
    }
}
