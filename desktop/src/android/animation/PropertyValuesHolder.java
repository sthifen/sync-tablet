package android.animation;

public class PropertyValuesHolder {
    final String name;
    final float[] floats;
    private PropertyValuesHolder(String n, float[] f) { name = n; floats = f; }
    public static PropertyValuesHolder ofFloat(String name, float... v) { return new PropertyValuesHolder(name, v); }
    public static PropertyValuesHolder ofFloat(android.util.Property<?, Float> p, float... v) { return new PropertyValuesHolder(p.getName(), v); }
}
