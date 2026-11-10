package dev.synctablet;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Los apuntes en el almacenamiento de la app (funciona sin el PC):
 * notebooks.json, versions.json y pages/&lt;id&gt;.json. Se escribe en un hilo
 * aparte; el JSON se genera en el hilo de la interfaz para no compartir
 * objetos que se están editando.
 */
final class Store {
    private static Store instance;

    static synchronized Store get(Context c) {
        if (instance == null) instance = new Store(c.getApplicationContext().getFilesDir());
        return instance;
    }

    private final File dir;
    private final File pagesDir;
    private final File assetsDir;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final ArrayList<Ink.Notebook> notebooks = new ArrayList<>();
    private final HashMap<String, Ink.Page> pages = new HashMap<>();
    private final HashMap<String, Long> versions = new HashMap<>();

    private Store(File root) {
        dir = new File(root, "notes");
        pagesDir = new File(dir, "pages");
        pagesDir.mkdirs();
        assetsDir = new File(dir, "assets");
        assetsDir.mkdirs();
        try {
            JSONArray a = new JSONArray(read(new File(dir, "notebooks.json"), "[]"));
            for (int i = 0; i < a.length(); i++) notebooks.add(Ink.Notebook.fromJson(a.getJSONObject(i)));
            JSONObject v = new JSONObject(read(new File(dir, "versions.json"), "{}"));
            for (java.util.Iterator<String> it = v.keys(); it.hasNext(); ) {
                String k = it.next();
                versions.put(k, v.getLong(k));
            }
        } catch (JSONException e) {
            android.util.Log.e("SyncTablet", "índice ilegible", e);
        }
    }

    File assetFile(String id) {
        return new File(assetsDir, id + ".jpg");
    }

    /** Guarda una imagen como JPEG y devuelve su id. */
    String putAsset(android.graphics.Bitmap b) throws IOException {
        String id = Ink.newId();
        File f = assetFile(id), tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out);
        }
        if (!tmp.renameTo(f)) throw new IOException("no se pudo guardar la imagen");
        return id;
    }

    /** Guarda un JPEG ya hecho y devuelve su id. */
    String putAssetBytes(byte[] jpeg) throws IOException {
        String id = Ink.newId();
        File f = assetFile(id), tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(jpeg);
        }
        if (!tmp.renameTo(f)) throw new IOException("no se pudo guardar la imagen");
        return id;
    }

    /** Página nueva al final, copia de otra (sus trazos e imágenes). */
    synchronized Ink.Page duplicatePage(Ink.Notebook n, Ink.Page from, int at) {
        Ink.Page p = new Ink.Page(Ink.newId(), n.id);
        p.copyFrom(from);
        // Una marca es de un sitio: la copia no hereda enlaces.
        p.marks.clear();
        // Las imágenes y textos se pueden mover: cada página con los suyos.
        for (int i = 0; i < p.items.size(); i++) p.items.set(i, p.items.get(i).copy(p.items.get(i).id));
        n.pages.add(at, p.id);
        pages.put(p.id, p);
        savePage(p);
        saveNotebooks();
        return p;
    }

    synchronized ArrayList<Ink.Notebook> notebooks() {
        return notebooks;
    }

    synchronized Ink.Notebook notebook(String id) {
        for (Ink.Notebook n : notebooks) if (n.id.equals(id)) return n;
        return null;
    }

    /** La página, desde la memoria o el disco (null si no existe). */
    synchronized Ink.Page page(String id) {
        Ink.Page p = pages.get(id);
        if (p != null) return p;
        try {
            File f = new File(pagesDir, id + ".json");
            if (!f.isFile()) return null;
            p = Ink.Page.fromJson(read(f, "{}"));
            pages.put(id, p);
            return p;
        } catch (JSONException e) {
            android.util.Log.e("SyncTablet", "página ilegible " + id, e);
            return null;
        }
    }

    /**
     * Texto y marcas de una página sin dejarla en memoria: revisar todos los
     * cuadernos para el mapa o las materias no debe cargar todos los trazos.
     */
    synchronized Ink.Page peek(String id) {
        Ink.Page p = pages.get(id);
        if (p != null) return p;
        try {
            File f = new File(pagesDir, id + ".json");
            return f.isFile() ? Ink.Page.fromJson(read(f, "{}")) : null;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Lo escrito a máquina de una página: PDF, textos, tablas y palabras clave. */
    static String text(Ink.Page p) {
        StringBuilder b = new StringBuilder();
        if (p == null) return "";
        if (p.bgText != null) b.append(p.bgText).append('\n');
        for (Ink.Item it : p.items) {
            if (it.text != null) b.append(it.text).append('\n');
            if (it.values != null) for (String v : it.values) if (v != null) b.append(v).append(' ');
        }
        for (Ink.Mark m : p.marks) for (String w : m.words) b.append(w).append(' ').append(w).append(' ');
        return b.toString();
    }

    /** Recuperar de una copia: un cuaderno que aquí no existe, con sus páginas (JSON). */
    synchronized boolean adopt(JSONObject nb, Map<String, String> pageJson) {
        try {
            Ink.Notebook n = Ink.Notebook.fromJson(nb);
            if (notebook(n.id) != null) return false;
            for (String id : n.pages) {
                String json = pageJson.get(id);
                if (json == null) continue;
                Ink.Page p = Ink.Page.fromJson(json);
                pages.put(p.id, p);
                savePage(p);
            }
            notebooks.add(n);
            saveNotebooks();
            return true;
        } catch (JSONException e) {
            android.util.Log.w("SyncTablet", "cuaderno de la copia", e);
            return false;
        }
    }

    File assetsDir() {
        return assetsDir;
    }

    synchronized Map<String, Long> versions() {
        return new HashMap<>(versions);
    }

    synchronized Ink.Notebook createNotebook(String title, int color) {
        Ink.Notebook n = new Ink.Notebook(Ink.newId(), title, color);
        n.updated = System.currentTimeMillis();
        notebooks.add(0, n);
        addPage(n);
        return n;
    }

    /** Página nueva al final del cuaderno, con el fondo de la anterior. */
    synchronized Ink.Page addPage(Ink.Notebook n) {
        Ink.Page p = new Ink.Page(Ink.newId(), n.id);
        if (!n.pages.isEmpty()) {
            Ink.Page last = page(n.pages.get(n.pages.size() - 1));
            if (last != null) p.background = last.background;
        }
        n.pages.add(p.id);
        pages.put(p.id, p);
        savePage(p);
        saveNotebooks();
        return p;
    }

    /** Página nueva en la posición `at`; `right`: junto a la anterior, no debajo. */
    synchronized Ink.Page insertPage(Ink.Notebook n, int at, boolean right) {
        Ink.Page p = new Ink.Page(Ink.newId(), n.id);
        at = Math.max(0, Math.min(n.pages.size(), at));
        if (at > 0) {
            Ink.Page before = page(n.pages.get(at - 1));
            if (before != null) p.background = before.background;
        }
        n.pages.add(at, p.id);
        if (right && at > 0) n.right.add(p.id);
        pages.put(p.id, p);
        savePage(p);
        saveNotebooks();
        return p;
    }

    /** Borra una página del cuaderno (no la última que quede). */
    synchronized boolean deletePage(Ink.Notebook n, String id) {
        int at = n.pages.indexOf(id);
        // Si empezaba fila, la de su derecha pasa a empezarla.
        if (at >= 0 && !n.right.contains(id) && at + 1 < n.pages.size()) n.right.remove(n.pages.get(at + 1));
        if (n.pages.size() <= 1 || !n.pages.remove(id)) return false;
        n.right.remove(id);
        pages.remove(id);
        versions.remove(id);
        final File f = new File(pagesDir, id + ".json");
        writer.execute(f::delete);
        saveNotebooks();
        saveVersions();
        return true;
    }

    /** A la papelera: las páginas se copian a `into` y salen del cuaderno y de la sincronización. */
    synchronized void trashNotebook(Ink.Notebook n, File into) {
        into.mkdirs();
        for (String id : n.pages) {
            Ink.Page p = page(id);
            if (p != null) {
                try {
                    Trash.write(new File(into, id + ".json"), p.toJson());
                } catch (java.io.IOException e) {
                    android.util.Log.w("SyncTablet", "papelera", e);
                }
            }
        }
        deleteNotebook(n);
    }

    /** De la papelera al cuaderno otra vez (null si no se pudo). */
    synchronized Ink.Notebook restoreNotebook(JSONObject json, File from) {
        if (json == null) return null;
        try {
            Ink.Notebook n = Ink.Notebook.fromJson(json);
            if (notebook(n.id) != null) return null;
            for (String id : n.pages) {
                File f = new File(from, id + ".json");
                Ink.Page p = f.isFile() ? Ink.Page.fromJson(Trash.read(f, "{}")) : new Ink.Page(id, n.id);
                pages.put(p.id, p);
                savePage(p);
            }
            n.updated = System.currentTimeMillis();
            notebooks.add(0, n);
            saveNotebooks();
            return n;
        } catch (JSONException e) {
            android.util.Log.w("SyncTablet", "restaurar cuaderno", e);
            return null;
        }
    }

    synchronized void deleteNotebook(Ink.Notebook n) {
        notebooks.remove(n);
        for (String id : n.pages) {
            pages.remove(id);
            versions.remove(id);
            final File f = new File(pagesDir, id + ".json");
            writer.execute(f::delete);
        }
        saveNotebooks();
        saveVersions();
    }

    synchronized void saveNotebooks() {
        JSONArray a = new JSONArray();
        try {
            for (Ink.Notebook n : notebooks) a.put(n.toJsonObject());
        } catch (JSONException e) {
            return;
        }
        final String json = a.toString();
        writer.execute(() -> write(new File(dir, "notebooks.json"), json));
    }

    synchronized void savePage(Ink.Page p) {
        final String json = p.toJson();
        final File f = new File(pagesDir, p.id + ".json");
        versions.put(p.id, p.version);
        writer.execute(() -> write(f, json));
        saveVersions();
    }

    private void saveVersions() {
        final String json = new JSONObject(versions).toString();
        writer.execute(() -> write(new File(dir, "versions.json"), json));
    }

    private static String read(File f, String fallback) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fallback;
        }
    }

    /** Escribe en un temporal y lo renombra: nunca queda medio archivo. */
    private static void write(File f, String data) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            android.util.Log.e("SyncTablet", "no se pudo guardar " + f, e);
            return;
        }
        if (!tmp.renameTo(f)) android.util.Log.e("SyncTablet", "no se pudo renombrar " + tmp);
    }
}
