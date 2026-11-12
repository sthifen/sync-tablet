package android.graphics.pdf;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.lang.reflect.Method;

/** Lee un PDF con el motor de la app (la librería nativa de PdfCraft), como haría el de Android. */
public final class PdfRenderer implements AutoCloseable {
    private static Method open, pages, render, close;
    private final long handle;
    private final int count;
    private final float[][] sizes;

    private static synchronized void bind() throws IOException {
        if (open != null) return;
        try {
            Class<?> n = Class.forName("dev.synctablet.Native");
            Method avail = n.getDeclaredMethod("available");
            avail.setAccessible(true);
            if (!(Boolean) avail.invoke(null)) throw new IOException("sin el motor de PDF");
            open = n.getDeclaredMethod("pdfOpen", byte[].class);
            pages = n.getDeclaredMethod("pdfPages", long.class);
            render = n.getDeclaredMethod("pdfRender", long.class, int.class, float.class, int[].class);
            close = n.getDeclaredMethod("pdfClose", long.class);
            for (Method m : new Method[] {open, pages, render, close}) m.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IOException("sin el motor de PDF", e);
        }
    }

    public PdfRenderer(ParcelFileDescriptor fd) throws IOException {
        bind();
        try {
            handle = (Long) open.invoke(null, (Object) fd.bytes());
            if (handle == 0) throw new IOException("PDF ilegible");
            count = (Integer) pages.invoke(null, handle);
        } catch (ReflectiveOperationException e) {
            throw new IOException(e);
        }
        sizes = new float[count][];
    }

    public int getPageCount() { return count; }
    public boolean shouldScaleForPrinting() { return true; }

    public Page openPage(int index) {
        if (index < 0 || index >= count) throw new IllegalArgumentException("Invalid page index");
        return new Page(index);
    }

    @Override
    public void close() {
        try { close.invoke(null, handle); } catch (ReflectiveOperationException ignored) {}
    }

    public final class Page implements AutoCloseable {
        public static final int RENDER_MODE_FOR_DISPLAY = 1, RENDER_MODE_FOR_PRINT = 2;
        private final int index;
        private int width = 612, height = 792;

        Page(int index) {
            this.index = index;
            // Tamaño en puntos: se pide una imagen de 72 px de ancho por pulgada.
            if (sizes[index] == null) {
                try {
                    int[] s = new int[2];
                    render.invoke(null, handle, index, 100f, s);
                    if (s[0] > 0) sizes[index] = new float[] {s[0], s[1]};
                } catch (ReflectiveOperationException ignored) {}
            }
            if (sizes[index] != null) {
                float ratio = sizes[index][1] / sizes[index][0];
                width = 612;
                height = Math.round(612 * ratio);
            }
        }

        public int getIndex() { return index; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }

        public void render(Bitmap dest, Rect clip, Matrix transform, int mode) {
            try {
                int[] s = new int[2];
                byte[] rgba = (byte[]) render.invoke(null, handle, index, (float) dest.getWidth(), s);
                if (rgba == null) return;
                Bitmap b = Bitmap.createBitmap(s[0], s[1], Bitmap.Config.ARGB_8888);
                b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgba));
                android.graphics.Canvas c = new android.graphics.Canvas(dest);
                c.drawBitmap(b, null, new android.graphics.RectF(0, 0, dest.getWidth(), dest.getHeight()), null);
                c.release();
            } catch (ReflectiveOperationException ignored) {}
        }

        @Override public void close() {}
    }
}
