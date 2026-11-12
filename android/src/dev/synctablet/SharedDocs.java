package dev.synctablet;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Cuadernos compartidos con otras personas (ver src/shared.rs): la app habla
 * con un nodo. En el PC es el de su servidor; en la tablet, el de su PC si está
 * conectada, y si no, el suyo propio (`SharedNode`, que corre dentro de la app).
 * Todo es bloqueante: se llama desde un hilo.
 */
final class SharedDocs {
    private SharedDocs() {}

    /** ¿Hay un nodo a mano? (un PC conectado, o el de la propia tablet). */
    static boolean available(Context c) {
        return Updater.DESKTOP || SyncClient.currentHost() != null || SharedNode.get(c) != null;
    }

    /** Dónde está el nodo: «este PC», «el PC» o «esta tablet». */
    static String where(Context c) {
        if (Updater.DESKTOP) return "este PC";
        return SyncClient.currentHost() != null ? "tu PC" : "esta tablet";
    }

    private static boolean useLocalNode() {
        return !Updater.DESKTOP && SyncClient.currentHost() == null;
    }

    static JSONObject list(Context c) throws IOException {
        if (useLocalNode()) return node(c).list();
        return call("GET", "/api/shared", null);
    }

    static JSONObject share(Context c, String notebook) throws IOException {
        if (useLocalNode()) return node(c).share(notebook);
        return call("POST", "/api/shared/share", obj("notebook", notebook));
    }

    static JSONObject join(Context c, String link) throws IOException {
        if (useLocalNode()) return node(c).join(link);
        return call("POST", "/api/shared/join", obj("link", link));
    }

    static JSONObject links(Context c, String doc) throws IOException {
        if (useLocalNode()) return node(c).links(doc);
        return call("GET", "/api/shared/" + doc + "/links", null);
    }

    static JSONObject keep(Context c, String doc, boolean on) throws IOException {
        if (useLocalNode()) return node(c).setKeep(doc, on);
        try {
            return call("POST", "/api/shared/" + doc + "/keep", new JSONObject().put("on", on));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    static void leave(Context c, String doc) throws IOException {
        if (useLocalNode()) {
            node(c).leave(doc);
            return;
        }
        call("POST", "/api/shared/" + doc + "/leave", new JSONObject());
    }

    /** El QR de un enlace (el PC lo dibuja; en la tablet sola, el lector de QR de la app). */
    static Bitmap qr(Context c, String doc, String link, boolean editor) throws IOException {
        if (useLocalNode()) {
            try {
                return (Bitmap) Class.forName("dev.synctablet.QrScanActivity").getMethod("encode", String.class, int.class).invoke(null, link, 600);
            } catch (ReflectiveOperationException e) {
                throw new IOException("sin QR en este aparato");
            }
        }
        byte[] png = raw("GET", "/api/shared/" + doc + "/qr.png?role=" + (editor ? "editor" : "lector"), null);
        return android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
    }

    private static SharedNode node(Context c) throws IOException {
        SharedNode n = SharedNode.get(c);
        if (n == null) throw new IOException("Conecta la tablet al PC o a una red WiFi");
        return n;
    }

    // ------------------------------------------------------------- HTTP

    private static JSONObject obj(String k, String v) {
        try {
            return new JSONObject().put(k, v);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONObject call(String method, String path, JSONObject body) throws IOException {
        byte[] b = raw(method, path, body);
        try {
            return new JSONObject(new String(b, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("respuesta rara del PC");
        }
    }

    private static byte[] raw(String method, String path, JSONObject body) throws IOException {
        String host = Updater.DESKTOP ? "127.0.0.1" : SyncClient.currentHost();
        if (host == null) throw new IOException("Sin conexión con el PC");
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + SyncClient.PORT + path).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(30000);
        c.setRequestMethod(method);
        if (!SyncClient.token.isEmpty() && !"127.0.0.1".equals(host)) c.setRequestProperty("Authorization", "Bearer " + SyncClient.token);
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = c.getOutputStream()) {
                    o.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) {
                try (InputStream i = in) {
                    byte[] buf = new byte[8192];
                    for (int k; (k = i.read(buf)) > 0; ) out.write(buf, 0, k);
                }
            }
            if (status >= 400) {
                String msg = "Error " + status;
                try {
                    msg = new JSONObject(out.toString("UTF-8")).optString("error", msg);
                } catch (JSONException ignored) {
                }
                throw new IOException(msg);
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
