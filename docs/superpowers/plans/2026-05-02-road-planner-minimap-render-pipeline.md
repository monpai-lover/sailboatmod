# Road Planner Minimap Render Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the road planner minimap render real terrain on initial open and through the force-render tool, including route areas that are not already loaded by the client.

**Architecture:** Add a small client request scheduler that converts the current `RoadPlannerMapView` into bounded server snapshot requests. Reuse `RoadMapSnapshotRequestPacket` and `RoadMapSnapshotSyncPacket` as the network bridge, add a server-side column sampler, and apply returned pixels into the existing `RoadPlannerTileManager` tile cache. Keep the current client-loaded chunk renderer as a local fallback, not the primary pipeline.

**Tech Stack:** Minecraft Forge 1.20.1, Java 17, Gradle/JUnit 5, existing `SimpleChannel` packets, `NativeImage`/`DynamicTexture` tile rendering.

---

## File Structure

- Create `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMinimapRequestScheduler.java`
  - Pure client-side scheduler for initial, viewport, and force-render map snapshot requests.
  - No Minecraft runtime dependencies beyond existing value objects and `BlockPos`.

- Create `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerSnapshotTileMapper.java`
  - Pure helper that maps snapshot pixels to tile/local pixel writes.
  - Keeps tile write math testable without `NativeImage` or `Minecraft` texture manager.

- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTile.java`
  - Add synchronized single-pixel/block update method used by snapshot application.

- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManager.java`
  - Add world/dimension accessors.
  - Add `applySnapshot(...)` to update tile images from server snapshots.

- Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
  - Own the scheduler.
  - Send initial, viewport, and force-render requests.
  - Apply returned snapshots only if current session/request state matches.
  - Surface map progress in the bottom status bar.

- Modify `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadMapSnapshotRequestPacket.java`
  - Replace corridor-only fields with bounded region request fields.
  - Implement server-side handler.

- Modify `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadMapSnapshotSyncPacket.java`
  - Add request identity fields.
  - Implement client-side handler.

- Create `src/main/java/com/monpai/sailboatmod/roadplanner/map/RoadMapServerColumnSampler.java`
  - Server-side `RoadMapColumnSampler` backed by `ServerLevel`.

- Modify `src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPacketRoundTripTest.java`
  - Update packet round-trip tests for the new fields.

- Create `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMinimapRequestSchedulerTest.java`
  - Tests for initial request, pan/zoom throttling, stable region sizing, and force-render priority.

- Create `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerSnapshotTileMapperTest.java`
  - Tests mapping snapshot pixels to tile/local writes across tile boundaries.

- Create `src/test/java/com/monpai/sailboatmod/roadplanner/map/RoadMapServerColumnSamplerTest.java`
  - Tests fallback sample creation for unavailable level/chunk conditions using the pure factory method added to the sampler.

---

## Task 1: Update Snapshot Packets and Round-Trip Tests

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadMapSnapshotRequestPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadMapSnapshotSyncPacket.java`
- Modify: `src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPacketRoundTripTest.java`

- [ ] **Step 1: Update packet round-trip tests first**

In `src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPacketRoundTripTest.java`, replace `snapshotRequestRoundTripsCorridorFields()` with:

```java
    @Test
    void snapshotRequestRoundTripsViewportFields() {
        RoadMapSnapshotRequestPacket packet = new RoadMapSnapshotRequestPacket(
                UUID.randomUUID(),
                "world_a",
                "minecraft:overworld",
                42L,
                RoadMapSnapshotRequestPacket.Purpose.INITIAL_VIEWPORT,
                new BlockPos(128, 0, -64),
                256,
                MapLod.LOD_4);

        RoadMapSnapshotRequestPacket decoded = roundTrip(packet, RoadMapSnapshotRequestPacket::encode, RoadMapSnapshotRequestPacket::decode);

        assertEquals(packet, decoded);
    }
```

Replace `snapshotSyncRoundTripsPixels()` with:

```java
    @Test
    void snapshotSyncRoundTripsPixelsAndRequestIdentity() {
        RoadMapSnapshotSyncPacket packet = new RoadMapSnapshotSyncPacket(
                UUID.randomUUID(),
                "world_a",
                "minecraft:overworld",
                42L,
                RoadMapSnapshotRequestPacket.Purpose.FORCE_RENDER,
                0,
                0,
                128,
                MapLod.LOD_4,
                32,
                32,
                new int[]{0xFF00AA00, 0xFF0000AA});

        RoadMapSnapshotSyncPacket decoded = roundTrip(packet, RoadMapSnapshotSyncPacket::encode, RoadMapSnapshotSyncPacket::decode);

        assertEquals(packet, decoded);
        assertEquals(42L, decoded.requestId());
        assertEquals(RoadMapSnapshotRequestPacket.Purpose.FORCE_RENDER, decoded.purpose());
        assertEquals(0xFF0000AA, decoded.argbPixels()[1]);
    }
```

- [ ] **Step 2: Run packet tests and verify they fail**

Run:

```bash
./gradlew test --tests com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPacketRoundTripTest
```

Expected: compilation fails because request/sync packet constructors and `Purpose` do not match the tests.

- [ ] **Step 3: Replace request packet fields and codec**

Replace `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadMapSnapshotRequestPacket.java` with:

```java
package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record RoadMapSnapshotRequestPacket(UUID sessionId,
                                           String worldId,
                                           String dimensionId,
                                           long requestId,
                                           Purpose purpose,
                                           BlockPos regionCenter,
                                           int regionSize,
                                           MapLod lod) {
    public static final int MIN_REGION_SIZE = 128;
    public static final int MAX_REGION_SIZE = 512;

    public RoadMapSnapshotRequestPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        worldId = worldId == null ? "" : worldId;
        dimensionId = dimensionId == null ? "" : dimensionId;
        purpose = purpose == null ? Purpose.VIEWPORT : purpose;
        regionCenter = regionCenter == null ? BlockPos.ZERO : regionCenter.immutable();
        lod = lod == null ? MapLod.LOD_4 : lod;
        regionSize = normalizeRegionSize(regionSize, lod);
    }

    public static void encode(RoadMapSnapshotRequestPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        RoadPlannerPacketCodec.writeString(buffer, packet.worldId(), 128);
        RoadPlannerPacketCodec.writeString(buffer, packet.dimensionId(), 128);
        buffer.writeVarLong(packet.requestId());
        buffer.writeEnum(packet.purpose());
        buffer.writeBlockPos(packet.regionCenter());
        buffer.writeVarInt(packet.regionSize());
        RoadPlannerPacketCodec.writeLod(buffer, packet.lod());
    }

    public static RoadMapSnapshotRequestPacket decode(FriendlyByteBuf buffer) {
        return new RoadMapSnapshotRequestPacket(
                RoadPlannerPacketCodec.readUuid(buffer),
                buffer.readUtf(128),
                buffer.readUtf(128),
                buffer.readVarLong(),
                buffer.readEnum(Purpose.class),
                buffer.readBlockPos(),
                buffer.readVarInt(),
                RoadPlannerPacketCodec.readLod(buffer));
    }

    public static void handle(RoadMapSnapshotRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().setPacketHandled(true);
    }

    public static int normalizeRegionSize(int requestedSize, MapLod lod) {
        MapLod safeLod = lod == null ? MapLod.LOD_4 : lod;
        int clamped = Math.max(MIN_REGION_SIZE, Math.min(MAX_REGION_SIZE, requestedSize));
        int remainder = clamped % safeLod.blocksPerPixel();
        if (remainder != 0) {
            clamped += safeLod.blocksPerPixel() - remainder;
        }
        return Math.min(MAX_REGION_SIZE, clamped);
    }

    public enum Purpose {
        INITIAL_VIEWPORT,
        VIEWPORT,
        FORCE_RENDER
    }
}
```

- [ ] **Step 4: Replace sync packet fields and codec**

Replace `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadMapSnapshotSyncPacket.java` with:

```java
package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public record RoadMapSnapshotSyncPacket(UUID sessionId,
                                        String worldId,
                                        String dimensionId,
                                        long requestId,
                                        RoadMapSnapshotRequestPacket.Purpose purpose,
                                        int regionCenterX,
                                        int regionCenterZ,
                                        int regionSize,
                                        MapLod lod,
                                        int pixelWidth,
                                        int pixelHeight,
                                        int[] argbPixels) {
    public RoadMapSnapshotSyncPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        worldId = worldId == null ? "" : worldId;
        dimensionId = dimensionId == null ? "" : dimensionId;
        purpose = purpose == null ? RoadMapSnapshotRequestPacket.Purpose.VIEWPORT : purpose;
        lod = lod == null ? MapLod.LOD_4 : lod;
        argbPixels = argbPixels == null ? new int[0] : Arrays.copyOf(argbPixels, argbPixels.length);
    }

    public static void encode(RoadMapSnapshotSyncPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        RoadPlannerPacketCodec.writeString(buffer, packet.worldId(), 128);
        RoadPlannerPacketCodec.writeString(buffer, packet.dimensionId(), 128);
        buffer.writeVarLong(packet.requestId());
        buffer.writeEnum(packet.purpose());
        buffer.writeInt(packet.regionCenterX());
        buffer.writeInt(packet.regionCenterZ());
        buffer.writeVarInt(packet.regionSize());
        RoadPlannerPacketCodec.writeLod(buffer, packet.lod());
        buffer.writeVarInt(packet.pixelWidth());
        buffer.writeVarInt(packet.pixelHeight());
        buffer.writeVarInt(packet.argbPixels().length);
        for (int pixel : packet.argbPixels()) {
            buffer.writeInt(pixel);
        }
    }

    public static RoadMapSnapshotSyncPacket decode(FriendlyByteBuf buffer) {
        UUID sessionId = RoadPlannerPacketCodec.readUuid(buffer);
        String worldId = buffer.readUtf(128);
        String dimensionId = buffer.readUtf(128);
        long requestId = buffer.readVarLong();
        RoadMapSnapshotRequestPacket.Purpose purpose = buffer.readEnum(RoadMapSnapshotRequestPacket.Purpose.class);
        int regionCenterX = buffer.readInt();
        int regionCenterZ = buffer.readInt();
        int regionSize = buffer.readVarInt();
        MapLod lod = RoadPlannerPacketCodec.readLod(buffer);
        int pixelWidth = buffer.readVarInt();
        int pixelHeight = buffer.readVarInt();
        int count = buffer.readVarInt();
        int[] pixels = new int[count];
        for (int index = 0; index < count; index++) {
            pixels[index] = buffer.readInt();
        }
        return new RoadMapSnapshotSyncPacket(sessionId, worldId, dimensionId, requestId, purpose,
                regionCenterX, regionCenterZ, regionSize, lod, pixelWidth, pixelHeight, pixels);
    }

    @Override
    public int[] argbPixels() {
        return Arrays.copyOf(argbPixels, argbPixels.length);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RoadMapSnapshotSyncPacket packet)) {
            return false;
        }
        return requestId == packet.requestId
                && regionCenterX == packet.regionCenterX
                && regionCenterZ == packet.regionCenterZ
                && regionSize == packet.regionSize
                && pixelWidth == packet.pixelWidth
                && pixelHeight == packet.pixelHeight
                && Objects.equals(sessionId, packet.sessionId)
                && Objects.equals(worldId, packet.worldId)
                && Objects.equals(dimensionId, packet.dimensionId)
                && purpose == packet.purpose
                && lod == packet.lod
                && Arrays.equals(argbPixels, packet.argbPixels);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(sessionId, worldId, dimensionId, requestId, purpose,
                regionCenterX, regionCenterZ, regionSize, lod, pixelWidth, pixelHeight);
        result = 31 * result + Arrays.hashCode(argbPixels);
        return result;
    }

    public static void handle(RoadMapSnapshotSyncPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().setPacketHandled(true);
    }
}
```

- [ ] **Step 5: Run packet tests**

Run:

```bash
./gradlew test --tests com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPacketRoundTripTest
```

Expected: packet tests pass.

- [ ] **Step 6: Commit packet changes**

Run:

```bash
git add src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadMapSnapshotRequestPacket.java \
        src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadMapSnapshotSyncPacket.java \
        src/test/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPacketRoundTripTest.java
git commit -m "feat: add road map snapshot request identity"
```

---

## Task 2: Add Client Minimap Request Scheduler

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMinimapRequestScheduler.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMinimapRequestSchedulerTest.java`

- [ ] **Step 1: Write failing scheduler tests**

Create `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMinimapRequestSchedulerTest.java`:

```java
package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadMapSnapshotRequestPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerMinimapRequestSchedulerTest {
    private static final UUID SESSION = new UUID(1L, 2L);
    private static final RoadPlannerMapLayout.Rect MAP = new RoadPlannerMapLayout.Rect(40, 30, 400, 300);

    @Test
    void initialRequestUsesCurrentMapCenterAndViewportSize() {
        RoadPlannerMinimapRequestScheduler scheduler = new RoadPlannerMinimapRequestScheduler(250L);
        RoadPlannerMapView view = RoadPlannerMapView.centered(128.0D, -64.0D, 2.0D);

        Optional<RoadPlannerMinimapRequestScheduler.Request> request = scheduler.initialRequest(0L, SESSION, "world", "minecraft:overworld", view, MAP);

        assertTrue(request.isPresent());
        assertEquals(1L, request.orElseThrow().requestId());
        assertEquals(RoadMapSnapshotRequestPacket.Purpose.INITIAL_VIEWPORT, request.orElseThrow().purpose());
        assertEquals(new BlockPos(128, 0, -64), request.orElseThrow().regionCenter());
        assertEquals(256, request.orElseThrow().regionSize());
        assertEquals(MapLod.LOD_4, request.orElseThrow().lod());
    }

    @Test
    void viewportRequestsAreThrottledUntilIntervalPasses() {
        RoadPlannerMinimapRequestScheduler scheduler = new RoadPlannerMinimapRequestScheduler(250L);
        RoadPlannerMapView first = RoadPlannerMapView.centered(0.0D, 0.0D, 2.0D);
        RoadPlannerMapView second = RoadPlannerMapView.centered(512.0D, 0.0D, 2.0D);

        assertTrue(scheduler.initialRequest(0L, SESSION, "world", "minecraft:overworld", first, MAP).isPresent());
        assertTrue(scheduler.viewportRequest(100L, SESSION, "world", "minecraft:overworld", second, MAP).isEmpty());
        assertTrue(scheduler.viewportRequest(251L, SESSION, "world", "minecraft:overworld", second, MAP).isPresent());
    }

    @Test
    void forceRenderRequestBypassesThrottleAndCoversSelection() {
        RoadPlannerMinimapRequestScheduler scheduler = new RoadPlannerMinimapRequestScheduler(500L);
        scheduler.initialRequest(0L, SESSION, "world", "minecraft:overworld", RoadPlannerMapView.centered(0.0D, 0.0D, 2.0D), MAP);

        Optional<RoadPlannerMinimapRequestScheduler.Request> request = scheduler.forceRenderRequest(
                10L,
                SESSION,
                "world",
                "minecraft:overworld",
                new BlockPos(-16, 64, -16),
                new BlockPos(96, 70, 96));

        assertTrue(request.isPresent());
        assertEquals(2L, request.orElseThrow().requestId());
        assertEquals(RoadMapSnapshotRequestPacket.Purpose.FORCE_RENDER, request.orElseThrow().purpose());
        assertEquals(new BlockPos(40, 0, 40), request.orElseThrow().regionCenter());
        assertEquals(128, request.orElseThrow().regionSize());
    }

    @Test
    void acceptsOnlyLatestMatchingResponse() {
        RoadPlannerMinimapRequestScheduler scheduler = new RoadPlannerMinimapRequestScheduler(250L);
        RoadPlannerMinimapRequestScheduler.Request first = scheduler.initialRequest(
                0L,
                SESSION,
                "world",
                "minecraft:overworld",
                RoadPlannerMapView.centered(0.0D, 0.0D, 2.0D),
                MAP).orElseThrow();
        RoadPlannerMinimapRequestScheduler.Request second = scheduler.forceRenderRequest(
                1L,
                SESSION,
                "world",
                "minecraft:overworld",
                BlockPos.ZERO,
                new BlockPos(64, 64, 64)).orElseThrow();

        assertTrue(!scheduler.acceptsResponse(first.requestId(), first.purpose(), first.regionCenter(), first.regionSize()));
        assertTrue(scheduler.acceptsResponse(second.requestId(), second.purpose(), second.regionCenter(), second.regionSize()));
    }
}
```

- [ ] **Step 2: Run scheduler tests and verify they fail**

Run:

```bash
./gradlew test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerMinimapRequestSchedulerTest
```

Expected: compilation fails because `RoadPlannerMinimapRequestScheduler` does not exist.

- [ ] **Step 3: Implement the scheduler**

Create `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMinimapRequestScheduler.java`:

```java
package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadMapSnapshotRequestPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import net.minecraft.core.BlockPos;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class RoadPlannerMinimapRequestScheduler {
    public static final int MIN_REGION_SIZE = 128;
    public static final int MAX_REGION_SIZE = 512;

    private final long viewportThrottleMs;
    private long nextRequestId = 1L;
    private long lastViewportRequestAtMs = Long.MIN_VALUE;
    private Request latestRequest;

    public RoadPlannerMinimapRequestScheduler(long viewportThrottleMs) {
        this.viewportThrottleMs = Math.max(0L, viewportThrottleMs);
    }

    public Optional<Request> initialRequest(long nowMs,
                                            UUID sessionId,
                                            String worldId,
                                            String dimensionId,
                                            RoadPlannerMapView view,
                                            RoadPlannerMapLayout.Rect map) {
        return Optional.of(recordRequest(nowMs, sessionId, worldId, dimensionId,
                RoadMapSnapshotRequestPacket.Purpose.INITIAL_VIEWPORT, regionForView(view, map), true));
    }

    public Optional<Request> viewportRequest(long nowMs,
                                             UUID sessionId,
                                             String worldId,
                                             String dimensionId,
                                             RoadPlannerMapView view,
                                             RoadPlannerMapLayout.Rect map) {
        Region region = regionForView(view, map);
        if (latestRequest != null
                && latestRequest.regionCenter().equals(region.center())
                && latestRequest.regionSize() == region.size()) {
            return Optional.empty();
        }
        if (lastViewportRequestAtMs != Long.MIN_VALUE && nowMs - lastViewportRequestAtMs < viewportThrottleMs) {
            return Optional.empty();
        }
        return Optional.of(recordRequest(nowMs, sessionId, worldId, dimensionId,
                RoadMapSnapshotRequestPacket.Purpose.VIEWPORT, region, false));
    }

    public Optional<Request> forceRenderRequest(long nowMs,
                                                UUID sessionId,
                                                String worldId,
                                                String dimensionId,
                                                BlockPos a,
                                                BlockPos b) {
        return Optional.of(recordRequest(nowMs, sessionId, worldId, dimensionId,
                RoadMapSnapshotRequestPacket.Purpose.FORCE_RENDER, regionForSelection(a, b), true));
    }

    public boolean acceptsResponse(long requestId,
                                   RoadMapSnapshotRequestPacket.Purpose purpose,
                                   BlockPos regionCenter,
                                   int regionSize) {
        return latestRequest != null
                && latestRequest.requestId() == requestId
                && latestRequest.purpose() == purpose
                && latestRequest.regionCenter().equals(regionCenter == null ? BlockPos.ZERO : regionCenter.immutable())
                && latestRequest.regionSize() == regionSize;
    }

    public Request latestRequest() {
        return latestRequest;
    }

    public void markCompleted(long requestId) {
        if (latestRequest != null && latestRequest.requestId() == requestId) {
            latestRequest = latestRequest.withCompleted(true);
        }
    }

    private Request recordRequest(long nowMs,
                                  UUID sessionId,
                                  String worldId,
                                  String dimensionId,
                                  RoadMapSnapshotRequestPacket.Purpose purpose,
                                  Region region,
                                  boolean bypassThrottle) {
        if (purpose != RoadMapSnapshotRequestPacket.Purpose.FORCE_RENDER) {
            lastViewportRequestAtMs = nowMs;
        }
        Request request = new Request(
                nextRequestId++,
                sessionId == null ? new UUID(0L, 0L) : sessionId,
                worldId == null ? "" : worldId,
                dimensionId == null ? "" : dimensionId,
                purpose == null ? RoadMapSnapshotRequestPacket.Purpose.VIEWPORT : purpose,
                region.center(),
                region.size(),
                MapLod.LOD_4,
                false);
        latestRequest = request;
        return request;
    }

    private static Region regionForView(RoadPlannerMapView view, RoadPlannerMapLayout.Rect map) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(map, "map");
        int minWorldX = view.screenToWorldX(map.x(), map);
        int maxWorldX = view.screenToWorldX(map.right(), map);
        int minWorldZ = view.screenToWorldZ(map.y(), map);
        int maxWorldZ = view.screenToWorldZ(map.bottom(), map);
        int spanX = Math.abs(maxWorldX - minWorldX);
        int spanZ = Math.abs(maxWorldZ - minWorldZ);
        int size = clampRegionSize(nextPowerOfTwo(Math.max(MIN_REGION_SIZE, Math.max(spanX, spanZ))));
        int centerX = snap((minWorldX + maxWorldX) / 2);
        int centerZ = snap((minWorldZ + maxWorldZ) / 2);
        return new Region(new BlockPos(centerX, 0, centerZ), size);
    }

    private static Region regionForSelection(BlockPos a, BlockPos b) {
        BlockPos safeA = a == null ? BlockPos.ZERO : a;
        BlockPos safeB = b == null ? safeA : b;
        int minX = Math.min(safeA.getX(), safeB.getX());
        int maxX = Math.max(safeA.getX(), safeB.getX());
        int minZ = Math.min(safeA.getZ(), safeB.getZ());
        int maxZ = Math.max(safeA.getZ(), safeB.getZ());
        int size = clampRegionSize(nextPowerOfTwo(Math.max(MIN_REGION_SIZE, Math.max(maxX - minX + 1, maxZ - minZ + 1))));
        return new Region(new BlockPos(snap((minX + maxX) / 2), 0, snap((minZ + maxZ) / 2)), size);
    }

    private static int snap(int value) {
        return value;
    }

    private static int nextPowerOfTwo(int value) {
        int result = 1;
        while (result < value) {
            result <<= 1;
        }
        return result;
    }

    private static int clampRegionSize(int value) {
        return Math.max(MIN_REGION_SIZE, Math.min(MAX_REGION_SIZE, value));
    }

    private record Region(BlockPos center, int size) {
        private Region {
            center = center == null ? BlockPos.ZERO : center.immutable();
            size = clampRegionSize(size);
        }
    }

    public record Request(long requestId,
                          UUID sessionId,
                          String worldId,
                          String dimensionId,
                          RoadMapSnapshotRequestPacket.Purpose purpose,
                          BlockPos regionCenter,
                          int regionSize,
                          MapLod lod,
                          boolean completed) {
        public Request {
            sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
            worldId = worldId == null ? "" : worldId;
            dimensionId = dimensionId == null ? "" : dimensionId;
            purpose = purpose == null ? RoadMapSnapshotRequestPacket.Purpose.VIEWPORT : purpose;
            regionCenter = regionCenter == null ? BlockPos.ZERO : regionCenter.immutable();
            regionSize = clampRegionSize(regionSize);
            lod = lod == null ? MapLod.LOD_4 : lod;
        }

        public Request withCompleted(boolean nextCompleted) {
            return new Request(requestId, sessionId, worldId, dimensionId, purpose, regionCenter, regionSize, lod, nextCompleted);
        }

        public RoadMapSnapshotRequestPacket toPacket() {
            return new RoadMapSnapshotRequestPacket(sessionId, worldId, dimensionId, requestId, purpose, regionCenter, regionSize, lod);
        }
    }
}
```

- [ ] **Step 4: Run scheduler tests and verify they pass**

Run:

```bash
./gradlew test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerMinimapRequestSchedulerTest
```

Expected: all tests in `RoadPlannerMinimapRequestSchedulerTest` pass.

- [ ] **Step 5: Commit scheduler**

Run:

```bash
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMinimapRequestScheduler.java \
        src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerMinimapRequestSchedulerTest.java
git commit -m "feat: add road planner minimap request scheduler"
```

---

## Task 3: Add Server Column Sampler

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/map/RoadMapServerColumnSampler.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadMapSnapshotRequestPacket.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/map/RoadMapServerColumnSamplerTest.java`

- [ ] **Step 1: Write fallback sample tests**

Create `src/test/java/com/monpai/sailboatmod/roadplanner/map/RoadMapServerColumnSamplerTest.java`:

```java
package com.monpai.sailboatmod.roadplanner.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RoadMapServerColumnSamplerTest {
    @Test
    void unavailableSampleUsesStableUnknownColor() {
        RoadMapColumnSample sample = RoadMapServerColumnSampler.unavailableSampleForTest(10, -20);

        assertEquals(10, sample.worldX());
        assertEquals(-20, sample.worldZ());
        assertEquals(0xFF2A2A2A, sample.baseArgb());
        assertFalse(sample.water());
        assertEquals(0, sample.waterDepth());
    }
}
```

- [ ] **Step 2: Run sampler test and verify it fails**

Run:

```bash
./gradlew test --tests com.monpai.sailboatmod.roadplanner.map.RoadMapServerColumnSamplerTest
```

Expected: compilation fails because `RoadMapServerColumnSampler` does not exist.

- [ ] **Step 3: Implement server sampler**

Create `src/main/java/com/monpai/sailboatmod/roadplanner/map/RoadMapServerColumnSampler.java`:

```java
package com.monpai.sailboatmod.roadplanner.map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;

public class RoadMapServerColumnSampler implements RoadMapColumnSampler {
    private static final int UNKNOWN_ARGB = 0xFF2A2A2A;

    private final ServerLevel level;

    public RoadMapServerColumnSampler(ServerLevel level) {
        this.level = level;
    }

    @Override
    public RoadMapColumnSample sample(int worldX, int worldZ) {
        if (level == null) {
            return unavailableSample(worldX, worldZ);
        }
        try {
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1;
            if (surfaceY < level.getMinBuildHeight()) {
                return unavailableSample(worldX, worldZ);
            }
            BlockPos pos = new BlockPos(worldX, surfaceY, worldZ);
            BlockState state = level.getBlockState(pos);
            boolean water = state.getFluidState().is(Fluids.WATER);
            int waterDepth = water ? waterDepth(level, pos) : 0;
            int reliefBaseY = Math.max(
                    level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ + 1),
                    level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX - 1, worldZ));
            MapColor mapColor = state.getMapColor(level, pos);
            int argb = mapColor == null ? UNKNOWN_ARGB : 0xFF000000 | mapColor.calculateRGBColor(MapColor.Brightness.NORMAL);
            return new RoadMapColumnSample(worldX, surfaceY, worldZ, argb, water, waterDepth, reliefBaseY);
        } catch (RuntimeException ignored) {
            return unavailableSample(worldX, worldZ);
        }
    }

    public static RoadMapColumnSample unavailableSampleForTest(int worldX, int worldZ) {
        return unavailableSample(worldX, worldZ);
    }

    private static RoadMapColumnSample unavailableSample(int worldX, int worldZ) {
        return new RoadMapColumnSample(worldX, 0, worldZ, UNKNOWN_ARGB, false, 0, 0);
    }

    private static int waterDepth(ServerLevel level, BlockPos pos) {
        int depth = 0;
        BlockPos.MutableBlockPos mutable = pos.mutable();
        while (level.getBlockState(mutable).getFluidState().is(Fluids.WATER) && mutable.getY() > level.getMinBuildHeight()) {
            depth++;
            mutable.move(Direction.DOWN);
        }
        return depth;
    }
}
```

- [ ] **Step 4: Wire request packet handler to the server sampler**

In `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadMapSnapshotRequestPacket.java`, add imports:

```java
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.roadplanner.map.RoadMapColorizer;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRegion;
import com.monpai.sailboatmod.roadplanner.map.RoadMapServerColumnSampler;
import com.monpai.sailboatmod.roadplanner.map.RoadMapSnapshot;
import com.monpai.sailboatmod.roadplanner.map.RoadMapSnapshotService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
```

Replace the no-op `handle(...)` method with:

```java
    public static void handle(RoadMapSnapshotRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> handleOnServer(packet, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void handleOnServer(RoadMapSnapshotRequestPacket packet, ServerPlayer sender) {
        if (sender == null || !(sender.level() instanceof ServerLevel level)) {
            return;
        }
        RoadMapRegion region = RoadMapRegion.centeredOn(packet.regionCenter(), packet.regionSize(), packet.lod());
        RoadMapSnapshotService service = RoadMapSnapshotService.directExecutorForTest(new RoadMapColorizer());
        RoadMapSnapshot snapshot = service.buildSnapshotAsync(level.getGameTime(), region, new RoadMapServerColumnSampler(level)).join();
        RoadMapSnapshotSyncPacket response = new RoadMapSnapshotSyncPacket(
                packet.sessionId(),
                packet.worldId(),
                level.dimension().location().toString(),
                packet.requestId(),
                packet.purpose(),
                region.center().getX(),
                region.center().getZ(),
                region.regionSize(),
                region.lod(),
                region.pixelWidth(),
                region.pixelHeight(),
                snapshot.argbPixels());
        ModNetwork.CHANNEL.sendTo(response, sender.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }
```

- [ ] **Step 5: Run sampler tests and existing snapshot tests**

Run:

```bash
./gradlew test --tests com.monpai.sailboatmod.roadplanner.map.RoadMapServerColumnSamplerTest \
               --tests com.monpai.sailboatmod.roadplanner.map.RoadMapSnapshotServiceTest
```

Expected: both test classes pass.

- [ ] **Step 6: Commit sampler and request handler**

Run:

```bash
git add src/main/java/com/monpai/sailboatmod/roadplanner/map/RoadMapServerColumnSampler.java \
        src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadMapSnapshotRequestPacket.java \
        src/test/java/com/monpai/sailboatmod/roadplanner/map/RoadMapServerColumnSamplerTest.java
git commit -m "feat: sample road planner map columns on server"
```

---

## Task 4: Add Snapshot-to-Tile Mapping and Tile Application

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerSnapshotTileMapper.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTile.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManager.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerSnapshotTileMapperTest.java`

- [ ] **Step 1: Write mapping tests**

Create `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerSnapshotTileMapperTest.java`:

```java
package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadMapSnapshotRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadMapSnapshotSyncPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerSnapshotTileMapperTest {
    @Test
    void mapsLodPixelsToTileLocalBlockPixels() {
        RoadMapSnapshotSyncPacket packet = new RoadMapSnapshotSyncPacket(
                UUID.randomUUID(),
                "world",
                "minecraft:overworld",
                1L,
                RoadMapSnapshotRequestPacket.Purpose.INITIAL_VIEWPORT,
                0,
                0,
                8,
                MapLod.LOD_4,
                2,
                2,
                new int[]{0xFF111111, 0xFF222222, 0xFF333333, 0xFF444444});

        List<RoadPlannerSnapshotTileMapper.TilePixel> pixels = RoadPlannerSnapshotTileMapper.map(packet);

        assertEquals(64, pixels.size());
        assertTrue(pixels.stream().anyMatch(pixel -> pixel.tileX() == -1 && pixel.tileZ() == -1 && pixel.localX() == 252 && pixel.localZ() == 252 && pixel.argb() == 0xFF111111));
        assertTrue(pixels.stream().anyMatch(pixel -> pixel.tileX() == 0 && pixel.tileZ() == 0 && pixel.localX() == 3 && pixel.localZ() == 3 && pixel.argb() == 0xFF444444));
    }

    @Test
    void ignoresPacketsWithMismatchedPixelCount() {
        RoadMapSnapshotSyncPacket packet = new RoadMapSnapshotSyncPacket(
                UUID.randomUUID(),
                "world",
                "minecraft:overworld",
                1L,
                RoadMapSnapshotRequestPacket.Purpose.VIEWPORT,
                0,
                0,
                8,
                MapLod.LOD_4,
                2,
                2,
                new int[]{0xFF111111});

        assertTrue(RoadPlannerSnapshotTileMapper.map(packet).isEmpty());
    }
}
```

- [ ] **Step 2: Run mapper test and verify it fails**

Run:

```bash
./gradlew test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerSnapshotTileMapperTest
```

Expected: compilation fails because `RoadPlannerSnapshotTileMapper` does not exist.

- [ ] **Step 3: Implement snapshot tile mapper**

Create `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerSnapshotTileMapper.java`:

```java
package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadMapSnapshotSyncPacket;

import java.util.ArrayList;
import java.util.List;

public final class RoadPlannerSnapshotTileMapper {
    private RoadPlannerSnapshotTileMapper() {
    }

    public static List<TilePixel> map(RoadMapSnapshotSyncPacket packet) {
        if (packet == null || packet.pixelWidth() <= 0 || packet.pixelHeight() <= 0) {
            return List.of();
        }
        int[] pixels = packet.argbPixels();
        if (pixels.length != packet.pixelWidth() * packet.pixelHeight()) {
            return List.of();
        }
        int lod = packet.lod().blocksPerPixel();
        int minX = packet.regionCenterX() - packet.regionSize() / 2;
        int minZ = packet.regionCenterZ() - packet.regionSize() / 2;
        List<TilePixel> mapped = new ArrayList<>(pixels.length * lod * lod);
        for (int pixelZ = 0; pixelZ < packet.pixelHeight(); pixelZ++) {
            for (int pixelX = 0; pixelX < packet.pixelWidth(); pixelX++) {
                int argb = pixels[pixelZ * packet.pixelWidth() + pixelX];
                for (int dz = 0; dz < lod; dz++) {
                    for (int dx = 0; dx < lod; dx++) {
                        int worldX = minX + pixelX * lod + dx;
                        int worldZ = minZ + pixelZ * lod + dz;
                        int tileX = Math.floorDiv(worldX, RoadPlannerTile.TILE_SIZE_BLOCKS);
                        int tileZ = Math.floorDiv(worldZ, RoadPlannerTile.TILE_SIZE_BLOCKS);
                        int localX = Math.floorMod(worldX, RoadPlannerTile.TILE_SIZE_BLOCKS);
                        int localZ = Math.floorMod(worldZ, RoadPlannerTile.TILE_SIZE_BLOCKS);
                        mapped.add(new TilePixel(tileX, tileZ, localX, localZ, argb));
                    }
                }
            }
        }
        return List.copyOf(mapped);
    }

    public record TilePixel(int tileX, int tileZ, int localX, int localZ, int argb) {
    }
}
```

- [ ] **Step 4: Add pixel update to RoadPlannerTile**

In `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTile.java`, add this method after `updateChunk(...)`:

```java
    public synchronized void updatePixel(int localX, int localZ, int argb) {
        if (image == null) {
            return;
        }
        if (localX < 0 || localX >= TILE_PIXEL_SIZE || localZ < 0 || localZ >= TILE_PIXEL_SIZE) {
            return;
        }
        image.setPixelRGBA(localX, localZ, argb);
        loadedFromCache = true;
        dirty = true;
    }
```

- [ ] **Step 5: Add snapshot application to RoadPlannerTileManager**

In `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManager.java`, add imports:

```java
import com.monpai.sailboatmod.network.packet.roadplanner.RoadMapSnapshotSyncPacket;
import java.util.HashSet;
import java.util.Set;
```

Add accessors after `loadedTileCount()`:

```java
    public String worldId() {
        return worldId;
    }

    public String dimensionId() {
        return dimensionId;
    }
```

Add `applySnapshot` after `applyChunkImage(ChunkPos chunkPos, RoadPlannerChunkImage chunkImage)`:

```java
    public int applySnapshot(RoadMapSnapshotSyncPacket packet) {
        if (packet == null) {
            return 0;
        }
        if (!packet.worldId().isBlank() && !packet.worldId().equals(worldId)) {
            return 0;
        }
        if (!packet.dimensionId().isBlank() && !packet.dimensionId().equals(dimensionId)) {
            return 0;
        }
        Set<RoadPlannerTile> touched = new HashSet<>();
        int appliedPixels = 0;
        for (RoadPlannerSnapshotTileMapper.TilePixel pixel : RoadPlannerSnapshotTileMapper.map(packet)) {
            RoadPlannerTile tile = getOrCreateTile(pixel.tileX(), pixel.tileZ());
            tile.updatePixel(pixel.localX(), pixel.localZ(), pixel.argb());
            touched.add(tile);
            appliedPixels++;
        }
        for (RoadPlannerTile tile : touched) {
            saveTile(tile);
        }
        return appliedPixels;
    }
```

- [ ] **Step 6: Run mapper tests**

Run:

```bash
./gradlew test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerSnapshotTileMapperTest
```

Expected: mapper tests pass.

- [ ] **Step 7: Compile client tile changes**

Run:

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit tile application**

Run:

```bash
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerSnapshotTileMapper.java \
        src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTile.java \
        src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTileManager.java \
        src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerSnapshotTileMapperTest.java
git commit -m "feat: apply road map snapshots to minimap tiles"
```

---

## Task 5: Wire Snapshot Requests Into RoadPlannerScreen

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadMapSnapshotSyncPacket.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java`

- [ ] **Step 1: Add screen behavior tests for status state**

In `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java`, add imports:

```java
import com.monpai.sailboatmod.network.packet.roadplanner.RoadMapSnapshotRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadMapSnapshotSyncPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
```

Add this test before the helper methods:

```java
    @Test
    void staleSnapshotDoesNotMarkMapUpdated() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadMapSnapshotSyncPacket stale = new RoadMapSnapshotSyncPacket(
                screen.state().sessionId(),
                "world",
                "minecraft:overworld",
                999L,
                RoadMapSnapshotRequestPacket.Purpose.INITIAL_VIEWPORT,
                0,
                0,
                128,
                MapLod.LOD_4,
                32,
                32,
                new int[32 * 32]);

        screen.applyMapSnapshot(stale);

        assertTrue(screen.mapStatusLineForTest().contains("等待地图"));
    }
```

- [ ] **Step 2: Run screen behavior test and verify it fails**

Run:

```bash
./gradlew test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest
```

Expected: compilation fails because `RoadPlannerScreen.applyMapSnapshot(...)` does not exist.

- [ ] **Step 3: Add scheduler fields and status text**

In `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java`, add import:

```java
import com.monpai.sailboatmod.network.packet.roadplanner.RoadMapSnapshotRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadMapSnapshotSyncPacket;
```

Add fields after `tileRenderScheduler`:

```java
    private final RoadPlannerMinimapRequestScheduler mapRequestScheduler = new RoadPlannerMinimapRequestScheduler(350L);
    private String mapStatusLine = "等待地图请求";
```

- [ ] **Step 4: Schedule initial request after layout is ready**

In `init()`, replace:

```java
    protected void init() {
        recomputeLayout();
    }
```

with:

```java
    protected void init() {
        recomputeLayout();
        requestInitialMapSnapshot();
    }
```

Add helper methods after `recomputeLayout()`:

```java
    private void requestInitialMapSnapshot() {
        if (testMode || tileManager == null || mapLayout == null) {
            mapStatusLine = "等待地图请求";
            return;
        }
        mapRequestScheduler.initialRequest(System.currentTimeMillis(), state.sessionId(), tileManager.worldId(), tileManager.dimensionId(), mapView, mapLayout.map())
                .ifPresent(this::sendMapSnapshotRequest);
    }

    private void requestViewportMapSnapshot() {
        if (testMode || tileManager == null || mapLayout == null) {
            return;
        }
        mapRequestScheduler.viewportRequest(System.currentTimeMillis(), state.sessionId(), tileManager.worldId(), tileManager.dimensionId(), mapView, mapLayout.map())
                .ifPresent(this::sendMapSnapshotRequest);
    }

    private void requestForceRenderMapSnapshot(BlockPos start, BlockPos end) {
        if (testMode || tileManager == null) {
            return;
        }
        mapRequestScheduler.forceRenderRequest(System.currentTimeMillis(), state.sessionId(), tileManager.worldId(), tileManager.dimensionId(), start, end)
                .ifPresent(this::sendMapSnapshotRequest);
    }

    private void sendMapSnapshotRequest(RoadPlannerMinimapRequestScheduler.Request request) {
        ModNetwork.CHANNEL.sendToServer(request.toPacket());
        mapStatusLine = switch (request.purpose()) {
            case INITIAL_VIEWPORT -> "地图: 初始加载中";
            case VIEWPORT -> "地图: 视口更新中";
            case FORCE_RENDER -> "地图: 强制渲染中";
        };
    }
```

- [ ] **Step 5: Request viewport updates during tick**

In `tick()`, replace:

```java
        renderPlayerAreaChunks(6);
        processCorridorDirect(4);
```

with:

```java
        requestViewportMapSnapshot();
        renderPlayerAreaChunks(2);
        processCorridorDirect(2);
```

This keeps the local loaded-chunk fallback but lowers its per-tick budget so server snapshots are the primary path.

- [ ] **Step 6: Request force-render snapshot on mouse release**

In `mouseReleased(...)`, inside the force-render block, replace:

```java
            tileRenderScheduler.clear();
            forceRenderQueue.enqueueSelection(forceRenderSelectionStart, forceRenderSelectionEnd == null ? forceRenderSelectionStart : forceRenderSelectionEnd, "\u9009\u533a\u6e32\u67d3");
            statusLine = "\u5df2\u52a0\u5165\u5f3a\u5236\u6e32\u67d3\u9009\u533a";
```

with:

```java
            BlockPos renderEnd = forceRenderSelectionEnd == null ? forceRenderSelectionStart : forceRenderSelectionEnd;
            tileRenderScheduler.clear();
            forceRenderQueue.enqueueSelection(forceRenderSelectionStart, renderEnd, "\u9009\u533a\u6e32\u67d3");
            requestForceRenderMapSnapshot(forceRenderSelectionStart, renderEnd);
            statusLine = "\u5df2\u53d1\u9001\u5f3a\u5236\u6e32\u67d3\u9009\u533a";
```

- [ ] **Step 7: Apply returned snapshots**

Add these public methods near the existing test-visible methods after `statusLineForTest()`:

```java
    public String mapStatusLineForTest() {
        return mapStatusLine;
    }

    public void applyMapSnapshot(RoadMapSnapshotSyncPacket packet) {
        if (packet == null || !state.sessionId().equals(packet.sessionId())) {
            return;
        }
        BlockPos center = new BlockPos(packet.regionCenterX(), 0, packet.regionCenterZ());
        if (!mapRequestScheduler.acceptsResponse(packet.requestId(), packet.purpose(), center, packet.regionSize())) {
            mapStatusLine = "等待地图请求";
            return;
        }
        if (tileManager == null) {
            mapStatusLine = "地图: tile 管理器未就绪";
            return;
        }
        int applied = tileManager.applySnapshot(packet);
        mapRequestScheduler.markCompleted(packet.requestId());
        mapStatusLine = applied > 0 ? "地图: 已更新" : "地图: 未收到可用像素";
    }
```

- [ ] **Step 8: Show map status in bottom status bar**

In `renderStatusBar(...)`, replace:

```java
        graphics.drawString(font, statusLine + " | " + routeText + progressText, status.x() + 10, status.y() + 9, RoadPlannerMapTheme.TEXT, false);
```

with:

```java
        graphics.drawString(font, statusLine + " | " + routeText + progressText + " | " + mapStatusLine, status.x() + 10, status.y() + 9, RoadPlannerMapTheme.TEXT, false);
```

- [ ] **Step 9: Wire sync packet handler to the active planner screen**

In `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadMapSnapshotSyncPacket.java`, add imports:

```java
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreen;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
```

Replace the no-op `handle(...)` method with:

```java
    public static void handle(RoadMapSnapshotSyncPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleOnClient(packet)));
        contextSupplier.get().setPacketHandled(true);
    }

    private static void handleOnClient(RoadMapSnapshotSyncPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof RoadPlannerScreen screen) {
            screen.applyMapSnapshot(packet);
        }
    }
```

- [ ] **Step 10: Run screen behavior tests**

Run:

```bash
./gradlew test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest
```

Expected: all tests in `RoadPlannerScreenBehaviorTest` pass.

- [ ] **Step 11: Compile Java**

Run:

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 12: Commit screen wiring**

Run:

```bash
git add src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreen.java \
        src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadMapSnapshotSyncPacket.java \
        src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerScreenBehaviorTest.java
git commit -m "feat: request minimap snapshots from road planner screen"
```

---

## Task 6: Full Verification and JarJar Build

**Files:**
- No source file changes expected.

- [ ] **Step 1: Run focused minimap and packet tests**

Run:

```bash
./gradlew test --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerMinimapRequestSchedulerTest \
               --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerSnapshotTileMapperTest \
               --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreenBehaviorTest \
               --tests com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPacketRoundTripTest \
               --tests com.monpai.sailboatmod.roadplanner.map.RoadMapServerColumnSamplerTest \
               --tests com.monpai.sailboatmod.roadplanner.map.RoadMapSnapshotServiceTest
```

Expected: all selected tests pass.

- [ ] **Step 2: Run compile**

Run:

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Build the all-in-one jar**

Run:

```bash
./gradlew jarJar
```

Expected: `BUILD SUCCESSFUL` and `build/libs/sailboatmod-1.3.7-all.jar` has a fresh timestamp.

- [ ] **Step 4: Record manual verification checklist in the final response**

Use this checklist after installing `build/libs/sailboatmod-1.3.7-all.jar` into the test client:

```text
1. Open planner between two far towns with a fresh `roadplanner_map_cache`.
2. Confirm the gray checkerboard changes to terrain after initial load.
3. Pan to a new nearby area and confirm the bottom status shows viewport update and terrain appears.
4. Select 强制渲染, drag a rectangle over an unloaded route area, release, and confirm status shows 强制渲染中 then 已更新.
5. Close and reopen planner in the same world/dimension and confirm cached tiles appear quickly.
6. Switch dimension or world and confirm old terrain cache is not shown under the new context.
```

- [ ] **Step 5: Commit verification note only if a file was intentionally updated**

If no files changed during verification, do not create an empty commit. If a verification note file is added, run:

```bash
git add docs/superpowers/plans/2026-05-02-road-planner-minimap-render-pipeline.md
git commit -m "docs: record road planner minimap verification"
```Replace `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadMapSnapshotRequestPacket.java` with:

```java
package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record RoadMapSnapshotRequestPacket(UUID sessionId,
                                           String worldId,
                                           String dimensionId,
                                           long requestId,
                                           Purpose purpose,
                                           BlockPos regionCenter,
                                           int regionSize,
                                           MapLod lod) {
    public static final int MIN_REGION_SIZE = 128;
    public static final int MAX_REGION_SIZE = 512;

    public RoadMapSnapshotRequestPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        worldId = worldId == null ? "" : worldId;
        dimensionId = dimensionId == null ? "" : dimensionId;
        purpose = purpose == null ? Purpose.VIEWPORT : purpose;
        regionCenter = regionCenter == null ? BlockPos.ZERO : regionCenter.immutable();
        lod = lod == null ? MapLod.LOD_4 : lod;
        regionSize = normalizeRegionSize(regionSize, lod);
    }

    public static void encode(RoadMapSnapshotRequestPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        RoadPlannerPacketCodec.writeString(buffer, packet.worldId(), 128);
        RoadPlannerPacketCodec.writeString(buffer, packet.dimensionId(), 128);
        buffer.writeVarLong(packet.requestId());
        buffer.writeEnum(packet.purpose());
        buffer.writeBlockPos(packet.regionCenter());
        buffer.writeVarInt(packet.regionSize());
        RoadPlannerPacketCodec.writeLod(buffer, packet.lod());
    }

    public static RoadMapSnapshotRequestPacket decode(FriendlyByteBuf buffer) {
        return new RoadMapSnapshotRequestPacket(
                RoadPlannerPacketCodec.readUuid(buffer),
                buffer.readUtf(128),
                buffer.readUtf(128),
                buffer.readVarLong(),
                buffer.readEnum(Purpose.class),
                buffer.readBlockPos(),
                buffer.readVarInt(),
                RoadPlannerPacketCodec.readLod(buffer));
    }

    public static void handle(RoadMapSnapshotRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().setPacketHandled(true);
    }

    public static int normalizeRegionSize(int requestedSize, MapLod lod) {
        MapLod safeLod = lod == null ? MapLod.LOD_4 : lod;
        int clamped = Math.max(MIN_REGION_SIZE, Math.min(MAX_REGION_SIZE, requestedSize));
        int remainder = clamped % safeLod.blocksPerPixel();
        if (remainder != 0) {
            clamped += safeLod.blocksPerPixel() - remainder;
        }
        return Math.min(MAX_REGION_SIZE, clamped);
    }

    public enum Purpose {
        INITIAL_VIEWPORT,
        VIEWPORT,
        FORCE_RENDER
    }
}
```

- [ ] **Step 4: Replace sync packet fields and codec**
