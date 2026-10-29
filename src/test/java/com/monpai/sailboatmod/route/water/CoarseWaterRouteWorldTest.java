package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.terrain.SurfaceSample;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CoarseWaterRouteWorldTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void savedTerrainOverridesPredictedOceanAndReadsEachChunkOnce() {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger predictions = new AtomicInteger();
        CoarseWaterRouteWorld world = new CoarseWaterRouteWorld((x, z) -> {
            reads.incrementAndGet();
            return Optional.of(new NbtChunkWaterReader.ChunkColumns(new boolean[256], new int[256]));
        }, (x, z, spacing) -> { predictions.incrementAndGet(); return water(); }, 63);
        assertFalse(world.sample(4, 5, WaterRoutePolicy.nbtCoarse()).passable());
        assertFalse(world.sample(8, 9, WaterRoutePolicy.nbtCoarse()).passable());
        assertEquals(1, reads.get());
        assertEquals(0, predictions.get());
        assertEquals(0, world.consumedChunkLoads());
    }

    @Test
    void missingNbtUsesCheapSinglePointPredictionAtNegativeCoordinates() {
        AtomicInteger reads = new AtomicInteger();
        CoarseWaterRouteWorld world = new CoarseWaterRouteWorld((cx, cz) -> {
            assertEquals(-1, cx); assertEquals(-2, cz); reads.incrementAndGet(); return Optional.empty();
        }, (x, z, spacing) -> { assertEquals(-1, x); assertEquals(-17, z); assertTrue(spacing >= 2); return water(); }, 63);
        assertTrue(world.sample(-1, -17, WaterRoutePolicy.nbtCoarse()).passable());
        assertTrue(world.sample(-1, -17, WaterRoutePolicy.nbtCoarse()).passable());
        assertEquals(1, reads.get(), "known absent NBT is cached, without creating a real chunk");
    }

    @Test
    void missingPredictionIsRetryableUnknownRatherThanFabricatedLandOrWater() {
        AtomicBoolean ready = new AtomicBoolean();
        CoarseWaterRouteWorld world = new CoarseWaterRouteWorld((x, z) -> Optional.empty(),
                (x, z, spacing) -> ready.get() ? water() : Optional.empty(), 63);
        assertNull(world.sample(0, 0, WaterRoutePolicy.nbtCoarse()));
        ready.set(true);
        assertTrue(world.sample(0, 0, WaterRoutePolicy.nbtCoarse()).passable());
    }

    private static Optional<SurfaceSample> water() {
        return Optional.of(new SurfaceSample(63, Blocks.WATER.defaultBlockState(), true, 32));
    }
}
