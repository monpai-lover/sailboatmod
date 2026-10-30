# Independent route atlas implementation plan

**Goal:** Move route viewing to a standalone item and dedicated UI according to the user's scope correction.

**Architecture:** Keep the authoritative public route catalog/network unchanged; a dedicated client screen owns a view and shared route layer. Existing maps relinquish their fix_11 route hooks. Only root runs Gradle/native games.

**Tech stack:** Java21, NeoForge1.21.1, LDLib2, JUnit5.

- [ ] Client worker removes old screen controls/render/input, retaining the unrelated scheduler reinit fix and its meaningful regression.
- [ ] Item worker adds route_atlas registry/tab, safe item-to-client hook, map model and recipe. Root supplies translation keys.
- [ ] Write RouteAtlasView regressions for same-context pan/zoom preservation, world/dimension reset and recenter; root observes RED then implements view state.
- [ ] Root builds the dedicated LDLib2 map with persistent view, immediate category toggles, selection/details and recenter. Restore ClaimWorldMapView to its pre-overlay baseline; remove obsolete options dialog.
- [ ] Native helper worker adapts ignored real-catalog fixture harness for actual item opening and GUI2/3/4 atlas checks plus old-screen negative checks.
- [ ] Independently review scope, client lifecycle and dedicated-server safety; run targeted/full suite and frontend check.
- [ ] Build fix_12 without smoke sources, run exact JAR server smoke, package and audit CRC/SHA manifests, preserve original world hashes, commit and fast-forward/push default branch.
