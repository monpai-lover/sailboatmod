package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.construction.RoadCoreExclusion;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualRoadPlannerServiceTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void detourRejectsLongCrossingThatExceedsShortSpanThreshold() {
        assertTrue(ManualRoadPlannerService.allowsPierlessDetourCrossingForTest(8));
        assertFalse(ManualRoadPlannerService.allowsPierlessDetourCrossingForTest(9));
    }

    @Test
    void coreExclusionTestHelperUsesDefaultRoadCoreRadius() {
        Set<Long> columns = ManualRoadPlannerService.collectCoreExclusionColumnsForTest(
                List.of(new BlockPos(10, 64, 20)),
                List.of()
        );

        assertTrue(columns.contains(RoadCoreExclusion.columnKey(10 + RoadCoreExclusion.DEFAULT_RADIUS, 20)));
        assertFalse(columns.contains(RoadCoreExclusion.columnKey(10 + RoadCoreExclusion.DEFAULT_RADIUS + 1, 20)));
    }

    @Test
    void finalPathValidationRejectsBlockedColumnBetweenPathNodes() {
        assertFalse(ManualRoadPlannerService.validateFinalPlannedPathForTest(
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                Set.of(RoadCoreExclusion.columnKey(4, 0))
        ));
    }

    @Test
    void cachedPreviewValidationUnblocksOnlyEndpointsAndRejectsBlockedMiddleSegment() {
        List<BlockPos> path = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(6, 64, 4)
        );

        assertTrue(ManualRoadPlannerService.validateCachedPreviewPathForTest(
                path,
                Set.of(
                        RoadCoreExclusion.columnKey(0, 0),
                        RoadCoreExclusion.columnKey(6, 4)
                ),
                Set.of(),
                path.get(0),
                path.get(1)
        ));

        assertFalse(ManualRoadPlannerService.validateCachedPreviewPathForTest(
                path,
                Set.of(
                        RoadCoreExclusion.columnKey(0, 0),
                        RoadCoreExclusion.columnKey(4, 2),
                        RoadCoreExclusion.columnKey(6, 4)
                ),
                Set.of(),
                path.get(0),
                path.get(1)
        ));
    }

    @Test
    void currentTownValidationRequiresBothCachedTownsToStillExist() {
        NationSavedData data = new NationSavedData();
        TownRecord source = town("source");
        TownRecord target = town("target");
        data.putTown(source);

        assertFalse(ManualRoadPlannerService.cachedPreviewTownsExistForTest(data, source, target));

        data.putTown(target);
        assertTrue(ManualRoadPlannerService.cachedPreviewTownsExistForTest(data, source, target));
    }

    @Test
    void configRebuildFailureClearsPreparedPreviewNbt() {
        ItemStack stack = new ItemStack(Items.STICK);
        ManualRoadPlannerService.cachePreparedPreviewStateForTest(stack, "target", "road", "hash", 123L);

        ManualRoadPlannerService.clearConfigRebuildFailurePreviewStateForTest(UUID.randomUUID(), stack);

        assertFalse(ManualRoadPlannerService.hasPreparedPreviewStateForTest(stack));
    }

    private static TownRecord town(String townId) {
        return new TownRecord(
                townId,
                "nation",
                townId,
                UUID.randomUUID(),
                0L,
                "minecraft:overworld",
                new BlockPos(0, 64, 0).asLong(),
                "",
                "european"
        );
    }
}
