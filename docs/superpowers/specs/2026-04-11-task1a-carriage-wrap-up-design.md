# Task 1A Carriage Wrap-Up Design

## Scope

This design closes the carriage-specific items from `未完成_任务_1.md` using the code that already exists in the worktree as the baseline.

Included:

1. carriage wood-type persistence on item stack, entity sync, and save data
2. distinct oak/spruce/dark oak carriage rendering
3. carriage placement/attach/detach sound registration
4. carriage-tuned land movement rules on road, off-road, and uphill travel
5. helper-level tests that prove the above behavior

Not included:

- a full inheritance split away from `SailboatEntity`
- continuous rolling audio
- new carriage geometry or a shader-based material system

## Current State

The worktree already contains the main closure implementation:

- `CarriageWoodType` exists and maps ids, translation keys, and textures
- `CarriageItem` stores and cycles wood type
- `CarriageEntity` syncs wood type and applies carriage-specific motion constants
- carriage textures and sound registry resources already exist
- focused tests already exist for wood parsing, item persistence, movement, and sound cues

This sub-project is therefore a closure and verification pass, not a greenfield build.

## Goal

Make the carriage section of `未完成_任务_1.md` truthfully closable with code and fresh verification evidence.

## Done Criteria

- Missing or invalid carriage wood data falls back to oak.
- Right-click cycling on the carriage item updates the stack NBT and user-facing message.
- Placed carriage entities preserve wood type through entity sync and save/load.
- `CarriageEntityModel`, `CarriageItemModel`, and `CarriageItemRenderer` resolve the correct oak/spruce/dark-oak textures.
- Placement, attach, and detach sound events are registered under `ModSounds` and wired into runtime behavior.
- Road/off-road/uphill handling uses explicit carriage constants rather than ship-like drift.
- The targeted carriage tests pass, and the full Gradle test suite is run before claiming completion.

## Design Decisions

### 1. Wood type remains a small explicit enum

Keep wood selection as a fixed enum with three values:

- `oak`
- `spruce`
- `dark_oak`

`CarriageWoodType` owns all carriage-wood lookup rules:

- serialized id
- translation key
- entity texture location
- stable `next()` cycling order
- default fallback behavior

This keeps item and entity code narrow and makes unknown historical data load safely.

### 2. Item stack is the user-editable source of truth before placement

`CarriageItem` owns the stack NBT helpers:

- `getWoodType`
- `setWoodType`
- `cycleWoodType`

Secondary use cycles the wood on the held item stack and surfaces the selected variant through existing translated messages. Placement copies the selected wood into the new `CarriageEntity`.

### 3. Entity sync stays inside the current transport architecture

The carriage should keep the route/storage/autopilot integration already inherited from `SailboatEntity`, but carriage-only state must be explicit:

- synced entity data for wood type
- save/load NBT for persistence
- carriage-specific motion constants
- carriage-specific attach/detach sound cues

This is the minimum-risk way to remove the obvious sailboat feel without opening a large architecture rewrite.

### 4. Rendering uses per-wood texture resolution, not runtime tinting

Use the existing per-variant textures:

- `carriage_oak.png`
- `carriage_spruce.png`
- `carriage_dark_oak.png`

Model and item rendering should simply resolve the texture from `CarriageWoodType`. That is already enough to close the user-visible material requirement without introducing a tint-layer system.

### 5. Movement closure means land-biased handling, not perfect vehicle simulation

The closure target is:

- stronger drag than sailboat motion
- bonus when travelling on finished road
- explicit off-road slowdown
- explicit uphill penalty
- safe airborne fallback when the carriage loses stable ground

That is sufficient for `未完成_任务_1.md`. A future full carriage-physics rewrite is out of scope.

## Testing And Verification

Automated evidence required:

- `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.entity.CarriageWoodTypeTest"`
- `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.item.CarriageItemTest"`
- `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.entity.CarriageEntityMovementTest"`
- `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.entity.CarriageEntitySoundCueTest"`
- `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test`

Manual verification notes required before closing the task file:

- place one carriage of each wood type and confirm texture selection
- cycle wood on the item stack and confirm the action-bar message
- board/unboard or attach/detach through the supported interaction and confirm sound cues

## Risks And Controls

Risk: old carriages or old item stacks load broken because they do not have wood data.

Control: all item/entity reads must funnel through `CarriageWoodType.fromSerialized(...)`, which defaults to oak.

Risk: movement changes accidentally break generic transport behavior.

Control: keep overrides limited to carriage-specific methods and validate with targeted movement tests plus full-suite verification.

## Recommendation

Treat task 1A as already mostly implemented. The remaining work is to keep the plan/spec aligned with reality, re-run verification, and only then mark the carriage checklist items complete.
