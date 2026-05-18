# Road Bridge Tree Footprint Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair tree-aware terrain sampling, continuous road/bridge footprints, bridge height tiers, and bridge ramp connectivity without changing auto-complete pathfinding.

**Architecture:** Keep pathfinding untouched and fix the structure layer after centerline/spans already exist. Add a small footprint rasterizer that converts centerline segments into continuous road bands, update bridge height/ramp profile generation, and feed the rasterized footprints into the existing build-step emitters.

**Tech Stack:** Java 17, Forge 1.20.1, JUnit 5, Minecraft block registries/tags, existing `roadplanner.structure` and `road.pathfinding.cache` packages.

---

## File Structure

- Modify `src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/RoadSurfaceHeuristics.java`
  - Responsibility: decide whether a block is terrain-bearing or natural/tree noise for road sampling.
- Create `src/test/java/com/monpai/sailboatmod/road/pathfinding/cache/RoadSurfaceHeuristicsTest.java`
  - Responsibility: lock tree/vegetation sampling semantics.
- Create `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadBandRasterizer.java`
  - Responsibility: convert centerline + width into continuous per-index road/bridge surface footprints.
- Create `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadBandRasterizerTest.java`
  - Responsibility: prove turns, diagonal segments, and lateral movement do not leave gaps.
- Modify `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeGeometryPlanner.java`
  - Responsibility: generate tiered bridge height profiles and buildable ramp/deck phases.
- Modify `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeGeometryPlannerTest.java`
  - Responsibility: lock lower ordinary bridge heights, high navigable/very long bridge heights, and buildable ramp deltas.
- Modify `src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeStructureEmitter.java`
  - Responsibility: use rasterized bridge footprints, choose ramp/deck block states, and keep piers on deck/support spans.
- Modify `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadSurfaceStepEmitter.java`
  - Responsibility: use rasterized road footprints so turns and lateral offsets are filled for road spans too.
- Modify `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java`
  - Responsibility: integration tests for bridge ramp connectivity, lower ordinary pier bridges, and turn footprint fill.

Do not modify these pathfinding implementation files for this work:

- `src/main/java/com/monpai/sailboatmod/road/pathfinding/impl/*`
- `src/main/java/com/monpai/sailboatmod/roadplanner/postprocess/RoadPathPostProcessor.java`
- `src/main/java/com/monpai/sailboatmod/road/pathfinding/cost/*`

---

### Task 1: Tree-Aware Terrain Sampling

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/RoadSurfaceHeuristics.java`
- Create: `src/test/java/com/monpai/sailboatmod/road/pathfinding/cache/RoadSurfaceHeuristicsTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/monpai/sailboatmod/road/pathfinding/cache/RoadSurfaceHeuristicsTest.java`:

```java
package com.monpai.sailboatmod.road.pathfinding.cache;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadSurfaceHeuristicsTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void ignoresTreeAndNaturalNoiseBlocks() {
        assertTrue(RoadSurfaceHeuristics.isIgnoredSurfaceNoise(Blocks.OAK_LOG.defaultBlockState()));
        assertTrue(RoadSurfaceHeuristics.isIgnoredSurfaceNoise(Blocks.OAK_WOOD.defaultBlockState()));
        assertTrue(RoadSurfaceHeuristics.isIgnoredSurfaceNoise(Blocks.OAK_LEAVES.defaultBlockState()));
        assertTrue(RoadSurfaceHeuristics.isIgnoredSurfaceNoise(Blocks.CRIMSON_STEM.defaultBlockState()));
        assertTrue(RoadSurfaceHeuristics.isIgnoredSurfaceNoise(Blocks.WARPED_HYPHAE.defaultBlockState()));
        assertTrue(RoadSurfaceHeuristics.isIgnoredSurfaceNoise(Blocks.MUSHROOM_STEM.defaultBlockState()));
        assertTrue(RoadSurfaceHeuristics.isIgnoredSurfaceNoise(Blocks.BAMBOO.defaultBlockState()));
        assertTrue(RoadSurfaceHeuristics.isIgnoredSurfaceNoise(Blocks.KELP.defaultBlockState()));
        assertTrue(RoadSurfaceHeuristics.isIgnoredSurfaceNoise(Blocks.SEAGRASS.defaultBlockState()));
    }

    @Test
    void treeBlocksAreNotRoadBearingSurfaces() {
        assertFalse(RoadSurfaceHeuristics.isRoadBearingSurface(Blocks.OAK_LOG.defaultBlockState()));
        assertFalse(RoadSurfaceHeuristics.isRoadBearingSurface(Blocks.OAK_LEAVES.defaultBlockState()));
        assertFalse(RoadSurfaceHeuristics.isRoadBearingSurface(Blocks.KELP.defaultBlockState()));
        assertTrue(RoadSurfaceHeuristics.isRoadBearingSurface(Blocks.GRASS_BLOCK.defaultBlockState()));
        assertTrue(RoadSurfaceHeuristics.isRoadBearingSurface(Blocks.STONE.defaultBlockState()));
    }
}
```

- [ ] **Step 2: Run the tests and verify failure**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.road.pathfinding.cache.RoadSurfaceHeuristicsTest"
```

Expected: at least one assertion fails for water plants or registry-name fallback coverage.

- [ ] **Step 3: Implement the heuristic extension**

In `RoadSurfaceHeuristics.java`, add imports:

```java
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
```

Replace `isIgnoredSurfaceNoise` with:

```java
public static boolean isIgnoredSurfaceNoise(BlockState state) {
    if (state == null || state.isAir()) {
        return false;
    }
    Block block = state.getBlock();
    return state.is(BlockTags.LEAVES)
            || state.is(BlockTags.LOGS)
            || block instanceof LeavesBlock
            || hasNaturalNoiseName(state)
            || state.is(BlockTags.FLOWERS)
            || state.is(BlockTags.SAPLINGS)
            || state.is(BlockTags.TALL_FLOWERS)
            || state.is(Blocks.TALL_GRASS)
            || state.is(Blocks.GRASS)
            || state.is(Blocks.FERN)
            || state.is(Blocks.LARGE_FERN)
            || state.is(Blocks.VINE)
            || state.is(Blocks.WEEPING_VINES)
            || state.is(Blocks.WEEPING_VINES_PLANT)
            || state.is(Blocks.TWISTING_VINES)
            || state.is(Blocks.TWISTING_VINES_PLANT)
            || state.is(Blocks.DEAD_BUSH)
            || state.is(Blocks.SNOW)
            || state.is(Blocks.BAMBOO)
            || state.is(Blocks.BAMBOO_SAPLING)
            || state.is(Blocks.SUGAR_CANE)
            || state.is(Blocks.SWEET_BERRY_BUSH)
            || state.is(Blocks.CACTUS)
            || state.is(Blocks.MUSHROOM_STEM)
            || state.is(Blocks.RED_MUSHROOM_BLOCK)
            || state.is(Blocks.BROWN_MUSHROOM_BLOCK)
            || state.is(Blocks.RED_MUSHROOM)
            || state.is(Blocks.BROWN_MUSHROOM)
            || state.is(Blocks.BIG_DRIPLEAF)
            || state.is(Blocks.BIG_DRIPLEAF_STEM)
            || state.is(Blocks.SMALL_DRIPLEAF)
            || state.is(Blocks.CAVE_VINES)
            || state.is(Blocks.CAVE_VINES_PLANT)
            || state.is(Blocks.HANGING_ROOTS)
            || state.is(Blocks.MOSS_CARPET)
            || state.is(Blocks.SPORE_BLOSSOM)
            || state.is(Blocks.GLOW_LICHEN)
            || state.is(Blocks.SCULK_VEIN)
            || state.is(Blocks.MANGROVE_ROOTS)
            || state.is(Blocks.MANGROVE_PROPAGULE)
            || state.is(Blocks.AZALEA)
            || state.is(Blocks.FLOWERING_AZALEA)
            || state.is(Blocks.KELP)
            || state.is(Blocks.KELP_PLANT)
            || state.is(Blocks.SEAGRASS)
            || state.is(Blocks.TALL_SEAGRASS)
            || state.is(Blocks.SEA_PICKLE)
            || state.is(BlockTags.REPLACEABLE);
}
```

Add this helper below `isIgnoredSurfaceNoise`:

```java
private static boolean hasNaturalNoiseName(BlockState state) {
    if (state == null || state.getBlock() == null) {
        return false;
    }
    String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    return path.endsWith("_leaves")
            || path.endsWith("_log")
            || path.endsWith("_wood")
            || path.endsWith("_stem")
            || path.endsWith("_hyphae");
}
```

- [ ] **Step 4: Run the focused tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.road.pathfinding.cache.RoadSurfaceHeuristicsTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/RoadSurfaceHeuristics.java src/test/java/com/monpai/sailboatmod/road/pathfinding/cache/RoadSurfaceHeuristicsTest.java
git commit -m "Fix road terrain tree sampling"
```

---

### Task 2: Continuous Road Band Rasterizer

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadBandRasterizer.java`
- Create: `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadBandRasterizerTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadBandRasterizerTest.java`:

```java
package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadBandRasterizerTest {
    @Test
    void turnFootprintFillsInsideCornerSweptArea() {
        List<RoadCenterlinePoint> centerline = List.of(
                point(0, 64, 0, 0),
                point(4, 64, 0, 4),
                point(4, 64, 4, 8)
        );

        List<List<BlockPos>> byIndex = RoadBandRasterizer.surfacePositionsByIndex(centerline, 5);
        Set<BlockPos> all = flatten(byIndex);

        assertTrue(all.contains(new BlockPos(3, 64, 1)), all.toString());
        assertTrue(all.contains(new BlockPos(4, 64, 2)), all.toString());
        assertFalse(byIndex.stream().anyMatch(List::isEmpty), byIndex.toString());
    }

    @Test
    void lateralMovementFootprintContainsSweptBlocksBetweenCenters() {
        List<RoadCenterlinePoint> centerline = List.of(
                point(0, 64, 0, 0),
                point(4, 64, 2, 4),
                point(8, 64, 2, 8)
        );

        Set<BlockPos> all = flatten(RoadBandRasterizer.surfacePositionsByIndex(centerline, 5));

        assertTrue(all.contains(new BlockPos(2, 64, 1)), all.toString());
        assertTrue(all.contains(new BlockPos(3, 64, 2)), all.toString());
        assertTrue(all.contains(new BlockPos(5, 64, 2)), all.toString());
    }

    @Test
    void rasterizedAdjacentBucketsTouchOrOverlap() {
        List<RoadCenterlinePoint> centerline = List.of(
                point(0, 64, 0, 0),
                point(4, 65, 0, 4),
                point(4, 66, 4, 8),
                point(8, 66, 4, 12)
        );

        List<List<BlockPos>> byIndex = RoadBandRasterizer.surfacePositionsByIndex(centerline, 5);

        for (int i = 1; i < byIndex.size(); i++) {
            assertTrue(touchesOrOverlaps(byIndex.get(i - 1), byIndex.get(i)),
                    "bucket " + (i - 1) + " -> " + i + " disconnected: " + byIndex);
        }
    }

    private static RoadCenterlinePoint point(int x, int y, int z, double dist) {
        return new RoadCenterlinePoint(new BlockPos(x, y, z), 0, RoadPlannerSegmentType.ROAD, y, y, dist);
    }

    private static Set<BlockPos> flatten(List<List<BlockPos>> byIndex) {
        Set<BlockPos> out = new HashSet<>();
        for (List<BlockPos> positions : byIndex) {
            out.addAll(positions);
        }
        return out;
    }

    private static boolean touchesOrOverlaps(List<BlockPos> first, List<BlockPos> second) {
        for (BlockPos left : first) {
            for (BlockPos right : second) {
                int dx = Math.abs(left.getX() - right.getX());
                int dy = Math.abs(left.getY() - right.getY());
                int dz = Math.abs(left.getZ() - right.getZ());
                if (dx + dy + dz <= 1) {
                    return true;
                }
            }
        }
        return false;
    }
}
```

- [ ] **Step 2: Run the tests and verify failure**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.roadplanner.structure.RoadBandRasterizerTest"
```

Expected: compilation fails because `RoadBandRasterizer` does not exist.

- [ ] **Step 3: Implement the rasterizer**

Create `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadBandRasterizer.java`:

```java
package com.monpai.sailboatmod.roadplanner.structure;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

final class RoadBandRasterizer {
    private RoadBandRasterizer() {
    }

    static List<List<BlockPos>> surfacePositionsByIndex(List<RoadCenterlinePoint> centerline, int width) {
        if (centerline == null || centerline.isEmpty()) {
            return List.of();
        }
        ArrayList<LinkedHashSet<BlockPos>> byIndex = new ArrayList<>(centerline.size());
        for (int i = 0; i < centerline.size(); i++) {
            byIndex.add(new LinkedHashSet<>());
        }
        if (centerline.size() == 1) {
            BlockPos only = centerline.get(0).pos();
            byIndex.get(0).add(new BlockPos(only.getX(), centerline.get(0).targetY(), only.getZ()));
            return freeze(byIndex);
        }

        LinkedHashMap<Long, OwnedCell> owned = new LinkedHashMap<>();
        double halfWidth = Math.max(1.0D, width / 2.0D);
        double halfWidthSq = halfWidth * halfWidth;

        for (int segment = 0; segment < centerline.size() - 1; segment++) {
            RoadCenterlinePoint start = centerline.get(segment);
            RoadCenterlinePoint end = centerline.get(segment + 1);
            int minX = (int) Math.floor(Math.min(start.pos().getX(), end.pos().getX()) - halfWidth - 1);
            int maxX = (int) Math.ceil(Math.max(start.pos().getX(), end.pos().getX()) + halfWidth + 1);
            int minZ = (int) Math.floor(Math.min(start.pos().getZ(), end.pos().getZ()) - halfWidth - 1);
            int maxZ = (int) Math.ceil(Math.max(start.pos().getZ(), end.pos().getZ()) + halfWidth + 1);

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Projection projection = projectToSegment(x, z, start, end, segment);
                    if (projection.distanceSq() > halfWidthSq) {
                        continue;
                    }
                    int ownerIndex = projection.t() < 0.5D ? segment : segment + 1;
                    int y = interpolateTargetY(x, z, centerline);
                    BlockPos pos = new BlockPos(x, y, z);
                    long key = BlockPos.asLong(x, 0, z);
                    OwnedCell existing = owned.get(key);
                    if (existing == null || projection.distanceSq() < existing.distanceSq()) {
                        owned.put(key, new OwnedCell(pos, ownerIndex, projection.distanceSq()));
                    }
                }
            }
        }

        for (OwnedCell cell : owned.values()) {
            byIndex.get(Math.max(0, Math.min(byIndex.size() - 1, cell.ownerIndex()))).add(cell.pos());
        }
        for (int i = 0; i < centerline.size(); i++) {
            RoadCenterlinePoint point = centerline.get(i);
            byIndex.get(i).add(new BlockPos(point.pos().getX(), point.targetY(), point.pos().getZ()));
        }
        return freeze(byIndex);
    }

    private static List<List<BlockPos>> freeze(ArrayList<LinkedHashSet<BlockPos>> byIndex) {
        ArrayList<List<BlockPos>> out = new ArrayList<>(byIndex.size());
        for (LinkedHashSet<BlockPos> bucket : byIndex) {
            out.add(bucket.stream()
                    .sorted(Comparator.comparingInt(BlockPos::getX)
                            .thenComparingInt(BlockPos::getY)
                            .thenComparingInt(BlockPos::getZ))
                    .toList());
        }
        return List.copyOf(out);
    }

    private static int interpolateTargetY(int x, int z, List<RoadCenterlinePoint> centerline) {
        Projection best = null;
        for (int segment = 0; segment < centerline.size() - 1; segment++) {
            Projection projection = projectToSegment(x, z, centerline.get(segment), centerline.get(segment + 1), segment);
            if (best == null || projection.distanceSq() < best.distanceSq()) {
                best = projection;
            }
        }
        if (best == null) {
            return centerline.get(0).targetY();
        }
        int startY = centerline.get(best.segmentIndex()).targetY();
        int endY = centerline.get(best.segmentIndex() + 1).targetY();
        return (int) Math.round(startY + (endY - startY) * best.t());
    }

    private static Projection projectToSegment(int x, int z, RoadCenterlinePoint start, RoadCenterlinePoint end, int segmentIndex) {
        double ax = start.pos().getX();
        double az = start.pos().getZ();
        double bx = end.pos().getX();
        double bz = end.pos().getZ();
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSq = dx * dx + dz * dz;
        double t = lengthSq < 1.0E-9D ? 0.0D : Math.max(0.0D, Math.min(1.0D, ((x - ax) * dx + (z - az) * dz) / lengthSq));
        double px = ax + (dx * t);
        double pz = az + (dz * t);
        double distSq = ((x - px) * (x - px)) + ((z - pz) * (z - pz));
        return new Projection(segmentIndex, t, distSq);
    }

    private record Projection(int segmentIndex, double t, double distanceSq) {
    }

    private record OwnedCell(BlockPos pos, int ownerIndex, double distanceSq) {
    }
}
```

- [ ] **Step 4: Run the focused tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.roadplanner.structure.RoadBandRasterizerTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadBandRasterizer.java src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadBandRasterizerTest.java
git commit -m "Add road band footprint rasterizer"
```

---

### Task 3: Tier Bridge Heights and Buildable Ramp Profiles

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeGeometryPlanner.java`
- Modify: `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeGeometryPlannerTest.java`

- [ ] **Step 1: Update failing bridge geometry tests**

In `RoadPlannerBridgeGeometryPlannerTest.java`, change `deckHeightUsesActualWaterSurfacePlusConfigHeightWhenShoresDoNotRaiseIt` to ordinary-long-bridge expectations:

```java
@Test
void ordinaryPierBridgeUsesLowerClearanceThanNavigableBridge() {
    RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
            centerline(0, 48, 64),
            new RoadSpan(RoadSpanType.BRIDGE, 0, 48, RoadPlannerSegmentType.BRIDGE_MAJOR),
            waterSampler(63, 55),
            new BridgeConfig()
    );

    assertEquals(RoadPlannerBridgeProfile.PIER_BRIDGE, plan.profile());
    assertTrue(plan.deckY() <= 66, "ordinary pier bridge should not always use water + 5 clearance");
    assertAdjacentTargetYDeltaAtMostOne(plan);
}
```

Add this new test below it:

```java
@Test
void veryLongMajorBridgeCanUseNavigableClearance() {
    RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
            centerline(0, 72, 64),
            new RoadSpan(RoadSpanType.BRIDGE, 0, 72, RoadPlannerSegmentType.BRIDGE_MAJOR),
            waterSampler(63, 52),
            new BridgeConfig()
    );

    assertEquals(RoadPlannerBridgeProfile.PIER_BRIDGE, plan.profile());
    assertTrue(plan.deckY() >= 68, "very long major bridge may keep navigable clearance");
    assertAdjacentTargetYDeltaAtMostOne(plan);
}
```

Add this test near the existing short deep crossing tests:

```java
@Test
void shortDeepNarrowWaterStaysLowArch() {
    RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
            centerline(0, 10, 64),
            new RoadSpan(RoadSpanType.BRIDGE, 0, 10, RoadPlannerSegmentType.BRIDGE_SMALL),
            waterSampler(63, 38),
            new BridgeConfig()
    );

    assertEquals(RoadPlannerBridgeProfile.LOW_ARCH, plan.profile());
    assertTrue(plan.piers().isEmpty());
    assertTrue(plan.deckY() <= 66, "deep narrow water should stay a small low bridge");
    assertAdjacentTargetYDeltaAtMostOne(plan);
}
```

- [ ] **Step 2: Run the tests and verify failure**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.roadplanner.structure.RoadPlannerBridgeGeometryPlannerTest"
```

Expected: ordinary pier bridge height still reports `68`, so the new lower-height assertion fails.

- [ ] **Step 3: Add tiered pier bridge height helpers**

In `RoadPlannerBridgeGeometryPlanner.java`, replace `deckYForActualBridge` with:

```java
private static int deckYForActualBridge(int waterY, int entryY, int exitY, int spanLength, RoadSpan span, BridgeConfig config) {
    boolean navigable = usesNavigableClearance(spanLength, span);
    int higherShore = Math.max(entryY, exitY);
    int lowerShore = Math.min(entryY, exitY);
    int waterClearance = navigable ? config.getDeckHeight() : Math.min(3, config.getDeckHeight());
    int shoreClearance = navigable ? 3 : 2;
    int desired = Math.max(waterY + waterClearance, higherShore + shoreClearance);
    int approachBudget = Math.max(2, Math.min(config.getDeckHeight(), Math.max(2, spanLength / 4)));
    int maxReachable = lowerShore + approachBudget;
    return Math.max(higherShore, Math.min(desired, maxReachable));
}

private static boolean usesNavigableClearance(int spanLength, RoadSpan span) {
    return span != null
            && span.sourceSegmentType() == com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType.BRIDGE_MAJOR
            && spanLength >= 64;
}
```

Update `deckYForProfile` signature and call sites:

```java
private static RoadPlannerBridgeProfile resolveProfile(RoadPlannerBridgeProfile profile,
                                                       int waterY,
                                                       int entryY,
                                                       int exitY) {
```

remains unchanged, but `deckYForProfile` becomes:

```java
private static int deckYForProfile(RoadPlannerBridgeProfile profile,
                                   int waterY,
                                   int entryY,
                                   int exitY,
                                   int spanLength,
                                   RoadSpan span,
                                   BridgeConfig config) {
    RoadPlannerBridgeProfile safeProfile = profile == null ? RoadPlannerBridgeProfile.PIER_BRIDGE : profile;
    if (safeProfile == RoadPlannerBridgeProfile.PIER_BRIDGE) {
        return deckYForActualBridge(waterY, entryY, exitY, spanLength, span, config);
    }
    int lowerShore = Math.min(entryY, exitY);
    int higherShore = Math.max(entryY, exitY);
    int clearanceDeck = waterY + safeProfile.waterClearance();
    int shoreDeck = higherShore + 1;
    int maxLowDeck = lowerShore + safeProfile.maxRiseFromLowerShore();
    return Math.max(higherShore, Math.min(Math.max(clearanceDeck, shoreDeck), maxLowDeck));
}
```

Change the call in `plan` to:

```java
int deckY = deckYForProfile(profile, waterY, entryY, exitY, spanLength, span, safeConfig);
```

- [ ] **Step 4: Make ramps use two horizontal samples per block when possible**

In `RoadPlannerBridgeGeometryPlanner.java`, replace `rampLength` with:

```java
private static int rampLength(int height, int availableIntervals) {
    if (availableIntervals <= 0 || height <= 0) {
        return 0;
    }
    int desired = Math.max(2, height * 2);
    return Math.min(desired, availableIntervals);
}
```

Keep `rampY` rounded integer interpolation so adjacent target heights stay at most one block apart.

- [ ] **Step 5: Keep piers on deck spans only**

Verify `piers(...)` still contains this check and do not remove it:

```java
if (plannedPoints.get(index).phase() == BuildPhase.DECK) {
    addPier(result, sourcePoints.get(index), deckY, sampler);
}
```

If a new test fails because all deck points were consumed by long ramps, reduce the level deck only after preserving at least one `BuildPhase.DECK` midpoint in `rampedDeck`.

- [ ] **Step 6: Run focused bridge geometry tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.roadplanner.structure.RoadPlannerBridgeGeometryPlannerTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeGeometryPlanner.java src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeGeometryPlannerTest.java
git commit -m "Tune bridge height and ramp profiles"
```

---

### Task 4: Use Rasterized Footprints in Road and Bridge Emitters

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeStructureEmitter.java`
- Modify: `src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadSurfaceStepEmitter.java`
- Modify: `src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java`

- [ ] **Step 1: Add failing integration tests**

In `RoadNodeStructureExpanderTest.java`, add:

```java
@Test
void turningRoadSurfaceFillsInsideCornerGap() {
    RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
            List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), new BlockPos(8, 64, 8)),
            List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD),
            RoadPlannerBuildSettings.DEFAULTS,
            RoadTerrainSampler.flat(64),
            RoadStructureMode.BUILD
    );

    List<BlockPos> surface = result.buildSteps().stream()
            .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.SURFACE)
            .map(com.monpai.sailboatmod.road.model.BuildStep::pos)
            .toList();

    assertTrue(surface.contains(new BlockPos(7, 64, 1)), surface.toString());
    assertTrue(surface.contains(new BlockPos(8, 64, 2)), surface.toString());
}
```

Add:

```java
@Test
void ordinaryLongBridgeIsLowerThanOldWaterPlusFiveDeck() {
    RoadTerrainSampler waterSampler = new RoadTerrainSampler() {
        @Override
        public int terrainY(int x, int z) {
            return 63;
        }

        @Override
        public int waterSurfaceY(int x, int z) {
            return 63;
        }

        @Override
        public int oceanFloorY(int x, int z) {
            return 54;
        }
    };

    RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
            List.of(new BlockPos(0, 64, 0), new BlockPos(48, 64, 0)),
            List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
            RoadPlannerBuildSettings.DEFAULTS,
            waterSampler,
            RoadStructureMode.BUILD
    );

    int maxDeckY = result.buildSteps().stream()
            .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.DECK)
            .map(com.monpai.sailboatmod.road.model.BuildStep::pos)
            .mapToInt(BlockPos::getY)
            .max()
            .orElseThrow();

    assertTrue(maxDeckY <= 66, "ordinary long bridge should be lower than the previous 68-block deck");
}
```

Add:

```java
@Test
void bridgeRampDeckFootprintsRemainConnectedAcrossTurn() {
    RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
            List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), new BlockPos(8, 64, 16)),
            List.of(RoadPlannerSegmentType.BRIDGE_MAJOR, RoadPlannerSegmentType.BRIDGE_MAJOR),
            RoadPlannerBuildSettings.DEFAULTS,
            RoadTerrainSampler.flat(61),
            RoadStructureMode.BUILD
    );

    List<BlockPos> bridgeSurface = result.buildSteps().stream()
            .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAMP
                    || step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.DECK)
            .map(com.monpai.sailboatmod.road.model.BuildStep::pos)
            .toList();

    assertTrue(bridgeSurface.stream().anyMatch(pos -> pos.getX() == 7 && pos.getZ() == 1), bridgeSurface.toString());
    assertTrue(bridgeSurface.stream().anyMatch(pos -> pos.getX() == 8 && pos.getZ() == 2), bridgeSurface.toString());
}
```

- [ ] **Step 2: Run the integration tests and verify failure**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest"
```

Expected: the new turn/bridge footprint assertions fail with the current per-slice footprint emitter.

- [ ] **Step 3: Update bridge emitter to use rasterized footprints**

In `BridgeStructureEmitter.emitProgrammaticBridge`, after `bridgeProfile` is built, add:

```java
List<List<BlockPos>> footprints = RoadBandRasterizer.surfacePositionsByIndex(bridgeProfile, settings.width());
```

Replace:

```java
List<BlockPos> footprint = RoadFootprintPlanner.surfacePositions(bridgeProfile, index, settings.width());
```

with:

```java
List<BlockPos> footprint = index < footprints.size()
        ? footprints.get(index)
        : RoadFootprintPlanner.surfacePositions(bridgeProfile, index, settings.width());
```

Keep the existing railing positions based on `RoadFootprintPlanner.railingPositions`; railings should remain outside the local road normal and not be rasterized into the deck.

- [ ] **Step 4: Make ramp state deterministic and local to the lower side**

In `BridgeStructureEmitter.rampState`, replace the current body with:

```java
private static BlockState rampState(RoadPlannerBuildSettings settings, List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> points, int index) {
    int y = points.get(index).point().targetY();
    int prevY = index > 0 ? points.get(index - 1).point().targetY() : y;
    int nextY = index + 1 < points.size() ? points.get(index + 1).point().targetY() : y;
    boolean risingFromPrevious = y > prevY;
    boolean fallingToNext = nextY < y;
    boolean lowerSideOfOneBlockTransition = risingFromPrevious || fallingToNext;
    return lowerSideOfOneBlockTransition ? settings.slabBottomState() : settings.slabTopState();
}
```

This keeps the existing material settings while preventing whole-deck alternating slabs.

- [ ] **Step 5: Update road surface emitter to use rasterized footprints**

In `RoadSurfaceStepEmitter.emit`, after `RoadPlannerBuildSettings safeSettings` is initialized, add:

```java
List<List<BlockPos>> footprints = RoadBandRasterizer.surfacePositionsByIndex(centerline, safeSettings.width());
```

Replace:

```java
List<BlockPos> footprint = RoadFootprintPlanner.surfacePositions(centerline, index, safeSettings.width());
```

with:

```java
List<BlockPos> footprint = index < footprints.size()
        ? footprints.get(index)
        : RoadFootprintPlanner.surfacePositions(centerline, index, safeSettings.width());
```

Keep the existing foundation, clearance, surface, ramp, and streetlight phases unchanged.

- [ ] **Step 6: Update the old pier-height integration expectation**

In `RoadNodeStructureExpanderTest.longBridgePiersUseOceanFloorAndActualDeckHeight`, change:

```java
assertEquals(68, piers.stream().mapToInt(BlockPos::getY).max().orElseThrow());
```

to:

```java
assertTrue(piers.stream().mapToInt(BlockPos::getY).max().orElseThrow() <= 66);
```

Keep the ocean-floor assertion:

```java
assertEquals(54, piers.stream().mapToInt(BlockPos::getY).min().orElseThrow());
```

- [ ] **Step 7: Run focused integration tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpanderTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeStructureEmitter.java src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadSurfaceStepEmitter.java src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java
git commit -m "Use continuous road planner footprints"
```

---

### Task 5: Full Verification and Regression Check

**Files:**
- Verify only; no planned source edits.

- [ ] **Step 1: Run focused road planner tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.roadplanner.structure.*" --tests "com.monpai.sailboatmod.road.pathfinding.cache.RoadSurfaceHeuristicsTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Compile the mod**

Run:

```powershell
.\gradlew.bat compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Confirm no pathfinding algorithm files changed**

Run:

```powershell
git diff --name-only HEAD~4..HEAD
```

Expected changed paths include only:

```text
src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/RoadSurfaceHeuristics.java
src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadBandRasterizer.java
src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeGeometryPlanner.java
src/main/java/com/monpai/sailboatmod/roadplanner/structure/BridgeStructureEmitter.java
src/main/java/com/monpai/sailboatmod/roadplanner/structure/RoadSurfaceStepEmitter.java
src/test/java/com/monpai/sailboatmod/road/pathfinding/cache/RoadSurfaceHeuristicsTest.java
src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadBandRasterizerTest.java
src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadPlannerBridgeGeometryPlannerTest.java
src/test/java/com/monpai/sailboatmod/roadplanner/structure/RoadNodeStructureExpanderTest.java
```

If any file under `src/main/java/com/monpai/sailboatmod/road/pathfinding/impl/` appears, stop and remove that unintended pathfinding edit with a small targeted patch before continuing.

- [ ] **Step 4: Final status**

Run:

```powershell
git status -sb
```

Expected: clean working tree on `feature/road-planner-rebuild`, ahead by the task commits.
