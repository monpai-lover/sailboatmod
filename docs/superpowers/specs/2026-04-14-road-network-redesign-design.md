# Road Network Redesign Design

## Goal

Redesign manual town-to-town road creation into a stable road-network system for logistics gameplay, heavily informed by RoadWeaver's terrain sampling, contour-aware pathfinding, path post-processing, road snapping, and terrain shaping concepts, while preserving this project's custom bridge philosophy:

- bridges are only used when continuous ground roads cannot be built
- bridges use real pier anchors
- bridge decks maintain clearance for boats
- bridges remain elevated across water and land instead of collapsing back onto nearby terrain

The redesign must also support real carriage entities traveling on completed roads between logistics-capable nodes such as towns, nations, post stations, docks, and warehouses.

## Product Intent

The first version remains player-driven. Players still explicitly create roads between nodes. The system is responsible for:

- finding a stable, buildable route
- snapping into and extending existing roads to form larger networks
- generating bridges only when needed
- building roads in a way that integrates with terrain instead of being buried by it
- registering completed roads into a reusable logistics network

This is not a full RoadWeaver-style structure-to-structure worldgen road system. It is a manual infrastructure system for building complex logistics road networks inside the existing town/nation gameplay loop.

## Scope

This design covers:

- manual road planning between supported nodes
- new terrain sampling and route planning layers
- bridge planning with pier anchors
- path post-processing and road snapping
- terrain shaping and surface replacement
- construction blueprint generation and execution boundaries
- road-network persistence and migration
- logistics runtime for carriage travel on completed roads
- structured failure reasons and `debug.log` diagnostics

This design does not cover:

- changing the player's road creation UX into a graph editor
- replacing towns, nations, warehouses, docks, or post stations as gameplay concepts
- removing existing construction persistence infrastructure
- deep world-generation integration in the first rollout

## Chosen Approach

The redesign uses a hybrid replacement strategy.

Keep:

- current manual player-triggered road creation flow
- town/nation/core protection rules
- existing construction persistence shell
- existing logistics node concepts
- the custom bridge idea centered on pier anchors and navigation clearance

Replace or redesign:

- terrain sampling
- low-level route solving
- path post-processing
- bridge span planning
- road snapping and merge logic
- runtime terrain shaping behavior

This approach is preferred over a full clean-slate rewrite because it addresses the current failures with lower migration risk and allows staged rollout without invalidating the rest of the mod.

## Architecture

The system is split into six layers with strict responsibilities.

### 1. Route Orchestration Layer

This layer owns the request lifecycle for a road creation attempt.

Responsibilities:

- validate source and target nodes
- enforce core protection rules, including the 5-block no-build buffer around cores
- normalize request parameters
- choose planning strategy order
- aggregate structured failure reasons
- hand successful plans to downstream geometry and construction layers

This layer keeps the role currently associated with `ManualRoadPlannerService`, but no longer performs deep route search or structure-specific decisions itself.

### 2. Terrain Understanding Layer

Introduce a RoadWeaver-inspired terrain analysis subsystem based on:

- terrain sampling cache
- corridor prewarming
- height, water, slope, and terrain-stability analysis
- shoreline and near-water awareness
- road attachability checks
- bridge-pier suitability checks

This layer provides a stable, symmetric view of terrain so that `A -> B` and `B -> A` follow the same world interpretation rules.

### 3. Route Solving Layer

This is the primary replacement layer.

Responsibilities:

- grid snapping
- segmented multi-anchor planning
- bidirectional A*
- contour/gradient-aware costing
- ground-route attempts before bridge attempts
- route skeleton generation

This layer returns abstract route structure, not final blocks.

### 4. Geometry and Structure Layer

This layer converts route skeletons into buildable geometric results.

Responsibilities:

- road centerline shaping
- width expansion
- slope continuity
- bridge deck geometry
- pier column geometry
- railing and lighting placement rules

Ground roads and bridges are modeled separately so bridge spans do not get pulled back onto nearby land.

### 5. Construction Execution Layer

This layer executes a finalized construction blueprint and persists runtime progress.

Responsibilities:

- batch-based block placement
- rollback snapshots
- one-time execution of decorative and structural batches
- avoiding repeated local rechecks that cause duplicate lights or repeated support placement

This layer should continue using the existing construction persistence shell but must stop making route-planning decisions while building.

### 6. Road-Network and Logistics Runtime Layer

This layer persists completed roads as a reusable network for logistics and carriage movement.

Responsibilities:

- register completed road edges and junctions
- expose travel-capable graph data
- route carriage entities along completed roads only
- keep incomplete or failed construction out of the travel graph

## Core Data Model

The redesign depends on a small set of explicit data objects with clean ownership.

### PathIntent

Represents one player-initiated road request.

Fields include:

- `sourceNodeId`
- `targetNodeId`
- `roadProfile`
- `clearanceRules`
- `constructionMode`
- `requestId`

It describes what the player asked for, not how the route will be solved.

### RouteSkeleton

Represents the solved abstract route before geometry expansion.

A route skeleton is composed of typed segments such as:

- `GROUND`
- `SLOPE`
- `BRIDGE_APPROACH`
- `BRIDGE_SPAN`
- `SNAPPED`
- `JUNCTION`

Each segment records:

- start and end positions
- direction
- design elevation
- width
- bridge-support eligibility
- lighting eligibility

This is the main handoff between route solving and structure generation.

### BridgeAnchorPlan

Represents the structural bridge solution for a bridge span.

Fields include:

- `waterSurfaceY`
- `deckY`
- `anchors[]`
- `approachStart`
- `approachEnd`
- `pierSpacing`
- `clearanceHeight`
- `bridgeWidth`
- `bridgeSpanLength`

This object is separate from generic route data because bridges are not just elevated roads. They are structured spans supported by discrete pier anchors.

### ConstructionBlueprint

Represents the complete one-shot build plan for a road or bridge.

Batches include:

- `clearanceBatch`
- `surfaceReplaceBatch`
- `gradeAdjustBatch`
- `supportBatch`
- `deckBatch`
- `railingBatch`
- `lightingBatch`
- `rollbackSnapshot`

The blueprint is generated once and then executed. Construction must not continuously rediscover placement logic during runtime.

### LogisticsNode

Represents travel and logistics endpoints.

Initial node types:

- town
- nation
- post station
- dock
- warehouse

Each node exposes a stable road attachment point rather than allowing arbitrary nearest-block attachment.

### RoadNetworkEdge

Represents a completed, travel-capable road edge.

Fields include:

- start node
- end node
- centerline geometry
- road type: `GROUND`, `BRIDGE`, or `MIXED`
- travel width
- travel speed modifier
- carriage allowance
- temporary closure state

This edge is the runtime unit used by carriage routing.

## Route Creation Pipeline

The planning pipeline is ordered to prefer stable, cheap, explainable outcomes.

### 1. Request Normalization

Convert source and target into normalized logical nodes.

Perform fast prechecks:

- same dimension validation
- node attachability
- core 5-block buffer enforcement
- immediate existing-road attachment opportunities

If prechecks fail, return a gameplay-level failure reason immediately.

### 2. Local Terrain Pre-Sampling

Before heavy search, prewarm terrain cache over a corridor surrounding the request.

Sample:

- surface heights
- water levels
- slopes
- replaceable surface blocks
- proximity to protected core areas
- nearby existing roads
- candidate pier-support columns

This produces a corridor map used by later planning phases.

### 3. First Pass: Ground Route Attempt

The first full planning attempt only uses:

- `GROUND`
- `SLOPE`

Use:

- grid snapping
- segmented anchor planning
- bidirectional A*
- contour- and gradient-aware costs
- attraction toward compatible existing roads

If a continuous buildable ground route is found, the system must not generate a bridge.

### 4. Second Pass: Segmented Intermediate Anchors

If direct ground planning fails, retry using intermediate anchors derived from:

- terrain saddles
- shoreline transitions
- existing-road connection candidates
- detour points outside protected core zones

The planner solves several shorter route segments instead of one huge search.

This reduces lag on long routes and improves the chance of success on complex terrain.

### 5. Third Pass: Bridge Candidate Detection

Bridge planning only activates when continuous ground construction fails on a specific span and bridge use is justified.

Bridge candidacy requires:

- continuous ground-route failure across a span
- detour cost above a configured threshold
- available high-clearance bridge deck possibility
- viable pier-anchor sequence

Small shallow crossings that can be replaced as ordinary surface roads must not automatically become bridges.

### 6. Fourth Pass: Bridge Span Planning

For bridge-eligible spans, run dedicated bridge planning:

- detect the full elevated span from first ascent to final descent
- find valid pier anchors along that elevated span
- choose deck height from water-surface-relative clearance, not from nearby land elevation
- generate continuous approach slopes
- preserve elevation across land patches beneath the bridge

Bridge logic must not degrade into ordinary ground-road placement once the bridge span begins.

### 7. Fifth Pass: Path Post-Processing

Apply RoadWeaver-inspired post-processing to the skeleton:

- redundant-point simplification
- bridge-run straightening
- non-bridge smoothing
- spline or Bezier curve refinement
- monotonic slope correction for ascent and descent

The result remains a `RouteSkeleton`, not final placed blocks.

### 8. Sixth Pass: Road Snapping and Network Merge

After a valid skeleton exists, detect and apply attachment to nearby compatible roads:

- edge merge
- junction creation
- terminal attachment

Snapping must respect:

- direction compatibility
- elevation continuity
- protected-core avoidance
- edge-type compatibility

### 9. Failure Result Assembly

If all strategies fail, return structured reasons such as:

- `BLOCKED_BY_CORE_BUFFER`
- `NO_CONTINUOUS_GROUND_ROUTE`
- `BRIDGE_ANCHOR_NOT_FOUND`
- `BRIDGE_CLEARANCE_UNSATISFIED`
- `SEARCH_BUDGET_EXCEEDED`
- `TARGET_NOT_ATTACHABLE`

The player sees localized gameplay text. `debug.log` records full structured diagnostics.

## Bridge Subsystem

Bridges are independent structures, not just special cases of ordinary roads.

### Bridge Activation Rules

Bridges only activate when ordinary continuous ground construction cannot succeed over a span.

Typical triggers:

- water or marshland interrupts continuous surface replacement
- detouring around the crossing is too expensive
- core-avoidance forces an elevated crossing

The presence of water alone is not enough.

### Bridge Composition

A bridge consists of:

- `Approach`
- `Span`
- `Exit`

The main span remains elevated across water and land. It does not collapse onto nearby land just because local terrain rises under the deck.

### Pier Anchor Selection

Pier anchors are selected first; the final bridge deck is then solved around them.

Pier anchors must:

- lie on or near the elevated bridge corridor
- stay outside core 5-block no-build buffers
- allow a full column from terrain, riverbed, or seabed up to just below the bridge deck
- satisfy spacing constraints relative to neighboring anchors

Only pier-anchor positions generate downward support columns. Non-anchor bridge cells do not drop supports.

### Elevated Span Width for Pier Count

Pier count is based on the full elevated bridge span, not only on water width.

That means:

- measure from the start of the upward bridge approach to the end of the downward bridge exit
- include elevated sections that pass above shallow banks, sandbars, islands, or land
- continue allowing pier placement on land if the structure is still a bridge span

This keeps bridge support visually and structurally consistent across mixed water/land crossings.

### Pier Count and Distribution

Use maximum-span-driven distribution rather than hard-coded ratios.

Rules:

- define a maximum allowed distance between neighboring pier anchors
- longer elevated spans automatically receive more intermediate piers
- distribute pier anchors as evenly as possible across the elevated bridge span
- ensure the bridge has structural support near the ascent, the middle, and the descent

The user's `1/7, 4/7, 7/7` example for a width-7 river is treated as an example outcome of even distribution, not as a fixed pattern.

### Deck Height Rules

Bridge deck elevation is determined from relative water-surface clearance:

- detect dominant water surface for the crossing
- add configured boat-clearance height
- keep the resulting deck elevation stable across the main span

Nearby land height must not pull the bridge deck down.

### Approach Slope Rules

Bridge approaches must be continuous and directional:

- ascent from ground to bridge deck is monotonic
- descent from bridge deck to ground is monotonic
- slope blocks use correct orientation based on bridge direction
- if a protected core would clip the approach, the system must re-plan approach geometry or reject the bridge span with a clear reason

Approaches must not be silently truncated.

### Cross-Section Rules

The bridge cross-section separates:

- central travel lane
- side structure band for railings and lights

This means visible bridge width can exceed effective travel width, preserving aesthetics while keeping side structures attached to the bridge body.

### Materials and Structure

Current baseline bridge composition:

- masonry pier columns from bottom to deck
- full-block deck surface rather than slab-only travel surface
- properly oriented slope blocks for approaches
- railings attached to deck-side structure positions, never floating
- moderate lighting density on pier tops and selected railing positions

### Bridge Curvature

Bridge decks may use smoothed control lines such as spline or Bezier curves, especially when avoiding protected core areas.

However, structural support remains anchored to discrete pier anchors. Visual deck smoothing must not break the support model.

### Bridge Failure Reasons

Bridge-specific failures include:

- no valid continuous pier-anchor sequence
- insufficient boat clearance
- approach cannot reconnect to terrain
- protected core clips required approach geometry
- structure width conflicts with the environment

Each failure must be written to `debug.log` with the request identifier.

## Road Snapping and Network Merge

Roads must grow into a network rather than remain isolated A-B lines.

### Timing

Snapping happens after a valid skeleton exists, not during raw path search.

### Eligible Snap Targets

A new road may snap to:

- completed ground roads
- completed bridge roads
- existing junctions
- node attachment stubs

It may not snap to:

- protected core tiles
- incomplete construction
- decorative non-road structures

### Snap Validation

Snapping requires:

- distance within threshold
- direction within threshold
- buildable elevation transition
- no protected-core violation

### Merge Results

Possible merge outputs:

- `MERGE_TO_EDGE`
- `CREATE_JUNCTION`
- `TERMINAL_ATTACH`

### Bridge Interaction

Bridge endpoints can accept snaps, but bridge main spans must not be degraded into ordinary terrain-following roads by later snapping.

### Road-Network Transaction Rules

Completed roads update the persistent network transactionally:

- add new nodes
- merge overlapping edges
- split edges at junctions
- apply final type and travel metadata

No half-built road should appear as a valid logistics edge.

## Terrain Shaping and Surface Replacement

Roads must integrate with terrain rather than sit on top of it as fragile overlays.

### Ground Road Surface Policy

Ordinary roads replace the surface instead of being stacked above it.

For `GROUND` segments:

- set target road elevation
- replace the terrain block at that elevation with road material
- clear necessary natural blocks above the road
- optionally trim or fill nearby shoulders for continuity

This prevents roads from being buried under untouched natural blocks.

### Slope Shaping

`SLOPE` segments must be terrain-aware:

- assign target elevation step by step
- replace the path surface continuously
- clear the required headroom continuously
- perform minimal shoulder shaping or fill where needed

The goal is a continuous drivable and walkable slope, not a jagged sequence of disconnected steps.

### Bridge Approach Shaping

`BRIDGE_APPROACH` segments require local terrain shaping where they reconnect to land:

- short embankment shaping
- ground transition smoothing
- no sudden bridge-to-ground discontinuity

### Runtime Beardifier-Inspired Model

The first rollout should use a runtime terrain deformation service inspired by Beardifier behavior:

- roads produce a local terrain influence field
- that field informs clearance, replacement, and shaping decisions
- the service is used during construction planning rather than as deep worldgen integration

Deeper world-generation integration may be considered later after runtime behavior is stable.

### Protected Core Interaction

Core 5-block protection applies to terrain shaping as well as block placement.

The planner may not silently trim road or slope geometry into the protected zone. It must re-plan or fail with a structured reason.

### Blueprint Integration

Terrain shaping must be encoded in `ConstructionBlueprint` batches rather than improvised during placement.

## Construction Execution and Rollback

Construction should become deterministic and batch-based.

### Blueprint-Driven Execution

All build decisions must be made before execution begins.

The construction manager should:

- execute ordered blueprint batches
- persist progress
- capture rollback data for all affected terrain and structures

### No Repeated Local Replanning

Lighting, railings, supports, and decorative elements must not be repeatedly rediscovered during runtime. Repeated local rescans are the source of duplicate-lighting bugs and support residue.

### Rollback Completeness

Rollback must include:

- surface replacements
- terrain shaping
- bridge supports and pier columns
- deck blocks
- railings
- lighting

Cancelling a build must not leave support columns or bridge fragments behind.

## Logistics Runtime and Carriage Entities

Completed roads should immediately become useful to logistics gameplay.

### Runtime Graph Source

Carriages route only on the completed persistent road graph:

- nodes
- junctions
- completed edges

Incomplete, rolling-back, or invalid edges are excluded from travel routing.

### Initial Node Set

Supported logistics nodes:

- town
- nation
- post station
- dock
- warehouse

Each node has explicit attachment points for entering or leaving the road graph.

### Edge Semantics

Travel edges carry runtime semantics:

- road type
- width
- speed modifier
- carriage allowance
- temporary closure state

### Carriage Routing

Long-distance carriage routing uses graph search on completed edges rather than world-block pathfinding.

This allows stable route choice, closures, and future congestion or priority systems without reusing expensive terrain planning logic.

### World Motion

Carriage entities follow sampled edge centerlines:

- edge geometry defines motion path
- edge metadata defines motion speed and restrictions

This applies equally to ground roads and bridges.

### Player Road Effect

Players walking on completed road surfaces may receive a low-level movement-speed benefit.

This should be implemented separately from carriage movement:

- players use a road-area effect check
- carriages use edge speed metadata directly

## Compatibility and Migration

The redesign must preserve existing saves and avoid breaking ongoing worlds.

### Save Compatibility

Requirements:

- old saves remain loadable
- completed roads remain present
- node identity for towns, nations, post stations, docks, and warehouses remains stable

### Road Record Migration

Existing road records migrate conservatively:

- recognizable ordinary roads become `GROUND`
- recognizable bridges become `BRIDGE`
- incomplete or ambiguous historical roads become `LEGACY`

`LEGACY` edges remain usable for travel but do not participate in advanced bridge extension or high-confidence snapping until rebuilt or re-scanned.

### Construction State Migration

Existing construction runtime states are classified into:

- complete
- early-stage and safely convertible
- ambiguous in-progress states

Ambiguous states should terminate conservatively rather than being force-converted into broken structures.

## Diagnostics and Failure Reporting

Diagnostics are first-class functionality in this redesign.

### Request Correlation

Every road request must have a stable `requestId` recorded through:

- request validation
- terrain sampling
- ground-route attempts
- segmented attempts
- bridge candidate detection
- bridge anchor planning
- post-processing
- road snapping
- blueprint generation
- blueprint execution
- road-network update
- logistics routing

### `debug.log` Categories

At minimum, structured `debug.log` records should exist for:

- `RoadRequest`
- `TerrainSample`
- `GroundRouteAttempt`
- `SegmentedRouteAttempt`
- `BridgeCandidate`
- `BridgeAnchorPlan`
- `RoutePostProcess`
- `RoadSnap`
- `ConstructionBlueprint`
- `ConstructionExecution`
- `RoadNetworkUpdate`
- `LogisticsRoute`

Each failure record must include a reason code and the request identifier.

### Player-Facing Failures

Player-facing messages remain concise and localized, for example:

- core protection area blocks road construction
- no continuous ground route could be built
- no valid bridge pier anchors were found
- bridge clearance is insufficient for boat passage
- route is too long and no valid intermediate anchors were found

Technical detail remains in `debug.log`.

## Testing Strategy

Compilation alone is not sufficient validation.

Minimum required validation scenarios:

- short flat ground road
- mountain slope road
- ordinary ground route that upgrades to bridge only after ground failure
- bridge across mixed water and land understructure
- protected core 5-block avoidance
- `A -> B` versus `B -> A` consistency
- snap into existing road network
- rollback removes pier columns and supports
- player receives road-speed effect on completed roads
- carriage travels across completed network edges

Use a mix of automated tests and in-game verification scenes.

## Rollout Plan

The redesign should be implemented in stages:

1. terrain sampling layer, failure reason model, and structured diagnostics
2. ground-route and segmented route core
3. bridge candidate detection, pier-anchor planning, and approach generation
4. post-processing and road snapping
5. construction blueprint and reliable rollback
6. persistent network upgrade and migration
7. carriage runtime on the new road graph
8. runtime terrain shaping / Beardifier-inspired deformation

Each stage should be independently verifiable before the next stage begins.

## Success Criteria

The redesign is successful when:

- long-distance road creation no longer stalls the server for large single searches
- ordinary roads remain ordinary whenever continuous surface replacement can succeed
- bridges generate stable deck, approach, pier, railing, and lighting structure only when needed
- bridge pier count scales with the full elevated span using even distribution under maximum-span rules
- core 5-block protected areas are never built into or terrain-shaped through
- `A -> B` and `B -> A` behave consistently under the same world state
- failure reasons are explicit in both player messaging and `debug.log`
- completed roads form a reusable network for carriage travel

## References

Reference concepts should be drawn from:

- `F:\Codex\Ref\RoadWeaver-1.20.1-Architectury\common\src\main\java\com\thepixelgods\roadweaver\features\path\pathlogic\terrain\TerrainSamplingCache.java`
- `F:\Codex\Ref\RoadWeaver-1.20.1-Architectury\common\src\main\java\com\thepixelgods\roadweaver\features\path\pathlogic\terrain\TerrainGradientHelper.java`
- `F:\Codex\Ref\RoadWeaver-1.20.1-Architectury\common\src\main\java\com\thepixelgods\roadweaver\features\path\pathlogic\pathfinding\HighwayBidirectionalAStarPathfinder.java`
- `F:\Codex\Ref\RoadWeaver-1.20.1-Architectury\common\src\main\java\com\thepixelgods\roadweaver\features\path\pathlogic\postprocess\PathPostProcessor.java`
- `F:\Codex\Ref\RoadWeaver-1.20.1-Architectury\common\src\main\java\com\thepixelgods\roadweaver\features\path\pathlogic\postprocess\RoadSnapService.java`
- `F:\Codex\Ref\RoadWeaver-1.20.1-Architectury\forge\src\main\java\com\thepixelgods\roadweaver\mixin\BeardifierMixin.java`

Target integration points in this project include:

- `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- `src/main/java/com/monpai/sailboatmod/nation/model/RoadNetworkRecord.java`
- `src/main/java/com/monpai/sailboatmod/nation/data/ConstructionRuntimeSavedData.java`
