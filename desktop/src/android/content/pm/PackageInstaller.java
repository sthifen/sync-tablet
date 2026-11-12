package android.content.pm;

import android.content.Intent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * En el escritorio, «instalar» es poner el AppImage nuevo en lugar del que
 * está abierto ($APPIMAGE) y volver a abrir la app. La suma ya la comprobó
 * Updater antes de llegar aquí.
 */
public class PackageInstaller {
    public static final String EXTRA_STATUS = "android.content.pm.extra.STATUS", EXTRA_STATUS_MESSAGE = "android.content.pm.extra.STATUS_MESSAGE",
            EXTRA_SESSION_ID = "android.content.pm.extra.SESSION_ID", ACTION_SESSION_COMMITTED = "android.content.pm.action.SESSION_COMMITTED";
    public static final int STATUS_PENDING_USER_ACTION = -1, STATUS_SUCCESS = 0, STATUS_FAILURE = 1, STATUS_FAILURE_BLOCKED = 2,
            STATUS_FAILURE_ABORTED = 3, STATUS_FAILURE_INVALID = 4, STATUS_FAILURE_CONFLICT = 5, STATUS_FAILURE_STORAGE = 6, STATUS_FAILURE_INCOMPATIBLE = 7;

    public static class SessionParams {
        public static final int MODE_FULL_INSTALL = 1, MODE_INHERIT_EXISTING = 2;
        public SessionParams(int mode) {}
        public void setAppPackageName(String p) {}
        public void setSize(long s) {}
        public void setInstallReason(int r) {}
        public void setRequireUserAction(int r) {}
        public void setPackageSource(int s) {}
        public void setInstallScenario(int s) {}
    }

    /** El AppImage que está abierto, o null si la app no se abrió desde uno. */
    public static File currentAppImage() {
        String p = System.getenv("APPIMAGE");
        if (p == null || p.isEmpty()) return null;
        File f = new File(p);
        return f.isFile() ? f : null;
    }

    public static class Session implements java.io.Closeable {
        private final File target = currentAppImage();
        private File temp;

        public OutputStream openWrite(String name, long offset, long length) throws IOException {
            if (target == null) throw new IOException("esta copia no se abrió desde un AppImage; actualízala con desktop/build.sh o baja el AppImage del release");
            if (!target.getParentFile().canWrite()) throw new IOException("no se puede escribir en " + target.getParent());
            temp = new File(target.getParentFile(), "." + target.getName() + ".nueva");
            return new FileOutputStream(temp);
        }

        public void fsync(OutputStream out) throws IOException { out.flush(); if (out instanceof FileOutputStream) ((FileOutputStream) out).getFD().sync(); }

        public void commit(android.content.IntentSender sender) {
            Intent result = sender == null || sender.intent() == null ? new Intent() : new Intent(sender.intent());
            boolean ok = false;
            String message = null;
            try {
                if (!temp.setExecutable(true, false)) throw new IOException("no se pudo marcar como ejecutable");
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                ok = true;
            } catch (IOException e) {
                message = e.getMessage();
                if (temp != null) temp.delete();
            }
            result.putExtra(EXTRA_STATUS, ok ? STATUS_SUCCESS : STATUS_FAILURE).putExtra(EXTRA_STATUS_MESSAGE, message);
            final boolean done = ok;
            android.os.MainQueue.post(this, () -> {
                android.app.Desktop d = android.app.Desktop.get();
                if (d == null) return;
                d.startActivity(null, result, -1);
                if (done) d.restart(target);
            }, 0, null);
        }

        public void abandon() { if (temp != null) temp.delete(); }
        @Override public void close() {}
    }

    public int createSession(SessionParams p) throws IOException {
        if (currentAppImage() == null) throw new IOException("esta copia no se abrió desde un AppImage; actualízala con desktop/build.sh o baja el AppImage del release");
        return 1;
    }

    public Session openSession(int id) { return new Session(); }
}
