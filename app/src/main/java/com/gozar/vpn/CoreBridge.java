package com.gozar.vpn;

import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

/**
 * پل بازتابی (Reflection) به هستهٔ Xray.
 *
 * مزیت: در زمان کامپایل به هیچ کلاس/متدی از AAR وابسته نیست؛
 * هر نسخه‌ای از libv2ray.aar بیلد می‌شود و در زمان اجرا خودش
 * API واقعی (Instance، runV2Ray، stopV2Ray، واسط Support، loop) را کشف می‌کند.
 */
public final class CoreBridge {
    private static final String TAG = "GozarCore";

    private static Object point;        // v2ray.V2RayPoint.Instance
    private static Method startMethod;  // runV2Ray(String)
    private static Method stopMethod;   // stopV2Ray()
    private static Method loopStart;    // tun2socks start (اختیاری)
    private static Method loopStop;     // tun2socks stop (اختیاری)
    private static boolean running = false;

    private CoreBridge() {}

    /* ─────────────── شروع تونل ─────────────── */

    public static void start(String configJson, int tunFd) throws Exception {
        if (running) stop();
        discover();

        /* ۱) ثبت واسط پشتیبانی (protect / setup / onFatalError و…) */
        try {
            Method setter = findSupportSetter(point.getClass());
            if (setter != null) {
                Class<?> itf = setter.getParameterTypes()[0];
                Object proxy = Proxy.newProxyInstance(
                        itf.getClassLoader(), new Class<?>[]{itf}, supportHandler());
                setter.invoke(point, proxy);
                Log.i(TAG, "support bound via " + setter.getName() + " (" + itf.getName() + ")");
            } else {
                Log.w(TAG, "support-setter not found on V2RayPoint");
            }
        } catch (Throwable th) {
            Log.w(TAG, "support bind failed: " + th);
        }

        /* ۲) اجرای هسته با کانفیگ */
        if (startMethod == null) throw new Exception("runV2Ray(String) not found in AAR — see Actions 'API dump'");
        try {
            startMethod.invoke(point, configJson);
            Log.i(TAG, "core started via " + startMethod.getName());
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable c = ite.getCause() != null ? ite.getCause() : ite;
            throw new Exception("core start failed: " + c);
        }

        /* ۳) راه‌اندازی پل TUN (در صورت وجود در AAR) */
        if (loopStart != null) {
            Object[] args = loopArgs(loopStart, tunFd);
            if (invokeLoop(loopStart, args)) {
                Log.i(TAG, "tun bridge started via " + loopStart.getDeclaringClass().getSimpleName()
                        + "." + loopStart.getName());
            } else {
                Log.e(TAG, "tun bridge invoke failed — tunnel will not pass traffic");
            }
        } else {
            Log.e(TAG, "tun2socks/loop API NOT found in AAR — check 'API dump' step in Actions log");
        }
        running = true;
    }

    /* ─────────────── توقف تونل ─────────────── */

    public static void stop() {
        if (loopStop != null) {
            try {
                Object[] a = new Object[loopStop.getParameterTypes().length];
                for (int i = 0; i < a.length; i++) a[i] = defaultFor(loopStop.getParameterTypes()[i]);
                if (Modifier.isStatic(loopStop.getModifiers())) loopStop.invoke(null, a);
                else if (point != null) loopStop.invoke(point, a);
            } catch (Throwable ignored) {}
        }
        if (stopMethod != null && point != null) {
            try { stopMethod.invoke(point); } catch (Throwable ignored) {}
        }
        running = false;
        Log.i(TAG, "core stopped");
    }

    /* ─────────────── کشف API ─────────────── */

    private static void discover() throws Exception {
        if (point != null && startMethod != null) return;

        Class<?> pc = classFor("v2ray.V2RayPoint");
        if (pc == null) throw new Exception("v2ray.V2RayPoint not found in AAR");

        /* یافتن فیلد استاتیک Instance */
        Field inst = null;
        for (Field f : pc.getFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            String n = f.getName().toLowerCase();
            if (n.contains("instance") || n.equals("default")) { inst = f; break; }
        }
        if (inst == null) {
            for (Field f : pc.getFields()) {
                if (Modifier.isStatic(f.getModifiers())) { inst = f; break; }
            }
        }
        if (inst == null) throw new Exception("static Instance field not found on V2RayPoint");
        point = inst.get(null);

        /* یافتن start / stop */
        for (Method m : pc.getMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            String n = m.getName().toLowerCase();
            if (ps.length == 1 && ps[0] == String.class
                    && (n.contains("run") || n.contains("start")) && startMethod == null) {
                startMethod = m;
            }
            if (ps.length == 0 && n.contains("stop") && !n.contains("loop") && stopMethod == null) {
                stopMethod = m;
            }
        }

        /* یافتن پل TUN (در صورت وجود) */
        String[] candidates = {"v2ray.Libv2ray", "v2ray.Tun2Socks", "v2ray.GoTun2Socks", "v2ray.Libv2rayLight"};
        for (String cn : candidates) {
            Class<?> c = classFor(cn);
            if (c == null) continue;
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName().toLowerCase();
                Class<?>[] ps = m.getParameterTypes();
                boolean numericFirst = ps.length >= 1
                        && (ps[0] == long.class || ps[0] == int.class || Number.class.isAssignableFrom(ps[0]));
                if (numericFirst && (n.contains("loop") || n.contains("tun2socks")) && n.contains("start")) {
                    loopStart = m;
                }
                if (ps.length == 0 && n.contains("loop") && n.contains("stop")) {
                    loopStop = m;
                }
            }
            if (loopStart != null) break;
        }

        Log.i(TAG, "discovered: start=" + nameOf(startMethod)
                + " stop=" + nameOf(stopMethod)
                + " loop=" + (loopStart != null ? loopStart.toString() : "null"));
    }

    private static Class<?> classFor(String name) {
        try { return Class.forName(name); } catch (Throwable t) { return null; }
    }

    private static String nameOf(Method m) {
        return m == null ? "null" : m.getDeclaringClass().getSimpleName() + "." + m.getName();
    }

    /** متدی که واسط پشتیبانی را می‌پذیرد (مثلاً setVpnSupportsSet) */
    private static Method findSupportSetter(Class<?> pc) {
        for (Method m : pc.getMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1 || !ps[0].isInterface()) continue;
            if (ps[0].getName().startsWith("java.")) continue;
            String n = m.getName().toLowerCase();
            if (n.contains("support") || n.contains("vpn") || n.contains("callback")) return m;
        }
        return null;
    }

    /* ─────────────── پراکسی واسط پشتیبانی ─────────────── */

    private static InvocationHandler supportHandler() {
        return (proxy, method, args) -> {
            String n = method.getName().toLowerCase();
            if (n.equals("hashcode")) return System.identityHashCode(proxy);
            if (n.equals("equals"))   return proxy == args[0];
            if (n.equals("tostring")) return "GozarSupportProxy";
            try {
                if (n.contains("protect")) {
                    Object a = (args != null && args.length > 0) ? args[0] : null;
                    long fd = (a instanceof Number) ? ((Number) a).longValue() : -1L;
                    return retFor(method, GozarVpnService.protectFd((int) fd) ? 0L : -1L);
                }
                if (n.contains("setup"))    return retFor(method, 0L);
                if (n.contains("shutdown")) return retFor(method, 0L);
                if (n.contains("fatal")) {
                    String msg = (args != null && args.length > 0 && args[0] != null)
                            ? String.valueOf(args[0]) : "unknown fatal";
                    Log.e(TAG, "core fatal: " + msg);
                    GozarVpnService.coreFatal();
                    return retFor(method, 0L);
                }
                if (n.contains("emit") || n.contains("status") || n.contains("log")) {
                    if (args != null && args.length >= 2 && args[1] != null) Log.i(TAG, "core: " + args[1]);
                    else if (args != null && args.length >= 1 && args[0] != null) Log.i(TAG, "core: " + args[0]);
                    return retFor(method, 0L);
                }
            } catch (Throwable th) {
                Log.w(TAG, "support callback: " + th);
            }
            return retFor(method, 0L);
        };
    }

    /** مقدار برگشتی متناسب با امضای متد (void / long / int / boolean) */
    private static Object retFor(Method m, long v) {
        Class<?> r = m.getReturnType();
        if (r == void.class) return null;
        if (r == boolean.class) return Boolean.TRUE;
        if (r == long.class || r == Long.class) return v;
        if (r == int.class || r == Integer.class) return (int) v;
        if (r == short.class) return (short) v;
        if (r == double.class) return (double) v;
        if (r == float.class) return (float) v;
        return null;
    }

    /* ─────────────── فراخوانی پل TUN ─────────────── */

    private static Object[] loopArgs(Method m, int tunFd) {
        Class<?>[] ps = m.getParameterTypes();
        Object[] a = new Object[ps.length];
        int numericSeen = 0;
        for (int i = 0; i < ps.length; i++) {
            Class<?> p = ps[i];
            if (p == long.class || p == Long.class) {
                a[i] = (numericSeen++ == 0) ? (long) tunFd : 0L;
            } else if (p == int.class || p == Integer.class) {
                a[i] = (numericSeen++ == 0) ? tunFd : 8500;
            } else if (p == short.class) {
                a[i] = (short) 0;
            } else if (p == double.class) {
                a[i] = 0d;
            } else if (p == float.class) {
                a[i] = 0f;
            } else if (p == boolean.class) {
                a[i] = Boolean.FALSE;
            } else if (p == String.class) {
                a[i] = "socks5://127.0.0.1:10808";
            } else {
                a[i] = null;
            }
        }
        return a;
    }

    private static boolean invokeLoop(Method m, Object[] args) {
        if (Modifier.isStatic(m.getModifiers())) {
            try { m.invoke(null, args); return true; } catch (Throwable t) { Log.w(TAG, "loop static: " + t); }
        }
        try { m.invoke(point, args); return true; } catch (Throwable t) { Log.w(TAG, "loop point: " + t); }
        try {
            Object inst = m.getDeclaringClass().getDeclaredConstructor().newInstance();
            m.invoke(inst, args);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "loop new: " + t);
        }
        return false;
    }

    private static Object defaultFor(Class<?> p) {
        if (p == boolean.class) return Boolean.FALSE;
        if (p == long.class) return 0L;
        if (p == int.class) return 0;
        if (p == short.class) return (short) 0;
        if (p == double.class) return 0d;
        if (p == float.class) return 0f;
        return null;
    }
}
