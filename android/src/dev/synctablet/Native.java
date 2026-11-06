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

    /** Asa de la presentación, o 0 si no se pudo leer. */
    static native long deckOpen(byte[] data);
    static native int deckSlides(long handle);
    /** RGBA premultiplicado; en `size`, ancho y alto. Null si falló. */
    static native byte[] deckRender(long handle, int slide, float width, int[] size);
    static native String deckText(long handle, int slide);
    static native void deckClose(long handle);
    /** Un .pptx con una imagen JPEG por diapositiva, de esa forma. Null si falló. */
    static native byte[] imagesToPptx(byte[][] images, float width, float height);

    /** Una foto editada, como JPEG. El recorte va de 0 a 1 ya girada. Null si falló. */
    static native byte[] imageEdit(byte[] data, float brightness, float contrast, float saturation, int quarterTurns,
                                   float left, float top, float right, float bottom, boolean document);

    /** Un .docx como JSON {title, blocks}; null si no se pudo leer. */
    static native String docxToNote(byte[] data);
    /** Una nota como .docx; null si no se pudo. */
    static native byte[] noteToDocx(String note);

    private Native() {}
}
