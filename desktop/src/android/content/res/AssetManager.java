package android.content.res;

public final class AssetManager {
    public java.io.InputStream open(String name) throws java.io.IOException {
        java.io.InputStream in = AssetManager.class.getResourceAsStream("/assets/" + name);
        if (in == null) throw new java.io.FileNotFoundException(name);
        return in;
    }
    public String[] list(String path) { return new String[0]; }
}
