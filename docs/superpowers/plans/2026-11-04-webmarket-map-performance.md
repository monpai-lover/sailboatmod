# WebMarket map performance implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Each worker owns disjoint production files; root alone runs Gradle and integration launches.

**Goal:** Remove browsing-driven map rescans and keep background map work within a controlled server tick budget while retaining cached map viewing and market operations.

**Architecture:** Add addon-owned settings and a global update gate. Reuse existing immutable chunk snapshots, region rendering and map cache. Separate cache reads from discovery, share one nanosecond budget across sampling paths, coalesce persistence and refresh only changed terrain images.

**Tech Stack:** Java 21, Minecraft 1.21.1, NeoForge, existing HTTP server, Leaflet/vanilla JS, JUnit/Node.

## Task 1: Global controls (root)

- [x] Register independent addon config and conservative settings.
- [x] Add `/marketweb map pause|resume|status`; persist the update switch.
- [x] Gate startup/tick producers and Tellus queue draining; preserve resume work and cached reads.
- [x] Add tests for controls and review behavior under no active render job.

## Task 2: HTTP and terrain refresh (HTTP worker)

- [x] Add regressions for cache-hit reads without discovery and bounded overview misses.
- [x] Add bounded tile revision tracking and conditional HTTP reads.
- [x] Refresh changed terrain tiles without mixing business/selection revisions into terrain keys.
- [x] Run focused Node checks and give root JUnit selectors.

## Task 3: Sampling budget and cooldown (scheduler worker)

- [x] Write regressions for fake-clock budget, once-per-tick reset, loaded/north samples, retained/deferred work and invalidation.
- [x] Enforce common configurable capture/probe limits in all active render paths.
- [x] Avoid repeated sampling of unchanged loaded chunks and bound browser discovery.
- [x] Expose useful diagnostics and integrate the state writer through agreed APIs.

## Task 4: State persistence (persistence worker)

- [x] Test coalescing, latest-state persistence, concurrent mutation and shutdown drain.
- [x] Keep file writes and JSON encoding outside queue monitors.
- [x] Bound/coalesce scheduled writes; pass integration instructions to the scheduler owner.

## Task 5: Integration (root)

- [x] Run focused regressions, then the complete required checks; distinguish baseline failures.
- [x] Review each task for requirements and resource/thread safety.
- [x] Verify actual packaged addon, offline/server behavior, unchanged map compatibility and main-mod dependency contract.
- [x] Package replacement addon, config/command instructions and SHA256 evidence.
- [x] Commit only scoped changes, integrate atop latest remote and push without force.
