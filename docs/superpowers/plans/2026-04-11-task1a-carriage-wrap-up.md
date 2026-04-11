# Task 1A Carriage Wrap-Up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the remaining carriage wood-type, persistence, rendering, movement, and sound behavior so the carriage section of `未完成_任务_1.md` can be marked complete.

**Architecture:** Keep `CarriageEntity` on top of the existing `SailboatEntity` integration, but make carriage-only state explicit with `CarriageWoodType`, item/entity persistence helpers, focused motion helpers, and a dedicated sound registry. Use small helper-level tests for wood selection, road/off-road movement decisions, and attach/detach sound cues, then wire the renderer and runtime code to those helpers without introducing a second transport system.

**Tech Stack:** Java 17, Forge 1.20.1, GeckoLib, JUnit 5, Gradle

---

### Task 1: Stabilize the carriage wood-type domain model

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

    @Test
    void exposesTranslationKeysAndTextureLocations() {
        assertEquals("item.sailboatmod.carriage.wood.oak", CarriageWoodType.OAK.translationKey());
        assertEquals("item.sailboatmod.carriage.wood.dark_oak", CarriageWoodType.DARK_OAK.translationKey());
        assertEquals("sailboatmod:textures/entity/carriage_oak.png", CarriageWoodType.OAK.textureLocation().toString());
        assertEquals("sailboatmod:textures/entity/carriage_spruce.png", CarriageWoodType.SPRUCE.textureLocation().toString());
        assertEquals("sailboatmod:textures/entity/carriage_dark_oak.png", CarriageWoodType.DARK_OAK.textureLocation().toString());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.entity.CarriageWoodTypeTest"`

Expected: FAIL with missing `CarriageWoodType` class or missing `fromSerialized`, `next`, `translationKey`, or `textureLocation` methods.

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

    CarriageWoodType(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    public String translationKey() {
        return "item.sailboatmod.carriage.wood." + serializedName;
    }

    public ResourceLocation textureLocation() {
        return new ResourceLocation(SailboatMod.MODID, "textures/entity/carriage_" + serializedName + ".png");
    }

    public CarriageWoodType next() {
        CarriageWoodType[] values = values();
        return values[(ordinal() + 1) % values.length];
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

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.entity.CarriageWoodTypeTest"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/entity/CarriageWoodType.java src/test/java/com/monpai/sailboatmod/entity/CarriageWoodTypeTest.java
git commit -m "feat: add carriage wood type model"
```

### Task 2: Persist wood type on the carriage item and placed entity

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/item/CarriageItem.java`
- Modify: `src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java`
- Create: `src/test/java/com/monpai/sailboatmod/item/CarriageItemTest.java`
- Modify: `src/main/resources/assets/sailboatmod/lang/en_us.json`
- Modify: `src/main/resources/assets/sailboatmod/lang/zh_cn.json`

- [ ] **Step 1: Write the failing test**

```java
package com.monpai.sailboatmod.item;

import com.monpai.sailboatmod.entity.CarriageWoodType;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CarriageItemTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void missingWoodTagFallsBackToOak() {
        ItemStack stack = new ItemStack(Items.STICK);
        assertEquals(CarriageWoodType.OAK, CarriageItem.getWoodType(stack));
    }

    @Test
    void writesAndReadsWoodTypeFromStackTag() {
        ItemStack stack = new ItemStack(Items.STICK);
        CarriageItem.setWoodType(stack, CarriageWoodType.SPRUCE);
        assertEquals(CarriageWoodType.SPRUCE, CarriageItem.getWoodType(stack));
        CarriageItem.setWoodType(stack, CarriageWoodType.DARK_OAK);
        assertEquals(CarriageWoodType.DARK_OAK, CarriageItem.getWoodType(stack));
    }

    @Test
    void cycleWoodTypeAdvancesAndWraps() {
        ItemStack stack = new ItemStack(Items.STICK);
        assertEquals(CarriageWoodType.SPRUCE, CarriageItem.cycleWoodType(stack));
        assertEquals(CarriageWoodType.SPRUCE, CarriageItem.getWoodType(stack));
        assertEquals(CarriageWoodType.DARK_OAK, CarriageItem.cycleWoodType(stack));
        assertEquals(CarriageWoodType.OAK, CarriageItem.cycleWoodType(stack));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.item.CarriageItemTest"`

Expected: FAIL with missing `getWoodType`, `setWoodType`, or `cycleWoodType`.

- [ ] **Step 3: Write the minimal item helpers**

```java
public static final String TAG_WOOD_TYPE = "WoodType";

public static CarriageWoodType getWoodType(ItemStack stack) {
    CompoundTag tag = stack.getTag();
    return CarriageWoodType.fromSerialized(tag == null ? null : tag.getString(TAG_WOOD_TYPE));
}

public static void setWoodType(ItemStack stack, CarriageWoodType woodType) {
    stack.getOrCreateTag().putString(TAG_WOOD_TYPE, woodType.serializedName());
}

public static CarriageWoodType cycleWoodType(ItemStack stack) {
    CarriageWoodType next = getWoodType(stack).next();
    setWoodType(stack, next);
    return next;
}
```

- [ ] **Step 4: Add entity sync and persistence**

```java
private static final EntityDataAccessor<String> DATA_WOOD_TYPE =
        SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.STRING);

@Override
protected void defineSynchedData() {
    super.defineSynchedData();
    this.entityData.define(DATA_WOOD_TYPE, CarriageWoodType.OAK.serializedName());
}

public CarriageWoodType getWoodType() {
    return CarriageWoodType.fromSerialized(this.entityData.get(DATA_WOOD_TYPE));
}

public void setWoodType(CarriageWoodType woodType) {
    this.entityData.set(DATA_WOOD_TYPE, woodType == null ? CarriageWoodType.OAK.serializedName() : woodType.serializedName());
}
```

```java
@Override
protected void addAdditionalSaveData(CompoundTag tag) {
    super.addAdditionalSaveData(tag);
    tag.putString("WoodType", getWoodType().serializedName());
}

@Override
protected void readAdditionalSaveData(CompoundTag tag) {
    super.readAdditionalSaveData(tag);
    setWoodType(CarriageWoodType.fromSerialized(tag.getString("WoodType")));
}
```

- [ ] **Step 5: Wire item use and tooltip**

```java
if (player.isSecondaryUseActive()) {
    CarriageWoodType next = cycleWoodType(itemstack);
    if (!level.isClientSide) {
        player.displayClientMessage(
                Component.translatable("item.sailboatmod.carriage.wood.selected", Component.translatable(next.translationKey())),
                true
        );
    }
    return InteractionResultHolder.sidedSuccess(itemstack, level.isClientSide());
}
```

```java
carriage.setWoodType(getWoodType(itemstack));
```

```java
@Override
public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
    tooltip.add(Component.translatable("item.sailboatmod.carriage.wood", Component.translatable(getWoodType(stack).translationKey())));
    tooltip.add(Component.translatable("item.sailboatmod.carriage.tip.cycle"));
}
```

- [ ] **Step 6: Run the targeted tests to verify they pass**

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.entity.CarriageWoodTypeTest" --tests "com.monpai.sailboatmod.item.CarriageItemTest"`

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/item/CarriageItem.java src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java src/test/java/com/monpai/sailboatmod/item/CarriageItemTest.java src/main/resources/assets/sailboatmod/lang/en_us.json src/main/resources/assets/sailboatmod/lang/zh_cn.json
git commit -m "feat: persist carriage wood selection"
```

### Task 3: Render distinct carriage wood variants

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/model/CarriageEntityModel.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/model/CarriageItemModel.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/renderer/CarriageItemRenderer.java`
- Create: `src/main/resources/assets/sailboatmod/textures/entity/carriage_oak.png`
- Create: `src/main/resources/assets/sailboatmod/textures/entity/carriage_spruce.png`
- Create: `src/main/resources/assets/sailboatmod/textures/entity/carriage_dark_oak.png`

- [ ] **Step 1: Write the failing tests first**

Use the `CarriageWoodTypeTest` assertions from Task 1 for texture resource paths. Do not add a renderer-instantiation test here because Forge item registration freezes make that test fragile in this repo.

- [ ] **Step 2: Run the existing texture assertions to verify red**

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.entity.CarriageWoodTypeTest"`

Expected: FAIL if `textureLocation()` is not yet correct.

- [ ] **Step 3: Wire entity and item models to the wood type**

```java
// In CarriageEntityModel
@Override
public ResourceLocation getTextureResource(CarriageEntity animatable) {
    return animatable.getWoodType().textureLocation();
}
```

```java
// In CarriageItemRenderer
private static final ThreadLocal<ItemStack> CURRENT_STACK = ThreadLocal.withInitial(() -> ItemStack.EMPTY);

public static ItemStack currentItemStack() {
    return CURRENT_STACK.get();
}
```

```java
CURRENT_STACK.set(stack);
super.renderByItem(stack, displayContext, poseStack, bufferSource, packedLight, packedOverlay);
CURRENT_STACK.remove();
```

```java
// In CarriageItemModel
@Override
public ResourceLocation getTextureResource(CarriageItem animatable) {
    return CarriageItem.getWoodType(CarriageItemRenderer.currentItemStack()).textureLocation();
}
```

- [ ] **Step 4: Create the three concrete textures**

Run:

```bash
ffmpeg -y -i src/main/resources/assets/sailboatmod/textures/entity/carriage.png -vf "colorchannelmixer=rr=1.08:gg=1.00:bb=0.88" -frames:v 1 -update 1 src/main/resources/assets/sailboatmod/textures/entity/carriage_oak.png
ffmpeg -y -i src/main/resources/assets/sailboatmod/textures/entity/carriage.png -vf "colorchannelmixer=rr=0.82:gg=0.95:bb=0.78" -frames:v 1 -update 1 src/main/resources/assets/sailboatmod/textures/entity/carriage_spruce.png
ffmpeg -y -i src/main/resources/assets/sailboatmod/textures/entity/carriage.png -vf "colorchannelmixer=rr=0.58:gg=0.50:bb=0.42" -frames:v 1 -update 1 src/main/resources/assets/sailboatmod/textures/entity/carriage_dark_oak.png
```

Expected: the three generated PNGs exist and are visibly distinct.

- [ ] **Step 5: Run verification**

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false compileJava test --tests "com.monpai.sailboatmod.entity.CarriageWoodTypeTest" --tests "com.monpai.sailboatmod.item.CarriageItemTest"`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/client/model/CarriageEntityModel.java src/main/java/com/monpai/sailboatmod/client/model/CarriageItemModel.java src/main/java/com/monpai/sailboatmod/client/renderer/CarriageItemRenderer.java src/main/resources/assets/sailboatmod/textures/entity/carriage_oak.png src/main/resources/assets/sailboatmod/textures/entity/carriage_spruce.png src/main/resources/assets/sailboatmod/textures/entity/carriage_dark_oak.png
git commit -m "feat: render carriage wood variants"
```

### Task 4: Close carriage movement and sound cues

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java`
- Create: `src/main/java/com/monpai/sailboatmod/registry/ModSounds.java`
- Modify: `src/main/java/com/monpai/sailboatmod/SailboatMod.java`
- Create: `src/main/resources/assets/sailboatmod/sounds.json`
- Create: `src/test/java/com/monpai/sailboatmod/entity/CarriageEntityMovementTest.java`
- Create: `src/test/java/com/monpai/sailboatmod/entity/CarriageEntitySoundCueTest.java`

- [ ] **Step 1: Write the failing movement-helper test**

```java
package com.monpai.sailboatmod.entity;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarriageEntityMovementTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void recognizesFinishedRoadLikeSurfaces() {
        assertTrue(CarriageEntity.isRoadSurfaceForTest(Blocks.STONE_BRICKS.defaultBlockState()));
        assertTrue(CarriageEntity.isRoadSurfaceForTest(Blocks.STONE_BRICK_SLAB.defaultBlockState()));
        assertTrue(CarriageEntity.isRoadSurfaceForTest(Blocks.STONE_BRICK_STAIRS.defaultBlockState()));
        assertFalse(CarriageEntity.isRoadSurfaceForTest(Blocks.DIRT.defaultBlockState()));
    }
}
```

```java
package com.monpai.sailboatmod.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CarriageEntitySoundCueTest {
    @Test
    void detectsAttachDetachAndNoChangePassengerTransitions() {
        assertEquals(CarriageEntity.PassengerSoundCue.NONE, CarriageEntity.passengerSoundCueForTest(0, 0));
        assertEquals(CarriageEntity.PassengerSoundCue.ATTACH, CarriageEntity.passengerSoundCueForTest(0, 1));
        assertEquals(CarriageEntity.PassengerSoundCue.ATTACH, CarriageEntity.passengerSoundCueForTest(1, 3));
        assertEquals(CarriageEntity.PassengerSoundCue.DETACH, CarriageEntity.passengerSoundCueForTest(2, 1));
        assertEquals(CarriageEntity.PassengerSoundCue.NONE, CarriageEntity.passengerSoundCueForTest(2, 2));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.entity.CarriageEntityMovementTest" --tests "com.monpai.sailboatmod.entity.CarriageEntitySoundCueTest"`

Expected: FAIL with missing road-surface or passenger-sound helper methods.

- [ ] **Step 3: Write minimal carriage movement helpers**

```java
private static final double ROAD_DRAG = 0.96D;
private static final double GROUND_DRAG = 0.91D;
private static final double AIR_DRAG = 0.88D;
private static final double ROAD_ACCEL_BONUS = 0.012D;
private static final double OFFROAD_PENALTY = 0.005D;
private static final double UPHILL_PENALTY = 0.01D;
```

```java
private static boolean isRoadSurfaceState(BlockState state) {
    String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    return path.contains("stone_brick") || path.contains("road");
}

public static boolean isRoadSurfaceForTest(BlockState state) {
    return isRoadSurfaceState(state);
}
```

```java
@Override
protected void applyTransportSupport() {
    if (level() == null) {
        return;
    }
    Vec3 motion = getDeltaMovement();
    if (isPrimaryTravelMedium()) {
        boolean onRoad = isOnFinishedRoadSurface();
        double drag = onRoad ? ROAD_DRAG : GROUND_DRAG;
        double clampedY = onGround() ? Math.max(-0.05D, motion.y * 0.15D) : motion.y;
        Vec3 adjusted = new Vec3(motion.x * drag, clampedY, motion.z * drag);
        adjusted = addForwardBonus(adjusted, onRoad ? ROAD_ACCEL_BONUS : -OFFROAD_PENALTY);
        if (isClimbing()) {
            adjusted = addForwardBonus(adjusted, -UPHILL_PENALTY);
        }
        setDeltaMovement(adjusted);
        return;
    }
    setDeltaMovement(motion.x * AIR_DRAG, Math.max(-0.12D, motion.y - 0.05D), motion.z * AIR_DRAG);
}
```

- [ ] **Step 4: Register and wire sounds**

```java
public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
        DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, SailboatMod.MODID);

public static final RegistryObject<SoundEvent> CARRIAGE_PLACE = register("entity.carriage.place");
public static final RegistryObject<SoundEvent> CARRIAGE_ATTACH = register("entity.carriage.attach");
public static final RegistryObject<SoundEvent> CARRIAGE_DETACH = register("entity.carriage.detach");
```

```java
ModSounds.SOUND_EVENTS.register(modEventBus);
```

```java
level.playSound(null, carriage.blockPosition(), ModSounds.CARRIAGE_PLACE.get(), SoundSource.NEUTRAL, 0.9F, 1.0F);
```

```java
private static PassengerSoundCue passengerSoundCue(int previousCount, int currentCount) {
    if (currentCount > previousCount) {
        return PassengerSoundCue.ATTACH;
    }
    if (currentCount < previousCount) {
        return PassengerSoundCue.DETACH;
    }
    return PassengerSoundCue.NONE;
}
```

```java
@Override
public void tick() {
    super.tick();
    if (!level().isClientSide) {
        int currentPassengerCount = getPassengers().size();
        PassengerSoundCue cue = passengerSoundCue(lastPassengerCount, currentPassengerCount);
        if (cue == PassengerSoundCue.ATTACH) {
            level().playSound(null, blockPosition(), ModSounds.CARRIAGE_ATTACH.get(), SoundSource.NEUTRAL, 0.85F, 1.0F);
        } else if (cue == PassengerSoundCue.DETACH) {
            level().playSound(null, blockPosition(), ModSounds.CARRIAGE_DETACH.get(), SoundSource.NEUTRAL, 0.85F, 1.0F);
        }
        lastPassengerCount = currentPassengerCount;
    }
}
```

- [ ] **Step 5: Create `sounds.json`**

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

- [ ] **Step 6: Run verification**

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false compileJava test --tests "com.monpai.sailboatmod.entity.CarriageWoodTypeTest" --tests "com.monpai.sailboatmod.item.CarriageItemTest" --tests "com.monpai.sailboatmod.entity.CarriageEntityMovementTest" --tests "com.monpai.sailboatmod.entity.CarriageEntitySoundCueTest"`

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java src/main/java/com/monpai/sailboatmod/registry/ModSounds.java src/main/java/com/monpai/sailboatmod/SailboatMod.java src/main/resources/assets/sailboatmod/sounds.json src/test/java/com/monpai/sailboatmod/entity/CarriageEntityMovementTest.java src/test/java/com/monpai/sailboatmod/entity/CarriageEntitySoundCueTest.java
git commit -m "feat: close carriage movement and sound cues"
```

### Task 5: Final verification and closeout notes

**Files:**
- Modify: `docs/superpowers/plans/2026-04-11-task1a-carriage-wrap-up.md`
- Modify: `未完成_任务_1.md`

- [ ] **Step 1: Review the carriage diff**

Run: `git status --short && git diff --stat`

Expected: only carriage-specific source, resource, test, and plan files differ.

- [ ] **Step 2: Run full project verification**

Run: `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test`

Expected: PASS

- [ ] **Step 3: Record manual verification checklist**

```text
- Sneak-right-click a carriage item and verify oak -> spruce -> dark_oak -> oak cycling.
- Place each wood variant and verify the entity and item render with the correct texture.
- Save and reload the world and verify the placed carriage keeps its wood type.
- Board and dismount the carriage and verify attach/detach sounds trigger once per transition.
- Compare on-road, off-road, and uphill handling to confirm the carriage no longer feels like a sailboat.
```

- [ ] **Step 4: Update the task file**

Replace the carriage section in `未完成_任务_1.md` with a short completed note referencing the spec and this plan. Remove any wording that still implies carriage closure depends on the missing stash.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/plans/2026-04-11-task1a-carriage-wrap-up.md 未完成_任务_1.md
git commit -m "docs: close task 1a carriage wrap-up"
```
