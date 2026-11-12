package android.graphics;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/** Mapa de bits sobre BufferedImage (ARGB premultiplicado, lo más rápido en Java2D). */
public final class Bitmap {
    public enum Config { ALPHA_8, RGB_565, ARGB_4444, ARGB_8888, RGBA_F16, HARDWARE, RGBA_1010102 }
    public enum CompressFormat { JPEG, PNG, WEBP, WEBP_LOSSY, WEBP_LOSSLESS }

    public static final int DENSITY_NONE = 0;

    public BufferedImage image;
    private Config config;
    private boolean recycled, mutable = true, hasAlpha;
    private int density = 160;
    private int generation;

    Bitmap(BufferedImage image, Config config) {
        this.image = image;
        this.config = config;
        this.hasAlpha = config != Config.RGB_565;
    }

    /** Envuelve una imagen de Java (la convierte si no es ARGB premultiplicado). */
    public static Bitmap wrap(BufferedImage img) {
        if (img.getType() != BufferedImage.TYPE_INT_ARGB_PRE) {
            BufferedImage c = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D g = c.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
            img = c;
        }
        return new Bitmap(img, Config.ARGB_8888);
    }

    private static BufferedImage blank(int w, int h, Config c) {
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("width and height must be > 0");
        return new BufferedImage(w, h, c == Config.RGB_565 ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB_PRE);
    }

    public static Bitmap createBitmap(int width, int height, Config config) { return new Bitmap(blank(width, height, config == null ? Config.ARGB_8888 : config), config == null ? Config.ARGB_8888 : config); }
    public static Bitmap createBitmap(int width, int height, Config config, boolean hasAlpha) { return createBitmap(width, height, config); }

    public static Bitmap createBitmap(Bitmap src) { return createBitmap(src, 0, 0, src.getWidth(), src.getHeight()); }

    public static Bitmap createBitmap(Bitmap src, int x, int y, int w, int h) { return createBitmap(src, x, y, w, h, null, false); }

    public static Bitmap createBitmap(Bitmap src, int x, int y, int w, int h, Matrix m, boolean filter) {
        if (m == null || m.isIdentity()) {
            Bitmap b = createBitmap(w, h, src.config == Config.RGB_565 ? Config.RGB_565 : Config.ARGB_8888);
            Graphics2D g = b.image.createGraphics();
            g.setComposite(java.awt.AlphaComposite.Src);
            g.drawImage(src.image, -x, -y, null);
            g.dispose();
            return b;
        }
        RectF r = new RectF(0, 0, w, h);
        m.mapRect(r);
        int nw = Math.max(1, Math.round(r.width())), nh = Math.max(1, Math.round(r.height()));
        Bitmap b = createBitmap(nw, nh, Config.ARGB_8888);
        Graphics2D g = b.image.createGraphics();
        if (filter) g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        java.awt.geom.AffineTransform t = java.awt.geom.AffineTransform.getTranslateInstance(-r.left, -r.top);
        t.concatenate(m.t);
        g.transform(t);
        g.drawImage(src.image.getSubimage(x, y, w, h), 0, 0, null);
        g.dispose();
        return b;
    }

    public static Bitmap createBitmap(int[] colors, int width, int height, Config config) {
        return createBitmap(colors, 0, width, width, height, config);
    }

    public static Bitmap createBitmap(int[] colors, int offset, int stride, int width, int height, Config config) {
        Bitmap b = createBitmap(width, height, config);
        b.setPixels(colors, offset, stride, 0, 0, width, height);
        return b;
    }

    public static Bitmap createScaledBitmap(Bitmap src, int w, int h, boolean filter) {
        if (w == src.getWidth() && h == src.getHeight()) return src;
        Bitmap b = createBitmap(Math.max(1, w), Math.max(1, h), src.config == Config.RGB_565 ? Config.RGB_565 : Config.ARGB_8888);
        Graphics2D g = b.image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, filter ? java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR : java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        if (filter && (w < src.getWidth() / 2 || h < src.getHeight() / 2)) {
            // Al encoger mucho, por pasos: si no, se ve dentado.
            BufferedImage cur = src.image;
            int cw = cur.getWidth(), ch = cur.getHeight();
            while (cw / 2 >= w && ch / 2 >= h) {
                cw /= 2; ch /= 2;
                BufferedImage half = new BufferedImage(Math.max(1, cw), Math.max(1, ch), BufferedImage.TYPE_INT_ARGB_PRE);
                Graphics2D hg = half.createGraphics();
                hg.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                hg.drawImage(cur, 0, 0, half.getWidth(), half.getHeight(), null);
                hg.dispose();
                cur = half;
            }
            g.drawImage(cur, 0, 0, w, h, null);
        } else {
            g.drawImage(src.image, 0, 0, w, h, null);
        }
        g.dispose();
        return b;
    }

    public int getWidth() { return image.getWidth(); }
    public int getHeight() { return image.getHeight(); }
    public int getScaledWidth(int targetDensity) { return getWidth(); }
    public int getScaledHeight(int targetDensity) { return getHeight(); }
    public Config getConfig() { return config; }
    public boolean hasAlpha() { return hasAlpha; }
    public void setHasAlpha(boolean a) { hasAlpha = a; }
    public boolean isMutable() { return mutable; }
    public boolean isRecycled() { return recycled; }
    public void recycle() { recycled = true; }
    public int getRowBytes() { return getWidth() * 4; }
    public int getByteCount() { return getWidth() * getHeight() * 4; }
    public int getAllocationByteCount() { return getByteCount(); }
    public int getDensity() { return density; }
    public void setDensity(int d) { density = d; }
    public void prepareToDraw() {}
    public int getGenerationId() { return generation; }
    void touched() { generation++; }

    public int getPixel(int x, int y) { return image.getRGB(x, y); }
    public void setPixel(int x, int y, int color) { image.setRGB(x, y, color); touched(); }

    public void getPixels(int[] pixels, int offset, int stride, int x, int y, int w, int h) { image.getRGB(x, y, w, h, pixels, offset, stride); }
    public void setPixels(int[] pixels, int offset, int stride, int x, int y, int w, int h) { image.setRGB(x, y, w, h, pixels, offset, stride); touched(); }

    private int[] raster() { return ((DataBufferInt) image.getRaster().getDataBuffer()).getData(); }

    /** Bytes RGBA (premultiplicados, como en Android) → píxeles. */
    public void copyPixelsFromBuffer(Buffer src) {
        int[] px = raster();
        int n = Math.min(px.length, getWidth() * getHeight());
        if (src instanceof ByteBuffer) {
            ByteBuffer b = (ByteBuffer) src;
            boolean opaque = image.getType() == BufferedImage.TYPE_INT_RGB;
            for (int i = 0; i < n && b.remaining() >= 4; i++) {
                int r = b.get() & 0xFF, g = b.get() & 0xFF, bl = b.get() & 0xFF, a = b.get() & 0xFF;
                px[i] = opaque ? (r << 16) | (g << 8) | bl : (a << 24) | (r << 16) | (g << 8) | bl;
            }
        } else if (src instanceof IntBuffer) {
            IntBuffer b = (IntBuffer) src;
            for (int i = 0; i < n && b.hasRemaining(); i++) {
                int v = b.get();
                // En memoria, RGBA: el int en orden nativo (little endian) es ABGR.
                int r = v & 0xFF, g = (v >> 8) & 0xFF, bl = (v >> 16) & 0xFF, a = v >>> 24;
                px[i] = (a << 24) | (r << 16) | (g << 8) | bl;
            }
        }
        touched();
    }

    public void copyPixelsToBuffer(Buffer dst) {
        int[] px = raster();
        int n = getWidth() * getHeight();
        if (dst instanceof ByteBuffer) {
            ByteBuffer b = (ByteBuffer) dst;
            boolean opaque = image.getType() == BufferedImage.TYPE_INT_RGB;
            for (int i = 0; i < n && b.remaining() >= 4; i++) {
                int v = px[i];
                b.put((byte) (v >> 16)).put((byte) (v >> 8)).put((byte) v).put((byte) (opaque ? 0xFF : v >>> 24));
            }
        }
    }

    public void eraseColor(int c) {
        int[] px = raster();
        int v;
        if (image.getType() == BufferedImage.TYPE_INT_RGB) v = c & 0xFFFFFF;
        else {
            int a = c >>> 24;
            int r = ((c >> 16) & 0xFF) * a / 255, g = ((c >> 8) & 0xFF) * a / 255, b = (c & 0xFF) * a / 255;
            v = (a << 24) | (r << 16) | (g << 8) | b;
        }
        java.util.Arrays.fill(px, v);
        touched();
    }

    public Bitmap copy(Config c, boolean isMutable) {
        Bitmap b = createBitmap(getWidth(), getHeight(), c == null ? config : c);
        Graphics2D g = b.image.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Src);
        g.drawImage(image, 0, 0, null);
        g.dispose();
        b.mutable = isMutable;
        return b;
    }

    public Bitmap extractAlpha() {
        Bitmap b = createBitmap(getWidth(), getHeight(), Config.ARGB_8888);
        int[] s = raster(), d = b.raster();
        for (int i = 0; i < s.length; i++) d[i] = s[i] & 0xFF000000;
        return b;
    }

    public boolean sameAs(Bitmap o) {
        if (o == null || o.getWidth() != getWidth() || o.getHeight() != getHeight()) return false;
        return java.util.Arrays.equals(raster(), o.raster());
    }

    public boolean compress(CompressFormat format, int quality, OutputStream out) {
        try {
            if (format == CompressFormat.JPEG || format == CompressFormat.WEBP_LOSSY || (format == CompressFormat.WEBP && quality < 100)) {
                BufferedImage rgb = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
                int[] s = raster(), d = ((DataBufferInt) rgb.getRaster().getDataBuffer()).getData();
                System.arraycopy(s, 0, d, 0, Math.min(s.length, d.length));
                if (image.getType() != BufferedImage.TYPE_INT_RGB) for (int i = 0; i < d.length; i++) d[i] &= 0xFFFFFF;
                ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
                ImageWriteParam p = w.getDefaultWriteParam();
                p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                p.setCompressionQuality(Math.max(0.05f, Math.min(1f, quality / 100f)));
                try (ImageOutputStream ios = ImageIO.createImageOutputStream(new NoClose(out))) {
                    w.setOutput(ios);
                    w.write(null, new IIOImage(rgb, null, null), p);
                } finally {
                    w.dispose();
                }
                return true;
            }
            // PNG (y WebP sin pérdida, que Java no sabe escribir).
            BufferedImage argb = new BufferedImage(getWidth(), getHeight(), image.getType() == BufferedImage.TYPE_INT_RGB ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = argb.createGraphics();
            g.setComposite(java.awt.AlphaComposite.Src);
            g.drawImage(image, 0, 0, null);
            g.dispose();
            return ImageIO.write(argb, "png", new NoClose(out));
        } catch (IOException e) {
            return false;
        }
    }

    /** ImageIO cierra el flujo; Android no. */
    private static final class NoClose extends java.io.FilterOutputStream {
        NoClose(OutputStream o) { super(o); }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
        @Override public void close() throws IOException { flush(); }
    }
}
