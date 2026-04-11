package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostStationRoadAnchorHelperTest {
    @Test
    void choosesExitFacingTargetDirection() {
        PostStationRoadAnchorHelper.Zone zone =
                new PostStationRoadAnchorHelper.Zone(new BlockPos(100, 64, 100), -3, 5, -2, 2);

        List<BlockPos> exits = PostStationRoadAnchorHelper.computeExitCandidates(zone);
        BlockPos chosen = PostStationRoadAnchorHelper.chooseBestExit(exits, new BlockPos(140, 64, 102), zone.origin());

        assertEquals(new BlockPos(106, 64, 102), chosen);
    }

    @Test
    void fallsBackWhenNoExitCandidatesExist() {
        BlockPos fallback = new BlockPos(88, 70, 91);

        assertEquals(fallback, PostStationRoadAnchorHelper.chooseBestExit(List.of(), new BlockPos(120, 70, 91), fallback));
    }

    @Test
    void oppositeTargetsChooseOppositeWaitingAreaExits() {
        PostStationRoadAnchorHelper.Zone zone =
                new PostStationRoadAnchorHelper.Zone(new BlockPos(200, 64, 200), -2, 2, -1, 1);

        List<BlockPos> exits = PostStationRoadAnchorHelper.computeExitCandidates(zone);

        assertEquals(
                new BlockPos(203, 64, 200),
                PostStationRoadAnchorHelper.chooseBestExit(exits, new BlockPos(240, 64, 200), zone.origin())
        );
        assertEquals(
                new BlockPos(198, 64, 200),
                PostStationRoadAnchorHelper.chooseBestExit(exits, new BlockPos(160, 64, 200), zone.origin())
        );
    }
}
