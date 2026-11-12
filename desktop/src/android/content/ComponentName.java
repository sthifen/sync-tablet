package android.content;

public final class ComponentName {
    private final String pkg, cls;
    public ComponentName(String pkg, String cls) { this.pkg = pkg; this.cls = cls; }
    public ComponentName(Context c, String cls) { this(c.getPackageName(), cls); }
    public ComponentName(Context c, Class<?> cls) { this(c.getPackageName(), cls.getName()); }
    public String getPackageName() { return pkg; }
    public String getClassName() { return cls; }
    public String flattenToString() { return pkg + "/" + cls; }
}
