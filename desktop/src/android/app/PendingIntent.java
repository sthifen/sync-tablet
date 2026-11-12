package android.app;

public final class PendingIntent {
    public static final int FLAG_ONE_SHOT = 0x40000000, FLAG_NO_CREATE = 0x20000000, FLAG_CANCEL_CURRENT = 0x10000000, FLAG_UPDATE_CURRENT = 0x08000000,
            FLAG_IMMUTABLE = 0x04000000, FLAG_MUTABLE = 0x02000000;
    private final android.content.Intent intent;
    private PendingIntent(android.content.Intent i) { intent = i; }
    public static PendingIntent getActivity(android.content.Context c, int request, android.content.Intent i, int flags) { return new PendingIntent(i); }
    public static PendingIntent getBroadcast(android.content.Context c, int request, android.content.Intent i, int flags) { return new PendingIntent(i); }
    public static PendingIntent getService(android.content.Context c, int request, android.content.Intent i, int flags) { return new PendingIntent(i); }
    public android.content.IntentSender getIntentSender() { return new android.content.IntentSender(intent); }
    public void cancel() {}
    public void send() {}
}
