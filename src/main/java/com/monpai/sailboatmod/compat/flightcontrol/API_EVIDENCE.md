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
