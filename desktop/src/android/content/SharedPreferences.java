package android.content;

import java.util.Map;
import java.util.Set;

public interface SharedPreferences {
    interface OnSharedPreferenceChangeListener { void onSharedPreferenceChanged(SharedPreferences p, String key); }

    interface Editor {
        Editor putString(String k, String v);
        Editor putStringSet(String k, Set<String> v);
        Editor putInt(String k, int v);
        Editor putLong(String k, long v);
        Editor putFloat(String k, float v);
        Editor putBoolean(String k, boolean v);
        Editor remove(String k);
        Editor clear();
        boolean commit();
        void apply();
    }

    Map<String, ?> getAll();
    String getString(String k, String def);
    Set<String> getStringSet(String k, Set<String> def);
    int getInt(String k, int def);
    long getLong(String k, long def);
    float getFloat(String k, float def);
    boolean getBoolean(String k, boolean def);
    boolean contains(String k);
    Editor edit();
    void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);
    void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);
}
