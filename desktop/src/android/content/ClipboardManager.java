package android.content;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;

/** El portapapeles del sistema (texto); lo demás se guarda dentro de la app. */
public class ClipboardManager {
    public interface OnPrimaryClipChangedListener { void onPrimaryClipChanged(); }

    static final ClipboardManager INSTANCE = new ClipboardManager();
    private ClipData local;
    private String localText;
    private final java.util.concurrent.CopyOnWriteArrayList<OnPrimaryClipChangedListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void setPrimaryClip(ClipData clip) {
        local = clip;
        localText = null;
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence t = clip.getItemAt(0).getText();
            if (t != null) {
                localText = t.toString();
                try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(localText), null); } catch (Exception ignored) {}
            }
        }
        for (OnPrimaryClipChangedListener l : listeners) l.onPrimaryClipChanged();
    }

    public ClipData getPrimaryClip() {
        ClipData image = systemImage();
        if (image != null) return image;
        String sys = systemText();
        if (sys != null && (localText == null || !sys.equals(localText))) return ClipData.newPlainText("texto", sys);
        return local;
    }

    public boolean hasPrimaryClip() { return getPrimaryClip() != null; }

    public ClipDescription getPrimaryClipDescription() { ClipData c = getPrimaryClip(); return c == null ? null : c.getDescription(); }

    public void clearPrimaryClip() { local = null; localText = null; }

    public void addPrimaryClipChangedListener(OnPrimaryClipChangedListener l) { listeners.add(l); }
    public void removePrimaryClipChangedListener(OnPrimaryClipChangedListener l) { listeners.remove(l); }

    @Deprecated public CharSequence getText() { ClipData c = getPrimaryClip(); return c == null || c.getItemCount() == 0 ? null : c.getItemAt(0).getText(); }
    @Deprecated public void setText(CharSequence t) { setPrimaryClip(ClipData.newPlainText("texto", t)); }
    @Deprecated public boolean hasText() { return getText() != null; }

    private static java.io.File lastImageFile;
    private static int lastImageHash;

    /** Una imagen copiada en el PC (una captura) o un archivo de imagen copiado en Archivos: como Uri, igual que en Android. */
    private static ClipData systemImage() {
        try {
            Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (t == null) return null;
            if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                @SuppressWarnings("unchecked")
                java.util.List<java.io.File> files = (java.util.List<java.io.File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                if (!files.isEmpty()) {
                    String mime = ContentResolver.mimeOf(files.get(0).getName());
                    if (mime != null && mime.startsWith("image/"))
                        return new ClipData(new ClipDescription("archivo", new String[] {mime}), new ClipData.Item(android.net.Uri.fromFile(files.get(0))));
                }
            }
            if (t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                java.awt.Image img = (java.awt.Image) t.getTransferData(DataFlavor.imageFlavor);
                java.awt.image.BufferedImage bi;
                if (img instanceof java.awt.image.BufferedImage) bi = (java.awt.image.BufferedImage) img;
                else {
                    bi = new java.awt.image.BufferedImage(img.getWidth(null), img.getHeight(null), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g = bi.createGraphics();
                    g.drawImage(img, 0, 0, null);
                    g.dispose();
                }
                int[] px = bi.getRGB(0, 0, bi.getWidth(), bi.getHeight(), null, 0, bi.getWidth());
                int hash = java.util.Arrays.hashCode(px);
                if (lastImageFile == null || hash != lastImageHash || !lastImageFile.isFile()) {
                    java.io.File dir = new java.io.File(android.app.Application.get().getCacheDir(), "clip");
                    dir.mkdirs();
                    java.io.File[] old = dir.listFiles();
                    if (old != null) for (java.io.File o : old) o.delete();
                    java.io.File f = new java.io.File(dir, "clip-" + System.currentTimeMillis() + ".png");
                    javax.imageio.ImageIO.write(bi, "png", f);
                    lastImageFile = f;
                    lastImageHash = hash;
                }
                return new ClipData(new ClipDescription("captura", new String[] {"image/png"}), new ClipData.Item(android.net.Uri.fromFile(lastImageFile)));
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String systemText() {
        try {
            Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (t != null && t.isDataFlavorSupported(DataFlavor.stringFlavor)) return (String) t.getTransferData(DataFlavor.stringFlavor);
        } catch (Exception ignored) {}
        return null;
    }
}
