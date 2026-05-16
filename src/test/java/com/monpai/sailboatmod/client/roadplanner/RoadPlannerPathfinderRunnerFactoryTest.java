package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.construction.RoadCoreExclusion;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerPathfinderRunnerFactoryTest {
    @Test
    void corridorColumnsCoverOnlyTheFineSearchCorridor() {
        Set<Long> columns = RoadPlannerPathfinderRunnerFactory.corridorColumns(
                List.of(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0)),
                8,
                4
        );

        assertTrue(columns.contains(RoadCoreExclusion.columnKey(0, 0)));
        assertTrue(columns.contains(RoadCoreExclusion.columnKey(8, 4)));
        assertTrue(columns.contains(RoadCoreExclusion.columnKey(16, -8)));
        assertFalse(columns.contains(RoadCoreExclusion.columnKey(0, 12)));
        assertFalse(columns.contains(RoadCoreExclusion.columnKey(32, 0)));
    }
}
