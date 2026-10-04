package com.aqil.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** Servis latar depan yang menjalankan server remote supaya telefon sentiasa boleh sambung. */
public class RemoteService extends Service {
    private RemoteServer server;

    static void start(Context c) {
        try {
            Intent i = new Intent(c, RemoteService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("remote", "Remote telefon", NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, "remote");
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.drawable.ic_launcher).setContentTitle("Aqil Launcher").setContentText("Remote telefon aktif");
        startForeground(1, b.build());
        if (server == null) {
            server = new RemoteServer(getApplicationContext());
            server.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
