package com.monpai.sailboatmod.market.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

/**
 * Writes static web resources and API payloads with HTTP caching and compression.
 *
 * <p>Static files: weak ETag from the SHA-256 of the bytes, {@code Cache-Control: no-cache} so the browser
 * revalidates every time and gets a body-less 304 when nothing changed, gzip for text types, and
 * {@code Vary: Accept-Encoding}. In dev mode the old {@code no-store} behaviour is kept so edits show up
 * immediately. Gzip output is cached per ETag, so each resource version is compressed once.
 *
 * <p>API payloads: gzip when the client accepts it and the body is large enough to benefit
 * (a market detail JSON is typically compressed to 10–15% of its size). No caching headers are added.
 */
public final class MarketWebStaticResponder {
    static final int MIN_COMPRESS_BYTES = 1024;
    private static final int MAX_CACHED_ENTRIES = 256;

    private final Map<String, byte[]> gzipByEtag = new ConcurrentHashMap<>();

    /**
     * Sends a static resource. Security headers (nosniff, frame options, referrer policy) are expected to be
     * applied by the caller before this method, as {@code MarketWebServer.applySecurityHeaders} does today.
     */
    public void write(HttpExchange exchange, String contentType, byte[] body, boolean devMode) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        if (devMode) {
            headers.set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            sendBody(exchange, 200, maybeCompress(exchange, contentType, body, null));
            return;
        }
        String etag = weakEtag(body);
        headers.set("ETag", etag);
        headers.set("Cache-Control", "no-cache");
        headers.add("Vary", "Accept-Encoding");
        if (matchesIfNoneMatch(exchange.getRequestHeaders().getFirst("If-None-Match"), etag)) {
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
            return;
        }
        sendBody(exchange, 200, maybeCompress(exchange, contentType, body, etag));
    }

    /** Sends a dynamic (API) response, gzip-compressed when the client accepts it and it is worth it. */
    public static void writeDynamic(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.add("Vary", "Accept-Encoding");
        byte[] payload = body;
        if (shouldCompress(exchange, contentType, body.length)) {
            payload = gzip(body);
            headers.set("Content-Encoding", "gzip");
        }
        sendBody(exchange, status, payload);
    }

    private byte[] maybeCompress(HttpExchange exchange, String contentType, byte[] body, String etag) {
        if (!shouldCompress(exchange, contentType, body.length)) {
            return body;
        }
        exchange.getResponseHeaders().set("Content-Encoding", "gzip");
        if (etag == null) {
            return gzip(body);
        }
        if (gzipByEtag.size() > MAX_CACHED_ENTRIES) {
            gzipByEtag.clear();
        }
        return gzipByEtag.computeIfAbsent(etag, ignored -> gzip(body));
    }

    private static void sendBody(HttpExchange exchange, int status, byte[] payload) throws IOException {
        exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
        if (payload.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        } else {
            exchange.close();
        }
    }

    static boolean shouldCompress(HttpExchange exchange, String contentType, int length) {
        return length >= MIN_COMPRESS_BYTES
                && isCompressible(contentType)
                && acceptsGzip(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
    }

    static boolean isCompressible(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("text/")
                || type.startsWith("application/javascript")
                || type.startsWith("application/json")
                || type.startsWith("image/svg+xml");
    }

    /** True when the Accept-Encoding header lists gzip (or *) without q=0. */
    static boolean acceptsGzip(String acceptEncoding) {
        if (acceptEncoding == null || acceptEncoding.isBlank()) {
            return false;
        }
        for (String part : acceptEncoding.split(",")) {
            String[] pieces = part.trim().split(";");
            String coding = pieces[0].trim().toLowerCase(Locale.ROOT);
            if (!coding.equals("gzip") && !coding.equals("*")) {
                continue;
            }
            double quality = 1.0;
            for (int i = 1; i < pieces.length; i++) {
                String param = pieces[i].trim();
                if (param.startsWith("q=")) {
                    try {
                        quality = Double.parseDouble(param.substring(2).trim());
                    } catch (NumberFormatException ignored) {
                        quality = 0.0;
                    }
                }
            }
            if (quality > 0.0) {
                return true;
            }
        }
        return false;
    }

    static String weakEtag(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            return "W/\"" + HexFormat.of().formatHex(digest, 0, 16) + "\"";
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Weak comparison as required for If-None-Match (RFC 9110 13.1.2). */
    static boolean matchesIfNoneMatch(String header, String etag) {
        if (header == null || header.isBlank()) {
            return false;
        }
        String opaque = stripWeak(etag);
        for (String candidate : header.split(",")) {
            String value = candidate.trim();
            if (value.equals("*") || stripWeak(value).equals(opaque)) {
                return true;
            }
        }
        return false;
    }

    private static String stripWeak(String tag) {
        return tag.startsWith("W/") ? tag.substring(2) : tag;
    }

    static byte[] gzip(byte[] body) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(64, body.length / 4));
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(body);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return buffer.toByteArray();
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
