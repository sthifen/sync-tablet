package android.view;

/** Un evento de puntero: ratón, tableta o dedos sintéticos (rueda). */
public final class MotionEvent extends InputEvent {
    public static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_MOVE = 2, ACTION_CANCEL = 3, ACTION_OUTSIDE = 4,
            ACTION_POINTER_DOWN = 5, ACTION_POINTER_UP = 6, ACTION_HOVER_MOVE = 7, ACTION_SCROLL = 8, ACTION_HOVER_ENTER = 9,
            ACTION_HOVER_EXIT = 10, ACTION_BUTTON_PRESS = 11, ACTION_BUTTON_RELEASE = 12;
    public static final int ACTION_MASK = 0xff, ACTION_POINTER_INDEX_MASK = 0xff00, ACTION_POINTER_INDEX_SHIFT = 8;
    public static final int ACTION_POINTER_1_DOWN = 5, ACTION_POINTER_2_DOWN = 0x105, ACTION_POINTER_1_UP = 6, ACTION_POINTER_2_UP = 0x106;
    public static final int TOOL_TYPE_UNKNOWN = 0, TOOL_TYPE_FINGER = 1, TOOL_TYPE_STYLUS = 2, TOOL_TYPE_MOUSE = 3, TOOL_TYPE_ERASER = 4, TOOL_TYPE_PALM = 5;
    public static final int BUTTON_PRIMARY = 1, BUTTON_SECONDARY = 2, BUTTON_TERTIARY = 4, BUTTON_BACK = 8, BUTTON_FORWARD = 16,
            BUTTON_STYLUS_PRIMARY = 32, BUTTON_STYLUS_SECONDARY = 64;
    public static final int AXIS_X = 0, AXIS_Y = 1, AXIS_PRESSURE = 2, AXIS_SIZE = 3, AXIS_TOUCH_MAJOR = 4, AXIS_TOUCH_MINOR = 5,
            AXIS_TOOL_MAJOR = 6, AXIS_TOOL_MINOR = 7, AXIS_ORIENTATION = 8, AXIS_VSCROLL = 9, AXIS_HSCROLL = 10, AXIS_Z = 11,
            AXIS_DISTANCE = 24, AXIS_TILT = 25, AXIS_SCROLL = 26, AXIS_RELATIVE_X = 27, AXIS_RELATIVE_Y = 28;
    public static final int FLAG_WINDOW_IS_OBSCURED = 1, FLAG_CANCELED = 0x20;
    public static final int EDGE_TOP = 1, EDGE_BOTTOM = 2, EDGE_LEFT = 4, EDGE_RIGHT = 8;
    public static final int CLASSIFICATION_NONE = 0;

    public static final class PointerProperties {
        public int id, toolType;
        public PointerProperties() {}
        public PointerProperties(PointerProperties o) { id = o.id; toolType = o.toolType; }
        public void clear() { id = -1; toolType = 0; }
    }

    public static final class PointerCoords {
        public float x, y, pressure = 1, size, touchMajor, touchMinor, toolMajor, toolMinor, orientation;
        public PointerCoords() {}
        public PointerCoords(PointerCoords o) { x = o.x; y = o.y; pressure = o.pressure; size = o.size; orientation = o.orientation; }
        public void clear() { x = y = 0; pressure = 1; size = 0; }
        public float getAxisValue(int axis) { return axis == AXIS_X ? x : axis == AXIS_Y ? y : axis == AXIS_PRESSURE ? pressure : axis == AXIS_SIZE ? size : 0; }
        public void setAxisValue(int axis, float v) { if (axis == AXIS_X) x = v; else if (axis == AXIS_Y) y = v; else if (axis == AXIS_PRESSURE) pressure = v; }
    }

    private int action;
    private long downTime, eventTime;
    private int count;
    private int[] ids = new int[2], tools = new int[2];
    private float[] xs = new float[2], ys = new float[2], pressures = new float[2];
    private float offsetX, offsetY;
    private float rawOffsetX, rawOffsetY;
    private int buttons, meta, flags, source = InputDevice.SOURCE_MOUSE;
    private float vscroll, hscroll;
    // Historia: posiciones intermedias (en coordenadas sin desplazar).
    private int historySize;
    private float[] hx = new float[0], hy = new float[0], hp = new float[0];
    private long[] ht = new long[0];

    private MotionEvent() {}

    public static MotionEvent obtain(long downTime, long eventTime, int action, float x, float y, int metaState) {
        return obtain(downTime, eventTime, action, x, y, 1f, 1f, metaState, 1f, 1f, 0, 0);
    }

    public static MotionEvent obtain(long downTime, long eventTime, int action, float x, float y, float pressure, float size, int metaState,
                                     float xPrecision, float yPrecision, int deviceId, int edgeFlags) {
        MotionEvent e = new MotionEvent();
        e.downTime = downTime; e.eventTime = eventTime; e.action = action; e.meta = metaState;
        e.count = 1; e.ids[0] = 0; e.tools[0] = TOOL_TYPE_FINGER; e.xs[0] = x; e.ys[0] = y; e.pressures[0] = pressure;
        e.source = InputDevice.SOURCE_TOUCHSCREEN;
        return e;
    }

    public static MotionEvent obtain(long downTime, long eventTime, int action, int pointerCount, PointerProperties[] props, PointerCoords[] coords,
                                     int metaState, int buttonState, float xPrecision, float yPrecision, int deviceId, int edgeFlags, int source, int flags) {
        MotionEvent e = new MotionEvent();
        e.downTime = downTime; e.eventTime = eventTime; e.action = action; e.meta = metaState; e.buttons = buttonState; e.source = source; e.flags = flags;
        e.count = pointerCount;
        e.ids = new int[Math.max(2, pointerCount)]; e.tools = new int[e.ids.length]; e.xs = new float[e.ids.length]; e.ys = new float[e.ids.length]; e.pressures = new float[e.ids.length];
        for (int i = 0; i < pointerCount; i++) {
            e.ids[i] = props[i].id; e.tools[i] = props[i].toolType; e.xs[i] = coords[i].x; e.ys[i] = coords[i].y; e.pressures[i] = coords[i].pressure;
        }
        return e;
    }

    /** Para la ventana: un puntero por cada uno de `ids`, en píxeles de la ventana. */
    public static MotionEvent create(long downTime, long eventTime, int action, int[] ids, int[] tools, float[] xs, float[] ys, float[] pressures, int buttons, int meta, int source) {
        MotionEvent e = new MotionEvent();
        e.downTime = downTime; e.eventTime = eventTime; e.action = action;
        e.count = ids.length;
        e.ids = ids.clone(); e.tools = tools.clone(); e.xs = xs.clone(); e.ys = ys.clone(); e.pressures = pressures.clone();
        e.buttons = buttons; e.meta = meta; e.source = source;
        return e;
    }

    public void setScroll(float v, float h) { vscroll = v; hscroll = h; }

    public void setHistory(float[] xs, float[] ys, float[] ps, long[] ts) {
        historySize = xs.length; hx = xs.clone(); hy = ys.clone(); hp = ps.clone(); ht = ts.clone();
    }

    public static MotionEvent obtain(MotionEvent o) {
        MotionEvent e = new MotionEvent();
        e.action = o.action; e.downTime = o.downTime; e.eventTime = o.eventTime; e.count = o.count;
        e.ids = o.ids.clone(); e.tools = o.tools.clone(); e.xs = o.xs.clone(); e.ys = o.ys.clone(); e.pressures = o.pressures.clone();
        e.offsetX = o.offsetX; e.offsetY = o.offsetY; e.rawOffsetX = o.rawOffsetX; e.rawOffsetY = o.rawOffsetY;
        e.buttons = o.buttons; e.meta = o.meta; e.flags = o.flags; e.source = o.source; e.vscroll = o.vscroll; e.hscroll = o.hscroll;
        e.historySize = o.historySize; e.hx = o.hx.clone(); e.hy = o.hy.clone(); e.hp = o.hp.clone(); e.ht = o.ht.clone();
        return e;
    }

    public static MotionEvent obtainNoHistory(MotionEvent o) { MotionEvent e = obtain(o); e.historySize = 0; return e; }

    public MotionEvent copy() { return obtain(this); }
    public void recycle() {}

    public int getAction() { return action; }
    public int getActionMasked() { return action & ACTION_MASK; }
    public int getActionIndex() { return (action & ACTION_POINTER_INDEX_MASK) >> ACTION_POINTER_INDEX_SHIFT; }
    public void setAction(int a) { action = a; }
    public int getPointerCount() { return count; }
    public int getPointerId(int i) { return ids[i]; }
    public int findPointerIndex(int id) { for (int i = 0; i < count; i++) if (ids[i] == id) return i; return -1; }
    public int getToolType(int i) { return tools[i]; }
    public float getX() { return xs[0] + offsetX; }
    public float getY() { return ys[0] + offsetY; }
    public float getX(int i) { return xs[i] + offsetX; }
    public float getY(int i) { return ys[i] + offsetY; }
    public float getRawX() { return xs[0] + rawOffsetX; }
    public float getRawY() { return ys[0] + rawOffsetY; }
    public float getRawX(int i) { return xs[i] + rawOffsetX; }
    public float getRawY(int i) { return ys[i] + rawOffsetY; }
    public float getPressure() { return pressures[0]; }
    public float getPressure(int i) { return pressures[i]; }
    public float getSize() { return 0.05f; }
    public float getSize(int i) { return 0.05f; }
    public float getTouchMajor() { return 10; }
    public float getTouchMajor(int i) { return 10; }
    public float getTouchMinor() { return 10; }
    public float getTouchMinor(int i) { return 10; }
    public float getToolMajor() { return 10; }
    public float getToolMajor(int i) { return 10; }
    public float getOrientation() { return 0; }
    public float getOrientation(int i) { return 0; }
    public int getButtonState() { return buttons; }
    public int getActionButton() { return buttons; }
    public boolean isButtonPressed(int b) { return (buttons & b) != 0; }
    public int getMetaState() { return meta; }
    public int getFlags() { return flags; }
    public int getEdgeFlags() { return 0; }
    public float getXPrecision() { return 1; }
    public float getYPrecision() { return 1; }
    public int getClassification() { return 0; }
    @Override public int getSource() { return source; }
    public void setSource(int s) { source = s; }
    public long getDownTime() { return downTime; }
    public void setDownTime(long t) { downTime = t; }
    @Override public long getEventTime() { return eventTime; }
    public long getEventTimeNanos() { return eventTime * 1_000_000L; }

    public float getAxisValue(int axis) { return getAxisValue(axis, 0); }
    public float getAxisValue(int axis, int i) {
        switch (axis) {
            case AXIS_X: return getX(i);
            case AXIS_Y: return getY(i);
            case AXIS_PRESSURE: return pressures[i];
            case AXIS_VSCROLL: return vscroll;
            case AXIS_HSCROLL: return hscroll;
            default: return 0;
        }
    }

    public int getHistorySize() { return historySize; }
    public float getHistoricalX(int h) { return hx[h] + offsetX; }
    public float getHistoricalY(int h) { return hy[h] + offsetY; }
    public float getHistoricalX(int i, int h) { return i == 0 ? hx[h] + offsetX : getX(i); }
    public float getHistoricalY(int i, int h) { return i == 0 ? hy[h] + offsetY : getY(i); }
    public float getHistoricalPressure(int h) { return hp[h]; }
    public float getHistoricalPressure(int i, int h) { return i == 0 ? hp[h] : pressures[i]; }
    public float getHistoricalSize(int h) { return 0.05f; }
    public float getHistoricalAxisValue(int axis, int h) { return axis == AXIS_X ? getHistoricalX(h) : axis == AXIS_Y ? getHistoricalY(h) : axis == AXIS_PRESSURE ? hp[h] : 0; }
    public float getHistoricalAxisValue(int axis, int i, int h) { return getHistoricalAxisValue(axis, h); }
    public long getHistoricalEventTime(int h) { return ht[h]; }

    public void offsetLocation(float dx, float dy) { offsetX += dx; offsetY += dy; }
    public void setLocation(float x, float y) { offsetX = x - xs[0]; offsetY = y - ys[0]; }

    public void transform(android.graphics.Matrix m) {
        float[] p = new float[2];
        for (int i = 0; i < count; i++) {
            p[0] = xs[i] + offsetX; p[1] = ys[i] + offsetY;
            m.mapPoints(p);
            xs[i] = p[0] - offsetX; ys[i] = p[1] - offsetY;
        }
        for (int h = 0; h < historySize; h++) {
            p[0] = hx[h] + offsetX; p[1] = hy[h] + offsetY;
            m.mapPoints(p);
            hx[h] = p[0] - offsetX; hy[h] = p[1] - offsetY;
        }
    }

    /** Para la ventana: el desplazamiento de la ventana a la pantalla. */
    public void setRawOffset(float dx, float dy) { rawOffsetX = dx; rawOffsetY = dy; }
    public float offsetX() { return offsetX; }
    public float offsetY() { return offsetY; }

    public static String actionToString(int a) {
        switch (a & ACTION_MASK) {
            case ACTION_DOWN: return "ACTION_DOWN";
            case ACTION_UP: return "ACTION_UP";
            case ACTION_MOVE: return "ACTION_MOVE";
            case ACTION_CANCEL: return "ACTION_CANCEL";
            case ACTION_POINTER_DOWN: return "ACTION_POINTER_DOWN";
            case ACTION_POINTER_UP: return "ACTION_POINTER_UP";
            default: return "ACTION_" + a;
        }
    }

    @Override public String toString() { return "MotionEvent{" + actionToString(action) + " x=" + getX() + " y=" + getY() + " n=" + count + "}"; }
}
