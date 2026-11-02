package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManualRoadDemolitionSelectorTest {
    @Test
    void picksNearestRoadWhenHitPointFallsBesideTheCenterLine() {
        RoadNetworkRecord eastWest = ManualRoadDemolitionSelector.roadForTest(
                "manual|a|b",
                List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0), new BlockPos(2, 64, 0))
        );

        RoadNetworkRecord selected = ManualRoadDemolitionSelector.selectRoadForTest(
                new Vec3(1.2D, 64.0D, 0.9D),
                new Vec3(1.0D, 0.0D, 0.0D),
                List.of(eastWest),
                2.0D
        );

        assertEquals("manual|a|b", selected.roadId());
    }

    @Test
    void prefersRoadAlignedWithViewDirectionWhenTwoRoadsOverlapSelectionRadius() {
        RoadNetworkRecord eastWest = ManualRoadDemolitionSelector.roadForTest(
                "manual|a|b",
                List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0), new BlockPos(2, 64, 0))
        );
        RoadNetworkRecord northSouth = ManualRoadDemolitionSelector.roadForTest(
                "manual|c|d",
                List.of(new BlockPos(1, 64, -1), new BlockPos(1, 64, 0), new BlockPos(1, 64, 1))
        );

        RoadNetworkRecord selected = ManualRoadDemolitionSelector.selectRoadForTest(
                new Vec3(1.0D, 64.0D, 0.4D),
                new Vec3(0.0D, 0.0D, 1.0D),
                List.of(eastWest, northSouth),
                2.0D
        );

        assertEquals("manual|c|d", selected.roadId());
    }
}
