package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadTerrainShaperTest {
    @Test
    void createsFillStepsUnderRaisedRoadbed() {
        List<RoadTerrainShaper.TerrainEdit> edits = RoadTerrainShaper.shapeRoadbed(
                List.of(new BlockPos(0, 65, 0), new BlockPos(1, 66, 0), new BlockPos(2, 67, 0)),
                pos -> 63
        );

        assertTrue(edits.stream().anyMatch(edit -> edit.pos().equals(new BlockPos(1, 64, 0))));
        assertTrue(edits.stream().anyMatch(edit -> edit.pos().equals(new BlockPos(2, 65, 0))));
    }
}
