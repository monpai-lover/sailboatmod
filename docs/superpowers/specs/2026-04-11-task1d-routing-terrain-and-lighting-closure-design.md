# Task 1D Routing, Terrain, And Lighting Closure Design

## Scope

This design closes the remaining route-quality, terrain-handling, and road-lighting items from `未完成_任务_1.md`.

Included:

1. explicit route-cost rules for slope, water, bridge usage, and preferred terrain
2. bounded review of extreme-height handling in route and geometry planning
3. explicit final rules for road lighting placement and exclusion zones
4. pure-logic tests that lock those rules down

Not included:

- a globally optimal scenic pathfinder
- decorative roadside-prop systems
- a rewrite of the construction pipeline

## Current State

The worktree already has a usable baseline:

- `RoadRouteNodePlanner` applies penalties for height, water, terrain, bridges, and turns
- `RoadPathfinder` samples route columns and feeds the planner plus Bezier centerline smoothing
- `RoadLightingPlanner` already places lamp posts while skipping bridge ranges and protected columns

What is missing is a closure-grade contract that says which heuristics are final enough to mark the task file complete and which tests prove them.

## Goal

Replace vague “continue improving human-like routing / terrain / lighting” backlog language with a bounded, test-backed ruleset that is good enough to ship and maintain.

## Done Criteria

- Route cost rules explicitly prefer gentler, drier, less obstructed ground over brute-force shortest paths.
- Bridge usage is bounded rather than allowed to dominate a path.
- Extreme local height changes no longer produce obviously unsupported or discontinuous route plans in helper-level tests.
- Lighting placement has clear spacing and exclusions, especially for bridges and protected columns.
- New pure-logic tests cover route-cost and lamp-placement rules.

## Design Decisions

### 1. Closure is rule-based, not perfection-based

The task file currently phrases routing as an endless improvement problem. That is not a closable definition.

For this pass, “done” means the planner has explicit preferences for:

- height changes
- adjacent water
- bridge use
- rough or soft terrain
- unnecessary turns

and those preferences are covered by tests.

### 2. Route logic remains in `RoadRouteNodePlanner`

Do not split heuristics across multiple places unless a testability problem requires it. The current planner already owns:

- per-step cost
- bridge budget
- turn penalties
- preferred-terrain bonus

That makes it the right place to harden the remaining route-quality rules.

### 3. Lighting stays practical and exclusion-driven

`RoadLightingPlanner` should keep a simple operational rule set:

- fixed spacing
- skip bridge ranges
- skip protected columns
- offset lamps beside the road rather than inside the travel band

This is enough to close the lighting item without inventing a decoration framework.

### 4. Extreme terrain closure should target unsupported cases only

The goal is not to solve every possible mountain road. The goal is to stop obvious bad outputs:

- discontinuous climbs
- excessive bridge chains
- unsupported route runs over extreme local gradients

If helper-level tests prove the planner rejects or strongly disfavors those shapes, the task is closeable.

## Testing And Verification

Automated evidence required:

- new pure-logic tests for `RoadRouteNodePlanner`
- new pure-logic tests for `RoadLightingPlanner`
- `./gradlew -Dnet.minecraftforge.gradle.check.certs=false test`

Manual verification notes if runtime inspection is practical:

- inspect at least one steep-terrain route preview/build result
- inspect one route with bridge opportunities and confirm lamp posts do not appear on the bridge body

## Risks And Controls

Risk: “human-like route” remains subjective and expands forever.

Control: codify a small, explicit heuristic contract and stop at that boundary.

Risk: lighting placement seems acceptable in code but collides with protected columns.

Control: add direct planner tests for protected-column and bridge-range exclusions.

## Recommendation

Finish task 1D by tightening the existing pure-logic planners, not by inventing a new routing stack. The winning move here is to freeze the current heuristics into clear tests and then close the backlog item.
