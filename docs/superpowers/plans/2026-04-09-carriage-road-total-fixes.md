# Carriage And Road Total Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix carriage material, carriage handling, claims-map button visibility, road anchor selection, slope-aware routing, wider slope-safe road geometry, and slower road construction in one coherent pass.

**Architecture:** Keep the existing carriage route/dock/autopilot integration and the existing manual-road planning pipeline, but move carriage-specific material and motion into explicit helpers and push road anchor/routing/geometry behavior into focused planner helpers. Add unit tests first for pure parsing, visibility predicates, waiting-area exit selection, and road geometry/terrain-cost helpers so the risky behavior changes are covered before implementation.

**Tech Stack:** Java 17, Forge 1.20.1, GeckoLib, JUnit 5, Gradle

---

### Task 1: Establish testable helper surfaces

**Files:**
- Create: `src/test/java/com/monpai/sailboatmod/entity/CarriageWoodTypeTest.java`
- Create: `src/test/java/com/monpai/sailboatmod/client/screen/ClaimsMapVisibilityTest.java`
- Create: `src/test/java/com/monpai/sailboatmod/nation/service/PostStationRoadAnchorHelperTest.java`
- Create: `src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerSlopeTest.java`
- Create: `src/main/java/com/monpai/sailboatmod/entity/CarriageWoodType.java`
- Create: `src/main/java/com/monpai/sailboatmod/client/screen/ClaimsMapVisibility.java`
- Create: `src/main/java/com/monpai/sailboatmod/nation/service/PostStationRoadAnchorHelper.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.monpai.sailboatmod.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CarriageWoodTypeTest {
    @Test
    void defaultsUnknownIdsToOak() {
        assertEquals(CarriageWoodType.OAK, CarriageWoodType.fromSerialized("unknown"));
        assertEquals(CarriageWoodType.OAK, CarriageWoodType.fromSerialized(""));
    }

    @Test
    void parsesSelectedWoodIds() {
        assertEquals(CarriageWoodType.SPRUCE, CarriageWoodType.fromSerialized("spruce"));
        assertEquals(CarriageWoodType.DARK_OAK, CarriageWoodType.fromSerialized("dark_oak"));
    }
}
```

```java
package com.monpai.sailboatmod.client.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimsMapVisibilityTest {
    @Test
    void mapToolsOnlyShowOnClaimsMapView() {
        assertTrue(ClaimsMapVisibility.showMapTools(true, 0));
        assertFalse(ClaimsMapVisibility.showMapTools(true, 1));
        assertFalse(ClaimsMapVisibility.showMapTools(false, 0));
    }
}
```

```java
package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostStationRoadAnchorHelperTest {
    @Test
    void choosesExitFacingTargetDirection() {
        PostStationRoadAnchorHelper.Zone zone = new PostStationRoadAnchorHelper.Zone(new BlockPos(100, 64, 100), -3, 5, -2, 2);
        List<BlockPos> exits = PostStationRoadAnchorHelper.computeExitCandidates(zone);
        BlockPos chosen = PostStationRoadAnchorHelper.chooseBestExit(exits, new BlockPos(140, 64, 102));
        assertEquals(new BlockPos(106, 64, 102), chosen);
    }
}
```

```java
package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadGeometryPlannerSlopeTest {
    @Test
    void marksSteepConsecutiveRiseAsStairSegment() {
        List<BlockPos> path = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 65, 0),
                new BlockPos(2, 66, 0)
        );
        RoadGeometryPlanner.SlopeProfile profile = RoadGeometryPlanner.analyzeSlopeProfile(path);
        assertTrue(profile.hasStairSegments());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.entity.CarriageWoodTypeTest" --tests "com.monpai.sailboatmod.client.screen.ClaimsMapVisibilityTest" --tests "com.monpai.sailboatmod.nation.service.PostStationRoadAnchorHelperTest" --tests "com.monpai.sailboatmod.construction.RoadGeometryPlannerSlopeTest"`

Expected: FAIL with missing classes or methods such as `CarriageWoodType`, `ClaimsMapVisibility.showMapTools`, `PostStationRoadAnchorHelper`, or `RoadGeometryPlanner.analyzeSlopeProfile`

- [ ] **Step 3: Write minimal implementation helpers**

```java
package com.monpai.sailboatmod.entity;

public enum CarriageWoodType {
    OAK("oak"),
    SPRUCE("spruce"),
    DARK_OAK("dark_oak");

    private final String serialized;

    CarriageWoodType(String serialized) {
        this.serialized = serialized;
    }

    public String serializedName() {
        return serialized;
    }

    public static CarriageWoodType fromSerialized(String value) {
        for (CarriageWoodType type : values()) {
            if (type.serialized.equalsIgnoreCase(value)) {
                return type;
            }
        }
        return OAK;
    }
}
```

```java
package com.monpai.sailboatmod.client.screen;

public final class ClaimsMapVisibility {
    private ClaimsMapVisibility() {
    }

    public static boolean showMapTools(boolean claimsPage, int claimsSubPage) {
        return claimsPage && claimsSubPage == 0;
    }
}
```

- [ ] **Step 4: Run the targeted tests to verify they pass**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.entity.CarriageWoodTypeTest" --tests "com.monpai.sailboatmod.client.screen.ClaimsMapVisibilityTest" --tests "com.monpai.sailboatmod.nation.service.PostStationRoadAnchorHelperTest" --tests "com.monpai.sailboatmod.construction.RoadGeometryPlannerSlopeTest"`

Expected: PASS for the new helper tests

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/entity/CarriageWoodType.java src/main/java/com/monpai/sailboatmod/client/screen/ClaimsMapVisibility.java src/main/java/com/monpai/sailboatmod/nation/service/PostStationRoadAnchorHelper.java src/test/java/com/monpai/sailboatmod/entity/CarriageWoodTypeTest.java src/test/java/com/monpai/sailboatmod/client/screen/ClaimsMapVisibilityTest.java src/test/java/com/monpai/sailboatmod/nation/service/PostStationRoadAnchorHelperTest.java src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerSlopeTest.java
git commit -m "Add carriage and road helper tests"
```

### Task 2: Implement carriage wood variants and claims-map visibility fix

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/item/CarriageItem.java`
- Modify: `src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/model/CarriageEntityModel.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/model/CarriageItemModel.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreen.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/nation/NationHomeScreen.java`
- Modify: `src/main/resources/assets/sailboatmod/lang/en_us.json`
- Modify: `src/main/resources/assets/sailboatmod/lang/zh_cn.json`
- Create: `src/main/resources/assets/sailboatmod/textures/entity/carriage_oak.png`
- Create: `src/main/resources/assets/sailboatmod/textures/entity/carriage_spruce.png`
- Create: `src/main/resources/assets/sailboatmod/textures/entity/carriage_dark_oak.png`

- [ ] **Step 1: Extend or add failing tests for NBT and texture lookup behavior**

```java
@Test
void itemAndEntityFallbackToOak() {
    assertEquals(CarriageWoodType.OAK, CarriageWoodType.fromSerialized(null));
}
```

- [ ] **Step 2: Run targeted tests and verify red**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.entity.CarriageWoodTypeTest" --tests "com.monpai.sailboatmod.client.screen.ClaimsMapVisibilityTest"`

Expected: FAIL if the new NBT/lookup assertions are not yet implemented

- [ ] **Step 3: Implement minimal production changes**

```java
// In CarriageItem
public static final String TAG_WOOD_TYPE = "WoodType";

public static CarriageWoodType getWoodType(ItemStack stack) {
    return CarriageWoodType.fromSerialized(stack.getOrCreateTag().getString(TAG_WOOD_TYPE));
}
```

```java
// In CarriageEntityModel / CarriageItemModel
return animatable.getWoodType().textureLocation();
```

```java
// In screens
boolean claimsMapView = ClaimsMapVisibility.showMapTools(claimsPage, this.claimsSubPage);
```

- [ ] **Step 4: Run the targeted tests**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.entity.CarriageWoodTypeTest" --tests "com.monpai.sailboatmod.client.screen.ClaimsMapVisibilityTest"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/item/CarriageItem.java src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java src/main/java/com/monpai/sailboatmod/client/model/CarriageEntityModel.java src/main/java/com/monpai/sailboatmod/client/model/CarriageItemModel.java src/main/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreen.java src/main/java/com/monpai/sailboatmod/client/screen/nation/NationHomeScreen.java src/main/resources/assets/sailboatmod/lang/en_us.json src/main/resources/assets/sailboatmod/lang/zh_cn.json src/main/resources/assets/sailboatmod/textures/entity/carriage_oak.png src/main/resources/assets/sailboatmod/textures/entity/carriage_spruce.png src/main/resources/assets/sailboatmod/textures/entity/carriage_dark_oak.png
git commit -m "Add carriage wood variants and map tool gating"
```

### Task 3: Implement carriage sounds, motion tuning, and road speed bonus

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/renderer/CarriageEntityRenderer.java`
- Create: `src/main/java/com/monpai/sailboatmod/registry/ModSounds.java`
- Create: `src/main/resources/assets/sailboatmod/sounds.json`
- Create: `src/main/resources/assets/sailboatmod/sounds/entity/carriage_place.ogg`
- Create: `src/main/resources/assets/sailboatmod/sounds/entity/carriage_attach.ogg`
- Create: `src/main/resources/assets/sailboatmod/sounds/entity/carriage_detach.ogg`

- [ ] **Step 1: Write or extend failing tests around road-surface and speed gating helpers**

```java
@Test
void roadBonusAppliesOnlyOnFinishedRoadBlocks() {
    assertTrue(CarriageEntity.isRoadSurfaceState(Blocks.STONE_BRICK_SLAB.defaultBlockState()));
    assertFalse(CarriageEntity.isRoadSurfaceState(Blocks.DIRT.defaultBlockState()));
}
```

- [ ] **Step 2: Run targeted tests to verify red**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.entity.CarriageEntityTest"`

Expected: FAIL with missing helper methods or behavior

- [ ] **Step 3: Implement minimal motion and sound changes**

```java
// In CarriageEntity
private static final double BASE_GROUND_SPEED = 0.16D;
private static final double ROAD_SPEED_MULTIPLIER = 1.22D;
private static final double OFFROAD_DRAG = 0.88D;
private static final double ONROAD_DRAG = 0.93D;
```

```java
// In ModSounds
public static final RegistryObject<SoundEvent> CARRIAGE_PLACE =
        SOUNDS.register("entity.carriage.place", () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(SailboatMod.MODID, "entity.carriage.place")));
```

- [ ] **Step 4: Run targeted tests**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.entity.CarriageEntityTest"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java src/main/java/com/monpai/sailboatmod/client/renderer/CarriageEntityRenderer.java src/main/java/com/monpai/sailboatmod/registry/ModSounds.java src/main/resources/assets/sailboatmod/sounds.json src/main/resources/assets/sailboatmod/sounds/entity/carriage_place.ogg src/main/resources/assets/sailboatmod/sounds/entity/carriage_attach.ogg src/main/resources/assets/sailboatmod/sounds/entity/carriage_detach.ogg
git commit -m "Retune carriage movement and sounds"
```

### Task 4: Re-anchor manual roads to post-station waiting areas

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/PostStationBlockEntity.java`
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/DockBlockEntity.java`
- Modify: `src/test/java/com/monpai/sailboatmod/nation/service/PostStationRoadAnchorHelperTest.java`

- [ ] **Step 1: Add failing tests for waiting-area exits and fallback behavior**

```java
@Test
void fallsBackWhenNoUsableWaitingAreaExitExists() {
    BlockPos fallback = new BlockPos(90, 64, 90);
    BlockPos chosen = PostStationRoadAnchorHelper.chooseBestExit(List.of(), new BlockPos(120, 64, 120), fallback);
    assertEquals(fallback, chosen);
}
```

- [ ] **Step 2: Run tests to verify red**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.PostStationRoadAnchorHelperTest"`

Expected: FAIL until the planner uses waiting-area exits

- [ ] **Step 3: Implement the anchor-selection changes**

```java
// In ManualRoadPlannerService
BlockPos sourceAnchor = PostStationRoadAnchorHelper.resolveBestTownExit(level, sourceTown, targetTownCenter);
BlockPos targetAnchor = PostStationRoadAnchorHelper.resolveBestTownExit(level, targetTown, sourceAnchor);
```

- [ ] **Step 4: Run the targeted tests**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.nation.service.PostStationRoadAnchorHelperTest"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java src/main/java/com/monpai/sailboatmod/block/entity/PostStationBlockEntity.java src/main/java/com/monpai/sailboatmod/block/entity/DockBlockEntity.java src/test/java/com/monpai/sailboatmod/nation/service/PostStationRoadAnchorHelperTest.java
git commit -m "Anchor manual roads to post station waiting areas"
```

### Task 5: Improve road path cost, slope geometry, width, and construction pace

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadRouteNodePlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadBezierCenterline.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- Modify: `src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerSlopeTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerTest.java`
- Modify: `src/test/java/com/monpai/sailboatmod/construction/RoadRouteNodePlannerTest.java`

- [ ] **Step 1: Write failing tests for steeper route penalties, stair segments, and wider cross-sections**

```java
@Test
void widerRoadAddsFiveBlockUsableCrossSection() {
    // Assert generated positions include the wider lateral span.
}
```

```java
@Test
void consecutiveFullBlockRiseCreatesStairSegmentWithSupport() {
    // Assert stair/foundation steps are emitted for repeated rises.
}
```

- [ ] **Step 2: Run tests to verify red**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.construction.RoadGeometryPlannerTest" --tests "com.monpai.sailboatmod.construction.RoadGeometryPlannerSlopeTest" --tests "com.monpai.sailboatmod.construction.RoadRouteNodePlannerTest"`

Expected: FAIL until the wider geometry and slope behavior is implemented

- [ ] **Step 3: Implement minimal geometry and runtime changes**

```java
// In RoadPathfinder
int slopePenalty = repeatedSlopePenalty(level, surface);
```

```java
// In RoadGeometryPlanner
public static SlopeProfile analyzeSlopeProfile(List<BlockPos> centerPath) {
    return SlopeProfile.from(centerPath);
}
```

```java
// In StructureConstructionManager
private static final int ROAD_BUILD_DURATION_TICKS = 1200;
```

- [ ] **Step 4: Run the targeted road tests**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.construction.RoadGeometryPlannerTest" --tests "com.monpai.sailboatmod.construction.RoadGeometryPlannerSlopeTest" --tests "com.monpai.sailboatmod.construction.RoadRouteNodePlannerTest"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java src/main/java/com/monpai/sailboatmod/construction/RoadRouteNodePlanner.java src/main/java/com/monpai/sailboatmod/construction/RoadBezierCenterline.java src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerSlopeTest.java src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerTest.java src/test/java/com/monpai/sailboatmod/construction/RoadRouteNodePlannerTest.java
git commit -m "Improve road anchors geometry and build pace"
```

### Task 6: Full verification and jar build

**Files:**
- Verify only: `build.gradle`
- Verify only: `build/libs/*`

- [ ] **Step 1: Run focused tests**

Run: `.\gradlew.bat test --tests "com.monpai.sailboatmod.entity.CarriageWoodTypeTest" --tests "com.monpai.sailboatmod.client.screen.ClaimsMapVisibilityTest" --tests "com.monpai.sailboatmod.nation.service.PostStationRoadAnchorHelperTest" --tests "com.monpai.sailboatmod.construction.RoadGeometryPlannerSlopeTest" --tests "com.monpai.sailboatmod.construction.RoadGeometryPlannerTest" --tests "com.monpai.sailboatmod.construction.RoadRouteNodePlannerTest"`

Expected: PASS

- [ ] **Step 2: Run compile validation**

Run: `.\gradlew.bat compileJava`

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run full package build**

Run: `.\gradlew.bat build`

Expected: BUILD SUCCESSFUL and jars copied into `build/libs`

- [ ] **Step 4: Inspect built artifacts**

Run: `Get-ChildItem build\\libs`

Expected: versioned `sailboatmod-*.jar` artifacts present

- [ ] **Step 5: Commit final integrated changes**

```bash
git add src/main/java src/main/resources src/test/java docs/superpowers/plans/2026-04-09-carriage-road-total-fixes.md
git commit -m "Fix carriage behavior and road planning"
```
