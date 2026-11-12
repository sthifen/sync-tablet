package android.text.method;

public class LinkMovementMethod extends ArrowKeyMovementMethod {
    private static final LinkMovementMethod INSTANCE = new LinkMovementMethod();
    public static MovementMethod getInstance() { return INSTANCE; }
}
