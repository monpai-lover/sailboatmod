package com.monpai.sailboatmod.market.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class MarketWebTerrainHttpTest {
    @Test void cacheHitsNeverDiscoverAndConditionalReadsTrackActualBytes() throws Exception {
        var current = new AtomicReference<>(new byte[] {1, 2, 3});
        var discoveries = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tile", exchange -> MarketWebServer.serveCachedTerrain(exchange,
                current::get, discoveries::incrementAndGet));
        server.start();
        try {
            var client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/tile?v=old");
            var first = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, first.statusCode());
            String etag = first.headers().firstValue("ETag").orElseThrow();
            assertFalse(first.headers().firstValue("Cache-Control").orElse("").contains("immutable"));
            var conditional = HttpRequest.newBuilder(uri).header("If-None-Match", etag).GET().build();
            var same = client.send(conditional, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(304, same.statusCode());
            assertEquals(0, same.body().length);
            assertEquals(0, discoveries.get());
            current.set(new byte[] {4, 5, 6});
            var changed = client.send(conditional, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, changed.statusCode());
            assertNotEquals(etag, changed.headers().firstValue("ETag").orElseThrow());
            current.set(null);
            var missing = client.send(conditional, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(404, missing.statusCode());
            assertEquals(1, discoveries.get());
            assertTrue(missing.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        } finally { server.stop(0); }
    }
}
