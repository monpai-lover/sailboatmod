package com.monpai.sailboatmod.market.web.map;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MarketWebMapRecoverySnapshotTest {
    @Test
    void crashCheckpointIncludesClaimsDecodedHandoffsAndRenderStagesWithoutStopping(@TempDir Path directory) throws Exception {
        MarketWebMapRenderManager manager = new MarketWebMapRenderManager();
        MarketWebMapRenderQueue queue = new MarketWebMapRenderQueue(8);
        manager.bindRenderQueue(queue);
        try {
            queue.enqueue("minecraft:overworld", 64, 0, 1L);
            var claim = queue.pollTracked(1, 2L).getFirst();
            map(manager, "pendingProbe").put(ChunkPos.asLong(96, 0), null);
            map(manager, "pendingDecoded").put(ChunkPos.asLong(128, 0), null);
            @SuppressWarnings("unchecked") Set<Long> completed = (Set<Long>) field(manager, "completedProbeTasks");
            completed.add(ChunkPos.asLong(160, 0));
            Method newState = MarketWebMapRenderManager.class.getDeclaredMethod("newRegionState", String.class, int.class, int.class);
            newState.setAccessible(true);
            map(manager, "regionStates").put(MarketWebMapRegionWatcher.packRegion(6, 0), newState.invoke(manager, "minecraft:overworld", 6, 0));
            map(manager, "scheduledRegionReferences").put(MarketWebMapRegionWatcher.packRegion(7, 0), 1);
            Field reader = MarketWebMapRenderManager.class.getDeclaredField("readerState");
            reader.setAccessible(true);
            reader.set(manager, newState.invoke(manager, "minecraft:overworld", 8, 0));
            map(manager, "readerProbes").put(ChunkPos.asLong(288, 0), null);
            Method recovery = MarketWebMapRenderManager.class.getDeclaredMethod("recoveryRegionsForSnapshot");
            recovery.setAccessible(true);
            MarketWebMapStateWriter writer = (MarketWebMapStateWriter) field(manager, "stateWriter");
            MarketWebMapDirtyChunkQueue chunks = (MarketWebMapDirtyChunkQueue) field(manager, "dirtyChunks");
            MarketWebMapDirtyRegionQueue regions = (MarketWebMapDirtyRegionQueue) field(manager, "dirtyRegions");
            regions.markDirty("minecraft:overworld", 1, 0);
            regions.expandChunks(32);
            assertTrue(writer.requestDirtyQueues(manager, chunks, directory.resolve("chunks.json"), regions, directory.resolve("regions.json"), () -> {
                try {
                    @SuppressWarnings("unchecked") List<MarketWebMapDirtyRegionQueue.RegionCoordinate> result =
                            (List<MarketWebMapDirtyRegionQueue.RegionCoordinate>) recovery.invoke(manager);
                    return result;
                } catch (ReflectiveOperationException exception) { throw new RuntimeException(exception); }
            }));
            assertTrue(writer.flush(3000));
            assertEquals(32, regions.inProgressLocalChunk(), "checkpointing must not rewind live progress");
            MarketWebMapDirtyRegionQueue restored = new MarketWebMapDirtyRegionQueue();
            assertEquals(9, restored.load(directory.resolve("regions.json")));
            assertEquals(0, restored.inProgressLocalChunk());
            assertEquals(0, restored.expandChunks(1).getFirst().chunkX() % 32);
            queue.acknowledge(claim);
        } finally {
            manager.shutdown();
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, Object> map(Object target, String name) throws Exception {
        return (Map<Long, Object>) field(target, name);
    }
}
