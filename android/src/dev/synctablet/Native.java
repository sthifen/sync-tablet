package dev.synctablet;

/**
 * La librería nativa (native/, en Rust). Se carga la primera vez que hace falta;
 * si no existe para este procesador, `available()` es falso y se sigue sin ella.
 */
final class Native {
    private static Boolean loaded;

    static synchronized boolean available() {
        if (loaded == null) {
            try {
                System.loadLibrary("synctablet");
                loaded = true;
            } catch (UnsatisfiedLinkError e) {
                android.util.Log.w("SyncTablet", "sin librería nativa", e);
                loaded = false;
            }
        }
        return loaded;
    }

    /** Asa del PDF, o 0 si no se pudo leer. */
    static native long pdfOpen(byte[] data);
    static native int pdfPages(long handle);
    /** RGBA premultiplicado; en `size`, ancho y alto. Null si falló. */
    static native byte[] pdfRender(long handle, int page, float width, int[] size);
    static native String pdfText(long handle, int page);
    static native void pdfClose(long handle);

    private Native() {}
}
