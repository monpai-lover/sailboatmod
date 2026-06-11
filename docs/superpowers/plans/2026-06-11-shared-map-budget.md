# Shared Map Budget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the road planner map and claim/nation map share one base-map state while keeping map work bounded per server tick.

**Architecture:** Add a small shared rendered-chunk index and shared client map state around the existing `RoadPlannerTileManager` cache. Keep `RoadPlannerMapTileSyncPacket` as the first-phase shared tile delta transport, route all received tile deltas through `SharedMapClientState`, and mark server-side rendered chunks when `RoadPlannerMapPreloadService` emits tiles. Tighten preload tick budgets so force-render jobs cannot multiply work across many sessions in one tick.

**Tech Stack:** Java 17, Forge 1.20.1 networking, JUnit 5, existing road planner map classes, existing claim map services.

---

## File Structure

Create:

- `src/main/java/com/monpai/sailboatmod/map/RenderedChunkIndex.java`
  Dimension-indexed rendered chunk set. Common logic usable on server and client.

- `src/main/java/com/monpai/sailboatmod/client/map/SharedMapClientState.java`
  Client singleton that applies shared tile deltas to `RoadPlannerTileManager.sharedDefault()` and updates `RenderedChunkIndex`.

- `src/main/java/com/monpai/sailboatmod/map/SharedMapServerState.java`
  Server lifecycle holder for rendered chunk state, used by preload/force-render completion.

- `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadBudget.java`
  Pure helper for per-tick map preload budgets by purpose.

- `src/test/java/com/monpai/sailboatmod/map/RenderedChunkIndexTest.java`

- `src/test/java/com/monpai/sailboatmod/client/map/SharedMapClientStateTest.java`

- `src/test/java/com/monpai/sailboatmod/map/SharedMapServerStateTest.java`

- `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadBudgetTest.java`

Modify:

- `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerClientMapTileCache.java`
  Delegate tile sync to `SharedMapClientState`.

- `src/main/java/com/monpai/sailboatmod/client/screen/ClaimWorldMapView.java`
  Let claim map acknowledge shared base-map deltas that came from other sessions.

- `src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerMapTileSyncPacket.java`
  Keep transport shape, but route client handling through the shared state.

- `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadService.java`
  Mark emitted tiles in server shared state and enforce global per-level tick budgets.

- `src/main/java/com/monpai/sailboatmod/ServerEvents.java`
  Initialize/clear server shared state alongside existing services.

Do not modify:

- Road graph construction.
- Road placement/undo systems.
- Claim overlay ownership rendering.
- Market web files.

---

### Task 1: Common Rendered Chunk Index

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/map/RenderedChunkIndex.java`
- Test: `src/test/java/com/monpai/sailboatmod/map/RenderedChunkIndexTest.java`

- [ ] **Step 1: Write failing tests for chunk marking and dimension isolation**

Create `src/test/java/com/monpai/sailboatmod/map/RenderedChunkIndexTest.java`:

```java
package com.monpai.sailboatmod.map;

import com.monpai.sailboatmod.roadplanner.map.RoadMapTileSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderedChunkIndexTest {
    @Test
    void marksFullTileAsSixteenBySixteenChunks() {
        RenderedChunkIndex index = new RenderedChunkIndex();

        int marked = index.markTile("minecraft:overworld", 2, -1, fullMask());

        assertEquals(256, marked);
        assertTrue(index.isRendered("minecraft:overworld", 32, -16));
        assertTrue(index.isRendered("minecraft:overworld", 47, -1));
        assertFalse(index.isRendered("minecraft:overworld", 48, -1));
        assertFalse(index.isRendered("minecraft:the_nether", 32, -16));
    }

    @Test
    void marksOnlyChunksCoveredByPartialMask() {
        RenderedChunkIndex index = new RenderedChunkIndex();
        boolean[] mask = new boolean[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                mask[y * RoadMapTileSpec.TILE_PIXELS + x] = true;
            }
        }

        int marked = index.markTile("minecraft:overworld", 0, 0, mask);

        assertEquals(1, marked);
        assertTrue(index.isRendered("minecraft:overworld", 0, 0));
        assertFalse(index.isRendered("minecraft:overworld", 1, 0));
        assertFalse(index.isRendered("minecraft:overworld", 0, 1));
    }

    @Test
    void clearDimensionRemovesOnlyThatDimension() {
        RenderedChunkIndex index = new RenderedChunkIndex();
        index.markChunk("minecraft:overworld", 4, 5);
        index.markChunk("minecraft:the_nether", 4, 5);

        index.clearDimension("minecraft:overworld");

        assertFalse(index.isRendered("minecraft:overworld", 4, 5));
        assertTrue(index.isRendered("minecraft:the_nether", 4, 5));
    }

    @Test
    void detectsAnyRenderedChunkInsideTile() {
        RenderedChunkIndex index = new RenderedChunkIndex();
        index.markChunk("minecraft:overworld", 18, 19);

        assertTrue(index.hasAnyRenderedChunkInTile("minecraft:overworld", 1, 1));
        assertFalse(index.hasAnyRenderedChunkInTile("minecraft:overworld", 2, 1));
    }

    private static boolean[] fullMask() {
        boolean[] mask = new boolean[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS];
        java.util.Arrays.fill(mask, true);
        return mask;
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.map.RenderedChunkIndexTest
```

Expected: compile fails because `RenderedChunkIndex` does not exist.

- [ ] **Step 3: Implement `RenderedChunkIndex`**

Create `src/main/java/com/monpai/sailboatmod/map/RenderedChunkIndex.java`:

```java
package com.monpai.sailboatmod.map;

import com.monpai.sailboatmod.roadplanner.map.RoadMapTileSpec;
import net.minecraft.world.level.ChunkPos;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class RenderedChunkIndex {
    private static final int CHUNKS_PER_TILE_AXIS = RoadMapTileSpec.TILE_BLOCKS / 16;
    private static final int PIXELS_PER_CHUNK_AXIS = RoadMapTileSpec.TILE_PIXELS / CHUNKS_PER_TILE_AXIS;

    private final Map<String, Set<Long>> renderedByDimension = new ConcurrentHashMap<>();

    public boolean markChunk(String dimensionId, int chunkX, int chunkZ) {
        String dimension = normalizeDimension(dimensionId);
        if (dimension.isEmpty()) {
            return false;
        }
        return renderedByDimension
                .computeIfAbsent(dimension, ignored -> ConcurrentHashMap.newKeySet())
                .add(ChunkPos.asLong(chunkX, chunkZ));
    }

    public int markTile(String dimensionId, int tileX, int tileZ, boolean[] coverageMask) {
        String dimension = normalizeDimension(dimensionId);
        if (dimension.isEmpty()) {
            return 0;
        }
        boolean[] safeMask = normalizeMask(coverageMask);
        int marked = 0;
        int tileChunkX = tileX * CHUNKS_PER_TILE_AXIS;
        int tileChunkZ = tileZ * CHUNKS_PER_TILE_AXIS;
        for (int localChunkZ = 0; localChunkZ < CHUNKS_PER_TILE_AXIS; localChunkZ++) {
            for (int localChunkX = 0; localChunkX < CHUNKS_PER_TILE_AXIS; localChunkX++) {
                if (!isChunkCovered(safeMask, localChunkX, localChunkZ)) {
                    continue;
                }
                if (markChunk(dimension, tileChunkX + localChunkX, tileChunkZ + localChunkZ)) {
                    marked++;
                }
            }
        }
        return marked;
    }

    public boolean isRendered(String dimensionId, int chunkX, int chunkZ) {
        Set<Long> rendered = renderedByDimension.get(normalizeDimension(dimensionId));
        return rendered != null && rendered.contains(ChunkPos.asLong(chunkX, chunkZ));
    }

    public boolean hasAnyRenderedChunkInTile(String dimensionId, int tileX, int tileZ) {
        String dimension = normalizeDimension(dimensionId);
        Set<Long> rendered = renderedByDimension.get(dimension);
        if (rendered == null || rendered.isEmpty()) {
            return false;
        }
        int startChunkX = tileX * CHUNKS_PER_TILE_AXIS;
        int startChunkZ = tileZ * CHUNKS_PER_TILE_AXIS;
        for (int dz = 0; dz < CHUNKS_PER_TILE_AXIS; dz++) {
            for (int dx = 0; dx < CHUNKS_PER_TILE_AXIS; dx++) {
                if (rendered.contains(ChunkPos.asLong(startChunkX + dx, startChunkZ + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    public int renderedChunkCount(String dimensionId) {
        Set<Long> rendered = renderedByDimension.get(normalizeDimension(dimensionId));
        return rendered == null ? 0 : rendered.size();
    }

    public void clearDimension(String dimensionId) {
        renderedByDimension.remove(normalizeDimension(dimensionId));
    }

    public void clearAll() {
        renderedByDimension.clear();
    }

    private static boolean isChunkCovered(boolean[] mask, int localChunkX, int localChunkZ) {
        int startX = localChunkX * PIXELS_PER_CHUNK_AXIS;
        int startZ = localChunkZ * PIXELS_PER_CHUNK_AXIS;
        int endX = Math.min(RoadMapTileSpec.TILE_PIXELS, startX + PIXELS_PER_CHUNK_AXIS);
        int endZ = Math.min(RoadMapTileSpec.TILE_PIXELS, startZ + PIXELS_PER_CHUNK_AXIS);
        for (int y = startZ; y < endZ; y++) {
            for (int x = startX; x < endX; x++) {
                if (mask[y * RoadMapTileSpec.TILE_PIXELS + x]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean[] normalizeMask(boolean[] mask) {
        int expected = RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS;
        if (mask != null && mask.length == expected) {
            return mask;
        }
        boolean[] full = new boolean[expected];
        java.util.Arrays.fill(full, true);
        return full;
    }

    private static String normalizeDimension(String dimensionId) {
        return dimensionId == null ? "" : dimensionId.trim();
    }
}
```

- [ ] **Step 4: Run the test again**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.map.RenderedChunkIndexTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/map/RenderedChunkIndex.java src/test/java/com/monpai/sailboatmod/map/RenderedChunkIndexTest.java
git commit -m "Add rendered chunk index"
```

---

### Task 2: Shared Client Map State

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/map/SharedMapClientState.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerClientMapTileCache.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/map/SharedMapClientStateTest.java`

- [ ] **Step 1: Write failing tests for client state applying tile packets**

Create `src/test/java/com/monpai/sailboatmod/client/map/SharedMapClientStateTest.java`:

```java
package com.monpai.sailboatmod.client.map;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapTileSpec;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedMapClientStateTest {
    @Test
    void applyingTilePacketMarksCoveredChunks() {
        SharedMapClientState state = new SharedMapClientState(null);

        int applied = state.applyTileDelta(packet("minecraft:overworld", 0, 0, firstChunkOnlyMask()));

        assertTrue(applied >= 0);
        assertTrue(state.isRendered("minecraft:overworld", 0, 0));
        assertFalse(state.isRendered("minecraft:overworld", 1, 0));
    }

    @Test
    void clearAllDropsRenderedIndex() {
        SharedMapClientState state = new SharedMapClientState(null);
        state.applyTileDelta(packet("minecraft:overworld", 0, 0, null));

        state.clearAll();

        assertFalse(state.isRendered("minecraft:overworld", 0, 0));
    }

    private static RoadPlannerMapTileSyncPacket packet(String dimensionId, int tileX, int tileZ, boolean[] mask) {
        return new RoadPlannerMapTileSyncPacket(
                UUID.randomUUID(),
                1L,
                RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER,
                "world_a",
                dimensionId,
                MapLod.LOD_1,
                tileX,
                tileZ,
                RoadMapTileSpec.TILE_PIXELS,
                RoadMapTileSpec.TILE_PIXELS,
                new int[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS],
                mask
        );
    }

    private static boolean[] firstChunkOnlyMask() {
        boolean[] mask = new boolean[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                mask[y * RoadMapTileSpec.TILE_PIXELS + x] = true;
            }
        }
        return mask;
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.client.map.SharedMapClientStateTest
```

Expected: compile fails because `SharedMapClientState` does not exist.

- [ ] **Step 3: Implement `SharedMapClientState`**

Create `src/main/java/com/monpai/sailboatmod/client/map/SharedMapClientState.java`:

```java
package com.monpai.sailboatmod.client.map;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileManager;
import com.monpai.sailboatmod.map.RenderedChunkIndex;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;

public final class SharedMapClientState {
    private static final SharedMapClientState DEFAULT = new SharedMapClientState(null);

    private final RenderedChunkIndex renderedChunks = new RenderedChunkIndex();
    private final RoadPlannerTileManager injectedTileManager;

    public SharedMapClientState(RoadPlannerTileManager injectedTileManager) {
        this.injectedTileManager = injectedTileManager;
    }

    public static SharedMapClientState defaultState() {
        return DEFAULT;
    }

    public int applyTileDelta(RoadPlannerMapTileSyncPacket packet) {
        if (packet == null) {
            return 0;
        }
        int applied = 0;
        RoadPlannerTileManager manager = tileManager();
        if (manager != null) {
            applied = manager.applyTileSync(packet);
        }
        renderedChunks.markTile(packet.dimensionId(), packet.tileX(), packet.tileZ(), packet.coverageMask());
        return applied;
    }

    public boolean isRendered(String dimensionId, int chunkX, int chunkZ) {
        return renderedChunks.isRendered(dimensionId, chunkX, chunkZ);
    }

    public boolean hasAnyRenderedChunkInTile(String dimensionId, int tileX, int tileZ) {
        return renderedChunks.hasAnyRenderedChunkInTile(dimensionId, tileX, tileZ);
    }

    public void clearAll() {
        renderedChunks.clearAll();
    }

    private RoadPlannerTileManager tileManager() {
        if (injectedTileManager != null) {
            return injectedTileManager;
        }
        try {
            return RoadPlannerTileManager.sharedDefault();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
```

- [ ] **Step 4: Route the default tile cache through shared state**

Modify `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerClientMapTileCache.java`:

```java
package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.client.map.SharedMapClientState;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;

public final class RoadPlannerClientMapTileCache {
    private RoadPlannerClientMapTileCache() {
    }

    public static void applyToDefaultCache(RoadPlannerMapTileSyncPacket packet) {
        SharedMapClientState.defaultState().applyTileDelta(packet);
    }
}
```

- [ ] **Step 5: Run the client state tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.client.map.SharedMapClientStateTest --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileManagerSharedTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/client/map/SharedMapClientState.java src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerClientMapTileCache.java src/test/java/com/monpai/sailboatmod/client/map/SharedMapClientStateTest.java
git commit -m "Share client map tile state"
```

---

### Task 3: Claim Map Reads Shared Base Map Deltas

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/ClaimWorldMapView.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/screen/ClaimWorldMapViewTest.java`

- [ ] **Step 1: Add a failing test that non-claim-session packets are acknowledged when shared state has the tile**

Append this test to `ClaimWorldMapViewTest`:

```java
@Test
void claimMapAcknowledgesSharedTilePacketFromOtherSession(@TempDir java.nio.file.Path tempDir) {
    RoadPlannerTileManager manager = RoadPlannerTileManager.forTest(
            tempDir.toFile(),
            "world_a",
            "minecraft:overworld"
    );
    RoadPlannerTileManager.setSharedDefaultForTest(manager);
    SharedMapClientState.defaultState().clearAll();
    ClaimWorldMapView view = new ClaimWorldMapView();
    RoadPlannerMapTileSyncPacket packet = new RoadPlannerMapTileSyncPacket(
            java.util.UUID.randomUUID(),
            77L,
            RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER,
            "world_a",
            "minecraft:overworld",
            MapLod.LOD_1,
            0,
            0,
            RoadMapTileSpec.TILE_PIXELS,
            RoadMapTileSpec.TILE_PIXELS,
            new int[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS],
            null
    );

    try {
        SharedMapClientState.defaultState().applyTileDelta(packet);

        int applied = view.applyTileSync(packet);

        assertEquals(1, applied);
    } finally {
        view.close();
        SharedMapClientState.defaultState().clearAll();
        RoadPlannerTileManager.clearSharedDefaultForTest();
    }
}
```

Add imports if missing:

```java
import com.monpai.sailboatmod.client.map.SharedMapClientState;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileManager;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapTileSpec;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
```

- [ ] **Step 2: Run the failing test**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.client.screen.ClaimWorldMapViewTest
```

Expected: FAIL if current `applyTileSync` returns `0` for all non-session packets.

- [ ] **Step 3: Modify `ClaimWorldMapView.applyTileSync` to use shared base state for default managers**

In `src/main/java/com/monpai/sailboatmod/client/screen/ClaimWorldMapView.java`, add import:

```java
import com.monpai.sailboatmod.client.map.SharedMapClientState;
```

Replace `applyTileSync` with:

```java
public int applyTileSync(RoadPlannerMapTileSyncPacket packet) {
    if (packet == null) {
        return 0;
    }
    if (sessionId.equals(packet.sessionId())) {
        RoadPlannerTileManager manager = tileManager();
        return manager == null ? 0 : manager.applyTileSync(packet);
    }
    if (!createDefaultTileManager) {
        return 0;
    }
    return SharedMapClientState.defaultState().hasAnyRenderedChunkInTile(packet.dimensionId(), packet.tileX(), packet.tileZ())
            ? 1
            : 0;
}
```

This keeps injected test managers session-scoped, while the real claim map can acknowledge shared base-map deltas from the road planner.

- [ ] **Step 4: Run claim map and tile sync receiver tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.client.screen.ClaimWorldMapViewTest --tests com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileSyncReceiverTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/client/screen/ClaimWorldMapView.java src/test/java/com/monpai/sailboatmod/client/screen/ClaimWorldMapViewTest.java
git commit -m "Let claim map observe shared tile deltas"
```

---

### Task 4: Server Shared Map State Lifecycle

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/map/SharedMapServerState.java`
- Modify: `src/main/java/com/monpai/sailboatmod/ServerEvents.java`
- Test: `src/test/java/com/monpai/sailboatmod/map/SharedMapServerStateTest.java`

- [ ] **Step 1: Write failing server state tests**

Create `src/test/java/com/monpai/sailboatmod/map/SharedMapServerStateTest.java`:

```java
package com.monpai.sailboatmod.map;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapTileSpec;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedMapServerStateTest {
    @Test
    void activeStateCanBeInstalledAndClearedForTests() {
        SharedMapServerState state = new SharedMapServerState();
        SharedMapServerState.setActiveForTest(state);

        try {
            assertNotNull(SharedMapServerState.get());
        } finally {
            SharedMapServerState.clearActiveForTest();
        }

        assertFalse(SharedMapServerState.isRendered("minecraft:overworld", 0, 0));
    }

    @Test
    void markingTilePacketUpdatesRenderedIndex() {
        SharedMapServerState state = new SharedMapServerState();
        state.markTileDelta(packet());

        assertTrue(state.isChunkRendered("minecraft:overworld", 0, 0));
    }

    private static RoadPlannerMapTileSyncPacket packet() {
        return new RoadPlannerMapTileSyncPacket(
                UUID.randomUUID(),
                1L,
                RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER,
                "world_a",
                "minecraft:overworld",
                MapLod.LOD_1,
                0,
                0,
                RoadMapTileSpec.TILE_PIXELS,
                RoadMapTileSpec.TILE_PIXELS,
                new int[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS],
                null
        );
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.map.SharedMapServerStateTest
```

Expected: compile fails because `SharedMapServerState` does not exist.

- [ ] **Step 3: Implement `SharedMapServerState`**

Create `src/main/java/com/monpai/sailboatmod/map/SharedMapServerState.java`:

```java
package com.monpai.sailboatmod.map;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.atomic.AtomicReference;

public final class SharedMapServerState {
    private static final AtomicReference<SharedMapServerState> ACTIVE = new AtomicReference<>();

    private final RenderedChunkIndex renderedChunks = new RenderedChunkIndex();

    public static void onServerStarted(MinecraftServer server) {
        if (server != null) {
            ACTIVE.set(new SharedMapServerState());
        }
    }

    public static void onServerStopped() {
        ACTIVE.set(null);
    }

    public static SharedMapServerState get() {
        return ACTIVE.get();
    }

    public static void setActiveForTest(SharedMapServerState state) {
        ACTIVE.set(state);
    }

    public static void clearActiveForTest() {
        ACTIVE.set(null);
    }

    public static void markRendered(RoadPlannerMapTileSyncPacket packet) {
        SharedMapServerState state = ACTIVE.get();
        if (state != null) {
            state.markTileDelta(packet);
        }
    }

    public static boolean isRendered(String dimensionId, int chunkX, int chunkZ) {
        SharedMapServerState state = ACTIVE.get();
        return state != null && state.isChunkRendered(dimensionId, chunkX, chunkZ);
    }

    public int markTileDelta(RoadPlannerMapTileSyncPacket packet) {
        if (packet == null) {
            return 0;
        }
        return renderedChunks.markTile(packet.dimensionId(), packet.tileX(), packet.tileZ(), packet.coverageMask());
    }

    public boolean isChunkRendered(String dimensionId, int chunkX, int chunkZ) {
        return renderedChunks.isRendered(dimensionId, chunkX, chunkZ);
    }

    public void clearAll() {
        renderedChunks.clearAll();
    }
}
```

- [ ] **Step 4: Register lifecycle in `ServerEvents`**

Modify `src/main/java/com/monpai/sailboatmod/ServerEvents.java`.

Add import:

```java
import com.monpai.sailboatmod.map.SharedMapServerState;
```

In `onServerStarted`, after `ClaimMapTaskService.onServerStarted(event.getServer());`, add:

```java
SharedMapServerState.onServerStarted(event.getServer());
```

In `onServerStopped`, before `RoadPlannerMapPreloadService.onServerStopped();`, add:

```java
SharedMapServerState.onServerStopped();
```

- [ ] **Step 5: Run lifecycle tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.map.SharedMapServerStateTest --tests com.monpai.sailboatmod.ServerEventsTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/map/SharedMapServerState.java src/main/java/com/monpai/sailboatmod/ServerEvents.java src/test/java/com/monpai/sailboatmod/map/SharedMapServerStateTest.java
git commit -m "Track shared server map state"
```

---

### Task 5: Mark Server Rendered Chunks When Force Render Emits Tiles

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadService.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadJobTest.java`

- [ ] **Step 1: Add a focused test for packet coverage if not already present**

`RoadPlannerMapPreloadJobTest` already has coverage tests. Add this small assertion to `jobMarksOnlyCoveredChunkPixelsInTileSyncMask` after `boolean[] mask = packets.get(0).coverageMask();`:

```java
assertEquals(RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS, mask.length);
```

If that assertion already exists, do not duplicate it.

- [ ] **Step 2: Modify `sendTile` to mark shared server state before sending**

In `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadService.java`, add import:

```java
import com.monpai.sailboatmod.map.SharedMapServerState;
```

Replace `sendTile` with:

```java
private void sendTile(ServerPlayer player, RoadPlannerMapTileSyncPacket packet) {
    SharedMapServerState.markRendered(packet);
    ModNetwork.CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
}
```

- [ ] **Step 3: Run affected tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerMapPreloadJobTest --tests com.monpai.sailboatmod.map.SharedMapServerStateTest
```

Expected: PASS.

- [ ] **Step 4: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadService.java src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadJobTest.java
git commit -m "Record rendered map tiles on server"
```

---

### Task 6: Tighten Preload Tick Budgets

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadBudget.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadService.java`
- Test: `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadBudgetTest.java`

- [ ] **Step 1: Write failing budget helper tests**

Create `src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadBudgetTest.java`:

```java
package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadPlannerMapPreloadBudgetTest {
    @Test
    void forceRenderGetsOneTilePerTick() {
        assertEquals(1, RoadPlannerMapPreloadBudget.tilesForPurpose(RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER));
    }

    @Test
    void globalLevelBudgetCapsTotalTileWork() {
        RoadPlannerMapPreloadBudget budget = new RoadPlannerMapPreloadBudget(3);

        assertEquals(1, budget.claim(RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER));
        assertEquals(2, budget.claim(RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD));
        assertEquals(0, budget.claim(RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD));
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerMapPreloadBudgetTest
```

Expected: compile fails because `RoadPlannerMapPreloadBudget` does not exist.

- [ ] **Step 3: Implement `RoadPlannerMapPreloadBudget`**

Create `src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadBudget.java`:

```java
package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;

public final class RoadPlannerMapPreloadBudget {
    public static final int DEFAULT_GLOBAL_TILE_BUDGET_PER_LEVEL_TICK = 6;

    private int remaining;

    public RoadPlannerMapPreloadBudget(int globalBudget) {
        this.remaining = Math.max(0, globalBudget);
    }

    public int claim(RoadPlannerMapPreloadRequestPacket.Purpose purpose) {
        int allowed = Math.min(remaining, tilesForPurpose(purpose));
        remaining -= allowed;
        return allowed;
    }

    public int remaining() {
        return remaining;
    }

    public static int tilesForPurpose(RoadPlannerMapPreloadRequestPacket.Purpose purpose) {
        if (purpose == RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER) {
            return 1;
        }
        if (purpose == RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH) {
            return 1;
        }
        return 2;
    }
}
```

- [ ] **Step 4: Use the budget helper in `RoadPlannerMapPreloadService.tick`**

In `RoadPlannerMapPreloadService.tick`, create one budget before iterating jobs:

```java
RoadPlannerMapPreloadBudget tileBudget = new RoadPlannerMapPreloadBudget(
        RoadPlannerMapPreloadBudget.DEFAULT_GLOBAL_TILE_BUDGET_PER_LEVEL_TICK);
```

Replace:

```java
int processedThisTick = 0;
while (processedThisTick < MAX_TILES_PER_TICK && !active.job.isFinished()) {
```

with:

```java
int maxTilesForJob = tileBudget.claim(active.job.purpose());
int processedThisTick = 0;
while (processedThisTick < maxTilesForJob && !active.job.isFinished()) {
```

After the block handling finished jobs, add:

```java
if (tileBudget.remaining() <= 0) {
    break;
}
```

Leave `MAX_FORCE_CHUNKS_PER_TICK` in place; it still caps chunk tickets inside one tile preparation.

- [ ] **Step 5: Remove unused `MAX_TILES_PER_TICK` if the compiler flags it**

If `MAX_TILES_PER_TICK` becomes unused, delete:

```java
private static final int MAX_TILES_PER_TICK = 4;
```

- [ ] **Step 6: Run budget and preload tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerMapPreloadBudgetTest --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerMapPreloadJobTest --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerMapPreloadServiceTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadBudget.java src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadService.java src/test/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadBudgetTest.java
git commit -m "Budget map preload work per tick"
```

---

### Task 7: Guard Claim Preview Work Against Regressions

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/ClaimPreviewTerrainServiceTest.java`
- Modify only if tests reveal a real issue: `src/main/java/com/monpai/sailboatmod/nation/service/ClaimPreviewTerrainService.java`

- [ ] **Step 1: Add a regression test that visible work is strictly capped by budget**

Append to `ClaimPreviewTerrainServiceTest`:

```java
@Test
void visibleBudgetCapsSampledTilesPerTick() {
    ClaimPreviewTerrainService service = new ClaimPreviewTerrainService();
    java.util.concurrent.atomic.AtomicInteger sampled = new java.util.concurrent.atomic.AtomicInteger();
    service.enqueueViewportForTest("minecraft:overworld", 0, 0, 3, 0, 22L, "town|budget");

    service.processBudgetedWorkForTest(5, 0, (dimensionId, chunkX, chunkZ) -> {
        sampled.incrementAndGet();
        return new int[] {1, 2, 3, 4};
    });

    assertEquals(5, sampled.get());
    assertEquals(44, service.visibleQueueSizeForTest());
}
```

- [ ] **Step 2: Add a regression test that prefetch does not run while visible work remains**

Append to `ClaimPreviewTerrainServiceTest`:

```java
@Test
void prefetchBudgetIsNotSpentUntilVisibleQueueIsEmpty() {
    ClaimPreviewTerrainService service = new ClaimPreviewTerrainService();
    java.util.concurrent.atomic.AtomicInteger sampled = new java.util.concurrent.atomic.AtomicInteger();
    service.enqueueViewportForTest("minecraft:overworld", 0, 0, 1, 1, 23L, "town|prefetch");

    service.processBudgetedWorkForTest(1, 99, (dimensionId, chunkX, chunkZ) -> {
        sampled.incrementAndGet();
        return new int[] {1, 2, 3, 4};
    });

    assertEquals(1, sampled.get());
    assertEquals(8, service.visibleQueueSizeForTest());
    assertEquals(16, service.prefetchQueueSizeForTest());
}
```

- [ ] **Step 3: Run claim terrain tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.ClaimPreviewTerrainServiceTest
```

Expected: PASS. If it fails, fix only the queue/budget logic needed to make these assertions true.

- [ ] **Step 4: If a fix is needed, keep it local to budget logic**

Acceptable implementation edits are limited to:

```java
public void processBudgetedWork(ServerLevel level, int visibleBudget, int prefetchBudget)
```

and:

```java
private void drainQueue(...)
```

Do not add blocking chunk loads, `CompletableFuture.join()`, or full-region scans.

- [ ] **Step 5: Commit**

```powershell
git add src/test/java/com/monpai/sailboatmod/nation/service/ClaimPreviewTerrainServiceTest.java src/main/java/com/monpai/sailboatmod/nation/service/ClaimPreviewTerrainService.java
git commit -m "Guard claim preview tick budgets"
```

If no production code changed, commit only the test file:

```powershell
git add src/test/java/com/monpai/sailboatmod/nation/service/ClaimPreviewTerrainServiceTest.java
git commit -m "Guard claim preview tick budgets"
```

---

### Task 8: Integration Verification

**Files:**
- No planned source changes.

- [ ] **Step 1: Run focused map tests**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.map.* --tests com.monpai.sailboatmod.client.map.* --tests com.monpai.sailboatmod.client.screen.ClaimWorldMapViewTest --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerMapPreloadBudgetTest --tests com.monpai.sailboatmod.roadplanner.service.RoadPlannerMapPreloadJobTest --tests com.monpai.sailboatmod.nation.service.ClaimPreviewTerrainServiceTest --tests com.monpai.sailboatmod.nation.service.ClaimMapViewportServiceTest
```

Expected: PASS.

- [ ] **Step 2: Run Java compile**

Run:

```powershell
.\gradlew.bat compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run full test if focused tests pass**

Run:

```powershell
.\gradlew.bat test
```

Expected: BUILD SUCCESSFUL. If unrelated tests fail, record exact failing test names and failure messages before continuing.

- [ ] **Step 4: Manual in-game verification**

Run client or packaged mod depending on the current release workflow. Verify:

- Open road planner and force-render unknown chunks.
- Open town claim map over the same area.
- Confirm the same terrain base appears.
- Open nation claim map over the same area.
- Confirm the same terrain base appears.
- Move/zoom the claim map and confirm chunk overlays align with terrain.
- Keep the map open while moving the viewport for at least one minute and confirm the server does not watchdog.
- Close the screen and confirm no continued force-render growth is visible in logs.

- [ ] **Step 5: Final build**

Run:

```powershell
.\gradlew.bat build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit any verification-only test updates if needed**

Only commit files changed by the implementation tasks. Do not add `logs/`, `roadplanner_drafts_test/`, or generated jars.

```powershell
git status --short
```

Expected staged/modified files should be only under `src/main/java`, `src/test/java`, and this plan/spec area.
