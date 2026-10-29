package com.monpai.sailboatmod.roadplanner.service;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** Holds road planner chunks loaded with a ticket; the chunk system loads them off the main thread. */
public final class RoadPlannerChunkTickets implements RoadChunkWarmup.ChunkPort {
    /** Expires on its own a little after the preview timeout, so a lost job cannot pin chunks forever. */
    private static final TicketType<ChunkPos> ROAD_PLANNER = TicketType.create(
            "sailboatmod_road_planner", Comparator.comparingLong(ChunkPos::toLong), RoadPlannerPreviewJobService.TIMEOUT_TICKS + 20 * 60);
    /** Chunks requested per tick and allowed to wait at once; keeps the chunk system responsive for players. */
    public static final int REQUESTS_PER_TICK = 48;
    public static final int MAX_IN_FLIGHT = 192;
    /** Holders per chunk and dimension, shared by every job (main thread only). */
    private static final Map<ResourceKey<Level>, RoadChunkTicketRefs> REFS = new HashMap<>();

    private final ServerLevel level;
    private final RoadChunkTicketRefs refs;

    public RoadPlannerChunkTickets(ServerLevel level) {
        this.level = level;
        this.refs = REFS.computeIfAbsent(level.dimension(), key -> new RoadChunkTicketRefs());
    }

    /** Forgets every holder; the tickets go away with the stopped server. */
    public static void clearAll() {
        REFS.clear();
    }

    @Override
    public void request(long chunkKey) {
        refs.acquire(chunkKey);
        ChunkPos pos = new ChunkPos(chunkKey);
        // Adding the same ticket refreshes its creation tick. Every holder lives at most TIMEOUT_TICKS after
        // acquiring it, so the longer ticket lifetime also covers jobs that joined an older shared ticket.
        level.getChunkSource().addRegionTicket(ROAD_PLANNER, pos, 0, pos);
    }

    /**
     * Loaded means generated and in memory. hasChunk only checks the ticket level, so it says yes before the chunk
     * exists, and reading its height then generates it on the main thread: the freeze this class is meant to avoid.
     */
    @Override
    public boolean isLoaded(long chunkKey) {
        return level.getChunkSource().getChunkNow(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey)) != null;
    }

    @Override
    public void release(long chunkKey) {
        if (refs.release(chunkKey)) {
            ChunkPos pos = new ChunkPos(chunkKey);
            level.getChunkSource().removeRegionTicket(ROAD_PLANNER, pos, 0, pos);
        }
    }
}
