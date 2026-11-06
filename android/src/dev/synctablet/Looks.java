package dev.synctablet;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Las fotos que el usuario pone de fondo o portada. Cada sitio tiene una clave
 * ("app", "home", "desk", "nb:<cuaderno>", "folder:<carpeta>"), su foto en
 * files/looks y dos ajustes: difuminado y oscurecido (0–100). Solo existen en
 * la tablet.
 *
 * El difuminado se calcula una vez, en pequeño, y se guarda en memoria: pintar
 * la foto ya difuminada no cuesta nada mientras se escribe o se desplaza.
 */
final class Looks {
    static final String APP = "app", HOME = "home", DESK = "desk";
    private static final int STORED = 1800, WORK = 640;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 16)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getAllocationByteCount();
        }
    };

    private Looks() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("looks", Context.MODE_PRIVATE);
    }

    private static File file(Context c, String key) {
        File dir = new File(c.getFilesDir(), "looks");
        dir.mkdirs();
        // El nombre de una carpeta puede traer cualquier cosa.
        return new File(dir, Integer.toHexString(key.hashCode()) + "-" + key.replaceAll("[^A-Za-z0-9_-]", "_") + ".jpg");
    }

    static boolean has(Context c, String key) {
        return file(c, key).isFile();
    }

    static int blur(Context c, String key) {
        return prefs(c).getInt(key + ".blur", APP.equals(key) ? 45 : DESK.equals(key) ? 35 : 0);
    }

    static int dim(Context c, String key) {
        return prefs(c).getInt(key + ".dim", APP.equals(key) ? 70 : DESK.equals(key) ? 25 : 0);
    }

    static void set(Context c, String key, int blur, int dim) {
        prefs(c).edit().putInt(key + ".blur", blur).putInt(key + ".dim", dim).apply();
    }

    static void remove(Context c, String key) {
        file(c, key).delete();
        prefs(c).edit().remove(key + ".blur").remove(key + ".dim").apply();
        CACHE.evictAll();
    }

    /** Una carpeta renombrada conserva su portada. */
    static void rename(Context c, String from, String to) {
        if (!has(c, from)) return;
        file(c, from).renameTo(file(c, to));
        set(c, to, blur(c, from), dim(c, from));
        prefs(c).edit().remove(from + ".blur").remove(from + ".dim").apply();
        CACHE.evictAll();
    }

    /** Copia la foto elegida, ya reducida. Falso si no se pudo leer. */
    static boolean save(Context c, Uri uri, String key) {
        BitmapFactory.Options size = new BitmapFactory.Options();
        size.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, size);
        } catch (IOException | SecurityException e) {
            return false;
        }
        if (size.outWidth <= 0) return false;
        BitmapFactory.Options read = new BitmapFactory.Options();
        while (Math.max(size.outWidth, size.outHeight) / (read.inSampleSize == 0 ? 1 : read.inSampleSize) > 2 * STORED) {
            read.inSampleSize = read.inSampleSize == 0 ? 2 : read.inSampleSize * 2;
        }
        Bitmap b;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            b = BitmapFactory.decodeStream(in, null, read);
        } catch (IOException | SecurityException | OutOfMemoryError e) {
            return false;
        }
        if (b == null) return false;
        float k = Math.min(1f, (float) STORED / Math.max(b.getWidth(), b.getHeight()));
        if (k < 1) b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
        try (FileOutputStream out = new FileOutputStream(file(c, key))) {
            b.compress(Bitmap.CompressFormat.JPEG, 88, out);
        } catch (IOException e) {
            return false;
        }
        CACHE.evictAll();
        return true;
    }

    /** La foto con su difuminado actual (null si ese sitio no tiene). */
    static Bitmap bitmap(Context c, String key) {
        return bitmap(c, key, blur(c, key));
    }

    static Bitmap bitmap(Context c, String key, int blur) {
        File f = file(c, key);
        if (!f.isFile()) return null;
        String id = key + "/" + blur;
        Bitmap hit = CACHE.get(id);
        if (hit != null) return hit;
        Bitmap b = BitmapFactory.decodeFile(f.getPath());
        if (b == null) return null;
        if (blur > 0) {
            float k = Math.min(1f, (float) WORK / Math.max(b.getWidth(), b.getHeight()));
            Bitmap small = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
            b = soften(small, Math.max(1, Math.round(blur * 0.3f)));
        }
        CACHE.put(id, b);
        return b;
    }

    /** Tres pasadas de media móvil: casi igual que un desenfoque gaussiano. */
    private static Bitmap soften(Bitmap in, int radius) {
        int w = in.getWidth(), h = in.getHeight();
        int[] a = new int[w * h], b = new int[w * h];
        in.getPixels(a, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            slide(a, b, w, h, 1, w, radius);
            slide(b, a, h, w, w, 1, radius);
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888);
    }

    /** Promedia `count` líneas de `len` píxeles; `step` avanza en la línea, `next` pasa a la siguiente. */
    private static void slide(int[] from, int[] to, int len, int count, int step, int next, int radius) {
        int span = 2 * radius + 1;
        for (int line = 0; line < count; line++) {
            int base = line * next, r = 0, g = 0, b = 0;
            for (int i = -radius; i <= radius; i++) {
                int p = from[base + Math.max(0, Math.min(len - 1, i)) * step];
                r += p >> 16 & 0xFF;
                g += p >> 8 & 0xFF;
                b += p & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                to[base + i * step] = 0xFF000000 | r / span << 16 | g / span << 8 | b / span;
                int out = from[base + Math.max(0, i - radius) * step], in = from[base + Math.min(len - 1, i + radius + 1) * step];
                r += (in >> 16 & 0xFF) - (out >> 16 & 0xFF);
                g += (in >> 8 & 0xFF) - (out >> 8 & 0xFF);
                b += (in & 0xFF) - (out & 0xFF);
            }
        }
    }
}
