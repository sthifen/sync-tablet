package android.util;

public abstract class Property<T, V> {
    private final String name;
    private final Class<V> type;
    public Property(Class<V> type, String name) { this.name = name; this.type = type; }
    public boolean isReadOnly() { return false; }
    public void set(T object, V value) { throw new UnsupportedOperationException("Property " + name + " is read-only"); }
    public abstract V get(T object);
    public String getName() { return name; }
    public Class<V> getType() { return type; }
}
