# Road Planner Two-Stage Pathfinding Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore two-stage road planner auto-complete pathfinding and add diagnostics proving which path stage produced the returned route.

**Architecture:** `RoadPlannerPathfinderRunnerFactory` will own the two-stage route run and diagnostics. `RoadPlannerAutoCompleteService` will log runner-vs-fallback and post-processing counts without changing fallback semantics. Tests use fake `Pathfinder` implementations and fake `TerrainSamplingCache` instances so behavior is verified without a running server.

**Tech Stack:** Java 17, Forge 1.20.1, JUnit 5, existing `Pathfinder`, `PathResult`, `TerrainSamplingCache`, and `RoadPlannerAutoCompleteService` APIs.

---

### Task 1: Two-Stage Runner Test Seam

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerPathfinderRunnerFactory.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerPathfinderRunnerFactoryTest.java`

- [ ] **Step 1: Write failing tests for coarse/fine behavior**

Add tests to `RoadPlannerPathfinderRunnerFactoryTest`:

```java
@Test
void twoStagePathfinderReturnsFinePathWhenFineStageSucceeds() {
    BlockPos start = new BlockPos(0, 64, 0);
    BlockPos end = new BlockPos(64, 64, 0);
    List<BlockPos> coarse = List.of(start, new BlockPos(32, 64, 8), end);
    List<BlockPos> fine = List.of(start, new BlockPos(24, 64, 12), new BlockPos(48, 64, 12), end);

    RoadPlannerPathfinderRunnerFactory.RouteRun run = RoadPlannerPathfinderRunnerFactory.runTwoStagePathForTest(
            start,
            end,
            PathfindingConfig.Algorithm.POTENTIAL_FIELD,
            RoadPlannerObstacleMask.empty(),
            flatTerrain(),
            recordingPathfinder(coarse),
            recordingPathfinder(fine)
    );

    assertTrue(run.path().equals(fine));
    assertTrue(run.diagnostics().fineSuccess());
    assertEquals(PathfindingConfig.Algorithm.POTENTIAL_FIELD, run.diagnostics().coarseAlgorithm());
}

@Test
void twoStagePathfinderFallsBackToCoarsePathWhenFineStageFails() {
    BlockPos start = new BlockPos(0, 64, 0);
    BlockPos end = new BlockPos(64, 64, 0);
    List<BlockPos> coarse = List.of(start, new BlockPos(32, 64, 8), end);

    RoadPlannerPathfinderRunnerFactory.RouteRun run = RoadPlannerPathfinderRunnerFactory.runTwoStagePathForTest(
            start,
            end,
            PathfindingConfig.Algorithm.GRADIENT_DESCENT,
            RoadPlannerObstacleMask.empty(),
            flatTerrain(),
            recordingPathfinder(coarse),
            failingPathfinder("fine failed")
    );

    assertEquals(coarse, run.path());
    assertTrue(run.diagnostics().coarseSuccess());
    assertFalse(run.diagnostics().fineSuccess());
}

@Test
void twoStagePathfinderReturnsEmptyPathWhenCoarseStageFails() {
    BlockPos start = new BlockPos(0, 64, 0);
    BlockPos end = new BlockPos(64, 64, 0);

    RoadPlannerPathfinderRunnerFactory.RouteRun run = RoadPlannerPathfinderRunnerFactory.runTwoStagePathForTest(
            start,
            end,
            PathfindingConfig.Algorithm.BASIC_ASTAR,
            RoadPlannerObstacleMask.empty(),
            flatTerrain(),
            failingPathfinder("coarse failed"),
            recordingPathfinder(List.of(start, end))
    );

    assertTrue(run.path().isEmpty());
    assertFalse(run.diagnostics().coarseSuccess());
    assertFalse(run.diagnostics().fineSuccess());
}
```

Add helper methods in the same test file:

```java
private static Pathfinder recordingPathfinder(List<BlockPos> path) {
    return (start, end, cache) -> PathResult.success(path);
}

private static Pathfinder failingPathfinder(String reason) {
    return (start, end, cache) -> PathResult.failure(reason);
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerPathfinderRunnerFactoryTest"
```

Expected: compilation fails because `RouteRun`, `diagnostics()`, and `runTwoStagePathForTest(...)` do not exist.

- [ ] **Step 3: Add minimal two-stage runner implementation**

In `RoadPlannerPathfinderRunnerFactory`, add:

```java
record RouteRun(List<BlockPos> path, Diagnostics diagnostics) {
    RouteRun {
        path = path == null ? List.of() : path.stream().map(BlockPos::immutable).toList();
    }
}

record Diagnostics(PathfindingConfig.Algorithm coarseAlgorithm,
                   BlockPos originalStart,
                   BlockPos originalDestination,
                   BlockPos routeStart,
                   BlockPos routeDestination,
                   boolean coarseSuccess,
                   int coarseNodeCount,
                   String coarseFailureReason,
                   boolean coarseRejectedByMask,
                   boolean fineSuccess,
                   int fineNodeCount,
                   String fineFailureReason,
                   boolean fineRejectedByMask,
                   int finalNodeCount,
                   double maxLateralDeviation) {
}

static RouteRun runTwoStagePathForTest(BlockPos from,
                                       BlockPos destination,
                                       PathfindingConfig.Algorithm algorithm,
                                       RoadPlannerObstacleMask baseMask,
                                       TerrainSamplingCache terrainCache,
                                       Pathfinder coarsePathfinder,
                                       Pathfinder finePathfinder) {
    return runTwoStagePath(from, destination, algorithm, baseMask, terrainCache, coarsePathfinder, finePathfinder);
}
```

Implement `runTwoStagePath(...)` so it:

1. Adjusts endpoints with existing `adjustEndpointForObstacles(...)`.
2. Runs `coarsePathfinder.findPath(...)`.
3. Returns empty when coarse fails or coarse path touches the mask.
4. Runs `finePathfinder.findPath(...)`.
5. Returns fine path if fine succeeds and passes mask.
6. Otherwise returns coarse path.

- [ ] **Step 4: Run tests and verify they pass**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerPathfinderRunnerFactoryTest"
```

Expected: test class passes.

### Task 2: Restore Production Two-Stage Service

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerPathfinderRunnerFactory.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerPathfinderRunnerFactoryTest.java`

- [ ] **Step 1: Write failing test that fine stage uses Potential Field settings**

Add a test pathfinder that records the cache precision by sampling `cache` class behavior is not reliable, so test the created fine config through a helper:

```java
@Test
void fineStageConfigUsesHighPrecisionPotentialFieldWithFourBlockStep() {
    PathfindingConfig config = RoadPlannerPathfinderRunnerFactory.fineStageConfigForTest();

    assertEquals(PathfindingConfig.Algorithm.POTENTIAL_FIELD, config.getAlgorithm());
    assertEquals(4, config.getAStarStep());
    assertEquals(PathfindingConfig.SamplingPrecision.HIGH, config.getSamplingPrecision());
}
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerPathfinderRunnerFactoryTest"
```

Expected: compilation fails because `fineStageConfigForTest()` does not exist.

- [ ] **Step 3: Implement production service restore**

In `serverService(ServerLevel level, PathfindingConfig.Algorithm algorithm)`:

1. Keep selected algorithm in a `coarseConfig`.
2. Create `coarsePathfinder = PathfinderFactory.create(coarseConfig)`.
3. Create `fineConfig = fineStageConfig()`.
4. Create `finePathfinder = PathfinderFactory.create(fineConfig)`.
5. Use `runTwoStagePath(...)` in the runner lambda.
6. Return `run.path()`.

Add:

```java
private static PathfindingConfig fineStageConfig() {
    PathfindingConfig fineConfig = new PathfindingConfig();
    fineConfig.setAlgorithm(PathfindingConfig.Algorithm.POTENTIAL_FIELD);
    fineConfig.setAStarStep(4);
    fineConfig.setSamplingPrecision(PathfindingConfig.SamplingPrecision.HIGH);
    return fineConfig;
}

static PathfindingConfig fineStageConfigForTest() {
    return fineStageConfig();
}
```

- [ ] **Step 4: Run tests and verify they pass**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerPathfinderRunnerFactoryTest"
```

Expected: test class passes.

### Task 3: Diagnostics Logging

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerPathfinderRunnerFactory.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerAutoCompleteService.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerAutoCompleteServiceTest.java`

- [ ] **Step 1: Write failing test for fallback flag exposure**

Add to `RoadPlannerAutoCompleteServiceTest`:

```java
@Test
void exposesWhetherInterpolationFallbackWasUsedForLastCompletion() {
    RoadPlannerAutoCompleteService service = new RoadPlannerAutoCompleteService((from, to) -> List.of());

    RoadPlannerAutoCompleteResult result = service.complete(
            new BlockPos(0, 64, 0),
            new BlockPos(96, 64, 0),
            List.of(),
            24
    );

    assertTrue(result.success());
    assertTrue(service.lastCompletionUsedInterpolationFallbackForTest());
}

@Test
void exposesRunnerRawAndProcessedNodeCountsForLastCompletion() {
    RoadPlannerAutoCompleteService service = new RoadPlannerAutoCompleteService((from, to) -> List.of(
            from,
            new BlockPos(16, 64, 8),
            new BlockPos(32, 64, 8),
            to
    ));

    RoadPlannerAutoCompleteResult result = service.complete(
            new BlockPos(0, 64, 0),
            new BlockPos(48, 64, 0),
            List.of(),
            8
    );

    assertTrue(result.success());
    assertFalse(service.lastCompletionUsedInterpolationFallbackForTest());
    assertEquals(4, service.lastRawPathNodeCountForTest());
    assertTrue(service.lastProcessedPathNodeCountForTest() >= 2);
}
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerAutoCompleteServiceTest"
```

Expected: compilation fails because the three `last...ForTest()` accessors do not exist.

- [ ] **Step 3: Implement diagnostics state and logs**

In `RoadPlannerAutoCompleteService`, add fields:

```java
private boolean lastCompletionUsedInterpolationFallback;
private int lastRawPathNodeCount;
private int lastProcessedPathNodeCount;
```

Set them during `complete(...)`:

```java
lastCompletionUsedInterpolationFallback = false;
lastRawPathNodeCount = suffixNodes.size();
if (suffixNodes.isEmpty()) {
    lastCompletionUsedInterpolationFallback = true;
    suffixNodes = interpolateRoadWeaverStyle(from, destination, spacing);
}
lastRawPathNodeCount = suffixNodes.size();
...
lastProcessedPathNodeCount = suffixNodes.size();
```

Add test accessors:

```java
boolean lastCompletionUsedInterpolationFallbackForTest() { return lastCompletionUsedInterpolationFallback; }
int lastRawPathNodeCountForTest() { return lastRawPathNodeCount; }
int lastProcessedPathNodeCountForTest() { return lastProcessedPathNodeCount; }
```

In `RoadPlannerPathfinderRunnerFactory`, log one compact diagnostics line after `runTwoStagePath(...)` returns.

- [ ] **Step 4: Run tests and verify they pass**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerAutoCompleteServiceTest" --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerPathfinderRunnerFactoryTest"
```

Expected: both test classes pass.

### Task 4: Full Verification and Jar

**Files:**
- No new source files.

- [ ] **Step 1: Run focused tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerPathfinderRunnerFactoryTest" --tests "com.monpai.sailboatmod.client.roadplanner.RoadPlannerAutoCompleteServiceTest" --tests "com.monpai.sailboatmod.roadplanner.obstacle.RoadPlannerObstacleMaskTest" --tests "com.monpai.sailboatmod.road.pathfinding.impl.*"
```

Expected: build successful.

- [ ] **Step 2: Run full build**

Run:

```powershell
.\gradlew.bat build
```

Expected: build successful and `build/libs/sailboatmod-1.3.8-all.jar` is generated.

- [ ] **Step 3: Copy jar to client mods directory**

Run:

```powershell
Copy-Item -LiteralPath 'F:\Codex\sailboatmod\build\libs\sailboatmod-1.3.8-all.jar' -Destination 'E:\.ELGMC Client\.minecraft\versions\1.20.1-Forge_47.4.16\mods\sailboatmod-1.3.8-all.jar' -Force
```

- [ ] **Step 4: Verify copied jar hash**

Run:

```powershell
Get-FileHash 'F:\Codex\sailboatmod\build\libs\sailboatmod-1.3.8-all.jar' -Algorithm SHA256
Get-FileHash 'E:\.ELGMC Client\.minecraft\versions\1.20.1-Forge_47.4.16\mods\sailboatmod-1.3.8-all.jar' -Algorithm SHA256
```

Expected: hashes match.
