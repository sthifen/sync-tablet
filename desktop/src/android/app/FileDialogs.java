package android.app;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Selectores de archivos del sistema: zenity (GTK, el de GNOME) o, si no está, el de Java. */
final class FileDialogs {
    private static File lastDir;

    private static boolean hasZenity() {
        for (String p : System.getenv().getOrDefault("PATH", "/usr/bin").split(":")) if (new File(p, "zenity").canExecute()) return true;
        return false;
    }

    /** Extensiones para un tipo MIME de Android. */
    static List<String> extensions(String mime) {
        ArrayList<String> e = new ArrayList<>();
        if (mime == null) return e;
        String m = mime.toLowerCase(Locale.ROOT);
        switch (m) {
            case "image/*": e.add("png"); e.add("jpg"); e.add("jpeg"); e.add("gif"); e.add("bmp"); e.add("webp"); break;
            case "application/pdf": e.add("pdf"); break;
            case "image/png": e.add("png"); break;
            case "image/jpeg": e.add("jpg"); e.add("jpeg"); break;
            case "application/zip": e.add("zip"); break;
            case "text/plain": e.add("txt"); break;
            case "text/markdown": e.add("md"); break;
            case "text/csv": case "text/comma-separated-values": e.add("csv"); break;
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document": e.add("docx"); break;
            case "application/msword": e.add("doc"); break;
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": e.add("xlsx"); break;
            case "application/vnd.ms-excel": e.add("xls"); break;
            case "application/vnd.oasis.opendocument.spreadsheet": e.add("ods"); break;
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation": e.add("pptx"); break;
            case "application/vnd.ms-powerpoint": e.add("ppt"); break;
            case "model/stl": case "application/sla": e.add("stl"); break;
            case "model/obj": e.add("obj"); break;
            default:
        }
        return e;
    }

    private static List<String> extensions(String type, String[] mimes) {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        if (mimes != null) for (String m : mimes) {
            List<String> x = extensions(m);
            if (x.isEmpty()) return new ArrayList<>();
            all.addAll(x);
        } else if (type != null && !type.equals("*/*")) {
            all.addAll(extensions(type));
        }
        return new ArrayList<>(all);
    }

    /** Para pruebas: el próximo selector devuelve este archivo sin preguntar. */
    static volatile File nextPick;

    static void open(java.awt.Component parent, String type, String[] mimes, boolean multiple, Consumer<List<File>> done) {
        File pick = nextPick;
        if (pick != null) { nextPick = null; SwingUtilities.invokeLater(() -> done.accept(java.util.Collections.singletonList(pick))); return; }
        List<String> exts = extensions(type, mimes);
        if (hasZenity()) {
            ArrayList<String> cmd = new ArrayList<>();
            cmd.add("zenity"); cmd.add("--file-selection"); cmd.add("--title=Abrir");
            if (multiple) { cmd.add("--multiple"); cmd.add("--separator=\n"); }
            if (lastDir != null) cmd.add("--filename=" + lastDir.getPath() + "/");
            if (!exts.isEmpty()) {
                StringBuilder f = new StringBuilder("--file-filter=Compatibles |");
                for (String x : exts) f.append(" *.").append(x).append(" *.").append(x.toUpperCase(Locale.ROOT));
                cmd.add(f.toString());
                cmd.add("--file-filter=Todos | *");
            }
            zenity(cmd, out -> {
                if (out == null) { done.accept(null); return; }
                ArrayList<File> files = new ArrayList<>();
                for (String l : out.split("\n")) if (!l.trim().isEmpty()) files.add(new File(l.trim()));
                if (!files.isEmpty()) lastDir = files.get(0).getParentFile();
                done.accept(files);
            });
            return;
        }
        SwingUtilities.invokeLater(() -> {
            JFileChooser c = new JFileChooser(lastDir);
            c.setMultiSelectionEnabled(multiple);
            if (!exts.isEmpty()) c.setFileFilter(new FileNameExtensionFilter("Compatibles", exts.toArray(new String[0])));
            if (c.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) { done.accept(null); return; }
            lastDir = c.getCurrentDirectory();
            ArrayList<File> files = new ArrayList<>();
            if (multiple) for (File f : c.getSelectedFiles()) files.add(f); else files.add(c.getSelectedFile());
            done.accept(files);
        });
    }

    static void save(java.awt.Component parent, String name, String type, Consumer<File> done) {
        File pick = nextPick;
        if (pick != null) { nextPick = null; SwingUtilities.invokeLater(() -> done.accept(pick)); return; }
        String suggested = name == null ? "archivo" : name;
        List<String> exts = extensions(type);
        File dir = lastDir != null ? lastDir : new File(android.os.Environment.xdg(android.os.Environment.DIRECTORY_DOCUMENTS));
        if (hasZenity()) {
            ArrayList<String> cmd = new ArrayList<>();
            cmd.add("zenity"); cmd.add("--file-selection"); cmd.add("--save"); cmd.add("--confirm-overwrite"); cmd.add("--title=Guardar");
            cmd.add("--filename=" + new File(dir, suggested).getPath());
            zenity(cmd, out -> {
                if (out == null || out.trim().isEmpty()) { done.accept(null); return; }
                File f = new File(out.trim());
                if (!exts.isEmpty() && f.getName().indexOf('.') < 0) f = new File(f.getPath() + "." + exts.get(0));
                lastDir = f.getParentFile();
                done.accept(f);
            });
            return;
        }
        SwingUtilities.invokeLater(() -> {
            JFileChooser c = new JFileChooser(dir);
            c.setSelectedFile(new File(dir, suggested));
            if (c.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) { done.accept(null); return; }
            lastDir = c.getCurrentDirectory();
            done.accept(c.getSelectedFile());
        });
    }

    static void folder(java.awt.Component parent, Consumer<File> done) {
        if (hasZenity()) {
            ArrayList<String> cmd = new ArrayList<>();
            cmd.add("zenity"); cmd.add("--file-selection"); cmd.add("--directory"); cmd.add("--title=Elegir carpeta");
            zenity(cmd, out -> done.accept(out == null || out.trim().isEmpty() ? null : new File(out.trim())));
            return;
        }
        SwingUtilities.invokeLater(() -> {
            JFileChooser c = new JFileChooser(lastDir);
            c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            done.accept(c.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION ? c.getSelectedFile() : null);
        });
    }

    private static void zenity(List<String> cmd, Consumer<String> done) {
        Thread t = new Thread(() -> {
            String out = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process p = pb.start();
                String s = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (p.waitFor() == 0) out = s;
            } catch (Exception e) {
                android.util.Log.w("SyncTablet", "zenity", e);
            }
            final String result = out;
            SwingUtilities.invokeLater(() -> done.accept(result));
        }, "selector");
        t.setDaemon(true);
        t.start();
    }

    /** Abre un archivo o una dirección con la aplicación del sistema. */
    static void openExternally(String target) {
        if (System.getProperty("synctablet.noopen") != null) { System.err.println("abrir: " + target); return; }
        Thread t = new Thread(() -> {
            try { new ProcessBuilder("xdg-open", target).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start(); }
            catch (Exception e) {
                try { java.awt.Desktop.getDesktop().browse(new java.net.URI(target)); } catch (Exception ignored) {}
            }
        }, "abrir");
        t.setDaemon(true);
        t.start();
    }
}
