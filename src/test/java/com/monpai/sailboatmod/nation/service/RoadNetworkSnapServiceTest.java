package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadNetworkSnapServiceTest {
    @Test
    void prefersParallelCompletedRoadEdgeWithinSnapThreshold() {
        BlockPos source = new BlockPos(0, 64, 2);
        BlockPos target = new BlockPos(6, 64, 2);
        List<BlockPos> snapped = RoadNetworkSnapService.snapPathForTest(
                List.of(source, new BlockPos(3, 64, 2), target),
                List.of(
                        new RoadNetworkRecord(
                                "manual|town:a|town:b",
                                "nation",
                                "town",
                                "minecraft:overworld",
                                "town:a",
                                "town:b",
                                List.of(
                                        new BlockPos(0, 64, 0),
                                        new BlockPos(3, 64, 0),
                                        new BlockPos(6, 64, 0)
                                ),
                                1L,
                                RoadNetworkRecord.SOURCE_TYPE_MANUAL
                        )
                )
        );

        assertEquals(new BlockPos(0, 64, 0), snapped.get(0));
        assertEquals(new BlockPos(6, 64, 0), snapped.get(snapped.size() - 1));
    }

    @Test
    void rejectsSnapWhenNearbyRoadRunsPerpendicularToRoute() {
        List<BlockPos> snapped = RoadNetworkSnapService.snapPathForTest(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(3, 64, 0),
                        new BlockPos(6, 64, 0)
                ),
                List.of(
                        new RoadNetworkRecord(
                                "manual|town:c|town:d",
                                "nation",
                                "town",
                                "minecraft:overworld",
                                "town:c",
                                "town:d",
                                List.of(
                                        new BlockPos(3, 64, -3),
                                        new BlockPos(3, 64, 0),
                                        new BlockPos(3, 64, 3)
                                ),
                                1L,
                                RoadNetworkRecord.SOURCE_TYPE_MANUAL
                        )
                )
        );

        assertEquals(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(3, 64, 0),
                        new BlockPos(6, 64, 0)
                ),
                snapped
        );
    }

    @Test
    void returnsContinuousRoadSubpathWhenBothEndpointsSnapToSameRoad() {
        List<BlockPos> snapped = RoadNetworkSnapService.snapPathForTest(
                List.of(
                        new BlockPos(0, 64, 2),
                        new BlockPos(3, 64, 2),
                        new BlockPos(6, 64, 2)
                ),
                List.of(
                        new RoadNetworkRecord(
                                "manual|town:e|town:f",
                                "nation",
                                "town",
                                "minecraft:overworld",
                                "town:e",
                                "town:f",
                                List.of(
                                        new BlockPos(0, 64, 0),
                                        new BlockPos(1, 64, 0),
                                        new BlockPos(2, 64, 0),
                                        new BlockPos(3, 64, 0),
                                        new BlockPos(4, 64, 0),
                                        new BlockPos(5, 64, 0),
                                        new BlockPos(6, 64, 0)
                                ),
                                1L,
                                RoadNetworkRecord.SOURCE_TYPE_MANUAL
                        )
                )
        );

        assertEquals(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 64, 0),
                        new BlockPos(3, 64, 0),
                        new BlockPos(4, 64, 0),
                        new BlockPos(5, 64, 0),
                        new BlockPos(6, 64, 0)
                ),
                snapped
        );
    }
}
