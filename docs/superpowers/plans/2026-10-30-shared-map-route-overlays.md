# Shared map route overlays implementation plan

> For agentic workers: use subagent-driven development and regression-first verification. Only root runs Gradle or native Minecraft processes; source owners do not overlap.

**Goal:** Registered roads, land routes and sea routes can be viewed, filtered and inspected in RoadPlanner, Town and Nation maps.

**Architecture:** Immutable shared route DTOs and geometry utilities feed a bounded main-mod viewport service. A common client route layer owns filtering, hit testing and selected details; existing screens expose its controls and retain their original default input semantics.

**Tech stack:** Java21, NeoForge1.21.1, LDLib2 2.2.42, existing SavedData catalogs, JUnit5.

## 1. Shared contracts and geometry — geometry worker

Files: create `map/route/{MapRouteKind,MapRoutePoint,MapRouteEntry,MapRouteViewport,MapRouteSnapshot,MapRouteGeometry}.java` and corresponding `src/test/java/com/monpai/sailboatmod/map/route/*Test.java`.

- [ ] Write regressions for a path from(-10,5) to(20,5) crossing viewport(0,0,10,10), disjoint re-entry, large/negative coordinates and bounded simplification. Root observes RED before implementation.
- [ ] Implement the approved records and pure geometry. Preserve each clipped fragment rather than connecting gaps. Reject non-finite coordinates. Contracts:

```java
MapRouteGeometry.clip(List<MapRoutePoint> path, MapRouteViewport view);
MapRouteGeometry.simplify(List<MapRoutePoint> path, double toleranceBlocks);
```

- [ ] Run targeted GREEN; self-review numeric bounds, disconnected paths and input immutability.

## 2. Server catalog, lifecycle and wire — catalog worker

Files: create `map/route/MapRouteCatalogService.java`, station SavedData/provider helpers and two `network/packet/maproute/*Packet.java` classes. Modify only terminal lifecycle/catalog methods needed for snapshots. Root owns ModNetwork/ServerEvents registrations.

- [ ] Write meaningful tests for current-dimension public records, no private mission/inventory reads, unloaded station persistence versus real removal, road deduplication, and capped/malformed/stale reply handling; root observes RED.
- [ ] Capture existing authoritative roads and published port/station routes. Persist station route geometry from normal lifecycle calls; no forced chunks, remote BE reads or all-pairs routing. Build `MapRouteSnapshot` with a visible truncated flag and 128-route/4096-point budget.
- [ ] Implement bounded asynchronous viewport jobs, immutable captured inputs and owner generation checks. Request codec carries session/world/dimension/request id and the shared viewport. Reply carries those identities and the snapshot. Expose start/tick/stop/forget lifecycle for root registration.
- [ ] Run targeted GREEN; review packet allocation/size limits, stale publication and optional-mod independence.

## 3. Common client layer, controls and RoadPlanner — client worker

Files: create `client/map/route/SharedMapRouteLayer.java`, rendering/hit-test/settings helpers and LDLib controls; modify `ClaimWorldMapView.java` and `RoadPlannerScreen.java`. Root owns Town/Nation screens.

- [ ] Add regressions for fixed screen-pixel hit distance, category toggles, selected identity, dimension/stale clearing and transform agreement; root observes RED.
- [ ] Implement throttled viewport requests, session/generation rejection, outlined category colors, visible simplification/truncation state and selected details. Expose a compact settings button and an explicit inspection-mode toggle.
- [ ] Wire RoadPlanner and expose ClaimWorldMapView route render/click/control accessors for root. Draft construction/merge overlays retain their behavior while inspection is off.
- [ ] Run targeted GREEN; review hover/click precedence, controls at small scale and clipping inside nested UI.

## 4. Screen and network integration — root

Files: `TownHomeScreen.java`, `NationHomeScreen.java`, `ModNetwork.java`, `ServerEvents.java`, logout hooks and language resources/documentation.

- [ ] Append two payloads at the end of ModNetwork, update protocol/count and lifecycle. Wire route layer after territory fill and before markers; route inspection consumes left clicks only in explicit inspection mode.
- [ ] Add translated settings/detail text and preserve existing layout scrolling. Verify no optional marketweb/BlueMap linkage is introduced.
- [ ] Review approved spec and code with independent workers; fix material findings and rerun affected tests.

## 5. Final validation and delivery — root

- [ ] Run `gradlew.bat -I build/transport-verification/bootstrap/local-plugins.init.gradle test --offline -x downloadAssets -x createMinecraftArtifacts --console=plain` and frontend check. Require zero failures.
- [ ] Extend the ignored native harness in `build/route-overlay-verification` to publish registered fixture roads/ports/stations in a copied world. Inspect all three maps, GUI2/3/4, category filtering, selection, pan and zoom. Record which metadata is synthetic and keep the actual terrain/network path real.
- [ ] Build production with the same init/skip flags and `releaseMod -PfixIndex=11`; verify no smoke classes, one metadata record per JAR and main/addon separation. Start/stop the exact production JARs in isolated NeoForge21.1.265.
- [ ] Package main/addon/dependencies with installation notes and hashes; verify ZIP CRC, original-world hashes and unrelated edits. Commit, fast-forward main, push and compare remote/default SHA; preserve ignored validation evidence.
