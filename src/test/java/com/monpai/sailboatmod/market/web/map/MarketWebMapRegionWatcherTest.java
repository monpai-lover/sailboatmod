package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MarketWebMapRegionWatcherTest {
    @Test
    void parsesValidRegionFileName() {
        long packed = MarketWebMapRegionWatcher.parseRegionFileName("r.-1.2.mca");
        assertNotEquals(Long.MIN_VALUE, packed);
        assertEquals(-1, MarketWebMapRegionWatcher.unpackRegionX(packed));
        assertEquals(2, MarketWebMapRegionWatcher.unpackRegionZ(packed));
    }

    @Test
    void parsesPositiveAndZeroCoordinates() {
        long packed = MarketWebMapRegionWatcher.parseRegionFileName("r.0.0.mca");
        assertEquals(0, MarketWebMapRegionWatcher.unpackRegionX(packed));
        assertEquals(0, MarketWebMapRegionWatcher.unpackRegionZ(packed));

        long packed2 = MarketWebMapRegionWatcher.parseRegionFileName("r.5.-7.mca");
        assertEquals(5, MarketWebMapRegionWatcher.unpackRegionX(packed2));
        assertEquals(-7, MarketWebMapRegionWatcher.unpackRegionZ(packed2));
    }

    @Test
    void rejectsNonRegionFileNames() {
        assertEquals(Long.MIN_VALUE, MarketWebMapRegionWatcher.parseRegionFileName("poi.0.0.mca"));
        assertEquals(Long.MIN_VALUE, MarketWebMapRegionWatcher.parseRegionFileName("r.0.0.dat"));
        assertEquals(Long.MIN_VALUE, MarketWebMapRegionWatcher.parseRegionFileName("r.mca"));
        assertEquals(Long.MIN_VALUE, MarketWebMapRegionWatcher.parseRegionFileName("r.a.b.mca"));
        assertEquals(Long.MIN_VALUE, MarketWebMapRegionWatcher.parseRegionFileName(null));
    }

    @Test
    void drainsOnlyTheRequestedBudgetAndLeavesTheRestQueued() {
        MarketWebMapRegionWatcher watcher = new MarketWebMapRegionWatcher();
        for (int i = 0; i < 5; i++) {
            watcher.addDirtyRegion(MarketWebMapRegionWatcher.packRegion(i, -i));
        }
        assertEquals(2, watcher.drainDirtyRegions(2).size());
        assertEquals(3, watcher.drainDirtyRegions(10).size());
    }

    @Test
    void capsPendingRegionEvents() {
        MarketWebMapRegionWatcher watcher = new MarketWebMapRegionWatcher();
        for (int i = 0; i < MarketWebMapRegionWatcher.MAX_DIRTY_REGIONS + 100; i++) {
            watcher.addDirtyRegion(MarketWebMapRegionWatcher.packRegion(i, 0));
        }
        assertEquals(MarketWebMapRegionWatcher.MAX_DIRTY_REGIONS,
                watcher.drainDirtyRegions(Integer.MAX_VALUE).size());
    }

    @Test
    void packRoundTripsAcrossSignedRange() {
        int[] samples = {0, 1, -1, 123, -123, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int x : samples) {
            for (int z : samples) {
                long packed = MarketWebMapRegionWatcher.packRegion(x, z);
                assertEquals(x, MarketWebMapRegionWatcher.unpackRegionX(packed));
                assertEquals(z, MarketWebMapRegionWatcher.unpackRegionZ(packed));
            }
        }
    }
}
