# Fixed-wing takeoff assist — design proposal

Status: user approved option A on 2026-11-03: configurable direct physical acceleration during the automatic takeoff roll, ending after actual departure. Implementation and verification are in progress; the native-only baseline remains separate.

## Verified baseline

The unchanged fix38 original 95-mass aircraft completed a real 4003.11m natural grass-surface run at top Y=-60. Actual native task native-flight-e ran TAKEOFF waypoint0, with original profile canonical hash642448556d1557b3a5e9fbad28fe431680b01d649f8d80e38259c4f76319375d. Speed during the last2km was13.4332–13.4367m/s, below the native VR23.3011m/s. Midpoint and endpoint independent native physical observations had all4 positive wheel contacts, mass95, with no meaningful climb in the trajectory. Native propeller was enabled at throttle1 and actual shaft−256 while unfrozen. Length alone did not solve takeoff in this configuration.

Full evidence is OLD/build/live-client-test/fix38-deployment/surface-run-02/actual-natural-ground-roll-4km-result-01.json and its CSV/raw JSONL. Existing4-wheat manifest remained constant. No boost, body pose/velocity edit, invented physics step, cargo transfer or payment was used. A normal whole-game freeze ended the case at4km, then a normal stop saved the actual surface position. All2194 temporary vanilla forceload chunks were removed and the original62 exact set retained. Frozen Create getSpeed returns0 by design: actual theoretical engine±32 and propeller−256 were intact, so the transient zero display was not a network failure.

## User choice

A. Recommended game assistance: an explicit, configurable physical forward boost during automatic takeoff roll. It creates extra propulsion and must be labelled as such. A successful assisted takeoff is not evidence that the aircraft's original engine can take off unaided.

B. Real propulsion only: retain native forces and add/upgrade actual engines or propellers; automation only commands the resulting real capability.

## Proposed A behavior

- Configurable enable/disable and strength, owned/server-authoritative; no arbitrary body UUID, blueprint, mass or coordinates in production.
- Apply real horizontal forward impulse only to the exact currently registered body and current native automatic TAKEOFF task/profile with proved current authority. Native aerodynamic controls remain responsible for actual rotation, lift and flight.
- Every actual Sable pre-physics substep uses current mass and real dt, once per increasing native physics frame. Project world forward onto XZ, normalize, transform into body-local coordinates, and apply zero angular impulse. Never change pose, linear velocity, task progress, native solver output or arrival status.
- Bound acceleration, total duration/impulse and speed; require valid native VR/profile, live propulsion, low brake, fresh genuine configured wheel contacts. Initial arming requires complete noncollinear configured-wheel support. An armed episode may continue while at least one fresh configured wheel remains genuinely supported during rotation; stop applying on unknown or zero support; confirm actual departure before permanently ending the episode, so later landing does not rearm it.
- Disable on manual takeover, pause/cancel, task/profile/body change, lost/frozen/stale native frame, exhausted propulsion or foreign authority. A resumed/reloaded saved episode grants no physical execution authority until revalidated.
- Integrate with the already identified exact-own grounded abort sequence. Never use extra boost to bypass route clearance, ownership, profile validation, stopping-margin or delivery gates.
- UI and telemetry explicitly distinguish native-only versus assisted takeoff.

Configuration defaults to disabled with 12m/s² strength, bounded to 0–20m/s². The owner can enable it in the registered fixed-wing controller. It supports both the controller's exact logistics flight and the registered aircraft's independently started native automatic navigation. A paused cargo mission alone does not grant or deny a separate native task; current native authority is always checked. Controller Pause/Stop explicitly suppress the current assist episode. A new native task can arm after a normal READY transition; an owner enabling the switch can explicitly arm a currently grounded eligible task. Loading saved settings never grants a running task assist authority. Each task has a maximum 20-second physical-time episode and may accelerate only up to its current native automatic takeoff speed command. VR remains a required valid native rotation reference; it is not an upper limit on liftoff speed. Repeated fix43 surface runs plateaued around 22.6m/s at the former 1.05*VR ceiling while the native controller continued to request more speed. This lower artificial ceiling is removed so different airframes follow their own native takeoff demand. The helper must never run ahead of the native speed ramp or rewrite that ramp. Valid partial support, including the final genuine wheel contact, permits an already armed episode to continue; unknown or zero contacts pause force; confirmed airborne ends it permanently for that task.

## Confirmed native implementation seam

Installed Sable2.0.6 exposes SablePrePhysicsTickEvent.prePhysicsTick(SubLevelPhysicsSystem,double dt), before applyQueuedForces and pipeline.physicsTick. RigidBodyHandle.of(ServerSubLevel) exposes applyLinearImpulse(Vector3dc) and applyLinearAndAngularImpulse(Vector3dc,Vector3dc[,boolean]). Existing Offroad code multiplies forces by real dt before passing local-coordinate impulse into these same APIs, establishing J_local=m*a*dt rather than interpreting the JNI method name as an unscaled force. This remains primary-source investigation, not an executed assist prototype.

## Required verification

Pure policy/frame-budget tests for exact authority, one-use frame, finite mass/dt/force bounds, no assistance in taxi/landing/unknown contacts, shutdown on pause/power/task/profile changes and no reload replay. Independent review of native callback units and axis conversion. Actual native-only versus assisted comparison on the same valid aircraft, followed by real takeoff/landing/inbound-taxi/stand-delivery regression. Existing2979-test baseline has0failures and2existing skips; that baseline does not test a not-yet-implemented boost feature.

OLD=TEST_WORKTREE.

Native FC 0.8.3 `fixedWingBrakeDeployment` caches `FixedWingFlapPlanning.allowBrakes`: it enables aerodynamic braking as a solver option above 1.05 VR (with hysteresis), and does not report wheel brake deployment. Assistance observes the actual Offroad wheel redstone brake inputs and remains capped at the current native takeoff speed command. It must not alternate between assist and brake rejection solely because the native aerodynamic option becomes available.

During an already ground-armed takeoff episode, one fresh positive configured wheel contact can remain `ROLLING` while the other wheels unload. Full, noncollinear support by all configured wheels is still required to arm a new episode. Actual native contact loss, invalid/missing wheel evidence, body attitude, heading and slip remain independent guards; zero supported wheels receive no assist. The actual fix_45 trace lost 9 m/s during a 0.8-second single-contact interval because the former two-contact rule suspended thrust at rotation.

fix_46 also reproduced a short unsupported interval around native physics 377.45–377.65 seconds during a surface roll, followed by wheel recontact and loss of takeoff speed. Treating its first zero-contact frame as permanent departure made that bounce unrecoverable. An existing ground-armed episode now pauses force immediately on every zero-contact frame and ends permanently only after 0.5 seconds of continuously observed native airborne substeps. Missing observations and unknown contacts do not establish that interval. Recontact before confirmation may continue the original roll within its unchanged 20-second budget; it never creates a new episode or applies force in the air. A task first encountered airborne remains ineligible to arm, and genuine departure cannot rearm after landing.
