# Road Ground Path And Construction Runtime Regression Design

## Summary

This design fixes two coupled regressions in the road lifecycle:

1. Ground-road planning now fails broadly with `SEARCH_EXHAUSTED` for both manual and auto roads.
2. Roads that still generate a visible ghost preview no longer advance construction automatically, and builder hammer interaction no longer accelerates them.

The fix treats these as two shared subsystem regressions, not as separate manual-vs-auto feature bugs:

- `Ground Route Resolution`
- `Road Construction Runtime`

The goal is to restore the intended behavior:

- Manual and auto roads both produce valid pure-land routes when terrain is traversable.
- Scheduled road construction jobs advance automatically over time.
- Builder hammer and builder participation accelerate an already-working automatic construction pipeline rather than acting as the only way to build.

## Problem Statement

### Regression A: Ground Route Resolution

Current behavior indicates that pure-land road planning is frequently or always collapsing into `SEARCH_EXHAUSTED`, affecting both:

- manually placed roads
- auto-generated road links

Because both paths fail, the defect must be treated as living in the shared land-route solving pipeline rather than in only one caller.

### Regression B: Road Construction Runtime

Current behavior indicates that a road can still reach the visible ghost-preview state, but:

- construction progress stays at `0%`
- no blocks are placed automatically
- builder hammer interaction does not begin or accelerate placement

This means plan generation is at least partially intact, but the runtime layer that exposes remaining steps, schedules the road job, recognizes interactable targets, or advances placement has diverged.

## Goals

- Restore successful pure-land routing for traversable town-to-town roads.
- Keep manual and auto roads on the same ground-route success/failure logic.
- Restore default automatic construction for all scheduled roads.
- Preserve builder hammer and builder behavior as acceleration mechanisms only.
- Ensure ghost rendering, interactable road targets, and remaining construction steps come from consistent runtime state.

## Non-Goals

- Redesign bridge generation or bridge geometry.
- Redesign claim-map async behavior.
- Rework road UI or add broad UX improvements.
- Change road economy balance except where needed to keep hammer acceleration behavior aligned with restored runtime semantics.

## Architecture Boundaries

### 1. Ground Route Resolution

This subsystem is responsible for converting endpoints into a traversable land-road centerline. It includes:

- `RoadPathfinder.findGroundPathForPlan(...)`
- `LandRoadRouteSelector.select(...)`
- `LandRoadHybridPathfinder.find(...)`
- `RoadAutoRouteService.resolveAutoRoute(...)`

This subsystem owns:

- route selection between legacy and hybrid backends
- land-only path expansion
- fallback behavior after a solver fails
- structured failure reasons such as `SEARCH_EXHAUSTED`

This subsystem does not own:

- corridor generation
- bridge mesh/placement artifacts
- road job scheduling or block placement

### 2. Road Construction Runtime

This subsystem is responsible for turning a valid `RoadPlacementPlan` into a live road job that advances over time and can be accelerated. It includes:

- `StructureConstructionManager.scheduleRoadConstruction(...)`
- `StructureConstructionManager.tickRoadConstructions(...)`
- `StructureConstructionManager.placeRoadBuildSteps(...)`
- `StructureConstructionManager.remainingRoadGhostPositions(...)`
- builder hammer target validation and credit consumption paths

This subsystem owns:

- active road job creation
- persisted road runtime restoration
- automatic progress over ticks
- mapping from remaining runtime steps to ghost/interactable targets
- hammer acceleration and builder acceleration

This subsystem does not own:

- route solving
- road geometry generation correctness
- bridge span planning

## Design Approach

### Recommended Approach

Fix both regressions as two shared pipelines with dedicated regression tests at their shared entry points.

This avoids patching only manual-road callers, only auto-road callers, or only hammer interaction. The current symptoms show shared-state divergence, so the fix must re-establish common truth at the subsystem boundaries.

### Alternative Approaches Considered

#### Alternative 1: Threshold-only hotfix

Change only obvious routing thresholds and fallback knobs.

Why rejected:

- It might reduce `SEARCH_EXHAUSTED`, but it does not explain why hammer interaction and automatic progress both fail on visible ghost roads.
- It risks masking a deeper selector/runtime mismatch.

#### Alternative 2: Revert recent road-related merges

Rollback recent route/runtime changes and rebuild forward.

Why rejected:

- Recent merges combine async map work, route planning, bridge work, and runtime refinements.
- Rollback scope would be large and risky.
- It would likely discard intentional behavior that should remain.

## Detailed Design

### A. Ground Route Resolution Fix

#### Intended Behavior

- Traversable pure-land routes between towns should succeed for both manual and auto use cases.
- `SEARCH_EXHAUSTED` should represent a genuine search failure, not the default outcome for ordinary land terrain.
- Manual and auto roads must share the same land-route success semantics.

#### Investigation Targets

- `LandRoadHybridPathfinder`
  - Neighbor expansion rules
  - Step-up / step-down tolerance
  - Goal reach condition
  - Cost shaping that could bias the open set away from the goal
- `RoadPathfinder.findGroundPathForPlan`
  - Legacy vs hybrid selection
  - How legacy failure is interpreted
  - Whether hybrid failure incorrectly falls back to an already-invalid result
- `RoadAutoRouteService.resolveAutoRoute`
  - Segment orchestration
  - How segment failure is collapsed into `SEARCH_EXHAUSTED`
  - Whether auto routes are skipping a valid direct ground solution

#### Required Behavioral Guarantees

- A route that is valid for manual planning must also be valid for auto route generation when evaluated against the same terrain and exclusions.
- If legacy fails but hybrid succeeds, the shared result must be success.
- If hybrid is selected, its failure must not silently replace a valid legacy result.
- Auto-route segment orchestration must not convert a recoverable segment problem into a permanent ground-route failure without exhausting intended fallbacks.

#### Test Plan For Ground Routing

Add or tighten tests that prove:

- near-distance pure-land manual planning succeeds on traversable terrain
- the same terrain succeeds when called through auto-route resolution
- selector/fallback behavior preserves the best successful ground result
- true failure cases still surface `SEARCH_EXHAUSTED` only when expansion cannot continue

### B. Road Construction Runtime Fix

#### Intended Behavior

- Once a legal road placement plan exists, the runtime must create or refresh an active road construction job.
- That job must advance without hammer input.
- Builder hammer must accelerate the same job and the same remaining steps rather than using a separate target model.

#### Investigation Targets

- `scheduleRoadConstruction(...)`
  - whether valid jobs are being dropped because `buildSteps` look empty or already completed
  - whether restored persisted state is overriding fresh runtime state incorrectly
- `tickRoadConstructions(...)`
  - whether progress is computed but no steps are consumed
  - whether jobs are removed prematurely
- `placeRoadBuildSteps(...)`
  - whether steps are all being classified as `SATISFIED`, `RETRYABLE`, or `BLOCKED`
  - whether phase locking prevents the first placeable batch from ever starting
- `remainingRoadGhostPositions(...)`
  - whether the interactable ghost set differs from the runtime remaining-step set
- hammer target validation
  - whether the client-visible ghost can fail server-side target membership checks

#### Required Behavioral Guarantees

- Visible road ghost positions must be derived from the same remaining-step/runtime source used for placement and hammer validation.
- A newly scheduled valid road job must appear in active road runtime state and survive until completion or rollback.
- Automatic tick advancement must consume at least the first placeable batch without requiring hammer credits.
- Hammer acceleration must target only positions that are also considered remaining runtime steps, but every visible valid remaining ghost must be hammer-targetable.

#### Test Plan For Construction Runtime

Add or tighten tests that prove:

- scheduling a valid road creates an active job
- ticking a scheduled road with no hammer input increases completed or consumed road steps over time
- hammer interaction against a visible remaining road ghost is accepted and accelerates the same runtime job
- automatic progress and hammer progress consume the same remaining-step model

## Data Flow

### Ground Route Resolution

1. Manual or auto caller requests a ground route.
2. Shared routing entry point evaluates legacy and hybrid backends under a single success/failure contract.
3. Best successful land route is returned.
4. Only genuine unsolved cases produce `SEARCH_EXHAUSTED`.

### Road Construction Runtime

1. A valid `RoadPlacementPlan` is produced.
2. `scheduleRoadConstruction(...)` creates or refreshes runtime state from that plan.
3. `tickRoadConstructions(...)` advances progress automatically.
4. `placeRoadBuildSteps(...)` consumes the next placeable runtime batch.
5. Ghost rendering, progress reporting, and hammer target validation all reference the same remaining-step view.
6. Hammer and builder acceleration only increase throughput on the existing job.

## Error Handling

- Keep `SEARCH_EXHAUSTED` as a structured failure reason, but only emit it after intended fallbacks and route backends are exhausted.
- If a road placement plan has zero build steps, explicitly treat it as a plan-generation/runtime-scheduling issue rather than silently allowing a visible but inert construction preview.
- If a visible ghost is not hammer-targetable, that is a runtime consistency bug and should fail tests directly.

## Verification Strategy

Minimum verification before implementation is considered complete:

- focused tests for shared land-route resolution
- focused tests for road job scheduling and automatic advancement
- focused tests for hammer target recognition on visible road ghosts
- existing regression suites around road pathfinding, route orchestration, and road construction runtime
- `.\gradlew.bat compileJava`

## Risks

- Fixing route selection without aligning auto-route orchestration could restore manual roads while leaving auto roads broken.
- Fixing tick advancement without unifying ghost/remaining-step state could restore automatic progress while hammer interaction still fails.
- Over-broad fallback changes could make impossible routes succeed by drifting into non-land behavior.

## Implementation Guidance

Implementation should proceed in two ordered tracks:

1. Restore shared ground-route correctness with regression tests first.
2. Restore road construction runtime scheduling/advancement with regression tests first.

The implementation should avoid UI-layer workarounds. The fix belongs in shared solver/runtime code so that manual roads, auto roads, hammer acceleration, and automatic build progression all recover together.
