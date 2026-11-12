package android.widget;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;

/** GridLayout: celdas en orden por filas con columnCount, columnas tan anchas como su hijo más ancho y pesos. */
public class GridLayout extends ViewGroup {
    public static final int HORIZONTAL = 0, VERTICAL = 1, UNDEFINED = Integer.MIN_VALUE, ALIGN_BOUNDS = 0, ALIGN_MARGINS = 1;

    public abstract static class Alignment {
        abstract int offset(int cell, int size);
        boolean fill() { return false; }
    }

    private static final Alignment UNDEFINED_ALIGNMENT = new Alignment() { int offset(int c, int s) { return 0; } };
    public static final Alignment START = new Alignment() { int offset(int c, int s) { return 0; } };
    public static final Alignment END = new Alignment() { int offset(int c, int s) { return c - s; } };
    public static final Alignment TOP = START, BOTTOM = END, LEFT = START, RIGHT = END, BASELINE = START;
    public static final Alignment CENTER = new Alignment() { int offset(int c, int s) { return (c - s) / 2; } };
    public static final Alignment FILL = new Alignment() { int offset(int c, int s) { return 0; } @Override boolean fill() { return true; } };

    public static class Spec {
        final int start, size;
        final Alignment alignment;
        final float weight;
        Spec(int start, int size, Alignment a, float w) { this.start = start; this.size = Math.max(1, size); this.alignment = a; this.weight = w; }
        Alignment align(boolean horizontal) {
            if (alignment != UNDEFINED_ALIGNMENT) return alignment;
            if (weight == 0f) return horizontal ? START : BASELINE;
            return FILL;
        }
    }

    public static final Spec UNDEFINED_SPEC = new Spec(UNDEFINED, 1, UNDEFINED_ALIGNMENT, 0);

    public static Spec spec(int start) { return new Spec(start, 1, UNDEFINED_ALIGNMENT, 0); }
    public static Spec spec(int start, int size) { return new Spec(start, size, UNDEFINED_ALIGNMENT, 0); }
    public static Spec spec(int start, float weight) { return new Spec(start, 1, UNDEFINED_ALIGNMENT, weight); }
    public static Spec spec(int start, int size, float weight) { return new Spec(start, size, UNDEFINED_ALIGNMENT, weight); }
    public static Spec spec(int start, Alignment a) { return new Spec(start, 1, a, 0); }
    public static Spec spec(int start, int size, Alignment a) { return new Spec(start, size, a, 0); }
    public static Spec spec(int start, Alignment a, float weight) { return new Spec(start, 1, a, weight); }
    public static Spec spec(int start, int size, Alignment a, float weight) { return new Spec(start, size, a, weight); }

    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        public Spec rowSpec = UNDEFINED_SPEC, columnSpec = UNDEFINED_SPEC;
        public LayoutParams() { super(WRAP_CONTENT, WRAP_CONTENT); }
        public LayoutParams(Spec row, Spec col) { this(); rowSpec = row; columnSpec = col; }
        public LayoutParams(ViewGroup.LayoutParams p) { super(p); }
        public LayoutParams(ViewGroup.MarginLayoutParams p) { super(p); }
        public LayoutParams(LayoutParams p) { super((ViewGroup.MarginLayoutParams) p); rowSpec = p.rowSpec; columnSpec = p.columnSpec; }
        public LayoutParams(Context c, android.util.AttributeSet a) { super(c, a); }
        public void setGravity(int g) {
            int h = g & android.view.Gravity.HORIZONTAL_GRAVITY_MASK, v = g & android.view.Gravity.VERTICAL_GRAVITY_MASK;
            Alignment ha = h == android.view.Gravity.CENTER_HORIZONTAL ? CENTER : h == android.view.Gravity.RIGHT ? END : h == android.view.Gravity.FILL_HORIZONTAL ? FILL : START;
            Alignment va = v == android.view.Gravity.CENTER_VERTICAL ? CENTER : v == android.view.Gravity.BOTTOM ? END : v == android.view.Gravity.FILL_VERTICAL ? FILL : START;
            columnSpec = new Spec(columnSpec.start, columnSpec.size, ha, columnSpec.weight);
            rowSpec = new Spec(rowSpec.start, rowSpec.size, va, rowSpec.weight);
        }
    }

    private int orientation = HORIZONTAL, columnCount = UNDEFINED, rowCount = UNDEFINED;
    private boolean useDefaultMargins;
    private int[] colX = new int[0], rowY = new int[0];
    private final ArrayList<int[]> cells = new ArrayList<>();

    public GridLayout(Context c) { super(c); }
    public GridLayout(Context c, android.util.AttributeSet a) { super(c); }

    public void setColumnCount(int n) { columnCount = n; requestLayout(); }
    public int getColumnCount() { return columnCount; }
    public void setRowCount(int n) { rowCount = n; requestLayout(); }
    public int getRowCount() { return rowCount; }
    public void setOrientation(int o) { orientation = o; requestLayout(); }
    public int getOrientation() { return orientation; }
    public void setUseDefaultMargins(boolean u) { useDefaultMargins = u; requestLayout(); }
    public void setAlignmentMode(int m) {}
    public void setRowOrderPreserved(boolean p) {}
    public void setColumnOrderPreserved(boolean p) {}

    @Override protected LayoutParams generateDefaultLayoutParams() { return new LayoutParams(); }
    @Override protected boolean checkLayoutParams(ViewGroup.LayoutParams p) { return p instanceof LayoutParams; }
    @Override protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        if (p instanceof LayoutParams) return new LayoutParams((LayoutParams) p);
        if (p instanceof MarginLayoutParams) return new LayoutParams((MarginLayoutParams) p);
        return new LayoutParams(p);
    }

    /** Coloca a cada hijo en su fila y columna: [fila, columna, filas, columnas]. */
    private void place() {
        cells.clear();
        int cols = columnCount == UNDEFINED ? Integer.MAX_VALUE : Math.max(1, columnCount);
        int rows = rowCount == UNDEFINED ? Integer.MAX_VALUE : Math.max(1, rowCount);
        java.util.HashSet<Long> used = new java.util.HashSet<>();
        int r = 0, c = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            LayoutParams lp = (LayoutParams) v.getLayoutParams();
            if (v.getVisibility() == GONE) { cells.add(null); continue; }
            int rs = lp.rowSpec.size, cs = lp.columnSpec.size;
            int row = lp.rowSpec.start, col = lp.columnSpec.start;
            if (orientation == HORIZONTAL) {
                if (col != UNDEFINED) { if (col < c && row == UNDEFINED) r++; c = col; }
                if (row != UNDEFINED) r = row;
                while (true) {
                    if (cols != Integer.MAX_VALUE && c + cs > cols) { c = col != UNDEFINED ? col : 0; r++; continue; }
                    boolean free = true;
                    for (int y = 0; y < rs && free; y++) for (int x = 0; x < cs && free; x++) if (used.contains(key(r + y, c + x))) free = false;
                    if (free) break;
                    if (col != UNDEFINED) { r++; } else c++;
                }
            } else {
                if (row != UNDEFINED) { if (row < r && col == UNDEFINED) c++; r = row; }
                if (col != UNDEFINED) c = col;
                while (true) {
                    if (rows != Integer.MAX_VALUE && r + rs > rows) { r = row != UNDEFINED ? row : 0; c++; continue; }
                    boolean free = true;
                    for (int y = 0; y < rs && free; y++) for (int x = 0; x < cs && free; x++) if (used.contains(key(r + y, c + x))) free = false;
                    if (free) break;
                    if (row != UNDEFINED) c++; else r++;
                }
            }
            for (int y = 0; y < rs; y++) for (int x = 0; x < cs; x++) used.add(key(r + y, c + x));
            cells.add(new int[] {r, c, rs, cs});
            if (orientation == HORIZONTAL) c += cs; else r += rs;
        }
    }

    private static long key(int r, int c) { return ((long) r << 32) | (c & 0xffffffffL); }

    private int margin(MarginLayoutParams lp, int which) {
        int m = which == 0 ? lp.leftMargin : which == 1 ? lp.topMargin : which == 2 ? lp.rightMargin : lp.bottomMargin;
        if (m == 0 && useDefaultMargins) m = Math.round(8 * getResources().getDisplayMetrics().density);
        return m;
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        place();
        int nCols = 0, nRows = 0;
        for (int[] c : cells) if (c != null) { nRows = Math.max(nRows, c[0] + c[2]); nCols = Math.max(nCols, c[1] + c[3]); }
        if (columnCount != UNDEFINED) nCols = Math.max(nCols, columnCount);
        int[] colW = new int[nCols], rowH = new int[nRows];
        float[] colWeight = new float[nCols], rowWeight = new float[nRows];
        int hPad = mPaddingLeft + mPaddingRight, vPad = mPaddingTop + mPaddingBottom;
        int count = getChildCount();
        // Primera pasada: medir con lo que piden.
        for (int i = 0; i < count; i++) {
            int[] c = cells.get(i);
            if (c == null) continue;
            View v = getChildAt(i);
            LayoutParams lp = (LayoutParams) v.getLayoutParams();
            int mh = margin(lp, 0) + margin(lp, 2), mv = margin(lp, 1) + margin(lp, 3);
            int cws = getChildMeasureSpec(ws, hPad + mh, lp.width == LayoutParams.MATCH_PARENT ? LayoutParams.WRAP_CONTENT : lp.width);
            int chs = getChildMeasureSpec(hs, vPad + mv, lp.height == LayoutParams.MATCH_PARENT ? LayoutParams.WRAP_CONTENT : lp.height);
            v.measure(cws, chs);
            if (c[3] == 1) colW[c[1]] = Math.max(colW[c[1]], v.getMeasuredWidth() + mh);
            if (c[2] == 1) rowH[c[0]] = Math.max(rowH[c[0]], v.getMeasuredHeight() + mv);
            if (lp.columnSpec.weight > 0) for (int x = 0; x < c[3]; x++) colWeight[c[1] + x] = Math.max(colWeight[c[1] + x], lp.columnSpec.weight);
            if (lp.rowSpec.weight > 0) for (int y = 0; y < c[2]; y++) rowWeight[c[0] + y] = Math.max(rowWeight[c[0] + y], lp.rowSpec.weight);
        }
        // Celdas que ocupan varias: reparten lo que les falte.
        for (int i = 0; i < count; i++) {
            int[] c = cells.get(i);
            if (c == null) continue;
            View v = getChildAt(i);
            LayoutParams lp = (LayoutParams) v.getLayoutParams();
            if (c[3] > 1) {
                int need = v.getMeasuredWidth() + margin(lp, 0) + margin(lp, 2), have = 0;
                for (int x = 0; x < c[3]; x++) have += colW[c[1] + x];
                if (need > have) { int add = (need - have) / c[3]; for (int x = 0; x < c[3]; x++) colW[c[1] + x] += add; }
            }
            if (c[2] > 1) {
                int need = v.getMeasuredHeight() + margin(lp, 1) + margin(lp, 3), have = 0;
                for (int y = 0; y < c[2]; y++) have += rowH[c[0] + y];
                if (need > have) { int add = (need - have) / c[2]; for (int y = 0; y < c[2]; y++) rowH[c[0] + y] += add; }
            }
        }
        distribute(colW, colWeight, ws, hPad);
        distribute(rowH, rowWeight, hs, vPad);
        colX = new int[nCols + 1];
        rowY = new int[nRows + 1];
        for (int i = 0; i < nCols; i++) colX[i + 1] = colX[i] + colW[i];
        for (int i = 0; i < nRows; i++) rowY[i + 1] = rowY[i] + rowH[i];
        // Los que llenan su celda se vuelven a medir con su tamaño.
        for (int i = 0; i < count; i++) {
            int[] c = cells.get(i);
            if (c == null) continue;
            View v = getChildAt(i);
            LayoutParams lp = (LayoutParams) v.getLayoutParams();
            boolean fillW = lp.columnSpec.align(true).fill() || lp.width == LayoutParams.MATCH_PARENT;
            boolean fillH = lp.rowSpec.align(false).fill() || lp.height == LayoutParams.MATCH_PARENT;
            if (!fillW && !fillH) continue;
            int cw = colX[c[1] + c[3]] - colX[c[1]] - margin(lp, 0) - margin(lp, 2);
            int ch = rowY[c[0] + c[2]] - rowY[c[0]] - margin(lp, 1) - margin(lp, 3);
            v.measure(fillW ? MeasureSpec.makeMeasureSpec(Math.max(0, cw), MeasureSpec.EXACTLY) : MeasureSpec.makeMeasureSpec(v.getMeasuredWidth(), MeasureSpec.EXACTLY),
                    fillH ? MeasureSpec.makeMeasureSpec(Math.max(0, ch), MeasureSpec.EXACTLY) : MeasureSpec.makeMeasureSpec(v.getMeasuredHeight(), MeasureSpec.EXACTLY));
        }
        int w = colX[nCols] + hPad, h = rowY[nRows] + vPad;
        setMeasuredDimension(resolveSizeAndState(Math.max(w, getSuggestedMinimumWidth()), ws, 0), resolveSizeAndState(Math.max(h, getSuggestedMinimumHeight()), hs, 0));
    }

    private static void distribute(int[] sizes, float[] weights, int spec, int pad) {
        if (MeasureSpec.getMode(spec) != MeasureSpec.EXACTLY) return;
        float total = 0;
        int used = 0;
        for (int i = 0; i < sizes.length; i++) { total += weights[i]; used += sizes[i]; }
        if (total <= 0) return;
        int extra = MeasureSpec.getSize(spec) - pad - used;
        if (extra <= 0) return;
        for (int i = 0; i < sizes.length; i++) if (weights[i] > 0) sizes[i] += Math.round(extra * weights[i] / total);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            int[] c = i < cells.size() ? cells.get(i) : null;
            if (c == null) continue;
            View v = getChildAt(i);
            LayoutParams lp = (LayoutParams) v.getLayoutParams();
            if (c[1] + c[3] >= colX.length || c[0] + c[2] >= rowY.length) continue;
            int x0 = mPaddingLeft + colX[c[1]] + margin(lp, 0), x1 = mPaddingLeft + colX[c[1] + c[3]] - margin(lp, 2);
            int y0 = mPaddingTop + rowY[c[0]] + margin(lp, 1), y1 = mPaddingTop + rowY[c[0] + c[2]] - margin(lp, 3);
            int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
            int x = x0 + lp.columnSpec.align(true).offset(x1 - x0, w);
            int y = y0 + lp.rowSpec.align(false).offset(y1 - y0, h);
            v.layout(x, y, x + w, y + h);
        }
    }
}
