# Verified harbour connectors, local rescue and helm seating

## Identity
Production commit: `47f4fcc6c452ef9e74546a2a5a132f775ad78274`.
GitHub Actions run `41319809578`, job `124887492059`: SUCCESS. Artifact `13089036896` downloaded and independently checked.
Artifact ZIP SHA256: `71446855f20f650496068fcad1f914e830277fd3112e9310bf8cde0e1e942db2`.
Main build name: `sailboatmod-1.3.13-fix_harbor_rescue_helm-neoforge-1.21.1.jar`.
Delivery name: `sailboatmod-1.3.13-harbor-rescue-helm-neoforge-1.21.1.jar` (rename only).
Size: **27,722,936 bytes**. SHA256: **`caf2065e59d7fb4492074019875bb29fead27d212c129cec53e5c193b9373221`**.
Full Gradle compilation and packaging, not bytecode replacement. Protocol remains **21** (also verified with javap on the delivered JAR).

## Failure and correction
The logged six-block departure connector succeeded, but the following mask-approved direct edge `(32264,65,-50120) -> (32072,65,-50040)` disagreed with real harbour terrain. Previously a strict validation failure passed the unchanged invalid route into an optimizer which requires valid input, so its mandatory input check rejected it at `(32258,65,-50117)`.
`NearPortRouteRepair` now runs BEFORE optional postprocessing. It validates near-port edges, uses bounded strict local detours, merges invalid intermediate hints when necessary, preserves actual first/final berths and updates edge provenance. It does not turn unknown or land into water. A sealed port or invalid true endpoint still fails.
The logged coordinate is a rejected hull anchor; the old combined message did not uniquely identify which footprint cell or water-height condition failed in the current save.

`StrictWaterDetour` is shared by near-port repair and boat lookahead/rescue: real-first 2x2 hull, equal water height, cell-supercover edges, cancellable local search and final connection checks. Windows are bounded to4096 blocks, with shared request deadlines/node limits. These are cooperative limits, not hard preemption of third-party calls.

Tellus passive rescue uses `WaterRescuePlanner` to reconnect within512 forward route blocks, including the interior of a sparse long leg, and retains the original suffix/destination. No full-voyage restart and no nearest-water teleport. Boats hold while work is pending; callbacks check request identity, voyage, target index, level and displacement. Failure stays paused. Both lookahead and local rescue respect pause; removed the old tick auto-resume based on a null controlling passenger.
Removed synchronous on-demand NBT rescue from the entity-tick path. Lookahead now observes bounded-executor rejection and generation cancellation. Stop/pause invalidates pending work.

`SailboatSeatLayout` shares scale0.68/lift0.35 with the renderer and places driver seat0 behind the model wheel, using the rear-deck height. Seat rotation now follows Minecraft yaw. Model, passenger count and manual/autopilot authority were not replaced. In-game rider pose, camera clearance and visuals still require client acceptance.

## Actual checks
- 215 offline JUnit XML reports: **1052 tests,0 failures,0 errors,0 skipped**.
- 1 native Tellus JUnit XML report: **6 tests,0 failures,0 errors,0 skipped**.
- Total **1058 targeted tests**, not all tests in the repository. Includes21 new cases:9 near-port,6 local-rescue,6 helm/lifecycle cases; existing lookahead wiring tests are also included.
- All16 edited source/test files match the build archive byte for byte. JAR contains the new production classes and no test Minecraft/JUnit substitutes.
- Preserved byte-identically against prior verified road build:313 road/roadplanner sources,42 MarketWeb sources,24 web resources,17 terrain predictor sources and2 chat sources.

The exact-coordinate quay regression is SYNTHETIC, not a reconstruction of the current world. Its actual postprocessed output is:
`(32258,65,-50119) -> (32240,65,-50120) -> (32072,65,-50040)`;
all edges pass strict checks. Original mask path went first to `(32264,65,-50120)` then crossed the artificial quay. CSV: `build/harbor-evidence/logged-quay-repair.csv`.
A separate strict local path test uses the previously uploaded junshi harbour surface crop; unexported cells remain blocked.
Native Tellus0.8.3 compatibility tests exercise existing LOD/generator long routes and5 sanitized saved-region fixtures, NOT the current complete multiplayer save or the global-mask harbour branch end to end. Four native output routes independently checked at quarter-block intervals have zero hull land hits; reverse interior reuse also passes. These tests are not live boat physics or seating visual tests.
The first CI attempt failed before Java compilation due to a Gradle init-script brace error. The second passed offline regressions but was superseded for the pause-entry protection. Counts above are from the final complete successful run only.

## Delivery
Client and server main mod should be updated together. Do not keep old main JARs alongside the new one. Protocol21 unchanged. **Independent sailboatmod-marketweb addon does not need updating**; it was compiled for compatibility but no web HTTP/DTO contract changed.
No region deletion or save reset. Re-submit failed routes; old saved routes are not automatically re-optimized. Already paused boats require an explicit resume after the local obstruction is resolved.
No force-push, merge to the main branch or live deployment. Previous far-route, destination reuse, road autocomplete, chat and task-queue fixes remain.
