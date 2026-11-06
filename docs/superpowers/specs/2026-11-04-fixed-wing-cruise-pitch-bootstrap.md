# Fixed-wing cruise pitch bootstrap

The current aircraft must complete the real transport workflow before the other airframes can be accepted. fix48 has now completed an actual takeoff and 30 continuous native physics seconds with no wheel support. Its airborne assist impulse was zero. That result does not certify waypoint completion, landing, taxi or delivery.

## Actual failure

The same ordinary native task subsequently passed the first waypoint with a minimum observed FCC-reference distance of 4.216256 m. Native FC 0.8.3 requires a three-dimensional 2 m capture (or a swept segment through that sphere), so it correctly retained WP0 and attempted a return. A separately recorded ordinary speed-reference change from 49 to 39.2 preserved the task but did not resolve recapture. Stop at low-altitude descent, normal public OFF/CANCEL and actual grass settling left the original body at mass95 with four supported wheels and zero velocity. No aircraft pose, velocity, progress, cargo or settlement was edited.

Primary installed source explains a control gap: private `FixedWingGuidance.flightPathFeedback` computes measured angle of attack as actual nose pitch minus actual flight-path pitch, clamps it to +/-8 degrees, then replaces that compensation with zero for ordinary demand-based flight while the pitch-trim learner has no held bias. Native trim admission also receives actual nose pitch as `previousPathPitch`, although its near-level gate is intended to describe a flight path. Thus a plane requiring positive angle of attack can lack an initial compensation. This is a causal hypothesis to test, not a claim that cruise is fixed.

## Minimal change to test

For a freshly proven, registered and calibrated aircraft under its actual native flight lease, retain the native measured-AoA compensation while trim is unheld. Use the existing native +/-8 degree bound, descent/climb clamps, attitude controller and physical elevators. Preserve the held-bias branch. Do not introduce extra airborne force, a hard-coded trim angle, a simulated arrival or a larger waypoint acceptance radius.

The change applies only during one `runRouteTask` call of the exact supported native class versions. Wrap its actual candidate follower invocation exactly once, restore the previous scope in `finally`, and hide an eligible outer scope during an ineligible nested invocation. Recheck the actual candidate's current stage at the inner feedback call because it may enter landing during the update. Exclude takeoff, unknown contact, speed below native VR, ground roll, final two landing points, native landing, direct orbit and follow modes. Tie eligibility to the live body, task, installed profile, reciprocal computer link, calibration, fresh physics frame and actual control lease. A disabled takeoff acceleration preference does not disable the independent cruise correction.

The inner redirect affects only the single demand read in the exact private feedback method. With an eligible unheld scope it bypasses that method's zero-override condition; otherwise it returns the original native demand. It does not forge a held/verified bias, replace a solver result or write controller progress. It changes normal elevator commands and must be verified in real physics.

## Verification

1. Preserve exact fix48 source, artifacts and current world before deployment.
2. Exercise nested rejection, exception cleanup, thread isolation, scope close order, original invocation count and exact version/callsite/hash gates. Confirm fail-closed behavior for uncertain evidence and independent scope/feedback markers.
3. Full build/test/package verification and installed-artifact hashes.
4. Restore the ordinary forward-speed setting to49, original cargo card and real settled support before restart. Author a fresh aligned route from the new actual ground pose. Repeat real takeoff and compare native height tracking, actual bootstrap counters and unmodified waypoint capture. No flight success may be inferred from unit tests.
5. Only after stable real flight, finish landing, stand taxi and actual delivery, then the fleet acceptance matrix and restart flights.

Evidence: OLD/build/live-client-test/fix48-deployment/{takeoff48-01-result.json,cruise48-02-frozen-prefix.jsonl,cruise48-03-stop.json,settle48-04-stop.json}. All intermediate trials remain explicit failures for the complete transport workflow.
