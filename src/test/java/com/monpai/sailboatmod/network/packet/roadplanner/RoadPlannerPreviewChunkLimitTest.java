package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerPreviewChunkLimitTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static RoadPlannerPreviewRequestPacket straightRoad(int length) {
        return new RoadPlannerPreviewRequestPacket("A", "B",
                List.of(new BlockPos(0, 64, 0), new BlockPos(length, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD));
    }

    @Test
    void corridorCoversTheRoadAndItsNeighbouringChunks() {
        var chunks = straightRoad(160).pathChunks();
        for (int chunkX = -1; chunkX <= 11; chunkX++) {
            for (int chunkZ = -1; chunkZ <= 1; chunkZ++) {
                assertTrue(chunks.contains(net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ)), chunkX + "," + chunkZ);
            }
        }
    }

    @Test
    void roadsBeyondTheOldChunkLimitAreAcceptedForStagedPlanning() {
        assertFalse(straightRoad(10_000).tooLongForPreview());
        // The old code loaded the first 6,000 chunks and sampled the rest as world bottom, burying the road.
        assertFalse(straightRoad(40_000).tooLongForPreview());
    }

    @Test
    void veryDistantEndpointsDoNotMaterializeTheirWholeChunkCorridor() {
        RoadPlannerPreviewRequestPacket road = new RoadPlannerPreviewRequestPacket("A", "B",
                List.of(new BlockPos(29_000_000, 64, 0), new BlockPos(29_160_000, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD));

        assertTrue(road.pathChunks().size() <= RoadPlannerPreviewRequestPacket.MAX_PREVIEW_CHUNKS + 1,
                "rejected roads may retain at most one overflow chunk, not the complete remote corridor");
        assertFalse(road.tooLongForPreview(), "remote roads are streamed one window at a time");
    }

    @Test
    void chunkEnumerationStopsAtTheLimitEvenWhenThePathFitsTheWorkBudget() {
        RoadPlannerPreviewRequestPacket diagonal = new RoadPlannerPreviewRequestPacket("A", "B",
                List.of(new BlockPos(0, 64, 0), new BlockPos(20_000, 64, 20_000)),
                List.of(RoadPlannerSegmentType.ROAD));

        assertTrue(diagonal.pathChunks().size() <= RoadPlannerPreviewRequestPacket.MAX_PREVIEW_CHUNKS + 1,
                "a diagonal corridor must stop collecting chunks immediately at the overflow marker");
        assertFalse(diagonal.tooLongForPreview());
    }

    @Test
    void overlappingSegmentsRemainOrderedWithoutAnEagerCumulativeExpansion() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos end = new BlockPos(10_000, 64, 0);
        RoadPlannerPreviewRequestPacket repeated = new RoadPlannerPreviewRequestPacket("A", "B",
                List.of(start, end, start, end, start), List.of());

        assertFalse(repeated.tooLongForPreview(), "repeated legs are processed in bounded stages too");
    }

    @Test
    void diagonalSegmentsUseTheirFullInterpolationLengthForTheWorkBudget() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos end = new BlockPos(8_000, 64, 8_000);
        RoadPlannerPreviewRequestPacket repeated = new RoadPlannerPreviewRequestPacket("A", "B",
                List.of(start, end, start, end), List.of());

        assertFalse(repeated.tooLongForPreview(), "diagonal interpolation is bounded per stage");
    }

    @Test
    void excessiveRepeatedNodesCannotCreateUnboundedPerNodeWork() {
        RoadPlannerPreviewRequestPacket repeated = new RoadPlannerPreviewRequestPacket("A", "B",
                java.util.Collections.nCopies(40_000, new BlockPos(0, 64, 0)), List.of());

        assertTrue(repeated.tooLongForPreview(), "even zero-length segments require bounded validation work");
    }

    @Test
    void normalThirtyThousandBlockRoadRemainsAvailable() {
        assertFalse(straightRoad(30_000).tooLongForPreview());
        var chunks = straightRoad(30_000).pathChunks();
        assertTrue(chunks.contains(net.minecraft.world.level.ChunkPos.asLong(1_876, 0)),
                "the accepted road still includes its final neighbouring chunk");
    }

    @Test
    void tellusPreviewBeyondThirtyMillionUsesThePackedPositionRange() {
        RoadPlannerPreviewRequestPacket road = new RoadPlannerPreviewRequestPacket("A", "B",
                List.of(new BlockPos(33_431_923, 64, 0), new BlockPos(33_471_923, 64, 0)), List.of());
        assertFalse(road.tooLongForPreview());
    }
}
