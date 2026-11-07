# Native fixed-wing ground controls

Inspected on 2026-10-31 from the installed `create-aeronautics-bundled-1.21.1-1.3.2.jar` nested Offroad and Simulated JARs using `javap -p -c`. This is API and bytecode evidence, not a moving-aircraft stopping or turning acceptance.

## Wheel topology

`dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity` only applies native wheel forces when `getHeldItem()` carries `OffroadDataComponents.TIRE`. A decorative grindstone does not qualify. Native item IDs and `TireLike.radius()` values:

| Item | Radius in blocks |
| --- | ---: |
| `offroad:small_tire` | 0.75 |
| `offroad:tire` | 0.96875 |
| `offroad:large_tire` | 1.25 |
| `offroad:monstrous_tire` | 2.0 |

Player insertion uses the wheel mount's normal item interaction. `getInventory()` exposes its one-slot `SingleSlotContainer`; `getHeldItem()` is the read-only wheel item observation. Wheel center is `mountPos.relative(HORIZONTAL_FACING).center()`. For an aircraft facing west and an axle along Z, mounts face north/south, outward from the aircraft. Suspension strength and actual loaded height must be checked on the real craft.

The mount reads native brake redstone from `level.getSignal(mountPos.above(), Direction.UP)`. Signal 15 removes the wheel's kinetic drive contribution and raises longitudinal damping from `0.075 * friction` to `0.375 * friction`. This is wheel braking; it is not proof that a particular plane can stop inside a configured distance.

`getSteeringSignal()` reads the difference between the clockwise-side and counterclockwise-side redstone signals relative to mount facing. `computeYaw()` maps the signed difference to `-signal/15 * pi/6`, up to 30 degrees each way. Opposite-facing mounts require the physical side mapping to be reversed to produce the same signed native steering command. Redstone receivers above the mounts provide brakes; receivers on the two control sides provide steering. A common frequency can command multiple identically mapped mounts.

`SteeringWheelBlockEntity.updateTargetAngle(float)` is available and `held` is a public manual-override observation. The steering wheel's shaft rotation alone is not the input read by Offroad's wheel mount: an actual conversion/link or native redstone port routing is required. `ThrottleLeverBlockEntity.setSignal(int)` is a normal native control method; the lever's internal `getState()` depends on its INVERTED block property. Native transmission signal calibration is therefore persisted explicitly. `AnalogTransmissionBlockEntity.getRotationModifier()` evaluates `(15-signal)/16`, but `propagateRotationTo` explicitly treats signal 0 as 1:1 and signal 15 as disconnected. Signals 1..14 propagate either that modifier or its reciprocal, depending on which internal shaft is the input. The original Polartt source supplies the extra cogwheel at -32 RPM: the reverse-output path predicts -256 RPM at signal 13 and -512 RPM at signal 14, before native max-speed/overstress rejection. Thus numeric interpolation is unsafe: a small demand interpolated from 15 toward 0 can select 14 and produce the highest transmission ratio.

## Fresh support observations

`ServerSubLevel.latestAngularVelocity` is a public JOML vector. A missing angular API leaves ground observations unavailable while preserving the existing air pose snapshot.

The read-only reflection adapter invokes the wheel's own private `getRotatedWheelAxis(Vec3i)` and `computeMaxExtensionToTerrain(Vector3dc, Pose3dc)`. The latter casts three native terrain rays five blocks down and ignores the wheel's own sublevel. The bridge verifies transformed sampling columns are already loaded before querying.

The support test requires a real tire, an actual native terrain hit, finite radius/extension and positive native spring compression:

```
compression = .65 - clamp(maxExtension + .10833333333333334 - tireRadius, 0, .65)
```

It does not infer contact from speed or from the wheel's old render fields. `NativeGroundObservation.fullyGrounded()` requires at least three distinct supported tires with a non-collinear XZ support triangle larger than 0.25 square blocks. Actual stand bounds, body attitude and speed remain separate caller proofs.

## Pure mission integration

`AircraftGroundMission.Plan` freezes mission/physical identity, dimension, a combined layout binding revision, runway/stand/reservation IDs, ordered outbound/inbound points, hold index and measured actuator calibration. The registrar verifies both airports' actual current revisions and reservation identities before producing each observation.

`step(plan, state, observation)` returns a replacement state and an action. `Action.outputs() == null` means no ground actuator write, including brakes. Outputs contain the already calibrated native throttle signal and 0..15 native left/right/brake levels. Do not invert throttle a second time. The controller emits only the calibrated neutral or drive endpoint. It powers below 80% of the current straight/corner speed limit, coasts at 100%, and retains the previous endpoint between those thresholds. `State.lastThrottleSignal` records the output latch; the compatibility constructor and recovered states use -1 (unknown/coast). Braking always selects the measured neutral endpoint.

Ground throttle must control an independent native wheel drivetrain, never the shared upstream supply of an FCC propeller. The accepted test retrofit is a separate portable engine -> ground analog transmission and lever -> gearbox -> actual Offroad traction wheels. The original propeller remains FCC-linked, with its separate flight supply configured and physically verified before preflight. This hardware design does not itself prove a completed retrofit or flight.

FC0.8.3's `AeronauticsPropellerBearingBEMixin` cancels ordinary `updateRotationSpeed` whenever the propeller has an FCC link, even while inactive. `AeronauticsPropellerFCCSupport` bounds output by real upstream `KineticBlockEntity.getSpeed()*0.3`; zero upstream RPM means zero available thrust. Therefore FC cannot restore a shaft disconnected by an external ground throttle, and merely powering an idle linked propeller does not establish ground taxi thrust. Preflight must read actual nonzero propeller input RPM and available native thrust; no link/unlink workaround or synthetic force is used by this controller.

`FC_STARTING` first emits neutral ground outputs. `START_FC` is requested only after the caller observes those outputs are neutral and proves grounded, slow, aligned and permitted. Only an observed bound native FC lease acknowledges ownership. Airborne or FC-owned phases emit no ground output. Landing requires an exact terminal flight-task proof, safe release and low linear/angular speed. FC may release its lease automatically on completion; an exact bound completion/release proof handles that path too. A release request never substitutes for release evidence.

Stand cargo readiness requires actual whole-hull containment, true wheel support, speed <=0.25 m/s, angular speed <=0.035 rad/s, heading error <=5 degrees, and 40 consecutive fresh server-tick samples. Route proximity is horizontal XZ because marked ground coordinates and the registrar center have different heights. Native contact supplies the separate vertical-support evidence.

`AircraftGroundMissionNbt.save(plan,state)` writes the complete bounded model. `loadOptional(tag)` rejects malformed, mismatched and out-of-range models, preserves phase/index/bindings and returns a recovered state with dwell cleared and throttle latch reset to -1. The saved latch is bounded to the measured endpoints (or unknown); a pre-latch version-1 record loads conservatively as unknown. Loading cannot replay saved completion into cargo settlement.

The caller must use a bounded watchdog for its own previously leased ground outputs when native telemetry disappears, and must never overwrite a foreign/manual or native FC lease. Live measured braking/turning calibration and complete physical flight/cargo acceptance remain required.
