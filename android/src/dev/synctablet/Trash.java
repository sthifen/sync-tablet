package dev.synctablet;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;

/**
 * Papelera: lo que se borra (cuadernos, notas, documentos, hojas de cálculo,
 * presentaciones y diagramas) se guarda aquí 30 días y se puede restaurar.
 * Vive en files/trash: un índice y una carpeta por cosa borrada. Un cuaderno
 * se borra del PC al tirarlo y se vuelve a subir al restaurarlo.
 */
final class Trash {
    static final long KEEP_MS = 30L * 24 * 60 * 60 * 1000;
    static final String NOTEBOOK = "nb", NOTE = "note", DOC = "doc", DECK = "deck", FLOW = "flow", SHEET = "sheet";

    static final class Entry {
        String id, kind, itemId, title = "", folder = "";
        long when;
        int color;
        JSONObject data;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("kind", kind);
            o.put("item", itemId);
            o.put("title", title);
            o.put("folder", folder);
            o.put("when", when);
            o.put("color", color);
            if (data != null) o.put("data", data);
            return o;
        }

        static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            e.id = o.optString("id");
            e.kind = o.optString("kind");
            e.itemId = o.optString("item");
            e.title = o.optString("title");
            e.folder = o.optString("folder");
            e.when = o.optLong("when");
            e.color = o.optInt("color");
            e.data = o.optJSONObject("data");
            return e;
        }

        String kindName() {
            switch (kind) {
                case NOTEBOOK: return "Cuaderno";
                case NOTE: return "Nota";
                case DOC: return "Documento";
                case DECK: return "Presentación";
                case FLOW: return "Diagrama";
                case SHEET: return "Hoja de cálculo";
                default: return "Archivo";
            }
        }
    }

    private Trash() {}

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "trash");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static synchronized ArrayList<Entry> list(Context c) {
        ArrayList<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(read(new File(dir(c), "index.json"), "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Entry.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        out.sort((x, y) -> Long.compare(y.when, x.when));
        return out;
    }

    private static synchronized void keep(Context c, ArrayList<Entry> list) {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : list) a.put(e.toJson());
            write(new File(dir(c), "index.json"), a.toString());
        } catch (JSONException | IOException e) {
            android.util.Log.w("SyncTablet", "papelera", e);
        }
    }

    private static synchronized Entry add(Context c, String kind, String itemId, String title, String folder, int color) {
        Entry e = new Entry();
        e.id = Ink.newId();
        e.kind = kind;
        e.itemId = itemId;
        e.title = title == null ? "" : title;
        e.folder = folder == null ? "" : folder;
        e.color = color;
        e.when = System.currentTimeMillis();
        return e;
    }

    private static synchronized void append(Context c, Entry e) {
        ArrayList<Entry> list = list(c);
        list.add(e);
        keep(c, list);
    }

    // ---- Tirar ----

    static void notebook(Context c, Ink.Notebook n) {
        Entry e = add(c, NOTEBOOK, n.id, n.title, n.folder, n.color);
        try {
            e.data = n.toJsonObject();
        } catch (JSONException ignored) {
        }
        Store.get(c).trashNotebook(n, new File(dir(c), e.id));
        append(c, e);
        SyncClient.get(c).send("{\"t\":\"delete_notebook\",\"notebook\":\"" + n.id + "\"}");
    }

    static void note(Context c, Notes notes, JSONObject n, String folder) {
        String title = n.optString("title");
        Entry e = add(c, NOTE, n.optString("id"), title.isEmpty() ? "Sin título" : title, folder, 0);
        try {
            e.data = new JSONObject(n.toString());
        } catch (JSONException ignored) {
        }
        append(c, e);
        notes.delete(n.optString("id"));
    }

    /** Documento, presentación, diagrama u hoja de cálculo: sus archivos pasan a la papelera. */
    static void file(Context c, String kind, String itemId, String title, String folder, long updated) {
        Entry e = add(c, kind, itemId, title, folder, 0);
        File into = new File(dir(c), e.id);
        into.mkdirs();
        for (File f : filesOf(c, kind, itemId)) f.renameTo(new File(into, f.getName()));
        if (SHEET.equals(kind)) {
            try {
                e.data = new JSONObject().put("updated", updated);
            } catch (JSONException ignored) {
            }
            SheetFiles.delete(c, itemId);
        }
        append(c, e);
    }

    private static File home(Context c, String kind) {
        switch (kind) {
            case DOC: return new File(c.getFilesDir(), "docs");
            case DECK: return new File(c.getFilesDir(), "decks");
            case FLOW: return new File(c.getFilesDir(), "flows");
            default: return new File(c.getFilesDir(), "sheets");
        }
    }

    private static ArrayList<File> filesOf(Context c, String kind, String id) {
        ArrayList<File> out = new ArrayList<>();
        File[] all = home(c, kind).listFiles((d, name) -> name.equals(id + ".json") || name.equals(id + ".xlsx") || name.startsWith(id + "-"));
        if (all != null) for (File f : all) out.add(f);
        return out;
    }

    // ---- Restaurar y borrar del todo ----

    /** Lo devuelve a su sitio (y al PC, si es un cuaderno o una nota). */
    static synchronized boolean restore(Context c, Notes notes, Entry e) {
        File from = new File(dir(c), e.id);
        try {
            switch (e.kind) {
                case NOTEBOOK: {
                    Ink.Notebook n = Store.get(c).restoreNotebook(e.data, from);
                    if (n == null) return false;
                    SyncClient sync = SyncClient.get(c);
                    sync.sendNotebook(n);
                    for (String id : n.pages) {
                        Ink.Page p = Store.get(c).page(id);
                        if (p != null) sync.sendPage(p);
                    }
                    break;
                }
                case NOTE:
                    if (e.data == null || !notes.restore(e.data)) return false;
                    break;
                default: {
                    File home = home(c, e.kind);
                    home.mkdirs();
                    File[] files = from.listFiles();
                    if (files != null) for (File f : files) f.renameTo(new File(home, f.getName()));
                    if (SHEET.equals(e.kind)) SheetFiles.restore(c, e.itemId, e.title, e.folder, e.data == null ? System.currentTimeMillis() : e.data.optLong("updated"));
                }
            }
        } catch (RuntimeException ex) {
            android.util.Log.w("SyncTablet", "restaurar", ex);
            return false;
        }
        forget(c, e);
        return true;
    }

    static synchronized void purge(Context c, Entry e) {
        forget(c, e);
    }

    static synchronized void empty(Context c) {
        for (Entry e : list(c)) forget(c, e);
    }

    /** Lo que lleva más de 30 días en la papelera se borra solo. */
    static synchronized void purgeOld(Context c) {
        long now = System.currentTimeMillis();
        for (Entry e : list(c)) if (now - e.when > KEEP_MS) forget(c, e);
    }

    private static void forget(Context c, Entry e) {
        deleteTree(new File(dir(c), e.id));
        ArrayList<Entry> list = list(c);
        list.removeIf(x -> x.id.equals(e.id));
        keep(c, list);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    static String read(File f, String fallback) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fallback;
        }
    }

    static void write(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("no se pudo guardar");
    }
}
