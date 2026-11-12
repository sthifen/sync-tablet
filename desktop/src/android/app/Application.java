package android.app;

import android.content.Context;
import android.content.ContextWrapper;

public class Application extends ContextWrapper {
    private static Application instance;
    public Application() { super(null); }
    public static synchronized Application get() { if (instance == null) instance = new Application(); return instance; }
    @Override public Context getApplicationContext() { return this; }
    public void onCreate() {}
    public void onTerminate() {}
    public void registerActivityLifecycleCallbacks(Object cb) {}
}
