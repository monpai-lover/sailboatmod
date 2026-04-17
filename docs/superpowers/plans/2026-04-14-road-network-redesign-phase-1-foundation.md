# Road Network Redesign Phase 1 Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first shippable slice of the road-network redesign by adding structured planning diagnostics, terrain sampling/cache primitives, a segmented ground-route skeleton planner, and manual-planner integration that returns explicit failure reasons instead of opaque empty-path failures.

**Architecture:** This phase keeps the current manual road entry flow and construction shell, but inserts a new planning foundation under them. The new foundation introduces request-scoped diagnostics, a cached terrain analysis service, and a ground-only route skeleton planner that tries segmented anchors before bridge-specific logic exists. Bridge span generation, road snapping, and carriage runtime are intentionally deferred into follow-up plans so this phase stays independently testable.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, existing JUnit 5 test suite under `src/test/java`, Gradle via `.\gradlew.bat test` and `.\gradlew.bat compileJava`.

---

## Scope Split

This spec is too broad for one safe implementation batch. This plan intentionally covers only Phase 1:

- request-scoped planning diagnostics
- structured planning failure reasons
- terrain sampling cache and analysis helpers
- segmented ground-route skeleton planning
- manual road planner integration with explicit player-facing failure messages

Follow-up plans are still required for:

- bridge span and pier-anchor subsystem
- post-processing and road snapping
- construction blueprint overhaul and rollback completeness
- road-network persistence upgrade and carriage runtime

## File Structure

### Create

- `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningFailureReason.java`
  - Enum of planner reason codes shared by route solving, manual planner UX, and debug logging.
- `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningRequestContext.java`
  - Immutable request metadata with `requestId`, source/target labels, and attempt counters.
- `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningDebugLogger.java`
  - Small helper that formats structured planning diagnostics for `debug.log`.
- `src/main/java/com/monpai/sailboatmod/nation/service/RoadTerrainSamplingCache.java`
  - Cached terrain-column sampler for surface height, water, slope, and basic attachability signals.
- `src/main/java/com/monpai/sailboatmod/nation/service/RoadTerrainAnalysisService.java`
  - Terrain helper that turns sampled columns into route costs, ground viability, and corridor analysis.
- `src/main/java/com/monpai/sailboatmod/nation/service/RouteSkeleton.java`
  - Minimal phase-1 abstract route result for `GROUND` and `SLOPE` segments.
- `src/main/java/com/monpai/sailboatmod/nation/service/GroundRouteSkeletonPlanner.java`
  - New segmented ground-route planner that consumes terrain analysis and returns a `RouteSkeleton`.
- `src/test/java/com/monpai/sailboatmod/nation/service/RoadTerrainSamplingCacheTest.java`
  - Unit tests for sampling cache reuse and terrain-column interpretation.
- `src/test/java/com/monpai/sailboatmod/nation/service/GroundRouteSkeletonPlannerTest.java`
  - Unit tests for segmented ground-route success and structured failure reasons.
- `src/test/java/com/monpai/sailboatmod/nation/service/RoadPlanningDebugLoggerTest.java`
  - Unit tests for request-scoped diagnostic formatting.

### Modify

- `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
  - Expose a structured ground-route result for callers that need failure reasons.
- `src/main/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestrator.java`
  - Allow external failure reasons from the segment planner to flow through instead of collapsing everything to subdivision exhaustion.
- `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
  - Create request contexts, call the new ground-route planner path, surface localized failure reasons, and emit structured diagnostics.
- `src/test/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestratorTest.java`
  - Extend tests to prove upstream failure reasons survive orchestration.
- `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`
  - Verify explicit failure reasons and 5-block core exclusion behavior remain intact after integration.

## Task 1: Introduce Structured Failure Reasons and Request Diagnostics

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningFailureReason.java`
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningRequestContext.java`
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningDebugLogger.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/RoadPlanningDebugLoggerTest.java`

- [ ] **Step 1: Write the failing tests for failure reason metadata and request-scoped log formatting**

```java
package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlanningDebugLoggerTest {
    @Test
    void failureReasonExposesStableReasonCodeAndTranslationKey() {
        assertEquals("NO_CONTINUOUS_GROUND_ROUTE", RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE.reasonCode());
        assertEquals("message.sailboatmod.road_planner.failure.no_continuous_ground_route",
                RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE.translationKey());
    }

    @Test
    void debugLoggerIncludesRequestIdStageAndReason() {
        RoadPlanningRequestContext context = RoadPlanningRequestContext.create(
                "manual-road",
                "GoatDie Town 2",
                "GoatDie Town",
                new BlockPos(-847, 62, 215),
                new BlockPos(-623, 66, 219)
        );

        String message = RoadPlanningDebugLogger.failure(
                "GroundRouteAttempt",
                context,
                RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE,
                "visited=96001 blockedColumns=0"
        );

        assertTrue(message.contains("requestId=" + context.requestId()));
        assertTrue(message.contains("stage=GroundRouteAttempt"));
        assertTrue(message.contains("reason=NO_CONTINUOUS_GROUND_ROUTE"));
    }
}
```

- [ ] **Step 2: Run the new test to verify it fails**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadPlanningDebugLoggerTest" --rerun-tasks`

Expected: FAIL with compilation errors for missing `RoadPlanningFailureReason`, `RoadPlanningRequestContext`, and `RoadPlanningDebugLogger`.

- [ ] **Step 3: Add the minimal shared failure-reason and request-context types**

```java
package com.monpai.sailboatmod.nation.service;

public enum RoadPlanningFailureReason {
    NONE("NONE", "message.sailboatmod.road_planner.failure.none"),
    BLOCKED_BY_CORE_BUFFER("BLOCKED_BY_CORE_BUFFER", "message.sailboatmod.road_planner.failure.blocked_by_core_buffer"),
    NO_CONTINUOUS_GROUND_ROUTE("NO_CONTINUOUS_GROUND_ROUTE", "message.sailboatmod.road_planner.failure.no_continuous_ground_route"),
    SEARCH_BUDGET_EXCEEDED("SEARCH_BUDGET_EXCEEDED", "message.sailboatmod.road_planner.failure.search_budget_exceeded"),
    TARGET_NOT_ATTACHABLE("TARGET_NOT_ATTACHABLE", "message.sailboatmod.road_planner.failure.target_not_attachable");

    private final String reasonCode;
    private final String translationKey;

    RoadPlanningFailureReason(String reasonCode, String translationKey) {
        this.reasonCode = reasonCode;
        this.translationKey = translationKey;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public String translationKey() {
        return translationKey;
    }
}
```

```java
package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;

import java.util.UUID;

public record RoadPlanningRequestContext(
        String requestId,
        String plannerKind,
        String sourceLabel,
        String targetLabel,
        BlockPos sourcePos,
        BlockPos targetPos) {

    public static RoadPlanningRequestContext create(String plannerKind,
                                                    String sourceLabel,
                                                    String targetLabel,
                                                    BlockPos sourcePos,
                                                    BlockPos targetPos) {
        return new RoadPlanningRequestContext(
                UUID.randomUUID().toString(),
                plannerKind,
                sourceLabel,
                targetLabel,
                sourcePos == null ? BlockPos.ZERO : sourcePos.immutable(),
                targetPos == null ? BlockPos.ZERO : targetPos.immutable()
        );
    }
}
```

```java
package com.monpai.sailboatmod.nation.service;

public final class RoadPlanningDebugLogger {
    private RoadPlanningDebugLogger() {
    }

    public static String failure(String stage,
                                 RoadPlanningRequestContext context,
                                 RoadPlanningFailureReason reason,
                                 String detail) {
        return stage
                + " requestId=" + context.requestId()
                + " planner=" + context.plannerKind()
                + " stage=" + stage
                + " reason=" + reason.reasonCode()
                + " source=" + context.sourcePos()
                + " target=" + context.targetPos()
                + " detail=" + detail;
    }
}
```

- [ ] **Step 4: Re-run the targeted test and verify it passes**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadPlanningDebugLoggerTest"`

Expected: PASS with `2 tests completed`.

- [ ] **Step 5: Commit the diagnostics foundation**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningFailureReason.java src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningRequestContext.java src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningDebugLogger.java src/test/java/com/monpai/sailboatmod/nation/service/RoadPlanningDebugLoggerTest.java
git commit -m "Add structured road planning diagnostics"
```

## Task 2: Add Terrain Sampling Cache and Analysis Primitives

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/RoadTerrainSamplingCache.java`
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/RoadTerrainAnalysisService.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/RoadTerrainSamplingCacheTest.java`

- [ ] **Step 1: Write failing tests for cached sampling and basic ground-route viability signals**

```java
package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadTerrainSamplingCacheTest {
    @Test
    void repeatedColumnSamplingReusesCachedResult() {
        TestTerrainLevel level = new TestTerrainLevel();
        level.setSurface(0, 0, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());

        RoadTerrainSamplingCache cache = new RoadTerrainSamplingCache(level);
        RoadTerrainSamplingCache.TerrainColumn first = cache.sample(0, 0);
        RoadTerrainSamplingCache.TerrainColumn second = cache.sample(0, 0);

        assertEquals(first, second);
        assertEquals(1, level.surfaceQueries());
    }

    @Test
    void waterAdjacentColumnHasHigherGroundPenaltyThanDryColumn() {
        TestTerrainLevel level = new TestTerrainLevel();
        level.setSurface(0, 0, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        level.setSurface(1, 0, 63, Blocks.WATER.defaultBlockState(), Blocks.AIR.defaultBlockState());

        RoadTerrainSamplingCache cache = new RoadTerrainSamplingCache(level);
        RoadTerrainAnalysisService analysis = new RoadTerrainAnalysisService(cache);

        assertTrue(analysis.terrainPenalty(new BlockPos(0, 64, 0)) > 0);
        assertFalse(analysis.requiresBridge(new BlockPos(0, 64, 0)));
    }
}
```

- [ ] **Step 2: Run the terrain sampling tests and verify they fail**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadTerrainSamplingCacheTest" --rerun-tasks`

Expected: FAIL with missing class and method errors for `RoadTerrainSamplingCache`, `RoadTerrainAnalysisService`, and `TestTerrainLevel`.

- [ ] **Step 3: Implement a minimal cached terrain sampler and analysis service**

```java
package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Map;

public final class RoadTerrainSamplingCache {
    private final Level level;
    private final Map<Long, TerrainColumn> columns = new HashMap<>();

    public RoadTerrainSamplingCache(Level level) {
        this.level = level;
    }

    public TerrainColumn sample(int x, int z) {
        long key = BlockPos.asLong(x, 0, z);
        return columns.computeIfAbsent(key, ignored -> {
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            BlockPos surfacePos = new BlockPos(x, surfaceY, z);
            return new TerrainColumn(
                    surfacePos,
                    level.getBlockState(surfacePos),
                    !level.getFluidState(surfacePos).isEmpty()
            );
        });
    }

    public record TerrainColumn(BlockPos surfacePos, net.minecraft.world.level.block.state.BlockState surfaceState, boolean water) {
    }
}
```

```java
package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;

public final class RoadTerrainAnalysisService {
    private final RoadTerrainSamplingCache cache;

    public RoadTerrainAnalysisService(RoadTerrainSamplingCache cache) {
        this.cache = cache;
    }

    public boolean requiresBridge(BlockPos pos) {
        return cache.sample(pos.getX(), pos.getZ()).water();
    }

    public int terrainPenalty(BlockPos pos) {
        int adjacentWater = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && cache.sample(pos.getX() + dx, pos.getZ() + dz).water()) {
                    adjacentWater++;
                }
            }
        }
        return adjacentWater;
    }
}
```

- [ ] **Step 4: Add a tiny fake level for deterministic cache tests and make the tests pass**

```java
static final class TestTerrainLevel extends ManualRoadPlannerServiceTest.TestServerLevel {
    private int surfaceQueries;

    void setSurface(int x, int z, int y, BlockState surface, BlockState above) {
        this.surfaceHeights.put(columnKey(x, z), y);
        this.blockStates.put(new BlockPos(x, y, z).asLong(), surface);
        this.blockStates.put(new BlockPos(x, y + 1, z).asLong(), above);
    }

    int surfaceQueries() {
        return surfaceQueries;
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        surfaceQueries++;
        return this.surfaceHeights.getOrDefault(columnKey(x, z), 0) + 1;
    }
}
```

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadTerrainSamplingCacheTest"`

Expected: PASS with `2 tests completed`.

- [ ] **Step 5: Commit the terrain sampling layer**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/RoadTerrainSamplingCache.java src/main/java/com/monpai/sailboatmod/nation/service/RoadTerrainAnalysisService.java src/test/java/com/monpai/sailboatmod/nation/service/RoadTerrainSamplingCacheTest.java
git commit -m "Add road terrain sampling cache"
```

## Task 3: Preserve Segment Failure Reasons Through Orchestration

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestrator.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestratorTest.java`

- [ ] **Step 1: Write a failing test that proves orchestration returns the planner's real failure reason**

```java
@Test
void failedSegmentKeepsUnderlyingFailureReason() {
    BlockPos start = new BlockPos(0, 64, 0);
    BlockPos end = new BlockPos(30, 64, 0);

    SegmentedRoadPathOrchestrator.OrchestratedPath path = SegmentedRoadPathOrchestrator.plan(
            start,
            end,
            List.of(),
            request -> new SegmentedRoadPathOrchestrator.SegmentPlan(
                    List.of(),
                    SegmentedRoadPathOrchestrator.FailureReason.SEARCH_EXHAUSTED
            ),
            request -> false
    );

    assertEquals(SegmentedRoadPathOrchestrator.FailureReason.SEARCH_EXHAUSTED, path.failureReason());
}
```

- [ ] **Step 2: Run the orchestrator test and confirm it fails because the current code collapses to subdivision exhaustion**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.SegmentedRoadPathOrchestratorTest.failedSegmentKeepsUnderlyingFailureReason" --rerun-tasks`

Expected: FAIL showing `expected: SEARCH_EXHAUSTED` but `was: SUBDIVISION_LIMIT_EXCEEDED`.

- [ ] **Step 3: Update the orchestrator to carry through explicit planner failures before subdivision fallback**

```java
private static SegmentAttempt resolveSegment(BlockPos from,
                                             BlockPos to,
                                             int depth,
                                             SegmentPlanner planner,
                                             Predicate<SegmentRequest> maySubdivide,
                                             List<SegmentAttempt> successfulSegments,
                                             List<SegmentAttempt> failedSegments) {
    SegmentRequest request = new SegmentRequest(from, to, depth);
    SegmentPlan plan = planner.plan(request);
    if (plan != null && plan.success() && isContinuousResolvedPath(from, to, plan.path())) {
        SegmentAttempt success = new SegmentAttempt(from, to, plan.path(), FailureReason.NONE);
        successfulSegments.add(success);
        return success;
    }

    FailureReason plannerFailure = plan == null ? FailureReason.SEARCH_EXHAUSTED : plan.failureReason();
    if (depth >= MAX_SUBDIVISION_DEPTH || !maySubdivide.test(request)) {
        SegmentAttempt failure = new SegmentAttempt(from, to, List.of(), plannerFailure);
        failedSegments.add(failure);
        return failure;
    }

    BlockPos midpoint = midpoint(from, to);
    SegmentAttempt left = resolveSegment(from, midpoint, depth + 1, planner, maySubdivide, successfulSegments, failedSegments);
    if (left.path().isEmpty()) {
        return left;
    }
    SegmentAttempt right = resolveSegment(midpoint, to, depth + 1, planner, maySubdivide, successfulSegments, failedSegments);
    if (right.path().isEmpty()) {
        return right;
    }

    LinkedHashSet<BlockPos> stitched = new LinkedHashSet<>();
    stitched.addAll(left.path());
    stitched.addAll(right.path());
    return new SegmentAttempt(from, to, List.copyOf(stitched), FailureReason.NONE);
}
```

- [ ] **Step 4: Re-run the orchestrator tests and verify both the new regression and existing subdivision cases pass**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.SegmentedRoadPathOrchestratorTest"`

Expected: PASS with all orchestrator tests green.

- [ ] **Step 5: Commit the orchestrator fix**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestrator.java src/test/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestratorTest.java
git commit -m "Preserve segmented route failure reasons"
```

## Task 4: Add a Ground-Only Route Skeleton Planner

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/RouteSkeleton.java`
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/GroundRouteSkeletonPlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/GroundRouteSkeletonPlannerTest.java`

- [ ] **Step 1: Write the failing planner tests for segmented ground success and explicit ground failure**

```java
package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundRouteSkeletonPlannerTest {
    @Test
    void successfulGroundRouteBuildsGroundAndSlopeSkeletonSegments() {
        GroundRouteSkeletonPlanner.PlannedGroundRoute planned = GroundRouteSkeletonPlanner.planForTest(
                new BlockPos(0, 64, 0),
                new BlockPos(6, 66, 0),
                List.of(new BlockPos(3, 65, 0)),
                request -> List.of(
                        request.from(),
                        new BlockPos(3, 65, 0),
                        request.to()
                )
        );

        assertTrue(planned.success());
        assertEquals(RouteSkeleton.SegmentType.GROUND, planned.skeleton().segments().get(0).type());
    }

    @Test
    void failedGroundRouteReturnsExplicitFailureReason() {
        GroundRouteSkeletonPlanner.PlannedGroundRoute planned = GroundRouteSkeletonPlanner.planForTest(
                new BlockPos(0, 64, 0),
                new BlockPos(48, 64, 0),
                List.of(),
                request -> List.of()
        );

        assertEquals(RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE, planned.failureReason());
    }
}
```

- [ ] **Step 2: Run the planner tests and verify they fail**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.GroundRouteSkeletonPlannerTest" --rerun-tasks`

Expected: FAIL with missing `GroundRouteSkeletonPlanner` and `RouteSkeleton`.

- [ ] **Step 3: Implement the minimal route skeleton model and ground planner**

```java
package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;

import java.util.List;

public record RouteSkeleton(List<Segment> segments) {
    public RouteSkeleton {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    public enum SegmentType {
        GROUND,
        SLOPE
    }

    public record Segment(SegmentType type, BlockPos start, BlockPos end) {
    }
}
```

```java
package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class GroundRouteSkeletonPlanner {
    private GroundRouteSkeletonPlanner() {
    }

    public record PlannedGroundRoute(boolean success,
                                     RouteSkeleton skeleton,
                                     List<BlockPos> path,
                                     RoadPlanningFailureReason failureReason) {
    }

    static PlannedGroundRoute planForTest(BlockPos start,
                                          BlockPos end,
                                          List<BlockPos> anchors,
                                          Function<SegmentedRoadPathOrchestrator.SegmentRequest, List<BlockPos>> planner) {
        SegmentedRoadPathOrchestrator.OrchestratedPath path = SegmentedRoadPathOrchestrator.planForTest(
                start,
                end,
                anchors,
                planner,
                request -> request.depth() < 2
        );
        if (!path.success()) {
            return new PlannedGroundRoute(false, new RouteSkeleton(List.of()), List.of(), RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE);
        }
        ArrayList<RouteSkeleton.Segment> segments = new ArrayList<>();
        for (int i = 1; i < path.path().size(); i++) {
            BlockPos previous = path.path().get(i - 1);
            BlockPos current = path.path().get(i);
            RouteSkeleton.SegmentType type = current.getY() == previous.getY()
                    ? RouteSkeleton.SegmentType.GROUND
                    : RouteSkeleton.SegmentType.SLOPE;
            segments.add(new RouteSkeleton.Segment(type, previous, current));
        }
        return new PlannedGroundRoute(true, new RouteSkeleton(segments), path.path(), RoadPlanningFailureReason.NONE);
    }
}
```

- [ ] **Step 4: Extend `RoadPathfinder` with a structured result wrapper used by the new planner and run the tests**

```java
public record PlannedPathResult(List<BlockPos> path, RoadPlanningFailureReason failureReason) {
    public PlannedPathResult {
        path = path == null ? List.of() : List.copyOf(path);
        failureReason = failureReason == null ? RoadPlanningFailureReason.NONE : failureReason;
    }

    public boolean success() {
        return path.size() >= 2;
    }
}

static PlannedPathResult findGroundPathForPlan(Level level,
                                               BlockPos from,
                                               BlockPos to,
                                               Set<Long> blockedColumns,
                                               Set<Long> excludedColumns) {
    List<BlockPos> path = findPath(level, from, to, blockedColumns, excludedColumns, false);
    return path.isEmpty()
            ? new PlannedPathResult(List.of(), RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE)
            : new PlannedPathResult(path, RoadPlanningFailureReason.NONE);
}
```

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.GroundRouteSkeletonPlannerTest"`

Expected: PASS with `2 tests completed`.

- [ ] **Step 5: Commit the ground skeleton planner**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/RouteSkeleton.java src/main/java/com/monpai/sailboatmod/nation/service/GroundRouteSkeletonPlanner.java src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java src/test/java/com/monpai/sailboatmod/nation/service/GroundRouteSkeletonPlannerTest.java
git commit -m "Add segmented ground route skeleton planner"
```

## Task 5: Integrate Manual Planner With Structured Ground Planning

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`

- [ ] **Step 1: Write a failing manual-planner regression test for explicit ground failure messaging**

```java
@Test
void structuredGroundFailureMapsToLocalizedFailureComponent() {
    assertEquals(
            "message.sailboatmod.road_planner.failure.no_continuous_ground_route",
            ManualRoadPlannerService.manualFailureMessageKeyForTest(
                    RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE
            )
    );
}
```

- [ ] **Step 2: Run the manual planner test and verify it fails because the new mapping helper is missing**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest.structuredGroundFailureMapsToLocalizedFailureComponent" --rerun-tasks`

Expected: FAIL with missing `manualFailureMessageKeyForTest`.

- [ ] **Step 3: Wire the manual planner to create request contexts, log failures, and map reasons to player messages**

```java
private static Component planningFailureComponent(RoadPlanningFailureReason reason) {
    RoadPlanningFailureReason safeReason = reason == null ? RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE : reason;
    return Component.translatable(safeReason.translationKey());
}

static String manualFailureMessageKeyForTest(RoadPlanningFailureReason reason) {
    return (reason == null ? RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE : reason).translationKey();
}

private static Component handleGroundPlanningFailure(ItemStack stack,
                                                     ServerPlayer player,
                                                     RoadPlanningRequestContext context,
                                                     RoadPlanningFailureReason reason,
                                                     String detail) {
    LOGGER.warn(RoadPlanningDebugLogger.failure("GroundRouteAttempt", context, reason, detail));
    Component failure = planningFailureComponent(reason);
    writeFailureMessage(stack, failure);
    clearPreviewState(stack);
    sendPreviewClear(player);
    return failure;
}
```

```java
RoadPlanningRequestContext context = RoadPlanningRequestContext.create(
        "manual-road",
        displayTownName(sourceTown),
        displayTownName(targetTown),
        sourcePos,
        targetPos
);
```

- [ ] **Step 4: Re-run the manual planner tests and a compile pass**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest"`

Expected: PASS with the new regression plus existing core-exclusion tests.

Run: `.\gradlew.bat compileJava`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit the manual planner integration**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java
git commit -m "Integrate manual planner with structured ground planning"
```

## Task 6: End-to-End Phase Verification

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/RoadPathfinderTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestratorTest.java`

- [ ] **Step 1: Add one end-to-end regression proving long routes now fail with explicit reasons instead of opaque empty-path behavior**

```java
@Test
void emptyGroundRouteExposesStructuredFailureReason() {
    RoadPathfinder.PlannedPathResult result = RoadPathfinder.findGroundPathForPlan(
            level,
            new BlockPos(-847, 62, 215),
            new BlockPos(-623, 66, 219),
            Set.of(),
            Set.of()
    );

    assertEquals(RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE, result.failureReason());
}
```

- [ ] **Step 2: Run the focused regression bucket**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadPathfinderTest" --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest" --tests "com.monpai.sailboatmod.nation.service.SegmentedRoadPathOrchestratorTest"`

Expected: PASS with all phase-1 planner tests green.

- [ ] **Step 3: Run the full Java test suite**

Run: `.\gradlew.bat test`

Expected: `BUILD SUCCESSFUL` and no regressions in construction or road geometry tests.

- [ ] **Step 4: Run a final compile-only sanity pass**

Run: `.\gradlew.bat compileJava`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit the verified phase**

```bash
git add src/test/java/com/monpai/sailboatmod/nation/service/RoadPathfinderTest.java src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java src/test/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestratorTest.java
git commit -m "Verify road redesign phase 1 foundation"
```

## Follow-Up Plans Required After This Phase

- Bridge subsystem plan:
  - `BridgeAnchorPlan`
  - pier-anchor distribution by elevated span width
  - water-clearance deck height
  - bridge approaches and structural batches
- Post-processing and snapping plan:
  - spline/Bezier smoothing
  - edge merge
  - junction creation
  - existing-road absorption
- Construction and runtime plan:
  - `ConstructionBlueprint`
  - rollback completeness
  - persistent road-network edge registration
  - carriage graph runtime
