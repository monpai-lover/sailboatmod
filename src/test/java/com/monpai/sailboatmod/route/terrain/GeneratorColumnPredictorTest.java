package com.monpai.sailboatmod.route.terrain;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratorColumnPredictorTest {
    private static final int MIN_Y = -64;
    private static final int MAX_Y = 128;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void waterColumnBecomesWaterCell() {
        GeneratorColumnPredictor predictor = new GeneratorColumnPredictor(
                (x, z) -> column((x & 15) == 3 && (z & 15) == 5), PrefetchGate.ALWAYS_READY, MIN_Y, MAX_Y);

        Prediction prediction = predictor.predictChunk(2, -1, 1000L);

        assertEquals(Prediction.Status.READY, prediction.status());
        boolean[] water = prediction.columns().water();
        assertTrue(water[5 * 16 + 3]);
        int waterCells = 0;
        for (boolean w : water) {
            if (w) {
                waterCells++;
            }
        }
        assertEquals(1, waterCells);
        assertEquals(64, prediction.columns().surfaceY()[5 * 16 + 3]);
    }

    @Test
    void notReadyWhenGateTimesOut() {
        PrefetchGate never = new PrefetchGate() {
            @Override
            public CompletableFuture<Void> request(int cx, int cz) {
                return new CompletableFuture<>();
            }

            @Override
            public List<CompletableFuture<Void>> requestCorridor(BlockPos a, BlockPos b, int sideBlocks) {
                return List.of();
            }
        };
        GeneratorColumnPredictor predictor = new GeneratorColumnPredictor((x, z) -> column(true), never, MIN_Y, MAX_Y);

        assertEquals(Prediction.Status.NOT_READY, predictor.predictChunk(0, 0, 10L).status());
    }

    @Test
    void failedWhenSourceThrows() {
        GeneratorColumnPredictor throwing = new GeneratorColumnPredictor((x, z) -> {
            throw new RuntimeException("boom");
        }, PrefetchGate.ALWAYS_READY, MIN_Y, MAX_Y);
        GeneratorColumnPredictor linkage = new GeneratorColumnPredictor((x, z) -> {
            throw new NoSuchMethodError("tellus changed");
        }, PrefetchGate.ALWAYS_READY, MIN_Y, MAX_Y);

        assertEquals(Prediction.Status.FAILED, throwing.predictChunk(0, 0, 10L).status());
        assertEquals(Prediction.Status.FAILED, linkage.predictChunk(0, 0, 10L).status());
    }

    @Test
    void alwaysReadyGateIsComplete() {
        assertTrue(PrefetchGate.ALWAYS_READY.request(5, 5).isDone());
        assertFalse(PrefetchGate.ALWAYS_READY.request(5, 5).isCompletedExceptionally());
    }

    @Test
    void failedPrefetchStillPredicts() {
        PrefetchGate broken = new PrefetchGate() {
            @Override
            public CompletableFuture<Void> request(int cx, int cz) {
                return CompletableFuture.failedFuture(new NoSuchMethodError("prefetchForArea changed"));
            }

            @Override
            public List<CompletableFuture<Void>> requestCorridor(BlockPos a, BlockPos b, int sideBlocks) {
                return List.of();
            }
        };
        GeneratorColumnPredictor predictor = new GeneratorColumnPredictor((x, z) -> column(true), broken, MIN_Y, MAX_Y);

        assertEquals(Prediction.Status.READY, predictor.predictChunk(0, 0, 10L).status(),
                "prefetch is advisory: getBaseColumn downloads by itself");
    }

    @Test
    void boundedWorkerDirectReadDoesNotWaitForSpeculativeAreaPrefetch() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        PrefetchGate never = new PrefetchGate() {
            public CompletableFuture<Void> request(int cx, int cz) { calls.incrementAndGet(); return new CompletableFuture<>(); }
            public List<CompletableFuture<Void>> requestCorridor(BlockPos a, BlockPos b, int side) { return List.of(); }
        };
        GeneratorColumnPredictor predictor = new GeneratorColumnPredictor((x, z) -> column(true), never, MIN_Y, MAX_Y);
        Prediction result = predictor.predictChunkDirect(0, 0);
        assertEquals(Prediction.Status.READY, result.status());
        assertEquals(0, calls.get());
        assertTrue(result.columns().water()[0]);
    }

    /** stone up to 60, then water to 64 (wet) or stone to 64 (dry), air above. */
    private static NoiseColumn column(boolean wet) {
        BlockState[] states = new BlockState[MAX_Y - MIN_Y];
        for (int y = MIN_Y; y < MAX_Y; y++) {
            BlockState s;
            if (y <= 60) {
                s = Blocks.STONE.defaultBlockState();
            } else if (y <= 64) {
                s = wet ? Blocks.WATER.defaultBlockState() : Blocks.STONE.defaultBlockState();
            } else {
                s = Blocks.AIR.defaultBlockState();
            }
            states[y - MIN_Y] = s;
        }
        return new NoiseColumn(MIN_Y, states);
    }
}
