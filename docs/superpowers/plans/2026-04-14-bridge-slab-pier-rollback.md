# Bridge Slab Pier Rollback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace stair-based road and bridge slopes with slab/full-block transitions, make bridge spans hollow between discrete piers, and ensure planned piers rollback cleanly.

**Architecture:** Keep the existing route-first, corridor-second pipeline. Update geometry generation in `RoadGeometryPlanner`, support index selection in `RoadCorridorPlanner`, and runtime placement/rollback ownership in `StructureConstructionManager` so bridge supports come only from planned pier columns instead of runtime flooding.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, JUnit 5, Gradle

---

## File Map

- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`
  - Remove stair output from slope generation and emit slab/full-block transitions only.
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`
  - Enforce short-span no-pier logic and long-span discrete-pier spacing.
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
  - Stop flooding bridge foundations under every deck block.
  - Ensure only planned support columns become piers and are included in rollback ownership.
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`
  - Add bridge plan tests for no stairs, short-bridge no-pier, long-bridge discrete piers, and hollow spans.
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/RoadLifecycleServiceTest.java`
  - Add rollback tracking coverage for planned bridge piers.

### Task 1: Lock Slope Output To Slabs And Full Blocks

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`

- [ ] **Step 1: Write the failing tests for land and bridge slopes**

```java
@Test
void elevatedWaterCrossingDoesNotEmitStairsAfterSlabTransitionRewrite() {
    TestServerLevel level = allocate(TestServerLevel.class);
    level.blockStates = new HashMap<>();
    level.surfaceHeights = new HashMap<>();
    level.biome = Holder.direct(allocate(Biome.class));

    setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
    setSurfaceColumn(level, 4, 0, 64, Blocks.DIRT.defaultBlockState());
    for (int x = 1; x <= 3; x++) {
        setSurfaceColumn(level, x, 0, 63, Blocks.WATER.defaultBlockState());
        level.blockStates.put(new BlockPos(x, 62, 0).asLong(), Blocks.STONE.defaultBlockState());
    }

    RoadPlacementPlan plan = invokeCreateRoadPlacementPlan(
            level,
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(1, 67, 0),
                    new BlockPos(2, 67, 0),
                    new BlockPos(3, 67, 0),
                    new BlockPos(4, 64, 0)
            )
    );

    assertTrue(plan.ghostBlocks().stream().noneMatch(block -> block.state().is(Blocks.STONE_BRICK_STAIRS)),
            () -> plan.ghostBlocks().toString());
    assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.STONE_BRICK_SLAB)));
}

@Test
void testRoadGeometryPlannerLandSlopeUsesNoStairs() {
    RoadPlacementPlan plan = StructureConstructionManager.createRoadPlacementPlanForTest(
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(1, 65, 0),
                    new BlockPos(2, 66, 0),
                    new BlockPos(3, 66, 0)
            ),
            List.of(),
            List.of()
    );

    assertTrue(plan.ghostBlocks().stream().noneMatch(block ->
            block.state().is(Blocks.STONE_BRICK_STAIRS)
                    || block.state().is(Blocks.SMOOTH_SANDSTONE_STAIRS)
                    || block.state().is(Blocks.MUD_BRICK_STAIRS)
                    || block.state().is(Blocks.SPRUCE_STAIRS)));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest"`

Expected: FAIL because the current bridge and slope generation still emits `stairs`.

- [ ] **Step 3: Write the minimal implementation in `RoadGeometryPlanner`**

```java
private static BlockState resolveState(List<BlockPos> path,
                                       int[] placementHeights,
                                       int index,
                                       BlockPos currentPos,
                                       boolean stairSegment,
                                       Function<BlockPos, BlockState> blockStateSupplier) {
    BlockState state = Objects.requireNonNull(blockStateSupplier.apply(currentPos), "blockStateSupplier returned null for pos " + currentPos);
    if (!stairSegment || !isWithinStairTravelBand(currentPos.getX(), currentPos.getZ(), path)) {
        return state;
    }
    if (isAmbiguousDiagonalClimb(path, index)) {
        return state;
    }
    return slabTransitionState(state, currentPos.getX(), currentPos.getZ(), currentPos.getY(), path, placementHeights);
}

private static BlockState slabTransitionState(BlockState state,
                                              int x,
                                              int z,
                                              int currentY,
                                              List<BlockPos> path,
                                              int[] placementHeights) {
    if (!shouldUseSlabTransition(x, z, currentY, path, placementHeights)) {
        return fullBlockStateForFamily(state);
    }
    return slabStateForFamily(state);
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest"`

Expected: PASS with no stair-based regression failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java
git commit -m "Replace road slope stairs with slab transitions"
```

### Task 2: Make Short Bridges Pier-Free And Long Bridges Use Discrete Piers

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`

- [ ] **Step 1: Write the failing bridge support placement tests**

```java
@Test
void shortWaterBridgeDoesNotCreatePierSupportColumns() {
    RoadPlacementPlan plan = StructureConstructionManager.createRoadPlacementPlanForTest(
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(1, 67, 0),
                    new BlockPos(2, 67, 0),
                    new BlockPos(3, 67, 0),
                    new BlockPos(4, 67, 0),
                    new BlockPos(5, 67, 0),
                    new BlockPos(6, 64, 0)
            ),
            List.of(new RoadPlacementPlan.BridgeRange(1, 5)),
            List.of()
    );

    assertTrue(plan.corridorPlan().slices().stream().allMatch(slice -> slice.supportPositions().isEmpty()),
            () -> plan.corridorPlan().slices().toString());
}

@Test
void longWaterBridgeUsesDiscretePierAnchorsInsteadOfContinuousSupport() {
    RoadPlacementPlan plan = StructureConstructionManager.createRoadPlacementPlanForTest(
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(1, 68, 0),
                    new BlockPos(2, 68, 0),
                    new BlockPos(3, 68, 0),
                    new BlockPos(4, 68, 0),
                    new BlockPos(5, 68, 0),
                    new BlockPos(6, 68, 0),
                    new BlockPos(7, 68, 0),
                    new BlockPos(8, 68, 0),
                    new BlockPos(9, 64, 0)
            ),
            List.of(new RoadPlacementPlan.BridgeRange(1, 8)),
            List.of()
    );

    List<Integer> supportIndexes = plan.corridorPlan().slices().stream()
            .filter(slice -> !slice.supportPositions().isEmpty())
            .map(RoadCorridorPlan.CorridorSlice::index)
            .toList();

    assertFalse(supportIndexes.isEmpty());
    assertTrue(supportIndexes.size() < 6, supportIndexes.toString());
    assertTrue(supportIndexes.stream().allMatch(index -> index > 1 && index < 8), supportIndexes.toString());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest"`

Expected: FAIL because current support planning still places support columns on short spans and does not enforce the new threshold.

- [ ] **Step 3: Write the minimal implementation in `RoadCorridorPlanner`**

```java
private static final int MIN_BRIDGE_SPAN_FOR_PIERS = 7;
private static final int TARGET_PIER_SPACING = 4;

private static SupportPlacementPlan collectSupportRequiredIndexes(List<RoadPlacementPlan.BridgeRange> bridgeRanges,
                                                                  Set<Integer> bridgeHeadIndexes,
                                                                  Set<Integer> navigableIndexes,
                                                                  int pathSize) {
    Set<Integer> supportIndexes = new HashSet<>();
    if (bridgeRanges == null || bridgeRanges.isEmpty() || pathSize <= 0) {
        return new SupportPlacementPlan(supportIndexes, true);
    }

    for (RoadPlacementPlan.BridgeRange range : bridgeRanges) {
        int start = Math.max(0, range.startIndex());
        int end = Math.min(pathSize - 1, range.endIndex());
        int spanLength = end - start + 1;
        if (spanLength < MIN_BRIDGE_SPAN_FOR_PIERS) {
            continue;
        }

        List<Integer> supportableInterior = new ArrayList<>();
        for (int i = start + 1; i <= end - 1; i++) {
            if (!bridgeHeadIndexes.contains(i) && !navigableIndexes.contains(i)) {
                supportableInterior.add(i);
            }
        }
        supportIndexes.addAll(distributeDiscretePierAnchors(supportableInterior, TARGET_PIER_SPACING));
    }
    return new SupportPlacementPlan(Set.copyOf(supportIndexes), true);
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest"`

Expected: PASS with short bridge support columns removed and long bridge supports remaining discrete.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java
git commit -m "Limit bridge piers to long discrete spans"
```

### Task 3: Stop Runtime Bridge Foundation Flooding

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`

- [ ] **Step 1: Write the failing hollow-span test**

```java
@Test
void longBridgePlanDoesNotFillEveryDeckColumnDownward() {
    TestServerLevel level = allocate(TestServerLevel.class);
    level.blockStates = new HashMap<>();
    level.surfaceHeights = new HashMap<>();
    level.biome = Holder.direct(allocate(Biome.class));

    setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
    setSurfaceColumn(level, 9, 0, 64, Blocks.DIRT.defaultBlockState());
    for (int x = 1; x <= 8; x++) {
        setSurfaceColumn(level, x, 0, 40, Blocks.WATER.defaultBlockState());
        level.blockStates.put(new BlockPos(x, 39, 0).asLong(), Blocks.STONE.defaultBlockState());
    }

    RoadPlacementPlan plan = invokeCreateRoadPlacementPlan(
            level,
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(1, 68, 0),
                    new BlockPos(2, 68, 0),
                    new BlockPos(3, 68, 0),
                    new BlockPos(4, 68, 0),
                    new BlockPos(5, 68, 0),
                    new BlockPos(6, 68, 0),
                    new BlockPos(7, 68, 0),
                    new BlockPos(8, 68, 0),
                    new BlockPos(9, 64, 0)
            )
    );

    long supportedColumns = plan.ghostBlocks().stream()
            .filter(block -> block.state().is(Blocks.STONE_BRICKS) || block.state().is(Blocks.COBBLESTONE_WALL))
            .map(block -> BlockPos.asLong(block.pos().getX(), 0, block.pos().getZ()))
            .distinct()
            .count();

    assertTrue(supportedColumns < 9, () -> plan.ghostBlocks().toString());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest"`

Expected: FAIL because bridge support columns still expand too broadly and runtime placement still assumes bridge-wide stabilization.

- [ ] **Step 3: Write the minimal implementation in `StructureConstructionManager`**

```java
private static void stabilizeRoadFoundation(ServerLevel level, BlockPos pos, RoadPlacementStyle style) {
    if (level == null || pos == null || style == null) {
        return;
    }
    if (style.bridge()) {
        return;
    }
    fillRoadFoundation(level, pos, style.support(), ROAD_FOUNDATION_DEPTH);
}

private static void appendCorridorSupportGhosts(LinkedHashMap<Long, RoadGeometryPlanner.GhostRoadBlock> ghostBlocks,
                                                List<BlockPos> positions,
                                                BlockState supportState) {
    if (ghostBlocks == null || positions == null || positions.isEmpty() || supportState == null) {
        return;
    }
    for (BlockPos pos : positions) {
        appendGhost(ghostBlocks, pos, supportState);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest"`

Expected: PASS with hollow spans between discrete support columns.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java
git commit -m "Keep bridge spans hollow between planned piers"
```

### Task 4: Make Bridge Piers Rollback-Safe

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/RoadLifecycleServiceTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`

- [ ] **Step 1: Write the failing rollback tracking test**

```java
@Test
void rollbackTrackingIncludesPlannedBridgePierBlocks() {
    RoadPlacementPlan plan = StructureConstructionManager.createRoadPlacementPlanForTest(
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(1, 68, 0),
                    new BlockPos(2, 68, 0),
                    new BlockPos(3, 68, 0),
                    new BlockPos(4, 68, 0),
                    new BlockPos(5, 68, 0),
                    new BlockPos(6, 68, 0),
                    new BlockPos(7, 68, 0),
                    new BlockPos(8, 68, 0),
                    new BlockPos(9, 64, 0)
            ),
            List.of(new RoadPlacementPlan.BridgeRange(1, 8)),
            List.of()
    );

    List<BlockPos> tracked = invokeRoadRollbackTrackedPositions(null, plan);
    List<BlockPos> pierBlocks = plan.corridorPlan().slices().stream()
            .flatMap(slice -> slice.supportPositions().stream())
            .toList();

    assertFalse(pierBlocks.isEmpty());
    assertTrue(tracked.containsAll(pierBlocks), () -> tracked.toString());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadLifecycleServiceTest"`

Expected: FAIL if planned support columns are not fully preserved through owned-block and rollback tracking.

- [ ] **Step 3: Write the minimal implementation in `StructureConstructionManager`**

```java
private static List<BlockPos> roadOwnedBlocks(ServerLevel level, RoadPlacementPlan plan) {
    LinkedHashSet<BlockPos> resolved = new LinkedHashSet<>();
    if (plan == null) {
        return List.of();
    }
    if (!plan.ownedBlocks().isEmpty()) {
        resolved.addAll(plan.ownedBlocks());
    }
    if (isUsableCorridorPlan(plan.corridorPlan())) {
        for (RoadCorridorPlan.CorridorSlice slice : plan.corridorPlan().slices()) {
            resolved.addAll(slice.supportPositions());
        }
    }
    return resolved.isEmpty() ? List.of() : List.copyOf(resolved);
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadLifecycleServiceTest"`

Expected: PASS with planned bridge piers included in rollback tracking and restoration order.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/nation/service/RoadLifecycleServiceTest.java
git commit -m "Track bridge piers in road rollback state"
```

### Task 5: Run Focused Verification And Build A Jar

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/RoadLifecycleServiceTest.java`

- [ ] **Step 1: Run focused regression suites**

Run:

```bash
.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest" --tests "com.monpai.sailboatmod.nation.service.RoadLifecycleServiceTest" --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest" --tests "com.monpai.sailboatmod.nation.service.SegmentedRoadPathOrchestratorTest" --rerun-tasks
```

Expected: PASS for the bridge shape, rollback, and route integration regressions.

- [ ] **Step 2: Run full build**

Run:

```bash
.\gradlew.bat build
```

Expected: `BUILD SUCCESSFUL` and updated jars in `build/libs/`.

- [ ] **Step 3: Commit the integrated bridge rewrite**

```bash
git add src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java src/test/java/com/monpai/sailboatmod/nation/service/RoadLifecycleServiceTest.java
git commit -m "Refine bridge slabs piers and rollback"
```

## Self-Review

- Spec coverage:
  - slab-only slopes: Task 1
  - short-bridge no-pier threshold: Task 2
  - long-bridge discrete piers at spacing: Task 2
  - hollow spans: Task 3
  - rollback-safe piers: Task 4
  - full verification and jar output: Task 5
- Placeholder scan:
  - No `TODO`, `TBD`, or deferred implementation markers remain.
- Type consistency:
  - All task snippets use existing classes and test helpers already present in the repo: `RoadPlacementPlan`, `RoadCorridorPlan.CorridorSlice`, `invokeCreateRoadPlacementPlan`, and existing Gradle test commands.
