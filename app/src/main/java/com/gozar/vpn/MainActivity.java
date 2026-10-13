package com.gozar.vpn;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.json.JSONObject;

public class MainActivity extends Activity {
    public static final int VPN_REQUEST = 7;
    private static final int FILE_CHOOSER = 11;
    private WebView web;
    private static MainActivity instance;
    private volatile String pendingConfigPath;
    private ValueCallback<Uri[]> filePathCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        instance = this;
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);

        web.setBackgroundColor(0xFF0A0F0D);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try { startActivityForResult(p.createIntent(), FILE_CHOOSER); return true; }
                catch (Exception e) { filePathCallback = null; return false; }
            }
        });
        web.addJavascriptInterface(new Bridge(), "GozarAndroid");
        web.loadUrl("file:///android_asset/index.html");

        copyGeoAssets();

        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
    }

    public static MainActivity getInstance() { return instance; }

    private void copyGeoAssets() {
        try {
            for (String f : new String[]{"geoip.dat", "geosite.dat"}) {
                File out = new File(getFilesDir(), f);
                if (out.exists() && out.length() > 0) continue;
                InputStream in = getAssets().open(f);
                FileOutputStream fo = new FileOutputStream(out);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                fo.close(); in.close();
            }
        } catch (Exception e) {
            android.util.Log.w("GozarCore", "geo assets: " + e);
        }
    }

    class Bridge {
        @JavascriptInterface
        public String startVpn(String configJson) {
            try {
                File f = new File(getFilesDir(), "gozar-core.json");
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(configJson.getBytes("UTF-8"));
                fo.close();
                pendingConfigPath = f.getAbsolutePath();

                final Intent prep = VpnService.prepare(MainActivity.this);
                if (prep != null) {
                    runOnUiThread(() -> startActivityForResult(prep, VPN_REQUEST));
                    return "ask";
                }
                runOnUiThread(MainActivity.this::startVpnService);
                return "starting";
            } catch (Exception e) { return "error"; }
        }

        @JavascriptInterface
        public void setVpnParams(String vpnJson) {
            try {
                File f = new File(getFilesDir(), "gozar-vpn.json");
                FileOutputStream fo = new FileOutputStream(f);
                fo.write((vpnJson == null ? "{}" : vpnJson).getBytes("UTF-8"));
                fo.close();
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public String getApps() {
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
                LinkedHashSet<String> seen = new LinkedHashSet<>();
                ArrayList<String> items = new ArrayList<>();
                for (ResolveInfo ri : list) {
                    String pkg = ri.activityInfo != null ? ri.activityInfo.packageName : null;
                    if (pkg == null || pkg.equals(getPackageName()) || seen.contains(pkg)) continue;
                    seen.add(pkg);
                    String name = String.valueOf(ri.loadLabel(pm));
                    JSONObject o = new JSONObject();
                    o.put("n", name);
                    o.put("p", pkg);
                    items.add(o.toString());
                }
                return "[" + String.join(",", items) + "]";
            } catch (Throwable t) { return "[]"; }
        }

        /** پینگ واقعی TCP — باز بودن درگاه، مستقل از تونل/پروتکل/CORS */
        @JavascriptInterface
        public String tcpPing(final String host, final int port) {
            try {
                final long t0 = System.currentTimeMillis();
                final boolean[] ok = {false};
                Thread th = new Thread(() -> {
                    try (java.net.Socket s = new java.net.Socket()) {
                        s.connect(new java.net.InetSocketAddress(host, port), 5000);
                        ok[0] = true;
                    } catch (Throwable ignored) {}
                });
                th.start();
                th.join(5200);
                return ok[0] ? ("ok," + (System.currentTimeMillis() - t0)) : "fail";
            } catch (Throwable t) {
                return "fail";
            }
        }

        @JavascriptInterface
        public void stopVpn() {
            Intent i = new Intent(MainActivity.this, GozarVpnService.class);
            i.setAction(GozarVpnService.ACTION_STOP);
            startService(i);
        }

        @JavascriptInterface
        public String vpnStatus() { return GozarVpnService.currentState; }

        @JavascriptInterface
        public String coreLog() { return CoreBridge.getLog(); }
    }

    void startVpnService() {
        Intent i = new Intent(this, GozarVpnService.class);
        i.putExtra("config", pendingConfigPath);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == VPN_REQUEST) {
            if (res == RESULT_OK) startVpnService();
        } else if (req == FILE_CHOOSER) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(res, data));
                filePathCallback = null;
            }
        } else super.onActivityResult(req, res, data);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        instance = null;
        super.onDestroy();
    }
}
