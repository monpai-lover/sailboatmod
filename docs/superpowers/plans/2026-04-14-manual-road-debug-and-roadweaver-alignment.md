# Manual Road Debug And RoadWeaver Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix manual road planning and runtime construction regressions so bridge entries, existing-road reuse, slope placement, rollback restoration, and teardown behavior all work reliably.

**Architecture:** Keep the current `ManualRoadPlannerService -> RoadPathfinder -> StructureConstructionManager` pipeline, but tighten anchor selection and blocked-column semantics so existing road corridors can be reused instead of rejected. Align slope handling more closely with RoadWeaver by preferring deterministic height smoothing and limited stair usage, and move rollback/cancel/demolish into step-driven reverse playback instead of instant full restoration.

**Tech Stack:** Java 17, Forge 47.2.0/47.4.x runtime behavior, JUnit 5, existing road planner/runtime systems, RoadWeaver reference code under `_refs/RoadWeaver`

---

### Task 1: Lock In Reproductions For Planning Reuse And Core Exclusion

**Files:**
- Modify: `F:\Codex\sailboatmod\src\test\java\com\monpai\sailboatmod\nation\service\ManualRoadPlannerServiceTest.java`
- Modify: `F:\Codex\sailboatmod\src\test\java\com\monpai\sailboatmod\nation\service\RoadNetworkSnapServiceTest.java`
- Test: `F:\Codex\sailboatmod\src\test\java\com\monpai\sailboatmod\nation\service\ManualRoadPlannerServiceTest.java`

- [ ] Add failing tests that prove existing road nodes inside claims can be selected as reusable anchors even when a direct core-excluded corridor would otherwise fail.
- [ ] Add failing tests that prove a path from `A` to `C` may legally reuse an `A-B` road segment instead of reporting `no_continuous_ground_route` when only the untouched core corridor is blocked.
- [ ] Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest" --rerun-tasks`
- [ ] Expected before implementation: FAIL in the new reuse/core-crossing cases.

### Task 2: Implement Existing-Road Reuse Before Core-Excluded Direct Planning

**Files:**
- Modify: `F:\Codex\sailboatmod\src\main\java\com\monpai\sailboatmod\nation\service\ManualRoadPlannerService.java`
- Modify: `F:\Codex\sailboatmod\src\main\java\com\monpai\sailboatmod\nation\service\RoadPathfinder.java`
- Test: `F:\Codex\sailboatmod\src\test\java\com\monpai\sailboatmod\nation\service\ManualRoadPlannerServiceTest.java`

- [ ] Update town-anchor and intermediate-anchor selection so claimed existing road nodes are always considered before boundary fallback when they are traversable.
- [ ] Adjust blocked-column handling so path endpoints and reusable road-node corridors are unblocked narrowly, rather than letting whole existing-road connections poison the route search.
- [ ] Ensure direct `A-C` planning can stitch into snapped/reused `A-B` segments while still respecting truly blocked structure/core columns off the reusable corridor.
- [ ] Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest" --rerun-tasks`
- [ ] Expected after implementation: PASS.

### Task 3: Lock In Reproduction For Repeated Build-Step Placement Loops

**Files:**
- Modify: `F:\Codex\sailboatmod\src\test\java\com\monpai\sailboatmod\nation\service\RoadLifecycleServiceTest.java`
- Test: `F:\Codex\sailboatmod\src\test\java\com\monpai\sailboatmod\nation\service\RoadLifecycleServiceTest.java`

- [ ] Add failing tests that prove a step already counts as complete when the world contains an equivalent road state that differs only in tolerated properties or safe runtime substitutions.
- [ ] Add failing tests that prove bridge/ramp steps do not remain perpetually incomplete when a valid stair/slab variant has already been placed.
- [ ] Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadLifecycleServiceTest" --rerun-tasks`
- [ ] Expected before implementation: FAIL in the new completion-loop cases.

### Task 4: Fix Build-Step Completion Semantics To Stop Infinite Reattempts

**Files:**
- Modify: `F:\Codex\sailboatmod\src\main\java\com\monpai\sailboatmod\nation\service\StructureConstructionManager.java`
- Modify: `F:\Codex\sailboatmod\src\main\java\com\monpai\sailboatmod\construction\RoadGeometryPlanner.java`
- Test: `F:\Codex\sailboatmod\src\test\java\com\monpai\sailboatmod\nation\service\RoadLifecycleServiceTest.java`

- [ ] Loosen `isRoadBuildStepPlaced(...)` so equivalent road surface families and safe stair/slab matches can satisfy a step when geometry intent is already present.
- [ ] Keep strict mismatch rejection for genuinely different block families so progress accounting cannot silently skip broken placements.
- [ ] Re-run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadLifecycleServiceTest" --rerun-tasks`
- [ ] Expected after implementation: PASS.

### Task 5: Lock In Reproduction For Stair Facing Regressions

**Files:**
- Modify: `F:\Codex\sailboatmod\src\test\java\com\monpai\sailboatmod\construction\RoadGeometryPlannerSlopeTest.java`
- Test: `F:\Codex\sailboatmod\src\test\java\com\monpai\sailboatmod\construction\RoadGeometryPlannerSlopeTest.java`

- [ ] Add failing tests for diagonal ramps, turn transitions, and bridgeheads where the current `stairFacing(...)` picks the wrong facing or oscillates between adjacent slices.
- [ ] Add a regression test mirroring RoadWeaver’s safer pattern: on mild slopes the planner should prefer smoothed slab/full-block continuity and only emit stairs when the climb band is unambiguous.
- [ ] Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.construction.RoadGeometryPlannerSlopeTest" --rerun-tasks`
- [ ] Expected before implementation: FAIL in the new facing/overuse-of-stairs cases.

### Task 6: Align Slope Geometry More Closely With RoadWeaver

**Files:**
- Modify: `F:\Codex\sailboatmod\src\main\java\com\monpai\sailboatmod\construction\RoadGeometryPlanner.java`
- Reference: `F:\Codex\_refs\RoadWeaver\common\src\main\java\net\shiroha233\roadweaver\features\path\pathlogic\pathfinding\HeightProfileService.java`
- Reference: `F:\Codex\_refs\RoadWeaver\common\src\main\java\net\shiroha233\roadweaver\features\path\pathlogic\core\SegmentPaver.java`
- Test: `F:\Codex\sailboatmod\src\test\java\com\monpai\sailboatmod\construction\RoadGeometryPlannerSlopeTest.java`

- [ ] Refine stair eligibility so stairs only appear on confirmed continuous climb runs, not on ambiguous shoulders or mixed-turn overlap cells.
- [ ] Rework facing selection to derive from the resolved climb direction at the actual placement band, not just from coarse centerline neighbors.
- [ ] Keep RoadWeaver’s guiding principle: smoothing first, slab/full-block continuity second, stairs last.
- [ ] Re-run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.construction.RoadGeometryPlannerSlopeTest" --rerun-tasks`
- [ ] Expected after implementation: PASS.

### Task 7: Lock In Reproduction For Cancel/Demolish Rollback Behavior

**Files:**
- Modify: `F:\Codex\sailboatmod\src\test\java\com\monpai\sailboatmod\nation\service\RoadLifecycleServiceTest.java`
- Modify: `F:\Codex\sailboatmod\src\test\java\com\monpai\sailboatmod\nation\data\ConstructionRuntimeSavedDataTest.java`
- Test: `F:\Codex\sailboatmod\src\test\java\com\monpai\sailboatmod\nation\service\RoadLifecycleServiceTest.java`

- [ ] Add failing tests that prove cancel/demolish should not restore/remove the whole road instantly.
- [ ] Add failing tests that prove original terrain, fluids, and cleared headspace are restored in reverse step order, with rollback running faster than forward build but still incrementally.
- [ ] Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadLifecycleServiceTest" --tests "com.monpai.sailboatmod.nation.data.ConstructionRuntimeSavedDataTest" --rerun-tasks`
- [ ] Expected before implementation: FAIL in the new incremental rollback cases.

### Task 8: Implement Accelerated Incremental Rollback And Demolition

**Files:**
- Modify: `F:\Codex\sailboatmod\src\main\java\com\monpai\sailboatmod\nation\service\StructureConstructionManager.java`
- Modify: `F:\Codex\sailboatmod\src\main\java\com\monpai\sailboatmod\nation\data\ConstructionRuntimeSavedData.java`
- Modify: `F:\Codex\sailboatmod\src\main\java\com\monpai\sailboatmod\nation\service\RoadLifecycleService.java`
- Test: `F:\Codex\sailboatmod\src\test\java\com\monpai\sailboatmod\nation\service\RoadLifecycleServiceTest.java`

- [ ] Convert cancel/demolish into queued reverse jobs that consume tracked rollback/build steps over several ticks instead of performing a one-shot restore.
- [ ] Preserve original terrain restoration snapshots and remove any remaining owned road blocks only when snapshot data is absent for that position.
- [ ] Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.RoadLifecycleServiceTest" --tests "com.monpai.sailboatmod.nation.data.ConstructionRuntimeSavedDataTest" --rerun-tasks`
- [ ] Expected after implementation: PASS.

### Task 9: Final Verification

**Files:**
- Verify only

- [ ] Run focused planner/runtime/slope tests together:
  `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest" --tests "com.monpai.sailboatmod.nation.service.RoadLifecycleServiceTest" --tests "com.monpai.sailboatmod.construction.RoadGeometryPlannerSlopeTest" --rerun-tasks`
- [ ] Run full build:
  `.\gradlew.bat build`
- [ ] Inspect git diff to confirm only intended files changed.

