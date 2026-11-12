package android.net;

public class NetworkCapabilities {
    public static final int TRANSPORT_WIFI = 1, TRANSPORT_ETHERNET = 3, TRANSPORT_CELLULAR = 0, NET_CAPABILITY_INTERNET = 12;
    public boolean hasTransport(int t) { return t == TRANSPORT_WIFI || t == TRANSPORT_ETHERNET; }
    public boolean hasCapability(int c) { return true; }
}
