# SailboatMod 1.21.1 fix_20 — autonav hotfix3 source port

This branch ports the **experimental** hotfix3 logic from the supplied 1.3.13 fix_20 JAR into maintainable Java sources. It is meant for a **review / test build**, not immediate production deployment.

## Problem reproduced

A ship dispatched through the aboard-ship destination menu can receive a connector that fits in an asymmetric 2×2 water footprint, then blindly append a saved lane that skims a shoreline. The hull reaches a corner with inadequate turning room, oscillates between SAILING and BLOCKED, repeatedly reroutes, and can exhaust the local rescue budget while no entity-level collision is reported.

The former progress checkpoint also required >0.08 blocks of progress **every tick** even though its own corner speed cap can be 0.035–0.05 blocks/tick. Steering control could oscillate at a single threshold, and a passenger-driven manual yaw limiter could counteract the autopilot's turn rate.

## Ported source logic

- Ship-origin menus refine **the entire private itinerary** (connector + saved suffix), keeping the public route graph untouched.
- The itinerary refiner uses a centred 5×5 water footprint for cruising and validates each connecting edge, with bounded local repairs and fail-closed UNKNOWN handling.
- Physical hull collision remains authoritative; the live motion gate and local detour planner share the same coastal reserve (near terminal berth only the existing physical check is used).
- Adjacent waypoint tracking avoids premature corner cuts and handles coincident nodes.
- Steering uses 95° / 35° hysteresis in open water and tighter alignment for near corners, with bounded pivot recovery and diagnostic traces.
- Slow-but-real forward progress accumulates against a checkpoint; deliberate turning and terrain waits are not falsely counted as physical stalls.
- This port only changes source code. It does **not** include the 27.9 MB binary JAR or alter world-save, network protocol, registry, item, or recipe formats.

## Verification and deployment status

Original locally produced experimental **JAR** SHA-256:
`2746ba65fee54a4c76ad6166c5775605b650448f38997518387d3141e068719b`

The binary hotfix3 was tested offline with simplified geometry and JVM probes (35 navigation/collision checks, 42 steering checks, 12 previously failing wall scenarios, 24 shore checks, and 3 menu-entry wiring checks). **Those results belong to the bytecode-patched JAR, not automatically to this Java source port.**

**This GitHub source port has NOT yet been built as the complete NeoForge + Create + Sable + Tellus project, and has NOT passed a live server test.** In particular, compile/link, real water physics, Sable sublevels, player sync, docking, and unloaded-chunk behavior need independent validation.

## Suggested acceptance tests

1. Build with the repository's NeoForge 1.21.1 / Java 21 toolchain and run existing tests.
2. Start a backed-up world, verify startup and that navigation logs include `navfix3=[...]`.
3. From aboard a ship near a bank, reselect a destination (resuming an old voyage does not recalculate its path). Inspect `[navfix3-shore]` output.
4. Exercise a shallow-angle shore, a 90° bank corner, a narrow channel that must refuse to sail, an actual obstacle, and an unloaded-chunk case.
5. Verify no blocking world-generation calls were introduced on the server tick.
6. Verify non-autopilot manual steering, cargo loading/unloading, and departure/return berth behavior.

Do not merge until the source port is compiled and run against the current repository dependency set. If it needs further adjustment, keep edits scoped to the autonav branch.
