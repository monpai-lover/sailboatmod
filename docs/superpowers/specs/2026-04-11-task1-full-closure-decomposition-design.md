# Task 1 Full Closure Decomposition Design

## Scope

This design decomposes `未完成_任务_1.md` into a sequence of implementation-sized sub-projects so the remaining work can be closed quickly and verifiably.

Included:

1. Carriage system wrap-up.
2. UI/tool visibility fixes and post-station-to-post-station road closure.
3. Road preview and runtime rendering polish.
4. Road routing, terrain handling, and lighting closure.

Not included:

- A single giant implementation plan covering every remaining subsystem at once.
- Large architectural rewrites that are not required to close `未完成_任务_1.md`.
- Recreating the missing stash or depending on it as a prerequisite.

## Goals

- Turn every open item in `未完成_任务_1.md` into either:
  - implemented and tested behavior, or
  - an explicit non-goal removed from the task file.
- Define work boundaries so each sub-project can be implemented, tested, and reviewed independently.
- Optimize for fast closure, not idealized long-term architecture.
- Ensure each completed sub-project can be evidenced by code changes, automated checks, and short manual verification steps.

## Current Confirmed State

The repo already contains partial or completed work in all four areas:

- Carriage wood type, motion, sound, and renderer work is already underway and partially verified.
- Claims-map tool visibility now has a shared helper and screen wiring in progress.
- Manual road planning already uses waiting-area exit helpers on the current primary path.
- Preview renderers already have testable camera-offset helpers and the integer camera truncation issue has been addressed.

This means the remaining work should be handled as closure and hardening, not as a fresh greenfield build.

## Decomposition Decision

The remaining work is too broad for a single spec or plan because it spans four loosely coupled subsystems:

1. carriage behavior and presentation
2. claims UI and manual road anchor closure
3. preview/runtime rendering behavior
4. routing, terrain, and lighting rules

Each subsystem has different verification needs, different files, and different risk profiles. Mixing them into one implementation plan would hide completion state and make regressions harder to isolate.

## Chosen Execution Strategy

Use a **sequential minimal-closure** strategy.

This means:

- one sub-project is active at a time
- each sub-project must end with tests plus explicit manual verification notes
- no speculative refactors
- no second system introduced when the current one can be closed safely

This strategy is chosen over parallel or test-first-across-all-subsystems approaches because the primary goal is to clear `未完成_任务_1.md` quickly and truthfully.

## Sub-Project Definitions

### A. Carriage System Wrap-Up

**Goal:** Close carriage wood type, persistence, movement, and sound behavior so carriage-related items in `未完成_任务_1.md` no longer depend on missing backup state.

**Done Criteria:**

- Wood type persists on item stack, placed entity, client sync, and save/load.
- Oak, spruce, and dark oak render as distinct carriage variants.
- Placement, attach, and detach sound events are registered and triggered.
- Carriage ground handling uses explicit carriage-tuned road/off-road/uphill behavior.
- Automated tests cover wood type parsing/persistence and movement/sound helper logic.

**Non-Goals:**

- No full inheritance split from `SailboatEntity`.
- No rolling loop audio system.
- No bone-tint material system.

### B. UI And Post-Station Road Closure

**Goal:** Close the remaining UI visibility leak and fully verify the manual road planner's waiting-area anchor behavior.

**Done Criteria:**

- Claims-map refresh/reset tools only appear on the claims map subpage in both town and nation screens.
- Manual road planning uses post-station waiting-area exits as the operative manual-road anchor path.
- Missing source/target station or exit states fail clearly.
- Exit-direction and fallback behavior are covered by automated tests.

**Non-Goals:**

- No rewrite of all road systems to a strict post-station requirement.
- No large planner-service refactor unless a direct bug requires it.

### C. Preview And Runtime Polish

**Goal:** Make ghost previews and planner previews stable, world-locked, and free of stale render state.

**Done Criteria:**

- Preview block coordinates are derived from precise `Vec3` camera offsets.
- Long-hint-line behavior is absent from manual road preview.
- Preview clear/failure/retarget flows do not leave stale ghost data on screen.
- Critical preview-coordinate logic is covered by renderer helper tests.

**Non-Goals:**

- No new preview UI system.
- No broad overlay redesign outside what is needed to close the open tasks.

### D. Routing, Terrain, And Lighting Closure

**Goal:** Close the remaining "human-like route", extreme-terrain handling, and road-lighting rule items with bounded, testable rules.

**Done Criteria:**

- Route cost logic includes explicit slope, water, bridge, and obstacle preferences.
- Extreme height-difference cases no longer produce obvious discontinuities or unsupported road runs.
- Lighting rules are fixed and implemented with clear exclusions and spacing.
- Pure-logic tests cover the added routing/terrain/lighting decisions.

**Non-Goals:**

- No decorative roadside prop framework.
- No attempt to produce the globally best-looking route in every terrain case.

## File And Plan Structure

Each sub-project gets its own spec and plan.

Planned spec files:

- `docs/superpowers/specs/2026-04-11-task1a-carriage-wrap-up-design.md`
- `docs/superpowers/specs/2026-04-11-task1b-ui-and-post-station-road-closure-design.md`
- `docs/superpowers/specs/2026-04-11-task1c-preview-and-runtime-polish-design.md`
- `docs/superpowers/specs/2026-04-11-task1d-routing-terrain-and-lighting-closure-design.md`

Planned implementation plans:

- `docs/superpowers/plans/2026-04-11-task1a-carriage-wrap-up.md`
- `docs/superpowers/plans/2026-04-11-task1b-ui-and-post-station-road-closure.md`
- `docs/superpowers/plans/2026-04-11-task1c-preview-and-runtime-polish.md`
- `docs/superpowers/plans/2026-04-11-task1d-routing-terrain-and-lighting-closure.md`

Only one of these implementation plans should be active at a time.

## Verification Policy

Each sub-project must end with:

- at least one automated test or helper-level test covering the new closure rule
- `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test`
- short manual verification notes for rendering/resource-facing behavior

Completion is not defined by "code changed". Completion is defined by evidence.

## Task File Closure Policy

`未完成_任务_1.md` should not remain a permanent open-ended backlog after this sequence.

When all four sub-projects are complete, it should be rewritten into one of these forms:

- a short completed-work log with spec/plan references and verification notes, or
- a trimmed archive note stating that the listed items were closed by the referenced specs/plans

It should no longer contain invalid live-state references such as the missing stash note.

## Risks And Controls

**Risk:** scope creeps back into large rewrites.

**Control:** each sub-project spec explicitly lists non-goals and done criteria.

**Risk:** renderer or resource work appears finished but is not actually verified.

**Control:** every rendering sub-project must include helper tests plus manual verification notes.

**Risk:** routing/terrain work keeps expanding indefinitely.

**Control:** closure is defined by bounded rule sets and tests, not by abstract "better pathfinding".

## Recommendation

Proceed with sub-project A first, then continue sequentially through B, C, and D.

This is the most reliable path to clearing `未完成_任务_1.md` without losing truthfulness about what is actually finished.
