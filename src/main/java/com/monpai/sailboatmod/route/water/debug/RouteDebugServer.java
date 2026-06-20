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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 水路寻路调试·进程内独立 HTTP 服务（与 marketweb 完全无关，自己的端口，默认关，{@code /sailboat routedebug} 命令手动启停）。
 *
 * <p>仅本机调试用：绑 127.0.0.1，默认不启动，避免生产/正式环境误开端口。寻路直接走 {@code route.water.*}
 * 正式链路（与游戏实际使用的算法一致），出图复用 {@code roadplanner.map} 着色 + Java2D。
 */
public final class RouteDebugServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile RouteDebugServer INSTANCE;

    private final MinecraftServer minecraftServer;
    private final int port;
    private HttpServer httpServer;
    private ExecutorService executor;

    private RouteDebugServer(MinecraftServer minecraftServer, int port) {
        this.minecraftServer = minecraftServer;
        this.port = port;
    }

    public static synchronized void start(MinecraftServer server, String host, int port) throws IOException {
        stop();
        RouteDebugServer s = new RouteDebugServer(server, port);
        s.startInternal(host);
        INSTANCE = s;
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

    MinecraftServer server() {
        return minecraftServer;
    }

    private void startInternal(String host) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
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
            String json = RouteDebugService.renderBundle(minecraftServer, docks.get(ai), docks.get(bi));
            writeJson(ex, 200, json);
        });
        ctx("/", ex -> writeHtml(ex, RouteDebugHtml.PAGE));
        httpServer.start();
        LOGGER.info("[RouteDebug] debug server started on {}:{}", host, port);
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
                handler.handle(ex);
            } catch (Exception e) {
                LOGGER.error("[RouteDebug] 请求失败 {}", ex.getRequestURI(), e);
                try {
                    writeText(ex, 500, "internal_error: " + e);
                } catch (IOException ignored) {
                }
            } finally {
                ex.close();
            }
        });
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
