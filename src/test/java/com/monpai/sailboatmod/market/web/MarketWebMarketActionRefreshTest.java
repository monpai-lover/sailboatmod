package com.monpai.sailboatmod.market.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MarketWebMarketActionRefreshTest {
    @Test
    void aCommittedActionWithPendingRefreshKeepsItsSuccessReceiptForRetries() {
        MarketWebIdempotency once = new MarketWebIdempotency();
        AtomicInteger mutations = new AtomicInteger();
        var action = (java.util.function.Supplier<MarketWebIdempotency.StoredResponse>) () -> {
            mutations.incrementAndGet();
            return MarketWebServer.afterMarketAction(() -> {
                throw new CompletionException(new MarketWebMarketResolver.UnavailableException(
                        "market_loading", "Please retry shortly.", 5));
            });
        };
        var first = once.execute("player purchase", "intent", action);
        var replay = once.execute("player purchase", "intent", action);
        assertEquals(200, first.status());
        assertTrue(JsonParser.parseString(new String(first.body(), StandardCharsets.UTF_8))
                .getAsJsonObject().get("ok").getAsBoolean());
        assertEquals(1, mutations.get());
        assertArrayEquals(first.body(), replay.body());
    }

    @Test
    void aReadyRefreshReturnsTheNormalMarketDetail() {
        JsonObject detail = new JsonObject();
        detail.addProperty("marketName", "Harbor Market");
        var response = MarketWebServer.afterMarketAction(() -> detail);
        assertEquals(200, response.status());
        assertEquals("Harbor Market", JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8))
                .getAsJsonObject().get("marketName").getAsString());
    }

    @Test
    void aPendingActionBeforeCommitCanRetryWithTheSameIntent() {
        MarketWebIdempotency once = new MarketWebIdempotency();
        AtomicInteger mutations = new AtomicInteger();
        assertThrows(MarketWebMarketResolver.UnavailableException.class, () -> once.execute("player purchase", "intent", () -> {
            throw new MarketWebMarketResolver.UnavailableException("market_loading", "Please retry shortly.", 5);
        }));
        var response = once.execute("player purchase", "intent", () -> {
            mutations.incrementAndGet();
            return MarketWebServer.afterMarketAction(() -> null);
        });
        assertEquals(200, response.status());
        assertEquals(1, mutations.get());
    }
}
