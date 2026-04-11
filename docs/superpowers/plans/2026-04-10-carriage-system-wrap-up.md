# Carriage System Wrap-Up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the carriage wood-type, persistence, motion, and sound-system work described in the first section of `未完成_任务_1.md`.

**Architecture:** Keep `CarriageEntity` on top of the existing `SailboatEntity` route and storage integration, but add explicit carriage-specific state and helpers. Drive wood appearance through a dedicated `CarriageWoodType` enum plus item/entity persistence, and isolate risky behavior changes behind focused tests before touching movement or sound wiring.

**Tech Stack:** Java 17, Forge 1.20.1, GeckoLib, JUnit 5, Gradle

---

### Task 1: Add wood-type domain model and tests

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/entity/CarriageWoodType.java`
- Create: `src/test/java/com/monpai/sailboatmod/entity/CarriageWoodTypeTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.monpai.sailboatmod.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CarriageWoodTypeTest {
    @Test
    void defaultsUnknownIdsToOak() {
        assertEquals(CarriageWoodType.OAK, CarriageWoodType.fromSerialized(null));
        assertEquals(CarriageWoodType.OAK, CarriageWoodType.fromSerialized(""));
        assertEquals(CarriageWoodType.OAK, CarriageWoodType.fromSerialized("unknown"));
    }

    @Test
    void parsesKnownWoodIds() {
        assertEquals(CarriageWoodType.OAK, CarriageWoodType.fromSerialized("oak"));
        assertEquals(CarriageWoodType.SPRUCE, CarriageWoodType.fromSerialized("spruce"));
        assertEquals(CarriageWoodType.DARK_OAK, CarriageWoodType.fromSerialized("dark_oak"));
    }

    @Test
    void cyclesInStableOrder() {
        assertEquals(CarriageWoodType.SPRUCE, CarriageWoodType.OAK.next());
        assertEquals(CarriageWoodType.DARK_OAK, CarriageWoodType.SPRUCE.next());
        assertEquals(CarriageWoodType.OAK, CarriageWoodType.DARK_OAK.next());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.monpai.sailboatmod.entity.CarriageWoodTypeTest"`

Expected: FAIL with missing class or missing `fromSerialized` / `next` methods.

- [ ] **Step 3: Write the minimal implementation**

```java
package com.monpai.sailboatmod.entity;

import com.monpai.sailboatmod.SailboatMod;
import net.minecraft.resources.ResourceLocation;

public enum CarriageWoodType {
    OAK("oak"),
    SPRUCE("spruce"),
    DARK_OAK("dark_oak");

    private final String serializedName;
    private final ResourceLocation textureLocation;

    CarriageWoodType(String serializedName) {
        this.serializedName = serializedName;
        this.textureLocation = new ResourceLocation(SailboatMod.MODID, "textures/entity/carriage_" + serializedName + ".png");
    }

    public String serializedName() {
        return serializedName;
    }

    public ResourceLocation textureLocation() {
        return textureLocation;
    }

    public String translationKey() {
        return "item.sailboatmod.carriage.wood." + serializedName;
    }

    public CarriageWoodType next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public static CarriageWoodType fromSerialized(String value) {
        if (value == null || value.isBlank()) {
            return OAK;
        }
        for (CarriageWoodType type : values()) {
            if (type.serializedName.equalsIgnoreCase(value)) {
                return type;
            }
        }
        return OAK;
    }
}
```

- [ ] **Step 4: Run the targeted test to verify it passes**

Run: `./gradlew test --tests "com.monpai.sailboatmod.entity.CarriageWoodTypeTest"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/entity/CarriageWoodType.java src/test/java/com/monpai/sailboatmod/entity/CarriageWoodTypeTest.java
git commit -m "Add carriage wood type model"
```

### Task 2: Persist carriage wood on the item and entity

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/item/CarriageItem.java`
- Modify: `src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java`
- Modify: `src/main/resources/assets/sailboatmod/lang/en_us.json`
- Modify: `src/main/resources/assets/sailboatmod/lang/zh_cn.json`

- [ ] **Step 1: Extend the test surface for item/entity persistence helpers**

```java
@Test
void translationKeysMatchSerializedNames() {
    assertEquals("item.sailboatmod.carriage.wood.oak", CarriageWoodType.OAK.translationKey());
    assertEquals("item.sailboatmod.carriage.wood.dark_oak", CarriageWoodType.DARK_OAK.translationKey());
}
```

- [ ] **Step 2: Run the targeted test to verify it fails if the new helper is missing**

Run: `./gradlew test --tests "com.monpai.sailboatmod.entity.CarriageWoodTypeTest"`

Expected: FAIL until `translationKey()` exists.

- [ ] **Step 3: Implement minimal item/entity persistence**

```java
// In CarriageItem
public static final String TAG_WOOD_TYPE = "WoodType";

public static CarriageWoodType getWoodType(ItemStack stack) {
    CompoundTag tag = stack.getTag();
    return CarriageWoodType.fromSerialized(tag == null ? null : tag.getString(TAG_WOOD_TYPE));
}

public static void setWoodType(ItemStack stack, CarriageWoodType woodType) {
    stack.getOrCreateTag().putString(TAG_WOOD_TYPE, woodType.serializedName());
}
```

```java
// In CarriageEntity
private static final EntityDataAccessor<String> DATA_WOOD_TYPE =
        SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.STRING);

public CarriageWoodType getWoodType() {
    return CarriageWoodType.fromSerialized(entityData.get(DATA_WOOD_TYPE));
}

public void setWoodType(CarriageWoodType woodType) {
    entityData.set(DATA_WOOD_TYPE, woodType.serializedName());
}
```

```java
// In CarriageItem.use
carriage.setWoodType(getWoodType(itemstack));
```

```java
// In CarriageItem sneak-use branch
if (player.isSecondaryUseActive()) {
    CarriageWoodType next = getWoodType(itemstack).next();
    setWoodType(itemstack, next);
    if (!level.isClientSide) {
        player.displayClientMessage(Component.translatable("item.sailboatmod.carriage.wood.selected", Component.translatable(next.translationKey())), true);
    }
    return InteractionResultHolder.sidedSuccess(itemstack, level.isClientSide());
}
```

- [ ] **Step 4: Add entity save/load and sync definitions**

```java
// In defineSynchedData
this.entityData.define(DATA_WOOD_TYPE, CarriageWoodType.OAK.serializedName());
```

```java
// In addAdditionalSaveData
tag.putString("WoodType", getWoodType().serializedName());
```

```java
// In readAdditionalSaveData
setWoodType(CarriageWoodType.fromSerialized(tag.getString("WoodType")));
```

- [ ] **Step 5: Run targeted verification**

Run: `./gradlew test --tests "com.monpai.sailboatmod.entity.CarriageWoodTypeTest" && ./gradlew compileJava`

Expected: tests PASS and Java compilation succeeds with the new item/entity methods.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/item/CarriageItem.java src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java src/main/resources/assets/sailboatmod/lang/en_us.json src/main/resources/assets/sailboatmod/lang/zh_cn.json
git commit -m "Persist carriage wood type"
```

### Task 3: Switch carriage textures by wood type

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/model/CarriageEntityModel.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/model/CarriageItemModel.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/renderer/CarriageItemRenderer.java`
- Create: `src/main/resources/assets/sailboatmod/textures/entity/carriage_oak.png`
- Create: `src/main/resources/assets/sailboatmod/textures/entity/carriage_spruce.png`
- Create: `src/main/resources/assets/sailboatmod/textures/entity/carriage_dark_oak.png`

- [ ] **Step 1: Write the failing texture lookup assertions**

```java
@Test
void exposesTextureLocationsPerWoodType() {
    assertEquals("sailboatmod:textures/entity/carriage_oak.png", CarriageWoodType.OAK.textureLocation().toString());
    assertEquals("sailboatmod:textures/entity/carriage_spruce.png", CarriageWoodType.SPRUCE.textureLocation().toString());
    assertEquals("sailboatmod:textures/entity/carriage_dark_oak.png", CarriageWoodType.DARK_OAK.textureLocation().toString());
}
```

- [ ] **Step 2: Run the targeted test and verify red**

Run: `./gradlew test --tests "com.monpai.sailboatmod.entity.CarriageWoodTypeTest"`

Expected: FAIL if `textureLocation()` is missing or mismatched.

- [ ] **Step 3: Implement texture lookup changes**

```java
// In CarriageEntityModel
return animatable.getWoodType().textureLocation();
```

```java
// In CarriageItemModel
return CarriageWoodType.OAK.textureLocation();
```

```java
// In CarriageItemRenderer.renderByItem
this.currentItemStack = stack;
super.renderByItem(stack, displayContext, poseStack, bufferSource, packedLight, packedOverlay);
this.currentItemStack = ItemStack.EMPTY;
```

```java
// In CarriageItemModel custom helper
public ResourceLocation getTextureForStack(ItemStack stack) {
    return CarriageItem.getWoodType(stack).textureLocation();
}
```

- [ ] **Step 4: Create the initial texture set**

Run:

```bash
cp src/main/resources/assets/sailboatmod/textures/entity/carriage.png src/main/resources/assets/sailboatmod/textures/entity/carriage_oak.png
cp src/main/resources/assets/sailboatmod/textures/entity/carriage.png src/main/resources/assets/sailboatmod/textures/entity/carriage_spruce.png
cp src/main/resources/assets/sailboatmod/textures/entity/carriage.png src/main/resources/assets/sailboatmod/textures/entity/carriage_dark_oak.png
```

Expected: three concrete texture files exist and can be iterated later without breaking resource lookup.

- [ ] **Step 5: Run verification**

Run: `./gradlew test --tests "com.monpai.sailboatmod.entity.CarriageWoodTypeTest" && ./gradlew compileJava`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/client/model/CarriageEntityModel.java src/main/java/com/monpai/sailboatmod/client/model/CarriageItemModel.java src/main/java/com/monpai/sailboatmod/client/renderer/CarriageItemRenderer.java src/main/resources/assets/sailboatmod/textures/entity/carriage_oak.png src/main/resources/assets/sailboatmod/textures/entity/carriage_spruce.png src/main/resources/assets/sailboatmod/textures/entity/carriage_dark_oak.png
git commit -m "Render carriage textures by wood type"
```

### Task 4: Tune carriage movement and add sound registration

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java`
- Create: `src/main/java/com/monpai/sailboatmod/registry/ModSounds.java`
- Modify: `src/main/java/com/monpai/sailboatmod/SailboatMod.java`
- Create: `src/main/resources/assets/sailboatmod/sounds.json`

- [ ] **Step 1: Add a movement-helper test surface before behavior changes**

```java
@Test
void roadSurfaceBonusUsesConservativeBlockChecks() {
    assertEquals(CarriageWoodType.OAK, CarriageWoodType.fromSerialized("oak"));
}
```

Expected note: if pure `BlockState` road-detection helpers are extracted during implementation, replace this placeholder test with a direct helper test in the same task rather than changing behavior untested.

- [ ] **Step 2: Implement explicit carriage tuning constants**

```java
private static final double GROUND_DRAG = 0.91D;
private static final double ROAD_DRAG = 0.96D;
private static final double AIR_DRAG = 0.88D;
private static final double ROAD_SPEED_BONUS = 0.018D;
private static final double OFFROAD_SPEED_PENALTY = 0.012D;
private static final double UPHILL_PENALTY = 0.015D;
```

```java
protected void applyTransportSupport() {
    Vec3 motion = getDeltaMovement();
    if (isPrimaryTravelMedium()) {
        double drag = isOnFinishedRoadSurface() ? ROAD_DRAG : GROUND_DRAG;
        double uphillPenalty = computeUphillPenalty();
        setDeltaMovement(motion.x * drag, onGround() ? Math.max(-0.05D, motion.y * 0.15D) : motion.y, motion.z * drag);
        if (isOnFinishedRoadSurface()) {
            addRoadSpeedBonus();
        } else {
            applyOffroadPenalty();
        }
        if (uphillPenalty > 0.0D) {
            applyUphillPenalty(uphillPenalty);
        }
        return;
    }
    setDeltaMovement(motion.x * AIR_DRAG, Math.max(-0.12D, motion.y - 0.05D), motion.z * AIR_DRAG);
}
```

- [ ] **Step 3: Add sound registry and placement hook**

```java
public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
        DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, SailboatMod.MODID);

public static final RegistryObject<SoundEvent> CARRIAGE_PLACE = register("entity.carriage.place");
public static final RegistryObject<SoundEvent> CARRIAGE_ATTACH = register("entity.carriage.attach");
public static final RegistryObject<SoundEvent> CARRIAGE_DETACH = register("entity.carriage.detach");
```

```java
// In SailboatMod constructor
ModSounds.SOUND_EVENTS.register(modEventBus);
```

```java
// In CarriageItem.use after spawn
level.playSound(null, carriage.blockPosition(), ModSounds.CARRIAGE_PLACE.get(), SoundSource.NEUTRAL, 0.9F, 1.0F);
```

- [ ] **Step 4: Create the sound resource manifest**

```json
{
  "entity.carriage.place": {
    "subtitle": "entity.sailboatmod.carriage",
    "sounds": [
      "minecraft:block.wood.place"
    ]
  },
  "entity.carriage.attach": {
    "subtitle": "entity.sailboatmod.carriage",
    "sounds": [
      "minecraft:entity.horse.saddle"
    ]
  },
  "entity.carriage.detach": {
    "subtitle": "entity.sailboatmod.carriage",
    "sounds": [
      "minecraft:entity.leash_knot.break"
    ]
  }
}
```

- [ ] **Step 5: Run verification**

Run: `./gradlew test --tests "com.monpai.sailboatmod.entity.CarriageWoodTypeTest" && ./gradlew compileJava && ./gradlew test`

Expected: targeted test PASS, compile succeeds, full test suite still green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java src/main/java/com/monpai/sailboatmod/registry/ModSounds.java src/main/java/com/monpai/sailboatmod/SailboatMod.java src/main/resources/assets/sailboatmod/sounds.json
git commit -m "Tune carriage movement and sounds"
```

### Task 5: Final verification and review

**Files:**
- Modify: `docs/superpowers/plans/2026-04-10-carriage-system-wrap-up.md`

- [ ] **Step 1: Mark completed plan items and review diff**

Run: `git status --short && git diff --stat`

Expected: only the planned carriage files and the plan file differ.

- [ ] **Step 2: Run final build verification**

Run: `./gradlew compileJava && ./gradlew test && ./gradlew build`

Expected: all commands PASS and `build/libs/` contains the generated jars.

- [ ] **Step 3: Summarize manual verification targets**

```text
- Sneak-right-click a carriage item to cycle oak/spruce/dark_oak.
- Place each carriage wood type and verify the entity texture matches.
- Reload the world and verify the placed carriage keeps its wood type.
- Compare on-road versus off-road feel and uphill slowdown.
- Verify carriage placement uses the registered carriage placement sound.
```

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/plans/2026-04-10-carriage-system-wrap-up.md
git commit -m "Document carriage wrap-up verification"
```
