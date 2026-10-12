package com.gozar.vpn;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends Activity {
    public static final int VPN_REQUEST = 7;
    private WebView web;
    private static MainActivity instance;
    private volatile String pendingConfigPath;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        instance = this;
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true); // ذخیرهٔ کانفیگ‌های پنل (localStorage)

        web.setBackgroundColor(0xFF0A0F0D);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "GozarAndroid");
        web.loadUrl("file:///android_asset/index.html");

        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
    }

    public static MainActivity getInstance() { return instance; }

    /** پل جاوااسکریپت ↔ جاوا — پنل با window.GozarAndroid صدایش می‌زند */
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
                    // دیالوگ مجوز باید روی UI Thread باز شود
                    runOnUiThread(() -> startActivityForResult(prep, VPN_REQUEST));
                    return "ask";
                }
                runOnUiThread(MainActivity.this::startVpnService);
                return "starting";
            } catch (Exception e) {
                return "error";
            }
        }

        @JavascriptInterface
        public void stopVpn() {
            Intent i = new Intent(MainActivity.this, GozarVpnService.class);
            i.setAction(GozarVpnService.ACTION_STOP);
            startService(i);
        }

        @JavascriptInterface
        public String vpnStatus() {
            return GozarVpnService.currentState;
        }
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
            if (res == RESULT_OK) startVpnService(); // مجوز داده شد → تونل
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