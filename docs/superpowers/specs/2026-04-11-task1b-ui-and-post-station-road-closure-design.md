# Task 1B UI And Post-Station Road Closure Design

## Scope

This design closes the remaining UI visibility leak and the manual post-station anchor verification items from `未完成_任务_1.md`.

Included:

1. claims-map-only visibility for refresh/reset map tools
2. strict manual-road validation around post-station waiting-area exits
3. helper tests for exit candidate selection, strict failure states, and station-footprint unblocking
4. closure of the “manual road really connects waiting areas” item with fresh evidence

Not included:

- a full rewrite of all road-planning services
- forcing non-manual planners to use the same strict station rules

## Current State

The worktree already contains:

- `ClaimsMapVisibility`
- town/nation screen wiring that uses the shared helper
- `PostStationRoadAnchorHelper`
- manual-planner tests for strict failure cases and waiting-area unblock behavior

This means task 1B is mostly about finishing verification and plugging any remaining helper-level gaps, not inventing a new planning system.

## Goal

Make the UI and manual-road-anchor sections of `未完成_任务_1.md` closable with targeted tests and a clear explanation of the remaining runtime rules.

## Done Criteria

- Refresh/reset map tools only appear on the actual claims-map subpage in both town and nation screens.
- Manual road planning fails clearly when either side lacks a station or waiting-area exit.
- The planner no longer silently “looks successful” while still depending on stale core/boundary anchor assumptions.
- Waiting-area exit choice and station-footprint unblocking are covered by automated tests.
- The road-planner item model/texture reference is reviewed and noted as either already correct or explicitly adjusted.

## Design Decisions

### 1. UI gating stays centralized in a single helper

`ClaimsMapVisibility.showMapTools(...)` is the one predicate used for:

- button visibility
- layout updates
- page-switch refreshes

The screens should not each carry their own subtly different conditions. A fail-closed helper is safer than duplicated booleans.

### 2. Manual planner closure is defined by strict station-route preconditions

The manual planner must prove four states before planning:

- source station exists
- target station exists
- source waiting-area exit resolved
- target waiting-area exit resolved

If any of these fails, the planner reports a specific failure reason rather than falling back quietly.

### 3. Selected station footprints must not self-block the route

Once the source/target station pair is chosen, the blocked-column set must explicitly unblock:

- waiting-area footprint columns
- selected exit column
- immediate handoff columns already intended as the route start/end

Without that step, the planner can still sabotage the chosen station pair even though helper resolution looks correct on paper.

### 4. “Closure” means helper and service evidence together

`PostStationRoadAnchorHelper` tests alone are not enough. The service layer must also prove:

- strict failure reasons
- unblock behavior
- stable exit-direction selection

That is the evidence level needed to retire the task-file warning.

## Testing And Verification

Automated evidence required:

- `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.client.screen.ClaimsMapVisibilityTest"`
- `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.nation.service.PostStationRoadAnchorHelperTest"`
- `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.nation.service.ManualRoadPlannerServiceTest"`
- `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test`

Manual verification notes:

- open town claims map and non-map subpages, confirm tool buttons only show on the claims map
- repeat in nation UI
- if a runtime repro is practical, create a manual road between towns with post stations and confirm the preview anchors at waiting-area exits rather than coarse town-edge anchors

## Risks And Controls

Risk: screen code still computes visibility in more than one place.

Control: use the helper consistently anywhere the refresh/reset widgets are laid out or refreshed.

Risk: helper tests pass but service flow still falls back incorrectly.

Control: add service-level assertions around strict validation and blocked-column adjustment.

## Recommendation

Use task 1B as a closure pass: preserve the current helper-based design, strengthen the remaining service tests, and record the planner/item-model review outcome directly in the docs and task file.
