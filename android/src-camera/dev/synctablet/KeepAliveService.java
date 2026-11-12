package dev.synctablet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Mientras la tablet mantenga vivo algún cuaderno compartido, la app sigue en
 * marcha con la pantalla apagada o cerrada (Android mata las apps en segundo
 * plano). Sólo existe en Android (src-camera/); lo arranca SharedNode.
 */
public final class KeepAliveService extends Service {
    private static final String CHANNEL = "compartidos";

    /** Arranca o para el servicio según haya algo que mantener vivo. */
    public static void update(Context c) {
        SharedNode n = SharedNode.peek();
        Intent i = new Intent(c, KeepAliveService.class);
        if (n != null && n.anyKeep()) {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } else {
            c.stopService(i);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Cuadernos compartidos", NotificationManager.IMPORTANCE_LOW));
        }
        SharedNode node = SharedNode.get(this);
        int kept = node == null ? 0 : node.keptCount();
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("Sync Tablet mantiene vivos tus compartidos")
                .setContentText(kept == 1 ? "1 cuaderno: los demás pueden seguir trabajando" : kept + " cuadernos: los demás pueden seguir trabajando")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
        if (kept == 0) stopSelf();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
