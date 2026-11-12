package android.database;

import java.util.ArrayList;

public class MatrixCursor implements Cursor {
    private final String[] columns;
    private final ArrayList<Object[]> rows = new ArrayList<>();
    private int pos = -1;
    private boolean closed;

    public MatrixCursor(String[] columns) { this.columns = columns; }
    public MatrixCursor(String[] columns, int capacity) { this(columns); }
    public void addRow(Object[] row) { rows.add(row.clone()); }
    @Override public int getCount() { return rows.size(); }
    @Override public int getPosition() { return pos; }
    @Override public boolean moveToFirst() { pos = 0; return !rows.isEmpty(); }
    @Override public boolean moveToNext() { pos++; return pos < rows.size(); }
    @Override public boolean moveToPosition(int p) { pos = p; return p >= 0 && p < rows.size(); }
    @Override public boolean isAfterLast() { return pos >= rows.size(); }
    @Override public int getColumnIndex(String name) { for (int i = 0; i < columns.length; i++) if (columns[i].equals(name)) return i; return -1; }
    @Override public int getColumnIndexOrThrow(String name) { int i = getColumnIndex(name); if (i < 0) throw new IllegalArgumentException("column '" + name + "' does not exist"); return i; }
    @Override public String getColumnName(int i) { return columns[i]; }
    @Override public String[] getColumnNames() { return columns; }
    @Override public int getColumnCount() { return columns.length; }
    private Object get(int i) { if (pos < 0 || pos >= rows.size()) throw new IllegalStateException("CursorIndexOutOfBounds"); return rows.get(pos)[i]; }
    @Override public String getString(int i) { Object o = get(i); return o == null ? null : o.toString(); }
    @Override public int getInt(int i) { Object o = get(i); return o instanceof Number ? ((Number) o).intValue() : o == null ? 0 : Integer.parseInt(o.toString()); }
    @Override public long getLong(int i) { Object o = get(i); return o instanceof Number ? ((Number) o).longValue() : o == null ? 0 : Long.parseLong(o.toString()); }
    @Override public float getFloat(int i) { Object o = get(i); return o instanceof Number ? ((Number) o).floatValue() : 0; }
    @Override public double getDouble(int i) { Object o = get(i); return o instanceof Number ? ((Number) o).doubleValue() : 0; }
    @Override public byte[] getBlob(int i) { return (byte[]) get(i); }
    @Override public boolean isNull(int i) { return get(i) == null; }
    @Override public int getType(int i) { Object o = get(i); return o == null ? FIELD_TYPE_NULL : o instanceof Number ? FIELD_TYPE_INTEGER : FIELD_TYPE_STRING; }
    @Override public boolean isClosed() { return closed; }
    @Override public void close() { closed = true; }
}
