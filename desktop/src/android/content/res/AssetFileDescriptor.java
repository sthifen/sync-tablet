package android.content.res;

public class AssetFileDescriptor implements java.io.Closeable {
    private final android.os.ParcelFileDescriptor fd;
    public AssetFileDescriptor(android.os.ParcelFileDescriptor fd) { this.fd = fd; }
    public android.os.ParcelFileDescriptor getParcelFileDescriptor() { return fd; }
    public long getLength() { return fd.getStatSize(); }
    @Override public void close() {}
}
