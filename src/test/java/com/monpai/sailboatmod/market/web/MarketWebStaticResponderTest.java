package com.monpai.sailboatmod.market.web;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** ETag/304, gzip and dev-mode behaviour of static files and API JSON, against a real com.sun.net.httpserver. */
class MarketWebStaticResponderTest {
    @Test
    void staticFilesRevalidateAndTextIsCompressed() throws Exception {
        byte[] js = ("// app.js\n" + "console.log('market');\n".repeat(400)).getBytes(StandardCharsets.UTF_8);
        byte[] small = "body{}".getBytes(StandardCharsets.UTF_8);
        AtomicReference<byte[]> current = new AtomicReference<>(js);
        AtomicReference<Boolean> devMode = new AtomicReference<>(false);
        MarketWebStaticResponder responder = new MarketWebStaticResponder();
        byte[] bigJson = ("{\"listings\":[" + "{\"itemName\":\"钻石\",\"unitPrice\":820},".repeat(500) + "{}]}").getBytes(StandardCharsets.UTF_8);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/app.js", exchange -> responder.write(exchange, "application/javascript; charset=utf-8", current.get(), devMode.get()));
        server.createContext("/small.css", exchange -> responder.write(exchange, "text/css; charset=utf-8", small, false));
        server.createContext("/api/big", exchange -> MarketWebStaticResponder.writeDynamic(exchange, 200, "application/json; charset=utf-8", bigJson));
        server.createContext("/api/small", exchange -> MarketWebStaticResponder.writeDynamic(exchange, 200, "application/json; charset=utf-8", small));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
        try {
            HttpResponse<byte[]> gz = get(client, base + "/app.js", "gzip, deflate, br", null);
            String etag = gz.headers().firstValue("ETag").orElse("");
            check("S1 gzip + weak ETag + no-cache + Vary",
                    gz.statusCode() == 200
                            && "gzip".equals(gz.headers().firstValue("Content-Encoding").orElse(""))
                            && etag.startsWith("W/\"")
                            && "no-cache".equals(gz.headers().firstValue("Cache-Control").orElse(""))
                            && gz.headers().allValues("Vary").toString().contains("Accept-Encoding")
                            && java.util.Arrays.equals(gunzip(gz.body()), js)
                            && gz.body().length < js.length / 5,
                    "status=" + gz.statusCode() + " headers=" + gz.headers().map() + " size=" + gz.body().length);

            HttpResponse<byte[]> notModified = get(client, base + "/app.js", "gzip", etag);
            check("S2 If-None-Match answers 304 without body",
                    notModified.statusCode() == 304 && notModified.body().length == 0,
                    "status=" + notModified.statusCode() + " bytes=" + notModified.body().length);

            HttpResponse<byte[]> identity = get(client, base + "/app.js", null, null);
            check("S3 identity encoding when gzip not accepted, same ETag",
                    identity.statusCode() == 200
                            && identity.headers().firstValue("Content-Encoding").isEmpty()
                            && java.util.Arrays.equals(identity.body(), js)
                            && etag.equals(identity.headers().firstValue("ETag").orElse("")),
                    "headers=" + identity.headers().map());

            HttpResponse<byte[]> tiny = get(client, base + "/small.css", "gzip", null);
            check("S4 small bodies are not compressed",
                    tiny.headers().firstValue("Content-Encoding").isEmpty() && java.util.Arrays.equals(tiny.body(), small),
                    "headers=" + tiny.headers().map());

            HttpResponse<byte[]> refused = get(client, base + "/app.js", "gzip;q=0, identity", null);
            check("S5 gzip;q=0 is respected", refused.headers().firstValue("Content-Encoding").isEmpty(), "headers=" + refused.headers().map());

            current.set(("// v2\n" + "console.log('market v2');\n".repeat(400)).getBytes(StandardCharsets.UTF_8));
            HttpResponse<byte[]> changed = get(client, base + "/app.js", "gzip", etag);
            check("S6 changed content gets a new ETag and a full response",
                    changed.statusCode() == 200 && !etag.equals(changed.headers().firstValue("ETag").orElse(etag)),
                    "status=" + changed.statusCode());

            devMode.set(true);
            HttpResponse<byte[]> dev = get(client, base + "/app.js", "gzip", null);
            check("S7 dev mode keeps no-store and sends no ETag",
                    dev.statusCode() == 200
                            && dev.headers().firstValue("Cache-Control").orElse("").contains("no-store")
                            && dev.headers().firstValue("ETag").isEmpty(),
                    "headers=" + dev.headers().map());

            HttpResponse<byte[]> api = get(client, base + "/api/big", "gzip", null);
            check("S8 large JSON is gzip-compressed",
                    "gzip".equals(api.headers().firstValue("Content-Encoding").orElse(""))
                            && java.util.Arrays.equals(gunzip(api.body()), bigJson),
                    "headers=" + api.headers().map() + " ratio=" + api.body().length + "/" + bigJson.length);
            HttpResponse<byte[]> apiSmall = get(client, base + "/api/small", "gzip", null);
            check("S9 small JSON stays identity", apiSmall.headers().firstValue("Content-Encoding").isEmpty(), "headers=" + apiSmall.headers().map());
        } finally {
            server.stop(0);
        }
    }

    private static HttpResponse<byte[]> get(HttpClient client, String url, String acceptEncoding, String ifNoneMatch) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
        if (acceptEncoding != null) {
            builder.header("Accept-Encoding", acceptEncoding);
        }
        if (ifNoneMatch != null) {
            builder.header("If-None-Match", ifNoneMatch);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    static byte[] gunzip(byte[] data) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return in.readAllBytes();
        }
    }

    private static void check(String name, boolean condition, String detail) {
        assertTrue(condition, name + " — " + detail);
    }
}
