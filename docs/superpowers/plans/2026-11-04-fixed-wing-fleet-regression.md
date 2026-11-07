# Fixed-wing fleet regression

User requirement: finish the current aircraft first, then test other fixed-wing aircraft. Each selected airframe must complete actual automatic taxi, takeoff, flight, landing and stand delivery. One successful airframe does not certify other layouts.

## Earlier evidence (superseded by the observations below)

- Installed isolated build: fix_44. Original Polartt body `airframe-a`, observed mass 95. No stable takeoff, landing or cargo delivery has passed.
- Fresh takeoff observations reached about 22.6 m/s. Elevators track native requested angles. Actual native aerodynamic/propeller pitch torque agrees closely with the allocation prediction.
- Recorded active wheel impulses oppose the requested pitch rotation during ground roll. Cached `queuedForce` survives the native airborne early return; exclude those stale vectors when interpreting wheel forces. The queued vector alone does not prove contact.
- Ordinary native pitch-limit trial: 0.2 to 0.4 rad/s, using the public configuration method after normal cancellation and verified released authority. Native synchronized redstone pitch limit changes with it. This is a separately labelled configuration trial, not a general production fix or a successful flight.
- Raw evidence: existing worktree `1.21.1-neoforge/build/live-client-test/fix43-deployment/runtime-01/server-fix43-force-tracking-v1` through `v7`. Individual force recording is bounded and restored after each observation.

Additional actual evidence on 2026-11-04:

- The fuelled 0.4 and 0.8 rad/s pitch-limit trials did not achieve stable liftoff. The original 0.2 rad/s limit and synchronized redstone setting were restored through the native public configuration API.
- fix_44 removes the premature 1.05 VR speed ceiling and follows the native takeoff ramp. Both the old airport route and a separately published, aligned straight test route still failed; straight-run maximum native speed was about 23.3 m/s. No landing or cargo delivery was inferred.
- The actual straight trace alternates `assisting` and `brakes_applied` at the native aerodynamic eligibility threshold. Inspection of installed FC 0.8.3 confirms `fixedWingBrakeDeployment` stores `allowBrakes`, not applied wheel braking. Correct that adapter interpretation while preserving actual wheel inputs, native speed cap, heading/slip/bank/contact guards and genuine airborne exit. Repeat the current aircraft before expanding the fleet.

## Airframe matrix

| Airframe | Difference to exercise | Current status |
| --- | --- | --- |
| Original Polartt test aircraft | Existing four-wheel airframe, original cargo custody and native hardware | Real takeoff and straight cruise; turn altitude loss, no completed transport |
| Public plane_v5 | Three wheels at two local heights, different five-bearing layout, 16 x 7 x 29 | Blueprint checksum and 300-block offline inspection complete; not placed, assembled or flown |
| Lac7om, Airplane from Aeronautics Trailer | Larger wing/gear layout, 17 x 5 x 21, published 213-block design | Source inspected; download/placement/flight pending |
| Separately built light fixed-wing fixture | Different wheelbase, main-wing and tail positions; independently assembled and calibrated | Not built |
| Separately built loaded fixed-wing fixture | Changed mass and centre of gravity with actual cargo | Not built |

Second source: https://createmod.com/schematics/airplane-from-aeronautics-trailer . The author's propulsion caveat is not proof of flight in this environment. Required blocks must be checked before import; missing mods must not be silently replaced or counted as compatibility passes.

## Per-airframe acceptance

- [ ] Preserve blueprint attribution/checksum and log every fixture adjustment. Build/assemble normally; identify the new body and registration independently.
- [ ] Read back actual mass, centre of gravity, propeller capability, working control surfaces, wheel support and ground calibration. Use measured geometry, not Polartt coordinates or fixed assumed gear locations.
- [ ] Select a connected taxi route compatible with the airframe clearance and turning limits; reject an insufficient path with a concrete reason.
- [ ] Taxi from the assigned stand through the selected hold point and align on the runway.
- [ ] Perform a real ground roll, rotate through working aerodynamic controls, leave all wheel support and continue climbing. A collision bounce, runway overrun or falling from a platform does not pass.
- [ ] Confirm takeoff acceleration assistance stops after genuine liftoff and remains stopped throughout that task.
- [ ] Follow the route, approach the destination runway, touch down, decelerate and taxi to the assigned stand.
- [ ] Unload only after the actual stationary stand-arrival condition; verify cargo and money custody with normal logistics entry points.
- [ ] Repeat three complete flights on the accepted configuration, then verify configuration persistence and a new ordinary departure after restart. Saved enabled state grants no automatic assist arming authority.
- [ ] Record pass/fail and fresh physical evidence for this exact airframe. Keep untested airframes marked untested.

## General support boundary

Compatibility must use each player's measured wheel, propulsion and aerodynamic layout. It must not encode a blueprint UUID, mass, wing angle or fixed pitch gain as a universal flight solution. A structurally unflyable or unconfigured aircraft needs a clear preflight failure; do not manufacture a flight pass through pose/velocity edits, direct lift/torque, solver-output replacement or synthetic arrival/settlement.

Preserve the original four-wheat order and escrow until the actual business workflow completes. Remove only the temporary force-loaded chunks recorded for the current observation and restore the exact original 62-chunk set when those resources are no longer needed.

fix_45 follow-up: corrected aerodynamic brake eligibility, but no sustained flight. Original 0.2 rad/s pitch setting produced only a short hop; 0.8 rad/s reduced pitch excursions but still failed. Actual force recording at 40 Hz shows the aircraft slows from about 26 to 17 m/s during a 0.8-second interval with one positive native wheel contact, while the two-contact guard suspends all assistance. Preserve real wheel evidence and initial full-support arming, allow a measured single-contact rolling transition, and repeat. Keep the 0.8 rad/s setting explicitly labelled as this fixture trial; it is not a universal configuration. Restore the original cargo route card via the public item API before restart so normal recovery can identify and cancel its own replay.

## Latest actual evidence

- fix_46, owner acceleration 20 m/s² and ordinary native pitch limit 1.2 rad/s: the original aircraft remained physically airborne for 27.9 native physics seconds, all four wheel contacts zero, rising 34.48 m. Additional assistance impulse throughout that airborne interval was zero. Native follower readback entered cruise. Evidence: OLD/build/live-client-test/fix46-deployment/takeoff46-03-result.json, its frozen raw trace prefix and sustained-flight46-03.png.
- That single takeoff is not a completed transport test. The old test waypoint had moved behind the aircraft after repeated surface runs; the subsequent turn lost altitude. Replacing the native route at low altitude entered a new acceleration stage and did not demonstrate recovery. The aircraft then actually touched the grass surface and settled normally; no body restoration was used.
- Preplanned ferry trials with a 15-degree bank limit and 8-degree climb setting failed to rotate. Restoring the 25-degree climb setting reproduced a short 0.2-second unsupported hop followed by ground recontact. The first zero-contact frame permanently ended assistance, so the flight was not sustained. These native configuration changes are fixture trials, not universal settings for other players' aircraft.
- fix_47 work separates immediate no-contact force suspension from sustained-liftoff confirmation, preserving initial complete support, task identity, authority, native speed command and the original 20-second budget. Actual runtime verification remains pending.
- The original four-wheat manifest remains unsettled. Landing, runway rollout, inbound taxi, stationary stand delivery, three repeated complete flights, restart departure and all additional airframes remain unpassed.

## fix50 actual flight and ground preparation

- Original C/D facilities were moved through the ordinary airport editor service using the actual online owner. All runway, taxi and stand surfaces are now the real grass plane Y=-60. The airport blocks and warehouses remain at their original positions. Both configurations committed with actual terrain/claim validation; this is configuration evidence, not a taxi/landing pass.
- The new ground-reference adapter observed all four wheels and twelve real rays. A separate forty-tick stability window measured FCC height above actual wheel surface at approximately 2.65 m. It does not trust the older hull-minimum offset or a persisted calibration as fresh support proof.
- The original task `native-flight-b` actually took off, maintained straight cruise near Y=-20 and advanced to waypoint3. First-turn altitude loss caused a normal freeze at native210.2. All airborne takeoff-assist impulses remained zero; total assist stayed8224.95013465149.
- A bounded one-second real force trace at210.2–211.2 found native recovery: direct aerodynamic impulses, queued propulsion, gravity11 and normal damping close against actual rigid-body velocities. This does not determine the cause of the earlier descent.
- Freeze within a native substep caused an ordinary BLOCKED→automatic retry. The new task `native-flight-c` retained waypoint3 but rebuilt follower/trim/approach state. Its separate3.7-second continuation again descended and stopped at the altitude boundary. Do not join these task segments into a continuous successful flight.
- The replacement segment captured a secondary defect: unheld measured-pitch bootstrap disappears when speed crosses VR, making the requested nose Y exactly zero. The fix51 continuity and truthful-environment work is tracked in `2026-11-04-fixed-wing-pitch-continuity.md`; runtime success is pending.
- Public normal OFF/CANCEL followed by real grass settling restored four supported wheels, zero velocity and unchanged mass95. The exact original cargo card `642448556d1557b3a5e9fbad28fe431680b01d649f8d80e38259c4f76319375d` was restored through its public item API. All three original business rows and the four-wheat manifest remained identical, with escrow8 and IN_TRANSIT status.
- This settling operation is fixture recovery, not an automatic destination landing or delivery. Automatic touchdown, inbound taxi, stand settlement, repeated flights, restart departure and every additional airframe still require actual verification.

Evidence: OLD/build/live-client-test/fix50-deployment/{ground-surface50-02-analysis.json,ferry50-01-frozen-prefix.jsonl,turn-force50-01-independent-analysis.json,ferry50-01-initial-descent-independent-analysis.json,settle50-01-stop.json}. The separate continuation and original-card restoration receipts retain their actual task identities and normal API results.

## fix51 actual takeoff retry and unchanged custody

- The fully verified fix51 artifact loaded on isolated server15570/client15633. Its complete release had621 suites/3116 tests, no failures/errors and two existing skips; actual native contract was enabled. All expected compatibility hooks read back as applied. Neither test counts nor hook presence prove a flight.
- Actual task `native-flight-d`, ferry profile `71b472499e05e58a5abd54446412f233d0469a383b314403aab7730ea1d3cc09`, briefly had zero wheel contacts for3.5 native seconds but rose only0.716 m before recontact. Its native V1 stayed18.0662 m/s and VR21.6794 m/s. Forward speed fell below V1, so native takeoff reduced target pitch to zero. The cause of the extra speed loss versus fix50 remains unproven.
- The old Session permanently ended after0.5 seconds of AIRBORNE while native still reported takeoff_climb and fixedWingCruiseEntered=false. Real recontact did not recover the remaining original ground-assist budget. This is the narrow fix52 defect; airframes must still receive zero assistance on every unsupported/unknown contact frame.
- No ordinary-cruise bootstrap call or available native trim environment was reached in this attempt; the two fix51 airborne changes remain runtime-unverified. No completed runway landing, taxi or cargo settlement occurred.
- Public OFF/CANCEL and real grass settling again produced four supported wheels and rest, preserving body UUID and mass95. The exact original642448 cargo card was restored through its public item API. All original business rows and the four-wheat manifest matched the preflight receipt exactly. Both isolated processes then exited normally; stopped-world backup before52 verified1418 files. No aircraft pose/velocity or business edits occurred.

Evidence: OLD/build/live-client-test/fix51-deployment/{ferry51-01-frozen-prefix.jsonl,ferry51-01-stop.json,settle51-01-stop.json,ferry51-01-recovery-and-custody.json}. The original62 chunk set was verified restored after both observation windows. Additional airframes, repeated complete flights and restart departure remain unpassed.
