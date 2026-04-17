# Land-Road Hybrid Planning, Construction Satisfaction, and Claim-Map Async Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add staged async claim-map loading for town/nation screens, integrate a RoadWeaver-inspired hybrid fallback for land-road planning only, and add Reign of Nether style satisfied-state semantics to road and building construction without changing bridge-road behavior.

**Architecture:** Keep bridge planning on the current path. Split heavy work into immutable-input async tasks and main-thread apply phases. Reuse the existing road planning async service for road work, add a separate claim-map async service for overview UI work, and insert a shared construction-satisfaction layer ahead of real block placement.

**Tech Stack:** Java 17, Forge 1.20.1, existing `RoadPlanningTaskService`, JUnit 5, existing `ServerLevel` test doubles and reflection-based private-method test helpers.

---

## Scope Check

This spec covers three subsystems, but they are intentionally kept in one plan because they share the same execution model:

- staged async inputs and revision-safe result application
- bridge-road exclusion
- main-thread-only world writes

Each task below ends in a testable intermediate state and can be committed independently.

## File Structure Map

### Claim-Map Async

- Create: `src/main/java/com/monpai/sailboatmod/nation/menu/ClaimPreviewMapState.java`
  - shared immutable map payload metadata for town/nation overview screens
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/ClaimMapTaskService.java`
  - async task service for claim-map preparation with latest-request-wins semantics
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/SyncClaimPreviewMapPacket.java`
  - dedicated incremental map sync packet
- Create: `src/main/java/com/monpai/sailboatmod/client/ClaimMapRasterizer.java`
  - client-side terrain and claim overlay raster composition
- Create: `src/main/java/com/monpai/sailboatmod/client/ClaimMapRenderTaskService.java`
  - client async raster task service with revision checks
- Modify: `src/main/java/com/monpai/sailboatmod/nation/menu/TownOverviewData.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/menu/NationOverviewData.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/TownOverviewService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/NationOverviewService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ClaimPreviewTerrainService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/ServerEvents.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/OpenTownScreenPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/OpenNationScreenPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/TownClientHooks.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/NationClientHooks.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreen.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/nation/NationHomeScreen.java`

### Land-Road Hybrid Planning

- Create: `src/main/java/com/monpai/sailboatmod/nation/service/LandRoadRouteSelector.java`
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinder.java`
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/LandTerrainSamplingCache.java`
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/LandPathCostModel.java`
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/LandPathQualityEvaluator.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningTaskService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningSnapshotBuilder.java`

### Construction Satisfaction

- Create: `src/main/java/com/monpai/sailboatmod/construction/ConstructionStepSatisfactionService.java`
- Create: `src/main/java/com/monpai/sailboatmod/construction/ConstructionStateMatchers.java`
- Create: `src/main/java/com/monpai/sailboatmod/construction/ConstructionStepExecutor.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- Modify: `src/main/java/com/monpai/sailboatmod/resident/entity/goal/BuilderJobGoal.java`

### Tests

- Create: `src/test/java/com/monpai/sailboatmod/nation/service/ClaimMapTaskServiceTest.java`
- Create: `src/test/java/com/monpai/sailboatmod/client/ClaimMapRasterizerTest.java`
- Create: `src/test/java/com/monpai/sailboatmod/network/packet/ClaimOverviewPacketRoundTripTest.java`
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/LandRoadRouteSelectorTest.java`
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinderTest.java`
- Create: `src/test/java/com/monpai/sailboatmod/construction/ConstructionStepSatisfactionServiceTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/RoadPlanningTaskServiceTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/RoadPlanningSnapshotBuilderTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/TownOverviewServiceTest.java`

### Task 1: Add Claim-Map Async State and Task Service

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/nation/menu/ClaimPreviewMapState.java`
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/ClaimMapTaskService.java`
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/ClaimMapTaskServiceTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/menu/TownOverviewData.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/menu/NationOverviewData.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.menu.ClaimPreviewMapState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimMapTaskServiceTest {
    @Test
    void newerClaimMapRequestReplacesOlderResultForSameScreen() {
        ClaimMapTaskService service = new ClaimMapTaskService(Runnable::run, Runnable::run);
        List<ClaimPreviewMapState> applied = new ArrayList<>();

        ClaimMapTaskService.TaskHandle<ClaimPreviewMapState> first = service.submitForTest(
                new ClaimMapTaskService.TaskKey("town-claims", "player-a"),
                () -> ClaimPreviewMapState.loading(1L, 8, 100, 200),
                applied::add
        );
        ClaimMapTaskService.TaskHandle<ClaimPreviewMapState> second = service.submitForTest(
                new ClaimMapTaskService.TaskKey("town-claims", "player-a"),
                () -> ClaimPreviewMapState.ready(2L, 8, 100, 200, List.of(0xFF112233)),
                applied::add
        );

        first.completeForTest();
        second.completeForTest();

        assertEquals(List.of(ClaimPreviewMapState.ready(2L, 8, 100, 200, List.of(0xFF112233))), applied);
    }

    @Test
    void mapStateFactoriesNormalizeNullColorsAndKeepRevision() {
        ClaimPreviewMapState state = ClaimPreviewMapState.ready(7L, 6, 12, 24, null);

        assertEquals(7L, state.revision());
        assertEquals(0, state.colors().size());
        assertTrue(state.ready());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ClaimMapTaskServiceTest" -q`  
Expected: FAIL with missing `ClaimMapTaskService` and `ClaimPreviewMapState`.

- [ ] **Step 3: Write minimal implementation**

```java
package com.monpai.sailboatmod.nation.menu;

import java.util.List;

public record ClaimPreviewMapState(long revision,
                                   int radius,
                                   int centerChunkX,
                                   int centerChunkZ,
                                   boolean loading,
                                   boolean ready,
                                   List<Integer> colors) {
    public ClaimPreviewMapState {
        colors = colors == null ? List.of() : colors.stream()
                .map(color -> 0xFF000000 | (color & 0x00FFFFFF))
                .toList();
    }

    public static ClaimPreviewMapState loading(long revision, int radius, int centerChunkX, int centerChunkZ) {
        return new ClaimPreviewMapState(revision, radius, centerChunkX, centerChunkZ, true, false, List.of());
    }

    public static ClaimPreviewMapState ready(long revision, int radius, int centerChunkX, int centerChunkZ, List<Integer> colors) {
        return new ClaimPreviewMapState(revision, radius, centerChunkX, centerChunkZ, false, true, colors);
    }
}
```

```java
package com.monpai.sailboatmod.nation.service;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ClaimMapTaskService {
    private static final java.util.concurrent.atomic.AtomicReference<ClaimMapTaskService> ACTIVE = new java.util.concurrent.atomic.AtomicReference<>();
    private final Executor computeExecutor;
    private final Executor mainThreadExecutor;
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicLong requestIds = new AtomicLong();
    private final ConcurrentHashMap<TaskKey, Long> activeRequests = new ConcurrentHashMap<>();

    public ClaimMapTaskService(Executor computeExecutor, Executor mainThreadExecutor) {
        this.computeExecutor = Objects.requireNonNull(computeExecutor, "computeExecutor");
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
    }

    public static ClaimMapTaskService get() {
        return ACTIVE.get();
    }

    public static void onServerStarted(net.minecraft.server.MinecraftServer server) {
        ACTIVE.set(new ClaimMapTaskService(java.util.concurrent.Executors.newFixedThreadPool(2), server::execute));
    }

    public static void onServerStopping() {
        ACTIVE.set(null);
    }

    public <T> java.util.concurrent.CompletableFuture<T> submitLatest(TaskKey key, Supplier<T> supplier, Consumer<T> apply) {
        long requestId = requestIds.incrementAndGet();
        long submittedEpoch = epoch.get();
        activeRequests.put(key, requestId);
        return java.util.concurrent.CompletableFuture.supplyAsync(supplier, computeExecutor)
                .thenApply(result -> isCurrent(key, requestId, submittedEpoch) ? result : null)
                .thenApplyAsync(result -> {
                    if (result != null && isCurrent(key, requestId, submittedEpoch)) {
                        apply.accept(result);
                    }
                    return result;
                }, mainThreadExecutor);
    }

    TaskHandle<com.monpai.sailboatmod.nation.menu.ClaimPreviewMapState> submitForTest(
            TaskKey key,
            Supplier<com.monpai.sailboatmod.nation.menu.ClaimPreviewMapState> supplier,
            Consumer<com.monpai.sailboatmod.nation.menu.ClaimPreviewMapState> apply
    ) {
        long requestId = requestIds.incrementAndGet();
        long submittedEpoch = epoch.get();
        activeRequests.put(key, requestId);
        return new TaskHandle<>(supplier, apply, key, requestId, submittedEpoch);
    }

    boolean isCurrent(TaskKey key, long requestId, long submittedEpoch) {
        return epoch.get() == submittedEpoch && Objects.equals(activeRequests.get(key), requestId);
    }

    public record TaskKey(String kind, String ownerKey) {}

    final class TaskHandle<T> {
        private final Supplier<T> supplier;
        private final Consumer<T> apply;
        private final TaskKey key;
        private final long requestId;
        private final long submittedEpoch;

        TaskHandle(Supplier<T> supplier, Consumer<T> apply, TaskKey key, long requestId, long submittedEpoch) {
            this.supplier = supplier;
            this.apply = apply;
            this.key = key;
            this.requestId = requestId;
            this.submittedEpoch = submittedEpoch;
        }

        void completeForTest() {
            T value = supplier.get();
            if (isCurrent(key, requestId, submittedEpoch)) {
                apply.accept(value);
            }
        }
    }
}
```

```java
// Add one field to both overview records:
ClaimPreviewMapState claimMapState
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ClaimMapTaskServiceTest" -q`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/menu/ClaimPreviewMapState.java src/main/java/com/monpai/sailboatmod/nation/service/ClaimMapTaskService.java src/main/java/com/monpai/sailboatmod/nation/menu/TownOverviewData.java src/main/java/com/monpai/sailboatmod/nation/menu/NationOverviewData.java src/test/java/com/monpai/sailboatmod/nation/service/ClaimMapTaskServiceTest.java
git commit -m "Add claim map async state model"
```

### Task 2: Split Overview Open Packets From Heavy Claim-Map Payloads

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/SyncClaimPreviewMapPacket.java`
- Create: `src/test/java/com/monpai/sailboatmod/network/packet/ClaimOverviewPacketRoundTripTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/TownOverviewService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/NationOverviewService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/ServerEvents.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/OpenTownScreenPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/OpenNationScreenPacket.java`

- [ ] **Step 1: Write the failing packet round-trip tests**

```java
package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.menu.ClaimPreviewMapState;
import com.monpai.sailboatmod.nation.menu.TownOverviewData;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimOverviewPacketRoundTripTest {
    @Test
    void openTownPacketCarriesEmptyMapStateWithoutTerrainColorPayload() {
        TownOverviewData data = new TownOverviewData(
                true, "town-a", "Town A", "", "", "", "", false, 0x123456, 0x654321,
                false, "", 0L, 0, 0, 0, 0, 10, 20, false, false, "", "", "", "", "", "", "", "",
                0, 0, 0L, "", false, false, false, false, false, false,
                List.of(), List.of(), List.of(), "european", Map.of(), 0.0f, Map.of(), 0.0f,
                0, 0, 0, 0, 0, 0L, 0L, 0L, List.of(), List.of(), List.of(), List.of(), List.of(),
                ClaimPreviewMapState.loading(11L, 8, 10, 20)
        );

        FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        OpenTownScreenPacket.encode(new OpenTownScreenPacket(data), buffer);
        OpenTownScreenPacket decoded = OpenTownScreenPacket.decode(buffer);

        assertEquals(11L, decoded.data().claimMapState().revision());
        assertTrue(decoded.data().nearbyTerrainColors().isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.network.packet.ClaimOverviewPacketRoundTripTest" -q`  
Expected: FAIL because overview data and packets do not yet carry `claimMapState`.

- [ ] **Step 3: Write minimal implementation**

```java
// In TownOverviewService and NationOverviewService, replace direct terrain sampling:
ClaimPreviewMapState mapState = ClaimPreviewMapState.loading(
        System.nanoTime(),
        claimPreviewRadius(),
        previewChunk.x,
        previewChunk.z
);
```

```java
// In TownOverviewService and NationOverviewService, queue the async claim-map job on first open:
ClaimMapTaskService taskService = ClaimMapTaskService.get();
if (taskService != null) {
    long revision = mapState.revision();
    taskService.submitLatest(
            new ClaimMapTaskService.TaskKey("town-claims", player.getUUID() + "|" + town.townId()),
            () -> ClaimPreviewMapState.ready(
                    revision,
                    claimPreviewRadius(),
                    previewChunk.x,
                    previewChunk.z,
                    ClaimPreviewTerrainService.sample(player.serverLevel(), previewChunk, claimPreviewRadius())
            ),
            readyState -> ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new SyncClaimPreviewMapPacket("town-claims", readyState.revision(), readyState.radius(), readyState.centerChunkX(), readyState.centerChunkZ(), readyState.colors())
            )
    );
}
```

```java
// In OpenTownScreenPacket/OpenNationScreenPacket encode:
buffer.writeLong(data.claimMapState().revision());
buffer.writeVarInt(data.claimMapState().radius());
buffer.writeInt(data.claimMapState().centerChunkX());
buffer.writeInt(data.claimMapState().centerChunkZ());
buffer.writeBoolean(data.claimMapState().loading());
buffer.writeBoolean(data.claimMapState().ready());
```

```java
package com.monpai.sailboatmod.network.packet;

import net.minecraft.network.FriendlyByteBuf;

import java.util.List;

public record SyncClaimPreviewMapPacket(String screenKind,
                                        long revision,
                                        int radius,
                                        int centerChunkX,
                                        int centerChunkZ,
                                        List<Integer> colors) {
    public static void encode(SyncClaimPreviewMapPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUtf(packet.screenKind(), 32);
        buffer.writeLong(packet.revision());
        buffer.writeVarInt(packet.radius());
        buffer.writeInt(packet.centerChunkX());
        buffer.writeInt(packet.centerChunkZ());
        buffer.writeVarInt(packet.colors().size());
        for (Integer color : packet.colors()) {
            buffer.writeInt(color == null ? 0xFF33414A : color);
        }
    }
}
```

```java
// Register the packet in ModNetwork and start/stop ClaimMapTaskService in ServerEvents:
ClaimMapTaskService.onServerStarted(event.getServer());
ClaimMapTaskService.onServerStopping();
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.network.packet.ClaimOverviewPacketRoundTripTest" -q`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/TownOverviewService.java src/main/java/com/monpai/sailboatmod/nation/service/NationOverviewService.java src/main/java/com/monpai/sailboatmod/network/packet/OpenTownScreenPacket.java src/main/java/com/monpai/sailboatmod/network/packet/OpenNationScreenPacket.java src/main/java/com/monpai/sailboatmod/network/packet/SyncClaimPreviewMapPacket.java src/test/java/com/monpai/sailboatmod/network/packet/ClaimOverviewPacketRoundTripTest.java
git commit -m "Split claim map payload from overview packets"
```

### Task 3: Add Client Claim-Map Rasterizer and Async Render Hook

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/client/ClaimMapRasterizer.java`
- Create: `src/main/java/com/monpai/sailboatmod/client/ClaimMapRenderTaskService.java`
- Create: `src/test/java/com/monpai/sailboatmod/client/ClaimMapRasterizerTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/TownClientHooks.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/NationClientHooks.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreen.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/nation/NationHomeScreen.java`

- [ ] **Step 1: Write the failing rasterizer tests**

```java
package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.nation.menu.NationOverviewClaim;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaimMapRasterizerTest {
    @Test
    void rasterizerOverlaysClaimPrimaryColorOnTerrainCell() {
        int[] pixels = ClaimMapRasterizer.rasterize(
                1,
                List.of(0xFF33414A, 0xFF33414A, 0xFF33414A, 0xFF33414A),
                List.of(new NationOverviewClaim(0, 0, "nation-a", "Nation A", 0x00AA5500, 0x00FFFFFF, "town-a", "Town A", "", "", "", "", "", "", "")),
                0,
                0
        );

        assertEquals(0xFFAA5500, pixels[0]);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.client.ClaimMapRasterizerTest" -q`  
Expected: FAIL because `ClaimMapRasterizer` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.nation.menu.NationOverviewClaim;

import java.util.List;

public final class ClaimMapRasterizer {
    private ClaimMapRasterizer() {}

    public static int[] rasterize(int radius,
                                  List<Integer> terrainColors,
                                  List<NationOverviewClaim> claims,
                                  int centerChunkX,
                                  int centerChunkZ) {
        int size = Math.max(1, radius * 2 + 1);
        int[] pixels = new int[size * size];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = i < terrainColors.size() ? terrainColors.get(i) : 0xFF33414A;
        }
        for (NationOverviewClaim claim : claims) {
            int localX = claim.chunkX() - centerChunkX + radius;
            int localZ = claim.chunkZ() - centerChunkZ + radius;
            if (localX < 0 || localZ < 0 || localX >= size || localZ >= size) {
                continue;
            }
            pixels[localZ * size + localX] = 0xFF000000 | (claim.primaryColorRgb() & 0x00FFFFFF);
        }
        return pixels;
    }
}
```

```java
// In TownClientHooks/NationClientHooks:
// - keep last overview data
// - submit raster work with revision
// - only apply texture if revision still matches the current screen state
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.client.ClaimMapRasterizerTest" -q`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/client/ClaimMapRasterizer.java src/main/java/com/monpai/sailboatmod/client/ClaimMapRenderTaskService.java src/main/java/com/monpai/sailboatmod/client/TownClientHooks.java src/main/java/com/monpai/sailboatmod/client/NationClientHooks.java src/main/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreen.java src/main/java/com/monpai/sailboatmod/client/screen/nation/NationHomeScreen.java src/test/java/com/monpai/sailboatmod/client/ClaimMapRasterizerTest.java
git commit -m "Add async claim map rasterization hooks"
```

### Task 4: Add Land-Road Hybrid Selector, Cost Model, and Trigger Tests

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/LandRoadRouteSelector.java`
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/LandTerrainSamplingCache.java`
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/LandPathCostModel.java`
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/LandPathQualityEvaluator.java`
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/LandRoadRouteSelectorTest.java`

- [ ] **Step 1: Write the failing selector tests**

```java
package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LandRoadRouteSelectorTest {
    @Test
    void selectorKeepsLegacyPathWhenQualityIsAcceptable() {
        LandRoadRouteSelector.Selection selection = LandRoadRouteSelector.selectForTest(
                new BlockPos(0, 64, 0),
                new BlockPos(8, 64, 0),
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                RoadPlanningFailureReason.NONE,
                0,
                0,
                0
        );

        assertEquals(LandRoadRouteSelector.BackEnd.LEGACY, selection.backEnd());
    }

    @Test
    void selectorSwitchesToHybridWhenLegacyFailsContinuousGround() {
        LandRoadRouteSelector.Selection selection = LandRoadRouteSelector.selectForTest(
                new BlockPos(0, 64, 0),
                new BlockPos(40, 90, 0),
                List.of(),
                RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE,
                12,
                8,
                20
        );

        assertEquals(LandRoadRouteSelector.BackEnd.HYBRID, selection.backEnd());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.LandRoadRouteSelectorTest" -q`  
Expected: FAIL because `LandRoadRouteSelector` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;

import java.util.List;

public final class LandRoadRouteSelector {
    public enum BackEnd { LEGACY, HYBRID }

    public record Selection(BackEnd backEnd, String reason) {}

    private static final int SOFT_ELEVATION_TRIGGER = 8;
    private static final int SOFT_WATER_TRIGGER = 6;

    private LandRoadRouteSelector() {}

    static Selection selectForTest(BlockPos from,
                                   BlockPos to,
                                   List<BlockPos> legacyPath,
                                   RoadPlanningFailureReason failureReason,
                                   int elevationVariance,
                                   int nearWaterColumns,
                                   int fragmentedColumns) {
        if (failureReason == RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE || legacyPath == null || legacyPath.size() < 2) {
            return new Selection(BackEnd.HYBRID, "legacy_failed");
        }
        if (elevationVariance >= SOFT_ELEVATION_TRIGGER || nearWaterColumns >= SOFT_WATER_TRIGGER || fragmentedColumns > 0) {
            return new Selection(BackEnd.HYBRID, "soft_trigger");
        }
        return new Selection(BackEnd.LEGACY, "legacy_ok");
    }
}
```

```java
// LandPathCostModel should expose one pure helper used later by the pathfinder:
double moveCost(int orthoOrDiagCost, int elevationDelta, int stabilityCost, int nearWaterCost, double deviationCost)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.LandRoadRouteSelectorTest" -q`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/LandRoadRouteSelector.java src/main/java/com/monpai/sailboatmod/nation/service/LandTerrainSamplingCache.java src/main/java/com/monpai/sailboatmod/nation/service/LandPathCostModel.java src/main/java/com/monpai/sailboatmod/nation/service/LandPathQualityEvaluator.java src/test/java/com/monpai/sailboatmod/nation/service/LandRoadRouteSelectorTest.java
git commit -m "Add land road hybrid selector and cost primitives"
```

### Task 5: Integrate Hybrid Land Planning Into RoadPathfinder and Auto-Route Async Work

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinder.java`
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinderTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
- Modify: `src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningTaskService.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/RoadPlanningTaskServiceTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/RoadPlanningSnapshotBuilderTest.java`

- [ ] **Step 1: Write the failing hybrid integration tests**

```java
@Test
void taskServiceKeepsAutoRouteRequestsIsolatedFromManualPreviewRequests() {
    RoadPlanningTaskService service = new RoadPlanningTaskService(Runnable::run, Runnable::run);
    List<String> applied = new ArrayList<>();

    RoadPlanningTaskService.TaskHandle<String> manual = service.submitForTest(
            new RoadPlanningTaskService.TaskKey("manual-preview", "player-a"),
            () -> "manual",
            applied::add
    );
    RoadPlanningTaskService.TaskHandle<String> auto = service.submitForTest(
            new RoadPlanningTaskService.TaskKey("auto-route", "station-a|station-b"),
            () -> "auto",
            applied::add
    );

    manual.completeForTest();
    auto.completeForTest();

    assertEquals(List.of("manual", "auto"), applied);
}
```

```java
@Test
void snapshotBuilderExposesDenseRibbonColumnsNeededByHybridFallback() {
    RoadPlanningSnapshot snapshot = RoadPlanningSnapshotBuilder.buildForTest(level, new BlockPos(0, 64, 0), new BlockPos(32, 64, 0), Set.of(), Set.of());
    assertNotNull(snapshot.column(1, 0));
}
```

```java
@Test
void hybridPathfinderReturnsContinuousLandOnlyPathOnBrokenSlopeFixture() {
    RoadPathfinder.PlannedPathResult result = LandRoadHybridPathfinder.find(level, new BlockPos(0, 64, 0), new BlockPos(6, 68, 0), Set.of(), Set.of(), new RoadPlanningPassContext(level));

    assertTrue(result.success());
    assertTrue(result.path().size() >= 2);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadPlanningTaskServiceTest" --tests "com.monpai.sailboatmod.nation.service.RoadPlanningSnapshotBuilderTest" -q`  
Expected: FAIL because the new `auto-route` isolation and hybrid snapshot assumptions are not wired.

- [ ] **Step 3: Write minimal implementation**

```java
// In RoadPathfinder.findGroundPathForPlan(...)
PlannedPathResult legacy = findPathForPlan(level, from, to, blockedColumns, excludedColumns, false, context);
LandRoadRouteSelector.Selection selection = LandRoadRouteSelector.selectForTest(
        from,
        to,
        legacy.path(),
        legacy.failureReason(),
        estimateElevationVariance(context, from, to),
        estimateNearWaterColumns(context, from, to),
        estimateFragmentedColumns(context, from, to)
);
if (selection.backEnd() == LandRoadRouteSelector.BackEnd.LEGACY) {
    return legacy;
}
return LandRoadHybridPathfinder.find(level, from, to, blockedColumns, excludedColumns, context);
```

```java
// In RoadAutoRouteService add async entry points rather than replacing sync ones:
public static CompletableFuture<RouteResolution> resolveAutoRouteAsync(ServerLevel level, BlockPos start, BlockPos end, Consumer<RouteResolution> apply) {
    RoadPlanningTaskService taskService = RoadPlanningTaskService.get();
    return taskService.submitLatest(
            new RoadPlanningTaskService.TaskKey("auto-route", start.asLong() + "|" + end.asLong()),
            () -> resolveAutoRoute(level, start, end),
            apply
    );
}
```

```java
package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public final class LandRoadHybridPathfinder {
    private LandRoadHybridPathfinder() {}

    public static RoadPathfinder.PlannedPathResult find(Level level,
                                                        BlockPos from,
                                                        BlockPos to,
                                                        Set<Long> blockedColumns,
                                                        Set<Long> excludedColumns,
                                                        RoadPlanningPassContext context) {
        LandTerrainSamplingCache cache = new LandTerrainSamplingCache(level, context);
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score));
        Map<Long, Node> best = new HashMap<>();
        open.add(new Node(from, null, 0.0D, heuristic(from, to)));
        best.put(from.asLong(), new Node(from, null, 0.0D, heuristic(from, to)));

        while (!open.isEmpty()) {
            Node current = open.poll();
            if (current.pos().distManhattan(to) <= 1) {
                return new RoadPathfinder.PlannedPathResult(rebuild(current, to), RoadPlanningFailureReason.NONE);
            }
            for (BlockPos next : neighbors(current.pos(), cache, blockedColumns, excludedColumns)) {
                double g = current.gScore() + LandPathCostModel.moveCost(10, Math.abs(next.getY() - current.pos().getY()), cache.stability(next), cache.nearWater(next), 0.0D);
                Node existing = best.get(next.asLong());
                if (existing != null && existing.gScore() <= g) {
                    continue;
                }
                Node candidate = new Node(next, current, g, g + heuristic(next, to));
                best.put(next.asLong(), candidate);
                open.add(candidate);
            }
        }
        return new RoadPathfinder.PlannedPathResult(List.of(), RoadPlanningFailureReason.SEARCH_EXHAUSTED);
    }

    private static double heuristic(BlockPos from, BlockPos to) {
        return from.distManhattan(to);
    }

    private static List<BlockPos> rebuild(Node node, BlockPos target) {
        ArrayList<BlockPos> out = new ArrayList<>();
        Node cursor = node;
        out.add(target.immutable());
        while (cursor != null) {
            out.add(0, cursor.pos().immutable());
            cursor = cursor.parent();
        }
        return List.copyOf(out);
    }

    private static List<BlockPos> neighbors(BlockPos pos,
                                            LandTerrainSamplingCache cache,
                                            Set<Long> blockedColumns,
                                            Set<Long> excludedColumns) {
        ArrayList<BlockPos> out = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int x = pos.getX() + dx;
                int z = pos.getZ() + dz;
                long key = BlockPos.asLong(x, 0, z);
                if ((blockedColumns != null && blockedColumns.contains(key)) || (excludedColumns != null && excludedColumns.contains(key))) {
                    continue;
                }
                out.add(cache.surface(x, z));
            }
        }
        return out;
    }

    private record Node(BlockPos pos, Node parent, double gScore, double score) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadPlanningTaskServiceTest" --tests "com.monpai.sailboatmod.nation.service.RoadPlanningSnapshotBuilderTest" -q`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinder.java src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningTaskService.java src/test/java/com/monpai/sailboatmod/nation/service/RoadPlanningTaskServiceTest.java src/test/java/com/monpai/sailboatmod/nation/service/RoadPlanningSnapshotBuilderTest.java
git commit -m "Integrate land road hybrid fallback and auto route async"
```

### Task 6: Add Construction Satisfaction Matchers and Core Service

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/construction/ConstructionStateMatchers.java`
- Create: `src/main/java/com/monpai/sailboatmod/construction/ConstructionStepSatisfactionService.java`
- Create: `src/test/java/com/monpai/sailboatmod/construction/ConstructionStepSatisfactionServiceTest.java`

- [ ] **Step 1: Write the failing matcher tests**

```java
package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConstructionStepSatisfactionServiceTest {
    @Test
    void naturalLeavesAreRetryableCleanupNotSatisfiedRoadDeck() {
        ConstructionStepSatisfactionService.StepDecision decision =
                ConstructionStepSatisfactionService.decideForTest(
                        Blocks.OAK_LEAVES.defaultBlockState(),
                        Blocks.STONE_BRICK_SLAB.defaultBlockState(),
                        new BlockPos(0, 64, 0),
                        ConstructionStepSatisfactionService.StepKind.ROAD_DECK
                );

        assertEquals(ConstructionStepSatisfactionService.StepDecision.RETRYABLE, decision);
    }

    @Test
    void equivalentStoneBrickDeckCountsAsSatisfied() {
        ConstructionStepSatisfactionService.StepDecision decision =
                ConstructionStepSatisfactionService.decideForTest(
                        Blocks.STONE_BRICKS.defaultBlockState(),
                        Blocks.STONE_BRICK_SLAB.defaultBlockState(),
                        new BlockPos(0, 64, 0),
                        ConstructionStepSatisfactionService.StepKind.ROAD_DECK
                );

        assertEquals(ConstructionStepSatisfactionService.StepDecision.SATISFIED, decision);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.construction.ConstructionStepSatisfactionServiceTest" -q`  
Expected: FAIL because the service and matcher classes do not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.monpai.sailboatmod.construction;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class ConstructionStateMatchers {
    private ConstructionStateMatchers() {}

    static boolean isNaturalCleanup(BlockState state) {
        return state.is(Blocks.OAK_LEAVES) || state.is(Blocks.GRASS) || state.is(Blocks.FERN) || state.is(Blocks.SNOW);
    }

    static boolean isEquivalentRoadDeck(BlockState existing, BlockState target) {
        return existing.is(Blocks.STONE_BRICKS) && target.is(Blocks.STONE_BRICK_SLAB);
    }
}
```

```java
package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public final class ConstructionStepSatisfactionService {
    public enum StepKind { ROAD_DECK, ROAD_SUPPORT, ROAD_DECOR, BUILDING_BLOCK }
    public enum StepDecision { SATISFIED, PLACE_NOW, RETRYABLE, BLOCKED }

    private ConstructionStepSatisfactionService() {}

    static StepDecision decideForTest(BlockState existing, BlockState target, BlockPos pos, StepKind kind) {
        if (existing == null || existing.isAir()) {
            return StepDecision.PLACE_NOW;
        }
        if (existing.equals(target) || ConstructionStateMatchers.isEquivalentRoadDeck(existing, target)) {
            return StepDecision.SATISFIED;
        }
        if (ConstructionStateMatchers.isNaturalCleanup(existing)) {
            return StepDecision.RETRYABLE;
        }
        return StepDecision.BLOCKED;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.construction.ConstructionStepSatisfactionServiceTest" -q`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/construction/ConstructionStateMatchers.java src/main/java/com/monpai/sailboatmod/construction/ConstructionStepSatisfactionService.java src/test/java/com/monpai/sailboatmod/construction/ConstructionStepSatisfactionServiceTest.java
git commit -m "Add construction satisfaction matchers"
```

### Task 7: Wire Construction Satisfaction Into Road and Building Construction

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/construction/ConstructionStepExecutor.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- Modify: `src/main/java/com/monpai/sailboatmod/resident/entity/goal/BuilderJobGoal.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`

- [ ] **Step 1: Write the failing integration tests**

```java
@Test
void alreadySatisfiedRoadDeckStepAdvancesWithoutWritingDuplicateBlock() {
    TestServerLevel level = allocate(TestServerLevel.class);
    level.blockStates = new HashMap<>();
    level.surfaceHeights = new HashMap<>();
    level.biome = Holder.direct(allocate(Biome.class));
    level.blockStates.put(new BlockPos(0, 64, 0).asLong(), Blocks.STONE_BRICKS.defaultBlockState());

    RoadPlacementPlan plan = new RoadPlacementPlan(
            List.of(new BlockPos(0, 64, 0)),
            new BlockPos(0, 64, 0),
            new BlockPos(0, 64, 0),
            new BlockPos(0, 64, 0),
            new BlockPos(0, 64, 0),
            List.of(new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())),
            List.of(new RoadGeometryPlanner.RoadBuildStep(0, new BlockPos(0, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState(), RoadGeometryPlanner.RoadBuildPhase.DECK)),
            List.of(),
            List.of(),
            new BlockPos(0, 64, 0),
            new BlockPos(0, 64, 0),
            new BlockPos(0, 64, 0)
    );

    Object advanced = invokeAdvanceRoadBuildSteps(level, newRoadConstructionJob(level, "manual|test|satisfied", plan), 1);
    assertEquals(1, readRecordComponentAsInt(advanced, "placedStepCount"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest" -q`  
Expected: FAIL because `placeRoadBuildSteps(...)` still treats only direct placement as completion.

- [ ] **Step 3: Write minimal implementation**

```java
// In StructureConstructionManager.placeRoadBuildSteps(...)
ConstructionStepSatisfactionService.StepDecision decision =
        ConstructionStepSatisfactionService.decideForTest(
                level.getBlockState(step.pos()),
                step.state(),
                step.pos(),
                toStepKind(step.phase())
        );
if (decision == ConstructionStepSatisfactionService.StepDecision.SATISFIED) {
    attemptedStepKeys.add(step.pos().asLong());
    completedCount++;
    continue;
}
if (decision == ConstructionStepSatisfactionService.StepDecision.RETRYABLE) {
    clearNaturalObstacles(level, step.pos());
    continue;
}
if (decision == ConstructionStepSatisfactionService.StepDecision.BLOCKED) {
    highestPlaceablePhase = step.phase();
    continue;
}
```

```java
// In the structure/building block-placement path inside StructureConstructionManager:
ConstructionStepSatisfactionService.StepDecision decision =
        ConstructionStepSatisfactionService.decideForTest(
                level.getBlockState(nextPos),
                targetState,
                nextPos,
                ConstructionStepSatisfactionService.StepKind.BUILDING_BLOCK
        );
if (decision == ConstructionStepSatisfactionService.StepDecision.SATISFIED) {
    markStructureStepCompleted(jobId, nextPos);
    return;
}
if (decision == ConstructionStepSatisfactionService.StepDecision.RETRYABLE) {
    ConstructionStepExecutor.clearNaturalObstacles(level, nextPos);
    return;
}
```

```java
package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

public final class ConstructionStepExecutor {
    private ConstructionStepExecutor() {}

    public static void clearNaturalObstacles(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        if (!level.getBlockState(pos).isAir()) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest" -q`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/construction/ConstructionStepExecutor.java src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/main/java/com/monpai/sailboatmod/resident/entity/goal/BuilderJobGoal.java src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java
git commit -m "Wire construction satisfaction into road building"
```

### Task 8: Run Final Verification and Update the Design Trail

**Files:**
- Modify: `docs/superpowers/specs/2026-04-17-land-road-construction-and-claim-map-async-design.md`
- Modify: `docs/superpowers/plans/2026-04-17-land-road-construction-and-claim-map-async.md`

- [ ] **Step 1: Run the focused test suites**

```bash
.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ClaimMapTaskServiceTest" --tests "com.monpai.sailboatmod.network.packet.ClaimOverviewPacketRoundTripTest" --tests "com.monpai.sailboatmod.client.ClaimMapRasterizerTest" --tests "com.monpai.sailboatmod.nation.service.LandRoadRouteSelectorTest" --tests "com.monpai.sailboatmod.nation.service.RoadPlanningTaskServiceTest" --tests "com.monpai.sailboatmod.construction.ConstructionStepSatisfactionServiceTest" --tests "com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest" -q
```

Expected: PASS

- [ ] **Step 2: Run a full Java compile pass**

```bash
.\gradlew.bat compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Record any implementation deviations in the plan and spec**

```markdown
Add a short "Implementation Notes" section only if actual code differed from the planned class or method names.
If no deviations occurred, leave both documents unchanged.
```

- [ ] **Step 4: Commit verification and doc touch-up**

```bash
git add docs/superpowers/specs/2026-04-17-land-road-construction-and-claim-map-async-design.md docs/superpowers/plans/2026-04-17-land-road-construction-and-claim-map-async.md
git commit -m "Document async road and claim map verification"
```

- [ ] **Step 5: Prepare branch for execution review**

```bash
git status --short
```

Expected: clean worktree or only intentional follow-up changes
