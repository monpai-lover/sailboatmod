# Road Network Redesign Phase 3 Post-Processing And Snapping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a RoadWeaver-inspired path post-processing and network-snapping phase so manual town roads stop remaining as raw A-to-B lines and instead straighten bridge runs, smooth land runs, and preferentially merge into completed road edges.

**Architecture:** Keep the current manual planner, segmented route orchestration, and bridge-first-on-failure policy intact. Insert a dedicated post-processing service between raw resolved anchors and `StructureConstructionManager.createRoadPlacementPlan(...)`, then add a separate snapping service that can rewrite route endpoints onto completed road edges without forcing snapping decisions into `RoadPathfinder` or construction runtime code.

**Tech Stack:** Java 17, Forge 1.20.1, JUnit 5, Gradle, existing manual-road, pathfinder, and road-network tests under `src/test/java`.

---

## File Structure

### Create

- `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathPostProcessor.java`
  - Own path simplification, bridge-run straightening, land-only relaxation, and output continuity checks for manual roads.
- `src/main/java/com/monpai/sailboatmod/nation/service/RoadNetworkSnapService.java`
  - Own edge-level snap candidate discovery, direction checks, endpoint merge decisions, and stitched path rewriting against completed road records.
- `src/test/java/com/monpai/sailboatmod/nation/service/RoadPathPostProcessorTest.java`
  - Cover simplification, bridge straightening, non-bridge smoothing, and continuity preservation.
- `src/test/java/com/monpai/sailboatmod/nation/service/RoadNetworkSnapServiceTest.java`
  - Cover endpoint snap selection, direction rejection, and merged path continuity.

### Modify

- `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
  - Run raw resolved paths through post-processing and snapping before building `RoadPlacementPlan` and `RoadNetworkRecord`.
- `src/main/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolver.java`
  - Expose reusable network path helpers needed by snap selection without turning snapping into a second planner.
- `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`
  - Add regression coverage for post-processed preview paths and snapped manual road routes.
- `src/test/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolverTest.java`
  - Add focused assertions around network-backed path reuse that snapping depends on.
- `README.md`
  - Update redesign progress to mention phase 3 post-processing and snapping work once verified.

## Task 1: Lock down path post-processing behavior with failing tests

**Files:**
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/RoadPathPostProcessorTest.java`

- [ ] **Step 1: Write a failing simplification test**

```java
@Test
void simplifyPathRemovesRedundantStraightInteriorNodes() {
    List<BlockPos> simplified = RoadPathPostProcessor.simplifyPathForTest(
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(1, 64, 0),
                    new BlockPos(2, 64, 0),
                    new BlockPos(3, 64, 0)
            )
    );

    assertEquals(
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(3, 64, 0)
            ),
            simplified
    );
}
```

- [ ] **Step 2: Write a failing bridge-run straightening test**

```java
@Test
void straightenBridgeRunKeepsBridgeColumnsOnSingleLine() {
    List<BlockPos> straightened = RoadPathPostProcessor.straightenBridgeRunsForTest(
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(1, 64, 1),
                    new BlockPos(2, 64, 2),
                    new BlockPos(3, 64, 1),
                    new BlockPos(4, 64, 0)
            ),
            new boolean[] {false, true, true, true, false}
    );

    assertEquals(
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(1, 64, 0),
                    new BlockPos(2, 64, 0),
                    new BlockPos(3, 64, 0),
                    new BlockPos(4, 64, 0)
            ),
            straightened
    );
}
```

- [ ] **Step 3: Write a failing land-only smoothing test**

```java
@Test
void relaxPathSkippingBridgeSmoothsOnlyNonBridgeInteriorNodes() {
    List<BlockPos> relaxed = RoadPathPostProcessor.relaxPathSkippingBridgeForTest(
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(1, 64, 2),
                    new BlockPos(2, 64, 0),
                    new BlockPos(3, 64, 0),
                    new BlockPos(4, 64, 0)
            ),
            new boolean[] {false, false, false, true, false}
    );

    assertEquals(new BlockPos(1, 64, 1), relaxed.get(1));
    assertEquals(new BlockPos(3, 64, 0), relaxed.get(3));
}
```

- [ ] **Step 4: Run the focused test and verify it fails**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadPathPostProcessorTest" --rerun-tasks`

Expected: FAIL because `RoadPathPostProcessor` and its test hooks do not exist yet.

## Task 2: Implement the post-processing service with continuity guards

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathPostProcessor.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/RoadPathPostProcessorTest.java`

- [ ] **Step 1: Add the minimal class and pass-through process entry point**

```java
package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RoadPathPostProcessor {
    private RoadPathPostProcessor() {
    }

    public static List<BlockPos> process(List<BlockPos> rawPath, boolean[] bridgeMask) {
        if (rawPath == null || rawPath.size() < 2) {
            return List.of();
        }
        List<BlockPos> simplified = simplifyPath(rawPath);
        boolean[] normalizedMask = normalizeBridgeMask(bridgeMask, simplified.size());
        List<BlockPos> straightened = straightenBridgeRuns(simplified, normalizedMask);
        List<BlockPos> relaxed = relaxPathSkippingBridge(straightened, normalizedMask);
        return ensureContinuous(relaxed);
    }

    static List<BlockPos> simplifyPathForTest(List<BlockPos> rawPath) {
        return simplifyPath(rawPath);
    }

    static List<BlockPos> straightenBridgeRunsForTest(List<BlockPos> rawPath, boolean[] bridgeMask) {
        return straightenBridgeRuns(rawPath, bridgeMask);
    }

    static List<BlockPos> relaxPathSkippingBridgeForTest(List<BlockPos> rawPath, boolean[] bridgeMask) {
        return relaxPathSkippingBridge(rawPath, bridgeMask);
    }

    private static boolean[] normalizeBridgeMask(boolean[] bridgeMask, int size) {
        boolean[] normalized = new boolean[Math.max(0, size)];
        if (bridgeMask == null) {
            return normalized;
        }
        for (int i = 0; i < normalized.length && i < bridgeMask.length; i++) {
            normalized[i] = bridgeMask[i];
        }
        return normalized;
    }

    private static List<BlockPos> ensureContinuous(List<BlockPos> path) {
        if (path == null || path.size() < 2) {
            return List.of();
        }
        ArrayList<BlockPos> continuous = new ArrayList<>();
        BlockPos previous = null;
        for (BlockPos pos : path) {
            BlockPos current = Objects.requireNonNull(pos, "path contains null").immutable();
            if (previous == null) {
                continuous.add(current);
                previous = current;
                continue;
            }
            int dx = current.getX() - previous.getX();
            int dy = current.getY() - previous.getY();
            int dz = current.getZ() - previous.getZ();
            int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
            for (int step = 1; step <= steps; step++) {
                continuous.add(new BlockPos(
                        previous.getX() + Math.round(dx * (step / (float) steps)),
                        previous.getY() + Math.round(dy * (step / (float) steps)),
                        previous.getZ() + Math.round(dz * (step / (float) steps))
                ));
            }
            previous = current;
        }
        return List.copyOf(continuous);
    }
}
```

- [ ] **Step 2: Implement simplification, bridge straightening, and land relaxation minimally to satisfy the tests**

```java
private static List<BlockPos> simplifyPath(List<BlockPos> rawPath) {
    if (rawPath == null || rawPath.size() < 3) {
        return rawPath == null ? List.of() : List.copyOf(rawPath);
    }
    ArrayList<BlockPos> simplified = new ArrayList<>();
    simplified.add(rawPath.get(0).immutable());
    for (int i = 1; i < rawPath.size() - 1; i++) {
        BlockPos previous = simplified.get(simplified.size() - 1);
        BlockPos current = rawPath.get(i);
        BlockPos next = rawPath.get(i + 1);
        int dx1 = Integer.compare(current.getX() - previous.getX(), 0);
        int dz1 = Integer.compare(current.getZ() - previous.getZ(), 0);
        int dx2 = Integer.compare(next.getX() - current.getX(), 0);
        int dz2 = Integer.compare(next.getZ() - current.getZ(), 0);
        if (dx1 != dx2 || dz1 != dz2) {
            simplified.add(current.immutable());
        }
    }
    simplified.add(rawPath.get(rawPath.size() - 1).immutable());
    return List.copyOf(simplified);
}

private static List<BlockPos> straightenBridgeRuns(List<BlockPos> rawPath, boolean[] bridgeMask) {
    if (rawPath == null || rawPath.size() < 3 || bridgeMask == null || bridgeMask.length != rawPath.size()) {
        return rawPath == null ? List.of() : List.copyOf(rawPath);
    }
    ArrayList<BlockPos> straightened = new ArrayList<>(rawPath);
    int index = 0;
    while (index < bridgeMask.length) {
        if (!bridgeMask[index]) {
            index++;
            continue;
        }
        int runStart = index;
        while (index < bridgeMask.length && bridgeMask[index]) {
            index++;
        }
        int runEnd = index - 1;
        int anchorStart = Math.max(0, runStart - 1);
        int anchorEnd = Math.min(rawPath.size() - 1, runEnd + 1);
        BlockPos start = rawPath.get(anchorStart);
        BlockPos end = rawPath.get(anchorEnd);
        int span = Math.max(1, anchorEnd - anchorStart);
        for (int i = runStart; i <= runEnd; i++) {
            double t = (i - anchorStart) / (double) span;
            straightened.set(i, new BlockPos(
                    (int) Math.round(start.getX() + ((end.getX() - start.getX()) * t)),
                    rawPath.get(i).getY(),
                    (int) Math.round(start.getZ() + ((end.getZ() - start.getZ()) * t))
            ));
        }
    }
    return List.copyOf(straightened);
}

private static List<BlockPos> relaxPathSkippingBridge(List<BlockPos> rawPath, boolean[] bridgeMask) {
    if (rawPath == null || rawPath.size() < 3) {
        return rawPath == null ? List.of() : List.copyOf(rawPath);
    }
    ArrayList<BlockPos> relaxed = new ArrayList<>();
    relaxed.add(rawPath.get(0).immutable());
    for (int i = 1; i < rawPath.size() - 1; i++) {
        boolean keepRigid = bridgeMask != null
                && i < bridgeMask.length
                && (bridgeMask[i]
                || bridgeMask[i - 1]
                || (i + 1 < bridgeMask.length && bridgeMask[i + 1]));
        if (keepRigid) {
            relaxed.add(rawPath.get(i).immutable());
            continue;
        }
        BlockPos previous = rawPath.get(i - 1);
        BlockPos current = rawPath.get(i);
        BlockPos next = rawPath.get(i + 1);
        relaxed.add(new BlockPos(
                Math.round((previous.getX() + (current.getX() * 2.0F) + next.getX()) / 4.0F),
                current.getY(),
                Math.round((previous.getZ() + (current.getZ() * 2.0F) + next.getZ()) / 4.0F)
        ));
    }
    relaxed.add(rawPath.get(rawPath.size() - 1).immutable());
    return List.copyOf(relaxed);
}
```

- [ ] **Step 3: Re-run the focused test and verify it passes**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadPathPostProcessorTest" --rerun-tasks`

Expected: PASS.

## Task 3: Lock down endpoint snapping and network-backed merge behavior with failing tests

**Files:**
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/RoadNetworkSnapServiceTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolverTest.java`

- [ ] **Step 1: Write a failing snap-target selection test**

```java
@Test
void prefersParallelCompletedRoadEdgeWithinSnapThreshold() {
    BlockPos source = new BlockPos(0, 64, 2);
    BlockPos target = new BlockPos(6, 64, 2);
    List<BlockPos> snapped = RoadNetworkSnapService.snapPathForTest(
            List.of(source, new BlockPos(3, 64, 2), target),
            List.of(
                    new RoadNetworkRecord(
                            "manual|town:a|town:b",
                            "nation",
                            "town",
                            "minecraft:overworld",
                            "town:a",
                            "town:b",
                            List.of(
                                    new BlockPos(0, 64, 0),
                                    new BlockPos(3, 64, 0),
                                    new BlockPos(6, 64, 0)
                            ),
                            1L,
                            RoadNetworkRecord.SOURCE_TYPE_MANUAL
                    )
            )
    );

    assertEquals(new BlockPos(0, 64, 0), snapped.get(0));
    assertEquals(new BlockPos(6, 64, 0), snapped.get(snapped.size() - 1));
}
```

- [ ] **Step 2: Write a failing direction-compatibility rejection test**

```java
@Test
void rejectsSnapWhenNearbyRoadRunsPerpendicularToRoute() {
    List<BlockPos> snapped = RoadNetworkSnapService.snapPathForTest(
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(3, 64, 0),
                    new BlockPos(6, 64, 0)
            ),
            List.of(
                    new RoadNetworkRecord(
                            "manual|town:c|town:d",
                            "nation",
                            "town",
                            "minecraft:overworld",
                            "town:c",
                            "town:d",
                            List.of(
                                    new BlockPos(3, 64, -3),
                                    new BlockPos(3, 64, 0),
                                    new BlockPos(3, 64, 3)
                            ),
                            1L,
                            RoadNetworkRecord.SOURCE_TYPE_MANUAL
                    )
            )
    );

    assertEquals(
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(3, 64, 0),
                    new BlockPos(6, 64, 0)
            ),
            snapped
    );
}
```

- [ ] **Step 3: Extend the hybrid resolver tests to lock down reusable network path stitching**

```java
@Test
void collectsAdjacencyAcrossExistingRoadRecordPath() {
    Map<BlockPos, Set<BlockPos>> adjacency = RoadHybridRouteResolver.collectNetworkAdjacency(
            List.of(
                    new RoadNetworkRecord(
                            "manual|town:a|town:b",
                            "nation",
                            "town",
                            "minecraft:overworld",
                            "town:a",
                            "town:b",
                            List.of(
                                    new BlockPos(0, 64, 0),
                                    new BlockPos(1, 64, 0),
                                    new BlockPos(2, 64, 0)
                            ),
                            1L,
                            RoadNetworkRecord.SOURCE_TYPE_MANUAL
                    )
            )
    );

    assertEquals(Set.of(new BlockPos(1, 64, 0)), adjacency.get(new BlockPos(0, 64, 0)));
}
```

- [ ] **Step 4: Run the focused snapping tests and verify they fail**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadNetworkSnapServiceTest" --tests "com.monpai.sailboatmod.nation.service.RoadHybridRouteResolverTest" --rerun-tasks`

Expected: FAIL because the snapping service does not exist and hybrid helpers are not yet being reused for endpoint snapping.

## Task 4: Implement network snapping and wire it into manual planning

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/RoadNetworkSnapService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolver.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/RoadNetworkSnapServiceTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolverTest.java`

- [ ] **Step 1: Add the minimal snapping service and test hook**

```java
package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class RoadNetworkSnapService {
    private static final double MAX_SNAP_DISTANCE = 3.0D;
    private static final double MIN_DIRECTION_DOT = 0.6D;

    private RoadNetworkSnapService() {
    }

    public static List<BlockPos> snapPath(List<BlockPos> rawPath, List<RoadNetworkRecord> roads) {
        if (rawPath == null || rawPath.size() < 2 || roads == null || roads.isEmpty()) {
            return rawPath == null ? List.of() : List.copyOf(rawPath);
        }
        ArrayList<BlockPos> snapped = new ArrayList<>(rawPath);
        BlockPos snappedStart = findSnapPoint(rawPath.get(0), rawPath.get(1), roads);
        BlockPos snappedEnd = findSnapPoint(rawPath.get(rawPath.size() - 1), rawPath.get(rawPath.size() - 2), roads);
        if (snappedStart != null) {
            snapped.set(0, snappedStart);
        }
        if (snappedEnd != null) {
            snapped.set(snapped.size() - 1, snappedEnd);
        }
        return List.copyOf(snapped);
    }

    static List<BlockPos> snapPathForTest(List<BlockPos> rawPath, List<RoadNetworkRecord> roads) {
        return snapPath(rawPath, roads);
    }
}
```

- [ ] **Step 2: Implement nearest compatible snap-point selection**

```java
private static BlockPos findSnapPoint(BlockPos endpoint, BlockPos neighbor, List<RoadNetworkRecord> roads) {
    BlockPos best = null;
    double bestDistanceSq = Double.MAX_VALUE;
    for (RoadNetworkRecord road : roads) {
        if (road == null || road.path() == null) {
            continue;
        }
        for (int i = 0; i < road.path().size(); i++) {
            BlockPos candidate = road.path().get(i);
            if (candidate == null) {
                continue;
            }
            double distanceSq = endpoint.distSqr(candidate);
            if (distanceSq > (MAX_SNAP_DISTANCE * MAX_SNAP_DISTANCE)) {
                continue;
            }
            BlockPos reference = resolveReferenceNeighbor(road.path(), i);
            if (reference == null || !directionCompatible(endpoint, neighbor, candidate, reference)) {
                continue;
            }
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = candidate.immutable();
            }
        }
    }
    return best;
}

private static BlockPos resolveReferenceNeighbor(List<BlockPos> roadPath, int index) {
    if (index > 0) {
        return roadPath.get(index - 1);
    }
    if (index + 1 < roadPath.size()) {
        return roadPath.get(index + 1);
    }
    return null;
}

private static boolean directionCompatible(BlockPos endpoint,
                                           BlockPos neighbor,
                                           BlockPos snapped,
                                           BlockPos snappedNeighbor) {
    double leftX = neighbor.getX() - endpoint.getX();
    double leftZ = neighbor.getZ() - endpoint.getZ();
    double rightX = snappedNeighbor.getX() - snapped.getX();
    double rightZ = snappedNeighbor.getZ() - snapped.getZ();
    double leftLength = Math.hypot(leftX, leftZ);
    double rightLength = Math.hypot(rightX, rightZ);
    if (leftLength < 1.0E-6D || rightLength < 1.0E-6D) {
        return true;
    }
    double dot = Math.abs(((leftX / leftLength) * (rightX / rightLength)) + ((leftZ / leftLength) * (rightZ / rightLength)));
    return dot >= MIN_DIRECTION_DOT;
}
```

- [ ] **Step 3: Reuse post-processing and snapping inside `ManualRoadPlannerService.buildPlanCandidate(...)`**

```java
List<BlockPos> rawPath = waitingAreaRoute.path();
List<BlockPos> path = normalizePath(sourceAnchor, rawPath, targetAnchor);
if (path.size() < 2) {
    return null;
}

boolean[] bridgeMask = new boolean[path.size()];
for (int i = 0; i < path.size(); i++) {
    bridgeMask[i] = RoadPathfinder.describeColumnForAnchorSelection(level, path.get(i), blockedColumns).bridgeRequired();
}

List<BlockPos> processedPath = RoadPathPostProcessor.process(path, bridgeMask);
List<BlockPos> snappedPath = RoadNetworkSnapService.snapPath(
        processedPath,
        data.getRoadNetworks().stream()
                .filter(roadRecord -> roadRecord != null && roadRecord.dimensionId().equals(level.dimension().location().toString()))
                .toList()
);

if (!SegmentedRoadPathOrchestrator.isContinuousResolvedPath(
        snappedPath.get(0),
        snappedPath.get(snappedPath.size() - 1),
        snappedPath
)) {
    return null;
}
```

- [ ] **Step 4: Add reusable network path accessors in `RoadHybridRouteResolver` only if the snapping tests require them**

```java
static List<BlockPos> findNetworkPathForTest(BlockPos start, BlockPos end, Map<BlockPos, Set<BlockPos>> adjacency) {
    return findNetworkPath(start, end, adjacency);
}
```

- [ ] **Step 5: Re-run the focused snapping and manual-planner tests**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadNetworkSnapServiceTest" --tests "com.monpai.sailboatmod.nation.service.RoadHybridRouteResolverTest" --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest" --rerun-tasks`

Expected: PASS.

## Task 5: Add planner-level regression coverage and verify end-to-end

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`
- Modify: `README.md`

- [ ] **Step 1: Add a regression proving preview plans keep post-processed bridge approaches continuous**

```java
@Test
void normalizePathKeepsPostProcessedManualRoadContinuous() {
    List<BlockPos> processed = RoadPathPostProcessor.process(
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(1, 64, 1),
                    new BlockPos(2, 64, 2),
                    new BlockPos(3, 64, 1),
                    new BlockPos(4, 64, 0)
            ),
            new boolean[] {false, true, true, true, false}
    );

    assertTrue(SegmentedRoadPathOrchestrator.isContinuousResolvedPath(
            processed.get(0),
            processed.get(processed.size() - 1),
            processed
    ));
}
```

- [ ] **Step 2: Run the targeted verification suite**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadPathPostProcessorTest" --tests "com.monpai.sailboatmod.nation.service.RoadNetworkSnapServiceTest" --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest" --tests "com.monpai.sailboatmod.nation.service.RoadHybridRouteResolverTest" --rerun-tasks`

Expected: PASS.

- [ ] **Step 3: Run the broader build gate**

Run: `.\gradlew.bat build`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Update `README.md` redesign progress bullets**

```markdown
- 路径后处理开始补齐 `RoadWeaver` 风格的冗余点简化、桥段拉直和非桥平滑，减少原始寻路折线直接进入施工的问题
- 手动道路在生成施工蓝图前开始尝试吸附到已完成道路边，减少平行重复铺路并为后续路网并网打基础
```

- [ ] **Step 5: Commit verified phase 3 changes**

```bash
git add README.md src/main/java/com/monpai/sailboatmod/nation/service/RoadPathPostProcessor.java src/main/java/com/monpai/sailboatmod/nation/service/RoadNetworkSnapService.java src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java src/main/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolver.java src/test/java/com/monpai/sailboatmod/nation/service/RoadPathPostProcessorTest.java src/test/java/com/monpai/sailboatmod/nation/service/RoadNetworkSnapServiceTest.java src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java src/test/java/com/monpai/sailboatmod/nation/service/RoadHybridRouteResolverTest.java
git commit -m "Add road path post-processing and snapping"
```

## Self-Review

- Spec coverage: This plan covers the next approved redesign slice only: path post-processing and endpoint snapping. It intentionally leaves biome-aware materials, decoration systems, and structure-offset logic for later phases.
- Placeholder scan: No `TODO`, `TBD`, or cross-task dependency shorthand remains.
- Type consistency: New services are named `RoadPathPostProcessor` and `RoadNetworkSnapService` consistently across file structure, tests, and integration steps.

## Execution Note

This session is already on an approved inline execution path. After saving and pushing this document, continue in the current session and implement the tasks with TDD instead of stopping for a new execution-mode prompt.
