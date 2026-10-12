package com.gozar.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class GozarVpnService extends VpnService {

    public static final String ACTION_STOP = "com.gozar.vpn.STOP";
    public static volatile String currentState = "off";

    private static final int NOTIF_ID = 9;
    private static final String CHANNEL_ID = "gozar_vpn";
    private static GozarVpnService inst;
    private ParcelFileDescriptor tun;

    @Override
    public void onCreate() { super.onCreate(); inst = this; }

    @Override
    public void onDestroy() { inst = null; super.onDestroy(); }

    /** برای CoreBridge: محافظت سوکت هسته از عبور دوباره از تونل */
    public static boolean protectFd(int fd) {
        return inst != null && inst.protect(fd);
    }

    /** اگر هسته خطای مرگبار داد، تونل را ببند */
    public static void coreFatal() {
        GozarVpnService s = inst;
        if (s != null) s.stopAll();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopAll();
            return START_NOT_STICKY;
        }
        String path = intent != null ? intent.getStringExtra("config") : null;
        if (path == null || !new File(path).exists()) {
            stopAll();
            return START_NOT_STICKY;
        }

        startForeground(NOTIF_ID, buildNotification());

        try {
            VpnService.Builder b = new Builder()
                    .setSession("گذر")
                    .setMtu(8500)
                    .addAddress("26.26.26.1", 24)
                    .addDnsServer("26.26.26.2")
                    .addRoute("0.0.0.0", 0);
            try {
                b.addAddress("fdfe:dcba:9876::1", 126);
                b.addRoute("::", 0);
            } catch (Exception ignored) {}

            tun = b.establish();
            if (tun == null) { stopAll(); return START_NOT_STICKY; }

            CoreBridge.start(readFile(path), tun.getFd());
            currentState = "on";
        } catch (Exception e) {
            stopAll();
        }
        return START_STICKY;
    }

    @Override
    public void onRevoke() { stopAll(); } // کاربر از تنظیمات اندروید قطع کرد

    void stopAll() {
        try { CoreBridge.stop(); } catch (Exception ignored) {}
        if (tun != null) { try { tun.close(); } catch (Exception ignored) {} tun = null; }
        currentState = "off";
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    private static String readFile(String path) throws Exception {
        BufferedReader r = new BufferedReader(new FileReader(path));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        return sb.toString();
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "وضعیت تونل", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, GozarVpnService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return nb.setContentTitle("گذر — تونل فعال")
                .setContentText("ترافیک از هستهٔ Xray عبور می‌کند")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_lock_power_off, "قطع اتصال", stopPi).build())
                .build();
    }
}