package com.gozar.vpn;

/**
 * پل سرویس ↔ هستهٔ Xray (libv2ray.aar از 2dust/AndroidLibXrayLite).
 *
 * ⚠️ تنها نقطهٔ وابسته به نسخهٔ AAR — اگر بیلد اینجا خطای
 * «cannot find symbol» داد، نام متدها را با این مسیر تطبیق دهید:
 *   External Libraries → libv2ray → پکیج v2ray → کلاس‌ها را باز کنید.
 * رایج‌ترین نام‌های جایگزین:
 *   SetVpnSupportsSet → setVpnSupportsSet
 *   RunV2Ray          → runV2Ray
 *   StopV2Ray         → stopV2Ray
 *   StartLoop         → startLoop   (و گاهی روی V2RayPoint است نه Libv2ray)
 *   StopLoop          → stopLoop
 */
public final class CoreBridge {
    private CoreBridge() {}
    private static boolean running = false;

    public static void start(String configJson, int tunFd) throws Exception {
        v2ray.V2RayPoint.Instance.SetVpnSupportsSet(supports);
        v2ray.V2RayPoint.Instance.RunV2Ray(configJson);
        v2ray.Libv2ray.StartLoop((long) tunFd, 8500);
        running = true;
    }

    public static void stop() {
        if (!running) return;
        try { v2ray.Libv2ray.StopLoop(); } catch (Exception ignored) {}
        try { v2ray.V2RayPoint.Instance.StopV2Ray(); } catch (Exception ignored) {}
        running = false;
    }

    private static final v2ray.V2RayVPNServiceSupportsSet supports =
            new v2ray.V2RayVPNServiceSupportsSet() {
                @Override public long Setup() { return 0; }
                @Override public long Shutdown() { return 0; }
                @Override public long Protect(long fd) {
                    return GozarVpnService.protectFd((int) fd) ? 0 : -1;
                }
                @Override public long OnEmitStatus(int status, String msg) { return 0; }
                @Override public long OnFatalError(String msg) {
                    GozarVpnService.coreFatal();
                    return 0;
                }
            };
}