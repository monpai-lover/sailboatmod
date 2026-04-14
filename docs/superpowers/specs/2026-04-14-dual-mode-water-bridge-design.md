# Dual Mode Water Bridge Design

Date: 2026-04-14

## Goal

Reshape water-crossing road construction so bridges behave like deliberate structures instead of one generic elevated-road fallback:

- short water crossings should prefer a simple arch bridge with no intermediate piers
- long or structurally demanding crossings should use a pier-driven bridge model
- pier-driven bridges should be built from pier nodes upward into the deck rather than by placing a floating deck first and patching supports later
- bridge approaches should include explicit uphill and downhill road transitions so the bridge connects back into land roads cleanly
- long water crossings should become feasible when a valid pier chain can be sustained across the span

## User Decisions

- Keep the current outer road-planning flow based on `centerPath` and `bridgeRanges`.
- Borrow the structural idea from `F:\Codex\Ref\TongDaRailway-master`: large bridges should read as repeated pier-supported elevated structures.
- Do not copy the reference project's NBT-template bridge pipeline directly into this mod.
- Use two bridge modes:
  - `ARCH_SPAN` for water crossings that can succeed without intermediate piers
  - `PIER_BRIDGE` for crossings that need repeated support nodes
- Bridge mode selection should prefer `ARCH_SPAN` whenever the crossing can be completed without a middle pier.
- Long water crossings are allowed if the pier chain remains valid.
- Main water spans in pier-driven bridges should use a mostly uniform elevated deck, while shore and terrain transition zones may ramp up or down.
- This design should apply primarily to water crossings. Deep valleys or other terrain voids are secondary candidates, not the default trigger.

## Non-Goals

- Replacing the full road pathfinding pipeline
- Importing structure-template bridge assembly from the reference project
- Adding player-facing bridge-style configuration in this pass
- Solving all terrain-elevation edge cases outside the bridge and approach system
- Turning ordinary rolling land into elevated viaduct by default

## Problem Summary

The current bridge logic is still too deck-centric:

1. bridge planning starts from a continuous bridge surface and only later decides where supports appear
2. support distribution behaves like an attachment detail rather than the main structural organizer
3. long water crossings remain fragile because the system is effectively trying to validate one continuous elevated road, not a repeatable bridge-support chain
4. short water crossings are forced toward the same general bridge treatment even when a simple arch span would be more natural

The design needs a structural split:

- a small-crossing bridge that succeeds as one unsupported span between bridgeheads
- a large-crossing bridge that succeeds as a sequence of valid pier-supported spans

## Chosen Approach

Keep bridge detection at the existing `bridgeRange` level, but replace the internal bridge-construction model with a dual-mode planner:

1. detect a water bridge range from the existing road path
2. attempt an `ARCH_SPAN` plan first
3. if arch conditions fail, attempt a `PIER_BRIDGE` plan
4. feed the chosen bridge plan into geometry, corridor generation, preview, construction, and rollback

This preserves the current system boundary while making bridge structure explicit.

## Bridge Modes

### `ARCH_SPAN`

Use `ARCH_SPAN` when the crossing can be completed between two bridgeheads without an intermediate pier.

Rules:

- both ends must resolve to valid shore or bridgehead anchors
- the span must stay within the allowed unsupported bridge length
- approach grades must be able to connect back into the land road within allowed slope limits
- the bridge deck may form a simple arch or a smooth rise-and-fall profile
- no middle pier is placed

This mode is intended for streams, narrow rivers, and other short water gaps where a full pier-supported viaduct would look excessive.

### `PIER_BRIDGE`

Use `PIER_BRIDGE` when the crossing cannot be completed as one unsupported arch span.

Rules:

- the bridge is planned around explicit structural nodes
- repeated `PIER` nodes provide intermediate support
- `CHANNEL_PIER` nodes may be introduced on both sides of a main navigable water lane
- the main elevated deck should remain mostly level across the dominant water span
- shore-entry and shore-exit zones may ramp to meet the elevated deck

This mode is intended for wide rivers, bays, and long water spans where a repeatable support rhythm is necessary.

## Structural Planning Model

Bridge planning should become explicit and typed rather than inferred from generic support indexes.

Suggested planning records:

- `BridgeSpanPlan`
  - span start and end indexes
  - selected mode: `ARCH_SPAN` or `PIER_BRIDGE`
  - main deck height policy
  - navigable-water metadata when relevant
- `BridgePierNode`
  - path index
  - world position
  - foundation position
  - top-of-pier deck target height
  - node role: `ABUTMENT`, `PIER`, or `CHANNEL_PIER`
- `BridgeDeckSegment`
  - segment start and end indexes
  - segment type: `APPROACH_UP`, `MAIN_LEVEL`, `APPROACH_DOWN`, or `ARCHED_SPAN`
  - height interpolation rule for the segment

These records are internal bridge-planning outputs. They do not replace the outer road plan; they refine what a bridge range means.

## `ARCH_SPAN` Planning Rules

`ARCH_SPAN` should be attempted first for each water bridge range.

Planning steps:

1. identify two viable bridgehead anchors near the shore transitions
2. test whether the crossing can be completed within the allowed unsupported span length
3. test whether the deck profile can rise and fall within the allowed road slope limits
4. if both checks succeed, emit one `ARCHED_SPAN` deck segment between two `ABUTMENT` nodes

Height behavior:

- the bridge does not need a perfectly flat deck
- the center may rise above both bridgeheads to create an arch or gentle crest
- the two ends must still reconnect to land roads cleanly

Failure behavior:

- if unsupported span or approach constraints fail, discard `ARCH_SPAN` and escalate to `PIER_BRIDGE`

## `PIER_BRIDGE` Planning Rules

If `ARCH_SPAN` fails, plan the bridge as a pier-driven structure.

### Pier node selection

Select nodes in three layers:

- `ABUTMENT`
  - required at both ends
  - placed on shore or stable near-shore land
  - acts as the structural and geometric transition into the bridge
- `PIER`
  - distributed through the bridge interior at a target spacing
  - may shift locally forward or backward to find a valid foundation
- `CHANNEL_PIER`
  - optional control piers near the edges of a navigable main channel
  - preserve a wider central opening and clearer under-bridge passage

### Deck height policy

The deck height should be node-driven:

- bridgeheads begin from the land-road elevation
- the bridge should rise through an approach zone toward a target elevated deck
- the main water span should stay mostly level once that target height is reached
- the target deck height must satisfy all of:
  - required water or navigation clearance
  - safe margin above the most demanding water-crossing section
  - ability to reconnect to both ends using allowed approach slopes

### Segment generation

After nodes are selected:

- connect `ABUTMENT` to the first elevated node with `APPROACH_UP`
- connect interior pier nodes mostly with `MAIN_LEVEL`
- connect the final elevated node back to the far `ABUTMENT` with `APPROACH_DOWN`
- allow limited local adjustment near shore complexity, but avoid turning the main span into a wave-shaped deck

This makes bridge viability depend on whether a chain of structural nodes can be sustained, not on whether one continuous floating deck happens to fit.

## Failure Rules

### `ARCH_SPAN` fails when:

- the unsupported crossing distance exceeds the safe arch-span limit
- either bridgehead cannot connect to land within allowed slope limits
- required clearance or collision constraints cannot be satisfied

When `ARCH_SPAN` fails, planning should automatically attempt `PIER_BRIDGE`.

### `PIER_BRIDGE` fails when:

- repeated pier nodes cannot find valid foundations
- any required gap between structural nodes exceeds the safe supported-span limit
- the bridge cannot reconnect to land within allowed approach slope limits
- navigable-water clearance and bridgehead reconnection requirements cannot both be satisfied
- the resulting structure would conflict with protected or forbidden areas

If `PIER_BRIDGE` fails, the whole bridge range fails.

## Integration With Existing Modules

### `RoadBridgePlanner`

[RoadBridgePlanner.java](F:/Codex/sailboatmod/src/main/java/com/monpai/sailboatmod/construction/RoadBridgePlanner.java)

Upgrade this module from a bridge-kind classifier into the bridge-range structural planner:

- detect whether `ARCH_SPAN` can solve the crossing
- otherwise build a `PIER_BRIDGE` node-and-segment plan
- emit typed bridge-planning outputs rather than only coarse profile labels

### `RoadGeometryPlanner`

[RoadGeometryPlanner.java](F:/Codex/sailboatmod/src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java)

Consume the chosen bridge plan and turn it into height sequences:

- arch rise-and-fall profile for `ARCH_SPAN`
- approach ramps and mostly level deck for `PIER_BRIDGE`
- consistent slope semantics at bridge transitions

### `RoadCorridorPlanner`

[RoadCorridorPlanner.java](F:/Codex/sailboatmod/src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java)

Stop inferring bridge support primarily from `supportIndexes`.

Instead:

- consume bridge nodes and deck segments directly
- generate corridor slices that reflect bridge mode and structural intent
- emit support positions only where a bridge plan explicitly calls for them

### `StructureConstructionManager`

[StructureConstructionManager.java](F:/Codex/sailboatmod/src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java)

Continue to own construction and rollback, but consume the richer plan:

- `ARCH_SPAN`: build bridgeheads and the unsupported arch deck
- `PIER_BRIDGE`: build foundations and piers first, then deck, then approach transitions and attachments
- preserve rollback ownership for the full structure set produced by the chosen mode

## Error Handling And Safety

- Bridge planning should fail early when structural preconditions are impossible rather than placing partial bridge fragments.
- `ARCH_SPAN` escalation to `PIER_BRIDGE` is expected behavior, not an error.
- `PIER_BRIDGE` should allow limited local pier-node adjustment before declaring failure.
- The planner should not silently degrade a failed long crossing back into a weak floating road approximation.

## Testing

Add focused tests around the planner outputs and the resulting build behavior.

Planner-focused coverage:

- short water crossing succeeds as `ARCH_SPAN`
- short water crossing emits only bridgeheads and no interior pier nodes
- long water crossing upgrades to `PIER_BRIDGE`
- long water crossing emits repeated `PIER` nodes
- navigable main channels emit `CHANNEL_PIER` control points when required
- impossible pier chains fail cleanly
- impossible approach grades fail cleanly

Geometry and corridor coverage:

- `ARCH_SPAN` produces an arch or crest profile that reconnects to land
- `PIER_BRIDGE` produces uphill approach, mostly level main span, and downhill exit
- corridor slices only emit support positions for explicit pier nodes

Construction and rollback coverage:

- arch bridges do not place middle piers
- pier-driven bridges place piers before bridge deck sections
- rollback removes all bridge blocks produced by the chosen bridge mode

Manual validation targets:

1. a narrow river produces a no-pier arch bridge
2. a wide river produces a repeated-pier bridge with a mostly level main span
3. a long water crossing remains buildable as long as the pier chain is valid
4. bridge approaches reconnect cleanly without abrupt breaks at the shoreline

## Acceptance Criteria

- Small water crossings prefer a no-pier arch bridge whenever the crossing can be completed without intermediate support.
- Large water crossings upgrade to a pier-driven bridge instead of failing as one oversized unsupported deck.
- Pier-driven bridges are planned from structural nodes upward into the deck.
- Main bridge spans remain visually and geometrically coherent, with shore ramps handling elevation change.
- Long water crossings succeed when a valid bridgehead-plus-pier chain exists.
