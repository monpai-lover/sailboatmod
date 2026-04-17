# Water Bridge Stone Pier Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace wood-style water bridges with stone-slab bridges whose support pillars only appear at selected pier-anchor columns and extend down to the riverbed or seafloor.

**Architecture:** Keep the existing corridor-based bridge pipeline, but change bridge-support indexing so only explicit pier-anchor columns create vertical support stacks. Update the bridge material resolver in `StructureConstructionManager` so water bridges use stone deck, stone pier, stone railing, and lantern lighting without affecting land-road materials.

**Tech Stack:** Java 17, Forge 1.20.1, JUnit 5, Gradle, existing `RoadCorridorPlanner`, `RoadCorridorPlan`, `StructureConstructionManager`, and `RoadPlacementPlan`.

---

## File Map

- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`
  - Add focused regressions for stone bridge materials and anchor-only pier generation.
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`
  - Restrict support indexes to explicit pier-anchor bridge columns instead of broad bridge spans.
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
  - Change water-bridge material selection, make pier stacks extend to the riverbed, and switch railing/light support to stone.

## Task 1: Lock down desired water-bridge behavior with failing tests

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`

- [ ] **Step 1: Add a failing regression that proves only pier-anchor columns receive downward supports**

```java
@Test
void waterBridgeSupportsOnlyAppearAtPierAnchorColumns() {
    RoadPlacementPlan plan = StructureConstructionManager.createRoadPlacementPlanForTest(
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(1, 68, 0),
                    new BlockPos(2, 68, 0),
                    new BlockPos(3, 68, 0),
                    new BlockPos(4, 68, 0),
                    new BlockPos(5, 68, 0),
                    new BlockPos(6, 64, 0)
            ),
            List.of(new RoadPlacementPlan.BridgeRange(1, 5)),
            List.of(new RoadPlacementPlan.BridgeRange(3, 3))
    );

    List<RoadCorridorPlan.CorridorSlice> supportSlices = plan.corridorPlan().slices().stream()
            .filter(slice -> !slice.supportPositions().isEmpty())
            .toList();

    assertEquals(1, supportSlices.size());
    assertEquals(3, supportSlices.get(0).index());
}
```

- [ ] **Step 2: Add a failing regression that proves water bridges use stone deck and stone support materials**

```java
@Test
void waterBridgeUsesStoneDeckAndStonePierMaterials() {
    RoadPlacementPlan plan = StructureConstructionManager.createRoadPlacementPlanForTest(
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(1, 68, 0),
                    new BlockPos(2, 68, 0),
                    new BlockPos(3, 68, 0),
                    new BlockPos(4, 68, 0),
                    new BlockPos(5, 68, 0),
                    new BlockPos(6, 64, 0)
            ),
            List.of(new RoadPlacementPlan.BridgeRange(1, 5)),
            List.of(new RoadPlacementPlan.BridgeRange(3, 3))
    );

    assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.STONE_BRICK_SLAB)));
    assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.STONE_BRICKS)));
    assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.COBBLESTONE_WALL)));
    assertTrue(plan.ghostBlocks().stream().noneMatch(block -> block.state().is(Blocks.SPRUCE_SLAB)));
    assertTrue(plan.ghostBlocks().stream().noneMatch(block -> block.state().is(Blocks.SPRUCE_FENCE)));
}
```

- [ ] **Step 3: Run the focused test to verify it fails**

Run:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest
```

Expected: FAIL because supports are still emitted on multiple bridge columns and water bridges still use spruce materials.

- [ ] **Step 4: Commit the failing tests**

```bash
git add src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java
git commit -m "Add water bridge stone pier regressions"
```

## Task 2: Restrict bridge supports to explicit pier-anchor columns

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`

- [ ] **Step 1: Replace interval-based support placement with explicit pier-anchor support placement**

Update `collectSupportRequiredIndexes(...)` so that only navigable-water pier anchors and any explicitly preserved non-navigable pier anchors can create supports:

```java
private static SupportPlacementPlan collectSupportRequiredIndexes(List<RoadPlacementPlan.BridgeRange> bridgeRanges,
                                                                  Set<Integer> bridgeHeadIndexes,
                                                                  Set<Integer> navigableIndexes,
                                                                  int pathSize) {
    Set<Integer> supportIndexes = new HashSet<>();
    if (bridgeRanges == null || bridgeRanges.isEmpty() || pathSize <= 0) {
        return new SupportPlacementPlan(supportIndexes, true);
    }

    for (RoadPlacementPlan.BridgeRange range : bridgeRanges) {
        if (range == null) {
            continue;
        }
        int start = Math.max(0, range.startIndex());
        int end = Math.min(pathSize - 1, range.endIndex());
        for (int index = start; index <= end; index++) {
            if (bridgeHeadIndexes.contains(index)) {
                continue;
            }
            if (navigableIndexes.contains(index)) {
                supportIndexes.add(index);
            }
        }
    }
    return new SupportPlacementPlan(Set.copyOf(supportIndexes), true);
}
```

- [ ] **Step 2: Re-run the focused bridge-link test**

Run:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest
```

Expected: still FAIL, but now only on the material expectations.

- [ ] **Step 3: Commit the support-index change**

```bash
git add src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java
git commit -m "Restrict water bridge supports to pier anchors"
```

## Task 3: Switch water bridges to stone deck, railing, and pier materials

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`

- [ ] **Step 1: Update the water-bridge material style**

Change the water-bridge style selection in `selectRoadPlacementStyle(...)` and `roadPlacementStyleForState(...)`:

```java
if (isBridgeSegment(level, pos)) {
    return new RoadPlacementStyle(Blocks.STONE_BRICK_SLAB.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(), true);
}
```

And map bridge-surface states back to stone support:

```java
if (surfaceState.is(Blocks.STONE_BRICK_SLAB)) {
    return new RoadPlacementStyle(surfaceState, Blocks.STONE_BRICKS.defaultBlockState(), true);
}
if (surfaceState.is(Blocks.STONE_BRICK_STAIRS)) {
    return new RoadPlacementStyle(surfaceState, Blocks.STONE_BRICKS.defaultBlockState(), true);
}
```

- [ ] **Step 2: Update bridge railing and light support materials to stone**

Replace spruce railing/light support placements with wall-and-stone placements:

```java
appendGhost(merged, lightPos.above(), Blocks.COBBLESTONE_WALL.defaultBlockState());
appendGhost(merged, lightPos, Blocks.LANTERN.defaultBlockState());
```

And for side railings:

```java
appendGhost(merged, railingPos, Blocks.COBBLESTONE_WALL.defaultBlockState());
```

- [ ] **Step 3: Update road-ownership and road-surface detection helpers**

Add the new stone bridge support states to ownership checks and remove the spruce bridge-only assumptions:

```java
return state.is(Blocks.STONE_BRICK_SLAB)
        || state.is(Blocks.STONE_BRICK_STAIRS)
        || state.is(Blocks.COBBLESTONE_WALL)
        || state.is(Blocks.STONE_BRICKS)
        || state.is(Blocks.LANTERN);
```

- [ ] **Step 4: Re-run the focused bridge-link test**

Run:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest
```

Expected: still FAIL if pier depth is still fixed and not riverbed-aware.

- [ ] **Step 5: Commit the material swap**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java
git commit -m "Switch water bridges to stone materials"
```

## Task 4: Extend water-bridge piers down to the riverbed or seafloor

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`

- [ ] **Step 1: Add a failing regression for riverbed-depth pier extension**

Append this test:

```java
@Test
void waterBridgePierExtendsDownwardUntilRiverbed() {
    StructureConstructionManager.TestRoadPlacementResult result =
            StructureConstructionManager.roadPlacementResultForTest(
                    Blocks.AIR.defaultBlockState(),
                    true,
                    68,
                    60
            );

    assertEquals(61, result.foundationTopY());
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest
```

Expected: FAIL because bridge support depth is still capped and does not track the riverbed.

- [ ] **Step 3: Replace fixed-depth support fill with riverbed-aware downward stacking for bridge piers**

Refactor `stabilizeRoadFoundation(...)` into a bridge-aware downward fill:

```java
private static void stabilizeRoadFoundation(ServerLevel level, BlockPos pos, RoadPlacementStyle style) {
    BlockPos cursor = pos.below();
    while (cursor.getY() >= level.getMinBuildHeight()) {
        BlockState state = level.getBlockState(cursor);
        if (!isRoadPlacementReplaceable(state)) {
            return;
        }
        level.setBlock(cursor, style.support(), Block.UPDATE_ALL);
        cursor = cursor.below();
    }
}
```

If land roads still need bounded fill, split it explicitly:

```java
if (style.bridge()) {
    stabilizeBridgePierToRiverbed(level, pos, style.support());
} else {
    stabilizeLandRoadFoundation(level, pos, style.support());
}
```

- [ ] **Step 4: Re-run the focused bridge-link test**

Run:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest
```

Expected: PASS.

- [ ] **Step 5: Commit the pier-depth change**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java
git commit -m "Extend water bridge piers to riverbed"
```

## Task 5: End-to-end verification

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`

- [ ] **Step 1: Run the combined focused suite**

Run:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest --tests com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest
```

Expected: PASS.

- [ ] **Step 2: Run the full build**

Run:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dnet.minecraftforge.gradle.check.certs=false'; .\gradlew.bat build
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit the integrated bridge-pier update**

```bash
git add src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java
git commit -m "Rework water bridge piers and stone bridge materials"
```

## Notes For Execution

- Run all commands from `F:\Codex\sailboatmod`.
- Keep unrelated `logs/`, plan drafts, and `.bak` language files out of commits.
- Only water bridges should switch to the new stone style. Do not change desert/swamp/land-road materials.
- The new downward pier fill must only run on actual pier-anchor columns, not on every bridge deck column.
- Preserve lantern lighting on both side railings and pier tops.
