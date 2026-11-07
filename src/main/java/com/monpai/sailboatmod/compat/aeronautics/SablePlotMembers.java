package com.monpai.sailboatmod.compat.aeronautics;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Bounded reads of the already loaded chunks of one native Sable plot. */
final class SablePlotMembers {
    private static final int MAX_CHUNKS = 256;
    private static final int MAX_BLOCK_ENTITIES = 512;
    private final Class<?> plotType;
    private final Class<?> holderType;
    private final Method plot;
    private final Method loadedChunks;
    private final Method chunk;
    private final Method bounds;
    private final Method minX;
    private final Method maxX;
    private final Method minZ;
    private final Method maxZ;

    private SablePlotMembers(SableReflectionAccess.ClassLookup lookup, Class<?> bodyType) throws ReflectiveOperationException {
        plotType = lookup.find("dev.ryanhcode.sable.sublevel.plot.LevelPlot");
        holderType = lookup.find("dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder");
        plot = bodyType.getMethod("getPlot");
        loadedChunks = plotType.getMethod("getLoadedChunks");
        chunk = holderType.getMethod("getChunk");
        bounds = plotType.getMethod("getBoundingBox");
        var boundsType = bounds.getReturnType();
        minX = boundsType.getMethod("minX");
        maxX = boundsType.getMethod("maxX");
        minZ = boundsType.getMethod("minZ");
        maxZ = boundsType.getMethod("maxZ");
    }

    static SablePlotMembers bindIfAvailable(SableReflectionAccess.ClassLookup lookup, Class<?> bodyType) {
        try {
            return new SablePlotMembers(lookup, bodyType);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    Optional<List<BlockEntity>> read(Object body, ServerLevel world) throws ReflectiveOperationException {
        Object nativePlot = plot.invoke(body);
        if (!plotType.isInstance(nativePlot)
                || !(loadedChunks.invoke(nativePlot) instanceof Collection<?> holders)
                || holders.isEmpty() || holders.size() > MAX_CHUNKS) return Optional.empty();
        var chunks = new HashSet<Long>();
        var members = new ArrayList<BlockEntity>();
        for (Object holder : holders) {
            if (holder == null || holder.getClass() != holderType
                    || !(chunk.invoke(holder) instanceof LevelChunk loaded)
                    || !chunks.add(loaded.getPos().toLong())
                    || members.size() + loaded.getBlockEntities().size() > MAX_BLOCK_ENTITIES) return Optional.empty();
            for (var entry : loaded.getBlockEntities().entrySet()) {
                BlockEntity entity = entry.getValue();
                if (entity == null || entity.isRemoved() || entity.getLevel() != world
                        || !entry.getKey().equals(entity.getBlockPos())
                        || !loaded.getPos().equals(new ChunkPos(entry.getKey()))) return Optional.empty();
                members.add(entity);
            }
        }
        Object area = bounds.invoke(nativePlot);
        if (area == null || !coversBounds(integer(minX, area), integer(maxX, area),
                integer(minZ, area), integer(maxZ, area), chunks)) return Optional.empty();
        return Optional.of(List.copyOf(members));
    }

    private static int integer(Method coordinate, Object bounds) throws ReflectiveOperationException {
        return ((Number) coordinate.invoke(bounds)).intValue();
    }

    static boolean coversBounds(int minX, int maxX, int minZ, int maxZ, Set<Long> chunks) {
        if (minX > maxX || minZ > maxZ) return false;
        int fromX = minX >> 4, toX = maxX >> 4;
        int fromZ = minZ >> 4, toZ = maxZ >> 4;
        if ((toX - (long) fromX + 1) * (toZ - (long) fromZ + 1) > MAX_CHUNKS) return false;
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                if (!chunks.contains(ChunkPos.asLong(x, z))) return false;
            }
        }
        return true;
    }
}
