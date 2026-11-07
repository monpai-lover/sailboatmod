# Flight Control adapter evidence

Inspected release: Create Flight Control NeoForge 0.8.3, Modrinth version `FSr0O2k8`,
SHA-1 `6efa89db07e45fe8687e8d1abe8ca284d3fb1630`.
Evidence was obtained from public `javap` signatures and selected bytecode paths, not copied source.
No FC code or resources are bundled. The bridge contains no optional class imports.

The real `NavigationTerminalBlockEntity` owns its own lease and planner. Sailboat does not create
lease identities, request direct force, impersonate the terminal, or bypass reciprocal links.
No FC mixin or upstream patch is required for these operations:

- `NavigationProfileDraft.toWaypointRoute(ResourceLocation)` builds a validated 3-D route.
- `NavigationProfileItem.createWaypointRoute`, `updateWaypointRoute`, `writeDefinition` and
  `readDefinition` use a real navigation-card `ItemStack`. Submission prepares a detached copy,
  verifies its decoded route and fingerprint, then writes the installed card.
- `NavigationProfileHolderBlockEntity.notifyExternalProfileSlotMutation()` invalidates the slot.
  The next normal terminal tick observes the new definition. Callers must wait for a valid slot
  and obtain a fresh expected-state token before starting. `profile_pending` is not flight success.
  The successful result separately returns `submittedProfileFingerprint` from the validated definition:
  the invalidated task snapshot can have an empty fingerprint until the next tick and must not be used
  to bind the newly submitted route. Callers compare the observed card against the returned fingerprint.
- `requestAction(action, expectedTaskRevision, expectedSlotGeneration, fingerprint)` controls
  START/PAUSE/RESUME/CANCEL; the server-thread and stale-view checks remain inside FC.

Important FC 0.8.3 behavior:

- `effectiveEnabled = defaultEnabled XOR masterSwitchSignalHigh`. An active external master switch
  is reported as a conflict; the adapter never rewrites redstone identities or signal sources.
- Enabled terminals automatically start READY tasks and resume PAUSED/PREEMPTED tasks. Pause/cancel
  therefore disables automatic enablement first. Failed start/resume rolls back newly enabled state.
- PAUSE/CANCEL release the terminal's lease. This is not proof of zero physical actuator output:
  native/manual controllers can still act, and `isContributing()` always returns false for the terminal.
  A virtual-storage backend must separately provide safe physical custody. Never pause a fixed-wing
  craft merely because players are far away and leave it hanging in the physical world.
- START acceptance means STARTING. BLOCKED/PREEMPTED/WAITING_REFERENCE are not completion.
  HOVER/HOLD_LAST_TARGET retain control and normally enter HOLDING. RELEASE_CONTROL and
  APPROACH_LANDING release control on COMPLETED. Cargo arrival still requires logistics validation.
- `captureObservations().values()` exposes `phase`, `enabled`, `has_profile`, `current_path`,
  `path_count`. `current_path` is one-based and is absent unless the task is RUNNING with a valid lease.
- Resume can create a new task UUID. Preserve Sailboat's mission UUID separately.

Profiles and hardware:

- FC has GENERAL and FIXED_WING airframe modes. AIRSHIP and ROTORCRAFT both require GENERAL;
  their physical differences come from installed actuators and configured controllers, not invented
  FC enum modes. The bridge does not silently change FCC mode, working state, hardware, or gains.
- Fixed-wing paths use FOLLOW_FLIGHT_TERMINAL. Ordinary preceding legs may use SmoothDirect.
  APPROACH_LANDING requires the last TWO paths to be direct: touchdown, then runway stopping point.
  They are not an approach point and touchdown point. The caller must reserve and validate the runway.
- General profiles use explicit level pitch/roll and velocity-direction yaw with FC's configured
  linear/angular control network. Missing or ambiguous control ports remain FC diagnostics.
- Existing FCC configuration supplies takeoff/approach heights, stabilization distance, glide and
  landing pitch. These public setters exist but are not changed implicitly by route submission.

Position and optional dependencies:

- FC vehicle position comes from `AttitudeTargetUtil.getLogicalPositionStrict` -> current
  `FlightBodySnapshot.positionWorld`, or `subLevel.logicalPose().position()`. It is the ship logical
  pose origin, not the registrar or terminal block center. The existing native vehicle bridge uses
  the transformed registrar center and is not interchangeable for landing-height offsets.
- `inspectVehicle` exposes this FC position, world speed, mode, sample time and a conservative 500 ms
  freshness check. A stale sample does not establish landing or custody.
- Structure validation uses public `Sable.HELPER.getContaining`, actual ServerSubLevel UUIDs,
  removed state and owning level. It does not depend on Create: Simulated throttle/steering classes.

Validation: 14 pure protocol/model regression tests passed in an isolated JUnit harness after
observed failing assertions. Temporary Minecraft type stubs were used only to compile the pure
test harness on available Java 17; they are not in the repository and do not prove Minecraft or
FC runtime compatibility. Full NeoForge Java 21 compile/test and live three-airframe flights remain
separate required validation. No live FC flight or virtual custody was tested by this adapter work.

## 2026-10-31: verified internal task successors

The isolated fix17 server reproduced a separate integration failure using an explicitly authorized
public `FCC.releaseNavigationLease(actualIdentity, actualToken)` fault injection: native route renewal
published `PREEMPTED / LEASE_LOST / route_lease_renew:STALE_TOKEN`, then the next native automatic
reconcile resumed under a new task UUID. The five-tick Sailboat check saw the new UUID and entered
MANUAL. This controlled release does not prove the trigger of the earlier natural failure. A previous
1009ms main-thread pause did not reproduce that trigger; native FC has simulation-pause compensation.

The optional observer is restricted to version 0.8.3, the inspected original terminal/FCC class hashes,
and the unique internal resume callsite. It never changes task actions, arguments, UUIDs or leases.
Only the registered controller can explicitly establish its runtime claim after its accepted action.
An observed internal successor starts pending and cannot establish cargo custody until the new task
is RUNNING/HOLDING and its actual route domains, active identity, token generation and live broker frame
agree. Foreign leases, external PAUSE, OFF/ON authority/session changes, changed cards/links/topology
and unknown UUID transitions remain rejected. These proofs are not persisted across world restarts.

Unlike the original control bridge, this version-pinned evidence path uses exact private field handles
for NAV runtime identity/token/route/computer and FCC broker/session fields, plus read-only public
snapshot methods. It never enumerates optional methods or fields and never calls broker expiry/renewal
to inspect state. Business code does not load classes in the defined mixin package. Missing FC or an
unknown version/shape disables this observer while retaining the strict task-identity check.

Pure registry/lease tests and parsed metadata/compiled-bytecode checks are separate from real Mixin
application, dedicated/integrated startup and physical flights; those runtime checks are still required.

## 2026-10-31: ground handoff and available propulsion observations

`inspectControlOwnership` reads the existing version-pinned terminal/FCC identity evidence and
its broker effective frame without expiring, renewing or acquiring a lease. It separately checks
unexpired command deadlines with `System.nanoTime()`: a terminal-owned flag alone cannot prove a
live FC handoff, and a released flag alone cannot prove all broker commands have disappeared.
The returned body, task, profile fingerprint, configuration revision and slot generation must agree
across the observation. Ground control waits for this actual release proof before writing outputs.
Missing/changed optional internals return unavailable, never a fabricated empty broker.

`inspectPower` reads the FCC's public `linkedThrusters` entries (bounded at 128), resolves each loaded
same-body block and invokes only exact `IFCCThruster` interface handles. Reciprocal FCC links and
valid native link configurations are required. `getFCCMaxThrust` must return finite positive capacity
for at least one linked thruster. This proves currently available native propulsion, not adequate
acceleration, fuel endurance, lift, forward thrust direction or successful flight.

For Aeronautics propellers linked to FC 0.8.3, native FC controls bearing rotation while the upstream
kinetic shaft still determines maximum available thrust. A disconnected upstream transmission cannot
be recovered by requesting FC START. The physical test uses a separate real wheel drivetrain and
measured endpoint-only ground throttle signals; it leaves the flight propulsion shaft powered for
FC ownership. Linking/unlinking a propeller or writing artificial forces is not part of handoff.

Fresh Java21 focused tests cover ownership/release predicates and finite/bounded propulsion snapshots.
They do not replace actual native release, wheel traction, takeoff or destination landing observations.
