package com.monpai.sailboatmod.market.web;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class MarketWebPendingMarketHttpTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void pendingMarketsReturnRetryableStatusThroughTheNormalHttpWrapper(int wrappers) throws Exception {
        RuntimeException pending = new MarketWebMarketResolver.UnavailableException(
                "market_loading", "Market is loading. Please retry shortly.", 5);
        for (int i = 0; i < wrappers; i++) pending = new CompletionException(pending);
        RuntimeException failure = pending;
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            MarketWebServer web = bareServer(http);
            Method create = MarketWebServer.class.getDeclaredMethod("createContext", String.class, HttpHandler.class);
            create.setAccessible(true);
            create.invoke(web, "/pending", (HttpHandler) exchange -> { throw failure; });
            http.start();
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/pending"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, response.statusCode());
            assertEquals("5", response.headers().firstValue("Retry-After").orElse(""));
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
            var json = JsonParser.parseString(response.body()).getAsJsonObject();
            assertEquals("market_loading", json.get("errorCode").getAsString());
            assertFalse(json.get("ok").getAsBoolean());
        } finally {
            http.stop(0);
        }
    }

    private static MarketWebServer bareServer(HttpServer http) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        MarketWebServer web = (MarketWebServer) ((Unsafe) field.get(null)).allocateInstance(MarketWebServer.class);
        Field listener = MarketWebServer.class.getDeclaredField("httpServer");
        listener.setAccessible(true);
        listener.set(web, http);
        return web;
    }
}
