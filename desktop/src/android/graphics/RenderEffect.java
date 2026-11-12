package android.graphics;

public final class RenderEffect {
    final float blurX, blurY;
    private RenderEffect(float x, float y) { blurX = x; blurY = y; }
    public static RenderEffect createBlurEffect(float rx, float ry, Shader.TileMode edge) { return new RenderEffect(rx, ry); }
    public static RenderEffect createBlurEffect(float rx, float ry, RenderEffect input, Shader.TileMode edge) { return new RenderEffect(rx, ry); }
}
