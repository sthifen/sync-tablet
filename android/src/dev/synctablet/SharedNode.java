package dev.synctablet;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * El nodo de cuadernos compartidos dentro de la app de la tablet, para cuando
 * no tiene su PC al lado. Habla exactamente el mismo protocolo que el del PC
 * (src/shared.rs): operaciones firmadas con ECDSA P-256, entrada con un reto
 * HMAC y líneas JSON por TCP en el puerto del servidor + 2.
 *
 * Con «Mantener vivo» la tablet hace de host: atiende conexiones, abre el puerto
 * en el router si puede (UPnP) y sigue en marcha con la app cerrada gracias a
 * un servicio en primer plano (KeepAliveService).
 */
final class SharedNode {
    private static final long ONLINE_MS = 75_000;
    private static final int MAX_LINE = 48 << 20;

    private static SharedNode instance;

    /** null en el PC (allí está el nodo del servidor). */
    static synchronized SharedNode get(Context c) {
        if (Updater.DESKTOP) return null;
        if (instance == null) {
            instance = new SharedNode(c.getApplicationContext());
            instance.start();
        }
        return instance;
    }

    /** Si ya existe (para no arrancarlo sólo por mirar). */
    static synchronized SharedNode peek() {
        return instance;
    }

    // ------------------------------------------------------------ estado

    static final class Peer {
        String node, name = "";
        final ArrayList<String> addrs = new ArrayList<>();
        boolean keep;
        long last, hereTs;
    }

    static final class Doc {
        String doc, notebook, title, role, secret, publicKey, signer;
        boolean keep;
        final ArrayList<Peer> peers = new ArrayList<>();
        final HashSet<String> deadStrokes = new HashSet<>(), deadPages = new HashSet<>();
        final ArrayList<JSONObject> ops = new ArrayList<>();
        final HashSet<String> ids = new HashSet<>();
        final ArrayList<JSONObject> pending = new ArrayList<>();
        final HashSet<String> assets = new HashSet<>();
        PublicKey verify;
        PrivateKey sign;
    }

    static final class Conn {
        final long id;
        final String doc, node;
        final LinkedBlockingQueue<String> out = new LinkedBlockingQueue<>();
        volatile boolean closed;

        Conn(long id, String doc, String node) {
            this.id = id;
            this.doc = doc;
            this.node = node;
        }

        void send(String line) {
            if (!closed) out.offer(line);
        }
    }

    private final Context app;
    private final File dir;
    final String node, name;
    private final int port;
    private final HashMap<String, Doc> docs = new HashMap<>();
    private final HashMap<Long, Conn> conns = new HashMap<>();
    private final HashSet<String> meAddrs = new HashSet<>();
    private volatile String publicAddr;
    private final AtomicLong next = new AtomicLong(1);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();

    private SharedNode(Context app) {
        this.app = app;
        this.dir = new File(app.getFilesDir(), "compartidos");
        dir.mkdirs();
        Account a = Account.get(app);
        this.node = a.deviceId();
        this.name = a.deviceName();
        this.port = SyncClient.PORT + 2;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.getName().endsWith(".json")) continue;
                try {
                    Doc d = load(f);
                    docs.put(d.doc, d);
                } catch (Exception e) {
                    android.util.Log.w("SyncTablet", "compartido " + f, e);
                }
            }
        }
    }

    // ------------------------------------------------------------ utilidades

    private String randomHex(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return Account.hex(b);
    }

    private static String hmac(String key, String data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Account.hex(m.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean same(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int d = 0;
        for (int i = 0; i < a.length(); i++) d |= a.charAt(i) ^ b.charAt(i);
        return d == 0;
    }

    private static byte[] b64url(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    private static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String signedText(String doc, String id, String body) {
        return doc + "\n" + id + "\n" + body;
    }

    private void keys(Doc d) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("EC");
        d.verify = kf.generatePublic(new X509EncodedKeySpec(b64url(d.publicKey)));
        d.sign = d.signer == null ? null : kf.generatePrivate(new PKCS8EncodedKeySpec(b64url(d.signer)));
    }

    private static boolean verify(Doc d, JSONObject op) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(d.verify);
            s.update(signedText(d.doc, op.getString("id"), op.getString("body")).getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getDecoder().decode(op.getString("sig")));
        } catch (Exception e) {
            return false;
        }
    }

    private JSONObject signOp(Doc d, String body) throws Exception {
        String id = randomHex(12);
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(d.sign);
        s.update(signedText(d.doc, id, body).getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("id", id).put("by", node).put("body", body).put("sig", Base64.getEncoder().encodeToString(s.sign()));
    }

    // ------------------------------------------------------------ disco

    private Doc load(File f) throws Exception {
        JSONObject m = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        Doc d = new Doc();
        d.doc = m.getString("doc");
        d.notebook = m.getString("notebook");
        d.title = m.optString("title");
        d.role = m.optString("role", "lector");
        d.secret = m.getString("secret");
        d.publicKey = m.getString("public");
        d.signer = m.isNull("signer") ? null : m.optString("signer", null);
        d.keep = m.optBoolean("keep");
        JSONArray ps = m.optJSONArray("peers");
        for (int i = 0; ps != null && i < ps.length(); i++) {
            JSONObject o = ps.getJSONObject(i);
            Peer p = new Peer();
            p.node = o.getString("node");
            p.name = o.optString("name");
            p.keep = o.optBoolean("keep");
            JSONArray a = o.optJSONArray("addrs");
            for (int k = 0; a != null && k < a.length(); k++) p.addrs.add(a.getString(k));
            d.peers.add(p);
        }
        addAll(d.deadStrokes, m.optJSONArray("dead_strokes"));
        addAll(d.deadPages, m.optJSONArray("dead_pages"));
        keys(d);
        File opsFile = new File(dir, d.doc + ".ops.jsonl");
        if (opsFile.isFile()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(opsFile), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    try {
                        JSONObject op = new JSONObject(line);
                        if (d.ids.add(op.getString("id"))) d.ops.add(op);
                    } catch (JSONException ignored) {
                    }
                }
            }
        }
        return d;
    }

    private static void addAll(HashSet<String> to, JSONArray a) throws JSONException {
        for (int i = 0; a != null && i < a.length(); i++) to.add(a.getString(i));
    }

    private void save(Doc d) {
        try {
            JSONObject m = new JSONObject().put("doc", d.doc).put("notebook", d.notebook).put("title", d.title).put("role", d.role)
                    .put("secret", d.secret).put("public", d.publicKey).put("signer", d.signer == null ? JSONObject.NULL : d.signer).put("keep", d.keep);
            JSONArray ps = new JSONArray();
            for (Peer p : d.peers) ps.put(new JSONObject().put("node", p.node).put("name", p.name).put("keep", p.keep).put("addrs", new JSONArray(p.addrs)));
            m.put("peers", ps).put("dead_strokes", new JSONArray(d.deadStrokes)).put("dead_pages", new JSONArray(d.deadPages));
            File f = new File(dir, d.doc + ".json"), tmp = new File(dir, d.doc + ".tmp");
            java.nio.file.Files.write(tmp.toPath(), m.toString(2).getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Exception e) {
            android.util.Log.w("SyncTablet", "guardar compartido", e);
        }
    }

    private void append(Doc d, List<JSONObject> ops) {
        if (ops.isEmpty()) return;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir, d.doc + ".ops.jsonl"), true), StandardCharsets.UTF_8)) {
            for (JSONObject op : ops) w.write(op.toString() + "\n");
        } catch (IOException e) {
            android.util.Log.w("SyncTablet", "operaciones compartidas", e);
        }
    }

    // ------------------------------------------------------------ API (como /api/shared del PC)

    synchronized JSONObject list() {
        try {
            JSONArray out = new JSONArray();
            long now = System.currentTimeMillis();
            for (Doc d : docs.values()) {
                HashSet<String> connected = new HashSet<>();
                for (Conn c : conns.values()) if (c.doc.equals(d.doc)) connected.add(c.node);
                JSONArray peers = new JSONArray();
                String host = d.keep ? name : null;
                for (Peer p : d.peers) {
                    if (p.node.equals(node)) continue;
                    boolean online = connected.contains(p.node) || now - p.last < ONLINE_MS;
                    peers.put(new JSONObject().put("node", p.node).put("name", p.name.isEmpty() ? p.node : p.name).put("keep", p.keep).put("online", online));
                    if (host == null && p.keep && online) host = p.name;
                }
                out.put(new JSONObject().put("doc", d.doc).put("notebook", d.notebook).put("title", d.title).put("role", d.role).put("keep", d.keep)
                        .put("host", host == null ? JSONObject.NULL : host).put("connected", connected.size()).put("peers", peers).put("ops", d.ops.size()));
            }
            return new JSONObject().put("node", node).put("name", name).put("public", publicAddr == null ? JSONObject.NULL : publicAddr).put("docs", out);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> myAddrs() {
        ArrayList<String> v = new ArrayList<>();
        String ip = Discovery.lanIp();
        if (ip != null) v.add(ip + ":" + port);
        if (publicAddr != null) v.add(publicAddr);
        return v;
    }

    private String link(Doc d, boolean editor) {
        ArrayList<String> addrs = new ArrayList<>(d.keep ? myAddrs() : new ArrayList<>());
        for (Peer p : d.peers) if (p.keep && !p.node.equals(node)) for (String a : p.addrs) if (!addrs.contains(a)) addrs.add(a);
        while (addrs.size() > 8) addrs.remove(addrs.size() - 1);
        StringBuilder s = new StringBuilder("synctablet://doc?d=").append(enc(d.doc)).append("&b=").append(enc(d.notebook))
                .append("&n=").append(enc(d.title)).append("&s=").append(enc(d.secret)).append("&k=").append(enc(d.publicKey))
                .append("&o=").append(enc(node)).append("&h=").append(enc(String.join(",", addrs)));
        if (editor && d.signer != null) s.append("&e=").append(enc(d.signer));
        return s.toString();
    }

    private static String enc(String v) {
        StringBuilder b = new StringBuilder();
        for (byte x : v.getBytes(StandardCharsets.UTF_8)) {
            int c = x & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~') b.append((char) c);
            else b.append(String.format("%%%02X", c));
        }
        return b.toString();
    }

    synchronized JSONObject links(String doc) throws IOException {
        Doc d = docs.get(doc);
        if (d == null) throw new IOException("Ese documento no está compartido aquí");
        try {
            return new JSONObject().put("doc", doc).put("editor", d.signer == null ? JSONObject.NULL : link(d, true)).put("lector", link(d, false)).put("keep", d.keep);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Comparte un cuaderno de la tablet: quien lo comparte es admin y host. */
    JSONObject share(String notebookId) throws IOException {
        synchronized (this) {
            for (Doc d : docs.values()) if (d.notebook.equals(notebookId)) return links(d.doc);
        }
        Store store = Store.get(app);
        Ink.Notebook nb = store.notebook(notebookId);
        if (nb == null) throw new IOException("No existe ese cuaderno");
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = g.generateKeyPair();
            Doc d = new Doc();
            d.doc = randomHex(16);
            d.notebook = nb.id;
            d.title = nb.title;
            d.role = "admin";
            d.secret = randomHex(16);
            d.publicKey = b64url(kp.getPublic().getEncoded());
            d.signer = b64url(kp.getPrivate().getEncoded());
            d.keep = true;
            keys(d);
            ArrayList<JSONObject> ops = new ArrayList<>();
            ArrayList<Ink.Page> pages = new ArrayList<>();
            for (String pid : nb.pages) {
                Ink.Page p = store.page(pid);
                if (p != null) pages.add(p);
            }
            for (Ink.Page p : pages) for (String a : pageAssets(new JSONObject(p.toJson()))) addAssetOp(d, ops, a);
            ops.add(signOp(d, new JSONObject().put("t", "notebook").put("notebook", nb.toJsonObject()).toString()));
            for (Ink.Page p : pages) ops.add(signOp(d, "{\"t\":\"page\",\"page\":" + p.toJson() + "}"));
            synchronized (this) {
                for (JSONObject op : ops) {
                    d.ids.add(op.getString("id"));
                    d.ops.add(op);
                }
                docs.put(d.doc, d);
                save(d);
                append(d, ops);
            }
            keepServiceUpdate();
            return links(d.doc);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("No se pudo compartir: " + e.getMessage());
        }
    }

    private void addAssetOp(Doc d, List<JSONObject> ops, String asset) throws Exception {
        if (d.assets.contains(asset)) return;
        File f = Store.get(app).assetFile(asset);
        if (!f.isFile()) return;
        String data = Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(f.toPath()));
        ops.add(signOp(d, new JSONObject().put("t", "asset").put("id", asset).put("mime", "image/jpeg").put("data", data).toString()));
        d.assets.add(asset);
    }

    private static List<String> pageAssets(JSONObject page) {
        ArrayList<String> v = new ArrayList<>();
        String bg = page.optString("bg_asset", "");
        if (!bg.isEmpty()) v.add(bg);
        JSONArray items = page.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && "image".equals(it.optString("kind")) && !it.optString("asset").isEmpty()) v.add(it.optString("asset"));
        }
        return v;
    }

    synchronized JSONObject join(String link) throws IOException {
        if (!link.trim().startsWith("synctablet://doc?")) throw new IOException("No es un enlace de Sync Tablet");
        android.net.Uri u = android.net.Uri.parse(link.trim());
        String doc = u.getQueryParameter("d"), notebook = u.getQueryParameter("b"), secret = u.getQueryParameter("s"), pub = u.getQueryParameter("k");
        if (doc == null || notebook == null || secret == null || pub == null || !doc.matches("[0-9a-f]+")) throw new IOException("Enlace dañado");
        String signer = u.getQueryParameter("e"), from = u.getQueryParameter("o"), hosts = u.getQueryParameter("h"), title = u.getQueryParameter("n");
        Peer host = new Peer();
        host.node = from == null ? "" : from;
        host.keep = true;
        if (hosts != null) for (String a : hosts.split(",")) if (!a.isEmpty()) host.addrs.add(a);
        try {
            Doc d = docs.get(doc);
            if (d != null) {
                if (d.signer == null && signer != null && !signer.isEmpty()) {
                    d.signer = signer;
                    d.role = "editor";
                    keys(d);
                }
                mergePeer(d, host);
                save(d);
            } else {
                d = new Doc();
                d.doc = doc;
                d.notebook = notebook;
                d.title = title == null ? "" : title;
                d.secret = secret;
                d.publicKey = pub;
                d.signer = signer == null || signer.isEmpty() ? null : signer;
                d.role = d.signer != null ? "editor" : "lector";
                keys(d);
                d.peers.add(host);
                docs.put(doc, d);
                save(d);
            }
            wake();
            return new JSONObject().put("doc", doc).put("title", d.title);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Enlace dañado: " + e.getMessage());
        }
    }

    private static void mergePeer(Doc d, Peer p) {
        for (Peer x : d.peers) {
            if (!p.node.isEmpty() && x.node.equals(p.node)) {
                for (String a : p.addrs) if (!x.addrs.contains(a)) x.addrs.add(a);
                x.keep |= p.keep;
                return;
            }
        }
        d.peers.add(p);
    }

    JSONObject setKeep(String doc, boolean on) throws IOException {
        synchronized (this) {
            Doc d = docs.get(doc);
            if (d == null) throw new IOException("No está compartido aquí");
            d.keep = on;
            save(d);
        }
        announce(doc);
        keepServiceUpdate();
        wake();
        return links(doc);
    }

    synchronized void leave(String doc) throws IOException {
        if (docs.remove(doc) == null) throw new IOException("No está compartido aquí");
        for (Conn c : conns.values()) if (c.doc.equals(doc)) c.closed = true;
        new File(dir, doc + ".json").delete();
        new File(dir, doc + ".ops.jsonl").delete();
        keepServiceUpdate();
    }

    synchronized boolean isEmpty() {
        return docs.isEmpty();
    }

    synchronized boolean anyKeep() {
        for (Doc d : docs.values()) if (d.keep) return true;
        return false;
    }

    synchronized int keptCount() {
        int n = 0;
        for (Doc d : docs.values()) if (d.keep) n++;
        return n;
    }

    /** En marcha con la app cerrada mientras algo se mantenga vivo. */
    private void keepServiceUpdate() {
        try {
            Class.forName("dev.synctablet.KeepAliveService").getMethod("update", Context.class).invoke(null, app);
        } catch (ReflectiveOperationException e) {
            // Sin servicio (no debería pasar en Android).
        }
    }

    // ------------------------------------------------------------ cambios locales

    /** Lo que la tablet manda (SyncClient.send): si es de un cuaderno compartido, a la red. */
    static void local(String json) {
        SharedNode n = peek();
        if (n == null || n.isEmpty()) return;
        new Thread(() -> n.publish(json), "compartido-local").start();
    }

    private void publish(String json) {
        try {
            JSONObject v = new JSONObject(json);
            String t = v.optString("t");
            Doc d = null;
            synchronized (this) {
                if (docs.isEmpty()) return;
                String nb = null;
                if ("stroke".equals(t) || "erase".equals(t) || "delete_page".equals(t)) {
                    String page = v.optString("page");
                    Ink.Page p = Store.get(app).page(page);
                    if (p != null) nb = p.notebook;
                    else for (Ink.Notebook x : Store.get(app).notebooks()) if (x.pages.contains(page)) nb = x.id;
                } else if ("page".equals(t)) nb = v.getJSONObject("page").optString("notebook");
                else if ("notebook".equals(t)) nb = v.getJSONObject("notebook").optString("id");
                if (nb == null) return;
                for (Doc x : docs.values()) if (x.notebook.equals(nb)) d = x;
                if (d == null) return;
                if (d.sign == null) return; // lector: se queda en la tablet
                ArrayList<JSONObject> ops = new ArrayList<>();
                if ("page".equals(t)) for (String a : pageAssets(v.getJSONObject("page"))) addAssetOp(d, ops, a);
                if ("erase".equals(t)) addAll(d.deadStrokes, v.optJSONArray("ids"));
                if ("delete_page".equals(t)) d.deadPages.add(v.optString("page"));
                ops.add(signOp(d, json));
                for (JSONObject op : ops) {
                    d.ids.add(op.getString("id"));
                    d.ops.add(op);
                }
                append(d, ops);
                if ("erase".equals(t) || "delete_page".equals(t)) save(d);
                String line = new JSONObject().put("t", "ops").put("ops", new JSONArray(ops)).toString();
                for (Conn c : conns.values()) if (c.doc.equals(d.doc)) c.send(line);
            }
        } catch (Exception e) {
            android.util.Log.w("SyncTablet", "publicar cambio compartido", e);
        }
    }

    // ------------------------------------------------------------ cambios de la red

    private void receive(String doc, long fromConn, JSONArray ops) {
        ArrayList<JSONObject> fresh = new ArrayList<>();
        Doc d;
        synchronized (this) {
            d = docs.get(doc);
            if (d == null) return;
            for (int i = 0; i < ops.length(); i++) {
                JSONObject op = ops.optJSONObject(i);
                if (op == null || d.ids.contains(op.optString("id"))) continue;
                if (!verify(d, op)) {
                    android.util.Log.w("SyncTablet", "«" + d.title + "»: cambio con firma inválida, descartado");
                    continue;
                }
                d.ids.add(op.optString("id"));
                d.ops.add(op);
                fresh.add(op);
            }
            if (fresh.isEmpty()) return;
            append(d, fresh);
            try {
                String line = new JSONObject().put("t", "ops").put("ops", new JSONArray(fresh)).toString();
                for (Conn c : conns.values()) if (c.id != fromConn && c.doc.equals(doc)) c.send(line);
            } catch (JSONException ignored) {
            }
        }
        final Doc fd = d;
        main.post(() -> {
            for (JSONObject op : fresh) apply(fd, op);
        });
    }

    /** En el hilo principal: guarda el cambio y lo enseña (sin volver a mandarlo). */
    private void apply(Doc d, JSONObject op) {
        Store store = Store.get(app);
        SyncClient sync = SyncClient.get(app);
        boolean retry = false;
        try {
            JSONObject v = new JSONObject(op.getString("body"));
            switch (v.optString("t")) {
                case "asset": {
                    String id = v.getString("id");
                    File f = store.assetFile(id);
                    if (!f.isFile() && id.matches("[A-Za-z0-9_-]{1,64}")) {
                        File tmp = new File(f.getPath() + ".part");
                        java.nio.file.Files.write(tmp.toPath(), Base64.getDecoder().decode(v.getString("data")));
                        if (!tmp.renameTo(f)) tmp.delete();
                    }
                    d.assets.add(id);
                    InkView open = SyncClient.open;
                    if (open != null) {
                        open.redrawPages();
                        open.invalidate();
                    }
                    break;
                }
                case "notebook": {
                    JSONObject nb = v.getJSONObject("notebook");
                    if (!d.notebook.equals(nb.optString("id"))) return;
                    Ink.Notebook cur = store.notebook(d.notebook);
                    JSONArray pages = nb.optJSONArray("pages");
                    ArrayList<String> list = new ArrayList<>();
                    for (int i = 0; pages != null && i < pages.length(); i++) list.add(pages.getString(i));
                    if (cur != null) {
                        nb.put("folder", cur.folder).put("favorite", cur.favorite);
                        for (String p : cur.pages) if (!list.contains(p)) list.add(p);
                    } else {
                        nb.put("folder", "Compartidos").put("favorite", false);
                    }
                    list.removeAll(d.deadPages);
                    nb.put("pages", new JSONArray(list));
                    d.title = nb.optString("title", d.title);
                    Remote.apply(app, sync, new JSONObject().put("t", "notebook").put("notebook", nb));
                    retry = true;
                    break;
                }
                case "page": {
                    JSONObject p = v.getJSONObject("page");
                    if (!d.notebook.equals(p.optString("notebook")) || d.deadPages.contains(p.optString("id"))) return;
                    Ink.Page cur = store.page(p.getString("id"));
                    if (cur != null) {
                        JSONObject mine = new JSONObject(cur.toJson());
                        p.put("strokes", union(mine.optJSONArray("strokes"), p.optJSONArray("strokes")));
                        p.put("items", union(p.optJSONArray("items"), mine.optJSONArray("items")));
                        p.put("version", cur.version + 1);
                    }
                    JSONArray strokes = p.optJSONArray("strokes"), kept = new JSONArray();
                    for (int i = 0; strokes != null && i < strokes.length(); i++) {
                        JSONObject s = strokes.getJSONObject(i);
                        if (!d.deadStrokes.contains(s.optString("id"))) kept.put(s);
                    }
                    p.put("strokes", kept);
                    Remote.apply(app, sync, new JSONObject().put("t", "page").put("page", p));
                    Ink.Notebook nb = store.notebook(d.notebook);
                    if (nb != null && !nb.pages.contains(p.getString("id"))) {
                        JSONObject nj = nb.toJsonObject();
                        JSONArray pl = nj.getJSONArray("pages");
                        pl.put(p.getString("id"));
                        Remote.apply(app, sync, new JSONObject().put("t", "notebook").put("notebook", nj));
                    }
                    retry = true;
                    break;
                }
                case "stroke": {
                    String page = v.getString("page");
                    JSONObject s = v.getJSONObject("stroke");
                    if (d.deadStrokes.contains(s.optString("id"))) return;
                    Ink.Page cur = store.page(page);
                    if (cur == null) {
                        if (d.pending.size() < 10_000) d.pending.add(op);
                        return;
                    }
                    Remote.apply(app, sync, new JSONObject().put("t", "stroke").put("page", page).put("version", cur.version + 1).put("stroke", s));
                    break;
                }
                case "erase": {
                    String page = v.getString("page");
                    addAll(d.deadStrokes, v.optJSONArray("ids"));
                    Ink.Page cur = store.page(page);
                    if (cur == null) return;
                    Remote.apply(app, sync, new JSONObject().put("t", "erase").put("page", page).put("version", cur.version + 1).put("ids", v.getJSONArray("ids")));
                    break;
                }
                case "delete_page": {
                    d.deadPages.add(v.getString("page"));
                    Remote.apply(app, sync, v);
                    break;
                }
                default:
            }
        } catch (Exception e) {
            android.util.Log.w("SyncTablet", "cambio compartido no aplicado", e);
        }
        if (retry && !d.pending.isEmpty()) {
            ArrayList<JSONObject> parked = new ArrayList<>(d.pending);
            d.pending.clear();
            for (JSONObject p : parked) apply(d, p);
        }
    }

    /** Lo de `a` y lo de `b` que no esté ya (por id). */
    private static JSONArray union(JSONArray a, JSONArray b) throws JSONException {
        JSONArray out = new JSONArray();
        HashSet<String> ids = new HashSet<>();
        for (JSONArray arr : new JSONArray[] {a, b}) {
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (ids.add(o.optString("id"))) out.put(o);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ presencia

    private void announce(String doc) {
        try {
            synchronized (this) {
                Doc d = docs.get(doc);
                if (d == null) return;
                String line = new JSONObject().put("t", "here").put("node", node).put("name", name).put("keep", d.keep)
                        .put("addrs", new JSONArray(d.keep ? myAddrs() : new ArrayList<String>())).put("ts", System.currentTimeMillis()).toString();
                for (Conn c : conns.values()) if (c.doc.equals(doc)) c.send(line);
            }
        } catch (JSONException ignored) {
        }
    }

    private void heard(String doc, long fromConn, JSONObject v) {
        String n = v.optString("node");
        long ts = v.optLong("ts");
        if (n.isEmpty() || n.equals(node)) return;
        synchronized (this) {
            Doc d = docs.get(doc);
            if (d == null) return;
            Peer p = null;
            for (Peer x : d.peers) if (x.node.equals(n)) p = x;
            if (p == null) {
                p = new Peer();
                p.node = n;
                d.peers.add(p);
            }
            if (ts <= p.hereTs) return;
            p.hereTs = ts;
            p.last = System.currentTimeMillis();
            p.name = v.optString("name");
            p.keep = v.optBoolean("keep");
            JSONArray a = v.optJSONArray("addrs");
            if ((a != null && a.length() > 0) || !p.keep) {
                p.addrs.clear();
                for (int i = 0; a != null && i < a.length(); i++) p.addrs.add(a.optString(i));
            }
            for (Conn c : conns.values()) if (c.id != fromConn && c.doc.equals(doc)) c.send(v.toString());
            save(d);
        }
    }

    // ------------------------------------------------------------ red

    private final Object wakeLock = new Object();

    private void wake() {
        synchronized (wakeLock) {
            wakeLock.notifyAll();
        }
    }

    private void start() {
        Thread acceptor = new Thread(this::acceptLoop, "compartido-escucha");
        acceptor.setDaemon(true);
        acceptor.start();
        Thread sup = new Thread(this::supervise, "compartido");
        sup.setDaemon(true);
        sup.start();
    }

    private void acceptLoop() {
        while (true) {
            try (ServerSocket ss = new ServerSocket()) {
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(port));
                while (true) {
                    Socket s = ss.accept();
                    new Thread(() -> session(s, null), "compartido-in").start();
                }
            } catch (IOException e) {
                android.util.Log.w("SyncTablet", "compartido: no se pudo escuchar en " + port, e);
                sleep(30_000);
            }
        }
    }

    private void supervise() {
        long lastHere = 0, lastUpnp = 0;
        while (true) {
            ArrayList<String[]> targets = new ArrayList<>();
            synchronized (this) {
                for (Doc d : docs.values()) {
                    for (Peer p : d.peers) {
                        if (!p.keep || p.node.equals(node) || p.addrs.isEmpty()) continue;
                        boolean connected = false;
                        for (Conn c : conns.values()) connected |= c.doc.equals(d.doc) && c.node.equals(p.node);
                        if (!connected) targets.add(new String[] {d.doc, String.join(",", p.addrs)});
                    }
                }
            }
            for (String[] t : targets) {
                new Thread(() -> {
                    for (String a : t[1].split(",")) {
                        if (a.isEmpty() || meAddrs.contains(a)) continue;
                        int colon = a.lastIndexOf(':');
                        if (colon <= 0) continue;
                        try {
                            Socket s = new Socket();
                            s.connect(new InetSocketAddress(a.substring(0, colon), Integer.parseInt(a.substring(colon + 1))), 4000);
                            String r = session(s, t[0]);
                            if ("self".equals(r)) meAddrs.add(a);
                            break;
                        } catch (IOException | NumberFormatException ignored) {
                            // El siguiente.
                        }
                    }
                }, "compartido-out").start();
            }
            long now = System.currentTimeMillis();
            if (now - lastHere > 30_000) {
                lastHere = now;
                ArrayList<String> ids;
                synchronized (this) {
                    ids = new ArrayList<>(docs.keySet());
                }
                for (String d : ids) announce(d);
            }
            if (anyKeep() && now - lastUpnp > 30 * 60_000) {
                lastUpnp = now;
                new Thread(this::upnp, "upnp").start();
            }
            synchronized (wakeLock) {
                try {
                    wakeLock.wait(10_000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (true) {
            int c = in.read();
            if (c < 0) return b.size() == 0 ? null : b.toString("UTF-8");
            if (c == '\n') return b.toString("UTF-8");
            b.write(c);
            if (b.size() > MAX_LINE) throw new IOException("mensaje demasiado grande");
        }
    }

    private static void writeLine(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private synchronized JSONObject helloFields(String doc) throws JSONException {
        Doc d = docs.get(doc);
        boolean keep = d != null && d.keep;
        return new JSONObject().put("node", node).put("name", name).put("keep", keep).put("addrs", new JSONArray(keep ? myAddrs() : new ArrayList<String>()));
    }

    private synchronized String secret(String doc) {
        Doc d = docs.get(doc);
        return d == null ? null : d.secret;
    }

    /** Una conexión con otro nodo. Devuelve "self" si resultó ser este mismo. */
    private String session(Socket socket, String outgoingDoc) {
        Conn conn = null;
        try (Socket s = socket) {
            s.setTcpNoDelay(true);
            s.setSoTimeout(90_000);
            InputStream in = new java.io.BufferedInputStream(s.getInputStream(), 1 << 16);
            OutputStream out = new java.io.BufferedOutputStream(s.getOutputStream(), 1 << 16);
            String doc;
            JSONObject peer;
            if (outgoingDoc == null) {
                String nonce = randomHex(16);
                writeLine(out, new JSONObject().put("t", "hi").put("v", 1).put("node", node).put("nonce", nonce).toString());
                String l = readLine(in);
                if (l == null) return null;
                JSONObject j = new JSONObject(l);
                doc = j.optString("doc");
                String sec = secret(doc);
                if (sec == null || !same(j.optString("proof"), hmac(sec, "join\n" + nonce))) return null;
                JSONObject ok = helloFields(doc).put("t", "ok").put("proof", hmac(sec, "ok\n" + j.optString("nonce")));
                writeLine(out, ok.toString());
                peer = j;
            } else {
                doc = outgoingDoc;
                String sec = secret(doc);
                if (sec == null) return null;
                String l = readLine(in);
                if (l == null) return null;
                JSONObject hi = new JSONObject(l);
                if (node.equals(hi.optString("node"))) return "self";
                String nonce = randomHex(16);
                JSONObject join = helloFields(doc).put("t", "join").put("doc", doc).put("nonce", nonce).put("proof", hmac(sec, "join\n" + hi.optString("nonce")));
                writeLine(out, join.toString());
                l = readLine(in);
                if (l == null) return null;
                JSONObject ok = new JSONObject(l);
                if (!same(ok.optString("proof"), hmac(sec, "ok\n" + nonce))) return null;
                peer = ok;
            }
            String peerNode = peer.optString("node");
            if (peerNode.isEmpty() || peerNode.equals(node)) return "self";
            peer.put("ts", System.currentTimeMillis());
            heard(doc, 0, peer);
            conn = new Conn(next.getAndIncrement(), doc, peerNode);
            final Conn fc = conn;
            synchronized (this) {
                conns.put(conn.id, conn);
                Doc d = docs.get(doc);
                JSONArray ids = new JSONArray();
                if (d != null) for (JSONObject op : d.ops) ids.put(op.optString("id"));
                conn.send(new JSONObject().put("t", "inv").put("ids", ids).toString());
            }
            announce(doc);
            Thread writer = new Thread(() -> {
                try {
                    while (!fc.closed) {
                        String m = fc.out.poll(30, java.util.concurrent.TimeUnit.SECONDS);
                        writeLine(out, m != null ? m : "{\"t\":\"ping\"}");
                    }
                } catch (IOException | InterruptedException e) {
                    fc.closed = true;
                }
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }, "compartido-envía");
            writer.setDaemon(true);
            writer.start();
            for (String l; !conn.closed && (l = readLine(in)) != null; ) {
                JSONObject j;
                try {
                    j = new JSONObject(l);
                } catch (JSONException e) {
                    continue;
                }
                switch (j.optString("t")) {
                    case "inv":
                        sendMissing(conn, j.optJSONArray("ids"));
                        break;
                    case "ops":
                        receive(doc, conn.id, j.optJSONArray("ops"));
                        break;
                    case "here":
                        heard(doc, conn.id, j);
                        break;
                    default:
                }
            }
        } catch (IOException | JSONException e) {
            // Se cortó: el supervisor vuelve a intentarlo.
        } finally {
            if (conn != null) {
                conn.closed = true;
                synchronized (this) {
                    conns.remove(conn.id);
                }
            }
        }
        return null;
    }

    private void sendMissing(Conn c, JSONArray theirs) throws JSONException {
        HashSet<String> have = new HashSet<>();
        for (int i = 0; theirs != null && i < theirs.length(); i++) have.add(theirs.getString(i));
        synchronized (this) {
            Doc d = docs.get(c.doc);
            if (d == null) return;
            JSONArray batch = new JSONArray();
            long size = 0;
            for (JSONObject op : d.ops) {
                if (have.contains(op.optString("id"))) continue;
                batch.put(op);
                size += op.optString("body").length();
                if (size > 4 << 20 || batch.length() >= 500) {
                    c.send(new JSONObject().put("t", "ops").put("ops", batch).toString());
                    batch = new JSONArray();
                    size = 0;
                }
            }
            if (batch.length() > 0) c.send(new JSONObject().put("t", "ops").put("ops", batch).toString());
        }
    }

    // ------------------------------------------------------------ UPnP

    /** Pide al router que abra el puerto (si sabe UPnP) y apunta la dirección pública. */
    private void upnp() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(3000);
            byte[] q = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n"
                    + "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            s.send(new DatagramPacket(q, q.length, InetAddress.getByName("239.255.255.250"), 1900));
            byte[] buf = new byte[2048];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            String resp = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII);
            String location = null;
            for (String line : resp.split("\r\n")) if (line.toLowerCase(java.util.Locale.ROOT).startsWith("location:")) location = line.substring(9).trim();
            if (location == null) return;
            String desc = new String(get(location), StandardCharsets.UTF_8);
            String service = null, control = null;
            for (String type : new String[] {"urn:schemas-upnp-org:service:WANIPConnection:1", "urn:schemas-upnp-org:service:WANPPPConnection:1", "urn:schemas-upnp-org:service:WANIPConnection:2"}) {
                int i = desc.indexOf(type);
                if (i < 0) continue;
                int c0 = desc.indexOf("<controlURL>", i), c1 = desc.indexOf("</controlURL>", c0);
                if (c0 < 0 || c1 < 0) continue;
                service = type;
                control = new URL(new URL(location), desc.substring(c0 + 12, c1).trim()).toString();
                break;
            }
            String ip = Discovery.lanIp();
            if (control == null || ip == null) return;
            soap(control, service, "AddPortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + port + "</NewExternalPort><NewProtocol>TCP</NewProtocol>"
                    + "<NewInternalPort>" + port + "</NewInternalPort><NewInternalClient>" + ip + "</NewInternalClient><NewEnabled>1</NewEnabled>"
                    + "<NewPortMappingDescription>Sync Tablet</NewPortMappingDescription><NewLeaseDuration>3600</NewLeaseDuration>");
            String r = soap(control, service, "GetExternalIPAddress", "");
            int a = r.indexOf("<NewExternalIPAddress>"), b = r.indexOf("</NewExternalIPAddress>");
            if (a >= 0 && b > a) {
                publicAddr = r.substring(a + 22, b).trim() + ":" + port;
                meAddrs.add(publicAddr);
                android.util.Log.i("SyncTablet", "compartido: el router abrió el puerto; desde internet: " + publicAddr);
            }
        } catch (IOException e) {
            publicAddr = null;
            android.util.Log.i("SyncTablet", "compartido: el router no abrió el puerto (UPnP): " + e.getMessage());
        }
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static String soap(String control, String service, String action, String args) throws IOException {
        String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + service + "\">" + args + "</u:" + action + "></s:Body></s:Envelope>";
        HttpURLConnection c = (HttpURLConnection) new URL(control).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        c.setRequestProperty("SOAPAction", "\"" + service + "#" + action + "\"");
        try {
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() != 200) throw new IOException(action + ": " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
