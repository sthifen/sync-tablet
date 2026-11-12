package android.graphics;

public class BlurMaskFilter extends MaskFilter {
    public enum Blur { NORMAL, SOLID, OUTER, INNER }
    final float radius;
    final Blur style;
    public BlurMaskFilter(float radius, Blur style) { this.radius = radius; this.style = style; }
}
