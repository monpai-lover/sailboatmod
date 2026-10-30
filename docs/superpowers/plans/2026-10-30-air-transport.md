# Air transport implementation plan

> Execute in the approved NeoForge 1.21.1 feature worktree with independent modules and integration checks.

**Goal:** Carry registered Aeronautics aircraft and real cargo between player-built airports, expose flights and facilities in MarketWeb, and support safe physical/virtual handoff where a storage backend can prove exclusive custody.

**Architecture:** The existing registered-controller UUID and cargo receipts remain authoritative. AIR is a third logistics kind. A flight controller delegates flight to a real Create Flight Control navigation terminal on the same structure; it never synthesizes thrust or bypasses terminal identity checks. Airport facilities are persisted registrations of existing blocks. A separate mission journal records phases, representation, generation and trace ownership; virtual delivery cannot settle an order before physical docking. Offscreen handoff must fail closed without an installed, validated storage/recovery backend.

**Tech stack:** Java 21, NeoForge 21.1, NBT SavedData, reflection-isolated optional integrations, JUnit 5, existing vanilla JS MarketWeb and Node checks.

## Approved behavior and contracts

- AircraftClass: AIRSHIP / ROTORCRAFT / FIXED_WING. Control and landing requirements remain distinct.
- AirportFacility: id, RUNWAY/HELIPAD/MOORING, start/end landing-plane BlockPos, width, clearanceHeight. Unknown chunks do not pass validation. Reserve by stable carrier UUID with expiry.
- FlightControlBridge.inspect / submitRoute / requestAction use optimistic revision checks. Submission edits a real installed profile, waits for validation, and starts the real terminal. Redstone effective-enable overrides must be detected before claiming pause/cancel.
- ShippingTraceService.recordFlightTelemetry(level, controllerId, shippingOrderId, worldY, flightState, executionMode) preserves order status separately from flight phase.
- FlightMissionSavedData.hasActiveMissionForTrace(traceId) protects only a real active journal entry from orphan cleanup.

## Tasks

- [x] Establish baseline Java 21 build and test results. Resolve missing local dependencies, preserve unrelated checkout.
- [x] Implement airport model, validation budget, reservations, SavedData directory and AirportBlockEntity. Test unloaded terrain, obstructions, invalid dimensions, three aircraft classes, expiry and 3D parking.
- [x] Implement optional Flight Control adapter against reviewed 0.8.3 public signatures. Test stale revisions, enabled overrides, invalid routes and absent integration.
- [x] Add AIR to LogisticsCarrier, AIRPORT terminal/network kinds, transport-mode mapping and real block/item registration/resources.
- [x] Integrate controller aircraft configuration, terminal binding, airport routes, preflight checks, physical flight telemetry and dock-only settlement. Preserve dispatch receipt rollback and manifest identity.
- [x] Persist and test the mission journal and generation-checked transitions, including rejection of virtual delivery.
- [ ] Connect a durable exclusive Sable custody/restore and fuel backend before enabling remote virtual travel. Current runtime stays physical; missing contract and crash matrix are documented in `compat/automatedlogistics/API_EVIDENCE.md`.
- [x] Extend MarketBlockEntity auto-dispatch, previews, reachability, pickup and return paths for airports.
- [x] Add player-facing airport registration/validation commands and controller flight settings with owner/management permissions.
- [x] Extend trace NBT/JSON, three aircraft renderers, airport/runway map API/layer and mock fixtures. Verify old records and permission boundaries.
- [x] Run targeted JUnit/Node tests, full build/test where environment permits, inspect built resources, and capture current browser renders from the mock server. Combined live-market build: 2057 JUnit tests (1 skipped), 109 Node tests, both fix_14 JARs verified; see `docs/handoff/air-transport/verification.md`.
- [x] Independent review of integration and lifecycle invariants; fix findings and document exact runtime dependencies, setup and remaining live-game verification.
- [ ] Live game acceptance: three airframes, actual cargo, airport procedures and reload ordering. Automatic in-air restart continuation is not implemented.

## Acceptance boundaries

An unavailable plugin, stale terminal profile, unknown runway terrain or occupied destination must produce an actionable blocked reason. No fake arrival, zero-height fallback, duplicated cargo, direct velocity teleport, or blanket orphan exemption is accepted. Source checks, tests, build, browser preview and a live modded Minecraft flight are reported separately.
