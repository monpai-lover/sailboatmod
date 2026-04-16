# Bridge Slope, Exclusion Avoidance, and Placement Stability Design

## Goal

Fix the remaining road/bridge issues in the existing planning and construction pipeline without redesigning the full system:

1. Bridgehead slopes should remain structurally smooth instead of snapping back to terrain as soon as they touch land.
2. Final planned routes must not re-enter excluded columns after post-processing or snapping.
3. Bridge structures should be fully placeable and removable, including heads, supports, pier lights, bridge lamps, and supporting foundation blocks.

This design intentionally preserves the current bridge corridor, preview, and runtime construction architecture. The work is scoped to targeted root-cause fixes and regression coverage.

## Non-Goals

- No rewrite of the hybrid route planner, bridge planner, or road runtime system.
- No visual redesign of previews or bridge style selection.
- No unrelated refactor of user-modified files outside the affected pathfinding, geometry, and construction chain.

## Problem Summary

### 1. Bridgehead slope hard clamp

`RoadGeometryPlanner` currently computes improved span-based heights, but `constrainToTerrainEnvelopeFromSpanPlans(...)` still applies ordinary terrain locking to non-bridge columns near steep terrain. That means bridge approaches can be structurally computed first and then partially crushed back toward `terrain..terrain+1`, creating the "touch ground then dead" behavior the user reported.

### 2. Excluded-zone regression after post-processing

`ManualRoadPlannerService.finalizePlannedPath(...)` already validates candidate paths, but the final acceptance logic still operates as a staged fallback pipeline that can return a snapped or processed route that is continuous while still not respecting the intended avoidance semantics strongly enough. The fix must make excluded-column avoidance a hard acceptance condition for every final candidate.

### 3. Bridge placement instability

The remaining risk is not a single bug but a chain failure possibility:

`BridgeSpanPlan -> RoadCorridorPlan -> placement artifacts/build steps -> tryPlaceRoad(...) -> rollback tracking`

The system must be checked for cases where bridge plans are structurally valid but some blocks still fail to place or clean up because of context detection, support/foundation registration, or runtime placement order.

## Recommended Approach

Use targeted corrections in the existing chain:

1. Introduce a bridge-influence mask in geometry, not just a strict bridge-span mask.
2. Treat excluded-column avoidance as a final-candidate acceptance invariant.
3. Add bridge-specific regression tests that distinguish:
   - plan generation failures
   - missing build steps
   - runtime placement failures
   - rollback omissions

This approach is lower risk than a planner rewrite, preserves current village-lighting and pier-preview behavior, and aligns with the existing code and tests already in the repo.

## Design

### Geometry and slope smoothing

Affected file:
- `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`

Design changes:

1. Add a derived `bridgeInfluenceColumns` mask for span-plan-based height shaping.
   - This mask includes:
   - all actual bridge span columns
   - bridgehead transition columns
   - elevated approach columns produced by valid span plans
2. Update `constrainToTerrainEnvelopeFromSpanPlans(...)` so terrain-lock clamping is skipped for bridge-influence columns, not only direct bridge columns.
3. Preserve existing smoothing constraints:
   - turning-slope flattening
   - slab-preferred turning states
   - max one level change across three segments
4. Keep ordinary terrain locking for unrelated land-only steep segments.

Expected result:
- bridge approaches remain shaped by structured span/ramp logic through touchdown zones
- unrelated non-bridge land roads keep their current anti-spike terrain stabilization

### Excluded-zone avoidance

Affected file:
- `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`

Design changes:

1. Treat excluded-column safety as mandatory for every finalized candidate:
   - raw path
   - post-processed path
   - snapped path
2. Keep the staged fallback order:
   - safe raw candidate
   - safe processed candidate
   - safe snapped candidate
3. Reject any candidate that re-enters excluded columns at the final acceptance stage.
4. If no candidate remains safe and continuous, return empty instead of accepting a cut-through route.
5. Preserve current island/shoreline/bridge-first logic unless the candidate violates this invariant.

Expected result:
- routes may fail more explicitly in unsatisfied cases
- accepted routes will not cross rejected areas because of post-processing or snap regression

### Bridge placement stability

Affected file:
- `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`

Design changes:

1. Verify bridge build-step completeness for long-bridge corridor plans:
   - bridge head deck
   - pier supports
   - railing lights
   - pier lights
   - support/foundation-owned blocks
2. Keep the current "confirmed completion only" semantics for runtime progress.
3. Re-check `tryPlaceRoad(...)` bridge-context placement rules to ensure bridge deck/support placement is not blocked by incorrect terrain assumptions near bridge heads.
4. Ensure foundation/support ownership is preserved for rollback coverage where bridge-related blocks are added dynamically.

Expected result:
- bridge plans that preview correctly also emit complete build steps
- runtime construction does not silently skip structurally required bridge blocks
- rollback removes all owned bridge artifacts

## Data Flow

### Slope path

1. Center path enters span-plan height builder.
2. Span plans generate structured bridge deck/approach heights.
3. Bridge-influence mask protects approach shaping from ordinary terrain hard clamp.
4. Existing smoothing rules finalize heights.
5. Corridor and ghost/build-step generation consume the stabilized profile.

### Final path selection

1. Manual planner produces a candidate path.
2. Raw candidate is checked for continuity and exclusion safety.
3. Post-processed candidate is checked with the same rules.
4. Snapped candidate is checked with the same rules.
5. First safe valid candidate wins; otherwise planning fails.

### Bridge construction

1. Span plan generates valid bridge nodes and deck segments.
2. Corridor plan emits slices with support/light positions.
3. Placement artifacts emit ghost blocks, build steps, and owned blocks.
4. Runtime placement places only confirmed blocks.
5. Rollback tracking covers bridge supports, lights, and derived foundation blocks.

## Error Handling

- If smoothing cannot produce a safe bridge-influenced touchdown profile, the code should keep the existing continuity safeguards rather than force invalid heights.
- If no exclusion-safe final path exists, planning returns empty and is treated as a normal route failure.
- If a bridge plan is structurally valid but runtime placement still cannot place a block, the block remains unconsumed so preview/runtime ghosts remain visible and later repair attempts are possible.

## Testing Strategy

Add or update regression tests before implementation:

1. `RoadGeometryPlannerSlopeTest`
   - bridgehead slope remains above terrain clamp in bridge-influence columns
   - structured ramp remains smooth through touchdown
2. `ManualRoadPlannerServiceTest`
   - finalized route rejects post-processed/snapped candidates that re-enter excluded columns
   - safe fallback candidate is retained
3. `BuilderHammerSupportTest` or `StructureConstructionManagerRoadLinkTest`
   - long bridge emits complete placement/build-step coverage for critical bridge artifacts
   - runtime does not consume failed bridge steps
4. `RoadLifecycleServiceTest`
   - rollback tracks and removes bridge supports, pier columns, lights, and derived support/foundation blocks

Verification sequence:

1. Run targeted red-green regression tests for the new failures.
2. Run bridge-related construction and planner test suites.
3. Run full `.\gradlew.bat build`.

## Files Expected To Change

- `src/main/java/com/monpai/sailboatmod/construction/RoadGeometryPlanner.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/ManualRoadPlannerService.java`
- `src/main/java/com/monpai/sailboatmod/nation/service/StructureConstructionManager.java`
- related targeted tests under `src/test/java/com/monpai/sailboatmod/...`

## Risks and Mitigations

### Risk: over-relaxing terrain clamping causes floating land roads

Mitigation:
- only skip terrain clamp inside bridge-influence columns derived from valid span plans
- keep ordinary terrain locking elsewhere

### Risk: stronger exclusion validation causes more planning failures

Mitigation:
- preserve current fallback ordering
- only reject candidates that are both final and exclusion-invalid
- prefer explicit planning failure over silent forbidden routing

### Risk: bridge runtime fixes regress existing preview/runtime behavior

Mitigation:
- preserve current confirmed-completion semantics
- keep existing bridge preview and structured preview-node tests green
- run bridge-specific and full build verification

## Success Criteria

The work is complete when all of the following are true:

1. Bridgehead slopes no longer collapse back to terrain because of ordinary steep-terrain clamping near touchdown.
2. Final manual road routes never accept candidates that re-enter excluded columns after processing or snapping.
3. Long-bridge plans emit and place their required supports/lights/foundation-related blocks consistently.
4. Rollback removes bridge-related owned blocks without leaving support residues behind.
5. Existing bridge preview, pier preview, structured path preview, and runtime completion regressions remain green.
