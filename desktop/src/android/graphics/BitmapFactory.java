package android.graphics;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

public class BitmapFactory {
    public static class Options {
        public boolean inJustDecodeBounds, inMutable = true, inScaled, inDither, inPreferQualityOverSpeed;
        public int inSampleSize = 1, inDensity, inTargetDensity, outWidth, outHeight;
        public String outMimeType;
        public Bitmap.Config inPreferredConfig = Bitmap.Config.ARGB_8888;
        public Bitmap inBitmap;
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length) { return decodeByteArray(data, offset, length, null); }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length, Options opts) {
        if (data == null) return null;
        return decode(new ByteArrayInputStream(data, offset, length), opts);
    }

    public static Bitmap decodeFile(String path) { return decodeFile(path, null); }

    public static Bitmap decodeFile(String path, Options opts) {
        try {
            return decode(new ByteArrayInputStream(Files.readAllBytes(new File(path).toPath())), opts);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static Bitmap decodeStream(InputStream in) { return decodeStream(in, null, null); }

    public static Bitmap decodeStream(InputStream in, Rect outPadding, Options opts) {
        if (in == null) return null;
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return decode(new ByteArrayInputStream(b.toByteArray()), opts);
        } catch (IOException e) {
            return null;
        }
    }

    private static Bitmap decode(ByteArrayInputStream in, Options opts) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            if (iis == null) return null;
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext()) return null;
            ImageReader r = it.next();
            try {
                r.setInput(iis, true, true);
                int w = r.getWidth(0), h = r.getHeight(0);
                int sample = opts == null ? 1 : Math.max(1, opts.inSampleSize);
                // Android redondea hacia abajo a una potencia de dos.
                sample = Integer.highestOneBit(sample);
                if (opts != null) {
                    opts.outWidth = w / sample;
                    opts.outHeight = h / sample;
                    String f = r.getFormatName().toLowerCase(java.util.Locale.ROOT);
                    opts.outMimeType = "image/" + (f.contains("jp") ? "jpeg" : f);
                    if (opts.inJustDecodeBounds) return null;
                }
                javax.imageio.ImageReadParam p = r.getDefaultReadParam();
                if (sample > 1) p.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage img = r.read(0, p);
                return Bitmap.wrap(img);
            } finally {
                r.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static Bitmap decodeResource(Object res, int id) { return android.content.res.Resources.drawableBitmap(id); }
}
