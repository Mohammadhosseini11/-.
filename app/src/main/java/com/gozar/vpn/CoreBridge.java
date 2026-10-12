package com.gozar.vpn;

import android.util.Log;
import dalvik.system.DexFile;
import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Enumeration;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * پل بازتابی به هستهٔ Xray:
 *  - InitV2Env (محیط فایل‌های geo)
 *  - اجرای هسته با JSON
 *  - واسط پشتیبانی (protect / onFatalError) با پراکسی داینامیک
 *  - پل TUN با TunBridge (hev) و فالبک به پل بومی AAR
 *  - حذف خودکار قواعد geosite/geoip اگر فایل dat موجود نباشد
 *  - بافر لاگ حلقه‌ای برای نمایش داخل اپ
 */
public final class CoreBridge {
    private static final String TAG = "GozarCore";
    private static final StringBuilder ring = new StringBuilder();
    private static int ringCount = 0;

    private static Object point;                       // v2ray.V2RayPoint instance
    private static Method startMethod, stopMethod;     // runV2Ray / stopV2Ray
    private static Method loopStart, loopStop;         // پل بومی AAR (در صورت وجود)
    private static Method initEnv;                     // InitV2Env
    private static boolean running = false;
    private static String  sApkPath = null;

    private CoreBridge() {}

    public static void setApkPath(String p) { sApkPath = p; }

    private static void log(String m) {
        Log.i(TAG, m);
        synchronized (ring) {
            if (ringCount < 200) { ring.append(m).append('\n'); ringCount++; }
        }
    }
    public static String getLog() { synchronized (ring) { return ring.toString(); } }

    /* ═══════════════ شروع تونل ═══════════════ */

    public static void start(String configJson, int tunFd, String filesDir) throws Exception {
        if (running) stop();
        discover();

        /* ۱) محیط فایل‌های geo (در صورت وجود در AAR) */
        if (initEnv != null) {
            try {
                Class<?>[] ps = initEnv.getParameterTypes();
                Object[] a = new Object[ps.length];
                for (int i = 0; i < ps.length; i++) a[i] = filesDir;
                initEnv.invoke(null, a);
                log("InitV2Env ok: " + n(initEnv));
            } catch (Throwable t) { log("InitV2Env failed: " + t); }
        } else {
            log("InitV2Env not found in AAR (ok if dat files absent)");
        }

        /* ۲) اگر dat نداریم، قواعد geosite/geoip را بردار تا هسته حتماً استارت شود */
        boolean geoOk = new File(filesDir, "geoip.dat").exists()
                     && new File(filesDir, "geosite.dat").exists();
        if (!geoOk) configJson = sanitize(configJson);
        else log("geo dat files present");

        /* ۳) ثبت واسط پشتیبانی (protect / onFatalError / emitStatus) */
        try {
            Method setter = findSupportSetter(point.getClass());
            if (setter != null) {
                Class<?> itf = setter.getParameterTypes()[0];
                Object proxy = Proxy.newProxyInstance(itf.getClassLoader(),
                        new Class<?>[]{itf}, supportHandler());
                setter.invoke(point, proxy);
                log("support bound: " + setter.getName() + " (" + itf.getName() + ")");
            } else {
                log("support setter NOT found (protect may fail)");
            }
        } catch (Throwable t) { log("support bind failed: " + t); }

        /* ۴) اجرای هسته */
        if (startMethod == null) throw new Exception("core start method not found in AAR");
        try {
            startMethod.invoke(point, configJson);
            log("core started: " + n(startMethod));
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable c = ite.getCause() != null ? ite.getCause() : ite;
            throw new Exception("core start failed: " + c);
        }

        /* ۵) پل TUN: اول hev؛ اگر نبود، پل بومی AAR */
        String yaml = filesDir + "/hev.yaml";
        try { writeYaml(yaml); } catch (Throwable t) { throw new Exception("write hev.yaml: " + t); }
        int r = -1;
        try { r = TunBridge.start(yaml, tunFd); } catch (Throwable t) { log("hev load/start: " + t); }
        if (r == 0) {
            log("hev TUN bridge started (fd=" + tunFd + ")");
        } else if (loopStart != null) {
            log("hev unavailable (rc=" + r + ") — trying AAR loop: " + n(loopStart));
            Object[] a = loopArgs(loopStart, tunFd);
            if (!invokeLoop(loopStart, a)) log("ERROR: AAR loop invoke failed — no TUN bridge!");
            else log("AAR loop started");
        } else {
            log("ERROR: no TUN bridge available (hev rc=" + r + ", AAR loop=null)");
        }
        running = true;
    }

    /* ═══════════════ توقف ═══════════════ */

    public static void stop() {
        if (!running) return;
        try { TunBridge.stop(); } catch (Throwable ignored) {}
        if (loopStop != null) {
            try {
                Class<?>[] ps = loopStop.getParameterTypes();
                Object[] a = new Object[ps.length];
                for (int i = 0; i < ps.length; i++) a[i] = defVal(ps[i]);
                if (Modifier.isStatic(loopStop.getModifiers())) loopStop.invoke(null, a);
                else if (point != null) loopStop.invoke(point, a);
            } catch (Throwable ignored) {}
        }
        if (stopMethod != null && point != null) {
            try { stopMethod.invoke(point); } catch (Throwable ignored) {}
        }
        running = false;
        log("core stopped");
    }

    /* ═══════════════ کشف API ═══════════════ */

    private static void discover() throws Exception {
        if (point != null && startMethod != null) return;

        Class<?> pc = classFor("v2ray.V2RayPoint");
        if (pc == null) throw new Exception("v2ray.V2RayPoint not found in AAR");
        point = staticInstance(pc);
        if (point == null) throw new Exception("V2RayPoint instance not found");

        for (Method m : pc.getMethods()) {
            String n = m.getName().toLowerCase();
            Class<?>[] ps = m.getParameterTypes();
            if (startMethod == null && ps.length == 1 && ps[0] == String.class
                    && (n.contains("run") || n.contains("start"))) startMethod = m;
            if (stopMethod == null && ps.length == 0 && n.contains("stop")
                    && !n.contains("loop")) stopMethod = m;
        }

        /* کلاس‌های شناخته‌شده */
        scanClass(classFor("v2ray.Libv2ray"));
        scanClass(classFor("v2ray.Tun2Socks"));

        /* جستجوی سراسری در همهٔ کلاس‌های v2ray.* برای initv2env/startloop */
        if (sApkPath != null) {
            try {
                DexFile dex = new DexFile(sApkPath);
                Enumeration<String> en = dex.entries();
                int scanned = 0;
                while (en.hasMoreElements() && scanned < 500) {
                    String cn = en.nextElement();
                    if (!cn.startsWith("v2ray.") || cn.contains("$")) continue;
                    scanned++;
                    try {
                        scanClass(Class.forName(cn, false, CoreBridge.class.getClassLoader()));
                    } catch (Throwable ignored) {}
                }
                log("dex scan: " + scanned + " v2ray.* classes");
            } catch (Throwable t) { log("dex scan skipped: " + t); }
        } else {
            log("apk path not set — dex scan skipped");
        }

        log("discovered: start=" + n(startMethod) + " stop=" + n(stopMethod)
                + " initEnv=" + n(initEnv)
                + " loop=" + (loopStart != null ? n(loopStart) : "null"));

        if (startMethod == null) throw new Exception("no run/start(String) method on V2RayPoint");
    }

    private static void scanClass(Class<?> c) {
        if (c == null) return;
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                String nn = m.getName().toLowerCase();
                Class<?>[] ps = m.getParameterTypes();
                boolean strFirst = ps.length >= 1 && ps[0] == String.class;
                boolean fdFirst  = ps.length >= 1 && (ps[0] == int.class || ps[0] == long.class);
                if (initEnv == null && strFirst && nn.contains("initv2env")) initEnv = m;
                if (loopStart == null && fdFirst
                        && (nn.contains("startloop") || nn.contains("tun2socks"))) loopStart = m;
                if (loopStop == null && ps.length == 0 && nn.contains("stoploop")) loopStop = m;
            }
        } catch (Throwable ignored) {}
    }

    private static Object staticInstance(Class<?> pc) throws Exception {
        for (Field f : pc.getFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            String n = f.getName().toLowerCase();
            if (n.contains("instance") || n.equals("default")) return f.get(null);
        }
        for (Field f : pc.getFields())
            if (Modifier.isStatic(f.getModifiers())) return f.get(null);
        for (Method m : pc.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getParameterTypes().length == 0
                    && m.getReturnType() == pc && m.getName().toLowerCase().contains("instance")) {
                return m.invoke(null);
            }
        }
        return null;
    }

    private static Class<?> classFor(String name) {
        try { return Class.forName(name); } catch (Throwable t) { return null; }
    }
    private static String n(Method m) {
        return m == null ? "null" : m.getDeclaringClass().getSimpleName() + "." + m.getName();
    }

    /* ═══════════════ پراکسی پشتیبانی ═══════════════ */

    private static Method findSupportSetter(Class<?> pc) {
        for (Method m : pc.getMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1 || !ps[0].isInterface()) continue;
            if (ps[0].getName().startsWith("java.")) continue;
            String nn = m.getName().toLowerCase();
            if (nn.contains("support") || nn.contains("vpn") || nn.contains("callback")) return m;
        }
        return null;
    }

    private static InvocationHandler supportHandler() {
        return (proxy, method, args) -> {
            String nn = method.getName().toLowerCase();
            if (nn.equals("hashcode")) return System.identityHashCode(proxy);
            if (nn.equals("equals"))   return proxy == args[0];
            if (nn.equals("tostring")) return "GozarSupportProxy";
            try {
                if (nn.contains("protect")) {
                    Object a = (args != null && args.length > 0) ? args[0] : null;
                    long fd = (a instanceof Number) ? ((Number) a).longValue() : -1L;
                    boolean ok = GozarVpnService.protectFd((int) fd);
                    if (!ok) log("protect(fd=" + fd + ") FAILED");
                    return retFor(method, ok ? 0L : -1L);
                }
                if (nn.contains("fatal")) {
                    log("FATAL from core: " + (args != null && args.length > 0 ? String.valueOf(args[0]) : "?"));
                    GozarVpnService.coreFatal();
                    return retFor(method, 0L);
                }
                if (nn.contains("emit") || nn.contains("status") || nn.contains("log")) {
                    if (args != null && args.length >= 2 && args[1] != null) log("core: " + args[1]);
                    else if (args != null && args.length >= 1 && args[0] != null) log("core: " + args[0]);
                    return retFor(method, 0L);
                }
            } catch (Throwable th) { log("support cb: " + th); }
            return retFor(method, 0L);
        };
    }

    private static Object retFor(Method m, long v) {
        Class<?> r = m.getReturnType();
        if (r == void.class) return null;
        if (r == boolean.class) return Boolean.TRUE;
        if (r == long.class || r == Long.class) return v;
        if (r == int.class || r == Integer.class) return (int) v;
        if (r == short.class) return (short) v;
        return null;
    }

    private static Object defVal(Class<?> p) {
        if (p == boolean.class) return Boolean.FALSE;
        if (p == long.class) return 0L;
        if (p == int.class) return 0;
        if (p == short.class) return (short) 0;
        if (p == double.class) return 0d;
        if (p == float.class) return 0f;
        return null;
    }

    /* ═══════════════ فالبک پل AAR ═══════════════ */

    private static Object[] loopArgs(Method m, int tunFd) {
        Class<?>[] ps = m.getParameterTypes();
        Object[] a = new Object[ps.length];
        int numeric = 0;
        for (int i = 0; i < ps.length; i++) {
            Class<?> p = ps[i];
            if (p == long.class)  a[i] = (numeric++ == 0) ? (long) tunFd : 0L;
            else if (p == int.class) a[i] = (numeric++ == 0) ? tunFd : 8500;
            else if (p == String.class) a[i] = "socks5://127.0.0.1:10808";
            else if (p == boolean.class) a[i] = Boolean.FALSE;
            else if (p == short.class) a[i] = (short) 0;
            else if (p == double.class) a[i] = 0d;
            else if (p == float.class) a[i] = 0f;
            else a[i] = null;
        }
        return a;
    }

    private static boolean invokeLoop(Method m, Object[] args) {
        try {
            if (Modifier.isStatic(m.getModifiers())) { m.invoke(null, args); return true; }
        } catch (Throwable t) { log("loop static: " + t); }
        try { m.invoke(point, args); return true; } catch (Throwable t) { log("loop point: " + t); }
        return false;
    }

    /* ═══════════════ کمکی ═══════════════ */

    private static void writeYaml(String path) throws Exception {
        String yaml = "tunnel:\n"
                    + "  mtu: 8500\n"
                    + "  ipv4: 26.26.26.1\n"
                    + "  ipv6: 'fdfe:dcba:9876::1'\n"
                    + "socks5:\n"
                    + "  address: 127.0.0.1\n"
                    + "  port: 10808\n"
                    + "  udp: 'udp'\n"
                    + "misc:\n"
                    + "  log-level: warn\n";
        FileWriter w = new FileWriter(path);
        w.write(yaml);
        w.close();
    }

    /** حذف قواعد دارای geosite:/geoip: تا هسته بدون فایل dat استارت شود */
    private static String sanitize(String json) {
        try {
            JSONObject cfg = new JSONObject(json);
            JSONObject routing = cfg.optJSONObject("routing");
            if (routing != null) {
                JSONArray rules = routing.optJSONArray("rules");
                if (rules != null) {
                    JSONArray keep = new JSONArray();
                    for (int i = 0; i < rules.length(); i++) {
                        JSONObject r = rules.optJSONObject(i);
                        if (r == null) continue;
                        boolean usesGeo = false;
                        JSONArray dom = r.optJSONArray("domain");
                        if (dom != null) for (int j = 0; j < dom.length(); j++)
                            if (String.valueOf(dom.opt(j)).startsWith("geosite:")) usesGeo = true;
                        JSONArray ip = r.optJSONArray("ip");
                        if (ip != null) for (int j = 0; j < ip.length(); j++)
                            if (String.valueOf(ip.opt(j)).startsWith("geoip:")) usesGeo = true;
                        if (!usesGeo) keep.put(r);
                    }
                    routing.put("rules", keep);
                }
            }
            log("config sanitized (geo rules removed)");
            return cfg.toString();
        } catch (Throwable t) {
            log("sanitize failed: " + t);
            return json;
        }
    }
}
