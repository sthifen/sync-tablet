package dev.synctablet;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Conexión con el PC (`synctablet`, puerto 8788): primero por USB
 * (localhost, gracias al adb reverse del PC) y si no, por WiFi a la IP
 * guardada. Reintenta sola cada 2 s.
 *
 * Al conectar manda la versión de cada página; el PC pide las que le faltan.
 * Después, cada trazo o borrado se manda según ocurre. Sin conexión no se
 * acumula nada: al volver, el saludo con las versiones lo pone al día.
 *
 * Todo el JSON se genera en el hilo de la interfaz (dueño de las páginas).
 */
final class SyncClient {
    static final int PORT = 8788;

    interface Listener {
        void onStatus(boolean connected, String where);
    }

    private static SyncClient instance;

    static synchronized SyncClient get(Context c) {
        if (instance == null) {
            instance = new SyncClient(c.getApplicationContext());
            instance.start();
        }
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<String> out = new LinkedBlockingQueue<>();
    private volatile boolean connected;
    private volatile String where = "";
    /** Dirección del PC mientras hay conexión (para el HTTP de las notas). */
    private static volatile String host;

    static String currentHost() {
        return host;
    }

    private SyncClient(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences("sync", Context.MODE_PRIVATE);
    }

    String wifiHost() {
        return prefs.getString("host", "");
    }

    void setWifiHost(String host) {
        prefs.edit().putString("host", host.trim()).apply();
    }

    boolean isConnected() {
        return connected;
    }

    void addListener(Listener l) {
        listeners.add(l);
        l.onStatus(connected, where);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Manda un mensaje si hay conexión (si no, lo pondrá al día el saludo). */
    void send(String json) {
        if (connected) out.offer(json);
    }

    void sendNotebook(Ink.Notebook n) {
        try {
            JSONObject m = new JSONObject();
            m.put("t", "notebook");
            m.put("notebook", n.toJsonObject());
            send(m.toString());
        } catch (JSONException ignored) {
        }
    }

    void sendPage(Ink.Page p) {
        send("{\"t\":\"page\",\"page\":" + p.toJson() + "}");
    }

    /** Manda una imagen (en un hilo: leerla y codificarla tarda). */
    void sendAsset(String id) {
        if (!connected) return;
        new Thread(() -> {
            try {
                java.io.File f = Store.get(app).assetFile(id);
                byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
                String data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
                send("{\"t\":\"asset\",\"id\":\"" + id + "\",\"mime\":\"image/jpeg\",\"data\":\"" + data + "\"}");
            } catch (IOException e) {
                android.util.Log.w("SyncTablet", "no se pudo mandar la imagen " + id, e);
            }
        }, "sync-asset").start();
    }

    private void start() {
        Thread t = new Thread(this::loop, "sync");
        t.setDaemon(true);
        t.start();
    }

    private void loop() {
        while (true) {
            ArrayList<String> hosts = new ArrayList<>();
            hosts.add("127.0.0.1");
            String wifi = wifiHost();
            if (!wifi.isEmpty()) hosts.add(wifi);
            for (String host : hosts) {
                try (WebSocketClient ws = WebSocketClient.connect(host, PORT, "/ws/ink", 1500)) {
                    SyncClient.host = host;
                    session(ws, host.equals("127.0.0.1") ? "USB" : "WiFi " + host);
                } catch (IOException e) {
                    // Sin PC en ese camino: probar el siguiente.
                }
                SyncClient.host = null;
                setStatus(false, "");
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void session(WebSocketClient ws, String via) throws IOException {
        out.clear();
        Thread writer = new Thread(() -> {
            try {
                while (true) {
                    String m = out.poll(30, TimeUnit.SECONDS);
                    if (m != null) ws.sendText(m);
                }
            } catch (IOException | InterruptedException e) {
                ws.close();
            }
        }, "sync-out");
        writer.setDaemon(true);
        writer.start();
        setStatus(true, via);
        main.post(this::sendHello);
        main.post(() -> Notes.get(app).sync());
        try {
            while (true) {
                WebSocketClient.Message msg = ws.read();
                if (msg.opcode != WebSocketClient.TEXT) continue;
                JSONObject m = new JSONObject(msg.text());
                String t = m.optString("t");
                if ("note".equals(t) || "notes".equals(t)) {
                    // Una nota cambió en el PC.
                    main.post(() -> Notes.get(app).sync());
                } else if ("want_assets".equals(t)) {
                    JSONArray ids = m.getJSONArray("ids");
                    for (int i = 0; i < ids.length(); i++) sendAsset(ids.getString(i));
                } else if ("want".equals(m.optString("t"))) {
                    JSONArray ids = m.getJSONArray("pages");
                    final ArrayList<String> list = new ArrayList<>();
                    for (int i = 0; i < ids.length(); i++) list.add(ids.getString(i));
                    main.post(() -> {
                        Store store = Store.get(app);
                        for (String id : list) {
                            Ink.Page p = store.page(id);
                            if (p != null) sendPage(p);
                        }
                    });
                }
            }
        } catch (JSONException e) {
            throw new IOException(e);
        } finally {
            writer.interrupt();
        }
    }

    /** Saludo: nombre del aparato, versiones de las páginas y los cuadernos. */
    private void sendHello() {
        Store store = Store.get(app);
        try {
            JSONObject m = new JSONObject();
            m.put("t", "hello");
            m.put("device", deviceName());
            JSONObject pages = new JSONObject();
            for (Map.Entry<String, Long> e : store.versions().entrySet()) pages.put(e.getKey(), e.getValue());
            m.put("pages", pages);
            send(m.toString());
        } catch (JSONException ignored) {
        }
        for (Ink.Notebook n : store.notebooks()) sendNotebook(n);
    }

    private String deviceName() {
        String name = Settings.Global.getString(app.getContentResolver(), "device_name");
        return name != null && !name.isEmpty() ? name : Build.MODEL;
    }

    private void setStatus(boolean on, String via) {
        if (connected == on && where.equals(via)) return;
        connected = on;
        where = via;
        main.post(() -> {
            for (Listener l : listeners) l.onStatus(on, via);
        });
    }
}
