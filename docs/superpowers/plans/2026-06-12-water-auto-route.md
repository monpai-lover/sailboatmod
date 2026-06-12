# Water Auto Route Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace synchronous dock-to-dock water auto-route generation with a permission-aware, berth-based, tick-budgeted water routing system.

**Architecture:** Add a focused `com.monpai.sailboatmod.route.water` package containing route result models, permission checks, dock berth resolution, a budgeted water A* pathfinder, and a server task queue. Existing packets call `WaterAutoRouteService`; `AutoRouteService` remains as a legacy facade so older call sites keep compiling.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, Forge networking `SimpleChannel`, JUnit 5, existing `DockBlockEntity`, `RouteDefinition`, `NationSavedData`, and `ServerEvents` hooks.

---

## File Map

- Create `src/main/java/com/monpai/sailboatmod/route/water/WaterRouteFailureReason.java`: stable failure enum for tests and localized messages.
- Create `src/main/java/com/monpai/sailboatmod/route/water/WaterRoutePolicy.java`: routing budget and boat footprint constants.
- Create `src/main/java/com/monpai/sailboatmod/route/water/WaterRouteResult.java`: success/failure result for permission, berth, path, and task operations.
- Create `src/main/java/com/monpai/sailboatmod/route/water/WaterRoutePermissionService.java`: pure permission predicate for dock town/nation/diplomacy.
- Create `src/main/java/com/monpai/sailboatmod/route/water/DockBerthResolver.java`: finds water berths inside an existing dock zone.
- Create `src/main/java/com/monpai/sailboatmod/route/water/WaterColumn.java`: sampled water column state.
- Create `src/main/java/com/monpai/sailboatmod/route/water/WaterRouteWorld.java`: small interface for pathfinder/test worlds.
- Create `src/main/java/com/monpai/sailboatmod/route/water/ServerWaterRouteWorld.java`: `ServerLevel` adapter for water sampling and bounded chunk access.
- Create `src/main/java/com/monpai/sailboatmod/route/water/WaterRoutePathfinder.java`: budgeted A* over water columns.
- Create `src/main/java/com/monpai/sailboatmod/route/water/WaterRouteTask.java`: one pending route generation job.
- Create `src/main/java/com/monpai/sailboatmod/route/water/WaterRouteTaskService.java`: server-level pending task registry and tick runner.
- Create `src/main/java/com/monpai/sailboatmod/route/water/WaterAutoRouteService.java`: public packet/server facade.
- Modify `src/main/java/com/monpai/sailboatmod/route/AutoRouteService.java`: delegate permission and route creation to the new water service where possible, keep `findWaterRoute` legacy.
- Modify `src/main/java/com/monpai/sailboatmod/network/packet/RequestAutoRouteDocksPacket.java`: filter water candidates by new permission and berth checks.
- Modify `src/main/java/com/monpai/sailboatmod/network/packet/CreateAutoRoutePacket.java`: submit water task instead of synchronous route generation.
- Modify `src/main/java/com/monpai/sailboatmod/ServerEvents.java`: tick and stop `WaterRouteTaskService`.
- Modify `src/main/resources/assets/sailboatmod/lang/en_us.json`: add water auto-route messages.
- Modify `src/main/resources/assets/sailboatmod/lang/zh_cn.json`: add water auto-route messages.
- Test `src/test/java/com/monpai/sailboatmod/route/water/WaterRoutePermissionServiceTest.java`.
- Test `src/test/java/com/monpai/sailboatmod/route/water/DockBerthResolverTest.java`.
- Test `src/test/java/com/monpai/sailboatmod/route/water/WaterRoutePathfinderTest.java`.
- Test `src/test/java/com/monpai/sailboatmod/route/water/WaterRouteTaskServiceTest.java`.
- Test `src/test/java/com/monpai/sailboatmod/route/water/WaterAutoRouteServiceTest.java`.

## Task 1: Core Result Models And Permission Rules

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/route/water/WaterRouteFailureReason.java`
- Create: `src/main/java/com/monpai/sailboatmod/route/water/WaterRoutePolicy.java`
- Create: `src/main/java/com/monpai/sailboatmod/route/water/WaterRouteResult.java`
- Create: `src/main/java/com/monpai/sailboatmod/route/water/WaterRoutePermissionService.java`
- Test: `src/test/java/com/monpai/sailboatmod/route/water/WaterRoutePermissionServiceTest.java`

- [ ] **Step 1: Write failing permission tests**

Create `WaterRoutePermissionServiceTest` with tests for missing town, missing nation, same nation, allied/trade, neutral/enemy, and missing relation:

```java
package com.monpai.sailboatmod.route.water;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterRoutePermissionServiceTest {
    @Test
    void missingTownFailsBeforeDiplomacy() {
        WaterRoutePermissionService.DockAccess source = dock("", "nation-a");
        WaterRoutePermissionService.DockAccess target = dock("town-b", "nation-a");

        WaterRouteResult<Void> result = WaterRoutePermissionService.evaluate(source, target, (a, b) -> "allied");

        assertEquals(WaterRouteFailureReason.MISSING_TOWN, result.reason());
    }

    @Test
    void missingNationFails() {
        WaterRouteResult<Void> result = WaterRoutePermissionService.evaluate(
                dock("town-a", ""), dock("town-b", "nation-b"), (a, b) -> "trade");

        assertEquals(WaterRouteFailureReason.MISSING_NATION, result.reason());
    }

    @Test
    void sameNationSucceeds() {
        WaterRouteResult<Void> result = WaterRoutePermissionService.evaluate(
                dock("town-a", "nation-a"), dock("town-b", "nation-a"), (a, b) -> "");

        assertTrue(result.successful());
    }

    @Test
    void tradeOrAlliedNationSucceeds() {
        assertTrue(WaterRoutePermissionService.evaluate(
                dock("town-a", "nation-a"), dock("town-b", "nation-b"), relation(Map.of("nation-a|nation-b", "trade"))).successful());
        assertTrue(WaterRoutePermissionService.evaluate(
                dock("town-a", "nation-a"), dock("town-b", "nation-b"), relation(Map.of("nation-a|nation-b", "allied"))).successful());
    }

    @Test
    void neutralEnemyOrMissingRelationFails() {
        assertEquals(WaterRouteFailureReason.NO_PERMISSION, WaterRoutePermissionService.evaluate(
                dock("town-a", "nation-a"), dock("town-b", "nation-b"), relation(Map.of("nation-a|nation-b", "neutral"))).reason());
        assertEquals(WaterRouteFailureReason.NO_PERMISSION, WaterRoutePermissionService.evaluate(
                dock("town-a", "nation-a"), dock("town-b", "nation-b"), relation(Map.of("nation-a|nation-b", "enemy"))).reason());
        assertEquals(WaterRouteFailureReason.NO_PERMISSION, WaterRoutePermissionService.evaluate(
                dock("town-a", "nation-a"), dock("town-b", "nation-b"), relation(Map.of())).reason());
    }

    private static WaterRoutePermissionService.DockAccess dock(String townId, String nationId) {
        return new WaterRoutePermissionService.DockAccess(townId, nationId);
    }

    private static WaterRoutePermissionService.RelationLookup relation(Map<String, String> values) {
        return (left, right) -> values.getOrDefault(left + "|" + right, values.getOrDefault(right + "|" + left, ""));
    }
}
```

- [ ] **Step 2: Run failing test**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.route.water.WaterRoutePermissionServiceTest"
```

Expected: compilation fails because water route classes do not exist.

- [ ] **Step 3: Implement core model classes**

Create the enum:

```java
package com.monpai.sailboatmod.route.water;

public enum WaterRouteFailureReason {
    NONE,
    MISSING_SOURCE_DOCK,
    MISSING_TARGET_DOCK,
    INVALID_TERMINAL_KIND,
    MISSING_TOWN,
    MISSING_NATION,
    NO_PERMISSION,
    NO_SOURCE_BERTH,
    NO_TARGET_BERTH,
    RANGE_EXCEEDED,
    NO_WATER_PATH,
    CHUNK_BUDGET_EXCEEDED,
    NODE_BUDGET_EXCEEDED,
    TIMEOUT,
    ALREADY_PENDING
}
```

Create `WaterRouteResult`:

```java
package com.monpai.sailboatmod.route.water;

public record WaterRouteResult<T>(boolean successful, T value, WaterRouteFailureReason reason) {
    public static <T> WaterRouteResult<T> success(T value) {
        return new WaterRouteResult<>(true, value, WaterRouteFailureReason.NONE);
    }

    public static <T> WaterRouteResult<T> failure(WaterRouteFailureReason reason) {
        return new WaterRouteResult<>(false, null, reason == null ? WaterRouteFailureReason.NO_WATER_PATH : reason);
    }
}
```

Create `WaterRoutePolicy`:

```java
package com.monpai.sailboatmod.route.water;

public record WaterRoutePolicy(int stepSize,
                               int berthSearchStep,
                               int boatHalfWidth,
                               int clearanceHeight,
                               int maxSearchRadius,
                               int maxExpandedNodes,
                               int maxChunkLoads,
                               int nodesPerTick,
                               int chunkLoadsPerTick,
                               int timeoutTicks) {
    public static WaterRoutePolicy defaults() {
        return new WaterRoutePolicy(8, 2, 2, 3, 4096, 20000, 1024, 256, 8, 20 * 60);
    }
}
```

- [ ] **Step 4: Implement pure permission service**

```java
package com.monpai.sailboatmod.route.water;

import java.util.Locale;

public final class WaterRoutePermissionService {
    private WaterRoutePermissionService() {
    }

    public static WaterRouteResult<Void> evaluate(DockAccess source, DockAccess target, RelationLookup relations) {
        if (source == null || target == null) {
            return WaterRouteResult.failure(WaterRouteFailureReason.MISSING_SOURCE_DOCK);
        }
        if (source.townId().isBlank() || target.townId().isBlank()) {
            return WaterRouteResult.failure(WaterRouteFailureReason.MISSING_TOWN);
        }
        if (source.nationId().isBlank() || target.nationId().isBlank()) {
            return WaterRouteResult.failure(WaterRouteFailureReason.MISSING_NATION);
        }
        if (source.nationId().equals(target.nationId())) {
            return WaterRouteResult.success(null);
        }
        String relation = relations == null ? "" : relations.statusId(source.nationId(), target.nationId());
        relation = relation == null ? "" : relation.trim().toLowerCase(Locale.ROOT);
        if ("allied".equals(relation) || "trade".equals(relation)) {
            return WaterRouteResult.success(null);
        }
        return WaterRouteResult.failure(WaterRouteFailureReason.NO_PERMISSION);
    }

    public record DockAccess(String townId, String nationId) {
        public DockAccess {
            townId = townId == null ? "" : townId.trim();
            nationId = nationId == null ? "" : nationId.trim();
        }
    }

    @FunctionalInterface
    public interface RelationLookup {
        String statusId(String leftNationId, String rightNationId);
    }
}
```

- [ ] **Step 5: Verify and commit**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.route.water.WaterRoutePermissionServiceTest"
git add src/main/java/com/monpai/sailboatmod/route/water src/test/java/com/monpai/sailboatmod/route/water/WaterRoutePermissionServiceTest.java
git commit -m "Add water route permission rules"
```

Expected: selected test passes.

## Task 2: Dock Berth Resolution

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/route/water/DockBerthResolver.java`
- Test: `src/test/java/com/monpai/sailboatmod/route/water/DockBerthResolverTest.java`

- [ ] **Step 1: Write berth tests**

Test with a pure grid world. The dock core can be on shore; a valid berth inside the configured zone should still be found.

```java
package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockBerthResolverTest {
    @Test
    void shoreDockFindsWaterInsideZone() {
        TestBerthWorld world = new TestBerthWorld().waterRect(4, -4, 10, 4);
        DockBerthResolver.DockZone zone = new DockBerthResolver.DockZone(new BlockPos(0, 64, 0), -6, 10, -4, 4);

        WaterRouteResult<DockBerthResolver.DockBerth> result =
                DockBerthResolver.resolve(world, zone, WaterRoutePolicy.defaults());

        assertTrue(result.successful());
        assertTrue(result.value().pos().x >= 4.0D);
        assertTrue(zone.contains(result.value().pos()));
    }

    @Test
    void noWaterFails() {
        WaterRouteResult<DockBerthResolver.DockBerth> result = DockBerthResolver.resolve(
                new TestBerthWorld(), new DockBerthResolver.DockZone(new BlockPos(0, 64, 0), -4, 4, -4, 4), WaterRoutePolicy.defaults());

        assertEquals(WaterRouteFailureReason.NO_SOURCE_BERTH, result.reason());
    }

    @Test
    void berthAvoidsDockCore() {
        TestBerthWorld world = new TestBerthWorld().waterRect(-6, -6, 6, 6);
        WaterRouteResult<DockBerthResolver.DockBerth> result = DockBerthResolver.resolve(
                world, new DockBerthResolver.DockZone(new BlockPos(0, 64, 0), -6, 6, -6, 6), WaterRoutePolicy.defaults());

        assertTrue(result.successful());
        assertFalse(result.value().pos().distanceToSqr(new Vec3(0.5D, 64.0D, 0.5D)) < DockBerthResolver.DOCK_CORE_EXCLUSION_RADIUS_SQ);
    }

    private static final class TestBerthWorld implements DockBerthResolver.BerthWorld {
        private final Set<Long> water = new HashSet<>();

        TestBerthWorld waterRect(int minX, int minZ, int maxX, int maxZ) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    water.add((((long) x) << 32) ^ (z & 0xffffffffL));
                }
            }
            return this;
        }

        @Override
        public boolean isBerthWater(int x, int z, WaterRoutePolicy policy) {
            return water.contains((((long) x) << 32) ^ (z & 0xffffffffL));
        }

        @Override
        public int waterSurfaceY(int x, int z) {
            return 64;
        }
    }
}
```

- [ ] **Step 2: Run failing berth tests**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.route.water.DockBerthResolverTest"
```

Expected: compile fails because `DockBerthResolver` does not exist.

- [ ] **Step 3: Implement berth resolver**

Implement a pure resolver with server-friendly records:

```java
package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class DockBerthResolver {
    public static final double DOCK_CORE_EXCLUSION_RADIUS = 3.5D;
    public static final double DOCK_CORE_EXCLUSION_RADIUS_SQ = DOCK_CORE_EXCLUSION_RADIUS * DOCK_CORE_EXCLUSION_RADIUS;

    private DockBerthResolver() {
    }

    public static WaterRouteResult<DockBerth> resolve(BerthWorld world, DockZone zone, WaterRoutePolicy policy) {
        if (world == null || zone == null) {
            return WaterRouteResult.failure(WaterRouteFailureReason.NO_SOURCE_BERTH);
        }
        WaterRoutePolicy effective = policy == null ? WaterRoutePolicy.defaults() : policy;
        Vec3 dockCenter = Vec3.atCenterOf(zone.dockPos());
        DockBerth best = null;
        double bestScore = Double.MAX_VALUE;
        int step = Math.max(1, effective.berthSearchStep());
        for (int x = zone.minX(); x <= zone.maxX(); x += step) {
            for (int z = zone.minZ(); z <= zone.maxZ(); z += step) {
                int wx = zone.dockPos().getX() + x;
                int wz = zone.dockPos().getZ() + z;
                if (!world.isBerthWater(wx, wz, effective)) {
                    continue;
                }
                Vec3 pos = new Vec3(wx + 0.5D, world.waterSurfaceY(wx, wz), wz + 0.5D);
                if (!zone.contains(pos) || pos.distanceToSqr(dockCenter) < DOCK_CORE_EXCLUSION_RADIUS_SQ) {
                    continue;
                }
                double score = Math.abs(pos.distanceTo(dockCenter) - 8.0D);
                if (score < bestScore) {
                    bestScore = score;
                    best = new DockBerth(pos);
                }
            }
        }
        return best == null
                ? WaterRouteResult.failure(WaterRouteFailureReason.NO_SOURCE_BERTH)
                : WaterRouteResult.success(best);
    }

    public record DockBerth(Vec3 pos) {
    }

    public record DockZone(BlockPos dockPos, int minX, int maxX, int minZ, int maxZ) {
        public boolean contains(Vec3 point) {
            double dx = point.x - (dockPos.getX() + 0.5D);
            double dz = point.z - (dockPos.getZ() + 0.5D);
            return dx >= minX && dx <= maxX && dz >= minZ && dz <= maxZ;
        }
    }

    public interface BerthWorld {
        boolean isBerthWater(int x, int z, WaterRoutePolicy policy);

        int waterSurfaceY(int x, int z);
    }
}
```

- [ ] **Step 4: Verify and commit**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.route.water.DockBerthResolverTest"
git add src/main/java/com/monpai/sailboatmod/route/water/DockBerthResolver.java src/test/java/com/monpai/sailboatmod/route/water/DockBerthResolverTest.java
git commit -m "Resolve water berths inside dock zones"
```

Expected: selected tests pass.

## Task 3: Budgeted Water Pathfinder

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/route/water/WaterColumn.java`
- Create: `src/main/java/com/monpai/sailboatmod/route/water/WaterRouteWorld.java`
- Create: `src/main/java/com/monpai/sailboatmod/route/water/WaterRoutePathfinder.java`
- Test: `src/test/java/com/monpai/sailboatmod/route/water/WaterRoutePathfinderTest.java`

- [ ] **Step 1: Write pathfinder tests**

```java
package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterRoutePathfinderTest {
    @Test
    void continuousWaterFindsRoute() {
        TestWaterWorld world = new TestWaterWorld().waterRect(0, -8, 64, 8);
        WaterRoutePathfinder pathfinder = new WaterRoutePathfinder(world, new BlockPos(0, 64, 0), new BlockPos(64, 64, 0), testPolicy());

        WaterRoutePathfinder.Status status = WaterRoutePathfinder.Status.RUNNING;
        while (status == WaterRoutePathfinder.Status.RUNNING) {
            status = pathfinder.step(64, 64);
        }

        assertEquals(WaterRoutePathfinder.Status.SUCCESS, status);
        assertFalse(pathfinder.path().isEmpty());
    }

    @Test
    void landBarrierFails() {
        TestWaterWorld world = new TestWaterWorld().waterRect(0, -8, 64, 8).landRect(24, -8, 40, 8);
        WaterRoutePathfinder pathfinder = new WaterRoutePathfinder(world, new BlockPos(0, 64, 0), new BlockPos(64, 64, 0), testPolicy());

        WaterRoutePathfinder.Status status = WaterRoutePathfinder.Status.RUNNING;
        while (status == WaterRoutePathfinder.Status.RUNNING) {
            status = pathfinder.step(64, 64);
        }

        assertEquals(WaterRoutePathfinder.Status.FAILED, status);
        assertEquals(WaterRouteFailureReason.NO_WATER_PATH, pathfinder.failureReason());
    }

    @Test
    void nodeBudgetCanPauseSearch() {
        TestWaterWorld world = new TestWaterWorld().waterRect(0, -16, 96, 16);
        WaterRoutePathfinder pathfinder = new WaterRoutePathfinder(world, new BlockPos(0, 64, 0), new BlockPos(96, 64, 0), testPolicy());

        WaterRoutePathfinder.Status first = pathfinder.step(1, 64);

        assertEquals(WaterRoutePathfinder.Status.RUNNING, first);
        assertTrue(pathfinder.expandedNodes() <= 1);
    }

    private static WaterRoutePolicy testPolicy() {
        return new WaterRoutePolicy(8, 2, 1, 2, 256, 5000, 512, 64, 64, 200);
    }

    private static final class TestWaterWorld implements WaterRouteWorld {
        private final Set<Long> water = new HashSet<>();

        TestWaterWorld waterRect(int minX, int minZ, int maxX, int maxZ) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    water.add(key(x, z));
                }
            }
            return this;
        }

        TestWaterWorld landRect(int minX, int minZ, int maxX, int maxZ) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    water.remove(key(x, z));
                }
            }
            return this;
        }

        @Override
        public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
            return water.contains(key(x, z))
                    ? WaterColumn.passable(new BlockPos(x, 64, z), 0.0D)
                    : WaterColumn.blocked();
        }

        @Override
        public boolean canLoadMoreChunks(int requested) {
            return true;
        }

        @Override
        public int consumedChunkLoads() {
            return 0;
        }

        private static long key(int x, int z) {
            return (((long) x) << 32) ^ (z & 0xffffffffL);
        }
    }
}
```

- [ ] **Step 2: Run failing pathfinder tests**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.route.water.WaterRoutePathfinderTest"
```

Expected: compile fails because pathfinder classes do not exist.

- [ ] **Step 3: Implement `WaterColumn` and `WaterRouteWorld`**

```java
package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;

public record WaterColumn(boolean passable, BlockPos surfacePos, double extraCost) {
    public static WaterColumn passable(BlockPos surfacePos, double extraCost) {
        return new WaterColumn(true, surfacePos, Math.max(0.0D, extraCost));
    }

    public static WaterColumn blocked() {
        return new WaterColumn(false, null, 0.0D);
    }
}
```

```java
package com.monpai.sailboatmod.route.water;

public interface WaterRouteWorld {
    WaterColumn sample(int x, int z, WaterRoutePolicy policy);

    boolean canLoadMoreChunks(int requested);

    int consumedChunkLoads();
}
```

- [ ] **Step 4: Implement budgeted A***

Implement `WaterRoutePathfinder` with:

- constructor `(WaterRouteWorld world, BlockPos start, BlockPos goal, WaterRoutePolicy policy)`;
- `Status step(int maxNodeExpansions, int maxChunkLoads)`;
- `List<BlockPos> path()`;
- `WaterRouteFailureReason failureReason()`;
- `int expandedNodes()`.

The implementation must:

- seed start node from `world.sample(startX, startZ, policy)`;
- expand 8 neighbors at `policy.stepSize()`;
- reject samples where `WaterColumn.passable()` is false;
- stop at `policy.maxExpandedNodes()`;
- stop when distance to goal is <= `policy.stepSize()`;
- reconstruct and simplify the path.

- [ ] **Step 5: Verify and commit**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.route.water.WaterRoutePathfinderTest"
git add src/main/java/com/monpai/sailboatmod/route/water src/test/java/com/monpai/sailboatmod/route/water/WaterRoutePathfinderTest.java
git commit -m "Add budgeted water route pathfinder"
```

Expected: selected tests pass.

## Task 4: Task Queue And Route Application

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/route/water/WaterRouteTask.java`
- Create: `src/main/java/com/monpai/sailboatmod/route/water/WaterRouteTaskService.java`
- Test: `src/test/java/com/monpai/sailboatmod/route/water/WaterRouteTaskServiceTest.java`

- [ ] **Step 1: Write task service tests**

Create tests that submit a task, tick it with a one-node budget, reject duplicate source-target requests, and call a completion callback exactly once.

- [ ] **Step 2: Run failing task tests**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.route.water.WaterRouteTaskServiceTest"
```

Expected: compile fails because task classes do not exist.

- [ ] **Step 3: Implement task records and service**

`WaterRouteTask` should carry:

- source and target dock positions;
- requester UUID/name;
- source and target berth positions;
- created tick;
- `WaterRoutePathfinder`;
- completion callback.

`WaterRouteTaskService` should carry:

- a static/global service instance for server use;
- pure instance methods for tests;
- duplicate key by dimension/source/target;
- `submit(...)` returning `WaterRouteResult<WaterRouteTask>`;
- `tick()` advancing a bounded number of tasks;
- `clear()` for server stop and tests.

- [ ] **Step 4: Verify and commit**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.route.water.WaterRouteTaskServiceTest"
git add src/main/java/com/monpai/sailboatmod/route/water/WaterRouteTask.java src/main/java/com/monpai/sailboatmod/route/water/WaterRouteTaskService.java src/test/java/com/monpai/sailboatmod/route/water/WaterRouteTaskServiceTest.java
git commit -m "Queue water route tasks across ticks"
```

Expected: selected tests pass.

## Task 5: Server Adapter And Auto Route Service

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/route/water/ServerWaterRouteWorld.java`
- Create: `src/main/java/com/monpai/sailboatmod/route/water/WaterAutoRouteService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/route/AutoRouteService.java`
- Test: `src/test/java/com/monpai/sailboatmod/route/water/WaterAutoRouteServiceTest.java`

- [ ] **Step 1: Write service tests around pure seams**

Add tests that:

- candidate check fails when permission fails;
- candidate check fails when berth is missing;
- same-nation berth-ready pair is accepted;
- successful completion builds a `RouteDefinition` with source and target dock names.

- [ ] **Step 2: Run failing service tests**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.route.water.WaterAutoRouteServiceTest"
```

Expected: compile fails until `WaterAutoRouteService` exists.

- [ ] **Step 3: Implement `ServerWaterRouteWorld`**

Use `ServerLevel` to sample water:

- call `level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, true)` only while the per-task chunk budget allows it;
- use `FluidTags.WATER`;
- sample boat footprint around `x/z` using `WaterRoutePolicy.boatHalfWidth()`;
- use level height/water surface scanning near sea level and the sampled column;
- reject insufficient clearance.

- [ ] **Step 4: Implement `WaterAutoRouteService`**

Public methods:

```java
public static WaterRouteResult<Void> canListCandidate(Level level, DockBlockEntity source, DockBlockEntity target);
public static WaterRouteResult<Void> submitAutoRoute(ServerLevel level, DockBlockEntity source, DockBlockEntity target, @Nullable ServerPlayer player);
public static Component messageFor(WaterRouteFailureReason reason);
```

The service must:

- reject post-station mixes for water routes;
- adapt `DockBlockEntity` to `DockAccess`;
- look up diplomacy through `NationSavedData`;
- resolve source and target berths;
- create a `ServerWaterRouteWorld`;
- submit a task to `WaterRouteTaskService`;
- on task success append a route to the source dock and send a player message.

- [ ] **Step 5: Convert `AutoRouteService` to facade**

Change `canCreateAutoRoute` to call `WaterAutoRouteService.canListCandidate(...).successful()` for ordinary docks. Keep `findWaterRoute` as legacy and do not route packets through it.

- [ ] **Step 6: Verify and commit**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.route.water.WaterAutoRouteServiceTest"
.\gradlew.bat compileJava
git add src/main/java/com/monpai/sailboatmod/route/AutoRouteService.java src/main/java/com/monpai/sailboatmod/route/water src/test/java/com/monpai/sailboatmod/route/water/WaterAutoRouteServiceTest.java
git commit -m "Add water auto route service"
```

Expected: selected tests and compile pass.

## Task 6: Packet And Tick Integration

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/RequestAutoRouteDocksPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/CreateAutoRoutePacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/ServerEvents.java`

- [ ] **Step 1: Update target list packet**

For normal ports, replace:

```java
AutoRouteService.canCreateAutoRoute(serverLevel, sourceDock, targetDock)
```

with:

```java
WaterAutoRouteService.canListCandidate(serverLevel, sourceDock, targetDock).successful()
```

Keep post station behavior on `RoadAutoRouteService.canResolveAutoRoute(...)`.

- [ ] **Step 2: Update create packet**

For normal ports, replace synchronous creation:

```java
success = AutoRouteService.createAndSaveAutoRoute(serverLevel, sourceDock, targetDock);
```

with:

```java
WaterRouteResult<Void> result = WaterAutoRouteService.submitAutoRoute(serverLevel, sourceDock, targetDock, player);
success = result.successful();
```

Send a "started" message on success and a reason-specific failure message on failure.

- [ ] **Step 3: Add tick hook**

In `ServerEvents.onServerTick`, inside `server.getAllLevels().forEach(level -> { ... })`, call:

```java
WaterRouteTaskService.global().tick(level);
```

In `onServerStopped`, call:

```java
WaterRouteTaskService.global().clear();
```

- [ ] **Step 4: Verify and commit**

```powershell
.\gradlew.bat compileJava
git add src/main/java/com/monpai/sailboatmod/network/packet/RequestAutoRouteDocksPacket.java src/main/java/com/monpai/sailboatmod/network/packet/CreateAutoRoutePacket.java src/main/java/com/monpai/sailboatmod/ServerEvents.java
git commit -m "Route water auto creation through task queue"
```

Expected: compile passes.

## Task 7: Localization And Regression Sweep

**Files:**
- Modify: `src/main/resources/assets/sailboatmod/lang/en_us.json`
- Modify: `src/main/resources/assets/sailboatmod/lang/zh_cn.json`

- [ ] **Step 1: Add English messages**

Add keys:

```json
"message.sailboatmod.auto_route.water.started": "Water route calculation started.",
"message.sailboatmod.auto_route.water.created": "Created water route to %s (%sm).",
"message.sailboatmod.auto_route.water.failed.missing_town": "Both docks must be bound to towns.",
"message.sailboatmod.auto_route.water.failed.missing_nation": "Both docks must belong to nations.",
"message.sailboatmod.auto_route.water.failed.no_permission": "Water auto-routes require the same nation, an alliance, or a trade relation.",
"message.sailboatmod.auto_route.water.failed.no_source_berth": "The source dock zone has no usable water berth.",
"message.sailboatmod.auto_route.water.failed.no_target_berth": "The target dock zone has no usable water berth.",
"message.sailboatmod.auto_route.water.failed.no_water_path": "No continuous navigable water route was found.",
"message.sailboatmod.auto_route.water.failed.range_exceeded": "The docks are outside the automatic water route range.",
"message.sailboatmod.auto_route.water.failed.chunk_budget_exceeded": "Water route calculation stopped after reaching the chunk loading budget.",
"message.sailboatmod.auto_route.water.failed.node_budget_exceeded": "Water route calculation stopped after reaching the search budget.",
"message.sailboatmod.auto_route.water.failed.timeout": "Water route calculation timed out.",
"message.sailboatmod.auto_route.water.failed.already_pending": "A water route between these docks is already being calculated.",
"message.sailboatmod.auto_route.water.failed.generic": "Water route creation failed."
```

- [ ] **Step 2: Add Chinese messages**

Add matching `zh_cn` messages:

```json
"message.sailboatmod.auto_route.water.started": "水上航线计算已开始。",
"message.sailboatmod.auto_route.water.created": "已创建前往 %s 的水上航线（%sm）。",
"message.sailboatmod.auto_route.water.failed.missing_town": "两个码头都必须绑定城镇。",
"message.sailboatmod.auto_route.water.failed.missing_nation": "两个码头都必须属于国家。",
"message.sailboatmod.auto_route.water.failed.no_permission": "水上自动航线只能在同国家、盟友或贸易国家之间创建。",
"message.sailboatmod.auto_route.water.failed.no_source_berth": "起点码头停泊区没有可用水面泊位。",
"message.sailboatmod.auto_route.water.failed.no_target_berth": "目标码头停泊区没有可用水面泊位。",
"message.sailboatmod.auto_route.water.failed.no_water_path": "没有找到连续可通航水路。",
"message.sailboatmod.auto_route.water.failed.range_exceeded": "两个码头超出自动水路寻路范围。",
"message.sailboatmod.auto_route.water.failed.chunk_budget_exceeded": "水上航线计算达到区块加载预算后停止。",
"message.sailboatmod.auto_route.water.failed.node_budget_exceeded": "水上航线计算达到搜索预算后停止。",
"message.sailboatmod.auto_route.water.failed.timeout": "水上航线计算超时。",
"message.sailboatmod.auto_route.water.failed.already_pending": "这两个码头之间已有水上航线正在计算。",
"message.sailboatmod.auto_route.water.failed.generic": "水上航线创建失败。"
```

- [ ] **Step 3: Run focused tests**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.route.water.*"
```

Expected: all water route tests pass.

- [ ] **Step 4: Run compile**

```powershell
.\gradlew.bat compileJava
```

Expected: compile passes.

- [ ] **Step 5: Run broader route regressions**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.route.*"
```

Expected: route tests pass.

- [ ] **Step 6: Commit localization and final integration**

```powershell
git add src/main/resources/assets/sailboatmod/lang/en_us.json src/main/resources/assets/sailboatmod/lang/zh_cn.json
git commit -m "Add water auto route messages"
```

## Task 8: Final Verification And Summary

**Files:**
- No planned source edits.

- [ ] **Step 1: Run full Java test suite**

```powershell
.\gradlew.bat test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run compile**

```powershell
.\gradlew.bat compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Inspect final diff**

```powershell
git status -sb
git log --oneline -n 8
git diff --stat HEAD~7..HEAD
```

- [ ] **Step 4: Provide user-facing change summary**

Summarize:

- The design spec commit.
- The implementation plan commit.
- Permission rule changes.
- Dock berth changes.
- Water pathfinder changes.
- Task queue and tick integration.
- Packet behavior changes.
- Localization and test results.

## Self-Review

- Spec coverage:
  - Permission/town/nation/diplomacy rules: Task 1 and Task 5.
  - Dock-zone berth handling for shore dock cores: Task 2 and Task 5.
  - Strict water-only pathing and true Y values: Task 3 and Task 5.
  - Tick-budgeted route creation and duplicate pending requests: Task 4 and Task 6.
  - Packet integration and UI-light behavior: Task 6.
  - Failure reasons and localization: Task 7.
  - Compatibility through `AutoRouteService` facade: Task 5.
- Placeholder scan: no incomplete-work markers are intentionally present.
- Type consistency:
  - `WaterRouteFailureReason`, `WaterRouteResult`, `WaterRoutePolicy`, `DockBerthResolver.DockBerth`, and `WaterRouteTaskService.global()` names are used consistently across tasks.
  - Packet integration refers only to services created in earlier tasks.
