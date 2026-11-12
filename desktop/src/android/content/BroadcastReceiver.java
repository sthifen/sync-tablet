package android.content;

public abstract class BroadcastReceiver {
    public abstract void onReceive(Context c, Intent i);
    public final PendingResult goAsync() { return new PendingResult(); }
    public static class PendingResult { public final void finish() {} }
}
