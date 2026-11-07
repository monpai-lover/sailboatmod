# Fixed-wing blueprint live test plan

> **For agentic workers:** Use superpowers:executing-plans for sequential live actions and independent agents for source/API/evidence review. Track the checklist below; never count a prepared fixture or accepted task as successful flight.

**Goal:** Download a simple real Create Aeronautics fixed-wing aircraft, verify its actual physical flight, then test the existing fix_18 automatic cargo workflow on that aircraft.

**Architecture:** Preserve the raw public blueprint and author attribution. Import a local test copy using normal schematic placement, then add native Flight Control hardware without substituting direct-force or creative vector lift for the aircraft's own propeller and aerodynamic surfaces. Use only the existing isolated server and cloned client; preserve the old rotorcraft, five settled transactions and world backup.

**Tech stack:** Minecraft 1.21.1, NeoForge 21.1.265, Create 6.0.10, Aeronautics 1.3.2, Sable 2.0.6, Flight Control 0.8.3, SailboatMod fix_18, user-authorized native macOS game input.

## Evidence and files

- Worktree: `TEST_WORKTREE`.
- Test evidence directory: `build/live-client-test/fixed-wing/polartt-01/`.
- Raw blueprint and parsed structure: `source-simple_plane.nbt`, `source-simple_plane.json`.
- Attribution/checksum: `source.json`; author Polartt, https://createmod.com/schematics/simple-plane-by-polartt .
- Raw SHA256: `07391cdd59e9961094c5a96ed247dfd109eafdb1bd87ac1df800f13936d3acfe`.
- Live console/status: `build/optional-addons-2026-10-30/live-client-test/managed-console.fifo`, `fix18-01-status.json`.
- Keep production source frozen unless an actual reproducible integration defect requires a separate repair and failing regression.

## Sequential acceptance

- [x] Find a small actual fixed-wing schematic whose required mods are installed. Selected Polartt, 16×6×15, 139 blocks, normal public browser download.
- [x] Decode NBT before placement. Confirm palette contains only existing Create/Simulated/Aeronautics/vanilla aircraft blocks; no command blocks. Preserve source unchanged.
- [x] Save the current isolated world and create a new pre-fixed-wing backup with saving suspended only for the bounded copy, then restore normal saving. See `fixed-wing/polartt-01/backup.json`; saving was restored.
- [x] Inspect the actual Create import path. Import in a separate clear test area with a supported runway; do not relocate the previous rotorcraft or modify old transactions. Record placement origin and all added blocks. Normal Create creative printer placed the unchanged 139-block source at (600,82,505); assembled body `airframe-a`.
- [ ] Verify assembled propeller and four mechanical-bearing flap surfaces, actual sail count, glue and body bounds. Observe normal fuel/RPM and correct forward thrust direction; resolve import-related assembly/configuration using ordinary controls.
- [ ] Verify a controlled physical takeoff/flight of the blueprint, recording height, speed, physical body, actual propeller/surface operation and screenshot. A schematic preview does not pass.
- [ ] Add native FCC FIXED_WING, a single linear/angular coupler pair, forward indicator, NAV/card, registrar and chest on the same body. Set direct-force/direct-torque false. Link using normal native controls or reviewed corresponding public linkage API. Record every adjustment from the original blueprint.
- [ ] Read actual fixed-wing lift/VR, stopping distance and turn reference estimates before selecting route geometry. Build/register real supported runways at both airports with adequate approach space and town ownership; do not reuse helicopter-only helipads.
- [ ] Submit normal navigation, test actual takeoff, cruise, approach, touchdown and stop. Arrival requires the whole aircraft in the validated runway, bottom within0.5m and speed<=0.25m/s, sustained20ticks. Do not force an aircraft position/arrival to obtain a pass.
- [ ] Publish a finite new listing and use the normal buyer purchase entry point, with clearly identified NeoForge FakePlayer buyer. Observe actual automatic dispatch, cargo transfer, settlement and empty return. Sample money/goods custody and preserve all five old settled records. No direct order/NBT edits or forced settlement.
- [ ] If baseline succeeds, repeat one controlled lease-loss recovery case on this airframe, separately labeled; do not count a missed injection as an injected pass.
- [ ] Freeze raw evidence, checksums, screenshots and a concise result with separate statuses for original blueprint physical flight, modified native autopilot and full business workflow. State any concrete failure honestly; manual recovery remains distinct from an autonomous pass.
- [ ] Park or normally stop the test aircraft, save the world, remove only newly owned temporary keep-awake/forceload resources that are no longer needed, and preserve reviewable artifacts. No commit/push or production deployment is requested.

## Runtime limits

Current checkpoint, 2026-10-31 12:19: Blueprint propeller responded to normal coal refueling and produced about 2.57m/s westward ground movement. Four control-surface bearings are not yet verified. This does not pass controlled flight or automatic taxi. Earlier wording of throttle0 as idle was incorrect: native analog transmission disconnects at15. Normal guarded setSignal(15) was executed once, and `monitor/receipts/after-safe-stop15-01.json` confirms throttle15, propeller0RPM and zero linear velocity. No aircraft teleport/direct force/business edits were used. User requested airport UI with runway, taxi and parking configuration; the proposed extension is in `../specs/2026-10-31-airport-ui-ground-operations-design.md`.

All mutations require the fixed isolated server identity, world and fix_18 JAR checksum to match. Do not bypass ownership, disabled control or stale task checks. No raw applied force, aircraft teleport, business NBT writes, automatic acceptance of new tasks, or synthetic delivery is permitted as evidence of a physical/logistics pass. If aircraft crashes, record it and restore only the separately identified test copy after evidence capture.
