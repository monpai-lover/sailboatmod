# Task 1D Routing, Terrain, And Lighting Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Freeze the remaining routing, terrain, and lighting heuristics into bounded rules and tests so the backlog can be closed truthfully.

**Architecture:** Keep routing heuristics in `RoadRouteNodePlanner`, terrain sampling in `RoadPathfinder`, and lamp placement in `RoadLightingPlanner`. The main work is to expose and test the existing rules clearly, then make only the minimal adjustments needed for closure.

**Tech Stack:** Java 17, Forge 1.20.1, JUnit 5, Gradle

---

### Task 1: Lock route-cost heuristics behind pure-logic tests

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadRouteNodePlanner.java`
- Create: `src/test/java/com/monpai/sailboatmod/construction/RoadRouteNodePlannerTest.java`

- [ ] **Step 1: Write a failing pure-logic test for route preference**

```java
@Test
void prefersGentlerDryRouteOverShorterSteepWaterRoute() {
    RoadRouteNodePlanner.RouteMap map = routeMapWithTwoChoices();

    RoadRouteNodePlanner.RoutePlan plan = RoadRouteNodePlanner.plan(map);

    assertEquals(expectedGentlePath(), plan.path());
}
```

- [ ] **Step 2: Run the new planner test**

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.construction.RoadRouteNodePlannerTest"`

Expected: FAIL until the helper fixtures and/or weighting are aligned with the desired closure rule.

- [ ] **Step 3: Keep the route weights explicit and local to `stepCost(...)`**

```java
double cost = diagonal ? 1.45D : 1.0D;
cost += heightDiff * heightDiff * HEIGHT_PENALTY;
cost += next.adjacentWaterColumns() * WATER_PENALTY;
cost += next.terrainPenalty() * TERRAIN_PENALTY;
if (next.requiresBridge()) {
    cost += BRIDGE_PENALTY;
}
```

- [ ] **Step 4: Add one more test for bounded bridge usage or turn penalties if needed**

```java
@Test
void rejectsBridgeHeavyAlternativeWhenLandRouteExists() {
    RoadRouteNodePlanner.RouteMap map = routeMapThatWouldOveruseBridgeColumns();

    RoadRouteNodePlanner.RoutePlan plan = RoadRouteNodePlanner.plan(map);

    assertFalse(plan.usedBridge() && plan.totalBridgeColumns() > 0 && plan.path().equals(expectedOverBridgePath()));
}
```

- [ ] **Step 5: Re-run the planner test suite**

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.construction.RoadRouteNodePlannerTest"`

Expected: PASS

- [ ] **Step 6: Commit the route-heuristic closure**

```bash
git add src/main/java/com/monpai/sailboatmod/construction/RoadRouteNodePlanner.java src/test/java/com/monpai/sailboatmod/construction/RoadRouteNodePlannerTest.java
git commit -m "test: lock road route heuristic rules"
```

### Task 2: Lock lamp-placement rules behind pure-logic tests

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadLightingPlanner.java`
- Create: `src/test/java/com/monpai/sailboatmod/construction/RoadLightingPlannerTest.java`

- [ ] **Step 1: Write a failing lamp-placement exclusion test**

```java
@Test
void skipsBridgeRangesAndProtectedColumns() {
    List<BlockPos> lamps = RoadLightingPlanner.planLampPosts(
            sampleCenterPath(),
            List.of(new RoadPlacementPlan.BridgeRange(2, 6)),
            List.of(new BlockPos(8, 64, 4))
    );

    assertFalse(lamps.contains(new BlockPos(8, 64, 4)));
    assertTrue(lamps.stream().noneMatch(pos -> isBridgeLamp(pos)));
}
```

- [ ] **Step 2: Run the new lighting test**

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.construction.RoadLightingPlannerTest"`

Expected: FAIL until fixtures and planner offsets match the intended rule.

- [ ] **Step 3: Keep the planner simple: spacing, exclusion, side offset**

```java
for (int i = 2; i < centerPath.size() - 1; i += 6) {
    if (isBridgeIndex(i, bridgeRanges)) {
        continue;
    }
    BlockPos candidate = offsetLamp(center, dx, dz);
    if (!blocked.contains(columnKey(candidate))) {
        lamps.add(candidate.immutable());
    }
}
```

- [ ] **Step 4: Re-run the lighting test**

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.construction.RoadLightingPlannerTest"`

Expected: PASS

- [ ] **Step 5: Commit the lighting closure**

```bash
git add src/main/java/com/monpai/sailboatmod/construction/RoadLightingPlanner.java src/test/java/com/monpai/sailboatmod/construction/RoadLightingPlannerTest.java
git commit -m "test: lock road lighting placement rules"
```

### Task 3: Re-verify the full task-1D slice and close the task file

**Files:**
- Modify: `未完成_任务_1.md`

- [ ] **Step 1: Run the full verification command**

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test`

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Update the task file to replace open-ended routing/lighting wording with references to the finalized heuristic tests**

```markdown
- routing/terrain closure: covered by `RoadRouteNodePlannerTest`
- road-lighting closure: covered by `RoadLightingPlannerTest`
```

- [ ] **Step 3: Commit the backlog closure**

```bash
git add 未完成_任务_1.md
git commit -m "docs: close task1d routing and lighting items"
```
