package android.view;

/** Velocidad por mínimos cuadrados de los últimos 100 ms, como Android. */
public final class VelocityTracker {
    private static final int N = 20;
    private final float[] xs = new float[N], ys = new float[N];
    private final long[] ts = new long[N];
    private int count, head;
    private float vx, vy;
    private int pointerId = -1;

    public static VelocityTracker obtain() { return new VelocityTracker(); }
    public void recycle() {}
    public void clear() { count = 0; head = 0; vx = vy = 0; pointerId = -1; }

    public void addMovement(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) clear();
        int idx = pointerId < 0 ? 0 : Math.max(0, e.findPointerIndex(pointerId));
        if (pointerId < 0) pointerId = e.getPointerId(0);
        for (int h = 0; h < e.getHistorySize(); h++) add(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h), e.getHistoricalEventTime(h));
        add(e.getX(idx), e.getY(idx), e.getEventTime());
    }

    private void add(float x, float y, long t) {
        xs[head] = x; ys[head] = y; ts[head] = t;
        head = (head + 1) % N;
        if (count < N) count++;
    }

    public void computeCurrentVelocity(int units) { computeCurrentVelocity(units, Float.MAX_VALUE); }

    public void computeCurrentVelocity(int units, float max) {
        if (count < 2) { vx = vy = 0; return; }
        int newest = (head - 1 + N) % N;
        long tN = ts[newest];
        double st = 0, sx = 0, sy = 0, stt = 0, stx = 0, sty = 0;
        int n = 0;
        for (int i = 0; i < count; i++) {
            int k = (newest - i + N) % N;
            long age = tN - ts[k];
            if (age > 100) break;
            double t = -age;
            st += t; sx += xs[k]; sy += ys[k]; stt += t * t; stx += t * xs[k]; sty += t * ys[k];
            n++;
        }
        if (n < 2) { vx = vy = 0; return; }
        double den = n * stt - st * st;
        if (Math.abs(den) < 1e-6) { vx = vy = 0; return; }
        vx = (float) ((n * stx - st * sx) / den * units);
        vy = (float) ((n * sty - st * sy) / den * units);
        vx = Math.max(-max, Math.min(max, vx));
        vy = Math.max(-max, Math.min(max, vy));
    }

    public float getXVelocity() { return vx; }
    public float getYVelocity() { return vy; }
    public float getXVelocity(int id) { return vx; }
    public float getYVelocity(int id) { return vy; }
}
