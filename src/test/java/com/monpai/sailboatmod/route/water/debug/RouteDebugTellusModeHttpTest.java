package com.monpai.sailboatmod.route.water.debug;

import com.google.gson.JsonParser;
import com.monpai.sailboatmod.route.water.WaterMidMode;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RouteDebugTellusModeHttpTest {
    private final HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach void stop() { RouteDebugServer.stop(); }

    @Test void pageOffersTheTellusProductionMode() throws Exception {
        start();
        assertTrue(get("/").body().contains("value=\"tellus\""), "the new Tellus mode must be selectable");
    }

    @Test void capabilitiesReportAnUnavailableTellusWorldWithoutNativeSampling() throws Exception {
        start();
        var response = get("/api/capabilities");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        var data = JsonParser.parseString(response.body()).getAsJsonObject();
        assertFalse(data.get("tellusAvailable").getAsBoolean());
        assertEquals("nbt", data.get("defaultMode").getAsString());
        assertFalse(data.get("tellusUnavailableReason").getAsString().isBlank());
    }

    @Test void unsupportedTellusIsRejectedBeforeDockEnumerationOrPlanning() throws Exception {
        start();
        assertJsonError(get("/api/route?a=0&b=1&mode=tellus"), "TELLUS_UNAVAILABLE");
    }

    @Test void misspelledModesDoNotSilentlyRunAnotherPlanner() throws Exception {
        start();
        assertJsonError(get("/api/route?a=0&b=1&mode=typo"), "UNKNOWN_MODE");
    }

    @Test void tellUsIsAWebModeAndDoesNotAppendANetworkEnumValue() throws Exception {
        var web = assertDoesNotThrow(() -> Class.forName("com.monpai.sailboatmod.route.water.debug.RouteDebugMode"));
        var parse = web.getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        Object tellus = parse.invoke(null, "tellus");
        assertNotNull(tellus);
        var planner = web.getDeclaredMethod("plannerMode");
        planner.setAccessible(true);
        assertEquals(WaterMidMode.NBT, planner.invoke(tellus));
        assertArrayEquals(new WaterMidMode[]{WaterMidMode.NOISE, WaterMidMode.NBT, WaterMidMode.HYBRID}, WaterMidMode.values());
        assertEquals(0, WaterMidMode.NOISE.id());
        assertEquals(1, WaterMidMode.NBT.id());
        assertEquals(2, WaterMidMode.HYBRID.id());
    }

    @Test void owningServerStoppingClosesTheDebugListener() throws Exception {
        start();
        var handler = assertDoesNotThrow(() -> RouteDebugServer.class.getMethod("onServerStopping", ServerStoppingEvent.class));
        handler.invoke(null, new ServerStoppingEvent(null));
        assertNull(RouteDebugServer.get(), "an old world's listener must not survive its server");
    }

    private static void assertJsonError(HttpResponse<String> response, String reason) {
        assertEquals(400, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        var data = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals("FAILED", data.get("status").getAsString());
        assertEquals(reason, data.get("reason").getAsString());
    }

    private void start() throws Exception { RouteDebugServer.start(null, "127.0.0.1", 0); }
    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + RouteDebugServer.get().port() + path))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
