package com.monpai.sailboatmod.roadplanner.service;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerChunkTicketsTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void clearReferences() {
        RoadPlannerChunkTickets.clearAll();
    }

    @Test
    void laterHolderKeepsSharedTicketThroughItsEntirePreviewLifetime() throws Exception {
        TestChunkCache cache = allocate(TestChunkCache.class);
        TestLevel level = allocate(TestLevel.class);
        level.cache = cache;
        RoadPlannerChunkTickets first = new RoadPlannerChunkTickets(level);
        RoadPlannerChunkTickets later = new RoadPlannerChunkTickets(level);
        long chunk = ChunkPos.asLong(4, 7);

        first.request(chunk);
        cache.currentTick = RoadPlannerPreviewJobService.TIMEOUT_TICKS - 1;
        later.request(chunk);
        first.release(chunk);
        assertEquals(0, cache.removals, "the first holder must not remove the later holder's ticket");

        cache.currentTick += RoadPlannerPreviewJobService.TIMEOUT_TICKS;
        assertFalse(cache.expired(), "the later job must have its entire 2-minute loading lifetime");

        later.release(chunk);
        assertEquals(1, cache.removals, "the final holder releases the shared ticket exactly once");
        later.release(chunk);
        assertEquals(1, cache.removals);
    }

    @Test
    void renewedTicketStillExpiresIfAllCleanupIsLost() throws Exception {
        TestChunkCache cache = allocate(TestChunkCache.class);
        TestLevel level = allocate(TestLevel.class);
        level.cache = cache;
        RoadPlannerChunkTickets tickets = new RoadPlannerChunkTickets(level);
        tickets.request(ChunkPos.asLong(4, 7));

        cache.currentTick = RoadPlannerPreviewJobService.TIMEOUT_TICKS;
        assertFalse(cache.expired(), "the normal preview timeout must fit within the ticket lifetime");
        cache.currentTick = cache.ticket.getType().timeout() + 1;
        assertTrue(cache.expired(), "lost holders must not pin a chunk forever");
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (T) ((Unsafe) field.get(null)).allocateInstance(type);
    }

    private static final class TestLevel extends ServerLevel {
        private TestChunkCache cache;

        private TestLevel() {
            super(null, command -> { }, null, null, null, null, null, false, 0L, List.of(), false, null);
        }

        @Override
        public net.minecraft.resources.ResourceKey<Level> dimension() {
            return Level.OVERWORLD;
        }

        @Override
        public ServerChunkCache getChunkSource() {
            return cache;
        }
    }

    /** Keeps a real Minecraft Ticket and moves its clock without generating any chunks or starting a server. */
    private static final class TestChunkCache extends ServerChunkCache {
        private long currentTick;
        private Ticket<?> ticket;
        private int removals;

        private TestChunkCache() {
            super(null, null, null, null, Runnable::run, null, 0, 0, false, null, null, null);
        }

        @Override
        public <T> void addRegionTicket(TicketType<T> type, ChunkPos pos, int distance, T key) {
            ticket = new Ticket<>(type, 33 - distance, key, false);
            try {
                // DistanceManager.addTicket refreshes the existing Ticket's creation tick on every addOrGet.
                Method created = Ticket.class.getDeclaredMethod("setCreatedTick", long.class);
                created.setAccessible(true);
                created.invoke(ticket, currentTick);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public <T> void removeRegionTicket(TicketType<T> type, ChunkPos pos, int distance, T key) {
            removals++;
        }

        private boolean expired() throws ReflectiveOperationException {
            Method expired = Ticket.class.getDeclaredMethod("timedOut", long.class);
            expired.setAccessible(true);
            return (boolean) expired.invoke(ticket, currentTick);
        }
    }
}
