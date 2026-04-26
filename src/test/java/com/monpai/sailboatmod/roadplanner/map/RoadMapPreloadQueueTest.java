package com.monpai.sailboatmod.roadplanner.map;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadMapPreloadQueueTest {
    @Test
    void queueOrdersRegionsByPriority() {
        RoadMapRegion region = RoadMapRegion.centeredOn(BlockPos.ZERO, 128, MapLod.LOD_4);
        RoadMapPreloadQueue queue = new RoadMapPreloadQueue();
        UUID requestId = UUID.randomUUID();

        queue.replaceRequest(requestId, List.of(
                new RoadMapCorridorRegion(region, RoadMapRegionPriority.ROUGH_PATH),
                new RoadMapCorridorRegion(region, RoadMapRegionPriority.CURRENT),
                new RoadMapCorridorRegion(region, RoadMapRegionPriority.MANUAL_ROUTE)
        ));

        assertEquals(RoadMapRegionPriority.CURRENT, queue.poll().orElseThrow().priority());
        assertEquals(RoadMapRegionPriority.MANUAL_ROUTE, queue.poll().orElseThrow().priority());
        assertEquals(RoadMapRegionPriority.ROUGH_PATH, queue.poll().orElseThrow().priority());
    }

    @Test
    void replacingRequestCancelsOldEntries() {
        RoadMapRegion region = RoadMapRegion.centeredOn(BlockPos.ZERO, 128, MapLod.LOD_4);
        RoadMapPreloadQueue queue = new RoadMapPreloadQueue();
        UUID oldRequest = UUID.randomUUID();
        UUID newRequest = UUID.randomUUID();

        queue.replaceRequest(oldRequest, List.of(new RoadMapCorridorRegion(region, RoadMapRegionPriority.CURRENT)));
        queue.replaceRequest(newRequest, List.of(new RoadMapCorridorRegion(region, RoadMapRegionPriority.DESTINATION)));

        RoadMapPreloadQueue.Entry entry = queue.poll().orElseThrow();
        assertEquals(newRequest, entry.requestId());
        assertFalse(entry.cancelled());
        assertTrue(queue.poll().isEmpty());
    }
}
