# Staged road implementation handoff

Status: implementation is connected to the real request/confirmation entrypoints. Final integrated tests and native validation are pending; do not describe this document as evidence of in-game success.

## Connected flow

0. `RoadPlannerRouteExpander.expandForSubmission` estimates interpolation work with `double` coordinates before any client terrain sweep. Distant submissions preserve compact typed nodes; short submissions retain automatic water-crossing expansion. The actual planner submit button, long manual node insertion and client normalisation use this boundary. Automatic map entry/route preload requests acknowledge viewport streaming without enumerating or force-generating a corridor. Explicit force-render selection estimates its area before bounded enumeration and rejects an oversized selection.
1. `RoadPlannerPreviewRequestPacket.handle` validates bounded submitted nodes, cancels the previous unconfirmed preview, and submits a streaming job to the existing `RoadPlannerPreviewJobService` lifecycle. It does not enumerate or force-load the complete corridor.
2. `RoadPlannerStagedPlan` retains the submitted nodes and segment types. Each lazily resolved stage has a 128-block core and 32-block sampling halo, including typed neighbouring road/bridge approaches. Chunk enumeration follows only the current local corridor.
3. `RoadPlannerStagedPreviewJob.prepared` captures loaded chunk palette copies on the server tick, then dispatches one worker stage. The worker reads its immutable copies, existing FULL NBT, or native generator columns. `FutureTask` cancellation interrupts the actual stage task. Results are published only by the server tick.
4. `RoadPlannerPreviewAccumulator` retains at most 64 overview points and 2,048 nearby ghost blocks. Partial results cannot be confirmed. Only a complete preview attaches the compressed plan and bridge compiler to `RoadPlannerBuildControlService`. Its snapshot is bound to the original dimension; a cross-dimension confirmation rejects before accessing the new level or consuming the original preview.
5. Confirmation creates `RoadPlannerStagedBuildQueue`; it does not attach or execute predicted build steps. The current real window warms up through region tickets, compiles against the actual level, and executes the existing eight-step tick budget. Completed windows release their tickets and refresh/resend only their own real geometry. Pause/cancel releases the current window. Final registration obtains one completed-history snapshot and does not resend the complete historical road.
6. Original material settings, merge revalidation, reuse ownership filtering, protected-core checks, completed-road registration and rollback entries remain in the existing pipeline. Actual completed geometry/history grows as construction executes; the complete future road is not compiled or retained up front.
7. Confirmation preserves the new preview while an owner still has an active build or an unfinished asynchronous rollback. The packet gives a specific waiting message. Another owner's independent build remains unaffected, and an older rollback cannot later overwrite that owner's newly confirmed construction.

## Geometry

The existing structure expander and road/bridge emitters remain authoritative. Long bridge slices use an optional `RoadTerrainSampler.BridgeWindow` carrying the complete bridge's coordinate system, shore references and predicted water reference. `RoadPlannerBridgeGeometryPlanner.planWindow` slices the original ramp/deck profile and global pier spacing. Ordinary samplers and short bridges keep the existing geometry path. Typed approach halos prevent a bridge's road transitions from disappearing at a stage boundary.

The halo guides geometry but does not execute a preceding/future leg's columns. Actual long-bridge compilation checks only its current owned core against the preview's shore/water/clearance profile. Incompatible real terrain pauses with a visible instruction to cancel and preview again. Already built halo and remote bridge endpoints are excluded from these checks.

A compressed long ROAD crossing genuine sampled water fails visibly and asks for explicit bridge segments, in both prediction and real compilation. Missing native columns retain the existing visible unavailable/error behaviour; the long submission is not made buildable by inventing sea-floor terrain.

`RoadPlannerStageCompilerTest` compares the actual emitted visible geometry/material of a 400-block oak bridge against the original complete expander, and guards every real terrain query against the current held chunk window. This regression still requires an integrated run after the latest edits.

## Limits and ownership

- One queued/running stage per preview; eight active streaming previews; one terrain worker with queue capacity eight.
- A stage uses up to 4,096 cached immutable columns. Its NBT caches and copied palettes die with that stage.
- Real construction requests eight chunks per tick, with at most 32 waiting in flight, and retains only the current local stage's tickets.
- The former 6,000-chunk and 32,000-interpolation constants remain only in the legacy diagnostic `pathChunks` helper used by older tests. Neither the real preview handler nor real confirmation uses those corridor bounds. The synchronous `forceLoadNodeChunks` fallback has been removed.
- No new packet registration is required for road staging; it uses the existing preview request/result and construction controls.
- Root owns Gradle, central lifecycle/network edits, and all game launches. This worker has not launched Gradle or Minecraft and has not committed.

## Integration and verification

- Packed-position validation now uses the signed 26-bit BlockPos X/Z range, including the established Tellus coordinate X=33,431,923. The two regressions failed under the earlier vanilla 30-million rule; final GREEN is owned by root.
- `RoadPlannerRollbackJob` now reverses at most eight real entries per tick. Its current window includes the target chunks and the neighbour ring needed by block updates, at most 72 chunks, requested eight per tick with 32 in flight. Each completed/cancelled window releases its tickets. Cross-dimension cancellation resolves the original build dimension.
- The common offline NBT wait and optional worker flush now preserve interruption. A cancelled worker does not read an already-completed future or continue into native prediction, and does not cancel the shared vanilla I/O future. Two pure regressions cover these cases.
- `RoadPlannerBuildControlService.stopServer()` now cancels and releases transient construction/rollback windows before clearing them. Root must connect this method to the central server-stop hook in place of merely clearing unconfirmed previews.
- Run all new road regressions and the full suite after the current Gradle dependency-cache repair. Earlier helper GREEN results do not verify the later entrypoint integration.
- Native checks: long road beyond 6,000 corridor chunks, cancellation during external terrain waits, changing dimensions/logging out, partial preview progress, material preservation, bridge approaches/seams, and real construction advancing without generating the complete remote corridor.

Root's plain bootstrap runner has verified the actual 400-block bridge geometry/material/held-window comparison. This is source/runtime-unit evidence, not a Minecraft client validation.

## Far-zoom territory overlays

`ClaimMapPainter` now iterates the known claim collection instead of the complete visible chunk rectangle. Nation and Town pass their cached claim values and use direct map lookup for owner neighbours, hover and selection. Initial screen data populates the index before painting. A large area selection projects only its two corners and emits at most four screen-clipped border rectangles. The bounded-work regressions use a continent-sized visible range without a GUI or world.

Both screens also stop before sending when the far-view world-map request factory returns no request. This covers direct pan-finished sends as well as the ordinary visible-request guard. The shared view/far-zoom implementation is owned by the other map worker.

## Regression classes

`RoadPlannerPreviewChunkLimitTest`, `RoadPlannerStagedPlanTest`, `RoadPlannerStagedPreviewJobTest`, `RoadPlannerStreamingPreviewServiceTest`, `RoadPlanningTerrainSnapshotTest`, `RoadPlannerPreviewAccumulatorTest`, `RoadPlannerStagedBuildQueueTest`, `RoadPlannerBridgeWindowTest`, `RoadPlannerStageCompilerTest`, `RoadPlannerRollbackJobTest`, `RoadPlannerLongSubmissionTest`, `RoadPlannerMapPreloadServiceTest`, `RoadMapRoutePreloadPlannerTest`, `OfflineChunkNbtReaderInterruptTest`, and `ClaimMapPainterBoundedWorkTest`.
