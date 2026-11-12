package android.os;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class ParcelFileDescriptor implements java.io.Closeable {
    public static final int MODE_READ_ONLY = 0x10000000, MODE_WRITE_ONLY = 0x20000000, MODE_READ_WRITE = 0x30000000,
            MODE_CREATE = 0x08000000, MODE_TRUNCATE = 0x04000000, MODE_APPEND = 0x02000000;

    private final File file;
    private final byte[] data;

    public ParcelFileDescriptor(File f) { file = f; data = null; }
    public ParcelFileDescriptor(byte[] data) { file = null; this.data = data; }

    public static ParcelFileDescriptor open(File f, int mode) throws FileNotFoundException {
        if ((mode & MODE_CREATE) == 0 && !f.exists()) throw new FileNotFoundException(f.getPath());
        return new ParcelFileDescriptor(f);
    }

    public File file() { return file; }

    public byte[] bytes() throws IOException { return data != null ? data : java.nio.file.Files.readAllBytes(file.toPath()); }

    public java.io.FileDescriptor getFileDescriptor() { return new java.io.FileDescriptor(); }
    public long getStatSize() { return data != null ? data.length : file.length(); }
    public int getFd() { return -1; }
    public int detachFd() { return -1; }
    @Override public void close() {}

    public static class AutoCloseInputStream extends java.io.FileInputStream {
        public AutoCloseInputStream(ParcelFileDescriptor pfd) throws FileNotFoundException { super(pfd.file); }
    }

    public static class AutoCloseOutputStream extends java.io.FileOutputStream {
        public AutoCloseOutputStream(ParcelFileDescriptor pfd) throws FileNotFoundException { super(pfd.file); }
    }
}
