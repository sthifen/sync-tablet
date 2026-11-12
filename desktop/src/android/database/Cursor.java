package android.database;

public interface Cursor extends java.io.Closeable {
    int FIELD_TYPE_NULL = 0, FIELD_TYPE_INTEGER = 1, FIELD_TYPE_FLOAT = 2, FIELD_TYPE_STRING = 3, FIELD_TYPE_BLOB = 4;
    int getCount();
    int getPosition();
    boolean moveToFirst();
    boolean moveToNext();
    boolean moveToPosition(int p);
    boolean isAfterLast();
    int getColumnIndex(String name);
    int getColumnIndexOrThrow(String name);
    String getColumnName(int i);
    String[] getColumnNames();
    int getColumnCount();
    String getString(int i);
    int getInt(int i);
    long getLong(int i);
    float getFloat(int i);
    double getDouble(int i);
    byte[] getBlob(int i);
    boolean isNull(int i);
    int getType(int i);
    boolean isClosed();
    @Override void close();
}
