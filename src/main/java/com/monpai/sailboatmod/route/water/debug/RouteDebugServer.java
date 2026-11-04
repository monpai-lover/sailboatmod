package com.monpai.sailboatmod.route.water.debug;

import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 水路寻路调试·进程内独立 HTTP 服务（与 marketweb 完全无关，自己的端口，默认关，{@code /sailboat routedebug} 命令手动启停）。
 *
 * <p>默认仅绑 127.0.0.1、手动启动；显式 public 模式绑 0.0.0.0 并要求每次启动生成的访问令牌。
 * 寻路直接走 {@code route.water.*}
 * 正式链路（与游戏实际使用的算法一致），出图复用 {@code roadplanner.map} 着色 + Java2D。
 */
public final class RouteDebugServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ACCESS_COOKIE = "route_debug_session";
    private static volatile RouteDebugServer INSTANCE;

    private final MinecraftServer minecraftServer;
    private final String accessToken;
    private int port;
    private HttpServer httpServer;
    private ExecutorService executor;

    private RouteDebugServer(MinecraftServer minecraftServer, int port, boolean publicAccess) {
        this.minecraftServer = minecraftServer;
        this.port = port;
        if (publicAccess) {
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            this.accessToken = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        } else {
            this.accessToken = null;
        }
    }

    public static synchronized void start(MinecraftServer server, String host, int port) throws IOException {
        stop();
        InetSocketAddress address = new InetSocketAddress(host, port);
        RouteDebugServer s = new RouteDebugServer(server, port,
                address.getAddress() == null || !address.getAddress().isLoopbackAddress());
        try {
            s.startInternal(address);
            INSTANCE = s;
        } catch (IOException | RuntimeException failed) {
            s.stopInternal();
            throw failed;
        }
    }

    public static synchronized void stop() {
        if (INSTANCE != null) {
            INSTANCE.stopInternal();
            INSTANCE = null;
        }
    }

    public static RouteDebugServer get() {
        return INSTANCE;
    }

    public boolean isRunning() {
        return httpServer != null;
    }

    public int port() {
        return port;
    }

    public boolean requiresToken() {
        return accessToken != null;
    }

    /** Operator-only entry link. Never log or broadcast this value; a forwarded link retains its query. */
    public String accessUrl() {
        return "http://127.0.0.1:" + port + "/" + (accessToken == null ? "" : "?token=" + accessToken);
    }

    MinecraftServer server() {
        return minecraftServer;
    }

    private void startInternal(InetSocketAddress address) throws IOException {
        httpServer = HttpServer.create(address, 0);
        port = httpServer.getAddress().getPort();
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "RouteDebug-Http");
            t.setDaemon(true);
            return t;
        });
        httpServer.setExecutor(executor);
        ctx("/ping", ex -> writeText(ex, 200, "route-debug ok"));
        ctx("/api/docks", ex -> {
            var docks = callOnServerThread(() -> RouteDebugService.listDocks(minecraftServer));
            writeJson(ex, 200, RouteDebugService.docksJson(docks));
        });
        ctx("/api/route", ex -> {
            String q = ex.getRequestURI().getRawQuery();
            int ai = paramInt(q, "a", -1);
            int bi = paramInt(q, "b", -1);
            var docks = callOnServerThread(() -> RouteDebugService.listDocks(minecraftServer));
            if (ai < 0 || bi < 0 || ai >= docks.size() || bi >= docks.size()) {
                writeText(ex, 400, "bad a/b index (a=" + ai + " b=" + bi + " n=" + docks.size() + ")");
                return;
            }
            com.monpai.sailboatmod.route.water.WaterMidMode mode =
                    com.monpai.sailboatmod.route.water.WaterMidMode.parse(paramString(q, "mode", "nbt"));
            if (mode == null) {
                mode = com.monpai.sailboatmod.route.water.WaterMidMode.NBT;
            }
            String json = RouteDebugService.renderBundle(minecraftServer, docks.get(ai), docks.get(bi), mode);
            writeJson(ex, 200, json);
        });
        ctx("/", ex -> writeHtml(ex, RouteDebugHtml.PAGE));
        httpServer.start();
        LOGGER.info("[RouteDebug] debug server started on {}:{} (token required: {})",
                address.getHostString(), port, requiresToken());
    }

    private void stopInternal() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        LOGGER.info("[RouteDebug] 调试服务已停止");
    }

    private void ctx(String path, HttpHandler handler) {
        httpServer.createContext(path, ex -> {
            try {
                ex.getResponseHeaders().set("Cache-Control", "no-store");
                ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
                ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                ex.getResponseHeaders().set("X-Frame-Options", "DENY");
                if (!authorized(ex)) {
                    writeText(ex, 401, "Access denied. Open the complete access link shown by /sailboat routedebug start or status.");
                    return;
                }
                handler.handle(ex);
            } catch (Exception e) {
                // Queries may contain the private entry token. Log only the path.
                LOGGER.error("[RouteDebug] 请求失败 {}", ex.getRequestURI().getPath(), e);
                try {
                    writeText(ex, 500, "internal_error: " + e);
                } catch (IOException ignored) {
                }
            } finally {
                ex.close();
            }
        });
    }

    private boolean authorized(HttpExchange ex) {
        if (accessToken == null) return true;
        String supplied = paramString(ex.getRequestURI().getRawQuery(), "token", null);
        if (matchesToken(supplied)) {
            // Host-only, session-scoped cookie lets the existing page fetch its API without exposing the token to JS.
            ex.getResponseHeaders().set("Set-Cookie", ACCESS_COOKIE + "=" + accessToken + "; Path=/; HttpOnly; SameSite=Strict");
            return true;
        }
        for (String header : ex.getRequestHeaders().getOrDefault("Cookie", java.util.List.of())) {
            for (String cookie : header.split(";")) {
                String[] pair = cookie.trim().split("=", 2);
                if (pair.length == 2 && ACCESS_COOKIE.equals(pair[0]) && matchesToken(pair[1])) return true;
            }
        }
        return false;
    }

    private boolean matchesToken(String supplied) {
        return supplied != null && MessageDigest.isEqual(accessToken.getBytes(StandardCharsets.US_ASCII),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    static void writeText(HttpExchange ex, int status, String body) throws IOException {
        write(ex, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    static void writeJson(HttpExchange ex, int status, String body) throws IOException {
        write(ex, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    static void writeHtml(HttpExchange ex, String body) throws IOException {
        write(ex, 200, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    static void writePng(HttpExchange ex, byte[] png) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        write(ex, 200, "image/png", png);
    }

    private static void write(HttpExchange ex, int status, String contentType, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static int paramInt(String rawQuery, String key, int def) {
        if (rawQuery == null) {
            return def;
        }
        for (String kv : rawQuery.split("&")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2 && p[0].equals(key)) {
                try {
                    return Integer.parseInt(p[1]);
                } catch (NumberFormatException e) {
                    return def;
                }
            }
        }
        return def;
    }

    static String paramString(String rawQuery, String key, String def) {
        if (rawQuery == null) {
            return def;
        }
        for (String kv : rawQuery.split("&")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2 && p[0].equals(key)) {
                return p[1];
            }
        }
        return def;
    }

    <T> T callOnServerThread(Supplier<T> action) {
        if (minecraftServer == null || action == null) {
            return null;
        }
        if (minecraftServer.isSameThread()) {
            return action.get();
        }
        return minecraftServer.submit(action::get).join();
    }
}
