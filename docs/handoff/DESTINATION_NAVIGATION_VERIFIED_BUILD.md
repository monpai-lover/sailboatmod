# Destination navigation: complete build verification

Functional commit: `c58af4f84fba9c6718c19fc05201ad5b533d81f5`.
GitHub Actions run `41290673270`, job `124801079313`, artifact `13076909945`: SUCCESS.

This supersedes the previously local/incremental destination and adaptive-budget handoffs. It is a complete Gradle build, not a class replacement in an older JAR. The production source in the cleanup commit is unchanged from this tested commit.

## Delivery identity

- Main build filename: `sailboatmod-1.3.13-fix_destination_voyage-neoforge-1.21.1.jar`.
- Delivered alias: `sailboatmod-1.3.13-destination-navigation-test-neoforge-1.21.1.jar`.
- 27,557,471 bytes; SHA-256 `980f255858491c95574df8cc31a20150677b9f964121d1757626a17352f8848e`.
- Protocol **20**: update clients and server together. Keep only one main SailboatMod JAR.
- The independent MarketWeb addon was compiled as an API compatibility check. Its sources and HTTP/DTO schema were not changed; the existing addon need not be replaced for this update.

## Implemented

- Per-dimension persistent water-route infrastructure separate from a vessel's private itinerary and cargo. Full/reverse/interior reuse and useful prefix/suffix composition; every gap still runs normal water planning and every reused edge is rechecked. Exact on-network endpoints no longer invent nearby fresh connectors.
- Player dispatch selects a vessel and destination; port storage shipments and automatic market routing use the same destination planner. Authorization, endpoint validity and cargo availability are checked again after asynchronous planning; payment and cargo removal are deferred until departure.
- Monotonic polyline following, swept waypoint passage and safe immediate-next-leg rejoin replace exact-vertex chasing. Live loaded-only checks prevent blind corner skips; sharp turns and heading errors reduce speed. Existing server-authoritative passenger policy is retained.
- Map trace geometry follows the current vessel itinerary after docking/rescue/splicing; raises the old 2048-point trace limit to 65536 and reports live position/progress at a bounded 10-tick cadence. Planned geometry remains distinct from physical motion and browser interpolation.
- Bidirectional meeting uses checked rooted parent chains, rather than requiring both sides to close the same node. Adaptive progress budgets remain bounded; no arbitrary failed reads earn time.
- Whole-route postprocessing reserves separate time slices for pruning, offshore relocation, rounding and final pruning. Certified edits survive later budget exhaustion. Initial pruning avoids expensive clearance-ring scans; final pruning preserves tapered offshore clearance. Default target12 blocks, max shift32; narrow channels retain required turns and unknown terrain never becomes water.

## Tests actually executed

63 offline JUnit XML reports: **381 tests**, zero failures/errors/skips.
1 actual Tellus native JUnit XML report: **6 tests**, zero failures/errors/skips.
Total **387 targeted tests**, not the whole repository test suite. Main JAR and addon compiled and packaged successfully. All32 locally reviewed changed source/test/document files match the build source archive byte-for-byte; protocol20 and required production classes were inspected in the delivered JAR. No test stubs are packaged.

Native tests use pinned Tellus0.8.3 LOD and generator columns, plus the existing five sanitized uploaded terrain fixtures; they do not represent the user's entire live save. Each final path has an additional independent quarter-block 2x2 hull/water-height check.

| Native route | Before quality, blocks | After quality, blocks | Hairpins >120deg before/after | Independent hull checks | Collisions |
|---|---:|---:|---:|---:|---:|
| Original7623-block route, first long cold corridor |10678.14|9654.67|29/5|156296|0|
| Crete to Istanbul |11728.43|10346.40|29/0|166740|0|
| Reverse7623-block route |10629.05|9039.09|26/0|145760|0|
| Delayed strait |1510.12|1244.40|3/0|20004|0|

Original/cold case had65 moved controls and64 rounded corners; the other cases moved39/33/12 controls respectively. Long routes hit an optional quality slice limit and retained the last safe edits. In particular, the first cold route STILL has5 >120degree turns; do not advertise universally zero zigzags, zero hairpins, globally shortest routes, or a guaranteed offshore margin in every narrow passage.

Actual Tellus interior reverse reuse:2685.0644 blocks reused, new connector length0, newAStarRuns0, independently checked43828 hull cells, collisions0. The measured29.85ms reused warm captured data; it is not a server-wide latency promise.

The first build passed379 offline tests but exposed a real nonzero-connector reuse error in native testing. It was corrected and all tests rerun in the final successful run. Postprocessing also initially exhausted its budget before relocating controls; the staged/checkpointed implementation above is the corrected build.

## Installation and limits

Back up and stop before replacing both ends' main JAR. Protocol19 clients cannot use this protocol20 server. No region deletion or regeneration is needed. Existing routes and orders are not removed; generated routes are imported as their docks load, rather than by force-loading all ports. Existing route geometry is not automatically reoptimized; recreate a route to apply the new quality pass.

No live multiplayer passenger/vehicle physics, GUI/browser rendering, or macOS acceptance test was performed. Pure follower simulation and actual terrain integrations do not prove every ship can take every corner. Known passable-water geometry does not equal a verified turning radius. Failed/unknown terrain still stops or replans instead of inventing a route. Main branch was not merged and no live server was deployed.
