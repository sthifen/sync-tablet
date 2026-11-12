package android.view;

public final class InputDevice {
    public static final int SOURCE_CLASS_POINTER = 0x2, SOURCE_TOUCHSCREEN = 0x1002, SOURCE_MOUSE = 0x2002, SOURCE_STYLUS = 0x4002,
            SOURCE_KEYBOARD = 0x101, SOURCE_TOUCHPAD = 0x100008, SOURCE_UNKNOWN = 0, SOURCE_BLUETOOTH_STYLUS = 0xC002;
    static final InputDevice MOUSE = new InputDevice();
    public String getName() { return "Ratón"; }
    public int getSources() { return SOURCE_MOUSE | SOURCE_KEYBOARD; }
    public boolean supportsSource(int s) { return (getSources() & s) == s; }
    public static InputDevice getDevice(int id) { return MOUSE; }
    public static int[] getDeviceIds() { return new int[] {1}; }
}
