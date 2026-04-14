# Manual Road Water Bridge Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop manual cross-water road planning from failing after a valid bridge path has already been found.

**Architecture:** Keep bridge routing as a direct pathfinding concern, then derive bridge spans and pier placement from the finalized path. Remove bridge-deck candidates from the hard segmented anchor chain so optional bridge references cannot invalidate an otherwise valid bridge route.

**Tech Stack:** Java 17, Forge 1.20.1, JUnit 5, Gradle

---

### Task 1: Lock The Regression With Tests

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`

- [ ] **Step 1: Write the failing test**

Add a test that builds a shallow water strip between two land anchors and asserts `collectSegmentAnchors(...)` does not inject bridge deck anchors when there are no existing road-network anchors to reuse.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest.collectSegmentAnchorsDoesNotForceBridgeDeckCandidatesIntoSegmentChain" --rerun-tasks`
Expected: FAIL because current code returns bridge deck anchors from the water span.

- [ ] **Step 3: Write minimal implementation**

Update `collectSegmentAnchors(...)` so only reusable network anchors participate in the hard anchor chain. Bridge deck references remain post-path construction concerns.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest.collectSegmentAnchorsDoesNotForceBridgeDeckCandidatesIntoSegmentChain" --rerun-tasks`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java
git commit -m "Fix forced bridge segment anchors"
```

### Task 2: Verify Segmented Bridge Planning Still Works

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestratorTest.java`

- [ ] **Step 1: Write the failing test**

Add a focused orchestrator test showing direct segments should succeed without optional bridge anchors being forced into the chain.

- [ ] **Step 2: Run test to verify it fails or stays green for the right reason**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.SegmentedRoadPathOrchestratorTest" --rerun-tasks`
Expected: Either the new regression test fails before the fix, or the suite stays green while the service-layer regression from Task 1 remains the red test.

- [ ] **Step 3: Keep implementation minimal**

Only adjust tests if they are needed to pin the production behavior. Do not redesign the orchestrator unless the service-layer fix is insufficient.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.SegmentedRoadPathOrchestratorTest" --rerun-tasks`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestratorTest.java
git commit -m "Add segmented bridge routing regression coverage"
```

### Task 3: Full Verification And Build

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestratorTest.java`

- [ ] **Step 1: Run focused road-planning tests**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest" --tests "com.monpai.sailboatmod.nation.service.RoadPathfinderTest" --tests "com.monpai.sailboatmod.nation.service.RoadHybridRouteResolverTest" --tests "com.monpai.sailboatmod.nation.service.SegmentedRoadPathOrchestratorTest" --rerun-tasks`
Expected: PASS

- [ ] **Step 2: Run full build**

Run: `.\gradlew.bat build`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java src/test/java/com/monpai/sailboatmod/nation/service/SegmentedRoadPathOrchestratorTest.java docs/superpowers/plans/2026-04-14-manual-road-water-bridge-routing.md
git commit -m "Stabilize manual road bridge routing"
```
