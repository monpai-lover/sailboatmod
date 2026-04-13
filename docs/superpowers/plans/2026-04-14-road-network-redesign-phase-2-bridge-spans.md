# Road Network Redesign Phase 2 Bridge Spans Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align bridge-span handling with the approved RoadWeaver-inspired redesign by normalizing bridge ranges, filtering tiny false-positive bridge spans, and distributing pier anchors across the full elevated bridge span instead of using fixed local intervals.

**Architecture:** Keep the existing manual road flow and corridor/build pipeline, but move bridge-span normalization and pier-anchor distribution into `RoadBridgePlanner` so `StructureConstructionManager` and `RoadCorridorPlanner` stop carrying bridge heuristics inline. Range detection remains terrain-driven, while support placement becomes bridge-plan-driven.

**Tech Stack:** Java 17, Forge 1.20.1, JUnit 5, Gradle, existing construction and road tests under `src/test/java`.

---

## File Structure

### Modify

- `src/main/java/com/monpai/sailboatmod/construction/RoadBridgePlanner.java`
  - Add bridge-range normalization helpers and even pier-anchor distribution based on full elevated span length.
- `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`
  - Replace fixed-interval bridge support selection with `RoadBridgePlanner` anchor distribution.
- `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
  - Normalize raw bridge ranges before corridor planning and profile classification.
- `src/test/java/com/monpai/sailboatmod/construction/RoadBridgePlannerTest.java`
  - Add regressions for short-range filtering, nearby-range merging, and span-wide pier distribution.
- `src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java`
  - Add regression proving long elevated spans place supports at distributed anchor columns instead of every fixed interval.

## Task 1: Lock down bridge-range normalization with failing tests

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/construction/RoadBridgePlannerTest.java`

- [ ] **Step 1: Add a failing test for short bridge-range filtering**

```java
@Test
void normalizeRangesDropsTinyBridgeSpans() {
    assertEquals(
            List.of(new RoadPlacementPlan.BridgeRange(3, 5)),
            RoadBridgePlanner.normalizeRangesForTest(
                    List.of(
                            new RoadPlacementPlan.BridgeRange(1, 1),
                            new RoadPlacementPlan.BridgeRange(3, 5)
                    ),
                    8
            )
    );
}
```

- [ ] **Step 2: Add a failing test for nearby bridge-range merging**

```java
@Test
void normalizeRangesMergesNearbyElevatedSpans() {
    assertEquals(
            List.of(new RoadPlacementPlan.BridgeRange(1, 6)),
            RoadBridgePlanner.normalizeRangesForTest(
                    List.of(
                            new RoadPlacementPlan.BridgeRange(1, 2),
                            new RoadPlacementPlan.BridgeRange(5, 6)
                    ),
                    8
            )
    );
}
```

- [ ] **Step 3: Run the focused test bucket and verify it fails**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.construction.RoadBridgePlannerTest" --rerun-tasks`

Expected: FAIL with missing normalization helpers.

## Task 2: Lock down full-span pier-anchor distribution with failing tests

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/construction/RoadBridgePlannerTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java`

- [ ] **Step 1: Add a failing unit test for even anchor distribution across the full elevated span**

```java
@Test
void distributePierAnchorsUsesFullElevatedSpanLength() {
    assertEquals(
            List.of(2, 4, 6),
            RoadBridgePlanner.distributePierAnchorsForTest(
                    1,
                    7,
                    List.of(2, 3, 4, 5, 6)
            )
    );
}
```

- [ ] **Step 2: Add a failing corridor regression proving long bridge supports use distributed anchors**

```java
@Test
void plannerDistributesLongBridgeSupportsAcrossWholeElevatedSpan() {
    List<BlockPos> centerPath = java.util.stream.IntStream.rangeClosed(0, 8)
            .mapToObj(x -> new BlockPos(x, 64, 0))
            .toList();

    RoadCorridorPlan plan = RoadCorridorPlanner.plan(
            centerPath,
            List.of(new RoadPlacementPlan.BridgeRange(1, 7)),
            List.of()
    );

    assertEquals(
            List.of(2, 4, 6),
            plan.slices().stream()
                    .filter(slice -> !slice.supportPositions().isEmpty())
                    .map(RoadCorridorPlan.CorridorSlice::index)
                    .toList()
    );
}
```

- [ ] **Step 3: Run the focused corridor tests and verify they fail**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.construction.RoadCorridorPlannerTest" --tests "com.monpai.sailboatmod.construction.RoadBridgePlannerTest" --rerun-tasks`

Expected: FAIL because support selection still uses fixed interval heuristics and no shared bridge-anchor distribution exists.

## Task 3: Implement shared bridge normalization and anchor distribution

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadBridgePlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`

- [ ] **Step 1: Add bridge-range normalization helpers to `RoadBridgePlanner`**
- [ ] **Step 2: Add full-span even anchor distribution helpers to `RoadBridgePlanner`**
- [ ] **Step 3: Use normalized ranges in `StructureConstructionManager.detectBridgeRanges(...)`**
- [ ] **Step 4: Replace fixed-interval support planning in `RoadCorridorPlanner` with distributed anchors**
- [ ] **Step 5: Re-run the focused bridge test bucket**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.construction.RoadBridgePlannerTest" --tests "com.monpai.sailboatmod.construction.RoadCorridorPlannerTest" --tests "com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest"`

Expected: PASS.

## Task 4: Verify and document

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update `README.md` with the current road-system redesign progress**
- [ ] **Step 2: Run `.\gradlew.bat test`**
- [ ] **Step 3: Run `.\gradlew.bat compileJava`**
- [ ] **Step 4: Commit bridge-span changes and README update**

## Execution Note

This session is already continuing from an approved inline execution path, so execute this plan directly in the current session instead of stopping for a fresh execution-mode choice.
