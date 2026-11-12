package dev.synctablet.desktop;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * El servidor (src/, el que habla con la tablet y el celular) va dentro de la
 * app: se arranca solo al abrirla y se cierra con ella. Si ya había uno en
 * marcha (por ejemplo, lanzado a mano), se usa ese.
 */
final class Server {
    private Server() {}

    static void start() {
        int port = Integer.getInteger("synctablet.port", 8788);
        if (listening(port)) return;
        String bin = System.getProperty("synctablet.server", "");
        if (bin.isEmpty() || !new File(bin).canExecute()) {
            android.util.Log.w("SyncTablet", "sin servidor junto a la app (" + bin + "): la tablet no podrá conectarse");
            return;
        }
        File log = new File(System.getProperty("user.home"), ".local/share/synctablet/servidor.log");
        log.getParentFile().mkdirs();
        try {
            Process p = new ProcessBuilder(bin, "--no-open", "--port", String.valueOf(port))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                    .start();
            Runtime.getRuntime().addShutdownHook(new Thread(p::destroy, "servidor"));
            // Que esté escuchando antes de que la app intente conectarse.
            for (int i = 0; i < 50 && p.isAlive() && !listening(port); i++) Thread.sleep(100);
        } catch (Exception e) {
            android.util.Log.w("SyncTablet", "no se pudo arrancar el servidor", e);
        }
    }

    private static boolean listening(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
