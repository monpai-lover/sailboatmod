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
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModFileInfo;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class MarketWebServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final String ICON_CACHE_VERSION = "marketweb-icons-v5";
    private static volatile MarketWebServer INSTANCE;

    private final MinecraftServer minecraftServer;
    private final MarketWebAuthManager auth;
    private final MarketWebService service = new MarketWebService();
    private final MarketWebIconService icons = new MarketWebIconService();
    private final AtomicLong resourceVersion = new AtomicLong(initialResourceVersion());
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

    public long resourceVersion() {
        return resourceVersion.get();
    }

    public static String iconCacheVersion() {
        return ICON_CACHE_VERSION;
    }

    public static String addonVersion() {
        return ModList.get().getModContainerById(SailboatMarketWebAddon.MODID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    private static long initialResourceVersion() {
        return Math.max(1L, System.currentTimeMillis());
    }

    public void reload() {
        icons.clearCache();
        long version = resourceVersion.incrementAndGet();
        LOGGER.info("Market web resources reloaded (resource version {})", version);
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
        createContext("/api/auth/password-login", this::handlePasswordLogin);
        createContext("/api/session/me", this::handleSessionMe);
        createContext("/api/debug/version", this::handleDebugVersion);
        createContext("/api/markets", this::handleMarkets);
        createContext("/api/icons", this::handleIcon);
        createContext("/", this::handleStatic);
        httpServer.start();
        LOGGER.info("Market web server started on {}:{} (resource version {}, icon cache {})",
                address.getHostString(),
                address.getPort(),
                resourceVersion.get(),
                ICON_CACHE_VERSION);
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
        String username = stringValue(body, "username");
        String password = stringValue(body, "password");
        boolean wantsBind = !username.isBlank() || !password.isBlank();
        MarketWebAuthManager.AuthResult result;
        if (wantsBind) {
            result = auth.bindAccountFromToken(token, username, password);
        } else {
            MarketWebAuthManager.SessionToken session = auth.exchangeLoginToken(token);
            result = session == null
                    ? MarketWebAuthManager.AuthResult.invalid("invalid_token", "Invalid token")
                    : new MarketWebAuthManager.AuthResult(session, "", false, null, null);
        }
        if (!result.ok()) {
            writeJson(exchange, 401, error(result.errorCode() == null ? "invalid_token" : result.errorCode(), result.message() == null ? "Invalid token" : result.message()));
            return;
        }
        JsonObject out = new JsonObject();
        writeAuthSuccess(out, result);
        writeJson(exchange, 200, out);
    }

    private void handlePasswordLogin(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
            return;
        }
        JsonObject body = readBody(exchange);
        MarketWebAuthManager.AuthResult result = auth.loginWithAccount(
                stringValue(body, "username"),
                stringValue(body, "password")
        );
        if (!result.ok()) {
            writeJson(exchange, 401, error(result.errorCode() == null ? "invalid_credentials" : result.errorCode(), result.message() == null ? "Invalid username or password" : result.message()));
            return;
        }
        JsonObject out = new JsonObject();
        writeAuthSuccess(out, result);
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
            MarketWebAccountSavedData.AccountEntry account = auth.accountByPlayerUuid(identity.playerUuid());
            json.addProperty("accountBound", account != null);
            json.addProperty("accountUsername", account == null ? "" : account.username());
            json.addProperty("webResourceVersion", resourceVersion.get());
            json.addProperty("iconCacheVersion", ICON_CACHE_VERSION);
            return json;
        });
        writeJson(exchange, 200, out);
    }

    private void handleDebugVersion(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
            return;
        }
        JsonObject out = new JsonObject();
        out.addProperty("addonModId", SailboatMarketWebAddon.MODID);
        out.addProperty("addonVersion", addonVersion());
        out.addProperty("resourceVersion", resourceVersion.get());
        out.addProperty("iconCacheVersion", ICON_CACHE_VERSION);
        out.addProperty("devMode", com.monpai.sailboatmod.ModConfig.marketWebDevMode());
        writeJson(exchange, 200, out);
    }

    private void handleMarkets(HttpExchange exchange) throws IOException {
        List<String> path = pathParts(exchange.getRequestURI().getPath());
        if (path.size() == 2) {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                MarketPlayerIdentity identity = resolveIdentityOrGuest(exchange);
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
                MarketPlayerIdentity identity = resolveIdentityOrGuest(exchange);
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
            MarketPlayerIdentity identity = requireIdentity(exchange);
            if (identity == null) {
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
                ok = callOnServerThread(() -> service.retryDispatch(
                        minecraftServer,
                        identity,
                        marketId,
                        intValue(body, "orderIndex", 0),
                        stringValue(body, "terminalType")
                ));
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
        if ("/browse".equals(path) || "/inventory".equals(path) || "/sell".equals(path) || "/buy".equals(path) || "/chart".equals(path) || "/index".equals(path)) {
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
        if (path.startsWith("/assets/")) {
            String resourcePath = path.substring(1);
            writeStatic(exchange, resourcePath, contentType(resourcePath));
            return;
        }
        if (path.startsWith("/") && path.lastIndexOf('/') == 0) {
            String resourcePath = "marketweb/" + path.substring(1);
            writeStatic(exchange, resourcePath, contentType(resourcePath));
            return;
        }
        writeJson(exchange, 404, error("not_found", "Not found"));
    }

    private void handleIcon(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
            return;
        }
        String commodityKey = queryParam(exchange, "commodityKey");
        if (commodityKey.isBlank()) {
            writeJson(exchange, 400, error("missing_commodity_key", "Missing commodityKey"));
            return;
        }
        byte[] bytes = icons.loadIcon(commodityKey);
        if (bytes == null || bytes.length == 0) {
            LOGGER.warn("Market web icon not found for {}", commodityKey);
            writeJson(exchange, 404, error("icon_not_found", "Icon not found"));
            return;
        }
        Headers headers = exchange.getResponseHeaders();
        String etag = "\"" + ICON_CACHE_VERSION + ":v" + resourceVersion.get() + ":" + Integer.toHexString(commodityKey.hashCode()) + "\"";
        String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
        if (etag.equals(ifNoneMatch)) {
            headers.set("ETag", etag);
            headers.set("Cache-Control", "public, max-age=3600");
            headers.set("X-Web-Resource-Version", Long.toString(resourceVersion.get()));
            exchange.sendResponseHeaders(304, -1);
            return;
        }
        headers.set("Content-Type", "image/png");
        headers.set("Cache-Control", "public, max-age=3600");
        headers.set("ETag", etag);
        headers.set("X-Icon-Cache-Version", ICON_CACHE_VERSION);
        headers.set("X-Web-Resource-Version", Long.toString(resourceVersion.get()));
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String contentType(String resourcePath) {
        String normalized = resourcePath == null ? "" : resourcePath.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".png")) {
            return "image/png";
        }
        if (normalized.endsWith(".jpg") || normalized.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (normalized.endsWith(".svg")) {
            return "image/svg+xml; charset=utf-8";
        }
        if (normalized.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (normalized.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (normalized.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (normalized.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private static String queryParam(HttpExchange exchange, String key) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank() || key == null || key.isBlank()) {
            return "";
        }
        return Arrays.stream(raw.split("&"))
                .map(entry -> entry.split("=", 2))
                .filter(parts -> parts.length > 0 && key.equals(parts[0]))
                .map(parts -> parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "")
                .findFirst()
                .orElse("");
    }

    private void writeStatic(HttpExchange exchange, String resourcePath, String contentType) throws IOException {
        if (com.monpai.sailboatmod.ModConfig.marketWebDevMode()) {
            Path devFile = resolveDevResourcePath(resourcePath);
            if (devFile != null && Files.isRegularFile(devFile)) {
                byte[] bytes = Files.readAllBytes(devFile);
                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Type", contentType);
                headers.set("X-Web-Resource-Version", Long.toString(resourceVersion.get()));
                applyNoCache(headers);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
                return;
            }
        }
        try (InputStream stream = resource(resourcePath)) {
            if (stream == null) {
                LOGGER.warn("Market web static resource missing: {}", resourcePath);
                writeJson(exchange, 404, error("not_found", "Static resource missing"));
                return;
            }
            byte[] bytes = stream.readAllBytes();
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", contentType);
            headers.set("X-Web-Resource-Version", Long.toString(resourceVersion.get()));
            if (com.monpai.sailboatmod.ModConfig.marketWebDevMode()) {
                applyNoCache(headers);
            }
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }

    private Path resolveDevResourcePath(String resourcePath) {
        for (Path devRoot : resolveDevRoots(resourcePath)) {
            if (devRoot == null) {
                continue;
            }
            String normalized = resourcePath == null ? "" : resourcePath.replace('\\', '/');
            if (normalized.startsWith("marketweb/")) {
                normalized = normalized.substring("marketweb/".length());
            }
            if (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            if (normalized.isBlank()) {
                continue;
            }
            Path candidate = devRoot.resolve(normalized).normalize();
            if (candidate.startsWith(devRoot.normalize()) && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private Path resolveDevRoot() {
        String configured = com.monpai.sailboatmod.ModConfig.marketWebDevRoot();
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured.trim()).toAbsolutePath().normalize();
        }
        List<Path> candidates = List.of(
                Paths.get("src", "main", "resources", "marketweb"),
                Paths.get("src", "marketweb", "resources", "marketweb"),
                Paths.get("build", "resources", "main", "marketweb")
        );
        for (Path candidate : candidates) {
            Path absolute = candidate.toAbsolutePath().normalize();
            if (Files.isDirectory(absolute)) {
                return absolute;
            }
        }
        return null;
    }

    private List<Path> resolveDevRoots(String resourcePath) {
        String configured = com.monpai.sailboatmod.ModConfig.marketWebDevRoot();
        if (configured != null && !configured.isBlank()) {
            return List.of(Paths.get(configured.trim()).toAbsolutePath().normalize());
        }
        String normalized = resourcePath == null ? "" : resourcePath.replace('\\', '/');
        if (normalized.startsWith("assets/")) {
            return List.of(
                    Paths.get("src", "main", "resources").toAbsolutePath().normalize(),
                    Paths.get("build", "resources", "main").toAbsolutePath().normalize()
            );
        }
        Path devRoot = resolveDevRoot();
        return devRoot == null ? List.of() : List.of(devRoot);
    }

    private InputStream resource(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        InputStream modResource = modResource(path);
        if (modResource != null) {
            return modResource;
        }
        ClassLoader loader = getClass().getClassLoader();
        if (loader != null) {
            InputStream stream = loader.getResourceAsStream(path);
            if (stream != null) {
                return stream;
            }
        }
        return ClassLoader.getSystemResourceAsStream(path);
    }

    private InputStream modResource(String path) {
        String normalized = path.replace('\\', '/');
        String modId;
        String[] relative;
        if (normalized.startsWith("assets/")) {
            String[] parts = normalized.split("/");
            if (parts.length < 3) {
                return null;
            }
            modId = parts[1];
            if (modId.isBlank() || "minecraft".equals(modId)) {
                return null;
            }
            relative = new String[parts.length];
            System.arraycopy(parts, 0, relative, 0, relative.length);
        } else if (normalized.startsWith("marketweb/")) {
            modId = SailboatMarketWebAddon.MODID;
            relative = normalized.split("/");
        } else {
            return null;
        }
        IModFileInfo modFileInfo = ModList.get().getModFileById(modId);
        if (modFileInfo == null || modFileInfo.getFile() == null) {
            return null;
        }
        Path resourcePath = modFileInfo.getFile().findResource(relative);
        if (resourcePath == null || !Files.isRegularFile(resourcePath)) {
            return null;
        }
        try {
            return Files.newInputStream(resourcePath);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static void applyNoCache(Headers headers) {
        headers.set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        headers.set("Pragma", "no-cache");
        headers.set("Expires", "0");
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

    private MarketPlayerIdentity resolveIdentityOrGuest(HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        String sessionToken = "";
        if (authHeader != null && authHeader.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            sessionToken = authHeader.substring(7).trim();
        }
        final String resolvedSessionToken = sessionToken;
        MarketPlayerIdentity identity = callOnServerThread(() -> auth.resolveIdentity(minecraftServer, resolvedSessionToken));
        return identity != null ? identity : new MarketPlayerIdentity(null, "", null);
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

    private static void writeAuthSuccess(JsonObject out, MarketWebAuthManager.AuthResult result) {
        MarketWebAuthManager.SessionToken session = result.session();
        out.addProperty("sessionToken", session.token());
        out.addProperty("playerUuid", session.playerUuid().toString());
        out.addProperty("playerName", session.playerName());
        out.addProperty("accountUsername", result.accountUsername() == null ? "" : result.accountUsername());
        out.addProperty("accountBound", result.accountUsername() != null && !result.accountUsername().isBlank());
        out.addProperty("newlyBound", result.newlyBound());
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
