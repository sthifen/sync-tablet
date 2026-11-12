package android.net;

public class ConnectivityManager {
    public Network getActiveNetwork() { return new Network(); }
    public NetworkCapabilities getNetworkCapabilities(Network n) { return new NetworkCapabilities(); }
    public boolean bindProcessToNetwork(Network n) { return true; }
    public static class NetworkCallback { public void onAvailable(Network n) {} public void onLost(Network n) {} }
    public void registerDefaultNetworkCallback(NetworkCallback cb) {}
    public void unregisterNetworkCallback(NetworkCallback cb) {}
}
