# Registered fixed-wing unloaded wheel contact

The user authorized completing actual automatic taxi, takeoff, landing and stand delivery, followed by independent other-airframe regressions. This corrects the demonstrated native wheel contact calculation during an already authorized automatic takeoff; it does not add airborne assistance.

## Actual prechange evidence

Installed Offroad 1.3.2 WheelMountBlockEntity.class SHA-256: `fb2d03d3c3ccf2219d53c3e6bf8327017913c8fd00574c1a9d14b64b341c6fd1`. The native ray-hit branch continues until extension exceeds `.65 + radius + .25`. However spring compression reaches zero earlier, at `.65 + radius - .10833333333333334`. In the intervening range it still computes damping, longitudinal/lateral friction and unconditionally applies the wheel impulse. Upward motion can therefore produce a negative spring impulse and opposing tire drag after normal support has ended.

The fix_47 `rotation-forces47-19` actual 40 Hz observation recorded 696 per-wheel samples with zero compression, negative vertical impulse and a non-sentinel extension inside that applied-force branch. An example is radius .75, extension 1.4427869205379125, impulse [6.120815903377901,-.8267429395746597,-.0022663810636348774], dt .025. The published FCC time identifies the neighbouring frame; these fields describe the last executed native wheel update. The .65 early-return sentinel is excluded because it can retain stale impulse fields. The evidence is not based on interpreting those stale sentinel vectors as applied forces.

Evidence and exact native source: OLD/build/live-client-test/fix47-deployment/unloaded-wheel-force-evidence47-01.json and native-wheel-api/. Temporary public native force-recording state was restored and its listener removed, verified in the observer summary.

## Implementation boundary

Only the exact inspected Offroad class and single impulse call site enable the optional hook. The handler observes the current native wheel calculation immediately before force accumulation. It may cancel that wheel call; it never changes a native force vector, solver result, task, lease, body pose or velocity.

The caller must be a configured wheel of exactly one live registered/calibrated fixed-wing body, with enabled/renewed takeoff assistance, fresh current native automatic takeoff and an independently revalidated native flight lease for the same body/task/profile. The wheel must be uncompressed, have no positive vertical support impulse and have a sufficiently upright body. Actual tire radius is read from that player's wheel item.

Each of the three native-style casts is separately observed without requesting unloaded chunks. Any hit on another vehicle or a wall retains native behavior. Every accepted ray must hit static upward-facing terrain or miss, and the minimum eligible distance must match the current native extension. This individual check is necessary: the native aggregate overwrites normal/body metadata for later rays even if an earlier ray supplied the minimum distance.

Loaded suspension, positive damping support, brakes on genuinely supported wheels, native airborne early returns, manual controls, nonregistered/foreign wheels, unsupported binaries, ambiguous registrars and unavailable evidence retain native behavior. The existing assist still applies zero impulse when wheel support is zero and retains its original total time/speed/identity limits.

## Verification

The regression based on the real wheel sample failed with the previous no-correction policy, along with the zero-load traction and alternate-radius cases. Contact geometry, sentinel behavior, positive support, different tire radii, invalid evidence, three positions of cross-vehicle hits, walls/MISS behavior, exact-binary gating and optional handler ABI are covered by tests. A separate read-only review caught the native aggregate metadata issue and confirmed the individual-ray correction.

Actual fix_48 game regression is still required. A successful build or an increasing suppression counter alone does not prove a successful takeoff, landing or delivery. Other aircraft remain untested until each complete workflow is actually repeated.
