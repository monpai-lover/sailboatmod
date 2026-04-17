# Dual Mode Water Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement dual-mode water bridges so short crossings prefer no-pier arch spans and long crossings upgrade to pier-driven elevated bridges with explicit approach ramps.

**Architecture:** Keep the existing outer road-planning flow based on `centerPath` and `bridgeRanges`, but replace the internal bridge model with typed bridge span plans. `RoadBridgePlanner` becomes the source of truth for bridge mode selection and structural nodes, `RoadGeometryPlanner` turns those plans into height profiles, `RoadCorridorPlanner` emits explicit slices from those plans, and `StructureConstructionManager` consumes the richer outputs for build, preview, and rollback.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, Gradle, JUnit 5

---

## File Structure

**Modify**

- `src/main/java/com/monpai/sailboatmod/construction/RoadBridgePlanner.java`
  - add bridge-mode planning records, bridge node roles, and bridge-span planning helpers
- `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`
  - consume typed bridge span plans and produce arch-vs-pier height profiles
- `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlan.java`
  - extend corridor slices with explicit bridge-mode metadata
- `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`
  - generate supports, segment kinds, and navigation metadata from typed bridge plans instead of inferred support indexes
- `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
  - replace bridge-profile plumbing with bridge-span-plan plumbing and keep construction behavior aligned with the chosen bridge mode

**Modify tests**

- `src/test/java/com/monpai/sailboatmod/construction/RoadBridgePlannerTest.java`
- `src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerTest.java`
- `src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java`
- `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`

The plan intentionally avoids adding new modules unless the existing files become unmanageable during implementation. The smallest safe implementation is to extend the current files in place and migrate callers incrementally.

### Task 1: Add Typed Bridge Span Planning In `RoadBridgePlanner`

**Files:**

- Modify: `src/test/java/com/monpai/sailboatmod/construction/RoadBridgePlannerTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadBridgePlanner.java`

- [ ] **Step 1: Write the failing planner tests for arch-span preference, pier escalation, and invalid pier chains**

```java
@Test
void plansShortWaterCrossingAsArchSpanWithoutInteriorPiers() {
    List<BlockPos> centerPath = List.of(
            new BlockPos(0, 64, 0),
            new BlockPos(1, 64, 0),
            new BlockPos(2, 64, 0),
            new BlockPos(3, 64, 0),
            new BlockPos(4, 64, 0)
    );

    RoadBridgePlanner.BridgeSpanPlan plan = RoadBridgePlanner.planBridgeSpanForTest(
            centerPath,
            new RoadPlacementPlan.BridgeRange(1, 3),
            index -> index >= 1 && index <= 3,
            index -> false,
            index -> 62,
            index -> 63,
            index -> true
    );

    assertEquals(RoadBridgePlanner.BridgeMode.ARCH_SPAN, plan.mode());
    assertEquals(List.of(1, 3), plan.nodes().stream().map(RoadBridgePlanner.BridgePierNode::pathIndex).toList());
    assertTrue(plan.nodes().stream().allMatch(node -> node.role() == RoadBridgePlanner.BridgeNodeRole.ABUTMENT));
    assertEquals(List.of(RoadBridgePlanner.BridgeDeckSegmentType.ARCHED_SPAN),
            plan.deckSegments().stream().map(RoadBridgePlanner.BridgeDeckSegment::type).toList());
}

@Test
void upgradesWideNavigableCrossingToPierBridgeWithChannelPiers() {
    List<BlockPos> centerPath = List.of(
            new BlockPos(0, 64, 0),
            new BlockPos(1, 64, 0),
            new BlockPos(2, 64, 0),
            new BlockPos(3, 64, 0),
            new BlockPos(4, 64, 0),
            new BlockPos(5, 64, 0),
            new BlockPos(6, 64, 0),
            new BlockPos(7, 64, 0),
            new BlockPos(8, 64, 0)
    );

    RoadBridgePlanner.BridgeSpanPlan plan = RoadBridgePlanner.planBridgeSpanForTest(
            centerPath,
            new RoadPlacementPlan.BridgeRange(1, 7),
            index -> index >= 1 && index <= 7,
            index -> index >= 3 && index <= 5,
            index -> 40,
            index -> 63,
            index -> true
    );

    assertEquals(RoadBridgePlanner.BridgeMode.PIER_BRIDGE, plan.mode());
    assertTrue(plan.nodes().stream().anyMatch(node -> node.role() == RoadBridgePlanner.BridgeNodeRole.PIER));
    assertEquals(2, plan.nodes().stream().filter(node -> node.role() == RoadBridgePlanner.BridgeNodeRole.CHANNEL_PIER).count());
    assertTrue(plan.deckSegments().stream().anyMatch(segment -> segment.type() == RoadBridgePlanner.BridgeDeckSegmentType.MAIN_LEVEL));
}

@Test
void marksPierBridgeInvalidWhenInteriorFoundationSlotsCannotSustainTheCrossing() {
    List<BlockPos> centerPath = List.of(
            new BlockPos(0, 64, 0),
            new BlockPos(1, 64, 0),
            new BlockPos(2, 64, 0),
            new BlockPos(3, 64, 0),
            new BlockPos(4, 64, 0),
            new BlockPos(5, 64, 0),
            new BlockPos(6, 64, 0),
            new BlockPos(7, 64, 0),
            new BlockPos(8, 64, 0)
    );

    RoadBridgePlanner.BridgeSpanPlan plan = RoadBridgePlanner.planBridgeSpanForTest(
            centerPath,
            new RoadPlacementPlan.BridgeRange(1, 7),
            index -> index >= 1 && index <= 7,
            index -> false,
            index -> 40,
            index -> 63,
            index -> index == 1 || index == 7
    );

    assertEquals(RoadBridgePlanner.BridgeMode.PIER_BRIDGE, plan.mode());
    assertFalse(plan.valid());
}
```

- [ ] **Step 2: Run the planner tests to verify they fail before implementation**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.construction.RoadBridgePlannerTest"`

Expected: FAIL with compile errors such as `cannot find symbol: class BridgeSpanPlan` or `cannot find symbol: method planBridgeSpanForTest(...)`.

- [ ] **Step 3: Implement the bridge span planning records and bridge-mode selection**

```java
public enum BridgeMode {
    NONE,
    ARCH_SPAN,
    PIER_BRIDGE
}

public enum BridgeNodeRole {
    ABUTMENT,
    PIER,
    CHANNEL_PIER
}

public enum BridgeDeckSegmentType {
    ARCHED_SPAN,
    APPROACH_UP,
    MAIN_LEVEL,
    APPROACH_DOWN
}

public record BridgePierNode(int pathIndex,
                             BlockPos worldPos,
                             BlockPos foundationPos,
                             int deckY,
                             BridgeNodeRole role) {
    public BridgePierNode {
        worldPos = Objects.requireNonNull(worldPos, "worldPos").immutable();
        foundationPos = Objects.requireNonNull(foundationPos, "foundationPos").immutable();
        role = Objects.requireNonNull(role, "role");
    }
}

public record BridgeDeckSegment(int startIndex,
                                int endIndex,
                                BridgeDeckSegmentType type,
                                int startDeckY,
                                int endDeckY) {
    public BridgeDeckSegment {
        if (startIndex < 0 || endIndex < startIndex) {
            throw new IllegalArgumentException("invalid bridge deck segment bounds");
        }
        type = Objects.requireNonNull(type, "type");
    }
}

public record BridgeSpanPlan(int startIndex,
                             int endIndex,
                             BridgeMode mode,
                             List<BridgePierNode> nodes,
                             List<BridgeDeckSegment> deckSegments,
                             int mainDeckY,
                             boolean navigableWaterBridge,
                             boolean valid) {
    public BridgeSpanPlan {
        if (startIndex < 0 || endIndex < startIndex) {
            throw new IllegalArgumentException("invalid bridge span bounds");
        }
        mode = mode == null ? BridgeMode.NONE : mode;
        nodes = List.copyOf(nodes == null ? List.of() : nodes);
        deckSegments = List.copyOf(deckSegments == null ? List.of() : deckSegments);
        mainDeckY = Math.max(0, mainDeckY);
    }
}
```

```java
public static BridgeSpanPlan planBridgeSpan(List<BlockPos> centerPath,
                                            RoadPlacementPlan.BridgeRange range,
                                            IntPredicate unsupportedAt,
                                            IntPredicate navigableAt,
                                            IntUnaryOperator terrainYAt,
                                            IntUnaryOperator waterSurfaceYAt,
                                            IntPredicate foundationSupportedAt) {
    Objects.requireNonNull(centerPath, "centerPath");
    Objects.requireNonNull(range, "range");
    Objects.requireNonNull(unsupportedAt, "unsupportedAt");
    Objects.requireNonNull(navigableAt, "navigableAt");
    Objects.requireNonNull(terrainYAt, "terrainYAt");
    Objects.requireNonNull(waterSurfaceYAt, "waterSurfaceYAt");
    Objects.requireNonNull(foundationSupportedAt, "foundationSupportedAt");

    int start = Math.max(0, range.startIndex());
    int end = Math.min(centerPath.size() - 1, range.endIndex());
    if (end < start) {
        return new BridgeSpanPlan(0, 0, BridgeMode.NONE, List.of(), List.of(), 0, false, false);
    }
    if (canUseArchSpan(start, end, unsupportedAt, terrainYAt)) {
        return buildArchSpan(centerPath, start, end, terrainYAt);
    }
    return buildPierBridge(centerPath, start, end, navigableAt, terrainYAt, waterSurfaceYAt, foundationSupportedAt);
}

static BridgeSpanPlan planBridgeSpanForTest(List<BlockPos> centerPath,
                                            RoadPlacementPlan.BridgeRange range,
                                            IntPredicate unsupportedAt,
                                            IntPredicate navigableAt,
                                            IntUnaryOperator terrainYAt,
                                            IntUnaryOperator waterSurfaceYAt,
                                            IntPredicate foundationSupportedAt) {
    return planBridgeSpan(centerPath, range, unsupportedAt, navigableAt, terrainYAt, waterSurfaceYAt, foundationSupportedAt);
}
```

Use these rules inside `buildArchSpan(...)` and `buildPierBridge(...)`:

- `ARCH_SPAN`
  - emit only two `ABUTMENT` nodes
  - emit exactly one `ARCHED_SPAN` segment
  - keep `valid = true`
- `PIER_BRIDGE`
  - emit two `ABUTMENT` nodes plus distributed interior `PIER` nodes
  - add `CHANNEL_PIER` nodes on both sides of a contiguous navigable run when that run exists
  - compute `mainDeckY` as `max(waterSurfaceY + NAVIGABLE_WATER_CLEARANCE, landApproachRequirementY)`
  - emit `APPROACH_UP`, `MAIN_LEVEL`, and `APPROACH_DOWN` segments
  - if no valid interior support chain can be formed, keep the mode as `PIER_BRIDGE` but set `valid = false`

Keep `BridgeProfile` temporarily, but make `classifyRanges(...)` an adapter over the new span-plan logic so existing callers keep compiling until later tasks switch over.

- [ ] **Step 4: Run the planner tests again to verify the new planning API passes**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.construction.RoadBridgePlannerTest"`

Expected: PASS

- [ ] **Step 5: Commit the bridge span planning task**

```bash
git add src/main/java/com/monpai/sailboatmod/construction/RoadBridgePlanner.java src/test/java/com/monpai/sailboatmod/construction/RoadBridgePlannerTest.java
git commit -m "Add dual mode bridge span planning"
```

### Task 2: Teach `RoadGeometryPlanner` To Consume Bridge Span Plans

**Files:**

- Modify: `src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`

- [ ] **Step 1: Write failing geometry tests for arch-span cresting and pier-bridge ramps**

```java
@Test
void placementProfileUsesSingleCrestForArchSpanPlans() {
    List<BlockPos> centerPath = List.of(
            new BlockPos(0, 64, 0),
            new BlockPos(1, 64, 0),
            new BlockPos(2, 64, 0),
            new BlockPos(3, 64, 0),
            new BlockPos(4, 64, 0)
    );
    List<RoadBridgePlanner.BridgeSpanPlan> plans = List.of(
            new RoadBridgePlanner.BridgeSpanPlan(
                    1,
                    3,
                    RoadBridgePlanner.BridgeMode.ARCH_SPAN,
                    List.of(
                            new RoadBridgePlanner.BridgePierNode(1, new BlockPos(1, 64, 0), new BlockPos(1, 62, 0), 65, RoadBridgePlanner.BridgeNodeRole.ABUTMENT),
                            new RoadBridgePlanner.BridgePierNode(3, new BlockPos(3, 64, 0), new BlockPos(3, 62, 0), 65, RoadBridgePlanner.BridgeNodeRole.ABUTMENT)
                    ),
                    List.of(new RoadBridgePlanner.BridgeDeckSegment(1, 3, RoadBridgePlanner.BridgeDeckSegmentType.ARCHED_SPAN, 65, 65)),
                    65,
                    false,
                    true
            )
    );

    int[] heights = RoadGeometryPlanner.buildPlacementHeightProfile(centerPath, plans);

    assertEquals(64, heights[0]);
    assertEquals(65, heights[1]);
    assertTrue(heights[2] > heights[1]);
    assertEquals(65, heights[3]);
    assertEquals(64, heights[4]);
}

@Test
void placementProfileUsesLevelMainSpanForPierBridgePlans() {
    List<BlockPos> centerPath = List.of(
            new BlockPos(0, 64, 0),
            new BlockPos(1, 64, 0),
            new BlockPos(2, 64, 0),
            new BlockPos(3, 64, 0),
            new BlockPos(4, 64, 0),
            new BlockPos(5, 64, 0),
            new BlockPos(6, 64, 0)
    );
    List<RoadBridgePlanner.BridgeSpanPlan> plans = List.of(
            new RoadBridgePlanner.BridgeSpanPlan(
                    1,
                    5,
                    RoadBridgePlanner.BridgeMode.PIER_BRIDGE,
                    List.of(),
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

    int[] heights = RoadGeometryPlanner.buildPlacementHeightProfile(centerPath, plans);

    assertEquals(66, heights[1]);
    assertEquals(68, heights[2]);
    assertEquals(68, heights[3]);
    assertEquals(68, heights[4]);
    assertEquals(66, heights[5]);
}
```

- [ ] **Step 2: Run the geometry tests to verify they fail before implementation**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.construction.RoadGeometryPlannerTest"`

Expected: FAIL with compile errors because `buildPlacementHeightProfile(List<BlockPos>, List<BridgeSpanPlan>)` does not exist yet.

- [ ] **Step 3: Add a bridge-span-plan height-profile overload and migrate bridge shaping into it**

```java
public static int[] buildPlacementHeightProfile(List<BlockPos> centerPath,
                                                List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans) {
    Objects.requireNonNull(centerPath, "centerPath");
    Objects.requireNonNull(bridgePlans, "bridgePlans");
    if (centerPath.isEmpty()) {
        return new int[0];
    }
    int[] sampledHeights = samplePlacementHeights(centerPath);
    int[] smoothed = smoothPlacementHeights(sampledHeights);
    int[] adjusted = smoothed.clone();
    for (RoadBridgePlanner.BridgeSpanPlan plan : bridgePlans) {
        if (plan == null || !plan.valid()) {
            continue;
        }
        applyBridgeSpanPlan(adjusted, sampledHeights, plan);
    }
    return constrainToTerrainEnvelope(adjusted, sampledHeights, bridgePlans);
}

private static void applyBridgeSpanPlan(int[] adjusted,
                                        int[] sampledHeights,
                                        RoadBridgePlanner.BridgeSpanPlan plan) {
    if (plan.mode() == RoadBridgePlanner.BridgeMode.ARCH_SPAN) {
        applyArchDeckSegment(adjusted, plan);
        return;
    }
    for (RoadBridgePlanner.BridgeDeckSegment segment : plan.deckSegments()) {
        switch (segment.type()) {
            case APPROACH_UP, APPROACH_DOWN -> applyLinearDeckSegment(adjusted, segment);
            case MAIN_LEVEL -> applyLevelDeckSegment(adjusted, segment, plan.mainDeckY());
            case ARCHED_SPAN -> applyArchDeckSegment(adjusted, plan);
        }
    }
}

public static int[] buildPlacementHeightProfile(List<BlockPos> centerPath,
                                                List<RoadBridgePlanner.BridgeProfile> bridgeProfiles) {
    List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans = bridgeProfiles.stream()
            .filter(Objects::nonNull)
            .map(RoadGeometryPlanner::legacyPlanFromProfile)
            .toList();
    return buildPlacementHeightProfile(centerPath, bridgePlans);
}
```

Implementation rules:

- `ARCH_SPAN`
  - preserve the bridgehead deck at the segment ends
  - raise the midpoint to a single crest
  - avoid a flat plateau unless the span is too short to form a visible crest
- `PIER_BRIDGE`
  - approach segments interpolate between land and `mainDeckY`
  - `MAIN_LEVEL` keeps a constant bridge deck height across the elevated span
  - invalid plans are ignored so broken bridge plans cannot poison land-road geometry

- [ ] **Step 4: Run the geometry tests again to verify the new height-profile behavior**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.construction.RoadGeometryPlannerTest"`

Expected: PASS

- [ ] **Step 5: Commit the geometry-planning task**

```bash
git add src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerTest.java
git commit -m "Add dual mode bridge geometry profiles"
```

### Task 3: Move `RoadCorridorPlanner` And `RoadCorridorPlan` To Explicit Bridge Modes

**Files:**

- Modify: `src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlan.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`

- [ ] **Step 1: Write failing corridor tests for arch spans, explicit pier supports, and bridge-mode metadata**

```java
@Test
void archSpanSlicesCarryArchModeAndNeverEmitSupportColumns() {
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

    RoadCorridorPlan plan = RoadCorridorPlanner.plan(centerPath, bridgePlans, new int[] {64, 65, 67, 65, 64});

    assertEquals(RoadBridgePlanner.BridgeMode.ARCH_SPAN, plan.slices().get(2).bridgeMode());
    assertTrue(plan.slices().stream().allMatch(slice -> slice.supportPositions().isEmpty()));
}

@Test
void pierBridgeSlicesEmitSupportOnlyAtExplicitPierNodeIndexes() {
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

    RoadCorridorPlan plan = RoadCorridorPlanner.plan(centerPath, bridgePlans, new int[] {64, 66, 68, 68, 68, 66, 64});

    assertFalse(plan.slices().get(2).supportPositions().isEmpty());
    assertTrue(plan.slices().get(3).supportPositions().isEmpty());
    assertFalse(plan.slices().get(4).supportPositions().isEmpty());
    assertEquals(RoadBridgePlanner.BridgeMode.PIER_BRIDGE, plan.slices().get(3).bridgeMode());
}
```

- [ ] **Step 2: Run the corridor tests to verify they fail before implementation**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.construction.RoadCorridorPlannerTest"`

Expected: FAIL with compile errors because `CorridorSlice.bridgeMode()` and `RoadCorridorPlanner.plan(...bridgePlans...)` do not exist yet.

- [ ] **Step 3: Extend the corridor model and planner to use explicit bridge plans**

```java
public record CorridorSlice(int index,
                            BlockPos deckCenter,
                            SegmentKind segmentKind,
                            RoadBridgePlanner.BridgeMode bridgeMode,
                            List<BlockPos> surfacePositions,
                            List<BlockPos> excavationPositions,
                            List<BlockPos> clearancePositions,
                            List<BlockPos> railingLightPositions,
                            List<BlockPos> supportPositions,
                            List<BlockPos> pierLightPositions) {
    public CorridorSlice {
        bridgeMode = bridgeMode == null ? RoadBridgePlanner.BridgeMode.NONE : bridgeMode;
        deckCenter = immutable(Objects.requireNonNull(deckCenter, "deckCenter"));
        segmentKind = Objects.requireNonNull(segmentKind, "segmentKind");
        surfacePositions = copyOptionalPositions(surfacePositions, "surfacePositions");
        excavationPositions = copyOptionalPositions(excavationPositions, "excavationPositions");
        clearancePositions = copyOptionalPositions(clearancePositions, "clearancePositions");
        railingLightPositions = copyOptionalPositions(railingLightPositions, "railingLightPositions");
        supportPositions = copyOptionalPositions(supportPositions, "supportPositions");
        pierLightPositions = copyOptionalPositions(pierLightPositions, "pierLightPositions");
    }
}
```

```java
public static RoadCorridorPlan plan(List<BlockPos> centerPath,
                                    List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans,
                                    int[] placementHeights) {
    Objects.requireNonNull(centerPath, "centerPath");
    Objects.requireNonNull(bridgePlans, "bridgePlans");
    Objects.requireNonNull(placementHeights, "placementHeights");

    Map<Integer, RoadBridgePlanner.BridgeSpanPlan> planByIndex = expandBridgePlans(bridgePlans, centerPath.size());
    Map<Integer, RoadBridgePlanner.BridgePierNode> supportNodeByIndex = pierSupportNodesByIndex(bridgePlans);
    List<CorridorSlice> slices = new ArrayList<>(centerPath.size());
    for (int i = 0; i < centerPath.size(); i++) {
        RoadBridgePlanner.BridgeSpanPlan bridgePlan = planByIndex.get(i);
        RoadBridgePlanner.BridgeMode bridgeMode = bridgePlan == null ? RoadBridgePlanner.BridgeMode.NONE : bridgePlan.mode();
        RoadCorridorPlan.SegmentKind segmentKind = classifySegmentKind(i, centerPath, placementHeights, bridgePlan);
        BlockPos deckCenter = new BlockPos(centerPath.get(i).getX(), placementHeights[i], centerPath.get(i).getZ());
        List<BlockPos> surfacePositions = buildSurfacePositions(centerPath, i, placementHeights);
        List<BlockPos> supportPositions = bridgeMode == RoadBridgePlanner.BridgeMode.PIER_BRIDGE && supportNodeByIndex.containsKey(i)
                ? buildSupportPositions(supportNodeByIndex.get(i).foundationPos(), placementHeights[i])
                : List.of();
        slices.add(new CorridorSlice(
                i,
                deckCenter,
                segmentKind,
                bridgeMode,
                surfacePositions,
                buildExcavationPositions(surfacePositions, segmentKind),
                buildClearancePositions(surfacePositions, segmentKind),
                buildRailingLightPositions(centerPath, i, deckCenter, segmentKind),
                supportPositions,
                supportPositions.isEmpty() ? List.of() : buildBridgeEdgeMarkerPositions(centerPath, i, deckCenter)
        ));
    }
    return new RoadCorridorPlan(centerPath, slices, buildNavigationChannelFromPlans(bridgePlans, placementHeights, centerPath), true);
}
```

Implementation rules:

- `ARCH_SPAN`
  - mark bridge slices with `bridgeMode = ARCH_SPAN`
  - never emit interior `supportPositions`
  - keep navigation metadata absent unless the chosen span is explicitly navigable later
- `PIER_BRIDGE`
  - emit supports only for slices that carry explicit interior `PIER` or `CHANNEL_PIER` nodes
  - keep `ABUTMENT` slices support-free
  - retain existing lighting behavior, but derive it from the node-driven bridge slices

Keep the old `plan(centerPath, bridgeRanges, navigableWaterBridgeRanges, placementHeights)` overload temporarily and adapt it by building legacy bridge span plans inside the method, so `StructureConstructionManager` can switch in the next task without a giant one-shot diff.

- [ ] **Step 4: Run the corridor tests again to verify explicit bridge-mode slices**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.construction.RoadCorridorPlannerTest"`

Expected: PASS

- [ ] **Step 5: Commit the corridor-planning task**

```bash
git add src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlan.java src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java
git commit -m "Switch corridor planning to explicit bridge modes"
```

### Task 4: Integrate Dual-Mode Bridges Into `StructureConstructionManager`

**Files:**

- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`

- [ ] **Step 1: Write failing integration tests for short arch spans and long pier bridges**

```java
@Test
void shortWaterCrossingProducesArchModeWithoutSupportColumns() {
    RoadPlacementPlan plan = StructureConstructionManager.createRoadPlacementPlanForTest(
            List.of(
                    new BlockPos(0, 64, 0),
                    new BlockPos(1, 65, 0),
                    new BlockPos(2, 67, 0),
                    new BlockPos(3, 65, 0),
                    new BlockPos(4, 64, 0)
            ),
            List.of(new RoadPlacementPlan.BridgeRange(1, 3)),
            List.of()
    );

    assertTrue(plan.corridorPlan().slices().stream()
            .filter(slice -> slice.bridgeMode() == RoadBridgePlanner.BridgeMode.ARCH_SPAN)
            .allMatch(slice -> slice.supportPositions().isEmpty()));
}

@Test
void longWaterCrossingProducesPierModeAndFoundationToDeckSupportGhosts() {
    TestServerLevel level = allocate(TestServerLevel.class);
    level.blockStates = new HashMap<>();
    level.surfaceHeights = new HashMap<>();
    level.biome = Holder.direct(allocate(Biome.class));

    setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
    setSurfaceColumn(level, 9, 0, 64, Blocks.DIRT.defaultBlockState());
    for (int x = 1; x <= 8; x++) {
        setSurfaceColumn(level, x, 0, 40, Blocks.WATER.defaultBlockState());
        level.blockStates.put(new BlockPos(x, 39, 0).asLong(), Blocks.STONE.defaultBlockState());
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

    assertTrue(plan.corridorPlan().slices().stream().anyMatch(slice -> slice.bridgeMode() == RoadBridgePlanner.BridgeMode.PIER_BRIDGE));
    assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.STONE_BRICKS) && block.pos().getY() == 41));
}
```

- [ ] **Step 2: Run the structure-construction integration tests to verify they fail first**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest"`

Expected: FAIL because `StructureConstructionManager` still builds around `BridgeProfile` and corridor slices do not yet expose stable dual-mode bridge behavior.

- [ ] **Step 3: Switch bridge integration in `StructureConstructionManager` from `BridgeProfile` to `BridgeSpanPlan`**

```java
private static RoadCorridorPlan createRoadCorridorPlan(ServerLevel level,
                                                       List<BlockPos> centerPath,
                                                       List<RoadPlacementPlan.BridgeRange> bridgeRanges,
                                                       List<RoadPlacementPlan.BridgeRange> navigableWaterBridgeRanges) {
    if (centerPath == null) {
        return RoadCorridorPlanner.plan(List.of());
    }
    List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans = planBridgeSpans(level, centerPath, bridgeRanges, navigableWaterBridgeRanges);
    int[] placementHeights = RoadGeometryPlanner.buildPlacementHeightProfile(centerPath, bridgePlans);
    placementHeights = surfaceReplacementPlacementHeights(centerPath, placementHeights, bridgeRanges);
    return RoadCorridorPlanner.plan(centerPath, bridgePlans, placementHeights);
}
```

```java
private static List<RoadBridgePlanner.BridgeSpanPlan> planBridgeSpans(ServerLevel level,
                                                                      List<BlockPos> centerPath,
                                                                      List<RoadPlacementPlan.BridgeRange> bridgeRanges,
                                                                      List<RoadPlacementPlan.BridgeRange> navigableWaterBridgeRanges) {
    if (level == null || centerPath == null || centerPath.isEmpty() || bridgeRanges == null || bridgeRanges.isEmpty()) {
        return List.of();
    }
    List<RoadBridgePlanner.BridgeSpanPlan> plans = new ArrayList<>(bridgeRanges.size());
    for (RoadPlacementPlan.BridgeRange range : bridgeRanges) {
        if (range == null) {
            continue;
        }
        plans.add(RoadBridgePlanner.planBridgeSpan(
                centerPath,
                range,
                index -> selectRoadPlacementStyle(level, centerPath.get(index).above()).bridge(),
                index -> isNavigableIndex(index, navigableWaterBridgeRanges),
                index -> level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, centerPath.get(index)).below().getY(),
                index -> Math.max(0, findColumnWaterSurfaceY(level, centerPath.get(index).getX(), centerPath.get(index).getZ())),
                index -> hasStableFoundationBelow(level, centerPath.get(index))
        ));
    }
    return List.copyOf(plans);
}
```

Implementation rules:

- preserve the existing `detectBridgeRanges(...)` and `detectNavigableWaterBridgeRanges(...)` entry behavior
- replace `classifyBridgeProfiles(...)` call sites with `planBridgeSpans(...)`
- ensure short crossings remain support-free in both `corridorPlan` and `ghostBlocks`
- ensure long crossings still materialize full foundation-to-deck pier columns
- keep rollback ownership intact by continuing to source build steps from the geometry and corridor plans rather than introducing ad hoc placement

- [ ] **Step 4: Run the structure-construction integration tests again**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest"`

Expected: PASS

- [ ] **Step 5: Commit the integration task**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java
git commit -m "Integrate dual mode bridge construction"
```

### Task 5: Run The Targeted Verification Suite And Lock The Behavior

**Files:**

- Modify: `docs/superpowers/plans/2026-04-14-dual-mode-water-bridge.md`

- [ ] **Step 1: Run the focused bridge-planning and construction test suite**

Run:

```powershell
.\gradlew.bat test `
  --tests "com.monpai.sailboatmod.construction.RoadBridgePlannerTest" `
  --tests "com.monpai.sailboatmod.construction.RoadGeometryPlannerTest" `
  --tests "com.monpai.sailboatmod.construction.RoadCorridorPlannerTest" `
  --tests "com.monpai.sailboatmod.nation.service.StructureConstructionManagerRoadLinkTest"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Run a compile-only pass for the full mod**

Run: `.\gradlew.bat compileJava`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Perform manual bridge spot checks in a dev client**

Run: `.\gradlew.bat runClient`

Verify all of the following in-world:

- a short stream crossing produces an arch bridge with no middle pier
- a wide water crossing produces a pier-supported elevated bridge
- the main elevated span stays mostly level
- shore transitions climb and descend through visible ramp segments
- cancelling construction removes both arch-bridge and pier-bridge artifacts

- [ ] **Step 4: Update the plan checklist with actual command outcomes**

```markdown
- [x] Focused bridge suite passes on local machine
- [x] `compileJava` passes on local machine
- [x] Manual short-span arch bridge spot check complete
- [x] Manual long-span pier bridge spot check complete
```

- [ ] **Step 5: Commit the verified implementation**

```bash
git add src/main/java src/test/java docs/superpowers/plans/2026-04-14-dual-mode-water-bridge.md
git commit -m "Implement dual mode water bridges"
```
