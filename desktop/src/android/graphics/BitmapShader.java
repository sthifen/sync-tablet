package android.graphics;

public class BitmapShader extends Shader {
    private final Bitmap bitmap;
    public BitmapShader(Bitmap bitmap, TileMode tx, TileMode ty) { this.bitmap = bitmap; }

    @Override
    public java.awt.Paint awt(int alpha) {
        java.awt.geom.AffineTransform t = local.awt();
        java.awt.image.BufferedImage img = bitmap.image;
        return new java.awt.TexturePaint(img, new java.awt.geom.Rectangle2D.Double(t.getTranslateX(), t.getTranslateY(),
                img.getWidth() * t.getScaleX(), img.getHeight() * t.getScaleY()));
    }
}
