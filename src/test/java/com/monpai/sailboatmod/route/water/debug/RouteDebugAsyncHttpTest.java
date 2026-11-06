package com.monpai.sailboatmod.route.water.debug;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RouteDebugAsyncHttpTest {
    private final HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    @AfterEach void stop() { RouteDebugServer.stop(); }

    @Test void newJobEndpointsRequireThePublicTokenBeforeWorldWork() throws Exception {
        RouteDebugServer.start(null, "0.0.0.0", 0);
        for (String path : new String[]{"/api/route/start?a=0&b=1", "/api/route/jobs/missing", "/api/route/jobs/missing/cancel"}) {
            assertEquals(401, request(path, path.endsWith("/missing") ? "GET" : "POST").statusCode());
        }
    }

    @Test void pollingUnknownOrExpiredJobsReturnsJson404() throws Exception {
        RouteDebugServer.start(null, "127.0.0.1", 0);
        var response = request("/api/route/jobs/missing", "GET");
        assertEquals(404, response.statusCode());
        assertEquals("JOB_NOT_FOUND", JsonParser.parseString(response.body()).getAsJsonObject().get("reason").getAsString());
    }

    @Test void methodAndModeValidationPrecedeDockOrPlannerAccess() throws Exception {
        RouteDebugServer.start(null, "127.0.0.1", 0);
        assertEquals(405, request("/api/route/start?mode=nbt", "GET").statusCode());
        var typo = request("/api/route/start?mode=typo", "POST");
        assertEquals(400, typo.statusCode());
        assertEquals("UNKNOWN_MODE", JsonParser.parseString(typo.body()).getAsJsonObject().get("reason").getAsString());
        assertEquals(405, request("/api/route/jobs/missing/cancel", "GET").statusCode());
    }

    @Test void cachedFailedRouteKeepsItsActualReasonAndLogAcrossHttpPolls() throws Exception {
        RouteDebugServer.start(null, "127.0.0.1", 0);
        var job = jobs().submit(cancelled -> "{\"status\":\"FAILED\",\"reason\":\"TERRAIN_NOT_READY\",\"log\":\"diagnostic witness\"}");
        awaitTerminal(job.jobId());
        var first = request("/api/route/jobs/" + job.jobId(), "GET");
        var second = request("/api/route/jobs/" + job.jobId(), "GET");
        assertEquals(200, first.statusCode());
        assertEquals(first.body(), second.body());
        var data = JsonParser.parseString(first.body()).getAsJsonObject();
        assertEquals("FAILED", data.get("status").getAsString());
        assertEquals("TERRAIN_NOT_READY", data.get("reason").getAsString());
        assertEquals("diagnostic witness", data.getAsJsonObject("result").get("log").getAsString());
    }

    @Test void httpCancellationStopsTheActiveJobAndPreservesCancelledStatus() throws Exception {
        RouteDebugServer.start(null, "127.0.0.1", 0);
        var entered = new CountDownLatch(1);
        var job = jobs().submit(cancelled -> { entered.countDown(); new CountDownLatch(1).await(); return "{}"; });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var cancelled = request("/api/route/jobs/" + job.jobId() + "/cancel", "POST");
        assertEquals(200, cancelled.statusCode());
        assertEquals("CANCELLED", JsonParser.parseString(cancelled.body()).getAsJsonObject().get("status").getAsString());
        var status = request("/api/route/jobs/" + job.jobId(), "GET");
        assertEquals("CANCELLED", JsonParser.parseString(status.body()).getAsJsonObject().get("status").getAsString());
    }

    @Test void startQueuesImmediatelyBeforeAnyWorldMetadataOrRouteWork() throws Exception {
        RouteDebugServer.start(null, "127.0.0.1", 0);
        var entered = new CountDownLatch(1);
        jobs().submit(cancelled -> { entered.countDown(); new CountDownLatch(1).await(); return "{}"; });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        // No Minecraft world exists here. It must only be consulted later by the queued worker.
        var started = request("/api/route/start?a=0&b=1&mode=nbt", "POST");
        assertEquals(202, started.statusCode());
        var data = JsonParser.parseString(started.body()).getAsJsonObject();
        assertEquals("QUEUED", data.get("status").getAsString());
        assertTrue(data.get("jobId").getAsString().matches("[a-f0-9-]{36}"));
        assertEquals(200, request("/api/route/jobs/" + data.get("jobId").getAsString() + "/cancel", "POST").statusCode());
    }

    private static RouteDebugJobManager jobs() throws Exception {
        var field = RouteDebugServer.class.getDeclaredField("jobs");
        field.setAccessible(true);
        return (RouteDebugJobManager) field.get(RouteDebugServer.get());
    }

    private static void awaitTerminal(String id) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (jobs().get(id).status() == RouteDebugJobManager.Status.QUEUED || jobs().get(id).status() == RouteDebugJobManager.Status.RUNNING) {
            assertTrue(System.nanoTime() < until, "job must finish before polling its cached result");
            Thread.sleep(5);
        }
    }

    private HttpResponse<String> request(String path, String method) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + RouteDebugServer.get().port() + path))
                .timeout(Duration.ofSeconds(5)).method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
}
