# Airport Visual Planner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Independent files may be developed in parallel; root coordinates build and live verification.

**Goal:** Make airport runway, taxi and parking planning use an interactive real terrain map, following RoadPlanner.

**Architecture:** Reuse RoadPlannerMapView, RoadPlannerMapCanvas and sharedDefault tile cache, add an airport-only geometry draft model, map input/overlay, and AirportScreen integration. Keep existing server validation, dimension/revision guards and world preview.

**Tech Stack:** Java 21, NeoForge 1.21.1, LDLib2 themed UI, existing shared terrain protocol, JUnit.

## Task 1: Pure draft and geometry editing
- [x] Write behavior tests covering two-click runway, half-block conventions, directed polyline insert/move/delete/hold, stand position/heading, bounded history, undo, rejection and stale snapshot preservation.
- [x] Run the focused test red with the serialized Gradle runner; implement AirportPlannerModel and run green.
- [x] Publish its concrete API to the screen and renderer owners, preserving server packet shape.

## Task 2: Real map input and overlay
- [x] Create independent AirportMapElement using LDLib2 input, with wheel around cursor, middle pan, left draw/select/drag, release/capture cancellation and keyboard delete/undo.
- [x] Create AirportMapOverlay using model draft/saved geometry. Paint runway footprints/arrows, taxi corridors/nodes/wait line, rotated parking areas/heading handle. Use scissor and pixel-based hit tolerances.
- [x] Exercise geometry/coordinate and interaction bounds in focused tests; do not close the shared tile cache.

## Task 3: Primary AirportScreen map and properties
- [x] Replace form-primary layout with large map, tool row and selected-object properties. Reuse shared terrain canvas recipe from RouteAtlasScreen; do not edit generic ClaimMapElement.
- [x] Retain overview, owner/town/status, existing forms as precision controls, guarded world pick, dimension binding and server-only saves.
- [x] Save one selected facility with current expected revision, retain rejected/conflicted drafts and require explicit rebase/discard. Refresh and resize must preserve edits.
- [x] Add matching en_us/zh_cn visible labels and concise controls; verify existing picker/input/dimension regression tests.

## Task 4: Independent review and verified delivery
- [x] Spec review then code-quality review; fix substantive findings with regressions.
- [x] Run full JUnit plus releaseMod under serialized runner, verify complete release asset content and hashes.
- [x] Back up isolated world and replace only cloned client/test server artifacts; normal restart and connection.
- [x] Actually draw/edit/save runway, outbound/inbound paths and stand in game, inspect accepted server geometry, verify rejection/draft behavior and screenshot the map. Verify restart persistence.
- [ ] Resume physical fixed-wing calibration, real flight and normal fake-buyer cargo test; distinguish this unpassed goal from UI completion.

No commits, pushes or production installs are requested. Existing dirty work is preserved. Source checks do not establish native physics or order delivery success.

## Verified progress, 2026-10-31 14:20 CST

Tasks 1–4 visual-editor delivery are verified in the isolated fix22 client/server. Full JUnit: 2257 tests, 0 failures, 0 errors, 1 skipped, 476 classes. Actual native GUI drew and saved both C/D layouts on fix20; fix21 and fix22 normal restart readbacks exactly match their immutable geometry/revision baselines (C6/D5). Native GUI covered drag/undo/stand heading, pan/zoom, server foreign-town rejection with draft retention and discard, one-based outbound hold numbers 2/12, hidden inbound hold controls, and compact readable coordinate labels with full stored precision preserved. Evidence: `build/live-client-test/fix22-deployment/RESULT.md`. Physical fixed-wing flight/cargo success remains unverified and is the continuing original task.
