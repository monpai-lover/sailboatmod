# Road Bridge Construction Stability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make road bridge construction stable by consuming each placement step after one attempt, enforcing support->deck->decor ordering, smoothing bridge slopes with slab-first ramps, preserving short arch bridges, and suppressing repeated segmented pathfinding failures.

**Architecture:** Extend the existing road build-step model with explicit phases and persisted attempted-step bookkeeping, then refine bridge geometry and segmented planning in place instead of rewriting the planner. Reuse the current test layout and route/corridor builders so each change lands behind focused regression tests and stays compatible with persisted road jobs.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, Gradle, JUnit 5

---

## File Structure

### Existing Files To Modify

- `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`
  - Add `RoadBuildPhase`, extend `RoadBuildStep`, and expose phase-aware build-step creation.
- `src/main/java/com/monpai/sailboatmod/construction/RoadPlacementPlan.java`
  - Preserve phase-aware build steps when copying plans.
- `src/main/java/com/monpai/sailboatmod/construction/RoadBridgePlanner.java`
  - Tighten short-arch selection and emit gentler approach/main-span deck segments.
- `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`
  - Keep support columns tied to explicit pier nodes and ensure approach slices match the lower-slope deck profile.
- `src/main/java/com/monpai/sailboatmod/nation/data/ConstructionRuntimeSavedData.java`
  - Persist attempted-step state with backward-compatible load logic.
- `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
  - Consume attempted steps once, phase-sort build steps, resume from attempted-step persistence, and keep rollback exact.
- `src/main/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestrator.java`
  - Add per-pass failed-segment suppression and bounded subdivision tracking.
- `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
  - Thread a route-pass cache/context through column sampling and expose structured search-exhausted failures.
- `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
  - Reuse the planning-pass context, prefer anchor-driven segmentation, and map planner failures correctly.
- `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningFailureReason.java`
  - Add a dedicated search-exhausted failure code.

### New Files To Create

- `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningPassContext.java`
  - Hold per-plan terrain sampling cache, coarse-prewarm state, and failed-segment keys.

### Existing Tests To Modify

- `src/test/java/com/monpai/sailboatmod/construction/BuilderHammerSupportTest.java`
- `src/test/java/com/monpai/sailboatmod/construction/RoadBridgePlannerTest.java`
- `src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerSlopeTest.java`
- `src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java`
- `src/test/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestratorTest.java`
- `src/test/java/com/monpai/sailboatmod/nation/service/RoadTerrainSamplingCacheTest.java`
- `src/test/java/com/monpai/sailboatmod/nation/service/RoadPathfinderTest.java`
- `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`

## Task 1: Add Phase-Aware Road Build Steps

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadPlacementPlan.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- Test: `src/test/java/com/monpai/sailboatmod/construction/BuilderHammerSupportTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void buildStepsSortSupportsBeforeDeckBeforeDecor() {
    List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks = List.of(
            new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 66, 0), Blocks.LANTERN.defaultBlockState()),
            new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 64, 0), Blocks.STONE_BRICKS.defaultBlockState()),
            new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 65, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())
    );

    List<RoadGeometryPlanner.RoadBuildStep> buildSteps = invokeToBuildSteps(ghostBlocks);

    assertEquals(
            List.of(
                    RoadGeometryPlanner.RoadBuildPhase.SUPPORT,
                    RoadGeometryPlanner.RoadBuildPhase.DECK,
                    RoadGeometryPlanner.RoadBuildPhase.DECOR
            ),
            buildSteps.stream().map(RoadGeometryPlanner.RoadBuildStep::phase).toList()
    );
    assertEquals(
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(0, 65, 0),
                    new BlockPos(0, 66, 0)
            ),
            buildSteps.stream().map(RoadGeometryPlanner.RoadBuildStep::pos).toList()
    );
}

@SuppressWarnings("unchecked")
private static List<RoadGeometryPlanner.RoadBuildStep> invokeToBuildSteps(List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks) {
    try {
        Method method = StructureConstructionManager.class.getDeclaredMethod("toBuildSteps", List.class);
        method.setAccessible(true);
        return (List<RoadGeometryPlanner.RoadBuildStep>) method.invoke(null, ghostBlocks);
    } catch (ReflectiveOperationException e) {
        throw new AssertionError(e);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat "-Dnet.minecraftforge.gradle.check.certs=false" test --tests "com.monpai.sailboatmod.construction.BuilderHammerSupportTest.buildStepsSortSupportsBeforeDeckBeforeDecor"`

Expected: FAIL because `RoadBuildStep` does not expose `phase()` and `toBuildSteps(...)` preserves insertion order instead of support/deck/decor ordering.

- [ ] **Step 3: Write minimal implementation**

```java
public final class RoadGeometryPlanner {
    public enum RoadBuildPhase {
        SUPPORT,
        DECK,
        DECOR
    }

    public record RoadBuildStep(int order, BlockPos pos, BlockState state, RoadBuildPhase phase) {
        public RoadBuildStep(int order, BlockPos pos, BlockState state) {
            this(order, pos, state, classifyPhase(state));
        }
    }

    static RoadBuildPhase classifyPhase(BlockState state) {
        if (state == null) return RoadBuildPhase.DECK;
        if (state.is(Blocks.STONE_BRICKS) || state.is(Blocks.SPRUCE_FENCE)) return RoadBuildPhase.SUPPORT;
        if (state.is(Blocks.COBBLESTONE_WALL) || state.is(Blocks.LANTERN)) return RoadBuildPhase.DECOR;
        return RoadBuildPhase.DECK;
    }
}
```

```java
private static List<RoadGeometryPlanner.RoadBuildStep> toBuildSteps(List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks) {
    List<RoadGeometryPlanner.RoadBuildStep> phased = ghostBlocks.stream()
            .map(block -> new RoadGeometryPlanner.RoadBuildStep(0, block.pos(), block.state()))
            .sorted(Comparator
                    .comparing((RoadGeometryPlanner.RoadBuildStep step) -> step.phase())
                    .thenComparingInt(step -> step.pos().getY())
                    .thenComparingInt(step -> step.pos().getX())
                    .thenComparingInt(step -> step.pos().getZ()))
            .toList();

    ArrayList<RoadGeometryPlanner.RoadBuildStep> ordered = new ArrayList<>(phased.size());
    for (int i = 0; i < phased.size(); i++) {
        RoadGeometryPlanner.RoadBuildStep step = phased.get(i);
        ordered.add(new RoadGeometryPlanner.RoadBuildStep(i, step.pos(), step.state(), step.phase()));
    }
    return List.copyOf(ordered);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat "-Dnet.minecraftforge.gradle.check.certs=false" test --tests "com.monpai.sailboatmod.construction.BuilderHammerSupportTest.buildStepsSortSupportsBeforeDeckBeforeDecor"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java src/main/java/com/monpai/sailboatmod/construction/RoadPlacementPlan.java src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/construction/BuilderHammerSupportTest.java
git commit -m "Add phased road build step ordering"
```

## Task 2: Consume Placement Steps After One Attempt And Persist Attempted State

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/data/ConstructionRuntimeSavedData.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- Test: `src/test/java/com/monpai/sailboatmod/construction/BuilderHammerSupportTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void attemptedRoadBuildStepIsConsumedEvenWhenWorldStateStillDiffers() {
    RoadGeometryPlanner.RoadBuildStep step = new RoadGeometryPlanner.RoadBuildStep(
            0,
            new BlockPos(0, 65, 0),
            Blocks.STONE_BRICK_SLAB.defaultBlockState(),
            RoadGeometryPlanner.RoadBuildPhase.DECK
    );

    Set<Long> attempted = new LinkedHashSet<>();
    attempted.add(step.pos().asLong());

    List<RoadGeometryPlanner.RoadBuildStep> remaining = invokeRemainingRoadBuildSteps(
            List.of(step),
            Set.of(),
            attempted
    );

    assertTrue(remaining.isEmpty());
}

@Test
void roadJobStateRoundTripsAttemptedStepPositions() {
    ConstructionRuntimeSavedData.RoadJobState state = new ConstructionRuntimeSavedData.RoadJobState(
            "road-a",
            "minecraft:overworld",
            "owner",
            List.of(new BlockPos(0, 64, 0).asLong(), new BlockPos(1, 64, 0).asLong()),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            0,
            false,
            false,
            0,
            false,
            List.of(new BlockPos(3, 65, 3).asLong())
    );

    ConstructionRuntimeSavedData.RoadJobState loaded =
            ConstructionRuntimeSavedData.RoadJobState.load(state.save());

    assertEquals(List.of(new BlockPos(3, 65, 3).asLong()), loaded.attemptedStepPositions());
}

@SuppressWarnings("unchecked")
private static List<RoadGeometryPlanner.RoadBuildStep> invokeRemainingRoadBuildSteps(List<RoadGeometryPlanner.RoadBuildStep> buildSteps,
                                                                                     Set<Long> completed,
                                                                                     Set<Long> attempted) {
    try {
        Method method = StructureConstructionManager.class.getDeclaredMethod(
                "remainingRoadBuildSteps",
                List.class,
                Set.class,
                Set.class
        );
        method.setAccessible(true);
        return (List<RoadGeometryPlanner.RoadBuildStep>) method.invoke(null, buildSteps, completed, attempted);
    } catch (ReflectiveOperationException e) {
        throw new AssertionError(e);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat "-Dnet.minecraftforge.gradle.check.certs=false" test --tests "com.monpai.sailboatmod.construction.BuilderHammerSupportTest.attemptedRoadBuildStepIsConsumedEvenWhenWorldStateStillDiffers" --tests "com.monpai.sailboatmod.construction.BuilderHammerSupportTest.roadJobStateRoundTripsAttemptedStepPositions"`

Expected: FAIL because attempted-step tracking does not exist in either runtime state or remaining-step filtering.

- [ ] **Step 3: Write minimal implementation**

```java
public record RoadJobState(..., boolean removeRoadNetworkOnComplete, List<Long> attemptedStepPositions) {
    private static final int FORMAT_VERSION = 6;

    public RoadJobState {
        attemptedStepPositions = attemptedStepPositions == null ? List.of() : List.copyOf(attemptedStepPositions);
    }

    public CompoundTag save() {
        tag.putLongArray("AttemptedStepPositions",
                attemptedStepPositions.stream().mapToLong(Long::longValue).toArray());
        return tag;
    }

    public static RoadJobState load(CompoundTag tag) {
        return new RoadJobState(...,
                toLongList(tag.getLongArray("AttemptedStepPositions")));
    }
}
```

```java
private record RoadConstructionJob(..., Set<Long> attemptedStepKeys) {}

private static RoadConstructionJob placeRoadBuildSteps(ServerLevel level, RoadConstructionJob job, int stepCount) {
    Set<Long> attempted = new LinkedHashSet<>(job.attemptedStepKeys);
    for (RoadGeometryPlanner.RoadBuildStep step : job.plan.buildSteps()) {
        if (attempted.contains(step.pos().asLong())) {
            continue;
        }
        tryPlaceRoad(level, step.pos(), roadPlacementStyleForState(level, step.pos(), step.state()));
        attempted.add(step.pos().asLong());
        completedCount++;
        if (completedCount >= targetCount) {
            break;
        }
    }
    return new RoadConstructionJob(
            job.level,
            job.roadId,
            job.ownerUuid,
            job.townId,
            job.nationId,
            job.sourceTownName,
            job.targetTownName,
            job.plan,
            job.rollbackStates,
            completedCount,
            Math.max(job.progressSteps, completedCount),
            false,
            0,
            false,
            attempted
    );
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat "-Dnet.minecraftforge.gradle.check.certs=false" test --tests "com.monpai.sailboatmod.construction.BuilderHammerSupportTest.attemptedRoadBuildStepIsConsumedEvenWhenWorldStateStillDiffers" --tests "com.monpai.sailboatmod.construction.BuilderHammerSupportTest.roadJobStateRoundTripsAttemptedStepPositions"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/data/ConstructionRuntimeSavedData.java src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/construction/BuilderHammerSupportTest.java
git commit -m "Persist attempted road build steps"
```

## Task 3: Lower Bridge Slopes And Keep Short Bridges As Pure Arch Spans

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadBridgePlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`
- Test: `src/test/java/com/monpai/sailboatmod/construction/RoadBridgePlannerTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerSlopeTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void shortWaterCrossingRemainsArchSpanWithoutInteriorPiersEvenWithBridgeheads() {
    List<BlockPos> centerPath = List.of(
            new BlockPos(0, 64, 0),
            new BlockPos(1, 64, 0),
            new BlockPos(2, 64, 0),
            new BlockPos(3, 64, 0),
            new BlockPos(4, 64, 0),
            new BlockPos(5, 64, 0)
    );

    RoadBridgePlanner.BridgeSpanPlan plan = RoadBridgePlanner.planBridgeSpanForTest(
            centerPath,
            new RoadPlacementPlan.BridgeRange(1, 4),
            index -> index >= 1 && index <= 4,
            index -> false,
            index -> 61,
            index -> 63,
            index -> true
    );

    assertEquals(RoadBridgePlanner.BridgeMode.ARCH_SPAN, plan.mode());
    assertTrue(plan.nodes().stream().allMatch(node -> node.role() == RoadBridgePlanner.BridgeNodeRole.ABUTMENT));
}

@Test
void pierBridgeApproachProfileExtendsRampLengthBeforeIncreasingSlope() {
    List<BlockPos> centerPath = List.of(
            new BlockPos(0, 64, 0),
            new BlockPos(1, 64, 0),
            new BlockPos(2, 64, 0),
            new BlockPos(3, 64, 0),
            new BlockPos(4, 64, 0),
            new BlockPos(5, 64, 0),
            new BlockPos(6, 64, 0),
            new BlockPos(7, 64, 0)
    );

    int[] heights = RoadGeometryPlanner.buildPlacementHeightProfileFromSpanPlans(
            centerPath,
            List.of(new RoadBridgePlanner.BridgeSpanPlan(
                    1,
                    6,
                    RoadBridgePlanner.BridgeMode.PIER_BRIDGE,
                    List.of(),
                    List.of(
                            new RoadBridgePlanner.BridgeDeckSegment(1, 3, RoadBridgePlanner.BridgeDeckSegmentType.APPROACH_UP, 65, 68),
                            new RoadBridgePlanner.BridgeDeckSegment(3, 4, RoadBridgePlanner.BridgeDeckSegmentType.MAIN_LEVEL, 68, 68),
                            new RoadBridgePlanner.BridgeDeckSegment(4, 6, RoadBridgePlanner.BridgeDeckSegmentType.APPROACH_DOWN, 68, 65)
                    ),
                    68,
                    true,
                    true
            ))
    );

    assertEquals(65, heights[1]);
    assertTrue(heights[2] <= 67);
    assertEquals(68, heights[3]);
    assertTrue(heights[5] >= 66);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat "-Dnet.minecraftforge.gradle.check.certs=false" test --tests "com.monpai.sailboatmod.construction.RoadBridgePlannerTest.shortWaterCrossingRemainsArchSpanWithoutInteriorPiersEvenWithBridgeheads" --tests "com.monpai.sailboatmod.construction.RoadGeometryPlannerSlopeTest.pierBridgeApproachProfileExtendsRampLengthBeforeIncreasingSlope"`

Expected: FAIL because the current short-span threshold and fixed bridge-ramp shaping still allow too-steep profiles and do not explicitly prioritize lower-slope slab-first approaches.

- [ ] **Step 3: Write minimal implementation**

```java
private static final int MAX_ARCH_SPAN_COLUMNS = 8;

private static BridgeSpanPlan buildPierBridge(...) {
    int approachSpan = Math.max(2, Math.min(5, (mainDeckY - startDeckY)));
    deckSegments.add(new BridgeDeckSegment(start, Math.min(end, start + approachSpan), BridgeDeckSegmentType.APPROACH_UP, startDeckY, mainDeckY));
    deckSegments.add(new BridgeDeckSegment(Math.min(end, start + approachSpan), Math.max(start, end - approachSpan), BridgeDeckSegmentType.MAIN_LEVEL, mainDeckY, mainDeckY));
    deckSegments.add(new BridgeDeckSegment(Math.max(start, end - approachSpan), end, BridgeDeckSegmentType.APPROACH_DOWN, mainDeckY, endDeckY));
}
```

```java
private static int[] applyLinearBridgeSegment(int[] placementHeights, RoadBridgePlanner.BridgeDeckSegment segment) {
    int length = Math.max(1, segment.endIndex() - segment.startIndex());
    for (int i = segment.startIndex(); i <= segment.endIndex(); i++) {
        double t = (double) (i - segment.startIndex()) / (double) length;
        int target = (int) Math.round(segment.startDeckY() + ((segment.endDeckY() - segment.startDeckY()) * t));
        placementHeights[i] = target;
    }
    return placementHeights;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat "-Dnet.minecraftforge.gradle.check.certs=false" test --tests "com.monpai.sailboatmod.construction.RoadBridgePlannerTest" --tests "com.monpai.sailboatmod.construction.RoadGeometryPlannerSlopeTest" --tests "com.monpai.sailboatmod.construction.RoadCorridorPlannerTest"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/construction/RoadBridgePlanner.java src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java src/test/java/com/monpai/sailboatmod/construction/RoadBridgePlannerTest.java src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerSlopeTest.java src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java
git commit -m "Smooth bridge ramps and preserve short arch spans"
```

## Task 4: Add Planning-Pass Cache And Suppress Equivalent Failed Segments

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningPassContext.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestrator.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestratorTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/RoadTerrainSamplingCacheTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void failedSegmentIsOnlyPlannedOncePerEquivalentRequestKey() {
    AtomicInteger attempts = new AtomicInteger();

    SegmentedRoadPathOrchestrator.OrchestratedPath result = SegmentedRoadPathOrchestrator.plan(
            new BlockPos(0, 64, 0),
            new BlockPos(64, 64, 0),
            List.of(),
            request -> {
                attempts.incrementAndGet();
                return new SegmentedRoadPathOrchestrator.SegmentPlan(List.of(), SegmentedRoadPathOrchestrator.FailureReason.SEARCH_EXHAUSTED);
            },
            request -> true
    );

    assertFalse(result.success());
    assertTrue(attempts.get() < 5);
}

@Test
void planningPassContextReusesColumnSamplingWithinSinglePlan() {
    TestTerrainLevel level = allocate(TestTerrainLevel.class);
    level.blockStates = new HashMap<>();
    level.surfaceHeights = new HashMap<>();
    level.biome = Holder.direct(allocate(Biome.class));
    level.setSurface(0, 0, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());

    RoadPlanningPassContext context = new RoadPlanningPassContext(level);
    context.sampleColumn(0, 0);
    context.sampleColumn(0, 0);

    assertEquals(1, level.surfaceQueries());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat "-Dnet.minecraftforge.gradle.check.certs=false" test --tests "com.monpai.sailboatmod.nation.service.SegmentedRoadPathOrchestratorTest.failedSegmentIsOnlyPlannedOncePerEquivalentRequestKey" --tests "com.monpai.sailboatmod.nation.service.RoadTerrainSamplingCacheTest.planningPassContextReusesColumnSamplingWithinSinglePlan"`

Expected: FAIL because there is no failed-request suppression and no per-plan cache object.

- [ ] **Step 3: Write minimal implementation**

```java
public final class RoadPlanningPassContext {
    private final RoadTerrainSamplingCache terrainCache;
    private final Set<SegmentKey> failedSegments = new LinkedHashSet<>();

    private record SegmentKey(BlockPos from, BlockPos to) {}

    public RoadPlanningPassContext(Level level) {
        this.terrainCache = new RoadTerrainSamplingCache(level);
    }

    public RoadTerrainSamplingCache.TerrainColumn sampleColumn(int x, int z) {
        return terrainCache.sample(x, z);
    }

    public boolean markFailed(BlockPos from, BlockPos to) {
        return failedSegments.add(new SegmentKey(from.immutable(), to.immutable()));
    }
}
```

```java
private static SegmentAttempt resolveSegment(...) {
    if (!context.markFailed(from, to)) {
        SegmentAttempt failure = new SegmentAttempt(from, to, List.of(), FailureReason.SEARCH_EXHAUSTED);
        failedSegments.add(failure);
        return failure;
    }
    ...
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat "-Dnet.minecraftforge.gradle.check.certs=false" test --tests "com.monpai.sailboatmod.nation.service.SegmentedRoadPathOrchestratorTest" --tests "com.monpai.sailboatmod.nation.service.RoadTerrainSamplingCacheTest"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningPassContext.java src/main/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestrator.java src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java src/test/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestratorTest.java src/test/java/com/monpai/sailboatmod/nation/service/RoadTerrainSamplingCacheTest.java
git commit -m "Cache terrain sampling and suppress repeated failed segments"
```

## Task 5: Surface Structured Search-Exhausted Failures And Run End-To-End Verification

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningFailureReason.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/RoadPathfinderTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void emptyGroundRouteExposesSearchExhaustedFailureReasonWhenPlannerReportsIt() {
    RoadPathfinder.PlannedPathResult result = new RoadPathfinder.PlannedPathResult(
            List.of(),
            RoadPlanningFailureReason.SEARCH_EXHAUSTED
    );

    assertEquals(RoadPlanningFailureReason.SEARCH_EXHAUSTED, result.failureReason());
}

@Test
void manualPlannerMapsStructuredSearchExhaustedFailureToLocalizedMessage() {
    assertEquals(
            "message.sailboatmod.road_planner.failure.search_exhausted",
            ManualRoadPlannerService.manualFailureMessageKeyForTest(RoadPlanningFailureReason.SEARCH_EXHAUSTED)
    );
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat "-Dnet.minecraftforge.gradle.check.certs=false" test --tests "com.monpai.sailboatmod.nation.service.RoadPathfinderTest.emptyGroundRouteExposesSearchExhaustedFailureReasonWhenPlannerReportsIt" --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest.manualPlannerMapsStructuredSearchExhaustedFailureToLocalizedMessage"`

Expected: FAIL because `RoadPlanningFailureReason` has no `SEARCH_EXHAUSTED` value and manual failure mapping falls back to `NO_CONTINUOUS_GROUND_ROUTE`.

- [ ] **Step 3: Write minimal implementation**

```java
public enum RoadPlanningFailureReason {
    NONE(...),
    BLOCKED_BY_CORE_BUFFER(...),
    NO_CONTINUOUS_GROUND_ROUTE(...),
    SEARCH_EXHAUSTED("SEARCH_EXHAUSTED", "message.sailboatmod.road_planner.failure.search_exhausted"),
    SEARCH_BUDGET_EXCEEDED(...),
    TARGET_NOT_ATTACHABLE(...);
}
```

```java
static PlannedPathResult findGroundPathForPlan(...) {
    List<BlockPos> path = findPath(...);
    if (!path.isEmpty()) {
        return new PlannedPathResult(path, RoadPlanningFailureReason.NONE);
    }
    return new PlannedPathResult(List.of(), RoadPlanningFailureReason.SEARCH_EXHAUSTED);
}
```

- [ ] **Step 4: Run tests and targeted regression suites**

Run: `.\gradlew.bat "-Dnet.minecraftforge.gradle.check.certs=false" test --tests "com.monpai.sailboatmod.construction.BuilderHammerSupportTest" --tests "com.monpai.sailboatmod.construction.RoadBridgePlannerTest" --tests "com.monpai.sailboatmod.construction.RoadGeometryPlannerSlopeTest" --tests "com.monpai.sailboatmod.construction.RoadCorridorPlannerTest" --tests "com.monpai.sailboatmod.nation.service.SegmentedRoadPathOrchestratorTest" --tests "com.monpai.sailboatmod.nation.service.RoadTerrainSamplingCacheTest" --tests "com.monpai.sailboatmod.nation.service.RoadPathfinderTest" --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest"`

Expected: PASS

Run: `.\gradlew.bat "-Dnet.minecraftforge.gradle.check.certs=false" build`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningFailureReason.java src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java src/test/java/com/monpai/sailboatmod/nation/service/RoadPathfinderTest.java src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java
git commit -m "Expose search exhausted road planning failures"
```

## Spec Coverage Check

1. One-attempt placement semantics: Task 2
2. Support->deck->decor ordering: Task 1
3. Lower-slope slab-first bridge ramps: Task 3
4. Short arch bridge preservation: Task 3
5. Long bridge pier-node-driven deck generation: Task 3
6. Segmented retry suppression and planning-pass cache: Task 4
7. Search-exhausted diagnostics instead of generic land failure: Task 5

## Placeholder Scan

1. No `TODO`, `TBD`, or deferred implementation markers remain.
2. Every task has concrete files, test names, commands, and commit messages.

## Type Consistency Check

1. `RoadBuildPhase` is introduced in `RoadGeometryPlanner` and used consistently in build-step ordering tests and implementation.
2. `attemptedStepPositions` is introduced in `ConstructionRuntimeSavedData.RoadJobState` and referenced consistently as persisted runtime data.
3. `RoadPlanningPassContext` is the single per-plan cache/suppression holder referenced by planner tasks.
