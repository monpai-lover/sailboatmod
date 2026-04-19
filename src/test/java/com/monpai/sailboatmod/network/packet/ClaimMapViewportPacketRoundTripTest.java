package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.service.ClaimMapViewportSnapshot;
import com.monpai.sailboatmod.nation.service.ClaimPreviewTerrainService;
import com.monpai.sailboatmod.nation.service.ClaimMapTaskService;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimMapViewportPacketRoundTripTest {
    @Test
    void requestPacketRoundTripsAllFields() {
        RequestClaimMapViewportPacket packet = new RequestClaimMapViewportPacket(
                RequestClaimMapViewportPacket.ScreenKind.TOWN,
                "town-a",
                "minecraft:overworld",
                7L,
                8,
                40,
                -12,
                3
        );

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        RequestClaimMapViewportPacket.encode(packet, buffer);
        RequestClaimMapViewportPacket decoded = RequestClaimMapViewportPacket.decode(buffer);

        assertEquals(RequestClaimMapViewportPacket.ScreenKind.TOWN, decoded.screenKind());
        assertEquals("town-a", decoded.ownerId());
        assertEquals("minecraft:overworld", decoded.dimensionId());
        assertEquals(7L, decoded.revision());
        assertEquals(8, decoded.radius());
        assertEquals(40, decoded.centerChunkX());
        assertEquals(-12, decoded.centerChunkZ());
        assertEquals(3, decoded.prefetchRadius());
    }

    @Test
    void refreshPacketRoundTripsAllFields() {
        RefreshClaimMapViewportPacket packet = new RefreshClaimMapViewportPacket(
                RequestClaimMapViewportPacket.ScreenKind.NATION,
                "nation-a",
                "minecraft:the_nether",
                23L,
                5,
                -31,
                72
        );

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        RefreshClaimMapViewportPacket.encode(packet, buffer);
        RefreshClaimMapViewportPacket decoded = RefreshClaimMapViewportPacket.decode(buffer);

        assertEquals(RequestClaimMapViewportPacket.ScreenKind.NATION, decoded.screenKind());
        assertEquals("nation-a", decoded.ownerId());
        assertEquals("minecraft:the_nether", decoded.dimensionId());
        assertEquals(23L, decoded.revision());
        assertEquals(5, decoded.radius());
        assertEquals(-31, decoded.centerChunkX());
        assertEquals(72, decoded.centerChunkZ());
    }

    @Test
    void syncPacketRoundTripsAllSnapshotFields() {
        SyncClaimPreviewMapPacket packet = new SyncClaimPreviewMapPacket(
                SyncClaimPreviewMapPacket.ScreenKind.NATION,
                "nation-a",
                new ClaimMapViewportSnapshot("minecraft:overworld", 11L, 6, 2, 3, List.of(0xFF010203, 0xFF0A0B0C), false, 41, 64, 12, 24)
        );

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        SyncClaimPreviewMapPacket.encode(packet, buffer);
        SyncClaimPreviewMapPacket decoded = SyncClaimPreviewMapPacket.decode(buffer);

        assertEquals(SyncClaimPreviewMapPacket.ScreenKind.NATION, decoded.screenKind());
        assertEquals("nation-a", decoded.ownerId());
        assertEquals("minecraft:overworld", decoded.snapshot().dimensionId());
        assertEquals(11L, decoded.snapshot().revision());
        assertEquals(6, decoded.snapshot().radius());
        assertEquals(2, decoded.snapshot().centerChunkX());
        assertEquals(3, decoded.snapshot().centerChunkZ());
        assertEquals(List.of(0xFF010203, 0xFF0A0B0C), decoded.snapshot().pixels());
        assertFalse(decoded.snapshot().complete());
        assertEquals(41, decoded.snapshot().visibleReadyChunkCount());
        assertEquals(64, decoded.snapshot().visibleChunkCount());
        assertEquals(12, decoded.snapshot().prefetchReadyChunkCount());
        assertEquals(24, decoded.snapshot().prefetchChunkCount());
    }

    @Test
    void closeViewportPacketRoundTripsAllFields() {
        CloseClaimMapViewportPacket packet = new CloseClaimMapViewportPacket(
                CloseClaimMapViewportPacket.ScreenKind.TOWN,
                "town-a"
        );

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        CloseClaimMapViewportPacket.encode(packet, buffer);
        CloseClaimMapViewportPacket decoded = CloseClaimMapViewportPacket.decode(buffer);

        assertEquals(CloseClaimMapViewportPacket.ScreenKind.TOWN, decoded.screenKind());
        assertEquals("town-a", decoded.ownerId());
    }

    @Test
    void serverClampBoundsRadiusAndPrefetchRadius() {
        int clamped = RequestClaimMapViewportPacket.clampRadiusForServer(Integer.MAX_VALUE);
        assertTrue(clamped >= 0);
        assertTrue(clamped <= Integer.MAX_VALUE);
        assertEquals(0, RequestClaimMapViewportPacket.clampRadiusForServer(-12));
        assertEquals(RequestClaimMapViewportPacket.MAX_SERVER_PREFETCH_RADIUS, RequestClaimMapViewportPacket.clampPrefetchRadiusForServer(Integer.MAX_VALUE));
        assertEquals(0, RequestClaimMapViewportPacket.clampPrefetchRadiusForServer(-8));
    }

    @Test
    void completeOnlySnapshotReturnsPartialPixelsWhenVisibleChunkIsMissing() {
        RequestClaimMapViewportPacket packet = new RequestClaimMapViewportPacket(
                RequestClaimMapViewportPacket.ScreenKind.TOWN,
                "town-a",
                "minecraft:overworld",
                9L,
                1,
                0,
                0,
                1
        );

        ClaimMapViewportSnapshot snapshot = RequestClaimMapViewportPacket.tryBuildCompleteSnapshot(
                packet,
                "minecraft:overworld",
                (dimensionId, chunkX, chunkZ) -> (chunkX == 0 && chunkZ == 0) ? null : new int[] {0xFF112233},
                1,
                1
        );

        assertNotNull(snapshot);
        assertFalse(snapshot.complete());
        assertEquals(9 * ClaimPreviewTerrainService.SUB * ClaimPreviewTerrainService.SUB, snapshot.pixels().size());
    }

    @Test
    void completeOnlySnapshotReturnsPixelsWhenVisibleAreaComplete() {
        RequestClaimMapViewportPacket packet = new RequestClaimMapViewportPacket(
                RequestClaimMapViewportPacket.ScreenKind.NATION,
                "nation-a",
                "minecraft:overworld",
                15L,
                1,
                10,
                20,
                2
        );

        ClaimMapViewportSnapshot snapshot = RequestClaimMapViewportPacket.tryBuildCompleteSnapshot(
                packet,
                "minecraft:overworld",
                (dimensionId, chunkX, chunkZ) -> new int[] {chunkX * 100 + chunkZ},
                1,
                2
        );

        assertNotNull(snapshot);
        assertTrue(snapshot.complete());
        assertEquals(15L, snapshot.revision());
        assertEquals(1, snapshot.radius());
        assertEquals(10, snapshot.centerChunkX());
        assertEquals(20, snapshot.centerChunkZ());
        assertEquals(9 * ClaimPreviewTerrainService.SUB * ClaimPreviewTerrainService.SUB, snapshot.pixels().size());
    }

    @Test
    void refreshVisibleFootprintEnumeratesSquareArea() {
        java.util.Set<Long> visited = new java.util.HashSet<>();
        RefreshClaimMapViewportPacket.forEachVisibleChunk(3, -4, 1, (chunkX, chunkZ) ->
                visited.add(((long) chunkX << 32) ^ (chunkZ & 0xffffffffL))
        );

        assertEquals(9, visited.size());
        assertTrue(visited.contains(((long) 3 << 32) ^ (-4 & 0xffffffffL)));
        assertTrue(visited.contains(((long) 2 << 32) ^ (-5 & 0xffffffffL)));
        assertTrue(visited.contains(((long) 4 << 32) ^ (-3 & 0xffffffffL)));
    }

    @Test
    void pendingViewportRequestSendsAfterAsyncCompletionAndResendsWhenInvalidated() {
        RequestClaimMapViewportPacket.clearOpenViewportRequestsForTest();
        String screenKey = RequestClaimMapViewportPacket.logicalViewportKeyForTest(
                UUID.randomUUID(),
                RequestClaimMapViewportPacket.ScreenKind.TOWN,
                "town-a"
        );
        RequestClaimMapViewportPacket.recordViewportRequestForTest(
                screenKey,
                UUID.randomUUID(),
                new RequestClaimMapViewportPacket(
                        RequestClaimMapViewportPacket.ScreenKind.TOWN,
                        "town-a",
                        "minecraft:overworld",
                        4L,
                        1,
                        0,
                        0,
                        1
                ),
                "minecraft:overworld"
        );

        List<Long> sentRevisions = new ArrayList<>();
        ClaimMapTaskService taskService = new ClaimMapTaskService(Runnable::run, Runnable::run);
        int firstFlush = RequestClaimMapViewportPacket.flushOpenViewportRequestsForTest(
                taskService,
                state -> null,
                (state, snapshot) -> sentRevisions.add(snapshot.revision()),
                List.of()
        );
        int secondFlush = RequestClaimMapViewportPacket.flushOpenViewportRequestsForTest(
                taskService,
                state -> new ClaimMapViewportSnapshot("minecraft:overworld", state.revision(), state.radius(), state.centerChunkX(), state.centerChunkZ(), List.of(1, 2, 3, 4), false),
                (state, snapshot) -> sentRevisions.add(snapshot.revision()),
                List.of()
        );
        int thirdFlush = RequestClaimMapViewportPacket.flushOpenViewportRequestsForTest(
                taskService,
                state -> new ClaimMapViewportSnapshot("minecraft:overworld", state.revision(), state.radius(), state.centerChunkX(), state.centerChunkZ(), List.of(5, 6, 7, 8), true),
                (state, snapshot) -> sentRevisions.add(snapshot.revision()),
                List.of()
        );
        int invalidatedFlush = RequestClaimMapViewportPacket.flushOpenViewportRequestsForTest(
                taskService,
                state -> new ClaimMapViewportSnapshot("minecraft:overworld", state.revision(), state.radius(), state.centerChunkX(), state.centerChunkZ(), List.of(9, 10, 11, 12), true),
                (state, snapshot) -> sentRevisions.add(snapshot.revision()),
                List.of(screenKey)
        );

        assertEquals(0, firstFlush);
        assertEquals(1, secondFlush);
        assertEquals(0, thirdFlush);
        assertEquals(1, invalidatedFlush);
        assertEquals(List.of(4L, 4L), sentRevisions);
    }

    @Test
    void latestViewportRevisionWinsForLogicalScreenKey() {
        RequestClaimMapViewportPacket.clearOpenViewportRequestsForTest();
        UUID playerId = UUID.randomUUID();
        String screenKey = RequestClaimMapViewportPacket.logicalViewportKeyForTest(
                playerId,
                RequestClaimMapViewportPacket.ScreenKind.NATION,
                "nation-a"
        );
        RequestClaimMapViewportPacket.recordViewportRequestForTest(
                screenKey,
                playerId,
                new RequestClaimMapViewportPacket(
                        RequestClaimMapViewportPacket.ScreenKind.NATION,
                        "nation-a",
                        "minecraft:overworld",
                        7L,
                        1,
                        0,
                        0,
                        1
                ),
                "minecraft:overworld"
        );
        RequestClaimMapViewportPacket.recordViewportRequestForTest(
                screenKey,
                playerId,
                new RequestClaimMapViewportPacket(
                        RequestClaimMapViewportPacket.ScreenKind.NATION,
                        "nation-a",
                        "minecraft:overworld",
                        9L,
                        2,
                        5,
                        6,
                        1
                ),
                "minecraft:overworld"
        );

        AtomicReference<Long> deliveredRevision = new AtomicReference<>(Long.MIN_VALUE);
        ClaimMapTaskService taskService = new ClaimMapTaskService(Runnable::run, Runnable::run);
        int sent = RequestClaimMapViewportPacket.flushOpenViewportRequestsForTest(
                taskService,
                state -> new ClaimMapViewportSnapshot("minecraft:overworld", state.revision(), state.radius(), state.centerChunkX(), state.centerChunkZ(), List.of(1), true),
                (state, snapshot) -> deliveredRevision.set(snapshot.revision()),
                List.of()
        );

        assertEquals(1, sent);
        assertEquals(9L, deliveredRevision.get());
    }

    @Test
    void unregisterViewportRemovesOpenRegistrationForPlayerAndOwner() {
        RequestClaimMapViewportPacket.clearOpenViewportRequestsForTest();
        UUID playerId = UUID.randomUUID();
        String screenKey = RequestClaimMapViewportPacket.logicalViewportKeyForTest(
                playerId,
                RequestClaimMapViewportPacket.ScreenKind.TOWN,
                "town-a"
        );
        RequestClaimMapViewportPacket.recordViewportRequestForTest(
                screenKey,
                playerId,
                new RequestClaimMapViewportPacket(
                        RequestClaimMapViewportPacket.ScreenKind.TOWN,
                        "town-a",
                        "minecraft:overworld",
                        12L,
                        1,
                        0,
                        0,
                        1
                ),
                "minecraft:overworld"
        );
        assertTrue(RequestClaimMapViewportPacket.hasOpenViewportForTest(screenKey));

        RequestClaimMapViewportPacket.unregisterViewport(playerId, RequestClaimMapViewportPacket.ScreenKind.TOWN, "town-a");

        assertFalse(RequestClaimMapViewportPacket.hasOpenViewportForTest(screenKey));
    }

    @Test
    void unregisterViewportAlsoCleansTerrainStateForClosedLogicalScreen() throws Exception {
        RequestClaimMapViewportPacket.clearOpenViewportRequestsForTest();
        ClaimPreviewTerrainService service = new ClaimPreviewTerrainService();
        invokeTerrainMethod(null, "setActiveForTest", new Class<?>[] {ClaimPreviewTerrainService.class}, service);
        try {
            UUID playerId = UUID.randomUUID();
            String ownerId = "town-a";
            String screenKey = RequestClaimMapViewportPacket.logicalViewportKeyForTest(
                    playerId,
                    RequestClaimMapViewportPacket.ScreenKind.TOWN,
                    ownerId
            );
            RequestClaimMapViewportPacket.recordViewportRequestForTest(
                    screenKey,
                    playerId,
                    new RequestClaimMapViewportPacket(
                            RequestClaimMapViewportPacket.ScreenKind.TOWN,
                            ownerId,
                            "minecraft:overworld",
                            18L,
                            1,
                            0,
                            0,
                            1
                    ),
                    "minecraft:overworld"
            );
            invokeTerrainMethod(service, "enqueueViewportForTest", new Class<?>[] {
                            String.class, int.class, int.class, int.class, int.class, long.class, String.class
                    },
                    "minecraft:overworld", 0, 0, 1, 0, 18L, screenKey
            );
            invokeTerrainMethod(service, "clearQueuedWorkForTest", new Class<?>[0]);
            invokeTerrainMethod(service, "putViewportDependencyForTest", new Class<?>[] {
                            String.class, int.class, int.class, String.class
                    },
                    "minecraft:overworld", 2, 3, screenKey
            );

            RequestClaimMapViewportPacket.unregisterViewport(playerId, RequestClaimMapViewportPacket.ScreenKind.TOWN, ownerId);
            invokeTerrainMethod(service, "invalidateChunkForTest", new Class<?>[] {String.class, int.class, int.class}, "minecraft:overworld", 2, 3);

            @SuppressWarnings("unchecked")
            List<String> invalidatedViewportKeys = (List<String>) invokeTerrainMethod(
                    service,
                    "invalidatedViewportKeysForTest",
                    new Class<?>[0]
            );
            int visibleQueueSize = (int) invokeTerrainMethod(service, "visibleQueueSizeForTest", new Class<?>[0]);

            assertTrue(invalidatedViewportKeys.isEmpty());
            assertEquals(1, visibleQueueSize);
        } finally {
            invokeTerrainMethod(null, "clearActiveForTest", new Class<?>[0]);
            RequestClaimMapViewportPacket.clearOpenViewportRequestsForTest();
        }
    }

    private static Object invokeTerrainMethod(Object target,
                                              String methodName,
                                              Class<?>[] parameterTypes,
                                              Object... args) throws Exception {
        Method method = ClaimPreviewTerrainService.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}
