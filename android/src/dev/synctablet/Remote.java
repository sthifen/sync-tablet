package dev.synctablet;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;

/**
 * Lo que hizo otro aparato (la app del PC u otra tablet), que llega a través
 * del PC: se guarda aquí sin volver a mandarlo, y la hoja abierta y la
 * portada lo enseñan al momento.
 */
final class Remote {
    private Remote() {
    }

    /** En el hilo principal. */
    static void apply(Context app, SyncClient sync, JSONObject m) {
        Store store = Store.get(app);
        InkView open = SyncClient.open;
        try {
            switch (m.optString("t")) {
                case "page": {
                    Ink.Page p = Ink.Page.fromJson(m.getJSONObject("page").toString());
                    if (!store.receivePage(p)) return;
                    fetchMissing(app, store, store.page(p.id));
                    if (open != null) open.remoteChanged(p.id, false);
                    sync.notifyRemote(p.notebook);
                    break;
                }
                case "notebook": {
                    Ink.Notebook n = store.receiveNotebook(Ink.Notebook.fromJson(m.getJSONObject("notebook")));
                    sync.synced(n.id, n.toJsonObject().toString());
                    sync.notifyRemote(n.id);
                    break;
                }
                case "stroke": {
                    Ink.Page p = store.receiveStroke(m.getString("page"), m.optLong("version"), Ink.Stroke.fromJson(m.getJSONObject("stroke")));
                    if (p == null) return;
                    if (open != null) open.remoteChanged(p.id, true);
                    sync.notifyRemote(p.notebook);
                    break;
                }
                case "erase": {
                    JSONArray a = m.getJSONArray("ids");
                    HashSet<String> ids = new HashSet<>();
                    for (int i = 0; i < a.length(); i++) ids.add(a.getString(i));
                    Ink.Page p = store.receiveErase(m.getString("page"), m.optLong("version"), ids);
                    if (p == null) return;
                    if (open != null) open.remoteChanged(p.id, false);
                    sync.notifyRemote(p.notebook);
                    break;
                }
                case "delete_page": {
                    Ink.Notebook n = store.receiveDeletePage(m.getString("page"));
                    if (n != null) sync.notifyRemote(n.id);
                    break;
                }
                case "delete_notebook": {
                    Ink.Notebook n = store.notebook(m.getString("notebook"));
                    if (n == null) return;
                    Trash.notebookFromOther(app, n);
                    sync.notifyRemote(n.id);
                    break;
                }
                case "asset": {
                    String id = m.getString("id");
                    if (!store.assetFile(id).isFile()) fetch(app, store, id);
                    break;
                }
                default:
            }
        } catch (JSONException e) {
            android.util.Log.w("SyncTablet", "cambio de otro aparato ilegible", e);
        }
    }

    /** Las imágenes de la página que aún no están aquí. */
    private static void fetchMissing(Context app, Store store, Ink.Page p) {
        if (p == null) return;
        if (p.bgAsset != null && !store.assetFile(p.bgAsset).isFile()) fetch(app, store, p.bgAsset);
        for (Ink.Item it : p.items) if (it.isImage() && it.asset != null && !store.assetFile(it.asset).isFile()) fetch(app, store, it.asset);
    }

    private static final HashSet<String> fetching = new HashSet<>();

    /** Baja una imagen del PC (en un hilo) y vuelve a dibujar lo abierto. */
    private static void fetch(Context app, Store store, String id) {
        String host = SyncClient.currentHost();
        if (host == null || !id.matches("[A-Za-z0-9_-]{1,64}")) return;
        synchronized (fetching) {
            if (!fetching.add(id)) return;
        }
        new Thread(() -> {
            File f = store.assetFile(id), tmp = new File(f.getPath() + ".part");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + SyncClient.PORT + "/api/asset/" + id + ".jpg").openConnection();
                c.setConnectTimeout(3000);
                c.setReadTimeout(30000);
                if (!SyncClient.token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + SyncClient.token);
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    if (c.getResponseCode() != 200) throw new IOException("respuesta " + c.getResponseCode());
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                } finally {
                    c.disconnect();
                }
                if (!tmp.renameTo(f)) throw new IOException("no se pudo guardar");
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    InkView open = SyncClient.open;
                    if (open != null) open.redrawPages();
                    if (open != null) open.invalidate();
                    SyncClient.get(app).notifyRemote(null);
                });
            } catch (IOException e) {
                tmp.delete();
                android.util.Log.w("SyncTablet", "imagen " + id + " del PC", e);
            } finally {
                synchronized (fetching) {
                    fetching.remove(id);
                }
            }
        }, "sync-fetch").start();
    }
}
