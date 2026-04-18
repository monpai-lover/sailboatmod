# Carriage Virtual Horse and Land Route Design

## Goal

Turn the existing carriage into a dedicated overland logistics vehicle:

- fix the land route planner so road-first routes stop exhausting on valid short connectors
- replace the carriage's current boat-like ground motion with a drawn-carriage model based on a virtual horse
- integrate carriage driving and autopilot with the existing gear, route, ownership, storage, and logistics systems
- keep the carriage road-oriented, with limited off-road connector behavior instead of free ground sailing

## Constraints

- Do not turn the carriage into "a boat on land".
- Keep `CarriageEntity` as the primary entity used by the mod's existing logistics systems.
- Do not introduce a persistent visible or network-authoritative horse entity. The horse is virtual state owned by the carriage runtime.
- Automatic carriage routing must be road-first, with only short terrain connectors at route ends or small road gaps.
- Player driving and autopilot must share the same low-level traction and control model.
- The first phase only ports Nifty Carts' base drawn-cart behavior. It does not include wagon roof, carpet, chest expansion, or decorative interactions.

## Existing Context

### Current carriage

`CarriageEntity` currently extends `SailboatEntity`, which is useful for:

- ownership and access control
- storage and UI integration
- route and autopilot integration
- network sync patterns already used by transport entities

But the current ground support still behaves too much like adapted boat propulsion:

- acceleration is still applied directly to the carriage body
- on-road and off-road behavior is mostly drag/bonus tuning
- there is no explicit traction source in front of the carriage
- turning does not behave like a pulled vehicle with a lead point and body follow-through

### Current land planning

`LandRoadHybridPathfinder` on `main` currently keeps the looser `MAX_STEP_UP = 3` behavior but does not include the more complete traversability guards previously explored in the recovery branch:

- no explicit blocked start/goal validation before search
- no guarded "finish on foot" completion check
- no per-neighbor traversability validation beyond raw sampled surface presence

This leaves route search vulnerable to false traversability, invalid short completion, and wasteful search exhaustion.

### Nifty Carts reference

The useful behavior to port from Nifty Carts is the base drawn-cart model from `AbstractDrawnEntity` and related wagon movement:

- lead-point / pull spacing behavior
- carriage body orientation derived from a pulling target
- pulled-body follow-through instead of direct self-propulsion
- disconnect / stall protection when spacing or obstacles break the pull relationship
- multi-passenger attachment points and rider body orientation patterns

The parts not in scope for this phase are:

- real horse attachment
- roof/carpet/chest wagon interactions
- chain-of-carts and the broader Nifty entity family

## Architecture

### 1. Keep `CarriageEntity` as the transport shell

`CarriageEntity` remains the logistics-facing entity. It continues to own:

- inventory and transport menus
- owner checks
- route selection and autopilot identity
- dock/post station integration
- save/load lifecycle

The new work changes how movement is produced, not what the transport entity is.

### 2. Add a virtual traction layer

Introduce a new internal control and physics layer, split into two responsibilities:

- `VirtualHorseDriveState`
  - server-authoritative control state for the invisible pulling source
  - target heading
  - current heading
  - target traction force
  - current traction force
  - gear-derived acceleration envelope
  - braking / reverse state
  - stall / recovery state
- `DrawnCarriagePhysics`
  - carriage body solver that converts the virtual horse state into actual carriage movement
  - lead point positioning in front of the carriage
  - drawbar spacing enforcement
  - body follow-through and yaw smoothing
  - slope-aware traction and braking
  - lateral damping to suppress boat-like sliding
  - obstacle / detachment safety checks

The carriage will no longer generate its main forward acceleration by directly pushing its own body as if it were a hull. Instead, the carriage follows a virtual lead point with road-aware traction.

### 3. Shared control pipeline

Both player driving and autopilot write into one shared control interface:

- desired heading
- desired drive intent
- desired gear
- brake intent
- reverse intent

This control interface updates the virtual horse state, and only the virtual horse state drives carriage physics. This ensures:

- player driving and autopilot have the same handling
- gear behavior affects both manual and automatic operation identically
- future balancing is isolated to one traction model

## Route Planning Design

### 1. Road-first segmented routes

Automatic carriage routes must resolve into segmented land routes:

- start terrain connector
- road corridor
- end terrain connector

The road corridor is the preferred and expected main body of the route. Terrain connectors exist only to:

- reach the first road from a nearby station
- bridge a very short discontinuity in the road network
- leave the road near the destination

### 2. Connector limits

Terrain connectors must have a hard distance and search budget ceiling.

If a route requires a long off-road detour, carriage routing fails clearly instead of searching until exhaustion. This is critical for logistics predictability and to keep the carriage road-oriented.

### 3. Hybrid pathfinder corrections

`LandRoadHybridPathfinder` must be repaired to validate traversability explicitly:

- merge blocked and excluded columns into one traversal guard
- allow start/goal anchoring only through controlled endpoint unblocking
- reject blocked or unusable start/goal anchors before search
- validate each expanded neighbor with real ground-path diagnostics
- only allow short-step completion when the goal column is actually traversable

This removes false positives and false negatives that currently make route planning either fail too late or succeed through invalid terrain.

### 4. Selector behavior

Road planning should prefer:

1. existing road corridor path
2. hybrid road-plus-short-connector path
3. fail

It should not silently degrade into broad off-road carriage routing. A carriage is a road logistics vehicle, not a universal land transport.

## Movement and Handling Design

### 1. Manual driving

Manual input changes virtual horse intent, not carriage body velocity:

- forward input increases target traction
- brake / reverse input reduces or reverses target traction
- left/right steer changes the virtual horse heading target
- gear changes alter the acceleration curve and cruise envelope

Expected feel:

- startup pull instead of immediate full-body movement
- towing-style turning with visible body follow-through
- slower, heavier reaction off road
- limited reverse speed

### 2. Autopilot driving

Autopilot uses the same control model but derives intent from the current route segment:

- road segments allow normal travel gears
- terrain connectors force conservative low-speed control
- sharp turns reduce traction output automatically
- slope and obstacle pressure can trigger temporary downshift or recovery logic

### 3. Hard anti-boat rules

The physics layer must explicitly prevent boat-like behavior:

- strong lateral damping on ground
- no sustained sideways glide from prior heading
- uphill movement must consume traction against grade
- downhill movement must rely on braking and damping rather than free coasting
- loss of valid pull geometry must stall or recover, not keep inertial sliding

## Passenger and Body Behavior

Port the useful base wagon behavior:

- multi-passenger seat layout
- rider positioning relative to carriage body
- rider orientation clamped to carriage orientation
- optional auto-seat behavior for compatible non-player passengers only if it fits current mod rules

Out of scope for this phase:

- wagon roof
- carpet interactions
- expandable chest capacity
- decorative wagon variants beyond what current carriage already supports

## Data and Save State

`CarriageEntity` save data must gain only the additional state needed to resume the drawn-carriage model consistently:

- virtual horse heading and traction state
- current gear-derived motion state if needed for smooth reload
- autopilot segment progress if current route execution requires it
- stalled / recovery state if the existing transport system persists similar runtime state

Do not save ephemeral solver internals unless reload correctness depends on them. Keep persistent state minimal and reconstructable.

## Error Handling and Recovery

### Routing failures

When a carriage route cannot be resolved:

- report route failure explicitly
- do not spawn a fake long off-road fallback
- preserve existing route/network data without partial execution

### Runtime stall handling

If the carriage loses valid pull spacing, road support, or path progress:

- reduce traction
- attempt bounded recovery
- if recovery fails, stop and report blocked route / stalled carriage

This is preferable to allowing indefinite oscillation or slow drift.

## Testing Strategy

### 1. Land pathfinder regression tests

Add targeted tests covering:

- blocked start anchor rejection
- blocked goal anchor rejection
- blocked short-step completion rejection
- dry detour preference over invalid bridge-required columns
- near-town short connector success without search exhaustion
- connector hard limit failure when off-road distance is too large

### 2. Carriage control and physics tests

Add focused unit-style tests for:

- virtual horse traction ramp-up and ramp-down
- gear changing modifies acceleration envelope
- reverse is capped and distinct from forward behavior
- lateral damping suppresses sideways glide
- drawn spacing pulls the carriage body toward the lead point instead of direct self-thrust

### 3. Autopilot integration tests

Add tests covering:

- road segment route resolution for carriages
- short terrain connector allowance around road starts/ends
- no long-distance off-road fallback
- segment-follow controller chooses lower-speed mode on connector segments

### 4. Regression coverage for existing carriage behavior

Keep and extend current carriage tests so that:

- existing owner-only access behavior still works
- storage/menu behavior still works
- passenger sound cues still work
- new movement model does not break current route identity assumptions

## Implementation Boundaries

The implementation should stay focused on four bounded change areas:

1. land route planner repairs
2. carriage virtual horse control and drawn-body physics
3. carriage autopilot integration with segmented road-first routes
4. regression and integration tests

The following are explicitly deferred:

- visible horse rendering
- wagon roof/carpet/chest features
- chained cart trains
- replacing the broader sailboat transport architecture
- generalized off-road carriage travel as a first-class routing mode

## Acceptance Criteria

The work is complete when all of the following are true:

- carriage movement on land no longer feels like a tuned boat and is clearly pull-driven
- player driving uses the virtual horse model and gear-controlled acceleration
- autopilot uses the same control model
- carriage route planning prefers roads and only allows short terrain connectors
- land route search no longer exhausts on valid nearby road connector cases
- routes that require long off-road travel fail clearly instead of wandering through terrain search
- current carriage ownership, storage, and transport integration continue to work
- targeted automated tests cover the new route and movement rules
