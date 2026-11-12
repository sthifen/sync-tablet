package android.content;

import android.net.Uri;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class Intent implements Cloneable, android.os.Parcelable {
    public static final String ACTION_MAIN = "android.intent.action.MAIN", ACTION_VIEW = "android.intent.action.VIEW", ACTION_SEND = "android.intent.action.SEND",
            ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE", ACTION_GET_CONTENT = "android.intent.action.GET_CONTENT",
            ACTION_OPEN_DOCUMENT = "android.intent.action.OPEN_DOCUMENT", ACTION_CREATE_DOCUMENT = "android.intent.action.CREATE_DOCUMENT",
            ACTION_OPEN_DOCUMENT_TREE = "android.intent.action.OPEN_DOCUMENT_TREE", ACTION_CHOOSER = "android.intent.action.CHOOSER",
            ACTION_EDIT = "android.intent.action.EDIT", ACTION_PICK = "android.intent.action.PICK", ACTION_INSTALL_PACKAGE = "android.intent.action.INSTALL_PACKAGE",
            ACTION_APPLICATION_DETAILS_SETTINGS = "android.settings.APPLICATION_DETAILS_SETTINGS", ACTION_MANAGE_UNKNOWN_APP_SOURCES = "android.settings.MANAGE_UNKNOWN_APP_SOURCES",
            ACTION_DIAL = "android.intent.action.DIAL", ACTION_SENDTO = "android.intent.action.SENDTO", ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT";
    public static final String CATEGORY_OPENABLE = "android.intent.category.OPENABLE", CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER",
            CATEGORY_DEFAULT = "android.intent.category.DEFAULT", CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE";
    public static final String EXTRA_STREAM = "android.intent.extra.STREAM", EXTRA_TEXT = "android.intent.extra.TEXT", EXTRA_SUBJECT = "android.intent.extra.SUBJECT",
            EXTRA_TITLE = "android.intent.extra.TITLE", EXTRA_MIME_TYPES = "android.intent.extra.MIME_TYPES", EXTRA_ALLOW_MULTIPLE = "android.intent.extra.ALLOW_MULTIPLE",
            EXTRA_LOCAL_ONLY = "android.intent.extra.LOCAL_ONLY", EXTRA_INTENT = "android.intent.extra.INTENT", EXTRA_CHOOSER_TARGETS = "android.intent.extra.CHOOSER_TARGETS",
            EXTRA_PROCESS_TEXT = "android.intent.extra.PROCESS_TEXT", EXTRA_EMAIL = "android.intent.extra.EMAIL", EXTRA_INITIAL_INTENTS = "android.intent.extra.INITIAL_INTENTS";
    public static final int FLAG_GRANT_READ_URI_PERMISSION = 1, FLAG_GRANT_WRITE_URI_PERMISSION = 2, FLAG_GRANT_PERSISTABLE_URI_PERMISSION = 0x40,
            FLAG_ACTIVITY_NEW_TASK = 0x10000000, FLAG_ACTIVITY_CLEAR_TOP = 0x04000000, FLAG_ACTIVITY_SINGLE_TOP = 0x20000000,
            FLAG_ACTIVITY_NO_ANIMATION = 0x10000, FLAG_ACTIVITY_CLEAR_TASK = 0x8000, FLAG_ACTIVITY_REORDER_TO_FRONT = 0x20000,
            FLAG_ACTIVITY_NO_HISTORY = 0x40000000, FLAG_ACTIVITY_MULTIPLE_TASK = 0x08000000, FLAG_ACTIVITY_NEW_DOCUMENT = 0x80000,
            FLAG_ACTIVITY_LAUNCH_ADJACENT = 0x1000, FLAG_RECEIVER_FOREGROUND = 0x10000000;

    private String action, type, pkg;
    private Uri data;
    private Class<?> component;
    private ComponentName componentName;
    private Bundle extras;
    private int flags;
    private final HashSet<String> categories = new HashSet<>();
    private ClipData clip;

    public Intent() {}
    public Intent(String action) { this.action = action; }
    public Intent(String action, Uri data) { this.action = action; this.data = data; }
    public Intent(Context c, Class<?> cls) { component = cls; }
    public Intent(String action, Uri data, Context c, Class<?> cls) { this.action = action; this.data = data; component = cls; }
    public Intent(Intent o) {
        action = o.action; type = o.type; data = o.data; component = o.component; componentName = o.componentName; flags = o.flags; pkg = o.pkg; clip = o.clip;
        if (o.extras != null) extras = new Bundle(o.extras);
        categories.addAll(o.categories);
    }

    public static Intent createChooser(Intent target, CharSequence title) {
        Intent i = new Intent(ACTION_CHOOSER);
        i.putExtra(EXTRA_INTENT, target);
        if (title != null) i.putExtra(EXTRA_TITLE, title.toString());
        return i;
    }

    @Override public Object clone() { return new Intent(this); }
    public Intent cloneFilter() { return new Intent(this); }

    public String getAction() { return action; }
    public Intent setAction(String a) { action = a; return this; }
    public Uri getData() { return data; }
    public Intent setData(Uri d) { data = d; type = null; return this; }
    public String getDataString() { return data == null ? null : data.toString(); }
    public String getType() { return type; }
    public Intent setType(String t) { type = t; data = null; return this; }
    public Intent setDataAndType(Uri d, String t) { data = d; type = t; return this; }
    public Intent setTypeAndNormalize(String t) { return setType(t); }
    public String resolveType(Context c) { return type; }
    public Class<?> componentClass() { return component; }
    public ComponentName getComponent() { return component != null ? new ComponentName("dev.synctablet", component.getName()) : componentName; }
    public Intent setComponent(ComponentName c) { componentName = c; try { component = c == null ? null : Class.forName(c.getClassName()); } catch (ClassNotFoundException e) { component = null; } return this; }
    public Intent setClass(Context c, Class<?> cls) { component = cls; return this; }
    public Intent setClassName(String pkg, String cls) { return setComponent(new ComponentName(pkg, cls)); }
    public Intent setClassName(Context c, String cls) { return setComponent(new ComponentName(c, cls)); }
    public Intent setPackage(String p) { pkg = p; return this; }
    public String getPackage() { return pkg; }
    public int getFlags() { return flags; }
    public Intent setFlags(int f) { flags = f; return this; }
    public Intent addFlags(int f) { flags |= f; return this; }
    public void removeFlags(int f) { flags &= ~f; }
    public Intent addCategory(String c) { categories.add(c); return this; }
    public void removeCategory(String c) { categories.remove(c); }
    public boolean hasCategory(String c) { return categories.contains(c); }
    public Set<String> getCategories() { return categories.isEmpty() ? null : categories; }
    public ClipData getClipData() { return clip; }
    public void setClipData(ClipData c) { clip = c; }
    public ComponentName resolveActivity(android.content.pm.PackageManager pm) { return component != null ? getComponent() : new ComponentName("desktop", String.valueOf(action)); }

    private Bundle extras() { if (extras == null) extras = new Bundle(); return extras; }
    public Bundle getExtras() { return extras == null ? null : new Bundle(extras); }
    public Intent putExtras(Bundle b) { extras().putAll(b); return this; }
    public Intent putExtras(Intent i) { if (i.extras != null) extras().putAll(i.extras); return this; }
    public Intent replaceExtras(Bundle b) { extras = b == null ? null : new Bundle(b); return this; }
    public boolean hasExtra(String k) { return extras != null && extras.containsKey(k); }
    public void removeExtra(String k) { if (extras != null) extras.remove(k); }

    public Intent putExtra(String k, String v) { extras().putString(k, v); return this; }
    public Intent putExtra(String k, CharSequence v) { extras().putCharSequence(k, v); return this; }
    public Intent putExtra(String k, int v) { extras().putInt(k, v); return this; }
    public Intent putExtra(String k, long v) { extras().putLong(k, v); return this; }
    public Intent putExtra(String k, float v) { extras().putFloat(k, v); return this; }
    public Intent putExtra(String k, double v) { extras().putDouble(k, v); return this; }
    public Intent putExtra(String k, boolean v) { extras().putBoolean(k, v); return this; }
    public Intent putExtra(String k, String[] v) { extras().putStringArray(k, v); return this; }
    public Intent putExtra(String k, int[] v) { extras().putIntArray(k, v); return this; }
    public Intent putExtra(String k, long[] v) { extras().putLongArray(k, v); return this; }
    public Intent putExtra(String k, float[] v) { extras().putFloatArray(k, v); return this; }
    public Intent putExtra(String k, byte[] v) { extras().putByteArray(k, v); return this; }
    public Intent putExtra(String k, Bundle v) { extras().putBundle(k, v); return this; }
    public Intent putExtra(String k, android.os.Parcelable v) { extras().putParcelable(k, v); return this; }
    public Intent putExtra(String k, android.os.Parcelable[] v) { extras().putSerializable(k, v); return this; }
    public Intent putExtra(String k, java.io.Serializable v) { extras().putSerializable(k, v); return this; }
    public Intent putStringArrayListExtra(String k, ArrayList<String> v) { extras().putStringArrayList(k, v); return this; }
    public Intent putParcelableArrayListExtra(String k, ArrayList<? extends android.os.Parcelable> v) { extras().putSerializable(k, v); return this; }

    public String getStringExtra(String k) { return extras == null ? null : extras.getString(k); }
    public CharSequence getCharSequenceExtra(String k) { if (extras == null) return null; Object o = extras.get(k); return o instanceof CharSequence ? (CharSequence) o : null; }
    public int getIntExtra(String k, int def) { return extras == null ? def : extras.getInt(k, def); }
    public long getLongExtra(String k, long def) { return extras == null ? def : extras.getLong(k, def); }
    public float getFloatExtra(String k, float def) { return extras == null ? def : extras.getFloat(k, def); }
    public double getDoubleExtra(String k, double def) { return extras == null ? def : extras.getDouble(k, def); }
    public boolean getBooleanExtra(String k, boolean def) { return extras == null ? def : extras.getBoolean(k, def); }
    public String[] getStringArrayExtra(String k) { return extras == null ? null : extras.getStringArray(k); }
    public int[] getIntArrayExtra(String k) { return extras == null ? null : extras.getIntArray(k); }
    public long[] getLongArrayExtra(String k) { return extras == null ? null : extras.getLongArray(k); }
    public float[] getFloatArrayExtra(String k) { return extras == null ? null : extras.getFloatArray(k); }
    public byte[] getByteArrayExtra(String k) { return extras == null ? null : extras.getByteArray(k); }
    public Bundle getBundleExtra(String k) { return extras == null ? null : extras.getBundle(k); }
    public ArrayList<String> getStringArrayListExtra(String k) { return extras == null ? null : extras.getStringArrayList(k); }
    @SuppressWarnings("unchecked") public <T extends android.os.Parcelable> T getParcelableExtra(String k) { return extras == null ? null : (T) extras.get(k); }
    @SuppressWarnings("unchecked") public <T> T getParcelableExtra(String k, Class<T> c) { return extras == null ? null : (T) extras.get(k); }
    @SuppressWarnings("unchecked") public <T extends android.os.Parcelable> ArrayList<T> getParcelableArrayListExtra(String k) { return extras == null ? null : (ArrayList<T>) extras.get(k); }
    public java.io.Serializable getSerializableExtra(String k) { return extras == null ? null : (java.io.Serializable) extras.get(k); }

    @Override public String toString() { return "Intent{" + action + " " + data + " " + (component == null ? "" : component.getSimpleName()) + "}"; }
}
