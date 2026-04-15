# Village-Style Road Lighting Design

## Scope

This design covers one focused road-lighting pass:

1. Add actual bridge-pier lighting coverage where pier-supported bridges exist.
2. Unify bridge and land road lights around a village-style hanging-lantern silhouette.
3. Keep land road lighting spacing roughly as-is.
4. Make bridge lighting denser than land lighting without flooding the bridge deck.

This pass does not introduce NBT structure templates, a new standalone road-lighting system, or a rewrite of road corridor planning.

## Goals

- Use the existing road corridor and ghost/build-step pipeline.
- Keep light placement driven by corridor anchor positions rather than structure templates.
- Render both land and bridge lights as a compact "post + arm + hanging lantern" shape inspired by vanilla village lamps.
- Preserve current material adaptation by biome and road style instead of forcing one global palette.
- Add bridge-pier lighting only where a real pier-supported bridge segment exists.
- Keep horizontal arms pointed away from the walk/driving surface.

## Non-Goals

- No new block registrations.
- No new light source type beyond vanilla `LANTERN`.
- No new corridor data model for explicit lamp piece definitions.
- No attempt to light non-pier bridge spans with fake freestanding pier lamps.
- No full aesthetic overhaul of bridge railings or road surfaces outside the lighting work.

## Design Decisions

### 1. Keep the Existing Anchor-Based Pipeline

Road lighting already flows through corridor slice light-anchor positions and then through `StructureConstructionManager` ghost/build-step expansion. This pass keeps that pipeline intact.

- `RoadCorridorPlanner` remains responsible for deciding where lights belong.
- `StructureConstructionManager` remains responsible for translating those anchors into ghost blocks and build steps.
- Existing preview, phased construction, placed-step detection, and resume logic continue to operate on normal road ghost blocks.

This keeps the change localized and avoids destabilizing the road runtime.

### 2. Unified Lamp Shape

Each eligible road-light anchor will expand into a village-style hanging-lantern assembly:

- `base`: anchor position
- `post`: vertical support rising two blocks above the base
- `arm`: one-block horizontal extension from the top of the post
- `lantern`: a lantern hanging one block below the arm

If a safe arm direction cannot be determined for a given anchor, the renderer may fall back to a simplified vertical lamp stack for that one anchor rather than invalidating the whole slice.

### 3. Material Mapping

The silhouette is unified, but material selection still follows the current road/bridge style logic.

- Land roads keep their existing biome/style-sensitive base and support mapping.
- Bridge decks and bridge piers keep their current bridge material family for vertical support pieces.
- The horizontal arm should use a wood-like block family selected from the current placement style rather than a hardcoded global block set.
- Lanterns remain vanilla `LANTERN`.

The desired visual result is "same lamp language, local materials."

### 4. Land Lighting Behavior

Land lighting continues to use the existing alternating side-placement pattern and current interval target:

- Keep `LAND_STREETLIGHT_INTERVAL = 24` in this pass.
- Keep lights on one side at a time rather than mirroring both sides.
- Aim arms away from the road centerline so the lantern hangs off the road edge instead of over the travel surface.

This preserves readability and avoids making normal roads feel cluttered.

### 5. Bridge Deck Lighting Behavior

Bridge deck lighting stays denser than land lighting and continues to depend on bridge segment kind.

- Existing `railingLightPositions` remain the primary source for deck-edge lamps.
- Bridge head and support-span slices can stay on the current roughly-every-8-block rhythm.
- Navigable main spans keep the current looser rhythm driven by `BRIDGE_LIGHT_INTERVAL + 1`.
- Arms point outward from the bridge, never inward over the deck center.

Bridge lighting should feel intentionally brighter than land roads without turning into a continuous fence of lanterns.

### 6. Bridge-Pier Lighting

Current corridor planning leaves `pierLightPositions` empty, so this pass adds actual pier-light anchor generation for pier-supported bridge plans.

- Only `PIER_BRIDGE` segments with a real support node/foundation are eligible.
- Each usable pier should receive a compact lamp anchor near the top/outside of the pier, not in the deck center.
- Default to one lamp group per effective pier location.
- Successive pier lamps should alternate side across the bridge length to avoid visual over-concentration.
- Non-pier bridge modes do not synthesize fake pier lamps.

This gives actual bridge-pier illumination while respecting the physical bridge mode already chosen by the planner.

### 7. Direction and Clearance Rules

Lamp expansion must remain compatible with road use and bridge railings.

- Determine side direction from the same center-path lateral logic already used for road-edge placement.
- Prefer the outward-facing side for the horizontal arm.
- Avoid overwriting required travel-surface blocks.
- Avoid unnecessarily replacing railing/wall blocks that are part of bridge edge definition.
- Where a lamp and a railing occupy the same column, preserve the railing intent and attach the lantern above/outboard instead of deleting the edge treatment.

### 8. Preview and Build-Step Compatibility

All lamp pieces must still serialize as ordinary road ghost blocks/build steps.

- Ghost preview should show the final lamp silhouette.
- Build-step ordering should remain stable enough that supports place before the lantern.
- Completed-step detection must still recognize already-placed lamp blocks as satisfying the corresponding build step.

No new persistence format should be required for runtime road jobs.

## Data Flow

### Land Road Flow

1. `RoadCorridorPlanner` emits land `railingLightPositions` at the current alternating interval.
2. `StructureConstructionManager` resolves road side direction for each anchor.
3. The anchor expands into base/post/arm/lantern ghost blocks using local land material mapping.
4. Existing road build-step generation handles preview, placement, and completion tracking.

### Bridge Deck Flow

1. `RoadCorridorPlanner` emits bridge `railingLightPositions` according to bridge segment kind and interval rules.
2. `StructureConstructionManager` expands each anchor into the unified village-style lamp silhouette.
3. Lamp arms face away from the bridge deck centerline.
4. Existing ghost/build-step processing handles placement ordering and completion tracking.

### Bridge Pier Flow

1. Pier-supported bridge planning exposes support nodes/foundation positions already used for support generation.
2. `RoadCorridorPlanner` derives `pierLightPositions` from those real support nodes.
3. `StructureConstructionManager` expands the pier anchors into village-style hanging lamps with bridge-appropriate support materials.
4. Existing preview and build-step systems treat pier lamps like any other road-construction ghost blocks.

## Files Expected to Change

- `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- `src/test/java/com/monpai/sailboatmod/...` for corridor-planner and road-lighting coverage as needed

## Error Handling and Compatibility

- If a lamp anchor is missing a safe outward arm direction, fall back to a simpler vertical lamp at that anchor.
- If a bridge slice has no real pier support node, do not generate pier lighting for it.
- If an existing placed block already satisfies a lamp piece build step, normal completed-step detection should treat it as already built.
- If bridge edge geometry and lamp placement conflict in one column, prefer preserving navigability and bridge-edge integrity over forcing the full lamp silhouette.

## Testing and Verification

Minimum validation for this pass:

- `.\gradlew.bat compileJava`

Recommended targeted tests:

- Corridor-planner coverage for `pierLightPositions` on pier-supported bridge plans.
- No `pierLightPositions` for non-pier bridge modes.
- Stable outward-side selection for land and bridge lamp arms.

Recommended in-game checks:

- Land road segment: confirm alternating village-style lamps keep current approximate spacing and do not hang over the road center.
- Non-navigable bridge: confirm denser deck lighting and outward-facing lantern arms.
- Pier-supported large bridge: confirm actual bridge-pier lighting appears near pier tops and visually alternates or stays balanced.
- Preview/build flow: confirm ghost blocks, staged construction, and already-placed detection all still behave correctly.
