package com.monpai.sailboatmod.market.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-Sent Events for the market web page ({@code GET /api/events}).
 *
 * <p>The game thread calls {@link #publish} after something changes (a listing, a trade, an order, a wallet);
 * publishing never blocks: each connected page has a bounded queue and its own virtual thread that writes to
 * the socket, so a slow or dead connection is dropped instead of stalling the server. The HTTP handler returns
 * immediately after {@link #open}, so long-lived streams do not occupy the bounded request executor.
 *
 * <p>Events carry a monotonically increasing id; the last {@value #REPLAY_SIZE} public events are kept so a
 * reconnecting page that sends {@code Last-Event-ID} receives what it missed. Personal events (orders, wallet)
 * are only written to streams opened by that player and are not replayed.
 *
 * <p>The JDK server closes any response still being written after {@code sun.net.httpserver.maxRspTime}
 * seconds (MarketWebServer sets 120). Each stream therefore ends on its own shortly before that limit with a
 * {@code rotate} event, and the page reconnects at once with {@code Last-Event-ID}, so it never sees a broken
 * connection.
 */
public final class MarketWebEventHub {
    static final int REPLAY_SIZE = 256;
    static final int QUEUE_SIZE = 256;
    static final int DEFAULT_MAX_CLIENTS = 256;
    static final long HEARTBEAT_MILLIS = 20_000;
    static final long ROTATE_MARGIN_MILLIS = 20_000;
    static final long UNLIMITED_STREAM_MILLIS = 10 * 60_000;
    static final String ROTATE_FRAME = "event: rotate\ndata: {}\n\n";

    private record Event(long id, String type, String json, UUID audience) {
    }

    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<Event> replay = new ArrayDeque<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final int maxClients;
    private final long heartbeatMillis;
    private final long maxStreamMillis;
    private volatile boolean closed;

    public MarketWebEventHub() {
        this(DEFAULT_MAX_CLIENTS, HEARTBEAT_MILLIS, 0);
    }

    MarketWebEventHub(int maxClients, long heartbeatMillis) {
        this(maxClients, heartbeatMillis, 0);
    }

    /** {@code maxStreamMillis} 0 derives the stream lifetime from the JDK response time limit when a stream opens. */
    MarketWebEventHub(int maxClients, long heartbeatMillis, long maxStreamMillis) {
        this.maxClients = maxClients;
        this.heartbeatMillis = heartbeatMillis;
        this.maxStreamMillis = maxStreamMillis;
    }

    /** How long a stream may stay open under a {@code sun.net.httpserver.maxRspTime} value (seconds). */
    static long streamLifetimeMillis(String maxRspTimeSeconds) {
        long limit;
        try {
            limit = maxRspTimeSeconds == null ? -1 : Long.parseLong(maxRspTimeSeconds.trim()) * 1000;
        } catch (NumberFormatException ignored) {
            limit = -1;
        }
        if (limit <= 0) {
            return UNLIMITED_STREAM_MILLIS;
        }
        return limit > 2 * ROTATE_MARGIN_MILLIS ? limit - ROTATE_MARGIN_MILLIS : Math.max(1_000, limit / 2);
    }

    /** Broadcasts an event to every page. {@code json} must be a JSON object literal. */
    public void publish(String type, String json) {
        publish(type, json, null);
    }

    /** Sends an event only to the pages of {@code player}; a {@code null} player broadcasts. */
    public void publish(String type, String json, UUID player) {
        if (closed) {
            return;
        }
        Event event = new Event(nextId.getAndIncrement(), type, json, player);
        if (player == null) {
            synchronized (replay) {
                replay.addLast(event);
                while (replay.size() > REPLAY_SIZE) {
                    replay.removeFirst();
                }
            }
        }
        for (Client client : clients) {
            if (player == null || player.equals(client.viewer)) {
                client.offer(event);
            }
        }
    }

    /**
     * Opens a stream on {@code exchange} and returns at once; the stream is written from a virtual thread.
     * {@code viewer} is the signed-in player or {@code null} for guests (guests receive public events only).
     */
    public void open(HttpExchange exchange, UUID viewer) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        if (closed || clients.size() >= maxClients) {
            byte[] body = "{\"success\":false,\"error\":\"busy\",\"message\":\"Too many live connections\"}".getBytes(StandardCharsets.UTF_8);
            headers.set("Content-Type", "application/json; charset=utf-8");
            headers.set("Retry-After", "30");
            exchange.sendResponseHeaders(503, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            return;
        }
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache, no-transform");
        headers.set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);
        long lifetime = maxStreamMillis > 0 ? maxStreamMillis : streamLifetimeMillis(System.getProperty("sun.net.httpserver.maxRspTime"));
        Client client = new Client(exchange, viewer, System.currentTimeMillis() + lifetime);
        long lastSeen = parseLastEventId(exchange.getRequestHeaders().getFirst("Last-Event-ID"));
        if (lastSeen > 0) {
            synchronized (replay) {
                for (Event event : replay) {
                    if (event.id > lastSeen) {
                        client.offer(event);
                    }
                }
            }
        }
        clients.add(client);
        Thread.ofVirtual().name("marketweb-sse-", client.serial).start(client::pump);
    }

    public int clientCount() {
        return clients.size();
    }

    /** Closes every stream; call from MarketWebServer.stopInternal(). */
    public void close() {
        closed = true;
        for (Client client : new ArrayList<>(clients)) {
            client.shutdown();
        }
        clients.clear();
    }

    static long parseLastEventId(String header) {
        if (header == null) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(header.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static String frame(long id, String type, String json) {
        StringBuilder out = new StringBuilder(json.length() + 48);
        out.append("id: ").append(id).append('\n');
        out.append("event: ").append(type).append('\n');
        for (String line : json.split("\n", -1)) {
            out.append("data: ").append(line).append('\n');
        }
        return out.append('\n').toString();
    }

    private static final AtomicLong CLIENT_SERIAL = new AtomicLong();

    private final class Client {
        final long serial = CLIENT_SERIAL.incrementAndGet();
        final HttpExchange exchange;
        final UUID viewer;
        final long deadline;
        final BlockingQueue<Event> queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
        volatile boolean alive = true;

        Client(HttpExchange exchange, UUID viewer, long deadline) {
            this.exchange = exchange;
            this.viewer = viewer;
            this.deadline = deadline;
        }

        void offer(Event event) {
            if (alive && !queue.offer(event)) {
                // The page is not keeping up; drop it. It reconnects with Last-Event-ID and refetches.
                shutdown();
            }
        }

        void pump() {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(("retry: 3000\n: connected\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                while (alive) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        // End cleanly before the JDK cuts the response; the page reconnects with Last-Event-ID.
                        out.write(ROTATE_FRAME.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        break;
                    }
                    Event event = queue.poll(Math.min(heartbeatMillis, remaining), TimeUnit.MILLISECONDS);
                    List<Event> batch = new ArrayList<>();
                    if (event != null) {
                        batch.add(event);
                        queue.drainTo(batch, 31);
                    }
                    StringBuilder chunk = new StringBuilder();
                    if (batch.isEmpty()) {
                        chunk.append(": ping\n\n");
                    }
                    for (Event item : batch) {
                        chunk.append(frame(item.id, item.type, item.json));
                    }
                    out.write(chunk.toString().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException | InterruptedException disconnected) {
                // the page went away or the server is stopping
            } finally {
                alive = false;
                clients.remove(this);
                exchange.close();
            }
        }

        void shutdown() {
            alive = false;
            clients.remove(this);
            exchange.close();
        }
    }
}
