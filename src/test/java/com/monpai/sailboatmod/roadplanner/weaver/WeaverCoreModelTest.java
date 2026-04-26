package com.monpai.sailboatmod.roadplanner.weaver;

import com.monpai.sailboatmod.roadplanner.weaver.geometry.WeaverLine;
import com.monpai.sailboatmod.roadplanner.weaver.model.WeaverRoadData;
import com.monpai.sailboatmod.roadplanner.weaver.model.WeaverRoadSegmentPlacement;
import com.monpai.sailboatmod.roadplanner.weaver.model.WeaverRoadSpan;
import com.monpai.sailboatmod.roadplanner.weaver.model.WeaverSpanType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WeaverCoreModelTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void lineFrameProjectsPointOntoSegment() {
        WeaverLine line = new WeaverLine(new Vec3(0, 64, 0), new Vec3(10, 64, 0));

        WeaverLine.Frame frame = line.getFrame(new Vec3(4, 70, 3));

        assertEquals(new Vec3(4, 64, 0), frame.closestPoint());
        assertEquals(new Vec3(1, 0, 0), frame.tangent0());
        assertEquals(new Vec3(0, 0, 1), frame.binormal0());
        assertEquals(0.4D, frame.globalT(), 0.0001D);
    }

    @Test
    void roadDataStoresImmutablePlacementLists() {
        WeaverRoadSegmentPlacement placement = new WeaverRoadSegmentPlacement(
                new BlockPos(0, 64, 0),
                List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0)));
        WeaverRoadSpan span = new WeaverRoadSpan(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), WeaverSpanType.BRIDGE);

        WeaverRoadData data = new WeaverRoadData(
                5,
                0,
                List.of(Blocks.STONE_BRICKS.defaultBlockState()),
                List.of(Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                List.of(placement),
                List.of(span),
                List.of(64),
                1L,
                2L);

        assertEquals(placement, data.roadSegmentList().get(0));
        assertEquals(span, data.spans().get(0));
        assertThrows(UnsupportedOperationException.class, () -> data.roadSegmentList().add(placement));
        assertThrows(UnsupportedOperationException.class, () -> data.targetY().add(65));
    }
}
