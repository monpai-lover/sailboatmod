package com.monpai.sailboatmod.market.web;

import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SSE hub tests against a real com.sun.net.httpserver with a single-threaded request executor. */
class MarketWebEventHubTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void queueOverflowNeverClosesABlockedSocketOnThePublishingThread() throws Exception {
        MarketWebEventHub hub = new MarketWebEventHub(4, 20_000, 60_000);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
        ExecutorService publisher = Executors.newSingleThreadExecutor();
        server.setExecutor(requests);
        server.createContext("/api/events", exchange -> hub.open(exchange, null));
        server.start();
        Socket socket = new Socket();
        try {
            socket.setReceiveBufferSize(1024);
            socket.setSoTimeout(5000);
            socket.connect(server.getAddress());
            // Keep the request body incomplete, and stop reading after the handshake. HttpExchange.close()
            // can block both draining this body and flushing an SSE response against socket backpressure.
            socket.getOutputStream().write(("GET /api/events HTTP/1.1\r\nHost: localhost\r\nContent-Length: 65536\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            BufferedReader lines = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            do {
                line = lines.readLine();
                assertTrue(line != null, "SSE stream must complete its initial handshake");
            } while (!line.equals(": connected"));
            Future<?> publication = publisher.submit(() -> {
                String payload = "{\"padding\":\"" + "x".repeat(131_072) + "\"}";
                for (int index = 0; index < 2000; index++) {
                    hub.publish("market", payload);
                }
            });
            publication.get(2, TimeUnit.SECONDS);
            assertEquals(0, hub.clientCount(), "Overflowed clients must be removed even while socket close is blocked");
        } finally {
            socket.close();
            hub.close();
            server.stop(0);
            publisher.shutdownNow();
            requests.shutdownNow();
        }
    }

    /** Reads an SSE response line by line on its own thread. */
    private static final class Probe {
        final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        final CompletableFuture<HttpResponse<Stream<String>>> response;
        volatile boolean ended;
        volatile boolean broken;

        Probe(HttpClient client, String url, String viewer, String lastEventId) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
            if (viewer != null) {
                builder.header("X-Test-Viewer", viewer);
            }
            if (lastEventId != null) {
                builder.header("Last-Event-ID", lastEventId);
            }
            response = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofLines());
            response.thenAccept(res -> Thread.ofVirtual().start(() -> {
                try {
                    res.body().forEach(lines::add);
                } catch (RuntimeException failure) {
                    // stream cancelled or cut
                    broken = true;
                } finally {
                    ended = true;
                }
            }));
        }

        int status() throws Exception {
            return response.get(5, TimeUnit.SECONDS).statusCode();
        }

        boolean await(Predicate<String> match, long millis) throws InterruptedException {
            long deadline = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < deadline) {
                String line = lines.poll(50, TimeUnit.MILLISECONDS);
                if (line != null && match.test(line)) {
                    return true;
                }
            }
            return false;
        }

        boolean sawWithin(String needle, long millis) throws InterruptedException {
            return await(line -> line.contains(needle), millis);
        }
    }

    @Test
    void streamsDeliverReplayRotateAndNeverBlockTheExecutor() throws Exception {
        MarketWebEventHub hub = new MarketWebEventHub(3, 300);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService singleThread = Executors.newSingleThreadExecutor();
        server.setExecutor(singleThread);
        server.createContext("/api/events", exchange -> {
            String viewer = exchange.getRequestHeaders().getFirst("X-Test-Viewer");
            hub.open(exchange, viewer == null ? null : UUID.fromString(viewer));
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/events";
        HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
        try {
            Probe alice = new Probe(client, url, ALICE.toString(), null);
            Probe bob = new Probe(client, url, BOB.toString(), null);
            boolean connected = alice.status() == 200 && bob.status() == 200
                    && alice.sawWithin(": connected", 2000) && bob.sawWithin(": connected", 2000);
            check("H1 two streams open on a single-threaded executor (handler does not block)", connected, "clients=" + hub.clientCount());

            hub.publish("market", "{\"marketId\":\"m1\",\"revision\":7}");
            boolean aliceGot = alice.sawWithin("{\"marketId\":\"m1\",\"revision\":7}", 2000);
            boolean bobGot = bob.sawWithin("{\"marketId\":\"m1\",\"revision\":7}", 2000);
            check("H2 public events reach every page", aliceGot && bobGot, "alice=" + aliceGot + " bob=" + bobGot);

            hub.publish("order", "{\"orderId\":\"PO-1\",\"status\":\"DELIVERED\"}", ALICE);
            boolean aliceOrder = alice.sawWithin("PO-1", 2000);
            boolean bobOrder = bob.sawWithin("PO-1", 500);
            check("H3 personal events only reach that player", aliceOrder && !bobOrder, "alice=" + aliceOrder + " bob=" + bobOrder);

            boolean ping = alice.sawWithin(": ping", 1500);
            check("H4 idle streams get heartbeats", ping, "no ping");

            hub.publish("trade", "{\"n\":1}");
            hub.publish("trade", "{\"n\":2}");
            hub.publish("trade", "{\"n\":3}");
            // event ids so far: 1 market, 2 order (personal, not replayed), 3..5 trades
            Probe late = new Probe(client, url, null, "3");
            boolean replayed = late.status() == 200 && late.sawWithin("{\"n\":2}", 2000) && late.sawWithin("{\"n\":3}", 2000);
            check("H5 Last-Event-ID replays missed public events", replayed, "replay failed");

            Probe overflow = new Probe(client, url, null, null);
            check("H6 connections beyond the cap get 503", overflow.status() == 503, "status=" + overflow.status());

            hub.close();
            Thread.sleep(400);
            check("H7 close() ends every stream", alice.ended && bob.ended && hub.clientCount() == 0,
                    "aliceEnded=" + alice.ended + " bobEnded=" + bob.ended + " clients=" + hub.clientCount());

            check("H8 frame() splits multi-line data", MarketWebEventHub.frame(9, "x", "a\nb").equals("id: 9\nevent: x\ndata: a\ndata: b\n\n"),
                    MarketWebEventHub.frame(9, "x", "a\nb"));

            MarketWebEventHub rotating = new MarketWebEventHub(3, 60_000, 1_200);
            server.createContext("/api/rotating", exchange -> rotating.open(exchange, null));
            long started = System.currentTimeMillis();
            Probe rotated = new Probe(client, url.replace("/api/events", "/api/rotating"), null, null);
            boolean sawRotate = rotated.status() == 200 && rotated.sawWithin("event: rotate", 5000);
            long elapsed = System.currentTimeMillis() - started;
            Thread.sleep(300);
            check("H9 streams end cleanly with a rotate event before the response time limit",
                    sawRotate && rotated.ended && !rotated.broken && elapsed >= 1_000 && rotating.clientCount() == 0,
                    "rotate=" + sawRotate + " ended=" + rotated.ended + " broken=" + rotated.broken + " elapsed=" + elapsed);

            check("H10 stream lifetime stays under the JDK limit",
                    MarketWebEventHub.streamLifetimeMillis("120") == 100_000
                            && MarketWebEventHub.streamLifetimeMillis("30") == 15_000
                            && MarketWebEventHub.streamLifetimeMillis("-1") == MarketWebEventHub.UNLIMITED_STREAM_MILLIS
                            && MarketWebEventHub.streamLifetimeMillis(null) == MarketWebEventHub.UNLIMITED_STREAM_MILLIS
                            && MarketWebEventHub.streamLifetimeMillis("abc") == MarketWebEventHub.UNLIMITED_STREAM_MILLIS,
                    "120s -> " + MarketWebEventHub.streamLifetimeMillis("120"));
        } finally {
            server.stop(0);
            singleThread.shutdownNow();
        }
    }

    private static void check(String name, boolean condition, String detail) {
        assertTrue(condition, name + " — " + detail);
    }
}
