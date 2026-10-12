package com.gozar.vpn;

/** پل TUN→SOCKS5 با hev-socks5-tunnel (کتابخانهٔ بومی، بیلدشده در Actions) */
public final class TunBridge {
    static { System.loadLibrary("gozar-tun"); }

    /** شروع پل: configPath = مسیر فایل yaml، fd = فایل‌دیسکریپتور TUN */
    public static native int start(String configPath, int fd);

    /** توقف نرم پل */
    public static native void stop();
}