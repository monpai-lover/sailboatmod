# Fixed-wing parking brake regression plan

> **For agentic workers:** Use the existing approved airport ground-operations design and test-driven development. Root alone performs live deployment and inputs.

**Goal:** A registered fixed-wing aircraft remains braked after reaching its stand and during grounded idle/preflight; normal taxi, takeoff, and manual takeover explicitly hand over that brake.

**Evidence:** fix30 actual original body `airframe-a`, native NAV OFF and no native leases, FCC WORK ON, mass95, prop8/256. Native FCC produced about10–12N EAST and the aircraft crept at0.137m/s. Root neutralized the physical prop input and verified actual zero/stopped. `arrive()` and `holdNative()` currently clear aircraft BRAKE; inactive tick never reasserts it. This falls within the user's approved requirement for actual stand parking and delivery.

**Architecture:** Use the registrar's existing BRAKE signal output and existing pure native four-wheel observation. Keep ground mission, airborne control, and manual takeover ownership separate. Apply no force, pose, mass, navigation-profile, wheel-brake-state, or timer writes; request no terrain from an idle observation. Preserve optional-mod reflection and protocol24/payload122.

Acquire a new parked hold only with normal registered/confirmed fixed-wing hardware, a bound loaded airport's actual whole-hull cargo stand, fresh same-body four-wheel support, stopped/heading checks, and proved released native control. Transfer an already verified COMPLETE ground parking hold into idle without clearing BRAKE. Use separate runtime parked ownership, because retaining `groundOutputsOwned` would break existing startup/stop identity assumptions. START keeps the hold until normal ground mission outputs take over; flight handoff releases it only under the existing native neutral/lease gates. Missing observations do not grant new authority. Reload rechecks actual ground/stand; removal and foreign/manual ownership yield only the registrar's own outputs.

Expose owner parking-release/re-engage through one appended `ControllerAction` and the existing controller UI, with explicit hold/released status in English/Chinese. Preserve all old action ordinals, existing permissions, and all arrival/delivery checks. Owner release must permit manual taxi without an automatic next-tick reapply; a normal later dispatch/re-engage can reclaim under fresh preconditions. This is part of the approved complete stand-parking workflow.

- [ ] Read current idle, arrive, pause/stop, load/remove, dispatch, ground-output, and native-flight handoff paths; write a concise lifecycle decision table before implementation.
- [ ] Add meaningful failing behavior regressions for grounded idle/arrival hold, start/preflight hold, taxi takeover, airborne exclusion, manual/foreign-owner release, and unloaded/missing observation handling. Run and archive the expected RED failures before production edits.
- [ ] Implement the smallest scoped parking-output ownership change. Do not change settlement/arrival acceptance, task progression, native flight algorithms, or the measured calibration values.
- [ ] Run the targeted tests, then the full Java21 `./gradlew releaseMod -PfixIndex=31`; archive full test XML and release output. Independently review the lifecycle changes and all packaging deltas.
- [ ] Root deploys only the isolated server/client after an offline world backup and verified JAR hashes. Preserve every loaded helper/source/receipt and all prior failures. Rehydrate a detached buyer only from the already paid wallet8/finite listing4, without replaying funding/listing.
- [ ] Root verifies actual95kg/8-sail native256/no-overstress, real brake15 and a stationary observation interval, fresh native fixed-wing performance, normal taxi back to the stand, real order loading/flight/landing/stand delivery/return, and cleanup/autonomous-loading evidence.

No actual flight, delivery, or fix31 runtime success is inferred from tests or this plan. Exact state and output ownership must be reviewed before release.
