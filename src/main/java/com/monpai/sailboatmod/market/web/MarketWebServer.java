package com.monpai.sailboatmod.market.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpContext;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public final class MarketWebServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static volatile MarketWebServer INSTANCE;

    private final MinecraftServer minecraftServer;
    private final MarketWebAuthManager auth;
    private final MarketWebService service = new MarketWebService();
    private HttpServer httpServer;
    private ExecutorService executor;

    private MarketWebServer(MinecraftServer minecraftServer) {
        this.minecraftServer = minecraftServer;
        this.auth = new MarketWebAuthManager(minecraftServer);
    }

    public static synchronized void start(MinecraftServer minecraftServer) {
        stop();
        if (minecraftServer == null || !com.monpai.sailboatmod.ModConfig.marketWebEnabled()) {
            return;
        }
        try {
            MarketWebServer server = new MarketWebServer(minecraftServer);
            server.startInternal();
            INSTANCE = server;
        } catch (Exception exception) {
            LOGGER.error("Failed to start market web server", exception);
        }
    }

    public static synchronized void stop() {
        if (INSTANCE != null) {
            INSTANCE.stopInternal();
            INSTANCE = null;
        }
    }

    public static MarketWebServer get() {
        return INSTANCE;
    }

    public boolean isRunning() {
        return httpServer != null;
    }

    public MarketWebAuthManager auth() {
        return auth;
    }

    private void startInternal() throws IOException {
        InetSocketAddress address = new InetSocketAddress(
                com.monpai.sailboatmod.ModConfig.marketWebBindHost(),
                com.monpai.sailboatmod.ModConfig.marketWebPort()
        );
        httpServer = HttpServer.create(address, 0);
        executor = Executors.newCachedThreadPool();
        httpServer.setExecutor(executor);
        createContext("/api/auth/token-login", this::handleTokenLogin);
        createContext("/api/session/me", this::handleSessionMe);
        createContext("/api/markets", this::handleMarkets);
        createContext("/", this::handleStatic);
        httpServer.start();
        LOGGER.info("Market web server started on {}:{}", address.getHostString(), address.getPort());
    }

    private void stopInternal() {
        auth.clear();
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void createContext(String path, HttpHandler handler) {
        HttpContext context = httpServer.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception exception) {
                LOGGER.error("Market web request failed: {}", exchange.getRequestURI(), exception);
                writeJson(exchange, 500, error("internal_error", "Internal server error"));
            } finally {
                exchange.close();
            }
        });
        context.getFilters().clear();
    }

    private void handleTokenLogin(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
            return;
        }
        JsonObject body = readBody(exchange);
        String token = stringValue(body, "token");
        MarketWebAuthManager.SessionToken session = auth.exchangeLoginToken(token);
        if (session == null) {
            writeJson(exchange, 401, error("invalid_token", "Invalid token"));
            return;
        }
        JsonObject out = new JsonObject();
        out.addProperty("sessionToken", session.token());
        out.addProperty("playerUuid", session.playerUuid().toString());
        out.addProperty("playerName", session.playerName());
        writeJson(exchange, 200, out);
    }

    private void handleSessionMe(HttpExchange exchange) throws IOException {
        MarketPlayerIdentity identity = requireIdentity(exchange);
        if (identity == null) {
            return;
        }
        JsonObject out = callOnServerThread(() -> {
            JsonObject json = new JsonObject();
            json.addProperty("playerUuid", identity.playerUuidString());
            json.addProperty("playerName", identity.playerName());
            json.addProperty("online", identity.onlinePlayer() != null);
            return json;
        });
        writeJson(exchange, 200, out);
    }

    private void handleMarkets(HttpExchange exchange) throws IOException {
        MarketPlayerIdentity identity = requireIdentity(exchange);
        if (identity == null) {
            return;
        }
        List<String> path = pathParts(exchange.getRequestURI().getPath());
        if (path.size() == 2) {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonObject out = callOnServerThread(() -> {
                    JsonObject json = new JsonObject();
                    json.add("markets", service.listMarkets(minecraftServer, identity));
                    return json;
                });
                writeJson(exchange, 200, out);
                return;
            }
            writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
            return;
        }
        if (path.size() >= 3) {
            String marketId = path.get(2);
            if (path.size() == 3 && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonObject detail = callOnServerThread(() -> service.marketDetail(minecraftServer, identity, marketId));
                if (detail == null) {
                    writeJson(exchange, 404, error("not_found", "Market not found"));
                    return;
                }
                writeJson(exchange, 200, detail);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
                return;
            }
            JsonObject body = readBody(exchange);
            boolean ok;
            if (path.size() == 4 && "purchase".equals(path.get(3))) {
                ok = callOnServerThread(() -> service.purchaseListing(
                        minecraftServer,
                        identity,
                        marketId,
                        intValue(body, "listingIndex", -1),
                        intValue(body, "quantity", 1)
                ));
            } else if (path.size() == 4 && "listings".equals(path.get(3))) {
                ok = callOnServerThread(() -> service.createListing(
                        minecraftServer,
                        identity,
                        marketId,
                        intValue(body, "storageIndex", -1),
                        intValue(body, "quantity", 1),
                        intValue(body, "priceAdjustmentBp", 0),
                        stringValue(body, "sellerNote")
                ));
            } else if (path.size() == 6 && "listings".equals(path.get(3)) && "cancel".equals(path.get(5))) {
                ok = callOnServerThread(() -> service.cancelListing(minecraftServer, identity, marketId, path.get(4)));
            } else if (path.size() == 5 && "credits".equals(path.get(3)) && "claim".equals(path.get(4))) {
                ok = callOnServerThread(() -> service.claimCredits(minecraftServer, identity, marketId));
            } else if (path.size() == 4 && "buy-orders".equals(path.get(3))) {
                ok = callOnServerThread(() -> service.createBuyOrder(
                        minecraftServer,
                        identity,
                        marketId,
                        stringValue(body, "commodityKey"),
                        intValue(body, "quantity", 1),
                        intValue(body, "minPriceBp", -1000),
                        intValue(body, "maxPriceBp", 1000)
                ));
            } else if (path.size() == 6 && "buy-orders".equals(path.get(3)) && "cancel".equals(path.get(5))) {
                ok = callOnServerThread(() -> service.cancelBuyOrder(minecraftServer, identity, marketId, path.get(4)));
            } else if (path.size() == 5 && "dispatch".equals(path.get(3)) && "retry".equals(path.get(4))) {
                ok = callOnServerThread(() -> service.retryDispatch(minecraftServer, identity, marketId));
            } else {
                writeJson(exchange, 404, error("not_found", "Endpoint not found"));
                return;
            }
            if (!ok) {
                writeJson(exchange, 400, error("action_failed", "Action failed"));
                return;
            }
            JsonObject detail = callOnServerThread(() -> service.marketDetail(minecraftServer, identity, marketId));
            writeJson(exchange, 200, detail == null ? success() : detail);
            return;
        }
        writeJson(exchange, 404, error("not_found", "Endpoint not found"));
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            writeStatic(exchange, "marketweb/index.html", "text/html; charset=utf-8");
            return;
        }
        if ("/app.js".equals(path)) {
            writeStatic(exchange, "marketweb/app.js", "application/javascript; charset=utf-8");
            return;
        }
        if ("/app.css".equals(path)) {
            writeStatic(exchange, "marketweb/app.css", "text/css; charset=utf-8");
            return;
        }
        if ("/config.json".equals(path)) {
            writeStatic(exchange, "marketweb/config.json", "application/json; charset=utf-8");
            return;
        }
        writeJson(exchange, 404, error("not_found", "Not found"));
    }

    private void writeStatic(HttpExchange exchange, String resourcePath, String contentType) throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                writeJson(exchange, 404, error("not_found", "Static resource missing"));
                return;
            }
            byte[] bytes = stream.readAllBytes();
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }

    private MarketPlayerIdentity requireIdentity(HttpExchange exchange) throws IOException {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        String sessionToken = "";
        if (authHeader != null && authHeader.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            sessionToken = authHeader.substring(7).trim();
        }
        final String resolvedSessionToken = sessionToken;
        MarketPlayerIdentity identity = callOnServerThread(() -> auth.resolveIdentity(minecraftServer, resolvedSessionToken));
        if (identity == null) {
            writeJson(exchange, 401, error("unauthorized", "Unauthorized"));
            return null;
        }
        return identity;
    }

    private static JsonObject readBody(HttpExchange exchange) throws IOException {
        try (InputStream stream = exchange.getRequestBody();
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private static void writeJson(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static JsonObject success() {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        return out;
    }

    private static JsonObject error(String code, String message) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("errorCode", code);
        out.addProperty("message", message);
        return out;
    }

    private static List<String> pathParts(String path) {
        return java.util.Arrays.stream((path == null ? "" : path).split("/"))
                .filter(part -> !part.isBlank())
                .toList();
    }

    private static int intValue(JsonObject body, String key, int fallback) {
        if (body == null || !body.has(key)) {
            return fallback;
        }
        try {
            return body.get(key).getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String stringValue(JsonObject body, String key) {
        if (body == null || !body.has(key)) {
            return "";
        }
        try {
            return body.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private <T> T callOnServerThread(Supplier<T> action) {
        if (minecraftServer == null || action == null) {
            return null;
        }
        if (minecraftServer.isSameThread()) {
            return action.get();
        }
        return minecraftServer.submit(action::get).join();
    }
}
