package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.terrain.PredictionBudget;
import com.monpai.sailboatmod.route.terrain.Prediction;
import com.monpai.sailboatmod.route.terrain.PrefetchGate;
import com.monpai.sailboatmod.route.terrain.TerrainPredictor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A null level means the NBT read always misses, which is exactly an ungenerated (or not yet FULL) chunk. */
class RealBlockWaterMapPredictorTest {
    private final List<Long> waits = new ArrayList<>();
    private int calls;

    @Test
    void nbtMissFallsThroughToPrediction() {
        RealBlockWaterMap map = new RealBlockWaterMap(null, 63).enableOnDemand()
                .withPredictor(predictor((cx, cz) -> Prediction.ready(columns(true))), PredictionBudget.sixtySeconds());

        assertTrue(map.sample(5, 5).passable());
        assertEquals(1, map.predictedHits());
    }

    @Test
    void withoutPredictorMissIsLand() {
        RealBlockWaterMap map = new RealBlockWaterMap(null, 63).enableOnDemand();

        assertFalse(map.sample(5, 5).passable());
        assertEquals(0, map.predictedHits());
    }

    @Test
    void notReadyAndFailedAreLandAndCounted() {
        RealBlockWaterMap map = new RealBlockWaterMap(null, 63).enableOnDemand()
                .withPredictor(predictor((cx, cz) -> cx == 0 ? Prediction.notReady() : Prediction.failed()),
                        PredictionBudget.sixtySeconds());

        assertFalse(map.sample(5, 5).passable());
        assertFalse(map.sample(16 + 5, 5).passable());
        assertEquals(1, map.predictTimeouts());
        assertEquals(1, map.predictFailures());
        assertEquals(0, map.predictedHits());
    }

    @Test
    void budgetIsSharedAndDrains() {
        AtomicLong now = new AtomicLong(1_000L);
        PredictionBudget budget = new PredictionBudget(60_000L, now::get);
        RealBlockWaterMap map = new RealBlockWaterMap(null, 63).enableOnDemand()
                .withPredictor(predictor((cx, cz) -> {
                    now.addAndGet(60_000L);
                    return Prediction.notReady();
                }), budget);

        map.sample(5, 5);
        map.sample(16 + 5, 5);

        assertEquals(List.of(60_000L, 0L), waits);
        assertEquals(0L, budget.remainingMillis());
    }

    @Test
    void seededChunkWinsOverPrediction() {
        RealBlockWaterMap map = new RealBlockWaterMap(null, 63).enableOnDemand()
                .withPredictor(predictor((cx, cz) -> Prediction.ready(columns(true))), PredictionBudget.sixtySeconds());
        map.seedChunk(0, 0, columns(false));

        assertFalse(map.sample(5, 5).passable());
        assertEquals(0, calls);
    }

    private interface Answer {
        Prediction answer(int cx, int cz);
    }

    private TerrainPredictor predictor(Answer answer) {
        return new TerrainPredictor() {
            @Override
            public Prediction predictChunk(int cx, int cz, long maxWaitMillis) {
                calls++;
                waits.add(maxWaitMillis);
                return answer.answer(cx, cz);
            }

            @Override
            public PrefetchGate gate() {
                return PrefetchGate.ALWAYS_READY;
            }
        };
    }

    private static NbtChunkWaterReader.ChunkColumns columns(boolean water) {
        boolean[] cells = new boolean[256];
        Arrays.fill(cells, water);
        int[] surface = new int[256];
        Arrays.fill(surface, 62);
        return new NbtChunkWaterReader.ChunkColumns(cells, surface);
    }
}
