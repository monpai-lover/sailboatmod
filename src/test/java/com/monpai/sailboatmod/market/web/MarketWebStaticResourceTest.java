package com.monpai.sailboatmod.market.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import sun.misc.Unsafe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketWebStaticResourceTest {
    @ParameterizedTest
    @CsvSource({
            "/vendor/leaflet/leaflet.js, application/javascript; charset=utf-8",
            "/vendor/leaflet/leaflet.css, text/css; charset=utf-8",
            "/vendor/leaflet/images/marker-icon.png, image/png"
    })
    void servesPackagedLeafletResources(String requestPath, String expectedContentType) throws Exception {
        TestExchange exchange = request(requestPath);

        assertEquals(200, exchange.getResponseCode());
        assertEquals(expectedContentType, exchange.getResponseHeaders().getFirst("Content-Type"));
        assertArrayEquals(Files.readAllBytes(Path.of("src/main/resources/marketweb" + requestPath)),
                exchange.response.toByteArray());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/vendor/leaflet/../leaflet/leaflet.js",
            "/vendor/leaflet/./leaflet.js",
            "/vendor/leaflet/%2e%2e/%2e%2e/app.js",
            "/vendor/leaflet/%5c..%5c..%5capp.js",
            "/vendor/leaflet//leaflet.js",
            "/vendors/leaflet/leaflet.js"
    })
    void rejectsTraversalAndPathsOutsideVendorPrefix(String requestPath) throws Exception {
        assertEquals(404, request(requestPath).getResponseCode());
    }

    private static TestExchange request(String path) throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        MarketWebServer server = (MarketWebServer) ((Unsafe) unsafeField.get(null)).allocateInstance(MarketWebServer.class);
        Field resourceVersion = MarketWebServer.class.getDeclaredField("resourceVersion");
        resourceVersion.setAccessible(true);
        resourceVersion.set(server, new AtomicLong(1L));
        TestExchange exchange = new TestExchange(path);
        Method handler = MarketWebServer.class.getDeclaredMethod("handleStatic", HttpExchange.class);
        handler.setAccessible(true);
        handler.invoke(server, exchange);
        return exchange;
    }

    private static final class TestExchange extends HttpExchange {
        private final URI requestUri;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream response = new ByteArrayOutputStream();
        private int responseCode;

        private TestExchange(String requestPath) {
            requestUri = URI.create(requestPath);
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return requestUri; }
        @Override public String getRequestMethod() { return "GET"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getResponseBody() { return response; }
        @Override public void sendResponseHeaders(int code, long length) { responseCode = code; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 1); }
        @Override public int getResponseCode() { return responseCode; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 11450); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream input, OutputStream output) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
