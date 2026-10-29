package com.monpai.sailboatmod.market.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Requests an attacker can send without logging in. The server object is bare: nothing past validation may run. */
class MarketWebSecurityTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "/assets/sailboatmod/../../../../etc/passwd",
            "/assets/sailboatmod/%2e%2e/%2e%2e/secret.txt",
            "/assets/sailboatmod/%5c..%5csecret.txt",
            "/assets//sailboatmod/lang/en_us.json"
    })
    void assetPathsCannotLeaveTheirFolder(String path) throws Exception {
        assertEquals(404, call("handleStatic", "GET", path, "").responseCode);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/map/flags/%2E%2E%2F%2E%2E%2Fworld%2Ficon.png",
            "/api/map/flags/..%5Csecret.png",
            "/api/map/flags/%2Fetc%2Fpasswd.png",
            "/api/map/flags/a%00b.png"
    })
    void flagIdsCannotNameFilesOutsideTheFlagFolder(String path) throws Exception {
        assertEquals(404, call("handleMapFlag", "GET", path, "").responseCode);
    }

    @Test
    void resourcePathCheckRejectsEveryWayOut() {
        assertTrue(MarketWebServer.isSafeResourcePath("assets/sailboatmod/lang/en_us.json"));
        for (String bad : new String[] {"assets/sailboatmod/../../x", "assets/./x", "assets//x", "assets\\..\\x", "", "assets/x\0.png"}) {
            org.junit.jupiter.api.Assertions.assertFalse(MarketWebServer.isSafeResourcePath(bad), bad);
        }
    }

    @Test
    void flagIdCheckAcceptsGeneratedIdsOnly() {
        assertTrue(MarketWebServer.isSafeFlagId("crimea_flag-01_abcdef123456"));
        for (String bad : new String[] {"../x", "..\\x", "/etc/passwd", "a b", "a\0b", "", "x".repeat(129)}) {
            org.junit.jupiter.api.Assertions.assertFalse(MarketWebServer.isSafeFlagId(bad), bad);
        }
    }

    @Test
    void oversizedLoginBodiesAreRefusedBeforeParsing() throws Exception {
        String huge = "{\"username\":\"" + "a".repeat(MarketWebServer.MAX_REQUEST_BODY_BYTES + 10) + "\"}";
        assertEquals(413, call("handlePasswordLogin", "POST", "/api/auth/password-login", huge).responseCode);
        assertEquals(413, call("handleTokenLogin", "POST", "/api/auth/token-login", huge).responseCode);
    }

    @Test
    void responsesForbidFramingAndTypeSniffing() throws Exception {
        TestExchange page = call("handleStatic", "GET", "/", "");
        assertEquals("nosniff", page.responseHeaders.getFirst("X-Content-Type-Options"));
        assertEquals("DENY", page.responseHeaders.getFirst("X-Frame-Options"));
        TestExchange missing = call("handleStatic", "GET", "/assets/sailboatmod/../x", "");
        assertEquals("nosniff", missing.responseHeaders.getFirst("X-Content-Type-Options"));
    }

    @Test
    void requestThreadsAreBounded() {
        ThreadPoolExecutor executor = MarketWebServer.newRequestExecutor();
        try {
            assertTrue(executor.getMaximumPoolSize() <= 32, "max threads " + executor.getMaximumPoolSize());
            assertTrue(executor.getQueue().remainingCapacity() < Integer.MAX_VALUE, "the queue is bounded");
            assertEquals(executor.getMaximumPoolSize(), executor.getCorePoolSize(),
                    "a bounded queue only adds threads past the core size once it is full, so core must equal max");
            assertTrue(executor.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy,
                    "overflow is refused (HttpServer then closes the connection), never run on the dispatcher thread");
        } finally {
            executor.shutdownNow();
        }
    }

    private static TestExchange call(String handlerName, String method, String path, String body) throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        MarketWebServer server = (MarketWebServer) ((Unsafe) unsafeField.get(null)).allocateInstance(MarketWebServer.class);
        Field resourceVersion = MarketWebServer.class.getDeclaredField("resourceVersion");
        resourceVersion.setAccessible(true);
        resourceVersion.set(server, new AtomicLong(1L));
        TestExchange exchange = new TestExchange(method, path, body);
        Method handler = MarketWebServer.class.getDeclaredMethod(handlerName, HttpExchange.class);
        handler.setAccessible(true);
        handler.invoke(server, exchange);
        return exchange;
    }

    private static final class TestExchange extends HttpExchange {
        private final String method;
        private final URI requestUri;
        private final byte[] body;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream response = new ByteArrayOutputStream();
        private int responseCode;

        private TestExchange(String method, String requestPath, String body) {
            this.method = method;
            this.requestUri = URI.create(requestPath);
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return requestUri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(body); }
        @Override public OutputStream getResponseBody() { return response; }
        @Override public void sendResponseHeaders(int code, long length) { responseCode = code; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("203.0.113.9", 1); }
        @Override public int getResponseCode() { return responseCode; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 11450); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream input, OutputStream output) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
