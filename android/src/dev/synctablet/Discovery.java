package dev.synctablet;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Busca PCs con Sync Tablet en la WiFi: manda «SYNCTABLET?» por difusión UDP
 * al puerto del servidor y apunta quién contesta (ver src/account.rs). No
 * necesita internet ni permisos especiales.
 */
final class Discovery {
    /** Un PC que contestó. */
    static final class Found {
        final String host;
        final int port;
        final String name, device, user;
        /** El servidor de cuentas que usa ese PC («» si no lo dice). */
        String tracker = "";

        Found(String host, int port, String name, String device, String user) {
            this.host = host;
            this.port = port;
            this.name = name;
            this.device = device;
            this.user = user;
        }
    }

    private Discovery() {}

    static List<Found> probe(int port, int waitMs) {
        ArrayList<Found> found = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            byte[] msg = "SYNCTABLET? v1".getBytes(StandardCharsets.US_ASCII);
            for (InetAddress to : broadcasts()) {
                try {
                    s.send(new DatagramPacket(msg, msg.length, to, port));
                } catch (IOException ignored) {
                    // Esa red no deja difundir: probar las demás.
                }
            }
            long end = System.currentTimeMillis() + waitMs;
            byte[] buf = new byte[1024];
            while (true) {
                int left = (int) (end - System.currentTimeMillis());
                if (left <= 0) break;
                s.setSoTimeout(left);
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    break;
                }
                try {
                    JSONObject o = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                    if (!"synctablet".equals(o.optString("t"))) continue;
                    String host = p.getAddress().getHostAddress();
                    if (!seen.add(host)) continue;
                    Found f = new Found(host, o.optInt("port", port), o.optString("name", host), o.optString("device", ""),
                            o.isNull("user") ? "" : o.optString("user", ""));
                    f.tracker = o.optString("tracker", "");
                    found.add(f);
                } catch (JSONException ignored) {
                    // No es un PC con Sync Tablet.
                }
            }
        } catch (IOException e) {
            android.util.Log.w("SyncTablet", "búsqueda por WiFi", e);
        }
        return found;
    }

    private static List<InetAddress> broadcasts() {
        ArrayList<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress a : ni.getInterfaceAddresses()) {
                    if (a.getBroadcast() != null) out.add(a.getBroadcast());
                }
            }
        } catch (IOException ignored) {
        }
        try {
            out.add(InetAddress.getByName("255.255.255.255"));
        } catch (IOException ignored) {
        }
        return out;
    }

    /** La IPv4 de este aparato en la red local (la WiFi), o null. */
    static String lanIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.getName().startsWith("docker") || ni.getName().startsWith("rmnet")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }
}
