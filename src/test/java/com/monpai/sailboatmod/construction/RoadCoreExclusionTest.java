package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadCoreExclusionTest {
    @Test
    void packsFiveBlockSquareAroundEveryCore() {
        Set<Long> excluded = RoadCoreExclusion.collectExcludedColumns(
                List.of(new BlockPos(100, 64, 100)),
                5
        );

        assertTrue(excluded.contains(RoadCoreExclusion.columnKey(95, 95)));
        assertTrue(excluded.contains(RoadCoreExclusion.columnKey(105, 105)));
        assertTrue(excluded.contains(RoadCoreExclusion.columnKey(100, 100)));
        assertFalse(excluded.contains(RoadCoreExclusion.columnKey(106, 100)));
    }

    @Test
    void ignoresNullCoreEntriesWithoutShrinkingOtherExclusions() {
        Set<Long> excluded = RoadCoreExclusion.collectExcludedColumns(
                Arrays.asList(null, new BlockPos(0, 70, 0)),
                5
        );

        assertEquals(121, excluded.size());
        assertTrue(excluded.contains(RoadCoreExclusion.columnKey(5, 0)));
    }
}
