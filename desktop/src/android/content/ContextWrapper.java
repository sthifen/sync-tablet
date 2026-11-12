package android.content;

public class ContextWrapper extends Context {
    private Context base;
    public ContextWrapper(Context base) { this.base = base; }
    protected void attachBaseContext(Context b) { base = b; }
    public Context getBaseContext() { return base; }
    @Override public Context getApplicationContext() { return base != null ? base.getApplicationContext() : this; }
}
