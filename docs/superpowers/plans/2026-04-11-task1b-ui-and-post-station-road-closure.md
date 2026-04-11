# Task 1B UI And Post-Station Road Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the claims-map tool visibility leak and finish the manual post-station road anchor verification path with tests and fresh evidence.

**Architecture:** Keep the current helper-based design: `ClaimsMapVisibility` remains the single UI predicate, while `PostStationRoadAnchorHelper` and `ManualRoadPlannerService` carry the waiting-area exit and strict-failure logic. This plan is a closure pass over the current worktree, not a planner rewrite.

**Tech Stack:** Java 17, Forge 1.20.1, JUnit 5, Gradle

---

### Task 1: Lock UI map-tool visibility to the claims-map subpage

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreen.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/nation/NationHomeScreen.java`
- Create or Modify: `src/main/java/com/monpai/sailboatmod/client/screen/ClaimsMapVisibility.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/screen/ClaimsMapVisibilityTest.java`

- [ ] **Step 1: Ensure the helper test covers the intended fail-closed contract**

```java
@Test
void showsMapToolsOnlyOnClaimsMapRootSubpage() {
    assertTrue(ClaimsMapVisibility.showMapTools(true, 0));
    assertFalse(ClaimsMapVisibility.showMapTools(true, 1));
    assertFalse(ClaimsMapVisibility.showMapTools(false, 0));
    assertFalse(ClaimsMapVisibility.showMapTools(false, 1));
}
```

- [ ] **Step 2: Run the UI visibility test first**

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.client.screen.ClaimsMapVisibilityTest"`

Expected: PASS if the current helper already matches the contract, otherwise FAIL and fix before continuing.

- [ ] **Step 3: Make both screens use the same helper predicate anywhere the tool widgets are shown or laid out**

```java
boolean claimsMapView = ClaimsMapVisibility.showMapTools(claimsPage, this.claimsSubPage);
refreshMapButton.visible = claimsMapView;
resetMapButton.visible = claimsMapView;
```

- [ ] **Step 4: Re-run the UI visibility test**

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.client.screen.ClaimsMapVisibilityTest"`

Expected: PASS

- [ ] **Step 5: Commit the UI closure**

```bash
git add src/main/java/com/monpai/sailboatmod/client/screen/ClaimsMapVisibility.java src/main/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreen.java src/main/java/com/monpai/sailboatmod/client/screen/nation/NationHomeScreen.java src/test/java/com/monpai/sailboatmod/client/screen/ClaimsMapVisibilityTest.java
git commit -m "fix: restrict claims map tools to map view"
```

### Task 2: Harden post-station waiting-area anchor behavior in service-level tests

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- Modify or Create: `src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java`
- Modify or Create: `src/test/java/com/monpai/sailboatmod/nation/service/PostStationRoadAnchorHelperTest.java`

- [ ] **Step 1: Add or confirm strict failure-state tests in the service layer**

```java
@Test
void strictManualPlanningRejectsMissingTargetExit() {
    ManualRoadPlannerService.ManualPlanFailure failure =
            ManualRoadPlannerService.validateStrictPostStationRoute(true, true, true, false);

    assertEquals(ManualRoadPlannerService.ManualPlanFailure.TARGET_EXIT_MISSING, failure);
}
```

```java
@Test
void strictManualPlanningAllowsOnlyFullyResolvedWaitingAreaRoute() {
    ManualRoadPlannerService.ManualPlanFailure failure =
            ManualRoadPlannerService.validateStrictPostStationRoute(true, true, true, true);

    assertEquals(ManualRoadPlannerService.ManualPlanFailure.NONE, failure);
}
```

- [ ] **Step 2: Add or confirm unblock-footprint coverage**

```java
@Test
void unblocksChosenStationWaitingAreaAndExitColumns() {
    Set<Long> blocked = ManualRoadPlannerService.unblockStationFootprint(
            Set.of(ManualRoadPlannerService.columnKeyForTest(100, 100),
                    ManualRoadPlannerService.columnKeyForTest(101, 100),
                    ManualRoadPlannerService.columnKeyForTest(102, 100)),
            Set.of(new BlockPos(100, 64, 100), new BlockPos(101, 64, 100)),
            new BlockPos(102, 64, 100)
    );

    assertFalse(blocked.contains(ManualRoadPlannerService.columnKeyForTest(100, 100)));
    assertFalse(blocked.contains(ManualRoadPlannerService.columnKeyForTest(101, 100)));
    assertFalse(blocked.contains(ManualRoadPlannerService.columnKeyForTest(102, 100)));
}
```

- [ ] **Step 3: Run the road-anchor tests**

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.nation.service.PostStationRoadAnchorHelperTest" --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest"`

Expected: PASS

- [ ] **Step 4: If a service gap remains, patch only the strict validation / unblock path**

```java
ManualPlanFailure failure = validateStrictPostStationRoute(
        !sourceStations.isEmpty(),
        !targetStations.isEmpty(),
        selectedSourceExit != null,
        selectedTargetExit != null
);
if (failure != ManualPlanFailure.NONE) {
    return ManualPlanResult.failure(failure);
}
```

- [ ] **Step 5: Commit the service/test closure**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java src/test/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerServiceTest.java src/test/java/com/monpai/sailboatmod/nation/service/PostStationRoadAnchorHelperTest.java
git commit -m "test: close post-station road anchor verification"
```

### Task 3: Re-verify the full task-1B slice and record the item-model review outcome

**Files:**
- Modify: `未完成_任务_1.md`
- Optionally Modify: `src/main/resources/assets/sailboatmod/models/item/road_planner.json`

- [ ] **Step 1: Inspect the current road-planner item model reference before claiming closure**

Run: `sed -n '1,200p' src/main/resources/assets/sailboatmod/models/item/road_planner.json`

Expected: the model references the intended current-mainline texture/model assets; if not, fix it in this task.

- [ ] **Step 2: Run the full verification command for task 1B**

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test`

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Update the task file only after fresh evidence exists**

```markdown
- claims-map tools: closed by `ClaimsMapVisibility` wiring and `ClaimsMapVisibilityTest`
- post-station manual-road closure: closed by `ManualRoadPlannerServiceTest` and `PostStationRoadAnchorHelperTest`
```

- [ ] **Step 4: Commit the task-file closure**

```bash
git add 未完成_任务_1.md src/main/resources/assets/sailboatmod/models/item/road_planner.json
git commit -m "docs: close task1b ui and post-station road items"
```
