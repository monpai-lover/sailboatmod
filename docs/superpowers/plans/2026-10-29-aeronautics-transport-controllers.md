# Aeronautics transport controllers implementation plan

> **For agentic workers:** Use subagent-driven development with systematic debugging, failing regressions before fixes, then spec and quality review. Only the root runs Gradle and native clients.

**Goal:** Implement the user-approved ship and land registration controllers, both mass 0, with native Aeronautics/Offroad power and existing authoritative logistics.

**Architecture:** A non-Entity carrier capability sits beneath existing TransportEntity; registered block entities implement that capability. Optional Sable reflection resolves actual structure membership and world transforms. Shared navigation computes native control signals; registered real inventories retain the cargo. Both blocks share an LDLib2 configuration screen and stable registration identity.

**Tech Stack:** Java 21, NeoForge 1.21.1, LDLib2, optional Aeronautics1.3.2/Simulated/Offroad/Sable2.0.6, JUnit5.

**Status (2026-10-30):** Implementation checkboxes below describe source work, not completed gameplay acceptance. Targeted RED/GREEN and root's full-source compilation are recorded under `build/transport-verification/`. The actual Sable first-pass probe passed assembly, identity, mass, real selected inventory, native actuator and ticket assertions (`native/physical-first-pass.json`, `movementTested=false`). Reopening that copied world then crashed in Sable `ServerLevelPlot.load` / `LayerLightSectionStorage` lighting; restart acceptance is **not passed**. A separate ignored vanilla-wood-only restart probe is prepared to isolate baseline Sable serialization from controller lifecycle. Exact packaged-JAR validation, powered water/land trips, complete delivery/payment/return, production packaging and push remain pending.

## Scope and ownership

- Root: new controller blocks/BE, native-output navigation, selected-cargo transactional adapter, registries/resources, LDLib2 UI and controller packets; central network version/registrations; integration, release and native validation.
- Logistics worker: `transport/LogisticsCarrier`, `TransportRef`, `CargoLoadReceipt`, registry and arrival orchestration; migrate Dock/PostStation/Market/trace and affected existing control packets, preserving entity seating and business authority.
- Compatibility worker: `transport/physical/PhysicalVehicleView` + bridge; `compat/aeronautics/` optional runtime implementation, exact verified Sable lookups/transforms and native control target validation.
- Shared-map worker is independent; it owns common map source separation and its request/patch packets. Root integrates central registrations.

## 1. Carrier and registry boundary

- [x] Add failing tests proving a non-Entity carrier can register/discover/resolve using a stable registration UUID, replacement invalidates runtime IDs, and unload is distinct from confirmed removal.
- [x] Implement `LogisticsCarrier` with the current business method names, preserving `TransportEntity`'s entity defaults and seats. `TransportRef(providerId,dimensionId,registrationUuid)` is durable. Physical `getTransportUuid()` returns the controller registration UUID; current Sable UUID is separate.
- [x] Add `CargoLoadReceipt.commit()/rollback()` and use dispatch receipts. Preserve the existing reservation, load/start/status transaction and original escrow settlement.
- [x] Merge registered real carriers into dock/station discovery by live world coordinates and WATER/LAND capability. Adapt trace lookup and existing controls without fabricating an entity.

## 2. Optional physical bridge

- [x] Add failing tests for no-mod startup, changed/reloaded structure identity, local/world transforms, cross-structure cargo/control rejection, native throttle/steering bounds and API failures.
- [x] Implement `PhysicalVehicleView(UUID physicalId,String dimensionId,Vec3 worldPosition,Vec3 worldForward,Vec3 linearVelocity,AABB worldBounds,double mass,boolean available)` and `PhysicalVehicleBridge.resolve(controller,facing)` with bounded cached reflection into verified installed APIs.
- [x] Expose same-structure, selected item handler and explicit native throttle/steering target helpers. Missing API or structure is a stopped/unavailable result. The bridge never creates an entity, teleports or supplies propulsion.

## 3. Controller model, cargo and navigation

- [x] Add failing pure navigation tests: heading wrap, ship/land steering limits, corner braking, reverse calibration, manual override, unloaded terrain wait, final arrival requires proximity and low speed.
- [x] Implement a persistent common block entity with registration UUID, owner, selected cargo/ports, route catalog, waypoint/task/manifest and state. Persist references and configuration, never a second copy of real cargo.
- [x] Implement precise simulated capacity and per-slot insertion receipt, with bounded rollback of only inserted cargo. Ownership and containing-structure checks apply before binding and before cargo transactions.
- [x] Feed existing route waypoints to a driver that emits forward/reverse/left/right/brake control signals. Native control bindings may call verified lever/wheel APIs, otherwise directional redstone ports drive player wiring. Require configured native controls before auto dispatch.
- [x] Re-resolve containing structure on assembly/reload/split. Unload pauses and keeps task; missing cargo or confirmed structure removal prevents false completion. Require positive hull total mass while controller mass remains exactly zero.
- [x] Use bounded rolling world/SubLevel loading and near-field real obstacle checks sized from vessel dimensions; pending terrain means braking/waiting. Preserve user-selected manual control and server authority.

## 4. Content and UI

- [x] Register `vessel_registration_controller` and `land_vehicle_registration_controller`, block items, creative entries, BE type, models/blockstates/recipes/loot/lang.
- [x] Add Sable definitions with priority2000, explicit block selectors and `"sable:mass":0.0`; do not change volume or substitute a tiny positive mass.
- [x] Add one responsive LDLib2 controller screen for identity/status, selected inventories, route/task and control bindings/calibration. Server validates owner and world-space proximity on every mutation, including moving structures.
- [x] Add bounded request/state packets at the end of ModNetwork; increment protocol to13 once for the combined release. Controllers and prediction maps must not expose private cargo/config to unrelated players.

## 5. Validation and integration

- [x] Record targeted failing regressions and targeted GREEN for carrier/dispatch boundaries, controller identity/removal, selected cargo, native driver/bridge, loading, complete terrain scanning and six distinct native control faces. Evidence includes `manual-build/final-controller-core-green.log` (37 tests) and `manual-build/controller-ports-green.log` (3 tests); these are not a substitute for final full-suite/package/native acceptance.
- [x] Run each new test in RED before implementation, then targeted GREEN. Root runs the complete existing/new JUnit and frontend suites plus `releaseMod`.
- [x] Spec review: both independent blocks, exact zero mass, real native power, both navigation kinds, real cargo/escrow, optional-mod boundary, safe resume, source authority. Quality review follows, fixing material findings.
- [ ] Verify the exact packaged JAR in an isolated production NeoForge test install with actual Aeronautics dependencies; check block placement/configuration, mass property, assembly and world coordinate resolution, native controls and one water/land logistics round trip.
- [ ] Resolve or isolate the copied-world Sable lighting restart failure; verify a normal stop/reopen and stable registration recovery separately from the successful first-session probe.
- [ ] Preserve user worlds; use copies. Build a new versioned package with dependencies, hashes and installation instructions. Integrate into `1.21.1-neoforge`, push and verify remote SHA; archive the managed worktree only after needed artifacts are preserved.

Final validation: releaseMod fix_10 passed1882 tests and frontend checks. The exact main/addon JARs started and stopped on isolated NeoForge21.1.265 without optional integrations. First-session native control/inventory/mass assertions passed, but a plain wooden Sable structure also reproduced the restart crash; official Sable issue1504 and absence of a later1.21.1 release are documented in FIX10. Powered travel and completed deliveries are not claimed. Final archive/commit/push evidence is recorded separately in artifacts/sailboatmod-fix_10-verification.json; this worktree remains retained with native evidence.
