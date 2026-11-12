package android.content;

public class IntentSender {
    public static class SendIntentException extends Exception {}
    private final Intent intent;
    public IntentSender() { intent = null; }
    public IntentSender(Intent i) { intent = i; }
    /** El intent que se manda cuando termina (para la capa de escritorio). */
    public Intent intent() { return intent; }
}
