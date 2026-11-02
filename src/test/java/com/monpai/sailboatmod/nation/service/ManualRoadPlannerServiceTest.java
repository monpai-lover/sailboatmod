package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualRoadPlannerServiceTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void airCleanupStepsAreNotVisiblePreviewBlocks() {
        BuildStep cleanup = new BuildStep(0, new BlockPos(0, 65, 0), Blocks.AIR.defaultBlockState(), BuildPhase.FOUNDATION);
        BuildStep surface = new BuildStep(1, new BlockPos(0, 64, 0), Blocks.DIRT_PATH.defaultBlockState(), BuildPhase.SURFACE);

        assertFalse(ManualRoadPlannerService.isVisibleRoadPreviewStep(cleanup));
        assertTrue(ManualRoadPlannerService.isVisibleRoadPreviewStep(surface));
    }

    @Test
    void detourRejectsLongCrossingThatExceedsShortSpanThreshold() {
        assertTrue(ManualRoadPlannerService.allowsPierlessDetourCrossingForTest(8));
        assertFalse(ManualRoadPlannerService.allowsPierlessDetourCrossingForTest(9));
    }

    @Test
    void cancellingUsesTheExistingPhysicalRollbackInsteadOfDeletingOnlyTheRoadRecord() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java"));
        String cancel = source.substring(source.indexOf("private static Component previewOrCancelRoad("),
                source.indexOf("static boolean isVisibleRoadPreviewStep("));
        assertTrue(cancel.contains("StructureConstructionManager.demolishRoadById("),
                "Cancel must stop the actual pending construction and restore owned blocks");
        assertFalse(cancel.contains("removeRoadNetwork("),
                "The construction lifecycle owns record removal after rollback, not the UI");
    }
}
