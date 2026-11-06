# Fixed-wing landing reference from actual supported surface

User-authorized scope: finish real taxi/takeoff/native flight/landing/stand delivery for the current aircraft, then independently verify different public and player-built fixed wings. Preserve physical physics, cargo custody, owned task authority and ordinary airport validation.

## Reproduced cause

The RVC currently passes FCC.y minus world hull.minY as the runway reference offset. The planner adds that value to the authored support surface. The hull box excludes the actual tire contact surface. In the frozen fix49 ground observation, FCC.y=-57.34873580932617, hull.minY=-59.29792594342143 and actual supported grass top=-60. Four configured wheels are positively supported. Current offset 1.949190 differs from true static reference offset 2.651264 by about .702074 m. This is a measured instance, not a universal correction.

WheelContact.worldCenter is an axle reference rather than a contact point. Radius/compression alone cannot generally reconstruct the hit surface because compression is clamped and three rays may differ. Native TerrainCastResult aggregates min extension but overwrites hit metadata on later rays. Each ray must be independently checked.

## Implementation contract

1. Obtain a transient immutable GroundSurfaceObservation through a read-only optional-native adapter. Verify exact supported ABI, all registered wheels uniquely bound to the same live body, positive native support, the three real rays and matching min extension. Require loaded paths and actual static UP terrain hits on one horizontal plane. Unknown, wall, foreign body, MISS, unloaded path and partial wheel evidence never yield a reference.
2. Combine the observation with a fresh working native FCC snapshot, matching body and exact configured wheel count, stopped linear speed <=.25 m/s and angular speed <=2 deg/s. Compute FCC.y minus observed terrain plane. No hull substitution, hardcoded airframe offset or saved historical calibration may supply missing proof.
3. Require a short continuous stable observation interval before new ground preflight. This state is transient, resets on invalid evidence/body/clock/config changes and does not advance from duplicate snapshots or a frozen native frame. Use the same validated measurement in the geometry candidate check and final submitted procedures. Verify that the selected source stand surface matches the observed plane (legacy runway-only fixed-wing path uses its selected runway surface).
4. Preserve the fixed-wing route planner end semantics (touchdown then runway stop) and all existing clearance/authority/stand checks. Rotorcraft and airships retain their existing hover geometry. Never rewrite an active airborne task.
5. Stop/resume/restart must obtain new native ground evidence. Persist neither proof nor trusted offset. Wheel changes, fuel/cargo load, centre of gravity changes and different body dimensions must be measured again.

## Verification

Use TDD for ray proof and reference integration. Include vertical translation, differing reference/gear heights, partial/duplicate/foreign wheels, invalid/clamped/contradictory ray evidence, non-horizontal floor, stale/future native snapshots, moving or rotating craft, changing wheel configuration and restart reset. Preserve fix49 sources/artifacts and use a new fix50 freeze before release. Runtime acceptance remains actual low-altitude flight, approach, wheel touchdown, runway deceleration, inbound taxi, stand dwell and real cargo/accounting readback. Code tests alone do not pass flight.
