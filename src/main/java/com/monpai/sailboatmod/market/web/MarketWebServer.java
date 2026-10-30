package com.monpai.sailboatmod.market.web;

import com.monpai.sailboatmod.market.MarketPlayerIdentity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class MarketWebServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final String ICON_CACHE_VERSION = "marketweb-icons-v7";
    private static volatile MarketWebServer INSTANCE;

    private final MinecraftServer minecraftServer;
    private final MarketWebAuthManager auth;
    private final MarketWebService service = new MarketWebService();
    private final MarketWebIconService icons;
    private final AtomicLong resourceVersion = new AtomicLong(initialResourceVersion());
    /** Stateless apart from a bounded gzip cache, so one serves every server instance. */
    private static final MarketWebStaticResponder STATIC_RESPONDER = new MarketWebStaticResponder();
    private final MarketWebIdempotency idempotency = new MarketWebIdempotency();
    private final MarketWebEventHub events = new MarketWebEventHub();
    /** Market revision and trade already announced on /api/events; -1 until the first tick. */
    private long publishedRevision = -1L;
    private long publishedTick;
    private long publishedTradeSequence;
    private HttpServer httpServer;
    private ExecutorService executor;

    private MarketWebServer(MinecraftServer minecraftServer) {
        this.minecraftServer = minecraftServer;
        this.auth = new MarketWebAuthManager(minecraftServer);
        this.icons = new MarketWebIconService(downloadedIconAssetRoot(minecraftServer));
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

    private static Path downloadedIconAssetRoot(MinecraftServer minecraftServer) {
        if (minecraftServer == null) {
            return null;
        }
        return minecraftServer.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve("sailboatmod_market")
                .resolve("web_assets")
                .resolve("1.21.1");
    }

    public void reload() {
        icons.clearCache();
        long version = resourceVersion.incrementAndGet();
        LOGGER.info("Market web resources reloaded (resource version {})", version);
    }

    /** Larger request bodies are refused (413) before parsing; an unbounded body could exhaust the server's memory. */
    static final int MAX_REQUEST_BODY_BYTES = 64 * 1024;
    /** Failed logins allowed per address and per username in {@link #LOGIN_WINDOW_MILLIS}. */
    private static final long LOGIN_WINDOW_MILLIS = 15 * 60_000L;
    private static final MarketWebLoginThrottle LOGIN_BY_ADDRESS = new MarketWebLoginThrottle(10, LOGIN_WINDOW_MILLIS, System::currentTimeMillis);
    private static final MarketWebLoginThrottle LOGIN_BY_USERNAME = new MarketWebLoginThrottle(10, LOGIN_WINDOW_MILLIS, System::currentTimeMillis);
    private static final java.util.regex.Pattern FLAG_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,128}");

    /**
     * 16 request threads and 256 waiting requests. Past that a request is refused, and HttpServer closes its
     * connection; it is never run on the accepting thread, which would stop all other connections.
     * Core size equals the maximum: with a bounded queue, threads past the core size only start once it is full.
     */
    static java.util.concurrent.ThreadPoolExecutor newRequestExecutor() {
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(
                16, 16, 60L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(256),
                runnable -> {
                    Thread thread = new Thread(runnable, "sailboatmod-market-web-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /** A relative resource path with no empty, "." or ".." segments and no backslashes. */
    static boolean isSafeResourcePath(String relative) {
        return relative != null && !relative.isEmpty() && relative.indexOf('\\') < 0 && relative.indexOf('\0') < 0
                && Arrays.stream(relative.split("/", -1))
                        .noneMatch(segment -> segment.isEmpty() || ".".equals(segment) || "..".equals(segment));
    }

    /** Flag ids are generated names: letters, digits, '_' and '-'. Anything else could name another file. */
    static boolean isSafeFlagId(String flagId) {
        return flagId != null && FLAG_ID.matcher(flagId).matches();
    }

    private static void applySecurityHeaders(Headers headers) {
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
    }

    private static String clientAddress(HttpExchange exchange) {
        InetSocketAddress remote = exchange.getRemoteAddress();
        String direct = remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
        java.util.Set<String> proxies;
        try {
            proxies = com.monpai.sailboatmod.ModConfig.marketWebTrustedProxies();
        } catch (RuntimeException configNotLoaded) {
            proxies = java.util.Set.of();
        }
        return MarketWebLoginThrottle.clientAddress(direct, exchange.getRequestHeaders().getFirst("X-Forwarded-For"), proxies);
    }

    /** Per username and address, so nobody can lock another player out of their account from elsewhere. */
    private static String usernameKey(HttpExchange exchange, String username) {
        return username == null || username.isBlank() ? null
                : "user:" + username.trim().toLowerCase(Locale.ROOT) + "|ip:" + clientAddress(exchange);
    }

    private static boolean loginAllowed(HttpExchange exchange, String username) throws IOException {
        String userKey = usernameKey(exchange, username);
        boolean allowed = LOGIN_BY_ADDRESS.allowed("ip:" + clientAddress(exchange))
                && (userKey == null || LOGIN_BY_USERNAME.allowed(userKey));
        if (!allowed) {
            writeJson(exchange, 429, error("too_many_attempts", "Too many failed attempts. Try again in 15 minutes."));
        }
        return allowed;
    }

    /** Only wrong credentials and tokens count as failures; form mistakes such as a taken username do not. */
    private static boolean isCredentialFailure(String errorCode) {
        return errorCode == null || "invalid_credentials".equals(errorCode) || "invalid_token".equals(errorCode);
    }

    private static void recordLogin(HttpExchange exchange, String username, boolean ok, String errorCode) {
        if (!ok && !isCredentialFailure(errorCode)) {
            return;
        }
        String address = "ip:" + clientAddress(exchange);
        String user = usernameKey(exchange, username);
        if (ok) {
            LOGIN_BY_ADDRESS.recordSuccess(address);
            if (user != null) {
                LOGIN_BY_USERNAME.recordSuccess(user);
            }
        } else {
            LOGIN_BY_ADDRESS.recordFailure(address);
            if (user != null) {
                LOGIN_BY_USERNAME.recordFailure(user);
            }
        }
    }

    private void startInternal() throws IOException {
        InetSocketAddress address = new InetSocketAddress(
                com.monpai.sailboatmod.ModConfig.marketWebBindHost(),
                com.monpai.sailboatmod.ModConfig.marketWebPort()
        );
        // Drop connections that never finish sending a request (slowloris) or never read the answer. Read once by
        // the JDK when its first HttpServer starts, so a value already set elsewhere is left alone.
        if (System.getProperty("sun.net.httpserver.maxReqTime") == null) {
            System.setProperty("sun.net.httpserver.maxReqTime", "30");
        }
        if (System.getProperty("sun.net.httpserver.maxRspTime") == null) {
            System.setProperty("sun.net.httpserver.maxRspTime", "120");
        }
        httpServer = HttpServer.create(address, 0);
        executor = newRequestExecutor();
        httpServer.setExecutor(executor);
        createContext("/api/auth/token-login", this::handleTokenLogin);
        createContext("/api/auth/password-login", this::handlePasswordLogin);
        createContext("/api/session/me", this::handleSessionMe);
        createContext("/api/debug/version", this::handleDebugVersion);
        createContext("/api/markets", this::handleMarkets);
        createContext("/api/listings", this::handleAggregatedListings);
        createContext("/api/map/snapshot", this::handleMapSnapshot);
        createContext("/api/map/markets", this::handleMapMarkets);
        createContext("/api/map/airports", this::handleMapAirports);
        createContext("/api/map/territories", this::handleMapTerritories);
        createContext("/api/map/shipments", this::handleMapShipments);
        createContext("/api/map/render/status", this::handleMapRenderStatus);
        createContext("/api/map/square/tile", this::handleSquareMapTile);
        createContext("/api/map/world", this::handleMapWorld);
        createContext("/api/map/tellus/tile", this::handleTellusMapTile);
        createContext("/api/map/tile", this::handleMapTile);
        createContext("/api/map/flags", this::handleMapFlag);
        createContext("/api/items/resolve", this::handleItemResolve);
        createContext("/api/icons/batch", this::handleIconBatch);
        createContext("/api/icons", this::handleIcon);
        // Not createContext(): its wrapper closes the exchange when the handler returns, which would end the stream.
        httpServer.createContext("/api/events", this::handleEvents).getFilters().clear();
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
        events.close();
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** GET /api/events: Server-Sent Events for open pages (see MarketWebEventHub). Guests get public events only. */
    private void handleEvents(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
                exchange.close();
                return;
            }
            applySecurityHeaders(exchange.getResponseHeaders());
            events.open(exchange, resolveIdentityOrGuest(exchange).playerUuid());
        } catch (Exception exception) {
            LOGGER.warn("Market web event stream failed: {}", exception.toString());
            exchange.close();
        }
    }

    /**
     * Server thread, every tick. Market writes (MarketChangeNotifier) become at most one {@code market} event a
     * second; recorded trades become {@code trade} events for the page's ticker. In-game and web actions alike.
     */
    void publishMarketChanges(long tick) {
        long revision = com.monpai.sailboatmod.market.MarketChangeNotifier.revision();
        if (publishedRevision < 0L) {
            publishedRevision = revision;
            publishedTick = tick;
            publishedTradeSequence = com.monpai.sailboatmod.market.MarketChangeNotifier.lastTradeSequence();
            return;
        }
        for (com.monpai.sailboatmod.market.MarketChangeNotifier.Trade trade
                : com.monpai.sailboatmod.market.MarketChangeNotifier.tradesAfter(publishedTradeSequence)) {
            JsonObject json = new JsonObject();
            json.addProperty("commodityKey", trade.commodityKey());
            json.addProperty("unitPrice", trade.unitPrice());
            json.addProperty("quantity", trade.quantity());
            json.addProperty("at", trade.at());
            events.publish("trade", json.toString());
            publishedTradeSequence = trade.sequence();
        }
        if (revision != publishedRevision && tick - publishedTick >= 20L) {
            publishedRevision = revision;
            publishedTick = tick;
            events.publish("market", "{\"marketId\":\"*\"}");
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
        if (body == null) {
            writeJson(exchange, 413, error("payload_too_large", "Request body too large"));
            return;
        }
        String token = stringValue(body, "token");
        String username = stringValue(body, "username");
        String password = stringValue(body, "password");
        if (!loginAllowed(exchange, username)) {
            return;
        }
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
        recordLogin(exchange, username, result.ok(), result.errorCode());
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
        if (body == null) {
            writeJson(exchange, 413, error("payload_too_large", "Request body too large"));
            return;
        }
        String username = stringValue(body, "username");
        if (!loginAllowed(exchange, username)) {
            return;
        }
        MarketWebAuthManager.AuthResult result = auth.loginWithAccount(
                username,
                stringValue(body, "password")
        );
        recordLogin(exchange, username, result.ok(), result.errorCode());
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
            if (path.size() == 4 && "minecolonies-warehouse".equals(path.get(3))
                    && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                MarketPlayerIdentity identity = resolveIdentityOrGuest(exchange);
                JsonArray items = callOnServerThread(() -> service.mineColoniesWarehouseItems(minecraftServer, identity, marketId));
                JsonObject out = new JsonObject();
                out.add("items", items == null ? new JsonArray() : items);
                writeJson(exchange, 200, out);
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
            if (body == null) {
                writeJson(exchange, 413, error("payload_too_large", "Request body too large"));
                return;
            }
            if (path.size() == 4 && "probe-modes".equals(path.get(3))) {
                JsonObject probe = callOnServerThread(() -> service.probeFulfillmentModes(
                        minecraftServer,
                        identity,
                        marketId,
                        intValue(body, "listingIndex", -1),
                        parseWarehousePos(stringValue(body, "targetWarehouse", ""))
                ));
                writeJson(exchange, 200, probe == null ? error("probe_failed", "Probe failed") : probe);
                return;
            }
            writeStored(exchange, once(exchange, identity, () -> runMarketAction(identity, marketId, path, body)));
            return;
        }
        writeJson(exchange, 404, error("not_found", "Endpoint not found"));
    }

    /** POST /api/markets/{id}/... after authentication; returns the answer rather than writing it, so once() can replay it. */
    private MarketWebIdempotency.StoredResponse runMarketAction(MarketPlayerIdentity identity, String marketId,
                                                                List<String> path, JsonObject body) {
        boolean ok;
        MarketWebService.ActionResult actionResult = null;
        if (path.size() == 4 && "purchase".equals(path.get(3))) {
            ok = callOnServerThread(() -> service.purchaseListing(
                    minecraftServer,
                    identity,
                    marketId,
                    intValue(body, "listingIndex", -1),
                    intValue(body, "quantity", 1),
                    stringValue(body, "fulfillment", "SELLER_SHIP"),
                    parseWarehousePos(stringValue(body, "targetWarehouse", ""))
            ));
        } else if (path.size() == 4 && "listings".equals(path.get(3))) {
            actionResult = callOnServerThread(() -> service.createListing(
                    minecraftServer,
                    identity,
                    marketId,
                    intValue(body, "storageIndex", -1),
                    intValue(body, "quantity", 1),
                    intValue(body, "unitPrice", 0),
                    stringValue(body, "sellerNote")
            ));
            ok = actionResult != null && actionResult.ok();
        } else if (path.size() == 4 && "minecolonies-listing".equals(path.get(3))) {
            actionResult = callOnServerThread(() -> service.createMineColoniesListing(
                    minecraftServer,
                    identity,
                    marketId,
                    intValue(body, "colonyId", 0),
                    stringValue(body, "itemId", ""),
                    intValue(body, "quantity", 1),
                    intValue(body, "unitPrice", 0),
                    stringValue(body, "sellerNote")
            ));
            ok = actionResult != null && actionResult.ok();
        } else if (path.size() == 6 && "listings".equals(path.get(3)) && "cancel".equals(path.get(5))) {
            ok = callOnServerThread(() -> service.cancelListing(minecraftServer, identity, marketId, path.get(4)));
        } else if (path.size() == 5 && "credits".equals(path.get(3)) && "claim".equals(path.get(4))) {
            ok = callOnServerThread(() -> service.claimCredits(minecraftServer, identity, marketId));
        } else if (path.size() == 5 && "wallet".equals(path.get(3)) && "transfer".equals(path.get(4))) {
            actionResult = callOnServerThread(() -> service.transferWallet(
                    minecraftServer,
                    identity,
                    marketId,
                    stringValue(body, "action"),
                    longValue(body, "amount", 0L)
            ));
            ok = actionResult != null && actionResult.ok();
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
        } else if (path.size() == 6 && "purchase-orders".equals(path.get(3)) && "cancel".equals(path.get(5))) {
            ok = callOnServerThread(() -> service.cancelPurchaseOrder(minecraftServer, identity, marketId, path.get(4)));
        } else if (path.size() == 5 && "dispatch".equals(path.get(3)) && "retry".equals(path.get(4))) {
            ok = callOnServerThread(() -> service.retryDispatch(
                    minecraftServer,
                    identity,
                    marketId,
                    intValue(body, "orderIndex", 0),
                    stringValue(body, "terminalType")
            ));
        } else {
            return json(404, error("not_found", "Endpoint not found"));
        }
        if (!ok) {
            if (actionResult != null) {
                return json(400, error(
                        actionResult.errorCode() == null || actionResult.errorCode().isBlank() ? "action_failed" : actionResult.errorCode(),
                        actionResult.message() == null || actionResult.message().isBlank() ? "Action failed" : actionResult.message()
                ));
            }
            return json(400, error("action_failed", "Action failed"));
        }
        JsonObject detail = callOnServerThread(() -> service.marketDetail(minecraftServer, identity, marketId));
        return json(200, detail == null ? success() : detail);
    }

    /**
     * 聚合视图端点:
     * GET  /api/listings                      → 全服所有挂单 + 来源标注 + 对查看者的可购买性(访客可读)。
     * POST /api/listings/{listingId}/purchase → 按全局 listingId 购买,可见性按买家城镇,货送买家城镇仓库(需登录)。
     *   body: { executorMarketId, quantity, fulfillment, targetWarehouse }
     */
    private void handleAggregatedListings(HttpExchange exchange) throws IOException {
        List<String> path = pathParts(exchange.getRequestURI().getPath());
        if (path.size() == 2) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
                return;
            }
            MarketPlayerIdentity identity = resolveIdentityOrGuest(exchange);
            JsonObject out = callOnServerThread(() -> service.aggregatedListings(minecraftServer, identity));
            writeJson(exchange, 200, out == null ? error("listings_unavailable", "Listings unavailable") : out);
            return;
        }
        // GET /api/listings/commodity?key=xxx → 全服该商品的"伪 detail"(对标终端详情,数据全服聚合)。
        // commodityKey 含冒号(如 minecraft:diamond),用 query param 而非路径段避免编码问题。
        if (path.size() == 3 && "commodity".equals(path.get(2))) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
                return;
            }
            String commodityKey = queryParam(exchange, "key");
            if (commodityKey == null || commodityKey.isBlank()) {
                writeJson(exchange, 400, error("missing_key", "Missing commodity key"));
                return;
            }
            MarketPlayerIdentity identity = resolveIdentityOrGuest(exchange);
            JsonObject out = callOnServerThread(() -> service.aggregatedCommodityDetail(minecraftServer, identity, commodityKey));
            writeJson(exchange, 200, out == null ? error("commodity_unavailable", "Commodity detail unavailable") : out);
            return;
        }
        if (path.size() == 4 && "purchase".equals(path.get(3))) {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
                return;
            }
            MarketPlayerIdentity identity = requireIdentity(exchange);
            if (identity == null) {
                return;
            }
            String listingId = path.get(2);
            JsonObject body = readBody(exchange);
            if (body == null) {
                writeJson(exchange, 413, error("payload_too_large", "Request body too large"));
                return;
            }
            writeStored(exchange, once(exchange, identity, () -> {
                boolean ok = callOnServerThread(() -> service.purchaseAggregated(
                        minecraftServer,
                        identity,
                        stringValue(body, "executorMarketId", ""),
                        listingId,
                        intValue(body, "quantity", 1),
                        stringValue(body, "fulfillment", "SELLER_SHIP"),
                        parseWarehousePos(stringValue(body, "targetWarehouse", ""))
                ));
                return ok ? json(200, success()) : json(400, error("action_failed", "Action failed"));
            }));
            return;
        }
        writeJson(exchange, 404, error("not_found", "Endpoint not found"));
    }

    private void handleMapSnapshot(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
            return;
        }
        MarketPlayerIdentity identity = resolveIdentityOrGuest(exchange);
        String focusedMarketId = queryParam(exchange, "focusedMarketId");
        JsonObject out = callOnServerThread(() -> service.mapSnapshot(minecraftServer, identity, focusedMarketId));
        writeJson(exchange, 200, out == null ? error("map_unavailable", "Map unavailable") : out);
    }

    private void handleMapMarkets(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
            return;
        }
        MarketPlayerIdentity identity = resolveIdentityOrGuest(exchange);
        JsonObject out = callOnServerThread(() -> {
            JsonObject json = success();
            json.add("markets", service.mapMarkets(minecraftServer, identity));
            return json;
        });
        writeJson(exchange, 200, out);
    }

    private void handleMapAirports(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
            return;
        }
        MarketPlayerIdentity identity = resolveIdentityOrGuest(exchange);
        JsonObject out = callOnServerThread(() -> {
            JsonObject json = success();
            json.add("airports", service.mapAirports(minecraftServer, identity));
            return json;
        });
        writeJson(exchange, 200, out);
    }

    private void handleMapTerritories(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
            return;
        }
        MarketPlayerIdentity identity = resolveIdentityOrGuest(exchange);
        JsonObject out = callOnServerThread(() -> {
            JsonObject json = success();
            json.add("territories", service.mapTerritories(minecraftServer, identity));
            return json;
        });
        writeJson(exchange, 200, out);
    }

    private void handleMapShipments(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
            return;
        }
        MarketPlayerIdentity identity = requireIdentity(exchange);
        if (identity == null) {
            return;
        }
        boolean history = pathParts(exchange.getRequestURI().getPath()).contains("history");
        JsonObject out = callOnServerThread(() -> {
            JsonObject json = success();
            if (history) {
                json.add("history", service.mapShipmentHistory(minecraftServer, identity));
            } else {
                json.add("shipments", service.mapShipments(minecraftServer, identity));
            }
            // Server time in ticks, so the page can show how long ago each timeline event happened.
            json.addProperty("gameTime", minecraftServer.overworld().getGameTime());
            return json;
        });
        writeJson(exchange, 200, out);
    }

    private void handleMapTile(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
            return;
        }
        List<String> path = pathParts(exchange.getRequestURI().getPath());
        if (path.size() != 6) {
            writeJson(exchange, 404, error("not_found", "Tile not found"));
            return;
        }
        String lod = path.get(3);
        Integer tileX = parseInt(path.get(4));
        Integer tileZ = parsePngInt(path.get(5));
        if (tileX == null || tileZ == null) {
            writeJson(exchange, 404, error("not_found", "Tile not found"));
            return;
        }
        byte[] bytes = callOnServerThread(() -> service.mapTile(minecraftServer, lod, tileX, tileZ));
        if (bytes == null || bytes.length == 0) {
            writeJson(exchange, 404, error("not_found", "Tile not found"));
            return;
        }
        Headers headers = exchange.getResponseHeaders();
        // 地形瓦片是"内容寻址"的不可变资源:URL 带 ?v=tileRefreshKey(),内容变则 ?v 变成新 URL。
        // 长缓存 + immutable 让浏览器对同一 ?v 永不重校验、直接磁盘命中(拖动/缩放复用零网络);
        // 内容更新走新 URL 自然取新图。修复"地图慢 + 移走回来重下"的根因。
        headers.set("Cache-Control", "public, max-age=86400, immutable");
        writeBytes(exchange, 200, "image/png", bytes);
    }

    private void handleSquareMapTile(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
            return;
        }
        List<String> path = pathParts(exchange.getRequestURI().getPath());
        if (path.size() != 7) {
            writeJson(exchange, 404, error("not_found", "Tile not found"));
            return;
        }
        String dimension = path.get(4);
        Integer zoom = parseInt(path.get(5));
        TileName tile = parseTileName(path.get(6));
        if (zoom == null || tile == null) {
            writeJson(exchange, 404, error("not_found", "Tile not found"));
            return;
        }
        byte[] bytes = callOnServerThread(() -> service.squareMapTile(minecraftServer, dimension, zoom, tile.x(), tile.z()));
        if (bytes == null || bytes.length == 0) {
            writeJson(exchange, 404, error("not_found", "Tile not found"));
            return;
        }
        Headers headers = exchange.getResponseHeaders();
        // 见 handleMapTile:瓦片 URL 带 ?v 版本号,长缓存 + immutable 安全且能更新。
        headers.set("Cache-Control", "public, max-age=86400, immutable");
        writeBytes(exchange, 200, "image/png", bytes);
    }

    /** {@code GET /api/map/world}:是否 Tellus 世界、世界比例、预测层最远层级。 */
    private void handleMapWorld(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
            return;
        }
        JsonObject json = callOnServerThread(() -> service.worldInfo(minecraftServer));
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, max-age=0");
        writeJson(exchange, 200, json);
    }

    /** {@code GET /api/map/tellus/tile/{z}/{x}_{y}.png}:Tellus 预测层瓦片;尚未画好回 404(已排入渲染)。 */
    private void handleTellusMapTile(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
            return;
        }
        List<String> path = pathParts(exchange.getRequestURI().getPath());
        if (path.size() != 6) {
            writeJson(exchange, 404, error("not_found", "Tile not found"));
            return;
        }
        Integer zoom = parseInt(path.get(4));
        TileName tile = parseTileName(path.get(5));
        if (zoom == null || tile == null) {
            writeJson(exchange, 404, error("not_found", "Tile not found"));
            return;
        }
        byte[] bytes = callOnServerThread(() -> service.tellusTile(minecraftServer, zoom, tile.x(), tile.z()));
        if (bytes == null || bytes.length == 0) {
            writeJson(exchange, 404, error("not_found", "Tile not found"));
            return;
        }
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400, immutable");
        writeBytes(exchange, 200, "image/png", bytes);
    }

    private void handleMapRenderStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
            return;
        }
        JsonObject json = callOnServerThread(() -> service.mapRenderStatus(minecraftServer));
        Headers headers = exchange.getResponseHeaders();
        headers.set("Cache-Control", "no-cache, max-age=0");
        writeJson(exchange, 200, json);
    }

    private void handleMapFlag(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
            return;
        }
        List<String> path = pathParts(exchange.getRequestURI().getRawPath());
        if (path.size() != 4 || !path.get(3).endsWith(".png")) {
            writeJson(exchange, 404, error("not_found", "Flag not found"));
            return;
        }
        String rawFlagId = path.get(3).substring(0, path.get(3).length() - ".png".length());
        String flagId = URLDecoder.decode(rawFlagId, StandardCharsets.UTF_8);
        if (!isSafeFlagId(flagId)) {
            writeJson(exchange, 404, error("not_found", "Flag not found"));
            return;
        }
        byte[] bytes = callOnServerThread(() -> service.mapFlag(minecraftServer, flagId));
        if (bytes == null || bytes.length == 0) {
            writeJson(exchange, 404, error("not_found", "Flag not found"));
            return;
        }
        Headers headers = exchange.getResponseHeaders();
        headers.set("Cache-Control", "public, max-age=300");
        writeBytes(exchange, 200, "image/png", bytes);
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
        if ("/browse".equals(path) || "/inventory".equals(path) || "/sell".equals(path) || "/buy".equals(path) || "/demand".equals(path) || "/chart".equals(path) || "/index".equals(path) || "/map".equals(path) || "/all".equals(path) || "/my_orders".equals(path)) {
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
        if ("/map.js".equals(path)) {
            writeStatic(exchange, "marketweb/map.js", "application/javascript; charset=utf-8");
            return;
        }
        if ("/tellus-basemap.js".equals(path)) {
            writeStatic(exchange, "marketweb/tellus-basemap.js", "application/javascript; charset=utf-8");
            return;
        }
        if ("/zh-tw.js".equals(path)) {
            writeStatic(exchange, "marketweb/zh-tw.js", "application/javascript; charset=utf-8");
            return;
        }
        if ("/logistics-panel.js".equals(path)) {
            writeStatic(exchange, "marketweb/logistics-panel.js", "application/javascript; charset=utf-8");
            return;
        }
        if ("/logistics-render.js".equals(path)) {
            writeStatic(exchange, "marketweb/logistics-render.js", "application/javascript; charset=utf-8");
            return;
        }
        if ("/config.json".equals(path)) {
            writeStatic(exchange, "marketweb/config.json", "application/json; charset=utf-8");
            return;
        }
        if (path.startsWith("/vendor/leaflet/")) {
            String resourcePath = path.substring(1);
            if (!isSafeResourcePath(resourcePath)) {
                writeJson(exchange, 404, error("not_found", "Not found"));
                return;
            }
            writeStatic(exchange, "marketweb/" + resourcePath, contentType(resourcePath));
            return;
        }
        if (path.startsWith("/assets/")) {
            String resourcePath = path.substring(1);
            if (!isSafeResourcePath(resourcePath)) {
                writeJson(exchange, 404, error("not_found", "Not found"));
                return;
            }
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

    private void handleItemResolve(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
            return;
        }
        String itemId = queryParam(exchange, "itemId");
        if (itemId.isBlank()) {
            itemId = queryParam(exchange, "commodityKey");
        }
        if (itemId.isBlank()) {
            writeJson(exchange, 400, error("missing_item_id", "Missing itemId"));
            return;
        }
        String resolvedItemId = itemId;
        MarketWebService.ItemPreview preview = callOnServerThread(() -> MarketWebService.resolveItemPreview(resolvedItemId));
        if (preview == null) {
            writeJson(exchange, 404, error("item_not_found", "Item not found"));
            return;
        }

        JsonObject out = success();
        out.addProperty("commodityKey", preview.commodityKey());
        out.addProperty("itemId", preview.itemId());
        out.addProperty("displayName", preview.displayName());
        out.addProperty("category", preview.category());
        out.addProperty("suggestedUnitPrice", preview.suggestedUnitPrice());

        byte[] icon = icons.loadIcon(preview.commodityKey());
        if (icon != null && icon.length > 0) {
            out.addProperty("icon", "data:image/png;base64," + Base64.getEncoder().encodeToString(icon));
        }
        Headers headers = exchange.getResponseHeaders();
        headers.set("Cache-Control", "public, max-age=60");
        headers.set("X-Icon-Cache-Version", ICON_CACHE_VERSION);
        headers.set("X-Web-Resource-Version", Long.toString(resourceVersion.get()));
        writeJson(exchange, 200, out);
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
        applySecurityHeaders(headers);
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

    private void handleIconBatch(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, error("method_not_allowed", "Method not allowed"));
            return;
        }
        List<String> requestedKeys = queryParams(exchange, "commodityKey").stream()
                .map(String::trim)
                .filter(key -> !key.isBlank())
                .limit(128)
                .toList();
        if (requestedKeys.isEmpty()) {
            writeJson(exchange, 400, error("missing_commodity_key", "Missing commodityKey"));
            return;
        }

        JsonObject out = success();
        JsonObject iconsJson = new JsonObject();
        JsonArray missing = new JsonArray();
        Base64.Encoder encoder = Base64.getEncoder();

        for (String commodityKey : new LinkedHashSet<>(requestedKeys)) {
            byte[] bytes = icons.loadIcon(commodityKey);
            if (bytes == null || bytes.length == 0) {
                missing.add(commodityKey);
                continue;
            }
            iconsJson.addProperty(commodityKey, "data:image/png;base64," + encoder.encodeToString(bytes));
        }

        Headers headers = exchange.getResponseHeaders();
        headers.set("Cache-Control", "public, max-age=60");
        headers.set("X-Icon-Cache-Version", ICON_CACHE_VERSION);
        headers.set("X-Web-Resource-Version", Long.toString(resourceVersion.get()));
        out.add("icons", iconsJson);
        out.add("missing", missing);
        writeJson(exchange, 200, out);
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

    private static List<String> queryParams(HttpExchange exchange, String key) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank() || key == null || key.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String entry : raw.split("&")) {
            String[] parts = entry.split("=", 2);
            if (parts.length == 0 || !key.equals(parts[0])) {
                continue;
            }
            values.add(parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }

    private void writeStatic(HttpExchange exchange, String resourcePath, String contentType) throws IOException {
        if (com.monpai.sailboatmod.ModConfig.marketWebDevMode()) {
            Path devFile = resolveDevResourcePath(resourcePath);
            if (devFile != null && Files.isRegularFile(devFile)) {
                byte[] bytes = Files.readAllBytes(devFile);
                Headers headers = exchange.getResponseHeaders();
                applySecurityHeaders(headers);
                headers.set("X-Web-Resource-Version", Long.toString(resourceVersion.get()));
                STATIC_RESPONDER.write(exchange, contentType, bytes, true);
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
            applySecurityHeaders(headers);
            headers.set("X-Web-Resource-Version", Long.toString(resourceVersion.get()));
            // 生产环境不能让浏览器(尤其 Safari)按启发式强缓存旧文件,否则改了前端却"毫无变化"。
            // 现在用 ETag + Cache-Control: no-cache:每次都向服务器重新验证,文件没变只回 304(无本文),变了立即取新文件;
            // 文本资源 gzip 压缩(maplibre-gl.js 1 MB → 约 270 KB)。
            STATIC_RESPONDER.write(exchange, contentType, bytes, false);
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

    /** The request's JSON object (empty when malformed), or null when the body exceeds {@link #MAX_REQUEST_BODY_BYTES}. */
    private static JsonObject readBody(HttpExchange exchange) throws IOException {
        byte[] bytes;
        try (InputStream stream = exchange.getRequestBody()) {
            bytes = stream.readNBytes(MAX_REQUEST_BODY_BYTES + 1);
        }
        if (bytes.length > MAX_REQUEST_BODY_BYTES) {
            return null;
        }
        try {
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private static void writeJson(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        applySecurityHeaders(exchange.getResponseHeaders());
        // gzip when the client accepts it and the body is over 1 KB (a market detail shrinks to about a tenth).
        MarketWebStaticResponder.writeDynamic(exchange, status, "application/json; charset=utf-8", bytes);
    }

    private static MarketWebIdempotency.StoredResponse json(int status, JsonObject body) {
        return new MarketWebIdempotency.StoredResponse(status, "application/json; charset=utf-8",
                GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void writeStored(HttpExchange exchange, MarketWebIdempotency.StoredResponse response) throws IOException {
        applySecurityHeaders(exchange.getResponseHeaders());
        MarketWebStaticResponder.writeDynamic(exchange, response.status(), response.contentType(), response.body());
    }

    /**
     * Runs a write at most once per Idempotency-Key (scoped to the player and path): a resend after a lost reply gets
     * the stored answer instead of buying again. Requests without the header run as before.
     */
    private MarketWebIdempotency.StoredResponse once(HttpExchange exchange, MarketPlayerIdentity identity,
                                                     java.util.function.Supplier<MarketWebIdempotency.StoredResponse> action) {
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if (key == null || key.isBlank()) {
            return action.get();
        }
        return idempotency.execute(identity.playerUuidString() + " " + exchange.getRequestURI().getPath(), key, action);
    }

    private static void writeBytes(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        byte[] safeBytes = bytes == null ? new byte[0] : bytes;
        Headers headers = exchange.getResponseHeaders();
        applySecurityHeaders(headers);
        headers.set("Content-Type", contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType);
        exchange.sendResponseHeaders(status, safeBytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(safeBytes);
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

    private static Integer parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Integer parsePngInt(String value) {
        if (value == null || !value.endsWith(".png")) {
            return null;
        }
        return parseInt(value.substring(0, value.length() - ".png".length()));
    }

    private static TileName parseTileName(String value) {
        if (value == null || !value.endsWith(".png")) {
            return null;
        }
        String name = value.substring(0, value.length() - ".png".length());
        int split = name.lastIndexOf('_');
        if (split <= 0 || split >= name.length() - 1) {
            return null;
        }
        Integer x = parseInt(name.substring(0, split));
        Integer z = parseInt(name.substring(split + 1));
        return x == null || z == null ? null : new TileName(x, z);
    }

    private record TileName(int x, int z) {
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

    private static long longValue(JsonObject body, String key, long fallback) {
        if (body == null || !body.has(key)) {
            return fallback;
        }
        try {
            return body.get(key).getAsLong();
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

    private static String stringValue(JsonObject body, String key, String fallback) {
        String v = stringValue(body, key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static net.minecraft.core.BlockPos parseWarehousePos(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new net.minecraft.core.BlockPos(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException ex) {
            return null;
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
