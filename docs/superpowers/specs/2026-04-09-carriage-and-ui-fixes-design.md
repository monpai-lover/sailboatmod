# Carriage Material, Motion, and Claims UI Fixes Design

## Scope

This design covers the confirmed issues for the current pass:

1. Carriage body material should vary by wood type.
2. The claims-map reset/refresh controls should not appear outside the claims map view.
3. Carriage motion and sounds should stop feeling like a sailboat.
4. Finished roads should provide a speed bonus to carriages.

This pass does not include the broader manual road anchor redesign, station waiting-area routing, or road width/slope generation refactor. Those remain separate follow-up work.

## Goals

- Support three fixed carriage wood variants: oak, spruce, and dark oak.
- Persist carriage wood type across item stacks, placement, entity sync, and save/load.
- Restrict map-only controls to the actual claims map subpage in both town and nation screens.
- Give carriages a ground-vehicle movement profile instead of inherited sailboat feel.
- Add carriage-appropriate placement/attach/detach/rolling sounds.
- Apply a controlled speed bonus only while the carriage is travelling on completed road surfaces.

## Non-Goals

- No new carriage model geometry.
- No runtime recoloring system.
- No new separate carriage entity base class in this pass.
- No changes to ship handling.
- No expansion beyond the three selected wood families.

## Design Decisions

### 1. Carriage Wood Variants

Carriage material is a fixed property chosen on the item stack and copied into the placed entity.

- Add a `CarriageWoodType` enum that owns:
  - stable serialized id
  - item/entity NBT token
  - translation key suffix
  - entity texture path
  - item texture path if item rendering needs direct lookup
- Keep a single registered carriage item id: `sailboatmod:carriage`.
- Store the selected wood type in the item stack tag.
- On placement, `CarriageItem` writes the selected wood type into `CarriageEntity`.
- `CarriageEntity` stores wood type in synced entity data and in save NBT.
- `CarriageEntityModel` and `CarriageItemModel` resolve texture resources from the wood type.

This avoids item registry bloat and keeps future wood expansion additive.

### 2. Claims Map Buttons

The reset and refresh controls are map tools, not page tools.

- In both `TownHomeScreen` and `NationHomeScreen`, gate these controls with `claimsMapView`.
- The controls must be hidden and inactive on the permissions subpage and on any non-claims page.
- Layout calls and visibility updates must use the same predicate so stale widget positions do not leak through page changes.

### 3. Carriage Motion

`CarriageEntity` will continue to inherit route, dock, storage, and autopilot integration from `SailboatEntity`, but carriage-specific motion will become an explicit layer with its own constants and checks.

- Disable sail-specific behavior for carriages.
- Replace sailboat-style movement feel with carriage-specific tuning:
  - lower base top speed than sailboat cruise
  - faster ground damping than water glide
  - reduced turn authority at higher speed
  - stronger slowdown while off stable ground
  - mild uphill penalty to avoid unrealistic slope climbing
- Ground validity remains based on sturdy, dry blocks below the carriage.
- The carriage should still use the existing route/autopilot pipeline, but its acceleration and retention should be tuned around land travel rather than sail states.

The implementation target is not a brand-new transport architecture. It is a carriage-specific override layer that removes the obvious boat feel without breaking existing route features.

### 4. Carriage Sounds

Add carriage-specific sound registration under the mod namespace.

- Register at minimum:
  - `entity.carriage.place`
  - `entity.carriage.attach`
  - `entity.carriage.detach`
- Reuse existing vanilla wood or horse-related sounds where a continuous custom rolling loop is not yet available.
- Trigger placement on successful spawn.
- Trigger attach/detach around carriage pull-state transitions or the closest equivalent interaction points available in current code.
- Replace any remaining sailboat-only sound hooks used by carriage interactions.

Reference behavior should follow Astikor’s intent, but implementation stays within this mod’s existing sound registry patterns.

### 5. Road Speed Bonus

Road acceleration is a carriage-only surface bonus.

- The bonus applies only when the carriage is on recognized finished road surface blocks.
- The first-pass road surface check will reuse existing construction output recognition instead of introducing a second road-tagging system.
- The bonus must be modest and capped so the carriage does not become unstable on slopes or while autopilot is turning.
- The same road bonus applies to manual driving and autopilot.
- Off-road travel immediately falls back to normal carriage movement.

## Data Flow

### Wood Variant Flow

1. Player obtains or configures a carriage item stack with a wood type.
2. Item stack NBT stores the serialized wood id.
3. On placement, `CarriageItem` reads the item tag and assigns the type to the new entity.
4. `CarriageEntity` syncs the type to clients and persists it to save data.
5. Client-side models resolve the correct carriage texture from the entity or item.

### Movement Flow

1. Existing control and route systems produce movement intent.
2. `CarriageEntity` applies carriage-specific ground handling rules.
3. A road-surface probe checks the finished road blocks beneath the carriage footprint.
4. If on-road, apply a bounded movement bonus.
5. Emit carriage sounds at placement and attachment state transitions.

## Files Expected to Change

- `src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java`
- `src/main/java/com/monpai/sailboatmod/item/CarriageItem.java`
- `src/main/java/com/monpai/sailboatmod/client/model/CarriageEntityModel.java`
- `src/main/java/com/monpai/sailboatmod/client/model/CarriageItemModel.java`
- `src/main/java/com/monpai/sailboatmod/client/renderer/CarriageEntityRenderer.java`
- `src/main/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreen.java`
- `src/main/java/com/monpai/sailboatmod/client/screen/nation/NationHomeScreen.java`
- `src/main/java/com/monpai/sailboatmod/registry/ModItems.java`
- `src/main/java/com/monpai/sailboatmod/registry/...` for sound registration if no dedicated sound registry exists yet
- `src/main/resources/assets/sailboatmod/textures/entity/...` for the three carriage textures
- `src/main/resources/assets/sailboatmod/lang/en_us.json`
- `src/main/resources/assets/sailboatmod/lang/zh_cn.json`
- `src/main/resources/assets/sailboatmod/sounds.json`

## Error Handling and Compatibility

- Unknown or missing wood ids must fall back to oak.
- Existing placed carriages without saved wood data must load as oak.
- Existing carriage items without wood NBT must behave as oak.
- Road bonus logic must default to no bonus if the surface check is inconclusive.
- UI visibility logic must fail closed: hidden unless on the claims map view.

## Testing and Verification

Minimum validation for this pass:

- `./gradlew.bat compileJava`
- `./gradlew.bat build`
- Confirm generated jar under `build/libs`
- Manual code-path verification for:
  - carriage wood fallback behavior
  - map control visibility in nation/town claims pages
  - carriage ground-motion constants and road bonus gating
  - sound registration/resource wiring

Recommended in-game checks after build:

- Spawn one carriage for each wood type and verify texture selection.
- Switch between claims map and claims permissions in town and nation UI and confirm the map controls only show on the map.
- Drive a carriage on normal terrain versus finished road and compare top speed/feel.
- Verify carriage placement and coupling interactions use carriage-oriented sounds.
