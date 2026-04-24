package com.monpai.sailboatmod.road.construction.road;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadSegmentPlacement;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadSegmentPaverTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void paverPlacesEveryProvidedFootprintCell() {
        List<BlockPos> centers = List.of(new BlockPos(0, 64, 0), new BlockPos(4, 64, 0));
        RoadSegmentPlacement placement = new RoadSegmentPlacement(
                centers.get(0),
                0,
                List.of(new BlockPos(0, 64, -1), new BlockPos(0, 64, 0), new BlockPos(0, 64, 1)),
                false);
        RoadSegmentPaver paver = new RoadSegmentPaver(new BiomeMaterialSelector());

        List<BuildStep> steps = paver.paveSegment(placement, centers, new int[]{64, 64},
                new FlatTerrainSamplingCache(64), "auto", 0);
        Set<BlockPos> surface = steps.stream()
                .filter(step -> step.phase() == BuildPhase.SURFACE)
                .map(BuildStep::pos)
                .collect(Collectors.toSet());

        assertTrue(surface.containsAll(placement.positions()),
                "Road paving should place every footprint cell supplied by post-processing");
    }

    @Test
    void paverEmitsClearStepsForWholeFootprintAboveSurface() {
        List<BlockPos> centers = List.of(new BlockPos(0, 64, 0), new BlockPos(4, 64, 0));
        RoadSegmentPlacement placement = new RoadSegmentPlacement(
                centers.get(0),
                0,
                List.of(new BlockPos(0, 64, -1), new BlockPos(0, 64, 0), new BlockPos(0, 64, 1)),
                false);
        RoadSegmentPaver paver = new RoadSegmentPaver(new BiomeMaterialSelector());

        List<BuildStep> steps = paver.paveSegment(placement, centers, new int[]{64, 64},
                new FlatTerrainSamplingCache(64), "auto", 0);

        long airSteps = steps.stream()
                .filter(step -> step.state().is(net.minecraft.world.level.block.Blocks.AIR))
                .count();
        assertTrue(airSteps >= 12,
                "three footprint cells should each clear several above-surface blocks");
    }
    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Unsafe unsafe = (Unsafe) field.get(null);
            return (T) unsafe.allocateInstance(type);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static final class FlatTerrainSamplingCache extends TerrainSamplingCache {
        private final int surfaceY;
        private final Holder<Biome> biome;

        private FlatTerrainSamplingCache(int surfaceY) {
            super(null, PathfindingConfig.SamplingPrecision.HIGH);
            this.surfaceY = surfaceY;
            this.biome = Holder.direct(allocate(Biome.class));
        }

        @Override
        public int getHeight(int x, int z) {
            return surfaceY;
        }

        @Override
        public boolean isWater(int x, int z) {
            return false;
        }

        @Override
        public int motionBlockingHeight(int x, int z) {
            return surfaceY + 1;
        }

        @Override
        public Holder<Biome> getBiome(int x, int z) {
            return biome;
        }
    }
}
