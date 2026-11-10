package dev.synctablet;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Las hojas de cálculo de la tablet: cada una es un .xlsx en files/sheets y su
 * nombre, fecha y carpeta van en files/sheets/index.json.
 */
final class SheetFiles {
    static final class Doc {
        String id, title, folder = "";
        long updated;
    }

    private SheetFiles() {}

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "sheets");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static File file(Context c, String id) {
        return new File(dir(c), id + ".xlsx");
    }

    static synchronized ArrayList<Doc> list(Context c) {
        ArrayList<Doc> out = new ArrayList<>();
        File f = new File(dir(c), "index.json");
        if (!f.isFile()) return out;
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(f.toPath()), "UTF-8"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Doc d = new Doc();
                d.id = o.getString("id");
                d.title = o.optString("title", "Hoja de cálculo");
                d.folder = o.optString("folder");
                d.updated = o.optLong("updated");
                if (file(c, d.id).isFile()) out.add(d);
            }
        } catch (IOException | JSONException e) {
            android.util.Log.w("SyncTablet", "índice de hojas", e);
        }
        out.sort((a, b) -> Long.compare(b.updated, a.updated));
        return out;
    }

    static Doc get(Context c, String id) {
        for (Doc d : list(c)) if (d.id.equals(id)) return d;
        return null;
    }

    private static synchronized void keep(Context c, ArrayList<Doc> docs) {
        JSONArray a = new JSONArray();
        try {
            for (Doc d : docs) a.put(new JSONObject().put("id", d.id).put("title", d.title).put("folder", d.folder).put("updated", d.updated));
            File tmp = new File(dir(c), "index.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(a.toString().getBytes("UTF-8"));
            }
            tmp.renameTo(new File(dir(c), "index.json"));
        } catch (IOException | JSONException e) {
            android.util.Log.w("SyncTablet", "guardar índice de hojas", e);
        }
    }

    /** Guarda el libro (y lo crea en el índice si es nuevo). */
    static synchronized void save(Context c, String id, String title, byte[] xlsx) throws IOException {
        File tmp = new File(dir(c), id + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(xlsx);
        }
        if (!tmp.renameTo(file(c, id))) throw new IOException("no se pudo guardar");
        ArrayList<Doc> docs = list(c);
        Doc d = null;
        for (Doc x : docs) if (x.id.equals(id)) d = x;
        if (d == null) {
            d = new Doc();
            d.id = id;
            docs.add(d);
        }
        if (title != null) d.title = title;
        d.updated = System.currentTimeMillis();
        keep(c, docs);
    }

    static synchronized void update(Context c, Doc changed) {
        ArrayList<Doc> docs = list(c);
        for (Doc x : docs) {
            if (x.id.equals(changed.id)) {
                x.title = changed.title;
                x.folder = changed.folder;
            }
        }
        keep(c, docs);
    }

    /** Vuelve de la papelera: el archivo ya está en su sitio, falta el índice. */
    static synchronized void restore(Context c, String id, String title, String folder, long updated) {
        ArrayList<Doc> docs = list(c);
        docs.removeIf(d -> d.id.equals(id));
        Doc d = new Doc();
        d.id = id;
        d.title = title;
        d.folder = folder == null ? "" : folder;
        d.updated = updated;
        docs.add(d);
        keep(c, docs);
    }

    static synchronized void delete(Context c, String id) {
        ArrayList<Doc> docs = list(c);
        docs.removeIf(d -> d.id.equals(id));
        keep(c, docs);
        file(c, id).delete();
    }
}
