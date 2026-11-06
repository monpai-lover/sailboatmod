package com.monpai.sailboatmod.route.water.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.monpai.sailboatmod.route.water.SailboatRouteCommands;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP requests: an unauthenticated remote request must never enter a Minecraft handler. */
class RouteDebugServerHttpTest {
    private final HttpClient client = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void stop() {
        RouteDebugServer.stop();
    }

    @Test
    void publicCommandIsExplicitAndExistingLocalFormsRemainAvailable() {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        SailboatRouteCommands.register(dispatcher);
        var start = dispatcher.getRoot().getChild("sailboat").getChild("routedebug").getChild("start");
        assertNotNull(start.getCommand(), "start without a port must retain local mode");
        var port = start.getChild("port");
        assertNotNull(port.getCommand(), "start with only a port must retain local mode");
        assertNotNull(port.getChild("public"), "start <port> public must explicitly select remote access");
        assertNotNull(port.getChild("public").getCommand());
    }

    @Test
    void randomPortReportsTheActualListeningPort() throws Exception {
        RouteDebugServer.start(null, "127.0.0.1", 0);
        assertTrue(RouteDebugServer.get().port() > 0, "port 0 must expose the actual assigned listening port");
        assertEquals(listeningPort(), RouteDebugServer.get().port());
    }

    @Test
    void localModeStillServesThePageAndPingWithoutAuthentication() throws Exception {
        RouteDebugServer.start(null, "127.0.0.1", 0);
        assertEquals(200, get("/").statusCode());
        var ping = get("/ping");
        assertEquals(200, ping.statusCode());
        assertEquals("route-debug ok", ping.body());
        assertTrue(ping.headers().allValues("Set-Cookie").isEmpty());
    }

    @Test
    void publicModeGuardsEveryEndpointBeforeAnyMinecraftWork() throws Exception {
        RouteDebugServer.start(null, "0.0.0.0", 0);
        for (String path : new String[] {"/", "/ping", "/api/docks", "/api/route?a=0&b=1", "/unknown"}) {
            var response = get(path);
            assertEquals(401, response.statusCode(), path + " must be denied before invoking a Minecraft handler");
            assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
            assertPrivate(response);
        }
    }

    @Test
    void incorrectQueryAndCookieTokensCannotAuthorizePublicWork() throws Exception {
        RouteDebugServer.start(null, "0.0.0.0", 0);
        assertEquals(401, get("/?token=").statusCode());
        assertEquals(401, get("/ping?token=wrong").statusCode());
        assertEquals(401, get("/api/route?a=0&b=1&token=wrong").statusCode());
        assertEquals(401, request("/api/route?a=0&b=1", "route_debug_session=wrong").statusCode());
    }

    @Test
    void entryTokenCreatesAnHttpOnlyCookieForSubsequentPageFetches() throws Exception {
        RouteDebugServer.start(null, "0.0.0.0", 0);
        URI entry = entryUrl();
        assertEquals(listeningPort(), entry.getPort());
        assertTrue(entry.getRawQuery().matches("token=[A-Za-z0-9_-]{43,}"), "entry URL needs an unpredictable URL-safe token");
        String token = entry.getRawQuery().substring("token=".length());
        var page = get("/?" + entry.getRawQuery());
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("Water Route Debug"));
        assertFalse(page.body().contains(token), "the shared HTML must not embed the access secret");
        assertPrivate(page);
        String setCookie = page.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(setCookie.startsWith("route_debug_session="));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Strict"));
        assertTrue(setCookie.contains("Path=/"));
        assertFalse(setCookie.contains("Domain="), "the access cookie must remain host-only");
        String cookie = setCookie.split(";", 2)[0];
        var ping = request("/ping", cookie);
        assertEquals(200, ping.statusCode());
        assertEquals("route-debug ok", ping.body());
        assertPrivate(ping);
    }

    @Test
    void restartingPublicModeInvalidatesBothTheOldUrlAndCookie() throws Exception {
        RouteDebugServer.start(null, "0.0.0.0", 0);
        String previousQuery = entryUrl().getRawQuery();
        String previousCookie = get("/?" + previousQuery).headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        RouteDebugServer.start(null, "0.0.0.0", 0);
        assertNotEquals(previousQuery, entryUrl().getRawQuery());
        assertEquals(401, get("/ping?" + previousQuery).statusCode());
        assertEquals(401, request("/ping", previousCookie).statusCode());
    }

    private static void assertPrivate(HttpResponse<String> response) {
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElse(""));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return request(path, null);
    }

    private HttpResponse<String> request(String path, String cookie) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + listeningPort() + path))
                .timeout(Duration.ofSeconds(5)).GET();
        if (cookie != null) builder.header("Cookie", cookie);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int listeningPort() throws Exception {
        // Inspect the already-running listener so authentication tests also run against the old port(0) bug.
        Field field = RouteDebugServer.class.getDeclaredField("httpServer");
        field.setAccessible(true);
        return ((HttpServer) field.get(RouteDebugServer.get())).getAddress().getPort();
    }

    private static URI entryUrl() throws Exception {
        // Reflection keeps this test source compilable before the public-mode accessor is implemented.
        return URI.create((String) RouteDebugServer.class.getMethod("accessUrl").invoke(RouteDebugServer.get()));
    }
}
