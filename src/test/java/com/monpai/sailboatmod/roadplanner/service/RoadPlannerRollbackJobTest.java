package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.road.construction.execution.ConstructionQueue;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class RoadPlannerRollbackJobTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    private static final class Port implements RoadChunkWarmup.ChunkPort {
        final Set<Long> held = new HashSet<>();
        boolean ready;
        int peak;
        @Override public void request(long key) { held.add(key); peak = Math.max(peak, held.size()); }
        @Override public boolean isLoaded(long key) { return ready; }
        @Override public void release(long key) { held.remove(key); }
    }

    @Test
    void cancellingALongRoadRestoresAtMostEightRealReadyEntriesAndReleasesOldWindows() throws Exception {
        Port port = new Port();
        List<ConstructionQueue.RollbackEntry> entries = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) entries.add(new ConstructionQueue.RollbackEntry(new BlockPos(i * 16, 63, 0), Blocks.STONE.defaultBlockState()));
        List<ConstructionQueue.RollbackEntry> restored = new ArrayList<>();
        Object job = job(entries, port, restored::add);
        tick(job);
        assertTrue(restored.isEmpty(), "cancel must not synchronously load and rewrite the whole constructed road");
        port.ready = true;
        for (int i = 0; i < 20 && restored.isEmpty(); i++) tick(job);
        assertEquals(8, restored.size());
        assertEquals(entries.get(entries.size() - 1), restored.get(0));
        assertTrue(port.held.isEmpty(), "the completed rollback window releases its real tickets");
        assertTrue(port.peak <= 72, "eight restore targets plus one neighbouring chunk ring remain bounded");
        job.getClass().getMethod("cancel").invoke(job);
    }

    @Test
    void stoppingARollbackDropsItsWaitingTicketsWithoutAnyFurtherWorldWrites() throws Exception {
        Port port = new Port();
        List<ConstructionQueue.RollbackEntry> restored = new ArrayList<>();
        Object job = job(List.of(new ConstructionQueue.RollbackEntry(BlockPos.ZERO, Blocks.STONE.defaultBlockState())), port, restored::add);
        tick(job);
        assertFalse(port.held.isEmpty());
        job.getClass().getMethod("cancel").invoke(job);
        assertTrue(port.held.isEmpty());
        port.ready = true;
        tick(job);
        assertTrue(restored.isEmpty());
    }

    private static Object job(List<ConstructionQueue.RollbackEntry> entries, Port port, Consumer<ConstructionQueue.RollbackEntry> restore) throws Exception {
        try { return Class.forName("com.monpai.sailboatmod.roadplanner.service.RoadPlannerRollbackJob")
                .getConstructor(List.class, RoadChunkWarmup.ChunkPort.class, Consumer.class).newInstance(entries, port, restore); }
        catch (ClassNotFoundException missing) { return fail("long-road cancellation needs its own bounded real restoration window"); }
    }
    private static void tick(Object job) throws Exception { job.getClass().getMethod("tick").invoke(job); }
}
