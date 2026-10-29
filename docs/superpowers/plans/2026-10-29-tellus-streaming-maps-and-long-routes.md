# Tellus streaming maps and long-route implementation plan

> **For agentic workers:** Use subagent-driven development and regression-first verification. Only the root runs Gradle/native processes. This task remains required alongside the transport controllers.

**Goal:** RoadPlanner, Town and Nation maps show Tellus prediction before exploration, replace it with actual saved/loaded terrain, and support long planning without generating the whole actual corridor first.

**Architecture:** The common client canvas draws separate predicted and actual surfaces. Actual coverage stays authoritative and can be filled asynchronously from existing FULL NBT. Viewport requests use bounded queues and never force ungenerated chunks. Planning uses prediction plus staged bounded route work; actual world generation/loading follows the traveling/building window.

**Tech Stack:** Main-mod TellusSurfaceSamplers, Java21 worker queues, current256px shared maps, versioned directional payloads, JUnit.

## 1. Shared prediction and actual maps

- [x] Add failing tests for prediction under missing actual pixels, actual-over-prediction priority, per-world/dimension/zoom isolation, correct centres/palette, unload and late packet rejection.
- [x] Add source-tagged bounded prediction-tile and actual-chunk patch packets; prediction never marks actual coverage or enters the real rendered-chunk index.
- [x] Add a shared client prediction cache/texture and render it below actual transparent coverage. Remove the opaque missing checkerboard where valid prediction exists. Wire the common RoadPlannerMapCanvas once so Town/Nation share behavior.
- [x] Add a bounded server viewport service: already-loaded data on server thread; existing FULL chunks via safe asynchronous NBT read/decode; Tellus prediction on a dedicated bounded worker. Do not call getChunk(force=true) for automatic map viewing.
- [x] Change automatic claim/Town/Nation requests to a non-generating viewport purpose. Keep deliberately requested administrative force rendering distinct. Ensure ordinary worlds still show actual maps.
- [x] Add dynamic power-of-two terrain spacing for far views without extending legacy MapLod geometry. Isolate caches by world/dimension/spacing, cap visible tiles and asynchronous reads, and prevent coarse actual centre samples from claiming whole chunks as rendered.
- [x] Cancel discarded queued predictions and cooperatively stop fine/coarse raster work between native samples. Reject stale/replaced work before caching or publishing; retain one prediction worker.

## 2. Long planning and progressive real world

- [x] Add route regressions beyond the existing12,288-block water search radius and6,000-chunk road preview corridor. Verify through automated tests that a distant request does not force-generate all covered world chunks and remains bounded/cancellable.
- [x] Use hierarchical/coarse predicted waypoints and bounded detailed segments, with progress and cancellation. Do not merely raise A* node/time/radius caps or generate a giant rectangle.
- [x] Reuse NBT-first/Tellus fallback for water and native generator/Tellus terrain prediction for road preview. Separate planning from actual construction and near-field navigation validation.
- [x] Replace eager complete-corridor road generation during preview with predicted segments. Construction/travel ensures only the current/forthcoming real window and releases old tickets. Preserve route network, transport logistics and authority.
- [x] Add rolling/limited Tellus data prefetch rather than enqueueing the whole long corridor at once. Unknown or unavailable terrain yields progress/wait instead of fabricated passability.
- [x] Preserve the regular sailboat's complete route and global saved cursor. Bound local lookahead to overlapping 256-point windows and merge local repairs without truncating the route prefix/suffix.

## 3. Validation

- [x] Run targeted RED/GREEN source-priority, negative-coordinate, scale, queue, cancellation and long-route tests. The final cancellation change has five failing regressions before implementation and11/11 queue/renderer cases passing after implementation; frontend checks also pass.
- [x] Run and record the final standard full suite after all delivered changes. Main-task builds and final aggregate counts remain authoritative; intermediate/manual totals do not establish final completion.
- [x] Inspect RoadPlanner, Town and Nation in a copy of the Tellus world at multiple GUI scales. Check prediction appears beyond actual terrain and is progressively replaced on visiting/loading without seams or hidden coverage.
- [ ] Verify a long water route and long road preview avoid world-wide generation, then confirm nearby real validation/construction and moving-window loading. Report any native validation limits explicitly.
- [ ] Review each subsystem, package the combined release after controller tests and push the final branch.

## 4. Known limits and verification boundaries

- Long water routing is implemented for the Tellus/NBT path, selected beyond8,192 blocks. It removes the old12,288-block overall constraint for that path, but retains a shared32,000-node coarse budget, detailed segments no longer than4,096 blocks, and local node/time limits. NOISE/HYBRID routes were not separately converted; arbitrary endpoint connectivity is not guaranteed. Progress and service cancellation exist; no dedicated player water-planning cancel control was added.
- Prediction cancellation is cooperative. Discarded/expired work stops before another native sample and cannot publish a late result. It cannot interrupt a synchronous Tellus HTTPS call already in progress; native cold-data download and far-view responsiveness still need explicit acceptance.
- The shared terrain stream uses int-safe coordinates and scale down to1/1,048,576 with at most128 visible tiles. Legacy BlockPos-based requests keep their signed26-bit coordinate boundary. Prediction stays transparent where data is unavailable; coarse actual samples are point evidence, not full underlying chunk coverage.
- Historical fine actual coverage and completed construction/rollback records accumulate with explored or executed history. Bounded active queues/windows do not make all retained history constant-size. Automated/source checks do not establish native map appearance, long sailing/construction, physical driving/delivery or restart recovery.

Final verification: standard releaseMod fix_10 passed1882 tests; frontend check passed. Native screenshots cover all three maps and GUI2/3/4; cold Tellus data took tens of seconds to minutes, while one generated chunk replaced transparent actual pixels. GUI4 Town scroll range and scrollbar access were observed. Full long sailing/construction and physical restart/round trips remain unverified or blocked as documented in FIX10.
