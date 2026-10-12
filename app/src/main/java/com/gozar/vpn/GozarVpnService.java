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
import org.json.JSONArray;
import org.json.JSONObject;

public class GozarVpnService extends VpnService {

    public static final String ACTION_STOP = "com.gozar.vpn.STOP";
    public static volatile String currentState = "off";

    private static final int NOTIF_ID = 9;
    private static final String CHANNEL_ID = "gozar_vpn";
    private static GozarVpnService inst;
    private ParcelFileDescriptor tun;

    @Override public void onCreate() { super.onCreate(); inst = this; }
    @Override public void onDestroy() { inst = null; super.onDestroy(); }

    /** برای CoreBridge: سوکت‌های هسته نباید از خودِ تونل عبور کنند */
    public static boolean protectFd(int fd) {
        return inst != null && inst.protect(fd);
    }

    /** خطای مرگبار هسته → بستن تونل */
    public static void coreFatal() {
        GozarVpnService s = inst;
        if (s != null) s.stopAll();
    }

    /** تنظیمات VPN از پنل (gozar-vpn.json) */
    private JSONObject readVpnParams() {
        JSONObject v = new JSONObject();
        try {
            File vf = new File(getFilesDir(), "gozar-vpn.json");
            if (vf.exists()) {
                BufferedReader r = new BufferedReader(new FileReader(vf));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                v = new JSONObject(sb.toString());
            }
        } catch (Throwable t) {
            android.util.Log.w("GozarCore", "vpn params: " + t);
        }
        return v;
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

        JSONObject v = readVpnParams();

        int mtu = v.optInt("mtu", 8500);
        if (mtu < 1280 || mtu > 9000) mtu = 8500;
        boolean ipv6 = v.optBoolean("ipv6", false);
        String dns = v.optString("dns", "26.26.26.2");

        try {
            VpnService.Builder b = new Builder()
                    .setSession("گذر")
                    .setMtu(mtu)
                    .addAddress("26.26.26.1", 24)
                    .addDnsServer(dns)
                    .addRoute("0.0.0.0", 0);
            if (ipv6) {
                try {
                    b.addAddress("fdfe:dcba:9876::1", 126);
                    b.addRoute("::", 0);
                } catch (Throwable ignored) {}
            }

            /* Per-app proxy: allow / disallow */
            try {
                JSONObject apps = v.optJSONObject("apps");
                String mode = apps == null ? "none" : apps.optString("mode", "none");
                JSONArray list = apps == null ? null : apps.optJSONArray("list");
                if (("allow".equals(mode) || "disallow".equals(mode)) && list != null && list.length() > 0) {
                    for (int i = 0; i < list.length(); i++) {
                        try {
                            String pkg = list.getString(i);
                            if ("allow".equals(mode)) b.addAllowedApplication(pkg);
                            else b.addDisallowedApplication(pkg);
                        } catch (Throwable t) {
                            android.util.Log.w("GozarCore", "per-app skip " + t);
                        }
                    }
                }
            } catch (Throwable ignored) {}

            tun = b.establish();
            if (tun == null) { stopAll(); return START_NOT_STICKY; }

            CoreBridge.setApkPath(getApplicationInfo().sourceDir);
            CoreBridge.start(readFile(path), tun.getFd(),
                    getFilesDir().getAbsolutePath(),
                    getApplicationInfo().nativeLibraryDir);
            currentState = "on";
        } catch (Throwable t) {
            android.util.Log.e("GozarCore", "service start failed", t);
            try { CoreBridge.log("JAVA CRASH in service: " + t); } catch (Throwable ignored) {}
            stopAll();
        }
        return START_STICKY;
    }

    @Override public void onRevoke() { stopAll(); }

    void stopAll() {
        currentState = "off";
        try { CoreBridge.stop(); } catch (Throwable ignored) {}
        try { TunBridge.stop(); } catch (Throwable ignored) {}
        if (tun != null) {
            try { tun.close(); } catch (Throwable ignored) {}
            tun = null;
        }
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
