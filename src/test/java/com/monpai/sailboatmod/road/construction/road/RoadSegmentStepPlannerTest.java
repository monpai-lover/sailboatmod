package com.monpai.sailboatmod.road.construction.road;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.RoadData;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadSegmentStepPlannerTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void roadBuilderKeepsPlacementsAndInterpolatedSurfaceY() {
        RoadConfig config = new RoadConfig();
        RoadBuilder builder = new RoadBuilder(config);
        List<BlockPos> centerPath = List.of(new BlockPos(0, 64, 0), new BlockPos(10, 68, 0));
        List<RoadSegmentPlacement> placements = List.of(new RoadSegmentPlacement(
                centerPath.get(0), 0,
                List.of(new BlockPos(0, 64, 1), new BlockPos(5, 64, 1), new BlockPos(10, 64, 1)),
                false));

        RoadData roadData = builder.buildRoad("test", centerPath, 3, new FlatTerrainSamplingCache(63),
                "auto", placements, List.of());

        assertEquals(placements, roadData.placements());
        Set<Integer> surfaceYs = roadData.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.SURFACE)
                .map(step -> step.pos().getY())
                .collect(Collectors.toSet());
        assertTrue(surfaceYs.containsAll(Set.of(64, 66, 68)));
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