# Water Bridge Stone Pier Design

## Goal

Replace the current wood-style water bridge treatment with a stone-style bridge treatment where only selected bridge-pier anchor columns generate downward support pillars, while the rest of the bridge deck stays open for navigation.

## Requirements

- Water bridges must use `stone_brick_slab` as the deck surface.
- Downward support pillars must only appear at bridge-pier anchor columns.
- Each bridge-pier pillar must extend from the bridge deck downward until it reaches non-replaceable riverbed or seafloor terrain.
- Non-anchor bridge columns must not generate continuous downward support.
- Both sides of the bridge deck must keep railings and lighting.
- This change applies only to water bridges. Normal land roads and non-water elevated transitions must keep their current behavior.

## Implementation Shape

### Bridge Material Selection

- Water-bridge deck slices switch from spruce bridge materials to stone bridge materials.
- The support material for water-bridge piers and railing light posts becomes stone-based instead of wood-based.

### Pier Placement Rules

- Corridor support generation must distinguish between:
  - true water-bridge pier anchor columns
  - non-anchor bridge deck columns
- Only true pier anchor columns are allowed to emit downward support stacks.
- Navigable-water main-span columns must remain free of downward supports unless they are explicitly selected pier anchors.

### Pier Depth Rules

- Existing fixed shallow support depth is insufficient for water bridges.
- Water-bridge piers must stack downward from the deck until they contact a non-replaceable block.
- Replaceable fluids and soft replaceable plants must not stop the pillar.
- The stopping block itself is not replaced.

### Railing And Lighting

- Water-bridge railings remain on both sides of the deck.
- Water-bridge railing lighting remains enabled.
- Pier-top lighting remains enabled on pier anchor columns.
- The support block used beneath those lights must match the new stone bridge style.

## Files Expected To Change

- `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`

## Test Strategy

- Add a regression proving only designated pier-anchor columns receive downward supports.
- Add a regression proving water-bridge ghost/build artifacts use stone slab bridge deck materials instead of spruce slab/fence materials.
- Re-run focused road-link tests, then run a full `build`.
