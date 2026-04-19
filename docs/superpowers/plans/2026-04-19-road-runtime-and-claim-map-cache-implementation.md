# Road Runtime And Claim Map Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair road auto-construction/runtime regressions, bridge and road turn continuity, terrain-aware land pathfinding fallback, and world-isolated claim-map cache behavior.

**Architecture:** Keep the existing road runtime/save contract centered on `StructureConstructionManager` and `RoadPlacementPlan`, but upgrade the planner, centerline/geometry generation, and runtime step execution so buildable routes can use terrain edits, bridges always touchdown back to terrain, curved decks stay closed, and stalled runtime steps can be skipped/reordered with player-visible diagnostics. Separately, add a client map-context key so minimap/claim preview cache buckets are isolated per world/session and dimension while remaining reusable on re-entry.

**Tech Stack:** Java 17, Forge 1.20.1, existing road planning/runtime classes, JUnit tests under `src/test/java`.

> **Audit note (2026-04-19):** Checkboxes below were corrected to reflect only behavior that can be supported by the current codebase plus fresh verification in this session. Historical red-phase steps were left unchecked unless they were re-executed now.

---

### Task 1: Auto-Build Stall Diagnosis And Recovery

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`

- [ ] **Step 1: Write failing tests for stalled runtime progression and support rollback ownership**

- [ ] **Step 2: Run targeted tests to verify they fail**

- [x] **Step 3: Add runtime diagnostics for the currently blocked road step and blockage reason**

- [x] **Step 4: Implement recoverable runtime progression that can skip/reorder permanently blocked steps and continue building**

- [x] **Step 5: Ensure rollback/dismantle removes runtime-owned supports together with the associated road structure**

- [x] **Step 6: Run targeted tests to verify they pass**

### Task 2: Mandatory Bridge Touchdown And Curved Deck Closure

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadBridgePlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`
- Test: `src/test/java/com/monpai/sailboatmod/construction/RoadBridgePlannerTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerSlopeTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerTest.java`

- [ ] **Step 1: Write failing tests for forced bridge endpoint touchdown on ice/shoreline endpoints and for curved bridge deck closure**

- [ ] **Step 2: Run targeted tests to verify they fail**

- [x] **Step 3: Extend bridge endpoint planning so every bridge tail generates downhill + platform + terrain reconnect beyond the clipped bridge span when needed**

- [x] **Step 4: Close curved bridge decks/platforms by merging adjacent turn footprints instead of relying on isolated slices**

- [x] **Step 5: Run targeted tests to verify they pass**

### Task 3: Land Road Curve Continuity

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadBezierCenterline.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadCorridorPlanner.java`
- Test: `src/test/java/com/monpai/sailboatmod/construction/RoadGeometryPlannerTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/construction/RoadCorridorPlannerTest.java`

- [ ] **Step 1: Write failing tests for ordinary land-road turns that currently split or leave outer-edge gaps**

- [ ] **Step 2: Run targeted tests to verify they fail**

- [x] **Step 3: Upgrade centerline/frame-driven slice generation so land-road turns keep continuous lateral coverage**

- [x] **Step 4: Run targeted tests to verify they pass**

### Task 4: Natural-First Land Pathfinding With Terrain-Modification Fallback

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPathfinder.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadRouteNodePlanner.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinder.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/RoadPlanningSnapshot.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/RoadPathfinderTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/LandRoadHybridPathfinderTest.java`

- [ ] **Step 1: Write failing tests for complex terrain where natural routing should be preferred but construction-aware fallback should still find a buildable route**

- [ ] **Step 2: Run targeted tests to verify they fail**

- [x] **Step 3: Refactor path selection into natural-detour-first and construction-aware fallback passes**

- [ ] **Step 4: Annotate route columns/segments with the accepted construction mode so later runtime phases can clear, cut, fill, tunnel, or bridge them**

- [x] **Step 5: Run targeted tests to verify they pass**

### Task 5: Runtime Terrain Modification And Surface Replacement

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadPlacementPlan.java`
- Modify: `src/main/java/com/monpai/sailboatmod/construction/RoadTerrainShaper.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/StructureConstructionManagerRoadLinkTest.java`

- [ ] **Step 1: Write failing tests for obstacle clearing, roadbed fill/cut ownership, and on-grade natural-surface replacement**

- [ ] **Step 2: Run targeted tests to verify they fail**

- [ ] **Step 3: Convert terrain modification into first-class runtime-owned road build actions**

- [ ] **Step 4: Replace natural surface blocks for on-grade roads instead of floating the road on top of untouched terrain**

- [ ] **Step 5: Run targeted tests to verify they pass**

### Task 6: Claim Map Cache Isolation Per World And Dimension

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/cache/TerrainColorClientCache.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/TownClientHooks.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/NationClientHooks.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreen.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/nation/NationHomeScreen.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ClaimMapViewportSnapshot.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/SyncClaimPreviewMapPacket.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreenTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/screen/nation/NationHomeScreenTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/network/packet/ClaimMapViewportPacketRoundTripTest.java`

- [ ] **Step 1: Write failing tests for cross-world and cross-dimension cache leakage, plus cache reuse when returning to the same map context**

- [ ] **Step 2: Run targeted tests to verify they fail**

- [x] **Step 3: Add a client map-context key and partition all claim preview caches and screen state by that key**

- [x] **Step 4: Ensure world/dimension switches invalidate only the active context while preserving reusable cache buckets for old contexts**

- [x] **Step 5: Run targeted tests to verify they pass**

### Task 7: Claim Map Context Reuse And Screen State Recovery

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/cache/TerrainColorClientCache.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/TownClientHooks.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/NationClientHooks.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreen.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/nation/NationHomeScreen.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ClaimMapViewportSnapshot.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/SyncClaimPreviewMapPacket.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreenTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/screen/nation/NationHomeScreenTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/network/packet/ClaimMapViewportPacketRoundTripTest.java`

- [ ] **Step 1: Write failing tests for reopening the same world/dimension context with preserved cache buckets, pending preview revisions, and screen-local viewport state**

- [ ] **Step 2: Run targeted tests to verify they fail**

- [x] **Step 3: Preserve reusable map-context buckets and reconnect Town/Nation screen state to the correct context when reopening or switching back**

- [x] **Step 4: Ensure context switches discard only stale active view state while keeping reusable buckets and progress metadata for matching contexts**

- [x] **Step 5: Run targeted tests to verify they pass**

### Task 8: Claim Map Parallel Rendering Acceleration And Progress Feedback

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/cache/TerrainColorClientCache.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/TownClientHooks.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/NationClientHooks.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreen.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/nation/NationHomeScreen.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ClaimMapViewportService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ClaimMapViewportSnapshot.java`
- Modify: `src/main/java/com/monpai/sailboatmod/nation/service/ClaimPreviewTerrainService.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/SyncClaimPreviewMapPacket.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/screen/town/TownHomeScreenTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/client/screen/nation/NationHomeScreenTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/nation/service/ClaimMapViewportServiceTest.java`
- Test: `src/test/java/com/monpai/sailboatmod/network/packet/ClaimMapViewportPacketRoundTripTest.java`

- [x] **Step 1: Write failing tests for visible/prefetch progress accounting, low-profile bottom progress UI, and faster completion of visible map chunks under batched parallel rendering**

- [x] **Step 2: Run targeted tests to verify they fail**

- [x] **Step 3: Add visible/prefetch progress counters to viewport snapshots and packet sync so both screens can render true progress instead of a loading boolean**

- [x] **Step 4: Replace single-threaded terrain tile draining with bounded multi-threaded sampling that prioritizes visible viewport chunks and batches multiple missing chunk tiles per scheduling pass**

- [x] **Step 5: Add a subtle bottom-edge progress indicator that remains visible until both visible and prefetch queues finish, without materially affecting map layout**

- [x] **Step 6: Run targeted tests to verify they pass**

### Task 9: Final Verification

**Files:**
- Modify: none expected unless verification reveals a regression

- [x] **Step 1: Run the related road/runtime/claim-map regression suite**

- [x] **Step 2: Run `.\gradlew.bat compileJava`**

- [x] **Step 3: If verification is green, run `.\gradlew.bat build` to produce a fresh jar**
