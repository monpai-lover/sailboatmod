# Airport UI and fixed-wing ground operations implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development for independent owned components and two-stage review; use TDD for behavioral changes. Root coordinates builds and all live actions. Do not commit or deploy production instances.

**Goal:** Configure player-built runways, directed taxi paths and stands through the airport block, then physically fly a modified Polartt aircraft between two airports and deliver only at its assigned stand.

**Architecture:** Preserve existing runway/helipad/mooring records. Add separate versioned airport ground layouts and an LDLib2 management screen. A bounded ground mission controller uses actual native throttle/steering/brake outputs; explicit ownership handshakes separate it from FC flight. Cargo departure and delivery bind the selected stand and layout revision. Keep legacy rotorcraft behavior unchanged.

**Tech stack:** Java21, NeoForge21.1.265, MC1.21.1, LDLib2 existing Ui kit, JUnit5, optional Sable/Aeronautics/Offroad/Simulated reflection, native FC0.8.3.

## Workspace and verification contract

Use `TEST_WORKTREE` on `codex/air-transport`. Preserve current fix18 and pre-existing ClaimPreview changes. Do not create a new checkout that loses those dependencies. Root runs coordinated Gradle builds with the existing Java21 toolchain; workers may run isolated pure harnesses and must record RED/GREEN. Do not run live attach, world mutation or GUI in workers.

The source blueprint remains unchanged. Modify only its isolated physical test copy through native item placement/controls. Add actual wheels, steering and brake hardware as required; original grindstone landing gear does not prove those capabilities. Record each difference. Full flight and cargo acceptance require actual observed motion, custody and payment.

## Task A: Airport domain and management service

Owner: domain worker. Files: new focused model/service files under `transport/air/airport/`, modify `block/entity/AirportBlockEntity.java`, relevant `src/test/.../airport/` tests. Do not modify UI, networking, registrar or language files.

- [ ] Freeze immutable `AirportGroundLayout`, `AirportParkingStand` and directed `AirportTaxiRoute` API with revision, runway/stand IDs, bounded world coordinates, width/clearance and ordered points; announce signatures before dependent work.
- [ ] Write RED cases for invalid geometry, disconnected route endpoints, missing runway/stand, foreign town cells, changed revisions, occupied edits and persistence round-trip. Include:

```java
assertFalse(layout.departureReady("standA", "missingRunway"));
assertFalse(stand.contains(wrongHeadingHull, wrongHeading, .25));
assertEquals(layout, AirportGroundLayout.load(layout.save()));
```

- [ ] Implement bounded validation only over loaded cells; return pending/unknown rather than CLEAR when evidence is unavailable. Enforce existing town/owner/range policy in one management service shared by UI/commands.
- [ ] AirportBE persists layout and exposes safe stand/route lookup plus versioned edits, reservations and whole-body parked checks. Reject edits of reserved/occupied facilities; older airports load an empty ground layout.
- [ ] Run focused JUnit/model tests GREEN and provide root exact API/test evidence for independent spec and quality review.

## Task B: Airport block UI and world selection

Owner: UI worker. Files: new `client/screen/AirportScreen.java`, hooks, DTO/menu as needed, airport packets, `AirportBlock.java`, append `ModNetwork.java` packet registration, both language JSON files and UI/protocol tests. Do not modify AirportBE or registrar.

- [ ] Use existing ThemedScreen/Ui/TabBar patterns; airport overview plus Runways/Taxi/Stand tabs. Show current validation/occupancy, maintain drafts on refresh and show server-side errors.
- [ ] Add packet tests RED: bounded strings/counts, missing sender, unloaded airport, stale revision and unauthorized actions do not mutate. Existing packet wire layout stays intact; new IDs append and protocol increments once.
- [ ] Implement runway start/end/direction/width/clearance editing, ordered OUTBOUND/INBOUND point editing and stand location/yaw/size. Use world selection + coordinate correction, preview actual geometry without building terrain.
- [ ] Server action uses domain service and current management rights, real distance and revision. Client packet delivery uses client-only hooks without dedicated-server classloading.
- [ ] Verify tests GREEN; root later opens UI in actual Minecraft, saves all three data types, observes markers and verifies restart persistence.

## Task C: Pure ground mission and native actuator capability

Owner: control worker. Files: new focused `transport/air/ground/` classes and tests; optional bridge extensions under `compat/aeronautics/` and `PhysicalVehicleView` observation extension if needed. Do not modify AirportBE, network, language or registrar until root agrees exact hooks.

- [ ] Inspect installed Offroad/Simulated API; identify real steering/brake and grounded/support evidence. Reject missing capability rather than infer it from low speed. Keep optional classes behind reflection.
- [ ] RED state machine cases: ground departure → hold line → aligned/permit → FC handshake; touchdown/rollout is insufficient until FC lease is released; taxi-in stops at exact stand; manual input revokes automation; stale geometry/observation cannot advance; restart does not replay or deliver.
- [ ] Implement bounded pure mission state and command generation with native physical outputs only. Use measured stopping/turning constraints and monotonic progress; passed intermediate nodes do not require full stops, hold/stand do.
- [ ] Expose a small immutable observation/action contract to root. Native output must correctly calibrate throttle inversion and respect manual override. Ground hold/brake never applies to an airborne FC mission.
- [ ] Run focused tests GREEN and supply independent review artifacts. Dynamic stopping distance remains a separate live acceptance.

## Task D: Registrar, controller UI and cargo integration

Owner: root, after A/C API freeze. Files: `RegisteredVehicleControllerBlockEntity.java`, `ControllerAction.java`, `RegisteredVehicleControllerScreen.java`, focused integration tests. Coordinate language additions through UI owner.

- [ ] RED regression: full-layout fixed wing can load/start at its assigned stand but cannot start FC before runway position/alignment/permit; stopping on runway cannot settle a stand-bound cargo mission.
- [ ] Add separate ground controls/capability settings to AIR power UI, persistence for phase/point/layout/runway/stand and identity tokens. Preserve old fix18 lease-successor logic and all rotorcraft paths.
- [ ] Implement TaxiOut→Hold→LineUp→FCStart; FC completion/physical rollout→explicit lease release→TaxiIn→exact stand/settled cargo. Bind arrival to assigned facility, geometry revision and mission.
- [ ] Restart/manual/change/dependency failures retain cargo and enter explicit recoverable status. No unknown task or missing physical observation is accepted.
- [ ] Focused tests GREEN, then independent spec review and quality review before live deploy.

## Task E: Build, live UI and physical acceptance

Root controls sequentially; workers review evidence only.

- [ ] Run coordinated `./gradlew test` using Java21 and inspect totals; then `./gradlew releaseMod -PfixIndex=19` with complete bundled assets. Inspect artifact content and SHA before replacement.
- [ ] Save/backup isolated server, stop cloned client/server normally, replace only test-instance artifacts, restart legacy-coordinate world with DH renderer disabled. Verify actual loaded JAR and window/process identity afresh.
- [ ] Use airport UI to configure existing C runway and new D runway, explicit stand and directed taxi routes. Verify readback, validation errors, world preview and persistence after a controlled restart.
- [ ] Prepare modified Polartt via native hardware placement and links; verify actual mass, control response, contact/support, fuel, thrust and flap lift. Record actual throttle mapping, dynamic ground stopping and turning tests before autonomous taxi.
- [ ] Physically observe taxi-out, runway hold/alignment, takeoff, cruise, destination approach/touchdown, rollout, taxi-in and final stand stop. Capture phase, body pose/velocity/actuator and control owner at each transition. No vehicle teleport/force/forced arrival.
- [ ] Publish a finite normal listing and use authorized FakePlayer buyer at D with explicit destination. Verify cargo4/wallet8 movement, real settlement and empty return; old five orders/wallet baseline remain accounted for.
- [ ] Separate visual/UI, model tests, physical flight and business statuses in final report. Save and stop safely; release only owned temporary resources. Do not claim any unpassed step complete.

## Review and completion

- [ ] Spec compliance review then code quality review for each owned component; fix critical/important findings and re-review.
- [ ] Final full tests after integration, no repeated unrelated suites without new evidence need. Complete real runtime acceptance before declaring the user request done.
- [ ] Update this checklist and design status with actual evidence; preserve downloaded attribution and unchanged raw source.
