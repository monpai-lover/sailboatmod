package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerPreviewJobServiceTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final class Port implements RoadChunkWarmup.ChunkPort {
        final Set<Long> loaded = new HashSet<>();
        final Set<Long> held = new HashSet<>();
        @Override public void request(long chunk) { held.add(chunk); }
        @Override public boolean isLoaded(long chunk) { return loaded.contains(chunk); }
        @Override public void release(long chunk) { held.remove(chunk); }
    }

    private static RoadChunkWarmup warmup(Port port, long... chunks) {
        List<Long> list = new ArrayList<>();
        for (long chunk : chunks) list.add(chunk);
        return new RoadChunkWarmup(list, port, 64, 256);
    }

    @Test
    void runsAtOnceWhenTheChunksAreAlreadyLoaded() {
        RoadPlannerPreviewJobService jobs = new RoadPlannerPreviewJobService();
        Port port = new Port();
        port.loaded.add(1L);
        List<String> ran = new ArrayList<>();
        jobs.submit(UUID.randomUUID(), Level.OVERWORLD, warmup(port, 1L), () -> ran.add("preview"), message -> { });
        assertEquals(List.of("preview"), ran);
        assertTrue(port.held.isEmpty(), "chunks are released after the preview is computed");
    }

    @Test
    void waitsForChunksThenRunsAndReportsProgressMeanwhile() {
        RoadPlannerPreviewJobService jobs = new RoadPlannerPreviewJobService();
        Port port = new Port();
        UUID owner = UUID.randomUUID();
        List<String> ran = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        jobs.submit(owner, Level.OVERWORLD, warmup(port, 1L, 2L), () -> ran.add("preview"), messages::add);
        assertTrue(ran.isEmpty());
        assertTrue(jobs.pendingFor(owner));
        for (int tick = 0; tick < 60; tick++) {
            jobs.tick(Level.OVERWORLD, id -> true);
        }
        assertTrue(ran.isEmpty());
        assertFalse(messages.isEmpty(), "the player sees loading progress");
        port.loaded.add(1L);
        port.loaded.add(2L);
        jobs.tick(Level.NETHER, id -> true);
        assertTrue(ran.isEmpty(), "jobs only advance in their own dimension");
        jobs.tick(Level.OVERWORLD, id -> true);
        assertEquals(List.of("preview"), ran);
        assertFalse(jobs.pendingFor(owner));
        assertTrue(port.held.isEmpty());
    }

    @Test
    void aNewRequestOrACancelOrLeavingDropsTheOldJob() {
        RoadPlannerPreviewJobService jobs = new RoadPlannerPreviewJobService();
        Port port = new Port();
        UUID owner = UUID.randomUUID();
        List<String> ran = new ArrayList<>();
        jobs.submit(owner, Level.OVERWORLD, warmup(port, 1L), () -> ran.add("old"), message -> { });
        jobs.submit(owner, Level.OVERWORLD, warmup(port, 2L), () -> ran.add("new"), message -> { });
        assertEquals(Set.of(2L), port.held, "the replaced job released its chunks");
        assertTrue(jobs.cancel(owner));
        assertFalse(jobs.cancel(owner));
        assertTrue(port.held.isEmpty());

        jobs.submit(owner, Level.OVERWORLD, warmup(port, 3L), () -> ran.add("offline"), message -> { });
        jobs.tick(Level.OVERWORLD, id -> false);
        assertFalse(jobs.pendingFor(owner));
        assertTrue(port.held.isEmpty());
        port.loaded.add(3L);
        jobs.tick(Level.OVERWORLD, id -> true);
        assertTrue(ran.isEmpty());
    }

    @Test
    void givesUpAfterTheTimeout() {
        RoadPlannerPreviewJobService jobs = new RoadPlannerPreviewJobService();
        Port port = new Port();
        UUID owner = UUID.randomUUID();
        List<String> messages = new ArrayList<>();
        jobs.submit(owner, Level.OVERWORLD, warmup(port, 1L), () -> { }, messages::add);
        for (int tick = 0; tick <= RoadPlannerPreviewJobService.TIMEOUT_TICKS; tick++) {
            jobs.tick(Level.OVERWORLD, id -> true);
        }
        assertFalse(jobs.pendingFor(owner));
        assertTrue(port.held.isEmpty());
        assertTrue(messages.get(messages.size() - 1).contains("超时"), messages.toString());
    }

    @Test
    void confirmBuildsWhatThePreviewComputedWithoutRecompiling() {
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService();
        UUID player = UUID.randomUUID();
        List<BlockPos> nodes = List.of(new BlockPos(0, 64, 0), new BlockPos(40, 64, 0));
        UUID previewId = service.startPreview(player, nodes, List.of(), RoadPlannerBuildSettings.DEFAULTS);
        List<BuildStep> computed = List.of(new BuildStep(0, new BlockPos(5, 70, 5), Blocks.GOLD_BLOCK.defaultBlockState(), BuildPhase.SURFACE));
        service.attachCompiledSteps(previewId, computed);
        UUID jobId = service.confirmPreview(player, previewId).orElseThrow();
        assertEquals(computed, service.buildQueueForTest(jobId).orElseThrow().getSteps());
    }

    @Test
    void stoppingTheServerReleasesEveryJob() {
        RoadPlannerPreviewJobService jobs = new RoadPlannerPreviewJobService();
        Port port = new Port();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        jobs.submit(a, Level.OVERWORLD, warmup(port, 1L), () -> { }, message -> { });
        jobs.submit(b, Level.NETHER, warmup(port, 2L), () -> { }, message -> { });
        jobs.clear();
        assertFalse(jobs.pendingFor(a));
        assertFalse(jobs.pendingFor(b));
        assertTrue(port.held.isEmpty());
    }

    @Test
    void forgettingAPlayerDropsTheirPreviewAndItsSteps() {
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService();
        UUID player = UUID.randomUUID();
        UUID previewId = service.startPreview(player, List.of(new BlockPos(0, 64, 0), new BlockPos(40, 64, 0)), List.of(),
                RoadPlannerBuildSettings.DEFAULTS);
        service.attachCompiledSteps(previewId, List.of(new BuildStep(0, BlockPos.ZERO, Blocks.STONE.defaultBlockState(), BuildPhase.SURFACE)));
        service.forgetPlayer(player);
        assertTrue(service.previewFor(player).isEmpty());
        assertEquals(0, service.cachedPreviewStepsForTest());
    }

    @Test
    void cancelChecksThePreviewThenTheBuildAndOnlyThenALoadingPreview() {
        List<String> asked = new ArrayList<>();
        var outcome = com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerCancelJobPacket.cancelInOrder(
                () -> { asked.add("preview"); return false; },
                () -> { asked.add("build"); return true; },
                () -> { asked.add("loading"); return true; });
        assertEquals(com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerCancelJobPacket.Cancelled.BUILD, outcome);
        assertEquals(List.of("preview", "build"), asked, "a running build is cancelled, the loading preview is left alone");
    }
}
