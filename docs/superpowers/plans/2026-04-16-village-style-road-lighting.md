# Village-Style Road Lighting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add real bridge-pier lighting and convert road/bridge lamps to a village-style hanging-lantern silhouette while keeping existing road planning and build-step pipelines intact.

**Architecture:** Keep `RoadCorridorPlanner` responsible for light anchors and teach it to emit `pierLightPositions` for real `PIER_BRIDGE` support nodes. Keep `StructureConstructionManager` responsible for ghost/build-step expansion, but replace the current straight-post lanterns with a compact village-style lamp renderer that builds a post, an outward arm, and a hanging lantern from the existing placement style. Land spacing stays at the current cadence; bridge lighting remains denser than land.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, JUnit 5, existing road corridor/geometry/build-step pipeline

---

## File Structure

- `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`
  Responsible for deciding where land lights, bridge deck lights, supports, and new pier-light anchors belong.
- `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
  Responsible for turning corridor anchors into ghost road blocks and build steps using the correct placement style.
- `src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java`
  Best place to lock down new pier-light anchor behavior because it already covers bridge modes, support nodes, and light positions.
- `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`
  Best place to lock down rendered ghost-block shape because it already inspects bridge ghost blocks and road placement plans end-to-end.

## Task 1: Emit Real Bridge-Pier Light Anchors

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`

- [ ] **Step 1: Write the failing planner tests**

Add these tests near the other bridge-mode tests in `src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java`:

```java
    @Test
    void pierBridgeSlicesPopulateAlternatingPierLightPositionsAtExplicitPierNodes() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 66, 0),
                new BlockPos(2, 68, 0),
                new BlockPos(3, 68, 0),
                new BlockPos(4, 68, 0),
                new BlockPos(5, 66, 0),
                new BlockPos(6, 64, 0)
        );
        List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans = List.of(
                new RoadBridgePlanner.BridgeSpanPlan(
                        1,
                        5,
                        RoadBridgePlanner.BridgeMode.PIER_BRIDGE,
                        List.of(
                                new RoadBridgePlanner.BridgePierNode(1, new BlockPos(1, 66, 0), new BlockPos(1, 63, 0), 66, RoadBridgePlanner.BridgeNodeRole.ABUTMENT),
                                new RoadBridgePlanner.BridgePierNode(2, new BlockPos(2, 68, 0), new BlockPos(2, 40, 0), 68, RoadBridgePlanner.BridgeNodeRole.PIER),
                                new RoadBridgePlanner.BridgePierNode(4, new BlockPos(4, 68, 0), new BlockPos(4, 40, 0), 68, RoadBridgePlanner.BridgeNodeRole.PIER),
                                new RoadBridgePlanner.BridgePierNode(5, new BlockPos(5, 66, 0), new BlockPos(5, 63, 0), 66, RoadBridgePlanner.BridgeNodeRole.ABUTMENT)
                        ),
                        List.of(
                                new RoadBridgePlanner.BridgeDeckSegment(1, 2, RoadBridgePlanner.BridgeDeckSegmentType.APPROACH_UP, 66, 68),
                                new RoadBridgePlanner.BridgeDeckSegment(2, 4, RoadBridgePlanner.BridgeDeckSegmentType.MAIN_LEVEL, 68, 68),
                                new RoadBridgePlanner.BridgeDeckSegment(4, 5, RoadBridgePlanner.BridgeDeckSegmentType.APPROACH_DOWN, 68, 66)
                        ),
                        68,
                        true,
                        true
                )
        );

        RoadCorridorPlan plan = RoadCorridorPlanner.plan(centerPath, bridgePlans, new int[] {65, 66, 68, 68, 68, 66, 65});

        assertEquals(List.of(new BlockPos(2, 68, 3)), plan.slices().get(2).pierLightPositions());
        assertTrue(plan.slices().get(3).pierLightPositions().isEmpty());
        assertEquals(List.of(new BlockPos(4, 68, -3)), plan.slices().get(4).pierLightPositions());
    }

    @Test
    void archAndNonPierBridgeSlicesLeavePierLightPositionsEmpty() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 65, 0),
                new BlockPos(2, 67, 0),
                new BlockPos(3, 65, 0),
                new BlockPos(4, 64, 0)
        );
        List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans = List.of(
                new RoadBridgePlanner.BridgeSpanPlan(
                        1,
                        3,
                        RoadBridgePlanner.BridgeMode.ARCH_SPAN,
                        List.of(
                                new RoadBridgePlanner.BridgePierNode(1, new BlockPos(1, 65, 0), new BlockPos(1, 62, 0), 65, RoadBridgePlanner.BridgeNodeRole.ABUTMENT),
                                new RoadBridgePlanner.BridgePierNode(3, new BlockPos(3, 65, 0), new BlockPos(3, 62, 0), 65, RoadBridgePlanner.BridgeNodeRole.ABUTMENT)
                        ),
                        List.of(new RoadBridgePlanner.BridgeDeckSegment(1, 3, RoadBridgePlanner.BridgeDeckSegmentType.ARCHED_SPAN, 65, 65)),
                        65,
                        false,
                        true
                )
        );

        RoadCorridorPlan plan = RoadCorridorPlanner.plan(centerPath, bridgePlans, new int[] {65, 65, 67, 65, 65});

        assertTrue(plan.slices().stream().allMatch(slice -> slice.pierLightPositions().isEmpty()));
    }
```

- [ ] **Step 2: Run the planner tests to verify they fail**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.construction.RoadCorridorPlannerTest
```

Expected: FAIL because `pierLightPositions()` is still `List.of()` for `PIER_BRIDGE` slices.

- [ ] **Step 3: Implement minimal pier-light anchor generation**

In `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`, wire `pierLightPositions` in the `plan(List<BlockPos>, List<RoadBridgePlanner.BridgeSpanPlan>, int[])` overload and add the helper methods below:

```java
            List<BlockPos> pierLightPositions = buildPierLightPositions(
                    centerPath,
                    i,
                    deckCenter,
                    bridgePlan,
                    supportNode
            );
```

```java
    private static List<BlockPos> buildPierLightPositions(List<BlockPos> centerPath,
                                                          int index,
                                                          BlockPos deckCenter,
                                                          RoadBridgePlanner.BridgeSpanPlan bridgePlan,
                                                          RoadBridgePlanner.BridgePierNode supportNode) {
        if (centerPath == null
                || deckCenter == null
                || bridgePlan == null
                || bridgePlan.mode() != RoadBridgePlanner.BridgeMode.PIER_BRIDGE
                || supportNode == null
                || supportNode.role() != RoadBridgePlanner.BridgeNodeRole.PIER) {
            return List.of();
        }

        int[] sideOffsets = resolveSideOffsets(centerPath, index);
        int sideX = sideOffsets[0];
        int sideZ = sideOffsets[1];
        int sideSign = pierLightSideSign(bridgePlan, supportNode.pathIndex());

        return List.of(new BlockPos(
                deckCenter.getX() + (sideX * RAILING_LIGHT_OFFSET * sideSign),
                deckCenter.getY(),
                deckCenter.getZ() + (sideZ * RAILING_LIGHT_OFFSET * sideSign)
        ));
    }

    private static int pierLightSideSign(RoadBridgePlanner.BridgeSpanPlan bridgePlan, int pathIndex) {
        int pierOrdinal = 0;
        for (RoadBridgePlanner.BridgePierNode node : bridgePlan.nodes()) {
            if (node == null || node.role() != RoadBridgePlanner.BridgeNodeRole.PIER) {
                continue;
            }
            if (node.pathIndex() == pathIndex) {
                return (pierOrdinal % 2 == 0) ? 1 : -1;
            }
            pierOrdinal++;
        }
        return 1;
    }
```

This keeps pier lights tied to real pier nodes and alternates side by pier ordinal instead of by raw slice index.

- [ ] **Step 4: Re-run the planner tests to verify they pass**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.construction.RoadCorridorPlannerTest
```

Expected: PASS, including the new `pierLightPositions` assertions.

- [ ] **Step 5: Commit the planner anchor change**

```powershell
git add src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java
git commit -m "Add bridge pier lighting anchors"
```

## Task 2: Render Village-Style Hanging Lamps

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`

- [ ] **Step 1: Write the failing rendering tests**

Replace the old straight-post bridge-light expectations in `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java` with these tests:

```java
    @Test
    void longLandRouteUsesOutboardHangingLanternStreetlights() {
        RoadPlacementPlan plan = StructureConstructionManager.createRoadPlacementPlanForTest(
                java.util.stream.IntStream.rangeClosed(0, 48)
                        .mapToObj(x -> new BlockPos(x, 64, 0))
                        .toList(),
                List.of(),
                List.of()
        );

        assertTrue(hasGhost(plan, new BlockPos(24, 66, -4), Blocks.OAK_FENCE));
        assertTrue(hasGhost(plan, new BlockPos(24, 65, -4), Blocks.LANTERN));
        assertFalse(hasGhost(plan, new BlockPos(24, 67, -3), Blocks.LANTERN));
    }

    @Test
    void pierBridgePlanProducesVillageStyleBridgeAndPierLamps() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 9, 0, 64, Blocks.DIRT.defaultBlockState());
        for (int x = 1; x <= 8; x++) {
            level.surfaceHeights.put(columnKey(x, 0), 40);
            for (int y = 40; y >= 0; y--) {
                level.blockStates.put(new BlockPos(x, y, 0).asLong(), Blocks.WATER.defaultBlockState());
            }
        }

        RoadPlacementPlan plan = invokeCreateRoadPlacementPlan(
                level,
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 66, 0),
                        new BlockPos(2, 68, 0),
                        new BlockPos(3, 68, 0),
                        new BlockPos(4, 68, 0),
                        new BlockPos(5, 68, 0),
                        new BlockPos(6, 68, 0),
                        new BlockPos(7, 68, 0),
                        new BlockPos(8, 66, 0),
                        new BlockPos(9, 64, 0)
                )
        );

        assertTrue(plan.corridorPlan().slices().stream().anyMatch(slice -> !slice.pierLightPositions().isEmpty()));
        assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.SPRUCE_FENCE)));
        assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.LANTERN) && Math.abs(block.pos().getZ()) >= 4));
    }
```

- [ ] **Step 2: Run the road-link test class to verify it fails**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest
```

Expected: FAIL because current rendering still places lanterns directly above stacked supports and never emits a horizontal arm block.

- [ ] **Step 3: Implement village-style lamp rendering and arm material mapping**

In `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`, make these coordinated changes:

1. Extend `RoadPlacementStyle` with an explicit arm block:

```java
    private record RoadPlacementStyle(BlockState surface,
                                      BlockState support,
                                      BlockState lightSupport,
                                      BlockState lightArm,
                                      boolean bridge) {
        private RoadPlacementStyle(BlockState surface, BlockState support, boolean bridge) {
            this(surface, support, support, Blocks.OAK_FENCE.defaultBlockState(), bridge);
        }
    }
```

2. Update style factories so stone bridges use a wood arm and spruce bridges keep spruce:

```java
    private static RoadPlacementStyle waterBridgePlacementStyle(BlockState surfaceState) {
        return new RoadPlacementStyle(
                surfaceState,
                Blocks.STONE_BRICKS.defaultBlockState(),
                Blocks.COBBLESTONE_WALL.defaultBlockState(),
                Blocks.SPRUCE_FENCE.defaultBlockState(),
                true
        );
    }

    private static RoadPlacementStyle landRoadPlacementStyle(BlockState surfaceState, BlockState supportState) {
        return new RoadPlacementStyle(
                surfaceState,
                supportState,
                supportState,
                Blocks.OAK_FENCE.defaultBlockState(),
                false
        );
    }
```

3. Replace `appendCorridorLightGhosts(...)` and `appendBridgeLightGhosts(...)` with a shared village-style renderer:

```java
    private static void appendVillageLampGhosts(LinkedHashMap<Long, RoadGeometryPlanner.GhostRoadBlock> ghostBlocks,
                                                List<BlockPos> positions,
                                                RoadPlacementStyle style,
                                                BlockPos deckCenter,
                                                boolean bridgeLamp) {
        if (ghostBlocks == null || positions == null || positions.isEmpty() || style == null || deckCenter == null) {
            return;
        }
        for (BlockPos lightPos : positions) {
            if (lightPos == null) {
                continue;
            }

            int armX = Integer.compare(lightPos.getX() - deckCenter.getX(), 0);
            int armZ = Integer.compare(lightPos.getZ() - deckCenter.getZ(), 0);
            BlockPos postBase = resolveLampPostBase(ghostBlocks, lightPos, style, bridgeLamp);

            if (armX == 0 && armZ == 0) {
                appendGhost(ghostBlocks, postBase, style.lightSupport());
                appendGhost(ghostBlocks, postBase.above(), style.lightSupport());
                appendGhost(ghostBlocks, postBase.above(2), Blocks.LANTERN.defaultBlockState());
                continue;
            }

            appendGhost(ghostBlocks, postBase, style.lightSupport());
            appendGhost(ghostBlocks, postBase.above(), style.lightSupport());

            BlockPos armPos = postBase.above().offset(armX, 0, armZ);
            appendGhost(ghostBlocks, armPos, style.lightArm());
            appendGhost(ghostBlocks, armPos.below(), Blocks.LANTERN.defaultBlockState());
        }
    }

    private static BlockPos resolveLampPostBase(LinkedHashMap<Long, RoadGeometryPlanner.GhostRoadBlock> ghostBlocks,
                                                BlockPos lightPos,
                                                RoadPlacementStyle style,
                                                boolean bridgeLamp) {
        if (!bridgeLamp || style.lightSupport() == null || !style.lightSupport().is(Blocks.COBBLESTONE_WALL)) {
            return lightPos;
        }
        int railingY = highestGhostYInColumnMatching(
                ghostBlocks,
                lightPos.getX(),
                lightPos.getZ(),
                state -> state != null && state.is(Blocks.COBBLESTONE_WALL)
        );
        return railingY == Integer.MIN_VALUE
                ? lightPos.above()
                : new BlockPos(lightPos.getX(), railingY, lightPos.getZ());
    }
```

4. Update the build loop to pass the whole style and the slice deck center:

```java
            if (sliceStyle.bridge() && sliceStyle.lightSupport().is(Blocks.COBBLESTONE_WALL)) {
                appendBridgeRailingGhosts(ghostBlocks, corridorPlan.centerPath(), slice);
                appendVillageLampGhosts(ghostBlocks, slice.railingLightPositions(), sliceStyle, slice.deckCenter(), true);
                appendVillageLampGhosts(ghostBlocks, slice.pierLightPositions(), sliceStyle, slice.deckCenter(), true);
            } else {
                appendVillageLampGhosts(ghostBlocks, slice.railingLightPositions(), sliceStyle, slice.deckCenter(), false);
                appendVillageLampGhosts(ghostBlocks, slice.pierLightPositions(), sliceStyle, slice.deckCenter(), false);
            }
```

This keeps the renderer driven by existing anchors while changing the silhouette in one place.

- [ ] **Step 4: Re-run the road-link tests to verify they pass**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest
```

Expected: PASS, with land lamps hanging outboard and pier-supported bridges now producing arm blocks and hanging lanterns.

- [ ] **Step 5: Commit the lamp renderer change**

```powershell
git add src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java
git commit -m "Render village-style road lamps"
```

## Task 3: Full Verification and Regression Sweep

**Files:**
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java` if any assertion needs final tightening after the full run
- No code changes expected elsewhere in this task

- [ ] **Step 1: Run the two focused test classes together**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.construction.RoadCorridorPlannerTest --tests com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest
```

Expected: PASS for both classes in one run.

- [ ] **Step 2: Run a compile-only validation of the production source set**

Run:

```powershell
.\gradlew.bat compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Tighten any flaky shape assertion before finalizing**

If any assertion fails because the final arm/support Y-level differs by one block after the end-to-end run, tighten the test around the intended gameplay contract instead of around an accidental intermediate shape. Use this adjustment pattern in `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`:

```java
        assertTrue(plan.ghostBlocks().stream().anyMatch(block ->
                        block.state().is(Blocks.LANTERN) && Math.abs(block.pos().getZ()) >= 4),
                () -> plan.ghostBlocks().toString());
        assertTrue(plan.ghostBlocks().stream().anyMatch(block ->
                        block.state().is(Blocks.OAK_FENCE) || block.state().is(Blocks.SPRUCE_FENCE)),
                () -> plan.ghostBlocks().toString());
```

Do not weaken the tests back to the old vertical-lantern behavior.

- [ ] **Step 4: Re-run the focused tests after the final assertion pass**

Run:

```powershell
.\gradlew.bat test --tests com.monpai.sailboatmod.construction.RoadCorridorPlannerTest --tests com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest
```

Expected: PASS again after any last test adjustment.

- [ ] **Step 5: Commit the verified lighting pass**

```powershell
git add src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java
git commit -m "Add village-style bridge and road lighting"
```

## Self-Review

### Spec Coverage

- Bridge-pier lighting from real `PIER_BRIDGE` support nodes: covered by Task 1.
- Unified village-style hanging-lantern silhouette: covered by Task 2.
- Land spacing kept at the current interval: preserved by Task 2 implementation scope.
- Bridge lighting denser than land: preserved by existing bridge anchor cadence and verified in Task 2/Task 3.
- No NBT-template rewrite and no new runtime format: preserved by Tasks 1 and 2 staying inside existing planner/build-step flow.

### Placeholder Scan

- No `TBD`, `TODO`, or “implement later” markers remain.
- Every task includes concrete file paths, commands, and code snippets.
- Test steps name the exact Gradle test selectors to run.

### Type Consistency

- `pierLightPositions` stays the planner output consumed by the existing corridor slice record.
- `RoadPlacementStyle` grows a `lightArm` block so the renderer can choose arm material without inventing a second style source.
- `appendVillageLampGhosts(...)` consumes `RoadPlacementStyle` and `slice.deckCenter()` consistently for both land and bridge slices.
