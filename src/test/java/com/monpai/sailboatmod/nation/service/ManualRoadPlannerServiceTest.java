package com.monpai.sailboatmod.nation.service;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualRoadPlannerServiceTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void duplicateManualRoadOnSameTownPairIsRejected() {
        assertTrue(ManualRoadPlannerService.manualRoadAlreadyExistsForTest(
                "manual|town:alpha|town:beta",
                Set.of("manual|town:alpha|town:beta")
        ));
    }

    @Test
    void plannerModeCyclesBuildCancelDemolish() {
        ItemStack stack = new ItemStack(net.minecraft.world.item.Items.STICK);

        assertEquals("BUILD", ManualRoadPlannerService.readPlannerModeForTest(stack).name());
        ManualRoadPlannerService.cyclePlannerModeForTest(stack);
        assertEquals("CANCEL", ManualRoadPlannerService.readPlannerModeForTest(stack).name());
        ManualRoadPlannerService.cyclePlannerModeForTest(stack);
        assertEquals("DEMOLISH", ManualRoadPlannerService.readPlannerModeForTest(stack).name());
    }

    @Test
    void manualRoadIdForTownPairUsesStableEdgeKey() {
        assertEquals(
                "manual|town:alpha|town:beta",
                ManualRoadPlannerService.manualRoadIdForTest("alpha", "beta")
        );
        assertEquals(
                "manual|town:alpha|town:beta",
                ManualRoadPlannerService.manualRoadIdForTest("beta", "alpha")
        );
    }

    @Test
    void cyclingPlannerModeClearsPendingPreviewConfirmationState() {
        ItemStack stack = new ItemStack(net.minecraft.world.item.Items.STICK);
        stack.getOrCreateTag().putString("PreviewRoadId", "manual|town:a|town:b");
        stack.getOrCreateTag().putString("PreviewHash", "preview-hash");

        ManualRoadPlannerService.cyclePlannerModeForTest(stack);

        assertFalse(stack.getOrCreateTag().contains("PreviewRoadId"));
        assertFalse(stack.getOrCreateTag().contains("PreviewHash"));
        assertEquals("CANCEL", ManualRoadPlannerService.readPlannerModeForTest(stack).name());
    }

    @Test
    void strictManualPlanningRejectsFallbackWhenStationPairIsMissing() {
        ManualRoadPlannerService.ManualPlanFailure failure =
                ManualRoadPlannerService.validateStrictPostStationRoute(false, true, false, false);

        assertEquals(ManualRoadPlannerService.ManualPlanFailure.SOURCE_STATION_MISSING, failure);
    }

    @Test
    void strictManualPlanningRejectsMissingTargetExit() {
        ManualRoadPlannerService.ManualPlanFailure failure =
                ManualRoadPlannerService.validateStrictPostStationRoute(true, true, true, false);

        assertEquals(ManualRoadPlannerService.ManualPlanFailure.TARGET_EXIT_MISSING, failure);
    }

    @Test
    void strictManualPlanningAllowsOnlyFullyResolvedWaitingAreaRoute() {
        ManualRoadPlannerService.ManualPlanFailure failure =
                ManualRoadPlannerService.validateStrictPostStationRoute(true, true, true, true);

        assertEquals(ManualRoadPlannerService.ManualPlanFailure.NONE, failure);
    }

    @Test
    void waitingAreaRouteValidationDoesNotRequireExitsBeforeTheyAreResolved() {
        ManualRoadPlannerService.ManualPlanFailure failure =
                ManualRoadPlannerService.validateWaitingAreaRouteStationsForTest(true, true);

        assertEquals(ManualRoadPlannerService.ManualPlanFailure.NONE, failure);
    }

    @Test
    void unblocksChosenStationWaitingAreaAndExitColumns() {
        Set<Long> blocked = ManualRoadPlannerService.unblockStationFootprint(
                Set.of(ManualRoadPlannerService.columnKeyForTest(100, 100),
                        ManualRoadPlannerService.columnKeyForTest(101, 100),
                        ManualRoadPlannerService.columnKeyForTest(102, 100)),
                Set.of(new BlockPos(100, 64, 100), new BlockPos(101, 64, 100)),
                new BlockPos(102, 64, 100)
        );

        assertFalse(blocked.contains(ManualRoadPlannerService.columnKeyForTest(100, 100)));
        assertFalse(blocked.contains(ManualRoadPlannerService.columnKeyForTest(101, 100)));
        assertFalse(blocked.contains(ManualRoadPlannerService.columnKeyForTest(102, 100)));
    }
}
