# Claim Map Server Tile Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current claim-map preview flow with a server-driven chunk-tile cache and complete-viewport snapshot pipeline for town and nation claim screens.

**Architecture:** Reuse the current overview/open-screen flow for metadata only, but move terrain delivery to a dedicated viewport request path. Persist chunk tiles in world saved data, keep hot chunk/snapshot caches in memory, and assemble complete viewport snapshots only after the visible area is fully ready. All world access stays on the server thread; request orchestration, cache checks, and snapshot assembly stay async.

**Tech Stack:** Java 17, Forge 1.20.1, existing `ClaimMapTaskService`, existing `ClaimPreviewTerrainService`, JUnit 5, Forge packet pipeline, existing overview screen tests.

---

## Scope Check

This plan covers one subsystem only: town/nation claim-map terrain delivery. It intentionally does not touch road planning, construction, or claim border visuals.

The key behavioral deltas from the current implementation are:

- drag requests no longer trigger client terrain resampling
- server caches chunk tiles instead of only one-shot preview arrays
- uncached dragged-to areas block until the visible viewport is complete
- the refresh button clears only the visible viewport footprint
- terrain changes immediately invalidate and rebuild affected tiles

## File Structure Map

### Server Tile Cache and Viewport Assembly

- Create: `src/main/java/com/monpai/sailboatmod/nation/service/ClaimMapViewportSnapshot.java`
  - immutable snapshot payload for a complete viewport image
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/ClaimMapViewportService.java`
  - server-side viewport coordinator, chunk-tile completeness checks, visible/prefetch scheduling, complete-only snapshot assembly
- Modify: `src/main/java/com/monpai/sailboatmod/nation/data/TerrainPreviewSavedData.java`
  - persist `SUB x SUB` chunk tiles rather than one single color and migrate legacy entries
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ClaimPreviewTerrainService.java`
  - become the chunk-tile sampler/invalidation worker used by the viewport service
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ClaimMapTaskService.java`
  - expose per-screen latest-request-wins helpers for viewport tasks and refresh pushes
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/TerrainCacheInvalidator.java`
  - trigger immediate chunk-tile invalidation and rebuild scheduling

### Network and Overview Flow

- Create: `src/main/java/com/monpai/sailboatmod/network/packet/RequestClaimMapViewportPacket.java`
  - client-to-server viewport request packet for open/drag recenter
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/RefreshClaimMapViewportPacket.java`
  - client-to-server visible-area refresh packet
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/SyncClaimPreviewMapPacket.java`
  - carry complete viewport snapshots rather than raw one-shot preview colors
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/OpenTownMenuPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/OpenNationMenuPacket.java`
  - stop using open requests as the terrain-fetch path; keep them for overview metadata only
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/ClearTerrainCachePacket.java`
  - retire global clear behavior in favor of visible-area refresh routing or delete after references are removed
- Modify: `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
  - register request/refresh packets and updated sync packet
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/TownOverviewService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/NationOverviewService.java`
  - emit loading metadata only and do not sample terrain directly during overview build

### Client Hooks and Screens

- Modify: `src/main/java/com/monpai/sailboatmod/client/TownClientHooks.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/NationClientHooks.java`
  - keep the last completed viewport snapshot, revision bookkeeping, and request dispatch helpers
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreen.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/nation/NationHomeScreen.java`
  - send explicit viewport/refresh packets, keep rendering the last completed snapshot while dragging, stop local terrain resampling
- Modify or delete: `src/main/java/com/monpai/sailboatmod/client/ClaimMapRasterizer.java`
- Modify or delete: `src/main/java/com/monpai/sailboatmod/client/ClaimMapRenderTaskService.java`
  - no longer the primary terrain-generation path; remove or reduce to compatibility glue if still referenced
- Modify: `src/main/java/com/monpai/sailboatmod/client/cache/TerrainColorClientCache.java`
  - optional compatibility cache only; it should no longer be a source of truth for live viewport generation

### Tests

- Create: `src/test/java/com/monpai/sailboatmod/nation/data/TerrainPreviewSavedDataTest.java`
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/ClaimMapViewportServiceTest.java`
- Create: `src/test/java/com/monpai/sailboatmod/network/packet/ClaimMapViewportPacketRoundTripTest.java`
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/ClaimPreviewTerrainServiceTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/ClaimMapTaskServiceTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/network/packet/ClaimOverviewPacketRoundTripTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreenTest.java`

## Task 1: Persist Chunk Tiles Instead of Single-Color Entries

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/data/TerrainPreviewSavedData.java`
- Create: `src/test/java/com/monpai/sailboatmod/nation/data/TerrainPreviewSavedDataTest.java`

- [ ] **Step 1: Write the failing saved-data tests**

```java
package com.monpai.sailboatmod.nation.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TerrainPreviewSavedDataTest {
    @Test
    void legacySingleColorEntriesExpandToFullSubTileArray() {
        CompoundTag root = new CompoundTag();
        ListTag entries = new ListTag();
        CompoundTag entry = new CompoundTag();
        entry.putString("Dimension", "minecraft:overworld");
        entry.putInt("ChunkX", 4);
        entry.putInt("ChunkZ", 9);
        entry.putInt("Color", 0xFF336699);
        entries.add(entry);
        root.put("Entries", entries);

        TerrainPreviewSavedData data = TerrainPreviewSavedData.load(root);

        assertArrayEquals(
                new int[] {0xFF336699, 0xFF336699, 0xFF336699, 0xFF336699},
                data.getTile("minecraft:overworld", 4, 9)
        );
    }

    @Test
    void saveRoundTripsSubTileColorsAndRemovesTiles() {
        TerrainPreviewSavedData data = new TerrainPreviewSavedData();
        data.putTile("minecraft:overworld", 1, 2, new int[] {0xFF111111, 0xFF222222, 0xFF333333, 0xFF444444});

        CompoundTag saved = data.save(new CompoundTag());
        TerrainPreviewSavedData reloaded = TerrainPreviewSavedData.load(saved);

        assertArrayEquals(
                new int[] {0xFF111111, 0xFF222222, 0xFF333333, 0xFF444444},
                reloaded.getTile("minecraft:overworld", 1, 2)
        );

        reloaded.removeTile("minecraft:overworld", 1, 2);
        assertNull(reloaded.getTile("minecraft:overworld", 1, 2));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.data.TerrainPreviewSavedDataTest" -q`  
Expected: FAIL because `getTile`, `putTile`, and legacy tile expansion do not exist.

- [ ] **Step 3: Write the minimal persistence implementation**

```java
// TerrainPreviewSavedData.java
private static final int DEFAULT_COLOR = 0xFF33414A;
private final Map<String, int[]> tiles = new LinkedHashMap<>();

public static TerrainPreviewSavedData load(CompoundTag tag) {
    TerrainPreviewSavedData data = new TerrainPreviewSavedData();
    ListTag entries = tag.getList("Entries", Tag.TAG_COMPOUND);
    for (Tag raw : entries) {
        if (!(raw instanceof CompoundTag entry)) {
            continue;
        }
        String dimension = entry.getString("Dimension");
        int chunkX = entry.getInt("ChunkX");
        int chunkZ = entry.getInt("ChunkZ");
        if (dimension.isBlank()) {
            continue;
        }
        int[] colors;
        if (entry.contains("Colors", Tag.TAG_INT_ARRAY)) {
            colors = normalizeTile(entry.getIntArray("Colors"));
        } else {
            int legacyColor = normalizeColor(entry.getInt("Color"));
            colors = new int[] {legacyColor, legacyColor, legacyColor, legacyColor};
        }
        data.tiles.put(key(dimension, chunkX, chunkZ), colors);
    }
    return data;
}

@Override
public CompoundTag save(CompoundTag tag) {
    ListTag entries = new ListTag();
    for (Map.Entry<String, int[]> entry : tiles.entrySet()) {
        String[] parts = entry.getKey().split("\\|", 3);
        if (parts.length != 3) {
            continue;
        }
        CompoundTag compound = new CompoundTag();
        compound.putString("Dimension", parts[0]);
        compound.putInt("ChunkX", Integer.parseInt(parts[1]));
        compound.putInt("ChunkZ", Integer.parseInt(parts[2]));
        compound.putIntArray("Colors", normalizeTile(entry.getValue()));
        entries.add(compound);
    }
    tag.put("Entries", entries);
    return tag;
}

public int[] getTile(String dimensionId, int chunkX, int chunkZ) {
    int[] colors = tiles.get(key(dimensionId, chunkX, chunkZ));
    return colors == null ? null : colors.clone();
}

public void putTile(String dimensionId, int chunkX, int chunkZ, int[] colors) {
    if (dimensionId == null || dimensionId.isBlank()) {
        return;
    }
    tiles.put(key(dimensionId, chunkX, chunkZ), normalizeTile(colors));
    trimToSize();
    setDirty();
}

public void removeTile(String dimensionId, int chunkX, int chunkZ) {
    if (dimensionId == null || dimensionId.isBlank()) {
        return;
    }
    if (tiles.remove(key(dimensionId, chunkX, chunkZ)) != null) {
        setDirty();
    }
}

private static int[] normalizeTile(int[] colors) {
    int[] source = colors == null ? new int[0] : colors;
    int[] normalized = new int[] {DEFAULT_COLOR, DEFAULT_COLOR, DEFAULT_COLOR, DEFAULT_COLOR};
    for (int i = 0; i < Math.min(source.length, normalized.length); i++) {
        normalized[i] = normalizeColor(source[i]);
    }
    return normalized;
}

private static int normalizeColor(int color) {
    return 0xFF000000 | (color & 0x00FFFFFF);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.data.TerrainPreviewSavedDataTest" -q`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/data/TerrainPreviewSavedData.java src/test/java/com/monpai/sailboatmod/nation/data/TerrainPreviewSavedDataTest.java
git commit -m "Persist claim map chunk tiles"
```

## Task 2: Add Viewport Snapshot Coordination and Latest-Only Request Semantics

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/ClaimMapViewportSnapshot.java`
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/ClaimMapViewportService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ClaimMapTaskService.java`
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/ClaimMapViewportServiceTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/ClaimMapTaskServiceTest.java`

- [ ] **Step 1: Write the failing viewport coordination tests**

```java
package com.monpai.sailboatmod.nation.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimMapViewportServiceTest {
    @Test
    void serviceDoesNotReturnSnapshotUntilVisibleAreaIsComplete() {
        ClaimMapViewportService service = new ClaimMapViewportService((dimension, chunkX, chunkZ) -> null);

        ClaimMapViewportSnapshot snapshot = service.tryBuildSnapshot(
                new ClaimMapViewportService.ViewportRequest("town", "minecraft:overworld", 10L, 8, 0, 0, 3),
                List.of()
        );

        assertNull(snapshot);
        assertEquals(289, service.pendingVisibleChunkCountForTest());
        assertTrue(service.pendingPrefetchChunkCountForTest() > 289);
    }

    @Test
    void latestRequestReplacesOlderRequestForSameScreenKey() {
        ClaimMapTaskService service = new ClaimMapTaskService(Runnable::run, Runnable::run);
        AtomicInteger appliedRevision = new AtomicInteger();

        ClaimMapTaskService.TaskHandle<Integer> first = service.submitForTest(
                new ClaimMapTaskService.TaskKey("town-viewport", "player-a|town-a"),
                () -> 1,
                appliedRevision::set
        );
        ClaimMapTaskService.TaskHandle<Integer> second = service.submitForTest(
                new ClaimMapTaskService.TaskKey("town-viewport", "player-a|town-a"),
                () -> 2,
                appliedRevision::set
        );

        first.completeForTest();
        second.completeForTest();

        assertEquals(2, appliedRevision.get());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ClaimMapViewportServiceTest" --tests "com.monpai.sailboatmod.nation.service.ClaimMapTaskServiceTest" -q`  
Expected: FAIL because `ClaimMapViewportService` and snapshot request types do not exist.

- [ ] **Step 3: Write the minimal viewport coordination implementation**

```java
// ClaimMapViewportSnapshot.java
package com.monpai.sailboatmod.nation.service;

import java.util.List;

public record ClaimMapViewportSnapshot(String dimensionId,
                                       long revision,
                                       int radius,
                                       int centerChunkX,
                                       int centerChunkZ,
                                       List<Integer> pixels) {
    public ClaimMapViewportSnapshot {
        dimensionId = dimensionId == null ? "" : dimensionId;
        pixels = pixels == null ? List.of() : List.copyOf(pixels);
    }
}
```

```java
// ClaimMapViewportService.java
package com.monpai.sailboatmod.nation.service;

import java.util.ArrayList;
import java.util.List;

public class ClaimMapViewportService {
    @FunctionalInterface
    public interface TileLookup {
        int[] get(String dimensionId, int chunkX, int chunkZ);
    }

    public record ViewportRequest(String screenKind,
                                  String dimensionId,
                                  long revision,
                                  int radius,
                                  int centerChunkX,
                                  int centerChunkZ,
                                  int prefetchRadius) {}

    private final TileLookup tileLookup;
    private int pendingVisibleChunkCount;
    private int pendingPrefetchChunkCount;

    public ClaimMapViewportService(TileLookup tileLookup) {
        this.tileLookup = tileLookup;
    }

    public ClaimMapViewportSnapshot tryBuildSnapshot(ViewportRequest request, List<Integer> claimOverlayColors) {
        pendingVisibleChunkCount = 0;
        pendingPrefetchChunkCount = 0;
        ArrayList<Integer> pixels = new ArrayList<>();
        for (int dz = -request.radius(); dz <= request.radius(); dz++) {
            for (int dx = -request.radius(); dx <= request.radius(); dx++) {
                int[] tile = tileLookup.get(request.dimensionId(), request.centerChunkX() + dx, request.centerChunkZ() + dz);
                if (tile == null) {
                    pendingVisibleChunkCount++;
                    continue;
                }
                for (int color : tile) {
                    pixels.add(color);
                }
            }
        }
        if (pendingVisibleChunkCount > 0) {
            pendingPrefetchChunkCount = countPrefetchChunks(request);
            return null;
        }
        return new ClaimMapViewportSnapshot(request.dimensionId(), request.revision(), request.radius(), request.centerChunkX(), request.centerChunkZ(), pixels);
    }

    private int countPrefetchChunks(ViewportRequest request) {
        int outer = request.radius() + Math.max(0, request.prefetchRadius());
        int diameter = outer * 2 + 1;
        int visibleDiameter = request.radius() * 2 + 1;
        return diameter * diameter - visibleDiameter * visibleDiameter;
    }

    int pendingVisibleChunkCountForTest() { return pendingVisibleChunkCount; }
    int pendingPrefetchChunkCountForTest() { return pendingPrefetchChunkCount; }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ClaimMapViewportServiceTest" --tests "com.monpai.sailboatmod.nation.service.ClaimMapTaskServiceTest" -q`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/ClaimMapViewportSnapshot.java src/main/java/com/monpai/sailboatmod/nation/service/ClaimMapViewportService.java src/main/java/com/monpai/sailboatmod/nation/service/ClaimMapTaskService.java src/test/java/com/monpai/sailboatmod/nation/service/ClaimMapViewportServiceTest.java src/test/java/com/monpai/sailboatmod/nation/service/ClaimMapTaskServiceTest.java
git commit -m "Add claim map viewport coordination"
```

## Task 3: Add Explicit Viewport Request and Refresh Packet Flow

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/RequestClaimMapViewportPacket.java`
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/RefreshClaimMapViewportPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/SyncClaimPreviewMapPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/OpenTownMenuPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/OpenNationMenuPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/ClearTerrainCachePacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
- Modify: `src/test/java/com/monpai/sailboatmod/network/packet/ClaimOverviewPacketRoundTripTest.java`
- Create: `src/test/java/com/monpai/sailboatmod/network/packet/ClaimMapViewportPacketRoundTripTest.java`

- [ ] **Step 1: Write the failing packet round-trip tests**

```java
package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.service.ClaimMapViewportSnapshot;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaimMapViewportPacketRoundTripTest {
    @Test
    void requestPacketRoundTripsViewportCoordinatesAndPrefetchRadius() {
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

        assertEquals(40, decoded.centerChunkX());
        assertEquals(-12, decoded.centerChunkZ());
        assertEquals(3, decoded.prefetchRadius());
    }

    @Test
    void syncPacketRoundTripsFullViewportPixels() {
        SyncClaimPreviewMapPacket packet = new SyncClaimPreviewMapPacket(
                SyncClaimPreviewMapPacket.ScreenKind.NATION,
                new ClaimMapViewportSnapshot("minecraft:overworld", 11L, 6, 2, 3, List.of(0xFF010203, 0xFF0A0B0C))
        );

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        SyncClaimPreviewMapPacket.encode(packet, buffer);
        SyncClaimPreviewMapPacket decoded = SyncClaimPreviewMapPacket.decode(buffer);

        assertEquals(11L, decoded.snapshot().revision());
        assertEquals(List.of(0xFF010203, 0xFF0A0B0C), decoded.snapshot().pixels());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.network.packet.ClaimMapViewportPacketRoundTripTest" --tests "com.monpai.sailboatmod.network.packet.ClaimOverviewPacketRoundTripTest" -q`  
Expected: FAIL because request/refresh packets and new sync snapshot payload do not exist.

- [ ] **Step 3: Write the minimal packet and registration implementation**

```java
// RequestClaimMapViewportPacket.java
public record RequestClaimMapViewportPacket(ScreenKind screenKind,
                                            String ownerId,
                                            String dimensionId,
                                            long revision,
                                            int radius,
                                            int centerChunkX,
                                            int centerChunkZ,
                                            int prefetchRadius) {
    public enum ScreenKind { TOWN, NATION }
}
```

```java
// RefreshClaimMapViewportPacket.java
public record RefreshClaimMapViewportPacket(RequestClaimMapViewportPacket.ScreenKind screenKind,
                                            String ownerId,
                                            String dimensionId,
                                            long revision,
                                            int radius,
                                            int centerChunkX,
                                            int centerChunkZ) {}
```

```java
// SyncClaimPreviewMapPacket.java
public record SyncClaimPreviewMapPacket(ScreenKind screenKind, ClaimMapViewportSnapshot snapshot) {
    public static void encode(SyncClaimPreviewMapPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.screenKind());
        buffer.writeUtf(packet.snapshot().dimensionId(), 64);
        buffer.writeLong(packet.snapshot().revision());
        buffer.writeVarInt(packet.snapshot().radius());
        buffer.writeInt(packet.snapshot().centerChunkX());
        buffer.writeInt(packet.snapshot().centerChunkZ());
        buffer.writeVarInt(packet.snapshot().pixels().size());
        for (Integer color : packet.snapshot().pixels()) {
            buffer.writeInt(color == null ? 0xFF33414A : color);
        }
    }
}
```

```java
// OpenTownMenuPacket / OpenNationMenuPacket
// Keep these packets for screen metadata only.
// Remove the direct terrain sampling side effect by deleting the viewport sampling submission
// from TownOverviewService and NationOverviewService, leaving ClaimPreviewMapState.loading(...)
// in the overview payload.
```

```java
// ClearTerrainCachePacket.java
// Replace global clear with a visible-area refresh packet path.
// If no callers remain after screen changes, delete this packet and its ModNetwork registration.
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.network.packet.ClaimMapViewportPacketRoundTripTest" --tests "com.monpai.sailboatmod.network.packet.ClaimOverviewPacketRoundTripTest" -q`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/network/packet/RequestClaimMapViewportPacket.java src/main/java/com/monpai/sailboatmod/network/packet/RefreshClaimMapViewportPacket.java src/main/java/com/monpai/sailboatmod/network/packet/SyncClaimPreviewMapPacket.java src/main/java/com/monpai/sailboatmod/network/packet/OpenTownMenuPacket.java src/main/java/com/monpai/sailboatmod/network/packet/OpenNationMenuPacket.java src/main/java/com/monpai/sailboatmod/network/packet/ClearTerrainCachePacket.java src/main/java/com/monpai/sailboatmod/network/ModNetwork.java src/test/java/com/monpai/sailboatmod/network/packet/ClaimMapViewportPacketRoundTripTest.java src/test/java/com/monpai/sailboatmod/network/packet/ClaimOverviewPacketRoundTripTest.java
git commit -m "Add claim map viewport request packets"
```

## Task 4: Refactor Terrain Sampling Into a Server Tile Worker With Visible/Prefetch Priorities

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ClaimPreviewTerrainService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ClaimMapViewportService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/TerrainCacheInvalidator.java`
- Modify: `src/main/java/com/monpai/sailboatmod/ServerEvents.java`
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/ClaimPreviewTerrainServiceTest.java`

- [ ] **Step 1: Write the failing sampling/invalidation tests**

```java
package com.monpai.sailboatmod.nation.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimPreviewTerrainServiceTest {
    @Test
    void visibleChunkWorkIsScheduledAheadOfPrefetchWork() {
        ClaimPreviewTerrainService service = new ClaimPreviewTerrainService();

        service.enqueueViewportForTest("minecraft:overworld", 0, 0, 1, 3, 5L, "town|a");

        assertEquals(9, service.visibleQueueSizeForTest());
        assertTrue(service.prefetchQueueSizeForTest() > 9);
    }

    @Test
    void invalidatingChunkClearsTileAndDependentSnapshots() {
        ClaimPreviewTerrainService service = new ClaimPreviewTerrainService();
        service.putTileForTest("minecraft:overworld", 2, 3, new int[] {1, 2, 3, 4});
        service.putViewportDependencyForTest("minecraft:overworld", 2, 3, "town|a|11");

        service.invalidateChunkForTest("minecraft:overworld", 2, 3);

        assertNull(service.getTileForTest("minecraft:overworld", 2, 3));
        assertTrue(service.invalidatedViewportKeysForTest().contains("town|a|11"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ClaimPreviewTerrainServiceTest" -q`  
Expected: FAIL because visible/prefetch queue inspection and dependent viewport invalidation do not exist.

- [ ] **Step 3: Write the minimal tile-worker implementation**

```java
// ClaimPreviewTerrainService.java
private final Queue<TileRequest> visibleQueue = new ConcurrentLinkedQueue<>();
private final Queue<TileRequest> prefetchQueue = new ConcurrentLinkedQueue<>();
private final ConcurrentMap<String, int[]> hotTiles = new ConcurrentHashMap<>();
private final ConcurrentMap<String, Set<String>> chunkToViewportDependencies = new ConcurrentHashMap<>();
private final ConcurrentMap<String, ClaimMapViewportSnapshot> viewportSnapshotCache = new ConcurrentHashMap<>();
private final List<String> invalidatedViewportKeys = new CopyOnWriteArrayList<>();

public void enqueueViewport(String dimensionId,
                            int centerChunkX,
                            int centerChunkZ,
                            int radius,
                            int prefetchRadius,
                            long revision,
                            String screenKey) {
    enqueueRing(visibleQueue, dimensionId, centerChunkX, centerChunkZ, radius, screenKey, true);
    enqueueRing(prefetchQueue, dimensionId, centerChunkX, centerChunkZ, radius + prefetchRadius, screenKey, false);
}

public void processBudgetedWork(ServerLevel level, int visibleBudget, int prefetchBudget) {
    drainQueue(level, visibleQueue, visibleBudget);
    drainQueue(level, prefetchQueue, prefetchBudget);
}

private void drainQueue(ServerLevel level, Queue<TileRequest> queue, int budget) {
    for (int processed = 0; processed < budget && !queue.isEmpty(); ) {
        TileRequest request = queue.poll();
        if (request == null) {
            continue;
        }
        level.getServer().execute(() -> {
            int[] tile = sampleChunkSubColors(level, request.chunkX(), request.chunkZ());
            hotTiles.put(tileKey(request.dimensionId(), request.chunkX(), request.chunkZ()), tile);
            TerrainPreviewSavedData.get(level).putTile(request.dimensionId(), request.chunkX(), request.chunkZ(), tile);
        });
        processed++;
    }
}

public void invalidateChunk(ServerLevel level, int chunkX, int chunkZ) {
    String key = tileKey(level.dimension().location().toString(), chunkX, chunkZ);
    hotTiles.remove(key);
    TerrainPreviewSavedData.get(level).removeTile(level.dimension().location().toString(), chunkX, chunkZ);
    Set<String> dependentViewportKeys = chunkToViewportDependencies.remove(key);
    if (dependentViewportKeys != null) {
        for (String viewportKey : dependentViewportKeys) {
            viewportSnapshotCache.remove(viewportKey);
            requeueViewport(viewportKey);
        }
    }
}

private void requeueViewport(String viewportKey) {
    invalidatedViewportKeys.add(viewportKey);
}

void enqueueViewportForTest(String dimensionId, int centerChunkX, int centerChunkZ, int radius, int prefetchRadius, long revision, String screenKey) {
    enqueueViewport(dimensionId, centerChunkX, centerChunkZ, radius, prefetchRadius, revision, screenKey);
}

void putTileForTest(String dimensionId, int chunkX, int chunkZ, int[] colors) {
    hotTiles.put(tileKey(dimensionId, chunkX, chunkZ), colors);
}

int[] getTileForTest(String dimensionId, int chunkX, int chunkZ) {
    return hotTiles.get(tileKey(dimensionId, chunkX, chunkZ));
}

void putViewportDependencyForTest(String dimensionId, int chunkX, int chunkZ, String viewportKey) {
    chunkToViewportDependencies.computeIfAbsent(tileKey(dimensionId, chunkX, chunkZ), ignored -> ConcurrentHashMap.newKeySet()).add(viewportKey);
}

void invalidateChunkForTest(String dimensionId, int chunkX, int chunkZ) {
    hotTiles.remove(tileKey(dimensionId, chunkX, chunkZ));
    Set<String> dependentViewportKeys = chunkToViewportDependencies.remove(tileKey(dimensionId, chunkX, chunkZ));
    if (dependentViewportKeys != null) {
        invalidatedViewportKeys.addAll(dependentViewportKeys);
    }
}

int visibleQueueSizeForTest() { return visibleQueue.size(); }
int prefetchQueueSizeForTest() { return prefetchQueue.size(); }
List<String> invalidatedViewportKeysForTest() { return List.copyOf(invalidatedViewportKeys); }
```

```java
// TerrainCacheInvalidator.java
ClaimPreviewTerrainService service = ClaimPreviewTerrainService.get(level.getServer());
if (service != null) {
    service.invalidateChunk(level, pos.getX() >> 4, pos.getZ() >> 4);
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ClaimPreviewTerrainServiceTest" -q`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/ClaimPreviewTerrainService.java src/main/java/com/monpai/sailboatmod/nation/service/ClaimMapViewportService.java src/main/java/com/monpai/sailboatmod/nation/service/TerrainCacheInvalidator.java src/main/java/com/monpai/sailboatmod/ServerEvents.java src/test/java/com/monpai/sailboatmod/nation/service/ClaimPreviewTerrainServiceTest.java
git commit -m "Add claim map tile worker and invalidation"
```

## Task 5: Convert Town/Nation Screens to Snapshot Consumption Only

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/TownOverviewService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/NationOverviewService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/TownClientHooks.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/NationClientHooks.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreen.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/nation/NationHomeScreen.java`
- Modify or delete: `src/main/java/com/monpai/sailboatmod/client/ClaimMapRasterizer.java`
- Modify or delete: `src/main/java/com/monpai/sailboatmod/client/ClaimMapRenderTaskService.java`
- Modify: `src/test/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreenTest.java`

- [ ] **Step 1: Write the failing client behavior tests**

```java
package com.monpai.sailboatmod.client.screen.town;

import com.monpai.sailboatmod.nation.menu.ClaimPreviewMapState;
import com.monpai.sailboatmod.nation.menu.TownOverviewData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TownHomeScreenTest {
    @Test
    void updateDataKeepsLastCompleteTerrainWhileNewViewportIsLoading() {
        TownHomeScreen screen = new TownHomeScreen(baseData(List.of(0xFF111111, 0xFF222222, 0xFF333333, 0xFF444444), ClaimPreviewMapState.ready(1L, 1, 0, 0, List.of())));
        screen.updateData(baseData(List.of(), ClaimPreviewMapState.loading(2L, 1, 1, 0)));

        assertEquals(0xFF111111, screen.sampleClaimTerrainColorForTest(0, 0, 0, 0));
    }

    @Test
    void completeViewportSnapshotReplacesPreviousTerrainAfterDrag() {
        TownHomeScreen screen = new TownHomeScreen(baseData(List.of(0xFF111111, 0xFF222222, 0xFF333333, 0xFF444444), ClaimPreviewMapState.ready(1L, 1, 0, 0, List.of())));
        screen.updateData(baseData(List.of(0xFFAAAAAA, 0xFFBBBBBB, 0xFFCCCCCC, 0xFFDDDDDD), ClaimPreviewMapState.ready(2L, 1, 1, 0, List.of())));

        assertEquals(0xFFAAAAAA, screen.sampleClaimTerrainColorForTest(1, 0, 0, 0));
    }

    private static TownOverviewData baseData(List<Integer> terrainColors, ClaimPreviewMapState mapState) {
        return new TownOverviewData(
                true, "town-a", "Town A", "", "", "", "", false, 0x123456, 0x654321,
                false, "", 0L, 0, 0, 0, 0, mapState.centerChunkX(), mapState.centerChunkZ(), false, false, "", "", "", "", "", "", "", "",
                "", 0, 0, 0L, "", false, false, false, false, false, false,
                List.of(), terrainColors, List.of(), "european", Map.of(), 0.0f, Map.of(), 0.0f,
                0, 0, 0, 0, 0, 0L, 0L, 0L, List.of(), List.of(), List.of(), List.of(), List.of(), mapState
        );
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.client.screen.town.TownHomeScreenTest" -q`  
Expected: FAIL because the screen still clears terrain immediately and still depends on local/client-side generation rules.

- [ ] **Step 3: Write the minimal client conversion**

```java
// TownOverviewService / NationOverviewService
ClaimPreviewMapState claimMapState = ClaimPreviewMapState.loading(System.nanoTime(), claimPreviewRadius(), previewChunk.x, previewChunk.z);
List<Integer> nearbyTerrainColors = List.of();
```

```java
// TownClientHooks / NationClientHooks
public static void requestViewport(String ownerId, String dimensionId, long revision, int radius, int centerChunkX, int centerChunkZ, int prefetchRadius) {
    ModNetwork.CHANNEL.sendToServer(new RequestClaimMapViewportPacket(
            RequestClaimMapViewportPacket.ScreenKind.TOWN,
            ownerId,
            dimensionId,
            revision,
            radius,
            centerChunkX,
            centerChunkZ,
            prefetchRadius
    ));
}

public static void applyClaimPreview(ClaimMapViewportSnapshot snapshot) {
    if (snapshot == null || snapshot.revision() < lastSyncedData.claimMapState().revision()) {
        return;
    }
    lastSyncedData = lastSyncedData.withClaimPreview(
            ClaimPreviewMapState.ready(snapshot.revision(), snapshot.radius(), snapshot.centerChunkX(), snapshot.centerChunkZ(), List.of()),
            snapshot.pixels()
    );
}
```

```java
// TownHomeScreen / NationHomeScreen
private static final int PREFETCH_RADIUS = 3;

private void requestRefresh(int centerChunkX, int centerChunkZ) {
    this.refreshPending = true;
    this.pendingPreviewCenterX = centerChunkX;
    this.pendingPreviewCenterZ = centerChunkZ;
    Minecraft minecraft = Minecraft.getInstance();
    com.monpai.sailboatmod.client.TownClientHooks.requestViewport(
            this.data.townId(),
            minecraft.level == null ? "minecraft:overworld" : minecraft.level.dimension().location().toString(),
            System.nanoTime(),
            claimRadius(),
            centerChunkX,
            centerChunkZ,
            PREFETCH_RADIUS
    );
}

private int sampleClaimTerrainColor(int chunkX, int chunkZ, int sx, int sz) {
    int sub = com.monpai.sailboatmod.nation.service.ClaimPreviewTerrainService.SUB;
    int gridX = chunkX - this.data.previewCenterChunkX() + claimRadius();
    int gridZ = chunkZ - this.data.previewCenterChunkZ() + claimRadius();
    int diameter = claimRadius() * 2 + 1;
    int chunkIndex = gridZ * diameter + gridX;
    int subIndex = chunkIndex * sub * sub + sz * sub + sx;
    List<Integer> colors = this.data.nearbyTerrainColors();
    if (subIndex >= 0 && subIndex < colors.size()) {
        return colors.get(subIndex);
    }
    Integer cached = TerrainColorClientCache.get(chunkX, chunkZ);
    return cached == null ? PREVIEW_DEFAULT_TERRAIN_COLOR : cached;
}

int sampleClaimTerrainColorForTest(int chunkX, int chunkZ, int sx, int sz) {
    return sampleClaimTerrainColor(chunkX, chunkZ, sx, sz);
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.client.screen.town.TownHomeScreenTest" -q`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/TownOverviewService.java src/main/java/com/monpai/sailboatmod/nation/service/NationOverviewService.java src/main/java/com/monpai/sailboatmod/client/TownClientHooks.java src/main/java/com/monpai/sailboatmod/client/NationClientHooks.java src/main/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreen.java src/main/java/com/monpai/sailboatmod/client/screen/nation/NationHomeScreen.java src/test/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreenTest.java
git add -u src/main/java/com/monpai/sailboatmod/client/ClaimMapRasterizer.java src/main/java/com/monpai/sailboatmod/client/ClaimMapRenderTaskService.java
git commit -m "Switch claim map screens to server viewport snapshots"
```

## Task 6: Run Full Verification and Record the New Design Trail

**Files:**
- Modify: `docs/superpowers/specs/2026-04-17-claim-map-server-tile-cache-design.md`
- Modify: `docs/superpowers/plans/2026-04-17-claim-map-server-tile-cache.md`

- [ ] **Step 1: Run the focused subsystem tests**

```bash
.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.data.TerrainPreviewSavedDataTest" --tests "com.monpai.sailboatmod.nation.service.ClaimMapViewportServiceTest" --tests "com.monpai.sailboatmod.network.packet.ClaimMapViewportPacketRoundTripTest" --tests "com.monpai.sailboatmod.nation.service.ClaimPreviewTerrainServiceTest" --tests "com.monpai.sailboatmod.nation.service.ClaimMapTaskServiceTest" --tests "com.monpai.sailboatmod.network.packet.ClaimOverviewPacketRoundTripTest" --tests "com.monpai.sailboatmod.client.screen.town.TownHomeScreenTest" -q
```

Expected: PASS

- [ ] **Step 2: Run a Java compile pass**

```bash
.\gradlew.bat compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Update spec/plan notes if implementation diverged**

```markdown
If the implementation kept `ClearTerrainCachePacket` as a visible-area refresh shim instead of deleting it, add one short note to the spec and this plan.
If `ClaimMapRasterizer` or `ClaimMapRenderTaskService` survived as compatibility wrappers, record that explicitly.
Otherwise leave both documents unchanged.
```

Recorded note (2026-04-18): `ClaimMapRasterizer` and `ClaimMapRenderTaskService` were intentionally retained as compatibility wrappers/tests only; active town/nation claim-map rendering now uses server viewport snapshots directly.

- [ ] **Step 4: Commit docs/verification touch-up**

```bash
git add docs/superpowers/specs/2026-04-17-claim-map-server-tile-cache-design.md docs/superpowers/plans/2026-04-17-claim-map-server-tile-cache.md
git commit -m "Document claim map tile cache verification"
```

- [ ] **Step 5: Inspect final worktree state**

```bash
git status --short
```

Expected: clean worktree or only intentional follow-up files such as local `logs/`
