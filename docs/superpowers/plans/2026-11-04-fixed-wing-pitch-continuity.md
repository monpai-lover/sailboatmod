# Fixed-wing pitch continuity and real trim environment

Continue the authorized automatic fixed-wing transport repair, then independently test other flyable airframes. A real landing, stand arrival and business settlement are still unpassed. Preserve the four-wheat order and escrow.

## Evidence and scope

The exact fix50 source is preserved in `build/fixed-wing-ground-reference-50/preserved-fix50-source.zip` with every entry checked against the 2240-file source freeze. Runtime evidence lives in OLD/build/live-client-test/fix50-deployment.

- The original task took off and held straight cruise. The first turn lost altitude; its initial cause is not established by existing actuator/force evidence.
- A bounded real-force window at native210.2–211.2 showed recovery, not insufficient total UP force. Native physical impulses and actual velocity changes close with gravity11 and normal damping.
- Ordinary freeze inside a physics substep makes Create report zero speed for the remaining game tick. Native navigation can become BLOCKED, then autonomously create a new task/follower at the retained waypoint. These task segments must not be presented as one continuous successful flight.
- In the separately identified normal replacement task, native213.1–213.3 crosses VR while bootstrap is unheld: the compatibility scope withdraws, requested nose Y becomes exactly zero, and descent subsequently grows. This discontinuity is a demonstrated secondary defect; correcting it does not prove the original turn is fixed.
- Native reversible propulsion prevents the axial probe and leaves its environment UNKNOWN. The trim learner reads only that environment; preserving real environmental observation does not justify marking acceleration/braking capability available or bypassing the reverse-probe protection.

## Work sequence

1. Add a failing continuity regression. Replace the binary VR cutout only for freshly authorized, airborne ordinary direct-route cruise. Use measured forward speed and a continuous weight from max(V1,0.95 VR) to VR. Retain the native measured AoA, native bounds and final pitch clamp; held trim and all excluded phases remain native. Do not add airborne force or forge a held bias.
2. Pin exact native classes and verify the precise feedback bytecode sites. Remove the old demand redirect when replacing it; never stack both interventions or replay a native invocation. Test unchanged fallback, held trim, invalid inputs, scope cleanup and continuity at both band edges, including the real native method where feasible.
3. Independently supply actual same-frame wind/configuration observation to unavailable axial limits through the native immutable Sample path. Preserve axial.available=false and all native Sample.forRead identity, age, epoch and demand-revision gates. Missing or uncertain model data stays UNKNOWN. Verify native learner admission/reset behavior; implement only after the hook design is reviewed.
4. Review, run focused regressions, then the full release checks. Preserve fix50 artifacts/world and verify the actual main JarJar artifact and installed hashes before any deployment.
5. Recover the current test aircraft using ordinary cancellation/control and real physics only. A recovery or settling operation is fixture preparation, not an automatic landing pass. Re-run the whole flight from measured ground support without diagnostic pauses between phases; use bounded observation and normal freeze only for a stop condition.
6. Require actual destination runway touchdown, deceleration, selected inbound taxi and stationary stand delivery before business success. Then perform the separately identified fleet matrix and repeated/restart tests; untested aircraft remain untested.

## Invariants

No aircraft pose/velocity setters, solver-output/task/progress/authority writes, fake arrival, enlarged capture sphere or synthetic cargo/money settlement. No extra airborne propulsion. No unreviewed changes to unrelated dirty work, no commit or push. Any native task replacement is recorded explicitly and requires a fresh observed binding.
