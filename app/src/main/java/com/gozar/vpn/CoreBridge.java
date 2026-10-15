package com.gozar.vpn;

import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Gozar Core Bridge
 *
 * Xray CoreController handles proxy traffic.
 * HEV bridges Android VPN TUN to the local SOCKS5 inbound.
 */
public final class CoreBridge {

    private static final String TAG = "GozarCore";

    private static final String API_CLASS = "libv2ray.Libv2ray";
    private static final String CALLBACK_CLASS =
            "libv2ray.CoreCallbackHandler";

    private static final StringBuilder ring = new StringBuilder();

    private static int ringCount = 0;
    private static String logPath;
    private static String filesDir;

    private static Object controller;
    private static Object callbackProxy;

    private static Method initCoreEnv;
    private static Method newCoreController;
    private static Method startLoop;
    private static Method stopLoop;

    private static boolean running = false;

    private CoreBridge() {}

    public static void setApkPath(String ignored) {
        // Kept for compatibility with GozarVpnService.
    }

    public static void log(String message) {
        Log.i(TAG, message);

        synchronized (ring) {
            if (ringCount < 400) {
                ring.append(message).append('\n');
                ringCount++;
            }

            if (logPath != null) {
                try (PrintWriter writer =
                             new PrintWriter(new FileWriter(logPath, true))) {
                    writer.println(message);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void tailFile(
            StringBuilder out, File file, int maxLines) {

        if (file == null || !file.isFile()) {
            return;
        }

        try (BufferedReader reader =
                     new BufferedReader(new FileReader(file))) {

            ArrayList<String> lines = new ArrayList<>();
            String line;

            while ((line = reader.readLine()) != null) {
                lines.add(line);

                if (lines.size() > maxLines) {
                    lines.remove(0);
                }
            }

            if (!lines.isEmpty()) {
                out.append("\n--- ")
                        .append(file.getName())
                        .append(" ---\n");

                for (String item : lines) {
                    out.append(item).append('\n');
                }
            }

        } catch (Throwable ignored) {
        }
    }

    public static String getLog() {
        synchronized (ring) {
            StringBuilder out = new StringBuilder(ring);

            if (filesDir != null) {
                tailFile(out,
                        new File(filesDir, "xray-error.log"), 60);

                tailFile(out,
                        new File(filesDir, "xray-access.log"), 25);

                tailFile(out,
                        new File(filesDir, "hev.log"), 40);
            }

            return out.toString();
        }
    }

    private static String readTail(String path, int maxLines) {
        StringBuilder out = new StringBuilder();
        File file = new File(path);

        if (!file.isFile()) {
            return "";
        }

        try (BufferedReader reader =
                     new BufferedReader(new FileReader(file))) {

            ArrayList<String> lines = new ArrayList<>();
            String line;

            while ((line = reader.readLine()) != null) {
                lines.add(line);

                if (lines.size() > maxLines) {
                    lines.remove(0);
                }
            }

            for (String item : lines) {
                out.append(item).append('\n');
            }

        } catch (Throwable ignored) {
        }

        return out.toString();
    }

    private static String ensureFileLogs(
            String json, String directory) {

        try {
            JSONObject config = new JSONObject(json);
            JSONObject logConfig = config.optJSONObject("log");

            if (logConfig == null) {
                logConfig = new JSONObject();
            }

            logConfig.put("loglevel", "warning");
            logConfig.put("error",
                    new File(directory, "xray-error.log")
                            .getAbsolutePath());
            logConfig.put("access",
                    new File(directory, "xray-access.log")
                            .getAbsolutePath());

            config.put("log", logConfig);

            return config.toString();

        } catch (Throwable error) {
            log("Cannot configure Xray logs: " + error);
            return json;
        }
    }

    private static String sanitize(String json) {
        try {
            JSONObject config = new JSONObject(json);
            JSONObject routing = config.optJSONObject("routing");

            if (routing == null) {
                return config.toString();
            }

            JSONArray rules = routing.optJSONArray("rules");

            if (rules == null) {
                return config.toString();
            }

            JSONArray kept = new JSONArray();

            for (int i = 0; i < rules.length(); i++) {
                JSONObject rule = rules.optJSONObject(i);

                if (rule == null) {
                    continue;
                }

                boolean usesGeo = false;

                JSONArray domains = rule.optJSONArray("domain");

                if (domains != null) {
                    for (int j = 0; j < domains.length(); j++) {
                        String value =
                                String.valueOf(domains.opt(j));

                        if (value.startsWith("geosite:")) {
                            usesGeo = true;
                            break;
                        }
                    }
                }

                JSONArray ips = rule.optJSONArray("ip");

                if (ips != null) {
                    for (int j = 0; j < ips.length(); j++) {
                        String value =
                                String.valueOf(ips.opt(j));

                        if (value.startsWith("geoip:")) {
                            usesGeo = true;
                            break;
                        }
                    }
                }

                if (!usesGeo) {
                    kept.put(rule);
                }
            }

            routing.put("rules", kept);

            log("Geo routing rules removed because data files are missing");

            return config.toString();

        } catch (Throwable error) {
            log("Config sanitize failed: " + error);
            return json;
        }
    }

    public static synchronized void start(
            String configJson,
            int tunFd,
            String directory,
            String nativeDir) throws Exception {

        if (running) {
            stop();
        }

        filesDir = directory;
        logPath = new File(directory, "gozar-core.log")
                .getAbsolutePath();

        log("Starting Gozar VPN");
        log("Android ABI: "
                + (Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "unknown"));
        log("TUN fd: " + tunFd);
        log("HEV status: " + TunBridge.status());

        File base = new File(directory);

        boolean hasGeoFiles =
                new File(base, "geoip.dat").isFile()
                && new File(base, "geosite.dat").isFile();

        if (!hasGeoFiles) {
            configJson = sanitize(configJson);
        }

        configJson = ensureFileLogs(configJson, directory);

        boolean coreStarted = false;
        boolean hevStarted = false;

        try {
            discover();

            // Initialize paths before creating the core controller.
            invokeInitCoreEnv(directory);

            if (controller == null) {
                controller = invokeNewCoreController();
            }

            if (controller == null) {
                throw new Exception(
                        "newCoreController returned null");
            }

            // The HEV bridge owns the Android TUN fd.
            // Do not pass that fd to Xray's StartLoop.
            Object result = invokeStartLoop(configJson);

            checkGoError(result, "StartLoop");

            coreStarted = true;
            log("Xray CoreController started");

            String yamlPath =
                    new File(directory, "hev.yaml")
                            .getAbsolutePath();

            writeYaml(yamlPath, directory);

            int rc = TunBridge.start(yamlPath, tunFd);

            if (rc != 0) {
                throw new Exception(
                        "HEV failed to start; return code=" + rc);
            }

            hevStarted = true;
            running = true;

            log("Gozar VPN started successfully");

        } catch (Throwable error) {

            if (hevStarted) {
                try {
                    TunBridge.stop();
                } catch (Throwable ignored) {
                }
            }

            if (coreStarted) {
                try {
                    invokeStopLoop();
                } catch (Throwable ignored) {
                }
            }

            running = false;

            Throwable cause = error;

            if (error instanceof
                    java.lang.reflect.InvocationTargetException) {

                Throwable inner =
                        ((java.lang.reflect.InvocationTargetException)
                                error).getCause();

                if (inner != null) {
                    cause = inner;
                }
            }

            String details = readTail(
                    new File(directory, "xray-error.log")
                            .getAbsolutePath(),
                    20);

            throw new Exception(
                    "VPN start failed: " + cause
                    + (details.isEmpty()
                    ? ""
                    : "\n--- xray-error.log ---\n" + details),
                    cause);
        }
    }

    public static synchronized void stop() {
        try {
            TunBridge.stop();
        } catch (Throwable error) {
            log("HEV stop error: " + error);
        }

        if (controller != null && stopLoop != null) {
            try {
                invokeStopLoop();
            } catch (Throwable error) {
                log("Core stop error: " + error);
            }
        }

        running = false;
        log("Gozar core stopped");
    }

    private static void discover() throws Exception {
        if (initCoreEnv != null
                && newCoreController != null
                && startLoop != null
                && stopLoop != null) {
            return;
        }

        Class<?> api;

        try {
            api = Class.forName(API_CLASS);
        } catch (Throwable error) {
            throw new Exception(
                    "Cannot load " + API_CLASS
                    + ". Check that AndroidLibXrayLite AAR "
                    + "is included in the app.", error);
        }

        Class<?> callbackType;

        try {
            callbackType = Class.forName(CALLBACK_CLASS);
        } catch (Throwable error) {
            throw new Exception(
                    "Cannot load " + CALLBACK_CLASS, error);
        }

        initCoreEnv =
                findMethod(api, "initCoreEnv", 2, true);

        newCoreController =
                findMethod(api, "newCoreController", 1, true);

        if (initCoreEnv == null) {
            throw new Exception(
                    "libv2ray.InitCoreEnv method not found");
        }

        if (newCoreController == null) {
            throw new Exception(
                    "libv2ray.NewCoreController method not found");
        }

        Class<?> controllerType =
                newCoreController.getReturnType();

        startLoop =
                findMethod(controllerType, "startLoop", 2, false);

        stopLoop =
                findMethod(controllerType, "stopLoop", 0, false);

        if (startLoop == null || stopLoop == null) {
            throw new Exception(
                    "CoreController StartLoop/StopLoop methods missing");
        }

        callbackProxy = Proxy.newProxyInstance(
                callbackType.getClassLoader(),
                new Class<?>[]{callbackType},
                new InvocationHandler() {
                    @Override
                    public Object invoke(
                            Object proxy,
                            Method method,
                            Object[] args) {

                        String name = method.getName();

                        if ("toString".equals(name)) {
                            return "GozarCoreCallback";
                        }

                        if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxy);
                        }

                        if ("equals".equals(name)) {
                            return args != null
                                    && args.length > 0
                                    && proxy == args[0];
                        }

                        if ("onEmitStatus".equalsIgnoreCase(name)) {
                            if (args != null && args.length > 1) {
                                log("Xray status: " + args[1]);
                            }
                        } else if ("startup".equalsIgnoreCase(name)) {
                            log("Xray startup callback");
                        } else if ("shutdown".equalsIgnoreCase(name)) {
                            log("Xray shutdown callback");
                        }

                        if (method.getReturnType() == int.class) {
                            return 0;
                        }

                        return null;
                    }
                });

        log("libv2ray API discovered");
        log("Controller class: " + controllerType.getName());
        log("Start method: " + startLoop);
        log("Stop method: " + stopLoop);
    }

    private static Method findMethod(
            Class<?> type,
            String name,
            int parameterCount,
            boolean requireStatic) {

        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name)) {
                continue;
            }

            if (method.getParameterTypes().length
                    != parameterCount) {
                continue;
            }

            boolean isStatic =
                    Modifier.isStatic(method.getModifiers());

            if (isStatic != requireStatic) {
                continue;
            }

            return method;
        }

        return null;
    }

    private static void invokeInitCoreEnv(
            String directory) throws Exception {

        try {
            initCoreEnv.invoke(null, directory, "");
            log("InitCoreEnv completed");

        } catch (Throwable error) {
            throw new Exception(
                    "InitCoreEnv failed: " + unwrap(error),
                    unwrap(error));
        }
    }

    private static Object invokeNewCoreController()
            throws Exception {

        try {
            return newCoreController.invoke(null, callbackProxy);

        } catch (Throwable error) {
            throw new Exception(
                    "NewCoreController failed: " + unwrap(error),
                    unwrap(error));
        }
    }

    private static Object invokeStartLoop(String config)
            throws Exception {

        try {
            Class<?> fdType =
                    startLoop.getParameterTypes()[1];

            Object zeroFd;

            if (fdType == int.class
                    || fdType == Integer.class) {
                zeroFd = 0;
            } else if (fdType == long.class
                    || fdType == Long.class) {
                zeroFd = 0L;
            } else {
                throw new Exception(
                        "Unexpected StartLoop fd parameter: "
                        + fdType.getName());
            }

            return startLoop.invoke(
                    controller, config, zeroFd);

        } catch (Throwable error) {
            throw new Exception(
                    "StartLoop failed: " + unwrap(error),
                    unwrap(error));
        }
    }

    private static void invokeStopLoop() throws Exception {
        stopLoop.invoke(controller);
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof
                java.lang.reflect.InvocationTargetException) {

            Throwable cause =
                    ((java.lang.reflect.InvocationTargetException)
                            error).getCause();

            if (cause != null) {
                return cause;
            }
        }

        return error;
    }

    private static void checkGoError(
            Object result, String operation) throws Exception {

        if (result instanceof String) {
            String message = ((String) result).trim();

            if (!message.isEmpty()) {
                throw new Exception(
                        operation + " returned error: " + message);
            }
        }

        if (result instanceof Throwable) {
            throw new Exception(
                    operation + " failed: " + result);
        }
    }

    private static void writeYaml(
            String path, String directory) throws Exception {

        int mtu = 8500;
        int socksPort = 10808;
        String timeouts = "300,60";

        File paramsFile =
                new File(directory, "gozar-vpn.json");

        if (paramsFile.isFile()) {
            try (BufferedReader reader =
                         new BufferedReader(
                                 new FileReader(paramsFile))) {

                StringBuilder content = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    content.append(line);
                }

                JSONObject params =
                        new JSONObject(content.toString());

                int configuredMtu = params.optInt("mtu", mtu);

                if (configuredMtu >= 1280
                        && configuredMtu <= 9000) {
                    mtu = configuredMtu;
                }

                int configuredPort =
                        params.optInt("socksPort", socksPort);

                if (configuredPort >= 1
                        && configuredPort <= 65535) {
                    socksPort = configuredPort;
                }

                timeouts = params.optString(
                        "hevTimeouts", timeouts);

            } catch (Throwable error) {
                log("HEV parameters ignored: " + error);
            }
        }

        StringBuilder yaml = new StringBuilder();

        yaml.append("tunnel:\n");
        yaml.append("  mtu: ").append(mtu).append('\n');
        yaml.append("  ipv4: 26.26.26.1\n");

        yaml.append("socks5:\n");
        yaml.append("  address: 127.0.0.1\n");
        yaml.append("  port: ").append(socksPort).append('\n');
        yaml.append("  udp: 'udp'\n");

        yaml.append("misc:\n");
        yaml.append("  log-file: ")
                .append(new File(directory, "hev.log")
                        .getAbsolutePath())
                .append('\n');

        yaml.append("  log-level: info\n");

        String[] parts = timeouts.split(",");

        if (parts.length > 0) {
            appendTimeout(yaml, "idle_timeout", parts[0]);
        }

        if (parts.length > 1) {
            appendTimeout(yaml, "udp_timeout", parts[1]);
        }

        try (FileWriter writer = new FileWriter(path)) {
            writer.write(yaml.toString());
        }

        log("HEV YAML created; SOCKS port=" + socksPort);
    }

    private static void appendTimeout(
            StringBuilder yaml, String key, String raw) {

        try {
            int value = Integer.parseInt(raw.trim());

            if (value >= 0 && value <= 86400) {
                yaml.append("  ")
                        .append(key)
                        .append(": ")
                        .append(value)
                        .append('\n');
            }

        } catch (Throwable ignored) {
        }
    }
}
