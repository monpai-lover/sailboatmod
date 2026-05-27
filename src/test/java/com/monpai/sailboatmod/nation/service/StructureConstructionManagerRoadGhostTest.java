package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.construction.RoadGeometryPlanner;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureConstructionManagerRoadGhostTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void roadConstructionGhostsHideOperationalCleanupButBuildStepsKeepThem() {
        List<BuildStep> steps = List.of(
                new BuildStep(0, new BlockPos(0, 66, 0), Blocks.AIR.defaultBlockState(), BuildPhase.FOUNDATION),
                new BuildStep(1, new BlockPos(0, 63, 0), Blocks.DIRT.defaultBlockState(), BuildPhase.FOUNDATION),
                new BuildStep(2, new BlockPos(0, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState(), BuildPhase.RAMP),
                new BuildStep(3, new BlockPos(1, 64, 0), Blocks.STONE_BRICKS.defaultBlockState(), BuildPhase.DECK),
                new BuildStep(4, new BlockPos(1, 65, 2), Blocks.OAK_FENCE.defaultBlockState(), BuildPhase.RAILING),
                new BuildStep(5, new BlockPos(1, 66, 2), Blocks.LANTERN.defaultBlockState(), BuildPhase.STREETLIGHT)
        );

        List<RoadGeometryPlanner.GhostRoadBlock> ghosts = StructureConstructionManager.visibleRoadGhostBlocksForTest(steps);
        List<RoadGeometryPlanner.RoadBuildStep> buildSteps = StructureConstructionManager.roadBuildStepsForTest(steps);

        assertEquals(4, ghosts.size());
        assertFalse(ghosts.stream().anyMatch(block -> block.state().isAir()));
        assertFalse(ghosts.stream().anyMatch(block -> block.pos().equals(new BlockPos(0, 63, 0))));
        assertTrue(ghosts.stream().anyMatch(block -> block.pos().equals(new BlockPos(0, 64, 0))));
        assertEquals(steps.size(), buildSteps.size(), "hidden cleanup/support steps still need to execute during real construction");
        assertEquals(RoadGeometryPlanner.RoadBuildPhase.SUPPORT, buildSteps.get(0).phase());
        assertEquals(RoadGeometryPlanner.RoadBuildPhase.SUPPORT, buildSteps.get(1).phase());
        assertEquals(RoadGeometryPlanner.RoadBuildPhase.DECK, buildSteps.get(2).phase());
        assertEquals(RoadGeometryPlanner.RoadBuildPhase.DECOR, buildSteps.get(5).phase());
    }

    @Test
    void roadBuildPlacementWritesThePlannedPreviewStateDirectly() {
        TestServerLevel level = newTestLevel();
        BlockPos pos = new BlockPos(0, 64, 0);
        level.blockStates.put(pos.asLong(), Blocks.GRASS_BLOCK.defaultBlockState());
        RoadGeometryPlanner.RoadBuildStep step = new RoadGeometryPlanner.RoadBuildStep(
                0,
                pos,
                Blocks.SMOOTH_STONE.defaultBlockState(),
                RoadGeometryPlanner.RoadBuildPhase.SURFACE
        );

        assertTrue(StructureConstructionManager.placePlannedRoadBuildStepForTest(level, step));

        assertEquals(Blocks.SMOOTH_STONE.defaultBlockState(), level.getBlockState(pos));
    }

    private static TestServerLevel newTestLevel() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        return level;
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Unsafe unsafe = (Unsafe) field.get(null);
            return (T) unsafe.allocateInstance(type);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class TestServerLevel extends ServerLevel {
        private Map<Long, BlockState> blockStates;

        private TestServerLevel() {
            super(null, command -> { }, null, null, null, null, null, false, 0L, List.of(), false, null);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return blockStates.getOrDefault(pos.asLong(), Blocks.AIR.defaultBlockState());
        }

        @Override
        public boolean setBlock(BlockPos pos, BlockState state, int flags) {
            blockStates.put(pos.asLong(), state);
            return true;
        }
    }
}
