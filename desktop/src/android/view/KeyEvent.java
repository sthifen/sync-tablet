package android.view;



public class KeyEvent extends InputEvent {
    public static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_MULTIPLE = 2;
    public static final int META_SHIFT_ON = 1, META_ALT_ON = 2, META_SYM_ON = 4, META_CTRL_ON = 0x1000, META_META_ON = 0x10000,
            META_SHIFT_LEFT_ON = 0x40, META_CTRL_LEFT_ON = 0x2000, META_ALT_LEFT_ON = 0x10, META_CAPS_LOCK_ON = 0x100000, META_FUNCTION_ON = 8;
    public static final int FLAG_SOFT_KEYBOARD = 2, FLAG_LONG_PRESS = 0x80, FLAG_CANCELED = 0x20;

    public static final int KEYCODE_UNKNOWN = 0, KEYCODE_SOFT_LEFT = 1, KEYCODE_HOME = 3, KEYCODE_BACK = 4, KEYCODE_0 = 7, KEYCODE_1 = 8, KEYCODE_2 = 9,
            KEYCODE_3 = 10, KEYCODE_4 = 11, KEYCODE_5 = 12, KEYCODE_6 = 13, KEYCODE_7 = 14, KEYCODE_8 = 15, KEYCODE_9 = 16, KEYCODE_STAR = 17, KEYCODE_POUND = 18,
            KEYCODE_DPAD_UP = 19, KEYCODE_DPAD_DOWN = 20, KEYCODE_DPAD_LEFT = 21, KEYCODE_DPAD_RIGHT = 22, KEYCODE_DPAD_CENTER = 23,
            KEYCODE_VOLUME_UP = 24, KEYCODE_VOLUME_DOWN = 25, KEYCODE_A = 29, KEYCODE_B = 30, KEYCODE_C = 31, KEYCODE_D = 32, KEYCODE_E = 33,
            KEYCODE_F = 34, KEYCODE_G = 35, KEYCODE_H = 36, KEYCODE_I = 37, KEYCODE_J = 38, KEYCODE_K = 39, KEYCODE_L = 40, KEYCODE_M = 41,
            KEYCODE_N = 42, KEYCODE_O = 43, KEYCODE_P = 44, KEYCODE_Q = 45, KEYCODE_R = 46, KEYCODE_S = 47, KEYCODE_T = 48, KEYCODE_U = 49,
            KEYCODE_V = 50, KEYCODE_W = 51, KEYCODE_X = 52, KEYCODE_Y = 53, KEYCODE_Z = 54, KEYCODE_COMMA = 55, KEYCODE_PERIOD = 56,
            KEYCODE_ALT_LEFT = 57, KEYCODE_ALT_RIGHT = 58, KEYCODE_SHIFT_LEFT = 59, KEYCODE_SHIFT_RIGHT = 60, KEYCODE_TAB = 61, KEYCODE_SPACE = 62,
            KEYCODE_ENTER = 66, KEYCODE_DEL = 67, KEYCODE_GRAVE = 68, KEYCODE_MINUS = 69, KEYCODE_EQUALS = 70, KEYCODE_LEFT_BRACKET = 71,
            KEYCODE_RIGHT_BRACKET = 72, KEYCODE_BACKSLASH = 73, KEYCODE_SEMICOLON = 74, KEYCODE_APOSTROPHE = 75, KEYCODE_SLASH = 76,
            KEYCODE_AT = 77, KEYCODE_PLUS = 81, KEYCODE_MENU = 82, KEYCODE_SEARCH = 84, KEYCODE_PAGE_UP = 92, KEYCODE_PAGE_DOWN = 93,
            KEYCODE_ESCAPE = 111, KEYCODE_FORWARD_DEL = 112, KEYCODE_CTRL_LEFT = 113, KEYCODE_CTRL_RIGHT = 114, KEYCODE_CAPS_LOCK = 115,
            KEYCODE_META_LEFT = 117, KEYCODE_META_RIGHT = 118, KEYCODE_MOVE_HOME = 122, KEYCODE_MOVE_END = 123, KEYCODE_INSERT = 124,
            KEYCODE_F1 = 131, KEYCODE_F2 = 132, KEYCODE_F3 = 133, KEYCODE_F4 = 134, KEYCODE_F5 = 135, KEYCODE_F6 = 136, KEYCODE_F7 = 137,
            KEYCODE_F8 = 138, KEYCODE_F9 = 139, KEYCODE_F10 = 140, KEYCODE_F11 = 141, KEYCODE_F12 = 142, KEYCODE_NUMPAD_ENTER = 160,
            KEYCODE_ZOOM_IN = 168, KEYCODE_ZOOM_OUT = 169, KEYCODE_STYLUS_BUTTON_PRIMARY = 308;

    public interface Callback {
        boolean onKeyDown(int keyCode, KeyEvent event);
        boolean onKeyLongPress(int keyCode, KeyEvent event);
        boolean onKeyUp(int keyCode, KeyEvent event);
        boolean onKeyMultiple(int keyCode, int count, KeyEvent event);
    }

    private final long downTime, eventTime;
    private final int action, code, repeat, meta;
    private final int unicode;
    private final String characters;

    public KeyEvent(int action, int code) { this(0, 0, action, code, 0, 0); }
    public KeyEvent(long downTime, long eventTime, int action, int code, int repeat) { this(downTime, eventTime, action, code, repeat, 0); }
    public KeyEvent(long downTime, long eventTime, int action, int code, int repeat, int meta) { this(downTime, eventTime, action, code, repeat, meta, 0, null); }
    public KeyEvent(long downTime, long eventTime, int action, int code, int repeat, int meta, int unicode, String chars) {
        this.downTime = downTime; this.eventTime = eventTime; this.action = action; this.code = code; this.repeat = repeat; this.meta = meta;
        this.unicode = unicode; this.characters = chars;
    }

    public final int getAction() { return action; }
    public final int getKeyCode() { return code; }
    public final int getRepeatCount() { return repeat; }
    public final int getMetaState() { return meta; }
    public final int getModifiers() { return meta & (META_SHIFT_ON | META_ALT_ON | META_CTRL_ON | META_META_ON); }
    public final long getDownTime() { return downTime; }
    @Override public final long getEventTime() { return eventTime; }
    public final int getFlags() { return 0; }
    public final boolean isShiftPressed() { return (meta & META_SHIFT_ON) != 0; }
    public final boolean isCtrlPressed() { return (meta & META_CTRL_ON) != 0; }
    public final boolean isAltPressed() { return (meta & META_ALT_ON) != 0; }
    public final boolean isMetaPressed() { return (meta & META_META_ON) != 0; }
    public final boolean isFunctionPressed() { return false; }
    public final boolean isCapsLockOn() { return (meta & META_CAPS_LOCK_ON) != 0; }
    public final boolean hasNoModifiers() { return getModifiers() == 0; }
    public final boolean hasModifiers(int m) { return getModifiers() == m; }
    public final boolean isLongPress() { return false; }
    public final boolean isCanceled() { return false; }
    public final boolean isTracking() { return false; }
    public final void startTracking() {}
    public final boolean isSystem() { return code == KEYCODE_BACK || code == KEYCODE_HOME || code == KEYCODE_VOLUME_UP || code == KEYCODE_VOLUME_DOWN; }
    public int getUnicodeChar() { return unicode; }
    public int getUnicodeChar(int metaState) { return unicode; }
    public char getDisplayLabel() { return (char) unicode; }
    public String getCharacters() { return characters; }
    public boolean isPrintingKey() { return unicode >= 0x20; }
    @Override public int getSource() { return InputDevice.SOURCE_KEYBOARD; }

    public static boolean isModifierKey(int k) {
        return k == KEYCODE_SHIFT_LEFT || k == KEYCODE_SHIFT_RIGHT || k == KEYCODE_ALT_LEFT || k == KEYCODE_ALT_RIGHT
                || k == KEYCODE_CTRL_LEFT || k == KEYCODE_CTRL_RIGHT || k == KEYCODE_META_LEFT || k == KEYCODE_META_RIGHT;
    }

    public static KeyEvent changeAction(KeyEvent e, int action) { return new KeyEvent(e.downTime, e.eventTime, action, e.code, e.repeat, e.meta, e.unicode, e.characters); }

    public final boolean dispatch(Callback receiver, Object state, Object target) {
        return action == ACTION_DOWN ? receiver.onKeyDown(code, this) : action == ACTION_UP ? receiver.onKeyUp(code, this) : false;
    }

    /** Del teclado de Java a los códigos de Android. */
    public static int fromAwt(int vk) {
        if (vk >= java.awt.event.KeyEvent.VK_A && vk <= java.awt.event.KeyEvent.VK_Z) return KEYCODE_A + (vk - java.awt.event.KeyEvent.VK_A);
        if (vk >= java.awt.event.KeyEvent.VK_0 && vk <= java.awt.event.KeyEvent.VK_9) return KEYCODE_0 + (vk - java.awt.event.KeyEvent.VK_0);
        if (vk >= java.awt.event.KeyEvent.VK_NUMPAD0 && vk <= java.awt.event.KeyEvent.VK_NUMPAD9) return KEYCODE_0 + (vk - java.awt.event.KeyEvent.VK_NUMPAD0);
        if (vk >= java.awt.event.KeyEvent.VK_F1 && vk <= java.awt.event.KeyEvent.VK_F12) return KEYCODE_F1 + (vk - java.awt.event.KeyEvent.VK_F1);
        switch (vk) {
            case java.awt.event.KeyEvent.VK_UP: return KEYCODE_DPAD_UP;
            case java.awt.event.KeyEvent.VK_DOWN: return KEYCODE_DPAD_DOWN;
            case java.awt.event.KeyEvent.VK_LEFT: return KEYCODE_DPAD_LEFT;
            case java.awt.event.KeyEvent.VK_RIGHT: return KEYCODE_DPAD_RIGHT;
            case java.awt.event.KeyEvent.VK_ENTER: return KEYCODE_ENTER;
            case java.awt.event.KeyEvent.VK_BACK_SPACE: return KEYCODE_DEL;
            case java.awt.event.KeyEvent.VK_DELETE: return KEYCODE_FORWARD_DEL;
            case java.awt.event.KeyEvent.VK_ESCAPE: return KEYCODE_ESCAPE;
            case java.awt.event.KeyEvent.VK_TAB: return KEYCODE_TAB;
            case java.awt.event.KeyEvent.VK_SPACE: return KEYCODE_SPACE;
            case java.awt.event.KeyEvent.VK_PAGE_UP: return KEYCODE_PAGE_UP;
            case java.awt.event.KeyEvent.VK_PAGE_DOWN: return KEYCODE_PAGE_DOWN;
            case java.awt.event.KeyEvent.VK_HOME: return KEYCODE_MOVE_HOME;
            case java.awt.event.KeyEvent.VK_END: return KEYCODE_MOVE_END;
            case java.awt.event.KeyEvent.VK_INSERT: return KEYCODE_INSERT;
            case java.awt.event.KeyEvent.VK_SHIFT: return KEYCODE_SHIFT_LEFT;
            case java.awt.event.KeyEvent.VK_CONTROL: return KEYCODE_CTRL_LEFT;
            case java.awt.event.KeyEvent.VK_ALT: return KEYCODE_ALT_LEFT;
            case java.awt.event.KeyEvent.VK_ALT_GRAPH: return KEYCODE_ALT_RIGHT;
            case java.awt.event.KeyEvent.VK_META: case java.awt.event.KeyEvent.VK_WINDOWS: return KEYCODE_META_LEFT;
            case java.awt.event.KeyEvent.VK_COMMA: return KEYCODE_COMMA;
            case java.awt.event.KeyEvent.VK_PERIOD: return KEYCODE_PERIOD;
            case java.awt.event.KeyEvent.VK_MINUS: case java.awt.event.KeyEvent.VK_SUBTRACT: return KEYCODE_MINUS;
            case java.awt.event.KeyEvent.VK_EQUALS: return KEYCODE_EQUALS;
            case java.awt.event.KeyEvent.VK_PLUS: case java.awt.event.KeyEvent.VK_ADD: return KEYCODE_PLUS;
            case java.awt.event.KeyEvent.VK_SLASH: case java.awt.event.KeyEvent.VK_DIVIDE: return KEYCODE_SLASH;
            case java.awt.event.KeyEvent.VK_SEMICOLON: return KEYCODE_SEMICOLON;
            case java.awt.event.KeyEvent.VK_BACK_SLASH: return KEYCODE_BACKSLASH;
            case java.awt.event.KeyEvent.VK_OPEN_BRACKET: return KEYCODE_LEFT_BRACKET;
            case java.awt.event.KeyEvent.VK_CLOSE_BRACKET: return KEYCODE_RIGHT_BRACKET;
            case java.awt.event.KeyEvent.VK_QUOTE: return KEYCODE_APOSTROPHE;
            case java.awt.event.KeyEvent.VK_CAPS_LOCK: return KEYCODE_CAPS_LOCK;
            case java.awt.event.KeyEvent.VK_CONTEXT_MENU: return KEYCODE_MENU;
            default: return KEYCODE_UNKNOWN;
        }
    }

    public static int metaFromAwt(int mods) {
        int m = 0;
        if ((mods & java.awt.event.InputEvent.SHIFT_DOWN_MASK) != 0) m |= META_SHIFT_ON | META_SHIFT_LEFT_ON;
        if ((mods & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0) m |= META_CTRL_ON | META_CTRL_LEFT_ON;
        if ((mods & java.awt.event.InputEvent.ALT_DOWN_MASK) != 0) m |= META_ALT_ON | META_ALT_LEFT_ON;
        if ((mods & java.awt.event.InputEvent.META_DOWN_MASK) != 0) m |= META_META_ON;
        return m;
    }

    @Override public String toString() { return "KeyEvent{action=" + action + " code=" + code + "}"; }
}
