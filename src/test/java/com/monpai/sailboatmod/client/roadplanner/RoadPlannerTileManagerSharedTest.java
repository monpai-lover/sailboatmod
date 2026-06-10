package com.monpai.sailboatmod.client.roadplanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertSame;

class RoadPlannerTileManagerSharedTest {
    @Test
    void sharedDefaultReturnsTheSameInjectedManager(@TempDir Path tempDir) {
        RoadPlannerTileManager manager = RoadPlannerTileManager.forTest(
                tempDir.toFile(),
                "world_a",
                "minecraft:overworld");
        RoadPlannerTileManager.setSharedDefaultForTest(manager);

        try {
            assertSame(manager, RoadPlannerTileManager.sharedDefault());
            assertSame(manager, RoadPlannerTileManager.sharedDefault());
        } finally {
            RoadPlannerTileManager.clearSharedDefaultForTest();
        }
    }
}
