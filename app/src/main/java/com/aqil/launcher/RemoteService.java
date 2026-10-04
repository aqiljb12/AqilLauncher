package com.aqil.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Servis latar depan yang menjalankan server remote supaya telefon sentiasa boleh sambung.
 * Satu RemoteServer sahaja bagi setiap proses (berkongsi walaupun servis dicipta semula),
 * jadi membuka launcher berulang kali / START_STICKY tidak pernah membuka port kedua.
 */
public class RemoteService extends Service {
    private static final Object LOCK = new Object();
    private static RemoteServer server;

    static void start(Context c) {
        try {
            Intent i = new Intent(c, RemoteService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
            // cth. Android 12+ tak benarkan mula dari latar: launcher akan cuba lagi bila dibuka
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // startForeground mesti dipanggil dalam 5 saat selepas startForegroundService()
        try {
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null && nm.getNotificationChannel("remote") == null) {
                    nm.createNotificationChannel(new NotificationChannel("remote", "Remote telefon", NotificationManager.IMPORTANCE_MIN));
                }
                b = new Notification.Builder(this, "remote");
            } else {
                b = new Notification.Builder(this);
            }
            b.setSmallIcon(R.drawable.ic_launcher).setContentTitle("Aqil Launcher").setContentText("Remote telefon aktif")
                    .setOngoing(true);
            startForeground(1, b.build());
        } catch (Exception ignored) {
        }
        synchronized (LOCK) {
            if (server == null) {
                server = new RemoteServer(getApplicationContext());
                server.start();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // tutup soket pelayan + semua sambungan + pekerja supaya port bebas bila servis dimulakan semula
        synchronized (LOCK) {
            if (server != null) {
                server.stop();
                server = null;
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
