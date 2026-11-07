package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.network.packet.roadplanner.SharedMapTerrainPatchPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SharedMapResourceGuardsTest {
    private static final String DIM = "minecraft:overworld";
    private static SharedMapSendQueue.Target target(UUID player, int tile) {
        return new SharedMapSendQueue.Target(player, "earth", DIM, tile, 0, 1);
    }
    @Test void packetRejectsFullTileAndKeepsDefensiveOwnership() {
        assertThrows(IllegalArgumentException.class, () -> new SharedMapTerrainPatchPacket("earth", DIM,
                0, 0, 0, 0, 256, 256, new int[65536], true));
        int[] pixels = new int[4096];
        Arrays.fill(pixels, 0xFF123456);
        var packet = new SharedMapTerrainPatchPacket("earth", DIM, 0, 0, 0, 0, 256, 16, pixels, true);
        pixels[0] = 0;
        assertEquals(0xFF123456, packet.pixels()[0]);
        int[] escaped = packet.pixels();
        escaped[0] = 0;
        assertEquals(0xFF123456, packet.pixels()[0]);
    }
    @Test void decoderRejectsAdvertisedLargeOrMismatchedArraysBeforeReadingPixels() {
        for (int count : new int[]{65536, Integer.MAX_VALUE, -1, 4095}) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeUtf("earth", 128).writeUtf(DIM, 128);
                buffer.writeInt(0).writeInt(0);
                buffer.writeVarInt(0).writeVarInt(0).writeVarInt(256).writeVarInt(16);
                buffer.writeBoolean(true);
                buffer.writeVarInt(1);
                buffer.writeVarInt(count);
                assertThrows(IllegalArgumentException.class, () -> SharedMapTerrainPatchPacket.decode(buffer));
            } finally { buffer.release(); }
        }
    }
    @Test void fullPredictionStreamsAsSixteenStripsWithoutRestartingOnDuplicate() {
        SharedMapSendQueue queue = new SharedMapSendQueue();
        UUID player = UUID.randomUUID();
        int[] pixels = new int[65536];
        for (int i = 0; i < pixels.length; i++) pixels[i] = 0xFF000000 | i;
        var target = target(player, 0);
        queue.offer(target, 0, 0, 256, 256, pixels, true, 0);
        List<SharedMapTerrainPatchPacket> packets = new ArrayList<>();
        for (int tick = 0; tick < 16; tick++) {
            queue.offer(target, 0, 0, 256, 256, pixels, true, tick);
            queue.drain(tick, ignored -> true, (ignored, packet) -> packets.add(packet));
        }
        assertEquals(16, packets.size());
        int[] reconstructed = new int[65536];
        for (int i = 0; i < packets.size(); i++) {
            var packet = packets.get(i);
            assertEquals(4096, packet.pixels().length);
            assertEquals(i * 16, packet.offsetZ());
            System.arraycopy(packet.pixels(), 0, reconstructed, packet.offsetZ() * 256, 4096);
        }
        assertArrayEquals(pixels, reconstructed);
        assertEquals(0, queue.size());
        assertEquals(0, queue.retainedBytes());
    }
    @Test void senderBoundsGlobalAndPerPlayerBytesAndCannotDoubleDrainATick() {
        SharedMapSendQueue queue = new SharedMapSendQueue();
        int[] raster = new int[65536];
        for (int p = 0; p < 10; p++) {
            UUID id = UUID.randomUUID();
            for (int i = 0; i < 12; i++) queue.offer(target(id, i), 0, 0, 256, 256, raster, true, 0);
        }
        Map<UUID, Integer> sent = new HashMap<>();
        queue.drain(1, ignored -> true, (target, packet) -> sent.merge(target.player(), packet.pixels().length * 4 + 1024, Integer::sum));
        assertTrue(sent.values().stream().allMatch(n -> n <= SharedMapSendQueue.PLAYER_BYTES_PER_TICK));
        assertTrue(sent.values().stream().mapToInt(Integer::intValue).sum() <= SharedMapSendQueue.BYTES_PER_TICK);
        queue.drain(1, ignored -> true, (target, packet) -> fail("duplicate drain in same server tick"));
    }
    @Test void retainedBytesAndPendingCountStayBoundedAndExpiredDisconnectedViewsAreReleased() {
        SharedMapSendQueue queue = new SharedMapSendQueue();
        int[] raster = new int[65536];
        for (int p = 0; p < 30; p++) {
            UUID id = UUID.randomUUID();
            for (int i = 0; i < 100; i++) queue.offer(target(id, i), 0, 0, 256, 256, raster, true, 0);
            assertTrue(queue.retainedBytes() <= SharedMapSendQueue.MAX_RETAINED_BYTES);
            assertTrue(queue.size() <= SharedMapSendQueue.MAX_PENDING);
        }
        queue.drain(SharedMapSendQueue.TTL_TICKS, ignored -> true, (target, packet) -> fail("expired"));
        assertEquals(0, queue.retainedBytes());
        queue.offer(target(UUID.randomUUID(), 0), 0, 0, 256, 256, raster, true, 300);
        queue.drain(301, ignored -> false, (target, packet) -> fail("disconnected"));
        assertEquals(0, queue.size());
    }
    @Test void failedRecipientDoesNotBlockOthersOrLeakItsRaster() {
        SharedMapSendQueue queue = new SharedMapSendQueue();
        UUID bad = UUID.randomUUID(), good = UUID.randomUUID();
        int[] actual = new int[256];
        queue.offer(target(bad, 0), 0, 0, 16, 16, actual, false, 0);
        queue.offer(target(good, 0), 0, 0, 16, 16, actual, false, 0);
        List<UUID> sent = new ArrayList<>();
        queue.drain(1, ignored -> true, (target, packet) -> {
            if (target.player().equals(bad)) throw new IllegalStateException("closed connection");
            sent.add(target.player());
        });
        assertEquals(List.of(good), sent);
        assertEquals(0, queue.retainedBytes());
    }
    @Test void temporaryBackpressureRetainsTheCursorAndDoesNotBlockAnotherRecipient() {
        var queue=new SharedMapSendQueue();var slow=UUID.randomUUID();var ready=UUID.randomUUID();
        queue.offer(target(slow,0),0,0,16,16,new int[256],false,0);
        queue.offer(target(ready,0),0,0,16,16,new int[256],false,0);
        List<UUID> sent=new ArrayList<>();
        queue.drainWithStatus(1,t->t.player().equals(slow)?0:1,(t,p)->sent.add(t.player()));
        assertEquals(List.of(ready),sent);assertEquals(1,queue.size());assertEquals(1024,queue.retainedBytes());
        queue.drainWithStatus(2,t->1,(t,p)->sent.add(t.player()));
        assertEquals(List.of(ready,slow),sent);assertEquals(0,queue.retainedBytes());
    }
    @Test void disconnectedOrExpiredBackpressureReleasesRetainedPixels() {
        var queue=new SharedMapSendQueue();var player=UUID.randomUUID();
        queue.offer(target(player,0),0,0,16,16,new int[256],false,0);
        queue.drainWithStatus(1,t->-1,(t,p)->fail("disconnected"));assertEquals(0,queue.size());
        queue.offer(target(player,1),0,0,16,16,new int[256],false,1);
        queue.drainWithStatus(SharedMapSendQueue.TTL_TICKS+1,t->0,(t,p)->fail("expired"));
        assertEquals(0,queue.retainedBytes());
    }
}
