# Fixed-wing takeoff roll guard — actual fix38 regression

Status: design and source-only test preparation; not implemented, tested or deployed.

## Evidence and result

The actual fix38 run preserved the original physical aircraft, mission air-mission-a, purchase air-market-order-a and four-wheat manifest. Ordinary owner Continue resumed LINE_UP. The aircraft waited through real bounded runway validation, released the ground native-quiet lease through the ordinary barrier, and received native START at 2026-11-03T06:35:37.355784Z. The exact native task was native-flight-a. Native control produced real takeoff roll, with BRAKE transmitter and all four receivers zero, parking released, and propeller output enabled at full throttle.

Takeoff failed. On the runway, actual speed stayed around 9.7–10 m/s, below the native reference VR 35.39070064253659 m/s. The aircraft ran beyond the runway and descended. The isolated server was normally stopped at 06:39:34 UTC; no aircraft pose, force, tick, cargo, payment or world-restore operation was used. The last recorded material anchor was [-2560.192806754928, -12.130827733362906, 510.55267156965624], with the original body and manifest still present in telemetry. This is an overrun/descent failure, not successful takeoff or landing.

Root did not send the ordinary RVC Pause click after source inspection showed it would be ineffective: pause() branches to groundFailure() for a ground mission, and FC_ACTIVE has no owned ground outputs. That path neither pauses nor cancels the native task. Root stopped the isolated server after the overrun; intervention was too late to retain the aircraft on the runway.

Primary local native evidence establishes that FixedWingPerformance READY/V1/VR verifies lift, lateral force and torque while projecting out forward net force. It is not proof of acceleration or runway attainability. Offroad zero-RPM wheels retain longitudinal damping at zero brake; this materially contributes to the observed limitation, but the exact full force budget has not yet been isolated.

## Required next change

1. Arm a new runtime takeoff-roll episode only after this controller receives an accepted new START. Bind actual body, exact task/profile, immutable mission/airport assignment, selected runway geometry, fresh native reference VR and the measured calibration. Never restore actuator execution authority from a saved episode.
2. Observe actual progress, speed and runway remaining distance using the native reference and whole-hull front edge. Use stopping distance from the validated measured deceleration, plus a bounded handoff latency margin. Detect insufficient acceleration using a sufficient real observation window and the actual VR/remaining runway; do not hardcode this aircraft's speed, mass, UUID or blueprint.
3. Ordinary owner Pause during a still-grounded own takeoff roll, or an automatic takeoff-margin failure, records an abort intent. Cancellation requires current exact own task/profile/lease, fresh matching body, and real positive full wheel contacts. Never cancel a foreign or unproven airborne task.
4. Handoff order is accepted ordinary native CANCEL, then independent raw released-authority proof, then a fresh DRIVE quiet generation with two real fresh physics ACKs, then calibrated neutral throttle and BRAKE15. An accepted action alone is not a release proof. Keep the original mission, reservation, cargo and order; never call arrival, payment or clear-mission stop as abort completion.
5. Partial/unknown contact data must suspend cancellation and ground writes while retaining the episode. It must not permanently disable overrun protection. Only fresh positive physical evidence of actual departure above the runway, supported by native airborne/contact observations, ends the episode permanently so a later landing cannot rearm it.
6. After a stopped abort, show a specific reason and require reviewed reconfiguration/revalidation. No automatic replay of START. Unknown authority or incompatible actuator topology remains fail-closed.

## Validation required

- RED/GREEN tests for owner Pause reaching the exact-own abort request, runway-margin and acceleration-window decisions, no false airborne latch on missing/partial observations, no landing rearm, rejected cancellation, foreign/replaced task/profile, no ground output until actual release plus two fresh ACKs, and preserved cargo/mission.
- Existing ground, native lease, runway-readiness, recovery and delivery tests remain passing.
- Actual runtime abort regression on a physically grounded inadequate aircraft before a new takeoff attempt.
- A physically capable player-built fixed-wing aircraft must then complete real takeoff, destination landing, inbound taxi, stand stabilization and ordinary order delivery. Mathematical READY, package tests or leaving the runway edge cannot substitute for that evidence.

## Immutable runtime evidence

- OLD/build/live-client-test/fix38-deployment/actual-fixedwing-takeoff-failure38-01.json
- OLD/build/live-client-test/fix38-deployment/server-fix38-flight-telemetry-v1/receipts/fix38-resume-original-order-flight-01.jsonl
- OLD/build/live-client-test/fix38-deployment/server-fix38-physical-readback-v1/receipts/fix38-physical-takeoff-roll-02.json
- OLD/build/live-client-test/fixed-wing/polartt-01/server-fcc-cache-readback/fix38-v1/receipts/fix38-fcc-native-start-02.json
- OLD/build/live-client-test/fix38-deployment/actual-after-stop38-world-backup-01.json (461 files, SHA-256 b09a2f4e326cfb0771f21d7a05011b99753039a89d9a57ec8153efe75d18e4af)

OLD is TEST_WORKTREE. The failed actual world was copied for preservation and has not been restored from a prior snapshot.
