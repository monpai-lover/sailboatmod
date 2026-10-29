package com.monpai.sailboatmod.route.terrain;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreloadSampleInterpreterTest {
    private final AtomicInteger lodCalls = new AtomicInteger();
    private final Supplier<BlockState> lod = () -> {
        lodCalls.incrementAndGet();
        return Blocks.PODZOL.defaultBlockState();
    };

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void unknownLandMaskIsAFailedSample() {
        // Tellus falls back silently when a download fails; the land mask is the one flag that says so.
        assertTrue(PreloadSampleInterpreter.interpret(80, 30, true, false, 64, 4, lod).isEmpty());
    }

    @Test
    void oceanDepthIsMeasuredFromSeaLevel() {
        SurfaceSample s = PreloadSampleInterpreter.interpret(40, 0, false, true, 64, 4, lod).orElseThrow();
        assertTrue(s.water());
        assertEquals(64, s.surfaceY());
        assertEquals(24, s.waterDepth());
        assertEquals(1, PreloadSampleInterpreter.interpret(70, 0, false, true, 64, 4, lod).orElseThrow().waterDepth());
    }

    @Test
    void permanentWaterOnLandIsShallow() {
        SurfaceSample s = PreloadSampleInterpreter.interpret(120, 80, true, true, 64, 4, lod).orElseThrow();
        assertTrue(s.water());
        assertEquals(120, s.surfaceY());
        assertEquals(1, s.waterDepth());
    }

    @Test
    void nearestZoomUsesTheLodSurfaceBlock() {
        SurfaceSample s = PreloadSampleInterpreter.interpret(90, 30, true, true, 64, 1, lod).orElseThrow();
        assertEquals(Blocks.PODZOL, s.surface().getBlock());
        assertEquals(1, lodCalls.get());
    }

    @Test
    void fartherZoomsUseLandCoverWithoutLodLookups() {
        SurfaceSample s = PreloadSampleInterpreter.interpret(90, 70, true, true, 64, 2, lod).orElseThrow();
        assertEquals(Blocks.SNOW_BLOCK, s.surface().getBlock());
        assertEquals(0, lodCalls.get());
    }
}
