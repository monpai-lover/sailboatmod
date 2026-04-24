# Road Ground Path And Construction Runtime Regression Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore successful pure-land road planning for manual and auto roads, and restore automatic road construction progression with hammer acceleration layered on top of the same runtime state.

**Architecture:** The implementation is split into two coordinated tracks inside one plan: shared ground-route resolution and shared road-construction runtime. Each track starts with failing regression tests at the shared entry points, then applies the minimal code changes needed to restore the intended lifecycle without UI-layer workarounds.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, JUnit 5, existing reflective test helpers in `StructureConstructionManager` tests, Gradle.

---

## File Map

### Route Resolution Files

- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
  - Shared ground-route entry point used by manual and auto roads.
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinder.java`
  - Hybrid land solver expansion and success/failure behavior.
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/LandRoadRouteSelector.java`
  - Selector contract between legacy and hybrid backends.
- Modify: `src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java`
  - Auto-road caller that must preserve shared land-route success semantics.
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/RoadPathfinderTest.java`
  - Shared ground-path regression coverage.
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinderTest.java`
  - Hybrid solver regression coverage.
- Modify: `src/test/java/com/monpai/sailboatmod/route/RoadAutoRouteServiceTest.java`
  - Auto-route regression coverage on the same terrain.

### Construction Runtime Files

- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
  - Shared road-job scheduling, automatic tick progression, hammer interaction, and remaining-step/runtime-state derivation.
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/RoadLifecycleServiceTest.java`
  - Active road runtime state, progress, and lifecycle coverage using existing reflection helpers.
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`
  - Low-level road placement/runtime placement progression coverage.
- Modify: `src/test/java/com/monpai/sailboatmod/construction/BuilderHammerSupportTest.java`
  - Hammer/runtime consistency coverage for road ghosts and remaining steps.

## Scope Note

The approved spec spans two subsystems, but they are coupled by a single user-visible road lifecycle regression. This plan keeps them in one document and one execution sequence so the restored route solver and restored construction runtime can be verified together before shipping.

### Task 1: Lock Down Shared Ground-Route Failures

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/RoadPathfinderTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinderTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/route/RoadAutoRouteServiceTest.java`

- [ ] **Step 1: Write the failing shared-ground regression tests**

Add these tests to `src/test/java/com/monpai/sailboatmod/nation/service/RoadPathfinderTest.java`:

```java
    @Test
    void groundPathForPlanPrefersSuccessfulHybridResultWhenLegacyGroundSearchExhausts() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        setSurfaceColumn(level, 0, 0, 64, Blocks.GRASS_BLOCK.defaultBlockState());
        setSurfaceColumn(level, 1, 0, 64, Blocks.GRASS_BLOCK.defaultBlockState());
        setSurfaceColumn(level, 2, 0, 67, Blocks.GRASS_BLOCK.defaultBlockState());
        setSurfaceColumn(level, 3, 0, 67, Blocks.GRASS_BLOCK.defaultBlockState());
        setSurfaceColumn(level, 4, 0, 67, Blocks.GRASS_BLOCK.defaultBlockState());

        RoadPathfinder.PlannedPathResult result = RoadPathfinder.findGroundPathForPlan(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(4, 67, 0),
                java.util.Set.of(),
                java.util.Set.of()
        );

        assertTrue(result.success(), () -> "expected shared ground planning to recover via hybrid path, got " + result.failureReason());
        assertEquals(new BlockPos(0, 64, 0), result.path().get(0));
        assertEquals(new BlockPos(4, 67, 0), result.path().get(result.path().size() - 1));
    }
```

Add this test to `src/test/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinderTest.java` if it is not already present exactly as written:

```java
    @Test
    void hybridPathfinderAllowsShortRouteWithSingleThreeBlockRise() {
        TestTerrainLevel level = allocate(TestTerrainLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        level.setSurface(0, 0, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        level.setSurface(1, 0, 64, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        level.setSurface(2, 0, 67, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        level.setSurface(3, 0, 67, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());
        level.setSurface(4, 0, 67, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.AIR.defaultBlockState());

        RoadPathfinder.PlannedPathResult result = LandRoadHybridPathfinder.find(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(4, 67, 0),
                Set.of(),
                Set.of(),
                new RoadPlanningPassContext(level)
        );

        assertTrue(result.success(), () -> "expected a short stepped route to remain traversable, got " + result.failureReason());
        assertEquals(new BlockPos(0, 64, 0), result.path().get(0));
        assertEquals(new BlockPos(4, 67, 0), result.path().get(result.path().size() - 1));
    }
```

Add this test and helper class to `src/test/java/com/monpai/sailboatmod/route/RoadAutoRouteServiceTest.java`:

```java
    @Test
    void autoRoutePreviewUsesSameTraversableGroundPathAsManualPlanning() {
        TestServerLevel level = new TestServerLevel();

        level.setSurface(0, 0, 64, Blocks.GRASS_BLOCK.defaultBlockState());
        level.setSurface(1, 0, 64, Blocks.GRASS_BLOCK.defaultBlockState());
        level.setSurface(2, 0, 67, Blocks.GRASS_BLOCK.defaultBlockState());
        level.setSurface(3, 0, 67, Blocks.GRASS_BLOCK.defaultBlockState());
        level.setSurface(4, 0, 67, Blocks.GRASS_BLOCK.defaultBlockState());

        RoadAutoRouteService.RouteResolution result = RoadAutoRouteService.resolveAutoRoutePreview(
                level,
                new net.minecraft.core.BlockPos(0, 64, 0),
                new net.minecraft.core.BlockPos(4, 67, 0)
        );

        assertTrue(result.found(), "auto route preview should recover the same traversable land path");
        assertEquals(RoadAutoRouteService.PathSource.LAND_TERRAIN, result.source());
        assertEquals(new net.minecraft.core.BlockPos(0, 64, 0), result.path().get(0));
        assertEquals(new net.minecraft.core.BlockPos(4, 67, 0), result.path().get(result.path().size() - 1));
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) {
        try {
            java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
            return (T) unsafe.allocateInstance(type);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static long columnKey(int x, int z) {
        return net.minecraft.core.BlockPos.asLong(x, 0, z);
    }

    static final class TestServerLevel extends net.minecraft.server.level.ServerLevel {
        private java.util.Map<Long, net.minecraft.world.level.block.state.BlockState> blockStates;
        private java.util.Map<Long, Integer> surfaceHeights;
        private net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome;

        private TestServerLevel() {
            super(null, command -> { }, null, null, null, null, null, false, 0L, java.util.List.of(), false, null);
        }

        void setSurface(int x, int z, int y, net.minecraft.world.level.block.state.BlockState surface) {
            if (blockStates == null) {
                blockStates = new java.util.HashMap<>();
            }
            if (surfaceHeights == null) {
                surfaceHeights = new java.util.HashMap<>();
            }
            if (biome == null) {
                biome = net.minecraft.core.Holder.direct(allocate(net.minecraft.world.level.biome.Biome.class));
            }
            surfaceHeights.put(columnKey(x, z), y);
            blockStates.put(new net.minecraft.core.BlockPos(x, y, z).asLong(), surface);
            blockStates.put(new net.minecraft.core.BlockPos(x, y + 1, z).asLong(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }

        @Override
        public net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos pos) {
            return blockStates.getOrDefault(pos.asLong(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }

        @Override
        public net.minecraft.core.BlockPos getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types type, net.minecraft.core.BlockPos pos) {
            int surfaceY = surfaceHeights.getOrDefault(columnKey(pos.getX(), pos.getZ()), 0);
            return new net.minecraft.core.BlockPos(pos.getX(), surfaceY + 1, pos.getZ());
        }

        @Override
        public int getMinBuildHeight() {
            return 0;
        }

        @Override
        public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getBiome(net.minecraft.core.BlockPos pos) {
            return biome;
        }
    }
```

- [ ] **Step 2: Run the targeted route tests to verify they fail**

Run:

```powershell
Set-Location 'F:\Codex\sailboatmod'
.\gradlew.bat test --tests 'com.monpai.sailboatmod.nation.service.RoadPathfinderTest.groundPathForPlanPrefersSuccessfulHybridResultWhenLegacyGroundSearchExhausts' --tests 'com.monpai.sailboatmod.nation.service.LandRoadHybridPathfinderTest.hybridPathfinderAllowsShortRouteWithSingleThreeBlockRise' --tests 'com.monpai.sailboatmod.route.RoadAutoRouteServiceTest.autoRoutePreviewUsesSameTraversableGroundPathAsManualPlanning'
```

Expected: FAIL, with at least one assertion showing `SEARCH_EXHAUSTED` or `found() == false` on the stepped pure-land fixture.

- [ ] **Step 3: Implement the minimal shared-route fix**

Update `src/main/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinder.java` to preserve the relaxed short-step land expansion and keep the terminal condition tied to resolved terrain surfaces:

```java
public final class LandRoadHybridPathfinder {
    private static final int MAX_STEP_UP = 3;
    private static final int MAX_STEP_DOWN = 3;

    // existing constructor omitted

    public static RoadPathfinder.PlannedPathResult find(Level level,
                                                        BlockPos from,
                                                        BlockPos to,
                                                        Set<Long> blockedColumns,
                                                        Set<Long> excludedColumns,
                                                        RoadPlanningPassContext context) {
        if (level == null || from == null || to == null) {
            return new RoadPathfinder.PlannedPathResult(List.of(), RoadPlanningFailureReason.SEARCH_EXHAUSTED);
        }

        LandTerrainSamplingCache cache = new LandTerrainSamplingCache(level, context);
        BlockPos start = cache.surface(from.getX(), from.getZ());
        BlockPos goal = cache.surface(to.getX(), to.getZ());
        if (start == null || goal == null) {
            return new RoadPathfinder.PlannedPathResult(List.of(), RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE);
        }

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score));
        Map<Long, Node> best = new HashMap<>();
        Node startNode = new Node(start, null, 0.0D, heuristic(start, goal));
        open.add(startNode);
        best.put(start.asLong(), startNode);

        while (!open.isEmpty()) {
            RoadPlanningTaskService.throwIfCancelled();
            Node current = open.poll();
            if (current == null) {
                continue;
            }
            if (current.pos().distManhattan(goal) <= 1) {
                return new RoadPathfinder.PlannedPathResult(rebuild(current, goal), RoadPlanningFailureReason.NONE);
            }
            for (BlockPos next : neighbors(current.pos(), cache, blockedColumns, excludedColumns)) {
                int elevationDelta = next == null ? Integer.MAX_VALUE : Math.abs(next.getY() - current.pos().getY());
                if (next == null || elevationDelta > (next.getY() >= current.pos().getY() ? MAX_STEP_UP : MAX_STEP_DOWN)) {
                    continue;
                }
                double gScore = current.gScore() + LandPathCostModel.moveCost(
                        orthogonalOrDiagonalCost(current.pos(), next),
                        elevationDelta,
                        cache.stability(next),
                        cache.nearWater(next),
                        deviationCost(from, to, next)
                );
                Node known = best.get(next.asLong());
                if (known != null && known.gScore() <= gScore) {
                    continue;
                }
                Node candidate = new Node(next, current, gScore, gScore + heuristic(next, goal));
                best.put(next.asLong(), candidate);
                open.add(candidate);
            }
        }
        return new RoadPathfinder.PlannedPathResult(List.of(), RoadPlanningFailureReason.SEARCH_EXHAUSTED);
    }
}
```

Update `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java` so `findGroundPathForPlan(...)` always returns the best successful result and never downgrades a valid route:

```java
    static PlannedPathResult findGroundPathForPlan(Level level,
                                                   BlockPos from,
                                                   BlockPos to,
                                                   Set<Long> blockedColumns,
                                                   Set<Long> excludedColumns,
                                                   RoadPlanningPassContext context) {
        PlannedPathResult legacy = findPathForPlan(level, from, to, blockedColumns, excludedColumns, false, context);
        LandRoadRouteSelector.Selection selection = LandRoadRouteSelector.select(
                from,
                to,
                legacy.path(),
                legacy.failureReason(),
                LandPathQualityEvaluator.elevationVariance(legacy.path()),
                estimateNearWaterColumns(level, legacy.path(), context),
                LandPathQualityEvaluator.fragmentedColumns(legacy.path())
        );
        if (selection.backEnd() == LandRoadRouteSelector.BackEnd.LEGACY && legacy.success()) {
            return legacy;
        }
        PlannedPathResult hybrid = LandRoadHybridPathfinder.find(level, from, to, blockedColumns, excludedColumns, context);
        if (hybrid.success()) {
            return hybrid;
        }
        return legacy;
    }
```

- [ ] **Step 4: Run the targeted route tests to verify they pass**

Run:

```powershell
Set-Location 'F:\Codex\sailboatmod'
.\gradlew.bat test --tests 'com.monpai.sailboatmod.nation.service.RoadPathfinderTest.groundPathForPlanPrefersSuccessfulHybridResultWhenLegacyGroundSearchExhausts' --tests 'com.monpai.sailboatmod.nation.service.LandRoadHybridPathfinderTest.hybridPathfinderAllowsShortRouteWithSingleThreeBlockRise' --tests 'com.monpai.sailboatmod.route.RoadAutoRouteServiceTest.autoRoutePreviewUsesSameTraversableGroundPathAsManualPlanning'
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java src/main/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinder.java src/test/java/com/monpai/sailboatmod/nation/service/RoadPathfinderTest.java src/test/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinderTest.java src/test/java/com/monpai/sailboatmod/route/RoadAutoRouteServiceTest.java
git commit -m "Restore shared land road path recovery"
```

### Task 2: Keep Auto Routing On The Same Ground-Route Contract

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/LandRoadRouteSelector.java`
- Modify: `src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/LandRoadRouteSelectorTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/route/RoadAutoRouteServiceTest.java`

- [ ] **Step 1: Write the failing selector and auto-route fallback tests**

Add this test to `src/test/java/com/monpai/sailboatmod/nation/service/LandRoadRouteSelectorTest.java`:

```java
    @Test
    void selectorDoesNotKeepFailedLegacyBackendWhenNoGroundPathWasProduced() {
        LandRoadRouteSelector.Selection selection = LandRoadRouteSelector.selectForTest(
                new BlockPos(0, 64, 0),
                new BlockPos(12, 67, 0),
                List.of(),
                RoadPlanningFailureReason.SEARCH_EXHAUSTED,
                0,
                0,
                0
        );

        assertEquals(LandRoadRouteSelector.BackEnd.HYBRID, selection.backEnd());
    }
```

Add this test to `src/test/java/com/monpai/sailboatmod/route/RoadAutoRouteServiceTest.java`:

```java
    @Test
    void preferResolutionReturnsDirectLandPathWhenHybridFails() {
        RoadAutoRouteService.RouteResolution direct = new RoadAutoRouteService.RouteResolution(
                RoadAutoRouteService.PathSource.LAND_TERRAIN,
                List.of(new net.minecraft.core.BlockPos(0, 64, 0), new net.minecraft.core.BlockPos(4, 67, 0))
        );
        RoadAutoRouteService.RouteResolution hybrid = RoadAutoRouteService.RouteResolution.none();

        RoadAutoRouteService.RouteResolution chosen = RoadAutoRouteService.preferResolutionForTest(direct, hybrid);

        assertTrue(chosen.found());
        assertEquals(RoadAutoRouteService.PathSource.LAND_TERRAIN, chosen.source());
        assertEquals(2, chosen.path().size());
    }
```

- [ ] **Step 2: Run the fallback tests to verify they fail**

Run:

```powershell
Set-Location 'F:\Codex\sailboatmod'
.\gradlew.bat test --tests 'com.monpai.sailboatmod.nation.service.LandRoadRouteSelectorTest.selectorDoesNotKeepFailedLegacyBackendWhenNoGroundPathWasProduced' --tests 'com.monpai.sailboatmod.route.RoadAutoRouteServiceTest.preferResolutionReturnsDirectLandPathWhenHybridFails'
```

Expected: FAIL, showing selector prefers `LEGACY` on a failed route or auto-route resolution drops the successful direct path.

- [ ] **Step 3: Implement the minimal selector and auto-route contract fix**

Update `src/main/java/com/monpai/sailboatmod/nation/service/LandRoadRouteSelector.java`:

```java
    public static Selection select(BlockPos from,
                                   BlockPos to,
                                   List<BlockPos> legacyPath,
                                   RoadPlanningFailureReason failureReason,
                                   int elevationVariance,
                                   int nearWaterColumns,
                                   int fragmentedColumns) {
        if (failureReason == RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE
                || failureReason == RoadPlanningFailureReason.SEARCH_EXHAUSTED
                || legacyPath == null
                || legacyPath.size() < 2) {
            return new Selection(BackEnd.HYBRID, "legacy_failed");
        }
        if (elevationVariance >= SOFT_ELEVATION_TRIGGER
                || nearWaterColumns >= SOFT_WATER_TRIGGER
                || fragmentedColumns > 0) {
            return new Selection(BackEnd.HYBRID, "soft_trigger");
        }
        return new Selection(BackEnd.LEGACY, "legacy_ok");
    }
```

Keep `src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java` returning the best found route by preserving the existing `preferResolution(...)` pattern and ensuring `direct` is always computed:

```java
    private static RouteResolution resolveAutoRoute(ServerLevel level, BlockPos start, BlockPos end) {
        if (level == null || start == null || end == null) {
            return RouteResolution.none();
        }

        Graph graph = buildGraph(level);
        SegmentedRoadPathOrchestrator.OrchestratedPath orchestrated = SegmentedRoadPathOrchestrator.plan(
                start,
                end,
                collectSegmentAnchors(level, start, end, graph.nodes()),
                request -> new SegmentedRoadPathOrchestrator.SegmentPlan(
                        resolveHybridSegment(level, request.from(), request.to(), graph.nodes(), graph.adjacency()),
                        SegmentedRoadPathOrchestrator.FailureReason.SEARCH_EXHAUSTED
                ),
                request -> shouldSubdivideSegment(request.from(), request.to())
        );
        RouteResolution hybrid = orchestrated.success()
                ? new RouteResolution(
                        usesExistingNetwork(orchestrated.path(), graph.nodes()) ? PathSource.ROAD_NETWORK : PathSource.LAND_TERRAIN,
                        orchestrated.path()
                )
                : RouteResolution.none();
        RouteResolution direct = new RouteResolution(PathSource.LAND_TERRAIN, findLandRoute(level, start, end));
        return preferResolution(direct, hybrid);
    }
```

- [ ] **Step 4: Run the fallback tests to verify they pass**

Run:

```powershell
Set-Location 'F:\Codex\sailboatmod'
.\gradlew.bat test --tests 'com.monpai.sailboatmod.nation.service.LandRoadRouteSelectorTest.selectorDoesNotKeepFailedLegacyBackendWhenNoGroundPathWasProduced' --tests 'com.monpai.sailboatmod.route.RoadAutoRouteServiceTest.preferResolutionReturnsDirectLandPathWhenHybridFails'
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/LandRoadRouteSelector.java src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java src/test/java/com/monpai/sailboatmod/nation/service/LandRoadRouteSelectorTest.java src/test/java/com/monpai/sailboatmod/route/RoadAutoRouteServiceTest.java
git commit -m "Stabilize road selector and auto route fallback"
```

### Task 3: Restore Automatic Road Construction Progress

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/RoadLifecycleServiceTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`

- [ ] **Step 1: Write the failing runtime progression tests**

Add these tests to `src/test/java/com/monpai/sailboatmod/nation/service/RoadLifecycleServiceTest.java`:

```java
    @Test
    void scheduledRoadJobWithRemainingBuildStepsAdvancesDuringTick() {
        ServerLevel level = allocate(ServerLevel.class);
        RoadPlacementPlan plan = StructureConstructionManager.createRoadPlacementPlanForTest(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 64, 0)
                ),
                List.of(),
                List.of()
        );
        String roadId = "manual|tick|town_a|town_b";
        @SuppressWarnings("unchecked")
        Map<String, Object> activeRoads = readStaticMap("ACTIVE_ROAD_CONSTRUCTIONS");
        Object previous = activeRoads.put(roadId, newRoadConstructionJob(level, roadId, plan, List.of(), 0, plan.buildSteps().size(), false, 0, false));

        try {
            StructureConstructionManager.tickRoadConstructions(level);

            Object updated = activeRoads.get(roadId);
            assertNotNull(updated);
            assertTrue((int) readRecordComponent(updated, "placedStepCount") > 0, "tick should consume at least one road build step");
        } finally {
            restoreMapEntry(activeRoads, roadId, previous);
        }
    }

    @Test
    void roadConstructionRuntimeDoesNotDiscardValidJobJustBecauseProgressStartsAtZero() {
        ServerLevel level = allocate(ServerLevel.class);
        RoadPlacementPlan plan = StructureConstructionManager.createRoadPlacementPlanForTest(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 64, 0)
                ),
                List.of(),
                List.of()
        );
        String roadId = "manual|runtime|town_a|town_b";
        @SuppressWarnings("unchecked")
        Map<String, Object> activeRoads = readStaticMap("ACTIVE_ROAD_CONSTRUCTIONS");
        Object previous = activeRoads.put(roadId, newRoadConstructionJob(level, roadId, plan, List.of(), 0, 0.0D, false, 0, false));

        try {
            StructureConstructionManager.tickRoadConstructions(level);
            assertNotNull(activeRoads.get(roadId), "valid road runtime should remain active after first tick");
        } finally {
            restoreMapEntry(activeRoads, roadId, previous);
        }
    }
```

- [ ] **Step 2: Run the targeted runtime tests to verify they fail**

Run:

```powershell
Set-Location 'F:\Codex\sailboatmod'
.\gradlew.bat test --tests 'com.monpai.sailboatmod.nation.service.RoadLifecycleServiceTest.scheduledRoadJobWithRemainingBuildStepsAdvancesDuringTick' --tests 'com.monpai.sailboatmod.nation.service.RoadLifecycleServiceTest.roadConstructionRuntimeDoesNotDiscardValidJobJustBecauseProgressStartsAtZero'
```

Expected: FAIL, with `placedStepCount == 0` or the road job disappearing after the tick.

- [ ] **Step 3: Implement the minimal automatic-progression fix**

Update `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java` in `tickRoadConstructions(...)` and `placeRoadBuildSteps(...)` so the first batch is placeable without hammer credits and the runtime never treats zero progress as terminal when build steps remain:

```java
    public static void tickRoadConstructions(ServerLevel level) {
        List<String> completedBuilds = new ArrayList<>();
        List<String> completedRollbacks = new ArrayList<>();
        Set<ServerPlayer> playersToSync = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Map.Entry<String, RoadConstructionJob> entry : ACTIVE_ROAD_CONSTRUCTIONS.entrySet()) {
            RoadConstructionJob job = refreshRoadConstructionState(level, entry.getValue());
            if (job.level != level) continue;

            ServerPlayer owner = ownerPlayer(level, job.ownerUuid);
            if (owner != null) {
                playersToSync.add(owner);
            }

            int activeWorkers = getActiveRoadWorkerCount(level, entry.getKey());
            job = consumeRoadHammerCredit(level, entry.getKey(), job);
            int totalUnits = job.rollbackActive
                    ? roadRollbackActionOrder(level, job.plan, job.rollbackStates).size()
                    : job.plan.buildSteps().size();
            if (totalUnits <= 0) {
                if (job.rollbackActive) {
                    completedRollbacks.add(entry.getKey());
                } else {
                    completedBuilds.add(entry.getKey());
                }
                continue;
            }

            double speedMultiplier = activeWorkers > 0 ? (activeWorkers + 2.0D) / 3.0D : 1.0D;
            double progressPerTick = job.rollbackActive
                    ? roadRollbackProgressPerTick(totalUnits, speedMultiplier)
                    : roadBuildProgressPerTick(totalUnits, speedMultiplier);
            double targetProgress = job.progressSteps + progressPerTick;

            if (!job.rollbackActive && targetProgress < 1.0D && job.placedStepCount < totalUnits) {
                targetProgress = 1.0D;
            }

            // keep existing rollback branch and build branch structure below
        }
    }
```

Preserve the `placeRoadBuildSteps(...)` semantics that only return completion when `completedCount >= totalSteps`; do not add any code path that clears an otherwise-valid job while `buildSteps().size() > placedStepCount`.

- [ ] **Step 4: Run the targeted runtime tests to verify they pass**

Run:

```powershell
Set-Location 'F:\Codex\sailboatmod'
.\gradlew.bat test --tests 'com.monpai.sailboatmod.nation.service.RoadLifecycleServiceTest.scheduledRoadJobWithRemainingBuildStepsAdvancesDuringTick' --tests 'com.monpai.sailboatmod.nation.service.RoadLifecycleServiceTest.roadConstructionRuntimeDoesNotDiscardValidJobJustBecauseProgressStartsAtZero'
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/nation/service/RoadLifecycleServiceTest.java
git commit -m "Restore automatic road construction tick progress"
```

### Task 4: Re-Unify Visible Road Ghosts, Remaining Steps, And Hammer Targets

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/construction/BuilderHammerSupportTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`

- [ ] **Step 1: Write the failing hammer/runtime consistency tests**

Add this test to `src/test/java/com/monpai/sailboatmod/construction/BuilderHammerSupportTest.java`:

```java
    @Test
    void attemptedRoadStepRemainsHammerTargetableWhileWorldStateStillDiffersFromPlan() {
        RoadGeometryPlanner.RoadBuildStep step = new RoadGeometryPlanner.RoadBuildStep(
                0,
                new BlockPos(0, 65, 0),
                Blocks.STONE_BRICK_SLAB.defaultBlockState(),
                RoadGeometryPlanner.RoadBuildPhase.DECK
        );

        List<RoadGeometryPlanner.RoadBuildStep> remaining = invokeRemainingRoadBuildSteps(
                List.of(step),
                Set.of(),
                Set.of(step.pos().asLong())
        );

        assertEquals(List.of(step), remaining);
    }
```

Add this test to `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`:

```java
    @Test
    void advancingRoadBuildStepsKeepsUnplacedGhostsVisibleUntilStateMatchesPlan() {
        TestServerLevel level = new TestServerLevel();
        RoadPlacementPlan plan = StructureConstructionManager.createRoadPlacementPlanForTest(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 64, 0)
                ),
                List.of(),
                List.of()
        );

        Object advanced = invokeAdvanceRoadBuildSteps(level, newRoadConstructionJob(level, "manual|test|ghost_consistency", plan), 1);
        @SuppressWarnings("unchecked")
        Set<Long> attempted = (Set<Long>) advanced.getClass().getDeclaredMethod("attemptedStepKeys").invoke(advanced);
        List<BlockPos> remainingGhosts = invokeRemainingRoadGhostPositions(level, advanced);

        assertFalse(attempted.isEmpty(), "advance should mark at least one attempted step");
        assertFalse(remainingGhosts.isEmpty(), "remaining ghosts should still exist while world state differs from plan");
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeRemainingRoadGhostPositions(ServerLevel level, Object job) {
        try {
            Method method = StructureConstructionManager.class.getDeclaredMethod(
                    "remainingRoadGhostPositions",
                    ServerLevel.class,
                    Class.forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager$RoadConstructionJob")
            );
            method.setAccessible(true);
            return (List<BlockPos>) method.invoke(null, level, job);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
```

- [ ] **Step 2: Run the targeted hammer/runtime tests to verify they fail**

Run:

```powershell
Set-Location 'F:\Codex\sailboatmod'
.\gradlew.bat test --tests 'com.monpai.sailboatmod.construction.BuilderHammerSupportTest.attemptedRoadStepRemainsHammerTargetableWhileWorldStateStillDiffersFromPlan' --tests 'com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest.advancingRoadBuildStepsKeepsUnplacedGhostsVisibleUntilStateMatchesPlan'
```

Expected: FAIL, showing remaining road ghosts disappear too early or attempted but unconfirmed steps stop being targetable.

- [ ] **Step 3: Implement the minimal runtime-source unification**

Update `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java` so remaining road ghosts are always derived from unconfirmed remaining steps rather than raw attempted-step state:

```java
    private static Set<Long> consumedRoadBuildStepKeys(RoadPlacementPlan plan,
                                                       Set<Long> completedStepKeys,
                                                       Set<Long> attemptedStepKeys) {
        if (plan == null || plan.buildSteps().isEmpty()) {
            return Set.of();
        }
        Set<Long> validStepKeys = plan.buildSteps().stream()
                .map(RoadGeometryPlanner.RoadBuildStep::pos)
                .filter(Objects::nonNull)
                .map(BlockPos::asLong)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        LinkedHashSet<Long> consumed = new LinkedHashSet<>();
        if (completedStepKeys != null) {
            consumed.addAll(completedStepKeys.stream().filter(validStepKeys::contains).toList());
        }
        if (attemptedStepKeys != null && completedStepKeys != null) {
            for (Long key : attemptedStepKeys) {
                if (key != null && validStepKeys.contains(key) && completedStepKeys.contains(key)) {
                    consumed.add(key);
                }
            }
        }
        return Set.copyOf(consumed);
    }

    private static List<RoadGeometryPlanner.GhostRoadBlock> remainingRoadGhostBlocks(ServerLevel level, RoadConstructionJob job) {
        if (level == null || job == null || job.rollbackActive) {
            return List.of();
        }
        return remainingRoadGhostBlocks(job.plan, consumedRoadBuildStepKeys(level, job));
    }
```

Do not add any separate hammer-only target source. Hammer target validation must keep using `remainingRoadGhostPositions(level, job)`.

- [ ] **Step 4: Run the targeted hammer/runtime tests to verify they pass**

Run:

```powershell
Set-Location 'F:\Codex\sailboatmod'
.\gradlew.bat test --tests 'com.monpai.sailboatmod.construction.BuilderHammerSupportTest.attemptedRoadStepRemainsHammerTargetableWhileWorldStateStillDiffersFromPlan' --tests 'com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest.advancingRoadBuildStepsKeepsUnplacedGhostsVisibleUntilStateMatchesPlan'
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/construction/BuilderHammerSupportTest.java src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java
git commit -m "Unify road ghost visibility with runtime remaining steps"
```

### Task 5: Run Full Regression Verification

**Files:**
- Modify: none

- [ ] **Step 1: Run the focused shared road regression suite**

Run:

```powershell
Set-Location 'F:\Codex\sailboatmod'
.\gradlew.bat test --tests 'com.monpai.sailboatmod.nation.service.RoadPathfinderTest' --tests 'com.monpai.sailboatmod.nation.service.LandRoadHybridPathfinderTest' --tests 'com.monpai.sailboatmod.nation.service.LandRoadRouteSelectorTest' --tests 'com.monpai.sailboatmod.route.RoadAutoRouteServiceTest' --tests 'com.monpai.sailboatmod.nation.service.RoadLifecycleServiceTest' --tests 'com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest' --tests 'com.monpai.sailboatmod.construction.BuilderHammerSupportTest'
```

Expected: PASS

- [ ] **Step 2: Run compile verification**

Run:

```powershell
Set-Location 'F:\Codex\sailboatmod'
.\gradlew.bat compileJava
```

Expected: PASS

- [ ] **Step 3: Commit the final regression recovery batch**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java src/main/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinder.java src/main/java/com/monpai/sailboatmod/nation/service/LandRoadRouteSelector.java src/main/java/com/monpai/sailboatmod/route/RoadAutoRouteService.java src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/nation/service/RoadPathfinderTest.java src/test/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinderTest.java src/test/java/com/monpai/sailboatmod/nation/service/LandRoadRouteSelectorTest.java src/test/java/com/monpai/sailboatmod/route/RoadAutoRouteServiceTest.java src/test/java/com/monpai/sailboatmod/nation/service/RoadLifecycleServiceTest.java src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java src/test/java/com/monpai/sailboatmod/construction/BuilderHammerSupportTest.java
git commit -m "Recover road routing and construction runtime regressions"
```

## Self-Review

### Spec Coverage

- Shared ground-route correctness is covered by Tasks 1 and 2.
- Automatic construction progression is covered by Task 3.
- Hammer/runtime target consistency is covered by Task 4.
- Final verification is covered by Task 5.

### Placeholder Scan

- No `TBD`, `TODO`, or “implement later” placeholders remain.
- Every code-changing step includes concrete code snippets.
- Every test step includes exact commands and expected outcomes.

### Type Consistency

- `RoadPathfinder.PlannedPathResult`, `RoadAutoRouteService.RouteResolution`, `RoadPlacementPlan`, and `RoadGeometryPlanner.RoadBuildStep` names match current code.
- Runtime consistency steps reuse existing helpers such as `consumedRoadBuildStepKeys(...)`, `remainingRoadGhostBlocks(...)`, and reflective job helpers already present in road lifecycle tests.
