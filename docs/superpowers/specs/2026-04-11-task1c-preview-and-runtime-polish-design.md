# Task 1C Preview And Runtime Polish Design

## Scope

This design closes the preview-stability and runtime-visibility items from `未完成_任务_1.md`.

Included:

1. stable world-locked preview box math using exact camera `Vec3`
2. removal of stale ghost/preview state after failure or retarget flows
3. confirmation that manual-road preview no longer depends on the old long-hint-line display
4. bounded review of runtime progress visibility rules

Not included:

- a new preview UI framework
- a broad redesign of all overlay rendering in the mod

## Current State

The worktree already contains:

- `RoadPlannerPreviewRenderer` using exact camera coordinates
- `ConstructionGhostPreviewRenderer` using exact camera coordinates
- focused renderer tests for preview-box calculations
- overlay rendering that still exposes preview/progress information while holding the planner

This means the main remaining work is to define what “runtime polish complete” means and cover the last stale-state and visibility rules explicitly.

## Goal

Make the road preview feel visually stable and operationally clean enough that the task-file preview section can be closed with evidence instead of vague “still needs polish” language.

## Done Criteria

- Preview and construction ghosts stay world-locked while the player moves, looks around, and traverses slopes.
- Manual-road preview shows ghost blocks and anchor highlights only; the old long hint line is not part of the active manual-road experience.
- Preview failure, cancel, retarget, and confirmation flows do not leave stale ghost data on screen.
- Runtime overlay visibility rules are documented and, where practical, helper-tested.

## Design Decisions

### 1. Exact camera subtraction is the stability boundary

Preview box math should always be:

- world block position
- minus exact camera `Vec3`

Integer camera truncation is the root cause of visible snapping. Do not reintroduce `BlockPos.containing(camera)` style math in preview renderers.

### 2. Preview cleanup is part of correctness, not just polish

If the client hook still carries ghost state after a failed plan, changed target, or explicit mode switch, the UI lies to the player. Clearing stale state is therefore a correctness requirement.

### 3. Overlay visibility should remain intentionally narrow

The current overlay rule is “only while holding the planner.” That is acceptable for closure as long as:

- stale progress entries are dropped
- preview/progress text does not linger after the planner is no longer relevant

The task file should be closed against those concrete rules instead of an open-ended future permissions system.

## Testing And Verification

Automated evidence required:

- `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.client.renderer.RoadPlannerPreviewRendererTest"`
- `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test --tests "com.monpai.sailboatmod.client.renderer.ConstructionGhostPreviewRendererTest"`
- any additional client-hook helper test added for preview clearing
- `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test`

Manual verification notes:

- move and rotate the player near a preview and confirm no whole-block snapping
- confirm failed or cleared previews disappear immediately
- confirm the overlay only appears while the planner is actually relevant in hand

## Risks And Controls

Risk: renderer helper tests pass while stale state still persists in client hooks.

Control: add small state-management tests around the preview/progress hook methods instead of relying only on rendering math tests.

Risk: future work accidentally reintroduces line-based manual preview hints.

Control: keep the renderer focused on ghost boxes and highlights, and document that long hints are a non-goal for this closure pass.

## Recommendation

Treat task 1C as a small hardening pass on top of already-correct render math: verify cleanup behavior, document the runtime visibility rule, and stop once the preview experience is stable and honest.
