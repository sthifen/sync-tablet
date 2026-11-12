package dev.synctablet;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * La cuenta en el tracker (ver tracker/PROTOCOLO.md): usuario y contraseña
 * iguales en la tablet, el celular y el PC, para que se encuentren solos.
 *
 * La contraseña no sale del aparato: se manda una clave derivada con PBKDF2,
 * igual que en el visor y en tracker/src/crypto.ts. Todo lo de red es
 * bloqueante: se llama desde un hilo.
 */
final class Account {
    static final String DEFAULT_TRACKER = "https://synctablet-tracker.sthifen.workers.dev";
    static final int KEY_ITERATIONS = 200_000;

    private static Account instance;

    static synchronized Account get(Context c) {
        if (instance == null) {
            instance = new Account(c.getApplicationContext());
            instance.startHeartbeats();
        }
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;

    private Account(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences("account", Context.MODE_PRIVATE);
        if (prefs.getString("device_id", "").length() < 8) {
            byte[] b = new byte[12];
            new SecureRandom().nextBytes(b);
            prefs.edit().putString("device_id", kind() + "-" + hex(b)).apply();
        }
    }

    String user() {
        return session().isEmpty() ? "" : prefs.getString("user", "");
    }

    boolean loggedIn() {
        return !session().isEmpty();
    }

    /** El elegido; si no, el que usa el PC de esta WiFi; si no, el publicado por defecto. */
    String tracker() {
        String t = prefs.getString("tracker", "");
        if (!t.isEmpty()) return t;
        for (Discovery.Found f : SyncClient.nearby) if (f.tracker.startsWith("http")) return f.tracker;
        return DEFAULT_TRACKER;
    }

    /** El servidor de cuentas que usa el PC (llega en su QR): el que se usará al entrar. */
    void rememberTracker(String url) {
        if (!loggedIn()) prefs.edit().putString("tracker", url.trim().replaceAll("/+$", "")).apply();
    }

    String deviceId() {
        return prefs.getString("device_id", "");
    }

    private String session() {
        return prefs.getString("session", "");
    }

    /** pc (el servidor), desktop (esta app en Linux), tablet o phone. */
    String kind() {
        if (Updater.DESKTOP) return "desktop";
        return app.getResources().getConfiguration().smallestScreenWidthDp >= 600 ? "tablet" : "phone";
    }

    String deviceName() {
        String name = Settings.Global.getString(app.getContentResolver(), "device_name");
        return name != null && !name.isEmpty() ? name : Build.MODEL;
    }

    // ------------------------------------------------------------ sesión

    /** Entra o crea la cuenta. Lanza IOException con un mensaje para enseñar. */
    void login(String tracker, String user, String password, boolean register) throws IOException {
        tracker = tracker.trim().replaceAll("/+$", "");
        if (tracker.isEmpty()) tracker = DEFAULT_TRACKER;
        if (!tracker.startsWith("https://") && !tracker.startsWith("http://")) throw new IOException("La dirección del servidor de cuentas debe empezar por https://");
        user = user.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            JSONObject device = new JSONObject().put("id", deviceId()).put("name", deviceName()).put("kind", kind());
            JSONObject body = new JSONObject().put("user", user).put("key", deriveKey(user, password)).put("device", device);
            Reply r = call("POST", tracker + (register ? "/v1/register" : "/v1/login"), null, body);
            r.check();
            prefs.edit().putString("tracker", tracker).putString("user", r.body.optString("user", user))
                    .putString("session", r.body.getString("session")).apply();
        } catch (JSONException e) {
            throw new IOException("Respuesta rara del servidor de cuentas");
        }
        try {
            heartbeat();
        } catch (IOException ignored) {
            // Ya avisará el próximo.
        }
    }

    void logout() {
        String s = session();
        if (!s.isEmpty()) {
            try {
                call("POST", tracker() + "/v1/logout", s, new JSONObject());
            } catch (IOException ignored) {
                // Sin internet: la sesión se olvida aquí igual.
            }
        }
        forget();
    }

    private void forget() {
        prefs.edit().remove("session").remove("user").apply();
    }

    private Reply authed(String method, String path, JSONObject body) throws IOException {
        String s = session();
        if (s.isEmpty()) throw new IOException("No has iniciado sesión");
        Reply r = call(method, tracker() + path, s, body);
        if (r.status == 401) forget();
        r.check();
        return r;
    }

    void heartbeat() throws IOException {
        try {
            JSONArray lan = new JSONArray();
            String ip = Discovery.lanIp();
            if (ip != null) lan.put(ip + ":" + SyncClient.PORT);
            authed("POST", "/v1/heartbeat", new JSONObject().put("name", deviceName()).put("lan", lan).put("server", false));
        } catch (JSONException ignored) {
        }
    }

    JSONArray devices() throws IOException {
        JSONArray a = authed("GET", "/v1/devices", null).body.optJSONArray("devices");
        return a != null ? a : new JSONArray();
    }

    void removeDevice(String id) throws IOException {
        authed("DELETE", "/v1/devices/" + id.replaceAll("[^A-Za-z0-9_-]", ""), null);
    }

    /** Un boleto de un solo uso para entrar en ese aparato (el PC) de la cuenta. */
    String ticket(String forDevice) throws IOException {
        try {
            return authed("POST", "/v1/tickets", new JSONObject().put("for", forDevice)).body.getString("ticket");
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private void startHeartbeats() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    if (loggedIn()) heartbeat();
                } catch (IOException ignored) {
                    // Sin internet: lo intenta en un minuto.
                }
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "account");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------- HTTP

    static final class Reply {
        final int status;
        final JSONObject body;

        Reply(int status, JSONObject body) {
            this.status = status;
            this.body = body;
        }

        void check() throws IOException {
            if (status >= 200 && status < 300) return;
            String e = body.optString("error", "");
            throw new IOException(e.isEmpty() ? "Error " + status + " del servidor de cuentas" : capitalize(e));
        }
    }

    static Reply call(String method, String url, String bearer, JSONObject body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestMethod(method);
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
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
                    byte[] b = new byte[4096];
                    for (int k; (k = i.read(b)) > 0; ) out.write(b, 0, k);
                }
            }
            JSONObject o;
            try {
                o = new JSONObject(out.toString("UTF-8"));
            } catch (JSONException e) {
                o = new JSONObject();
            }
            return new Reply(status, o);
        } catch (java.net.UnknownHostException | java.net.ConnectException | java.net.SocketTimeoutException e) {
            throw new IOException("Sin conexión con el servidor de cuentas");
        } finally {
            c.disconnect();
        }
    }

    // ----------------------------------------------------------- cifrado

    /** PBKDF2-HMAC-SHA256(contraseña, "synctablet:" + usuario, 200 000, 32 bytes), en hex. */
    static String deriveKey(String user, String password) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(("synctablet:" + user).getBytes(StandardCharsets.UTF_8));
            mac.update(new byte[] {0, 0, 0, 1});
            byte[] u = mac.doFinal();
            byte[] t = u.clone();
            for (int i = 1; i < KEY_ITERATIONS; i++) {
                u = mac.doFinal(u);
                for (int j = 0; j < t.length; j++) t[j] ^= u[j];
            }
            return hex(t);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("No se pudo preparar la contraseña");
        }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
