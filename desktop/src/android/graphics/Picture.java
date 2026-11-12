package android.graphics;

/** Una grabación de dibujo: aquí, un mapa de bits del tamaño pedido. */
public class Picture {
    private Bitmap bitmap;
    private Canvas canvas;
    private int width, height;

    public Canvas beginRecording(int w, int h) {
        width = w; height = h;
        bitmap = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        canvas = new Canvas(bitmap);
        return canvas;
    }

    public void endRecording() { if (canvas != null) canvas.release(); canvas = null; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public void draw(Canvas c) { if (bitmap != null) c.drawBitmap(bitmap, 0, 0, null); }
}
