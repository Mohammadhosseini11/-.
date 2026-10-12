package com.gozar.vpn;

/** پل TUN→SOCKS5 با hev — لود کتابخانه lazy و بدون کرش */
public final class TunBridge {
    private static boolean tried = false;
    private static boolean ok = false;
    private static String err = null;

    private static synchronized boolean ensure() {
        if (!tried) {
            tried = true;
            try {
                System.loadLibrary("gozar-tun");
                ok = true;
            } catch (Throwable t) {
                ok = false;
                err = String.valueOf(t);
            }
        }
        return ok;
    }

    /** وضعیت برای لاگ: آیا libgozar-tun.so لود شد؟ */
    public static String status() {
        ensure();
        return ok ? "native libgozar-tun.so OK" : "native lib FAILED: " + err;
    }

    /** -100 = کتابخانه نیتیو موجود نیست */
    public static int start(String cfg, int fd) {
        if (!ensure()) return -100;
        return nativeStart(cfg, fd);
    }

    public static void stop() {
        if (ok) {
            try { nativeStop(); } catch (Throwable ignored) {}
        }
    }

    private static native int nativeStart(String configPath, int fd);
    private static native void nativeStop();
}
