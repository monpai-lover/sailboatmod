package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rules for pushing fresh overviews to open market screens; see {@link MarketLiveSync}. */
class MarketLiveScheduleTest {
    private static final int INTERVAL = 20;

    @Test
    void newlyOpenedScreensAreAssumedCurrent() {
        MarketLiveSchedule<String> schedule = new MarketLiveSchedule<>(INTERVAL);
        assertNull(schedule.next(Map.of("alice", "harbor"), 5, 100), "opening a screen already sent an overview");
        assertNull(schedule.next(Map.of("alice", "harbor"), 5, 200), "nothing changed since");
    }

    @Test
    void aChangeReachesTheViewerOnceTheIntervalHasPassed() {
        MarketLiveSchedule<String> schedule = new MarketLiveSchedule<>(INTERVAL);
        schedule.sent("alice", "harbor", 5, 100);
        assertNull(schedule.next(Map.of("alice", "harbor"), 6, 110), "the same viewer at most once a second");
        assertEquals("alice", schedule.next(Map.of("alice", "harbor"), 6, 120));
        schedule.sent("alice", "harbor", 6, 120);
        assertNull(schedule.next(Map.of("alice", "harbor"), 6, 400), "no second push without a newer change");
    }

    @Test
    void oneViewerPerCallStalestFirst() {
        MarketLiveSchedule<String> schedule = new MarketLiveSchedule<>(INTERVAL);
        schedule.sent("alice", "harbor", 1, 100);
        schedule.sent("bob", "harbor", 1, 50);
        schedule.sent("carol", "dock", 1, 80);
        Map<String, String> open = Map.of("alice", "harbor", "bob", "harbor", "carol", "dock");
        assertEquals("bob", schedule.next(open, 2, 200));
        schedule.sent("bob", "harbor", 2, 200);
        assertEquals("carol", schedule.next(open, 2, 201));
        schedule.sent("carol", "dock", 2, 201);
        assertEquals("alice", schedule.next(open, 2, 202));
        schedule.sent("alice", "harbor", 2, 202);
        assertNull(schedule.next(open, 2, 203));
    }

    @Test
    void aReplyToTheViewersOwnActionCountsAsPushed() {
        MarketLiveSchedule<String> schedule = new MarketLiveSchedule<>(INTERVAL);
        schedule.next(Map.of("alice", "harbor"), 3, 0);
        // alice buys: the write bumps the revision to 4 and her reply already contains it
        schedule.sent("alice", "harbor", 4, 30);
        assertNull(schedule.next(Map.of("alice", "harbor"), 4, 100));
    }

    @Test
    void closedScreensAreForgottenAndReopeningStartsCurrent() {
        MarketLiveSchedule<String> schedule = new MarketLiveSchedule<>(INTERVAL);
        schedule.sent("alice", "harbor", 1, 0);
        schedule.sent("bob", "harbor", 1, 0);
        assertNull(schedule.next(Map.of("alice", "harbor"), 1, 10));
        assertEquals(1, schedule.trackedViewers(), "bob closed his screen");
        assertNull(schedule.next(Map.of("alice", "dock"), 9, 100), "a different market is a newly opened screen");
        schedule.clear();
        assertEquals(0, schedule.trackedViewers());
    }

    @Test
    void manyViewersStillCostOneOverviewPerTick() {
        MarketLiveSchedule<Integer> schedule = new MarketLiveSchedule<>(INTERVAL);
        Map<Integer, String> open = new HashMap<>();
        for (int viewer = 0; viewer < 50; viewer++) {
            open.put(viewer, "harbor");
            schedule.sent(viewer, "harbor", 0, 0);
        }
        int pushes = 0;
        for (long tick = 20; tick < 70; tick++) {
            Integer due = schedule.next(open, 1, tick);
            if (due != null) {
                schedule.sent(due, "harbor", 1, tick);
                pushes++;
            }
        }
        assertEquals(50, pushes, "every viewer gets the change, one per tick");
    }

    @Test
    void everyMarketWriteAndEveryActionReplyGoesThroughTheLiveSync() throws Exception {
        String savedData = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/MarketSavedData.java"));
        String wallets = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/wallet/MarketWalletSavedData.java"));
        String repository = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/commodity/CommodityMarketRepository.java"));
        assertTrue(savedData.contains("public void setDirty()") && savedData.contains("MarketChangeNotifier.changed()"));
        assertTrue(wallets.contains("public void setDirty()") && wallets.contains("MarketChangeNotifier.changed()"));
        assertEquals(3, Pattern.compile("MarketChangeNotifier\\.(changed|traded)\\(").matcher(repository).results().count(),
                "buy order create, buy order status and trade history writes");

        for (String packet : List.of("PurchaseMarketListing", "CreateMarketListing", "CancelMarketListing", "CancelPurchaseOrder",
                "DispatchMarketOrder", "ClaimMarketCredits", "CreateBuyOrder", "CancelBuyOrder", "MarketWalletAction",
                "RenameMarket", "MarketGuiAction")) {
            String source = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/network/packet/" + packet + "Packet.java"));
            assertTrue(source.contains("MarketLiveSync.sendOverview(player, market)"), packet + " answers through MarketLiveSync");
            assertTrue(!source.contains("new OpenMarketScreenPacket("), packet + " must not bypass the live schedule");
        }
        String events = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/ServerEvents.java"));
        assertTrue(events.contains("MarketLiveSync.tick(server)") && events.contains("MarketLiveSync.reset()"));
    }
}
