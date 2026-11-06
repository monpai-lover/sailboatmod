package com.monpai.sailboatmod.route.water.debug;

import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.route.terrain.TellusWorlds;
import com.monpai.sailboatmod.route.water.PortAsyncRuntime;
import com.monpai.sailboatmod.route.water.WaterRouteCancellation;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
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
@EventBusSubscriber(modid = SailboatMod.MODID)
public final class RouteDebugServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ACCESS_COOKIE = "route_debug_session";
    private static volatile RouteDebugServer INSTANCE;

    private final MinecraftServer minecraftServer;
    private final String accessToken;
    private int port;
    private HttpServer httpServer;
    private ExecutorService executor;
    private RouteDebugJobManager jobs;

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
        if (server != null && server.isStopped()) throw new IOException("Server is stopping; start debug in the active world");
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

    @SubscribeEvent
    public static synchronized void onServerStopping(ServerStoppingEvent event) {
        if (INSTANCE != null && INSTANCE.minecraftServer == event.getServer()) stop();
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
        jobs = new RouteDebugJobManager();
        ctx("/ping", ex -> writeText(ex, 200, "route-debug ok"));
        ctx("/api/docks", ex -> {
            var docks = callOnServerThread(() -> RouteDebugService.listDocks(minecraftServer));
            writeJson(ex, 200, RouteDebugService.docksJson(docks));
        });
        ctx("/api/capabilities", ex -> {
            boolean available = tellusAvailable();
            var data = new com.google.gson.JsonObject();
            data.addProperty("tellusAvailable", available);
            data.addProperty("defaultMode", RouteDebugMode.defaultFor(available).id());
            data.addProperty("tellusUnavailableReason", available ? "" : "当前世界不是 Tellus 地球世界，使用 NBT 模式");
            writeJson(ex, 200, data.toString());
        });
        ctx("/api/route/start", ex -> {
            if (!requireMethod(ex, "POST")) return;
            handleRoute(ex, true);
        });
        ctx("/api/route/jobs/", this::handleJob);
        ctx("/api/route", ex -> handleRoute(ex, false));
        ctx("/", ex -> writeHtml(ex, RouteDebugHtml.PAGE));
        httpServer.start();
        LOGGER.info("[RouteDebug] debug server started on {}:{} (token required: {})",
                address.getHostString(), port, requiresToken());
    }

    private void handleRoute(HttpExchange ex, boolean asynchronous) throws IOException {
        String q = ex.getRequestURI().getRawQuery();
        String requested = paramString(q, "mode", null);
        RouteDebugMode mode = requested == null ? null : RouteDebugMode.parse(requested);
        if (requested != null && mode == null) {
            writeRouteError(ex, 400, "UNKNOWN_MODE", "未知调试模式；请选择 TELLUS、NBT、HYBRID 或 NOISE");
            return;
        }
        int ai = paramInt(q, "a", -1);
        int bi = paramInt(q, "b", -1);
        if (asynchronous) {
            startRouteJob(ex, ai, bi, mode);
            return;
        }
        if (mode == null) mode = RouteDebugMode.defaultFor(tellusAvailable());
        if (mode == RouteDebugMode.TELLUS && !tellusAvailable()) {
            writeRouteError(ex, 400, "TELLUS_UNAVAILABLE", "当前世界不是 Tellus 地球世界，无法运行 Tellus 模式");
            return;
        }
        var docks = callOnServerThread(() -> RouteDebugService.listDocks(minecraftServer));
        if (ai < 0 || bi < 0 || ai >= docks.size() || bi >= docks.size()) {
            writeText(ex, 400, "bad a/b index (a=" + ai + " b=" + bi + " n=" + docks.size() + ")");
            return;
        }
        String json = RouteDebugService.renderBundle(minecraftServer, docks.get(ai), docks.get(bi), mode);
        writeJson(ex, 200, json);
    }

    private void startRouteJob(HttpExchange ex, int ai, int bi, RouteDebugMode requestedMode) throws IOException {
        try {
            var job = jobs.submit(cancelled -> WaterRouteCancellation.run(cancelled, () -> {
                try {
                    RouteDebugMode mode = requestedMode == null ? RouteDebugMode.defaultFor(tellusAvailable()) : requestedMode;
                    if (mode == RouteDebugMode.TELLUS && !tellusAvailable())
                        return routeErrorJson("TELLUS_UNAVAILABLE", "当前世界不是 Tellus 地球世界，无法运行 Tellus 模式");
                    var docks = callOnServerThread(() -> RouteDebugService.listDocks(minecraftServer));
                    if (ai < 0 || bi < 0 || ai >= docks.size() || bi >= docks.size())
                        return routeErrorJson("BAD_DOCK_INDEX", "码头编号已失效，请刷新页面后重新选择码头");
                    String json = RouteDebugService.renderBundle(minecraftServer, docks.get(ai), docks.get(bi), mode);
                    // Encoding/invalid result errors belong to the job, never a later polling response.
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                    return json;
                } catch (java.util.concurrent.CancellationException cancelledWork) {
                    throw cancelledWork;
                } catch (RuntimeException | Error failed) {
                    LOGGER.error("[RouteDebug] background route/render failed (mode={})",
                            requestedMode == null ? "default" : requestedMode.id(), failed);
                    throw failed;
                }
            }));
            writeJson(ex, 202, jobJson(job));
        } catch (RouteDebugJobManager.BusyException busy) {
            writeRouteError(ex, 429, "JOB_QUEUE_FULL", busy.getMessage());
        }
    }

    private void handleJob(HttpExchange ex) throws IOException {
        String suffix = ex.getRequestURI().getPath().substring("/api/route/jobs/".length());
        boolean cancel = suffix.endsWith("/cancel");
        if (!requireMethod(ex, cancel ? "POST" : "GET")) return;
        String id = cancel ? suffix.substring(0, suffix.length() - "/cancel".length()) : suffix;
        var job = cancel ? jobs.cancel(id) : jobs.get(id);
        if (job == null) writeRouteError(ex, 404, "JOB_NOT_FOUND", "任务不存在或已过期，请重新生成路线");
        else writeJson(ex, 200, jobJson(job));
    }

    private static boolean requireMethod(HttpExchange ex, String method) throws IOException {
        if (method.equals(ex.getRequestMethod())) return true;
        ex.getResponseHeaders().set("Allow", method);
        writeRouteError(ex, 405, "METHOD_NOT_ALLOWED", "此接口需要 " + method);
        return false;
    }

    private static String jobJson(RouteDebugJobManager.Snapshot job) {
        var json = new com.google.gson.JsonObject();
        json.addProperty("jobId", job.jobId());
        json.addProperty("status", job.status().name());
        json.addProperty("elapsedMillis", job.elapsedMillis());
        if (job.message() != null) json.addProperty("message", job.message());
        if (job.status() == RouteDebugJobManager.Status.FAILED) json.addProperty("reason", "WORK_FAILED");
        if (job.status() == RouteDebugJobManager.Status.TIMED_OUT) json.addProperty("reason", "JOB_TIMED_OUT");
        if (job.result() != null) {
            var result = com.google.gson.JsonParser.parseString(job.result()).getAsJsonObject();
            json.add("result", result);
            // A completed computation can still contain a failed route. Preserve its real reason and log.
            if (result.has("status") && "FAILED".equals(result.get("status").getAsString())) {
                json.addProperty("status", "FAILED");
                for (String key : new String[]{"reason", "message"}) if (result.has(key)) json.add(key, result.get(key));
            }
        }
        return json.toString();
    }

    private void stopInternal() {
        if (jobs != null) {
            jobs.close();
            jobs = null;
        }
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
                if (minecraftServer != null && minecraftServer.isStopped()) {
                    writeRouteError(ex, 503, "SERVER_STOPPED", "调试服务所属世界已关闭，请在当前世界重新启动调试服务");
                    return;
                }
                handler.handle(ex);
            } catch (ResponseWriteException disconnected) {
                // The route may have finished after a proxy/browser closed its synchronous connection.
                // A second response cannot repair that connection; async results remain cached for polling.
                LOGGER.debug("[RouteDebug] response connection closed {}", ex.getRequestURI().getPath());
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

    private boolean tellusAvailable() {
        return minecraftServer != null && Boolean.TRUE.equals(callOnServerThread(
                () -> TellusWorlds.isTellusLevel(minecraftServer.overworld())));
    }

    private static void writeRouteError(HttpExchange ex, int status, String reason, String message) throws IOException {
        writeJson(ex, status, routeErrorJson(reason, message));
    }

    private static String routeErrorJson(String reason, String message) {
        var error = new com.google.gson.JsonObject();
        error.addProperty("status", "FAILED");
        error.addProperty("reason", reason);
        error.addProperty("message", message);
        return error.toString();
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
        try {
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        } catch (IOException disconnected) { throw new ResponseWriteException(disconnected); }
    }

    /** Only transport writes use this type; real route/render IO failures remain errors. */
    private static final class ResponseWriteException extends IOException {
        ResponseWriteException(IOException cause) { super(cause); }
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
        if (action == null) return null;
        if (minecraftServer == null || minecraftServer.isSameThread()) return action.get();
        if (minecraftServer.isStopped()) throw new java.util.concurrent.CancellationException("Server stopped");
        // MinecraftServer.submit may run inline when the server has stopped.
        // This mailbox is drained exclusively by the server tick and never runs a caller's world read inline.
        var task = PortAsyncRuntime.onMain(minecraftServer, action);
        try {
            return task.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException stopped) {
            task.cancel(false);
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Debug metadata request interrupted");
        } catch (java.util.concurrent.TimeoutException expired) {
            task.cancel(false);
            throw new java.util.concurrent.CompletionException(expired);
        } catch (java.util.concurrent.ExecutionException failed) {
            throw new java.util.concurrent.CompletionException(failed.getCause());
        }
    }
}
