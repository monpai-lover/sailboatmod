# Road Terrain And Curve Redesign

## Summary

This redesign replaces the current land-road pipeline with a RoadWeaver-style "constructible path" flow.
Road planning must no longer fail simply because the surface is discontinuous. Instead, planning should prefer
existing terrain when possible, but escalate into terrain modification, short arch bridges, tunnels, and full
bridges when needed. The curve system should stop using the current lightweight centerline smoothing approach and
adopt a RoadWeaver-style mixed interpolation and spline post-processing pipeline so slight offsets do not fracture
road and bridge surfaces.

The redesign applies to both automatic town-to-town road generation and manual road preview / builder workflows.
Both paths must consume the same planning, curve, and geometry pipeline so preview, auto-generation, and final
construction produce the same shape.

## Design

### 1. Constructible path planning

Land-road planning should output a semantic path, not just a list of terrain-following `BlockPos` points.
Each segment in the path must be classified as one of:

- surface road
- fill / embankment road
- cut slope / mountain cut
- tunnel
- short water arch bridge
- full bridge

The planner must follow this priority order:

1. Existing continuous surface roadbed
2. Small detour
3. Light cut/fill terrain shaping
4. Strong cut / stable short tunnel
5. Short arch bridge over small water
6. Full bridge

The planner should only fail when the path is blocked by true hard constraints such as excluded cores, player
structures, or a span / elevation problem that exceeds allowed thresholds.

### 2. Water and mountain rules

Small water should default to short arch bridges.

- Water spans of `<= 8` blocks are treated as short-water crossings.
- Short-water crossings use a low arch bridge profile and must reconnect naturally to land roadbed on both sides.
- Water spans above `8` blocks escalate to the full bridge system.

Mountain handling should be two-stage.

- If a road can be made continuous by cutting a slope, prefer that.
- If slope cutting is still too expensive or too steep, allow a stable tunnel segment.

### 3. Geometry rules

Surface roads should replace natural top blocks to form a continuous roadbed, rather than floating above terrain.
Cut/fill and tunnel geometry should be built from road width outward, not from the centerline only.

Cut slope rules:

- carve a full road-width bed
- clear a stable headroom envelope across the full width
- never allow one side of the road footprint to remain broken or suspended

Tunnel rules:

- use road width plus fixed headroom
- keep the floor as a normal road surface
- avoid bridge-like or ship-like behavior inside tunnels

Short arch bridge rules:

- short, low, continuous crossing over small water
- no long-pier bridge logic
- must reconnect smoothly into the land roadbed

Full bridge rules:

- continue using the current bridge family, but change bridge approaches to a continuous raised ramp shape
- this ramp style is only for bridge approaches, not for ordinary land slopes

Bridge approach shape must match the requested reference:

- ground platform
- continuous raised bridge approach
- main bridge deck platform
- continuous descending approach
- end platform

The old fragmented bridge slope shape with flat interruptions must not be generated.

### 4. Turn closure rules

Both land roads and bridges must actively fill turn closures.
Slight centerline offsets such as `x + 1` transitions must not leave broken shoulders, split platforms, or hanging
gaps. Turn geometry must be closed using direction-aware closure bands rather than relying on adjacent slices to
overlap by chance.

### 5. Curve system

The current `RoadBezierCenterline` approach should be replaced with a RoadWeaver-style mixed pipeline based on the
ideas in `PathPostProcessor` and `SplineHelper`.

This is not a pure Bezier replacement. The target behavior is a hybrid:

1. simplify raw path
2. detect bridge / protected segments
3. straighten bridge and other protected runs with interpolation / linear handling
4. relax only non-protected land segments
5. apply spline / Bezier-style smoothing to those land segments
6. rasterize back into a continuous grid path

Hard requirements:

- rasterized output must remain 8-neighbor continuous
- no duplicate columns
- no immediate reversals
- no narrow broken joins caused by slight offsets

Automatic roads, manual preview, and final construction must all use this shared centerline result.

### 6. Construction execution

`StructureConstructionManager` must consume the new semantic path and execute the chosen terrain operations:

- clear obstacles
- cut terrain
- fill terrain
- excavate tunnel volume
- place short arch bridges
- place full bridges
- use temporary bridge scaffolding when full bridge support is missing during runtime

Planning is only valid if the runtime builder can actually realize the chosen segment type.

## Implementation Shape

Primary modules to change:

- `LandRoadHybridPathfinder`
- `RoadPathfinder`
- `RoadCorridorPlanner`
- `RoadGeometryPlanner`
- `RoadBridgePlanner`
- `RoadBezierCenterline`
- `StructureConstructionManager`

Recommended responsibility split:

- pathfinders choose semantic segment types and constructible route
- curve post-processor converts the semantic route into a stable centerline
- corridor / geometry planners materialize roadbed, bridge, arch, tunnel, and closure geometry
- construction manager performs the semantic terrain edits and runtime placement

## Tests And Acceptance

Required regression coverage:

- discontinuous terrain can be solved by cut/fill instead of immediate failure
- mountain obstruction can produce a cut slope or tunnel
- water spans `<= 8` blocks become short arch bridges
- longer spans become full bridges
- bridge approaches use continuous raised ramps rather than fragmented platforms
- slight offset joins do not fracture
- bridge turns do not fracture
- land turns do not fracture
- automatic and manual generation produce the same centerline / geometry family

Acceptance criteria:

- land routing should succeed much more often on rough terrain
- bridge and arch transitions must reconnect to land cleanly
- bridge approach visuals must match the requested continuous style
- curve handling must behave like the RoadWeaver mixed interpolation + spline workflow

## Assumptions

- short water means `<= 8` blocks of water span
- strong mountain cutting and stable tunnels are allowed
- bridge-style continuous ramps apply only to bridge approaches
- the RoadWeaver curve workflow is the reference model for the centerline redesign
