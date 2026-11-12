package android.os;

public class ResultReceiver {
    public ResultReceiver(Handler h) {}
    protected void onReceiveResult(int code, Bundle data) {}
    public void send(int code, Bundle data) { onReceiveResult(code, data); }
}
