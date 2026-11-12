package android.view;

public abstract class InputEvent {
    public abstract long getEventTime();
    public int getSource() { return 0; }
    public boolean isFromSource(int s) { return (getSource() & s) == s; }
    public int getDeviceId() { return 1; }
    public InputDevice getDevice() { return InputDevice.MOUSE; }
}
