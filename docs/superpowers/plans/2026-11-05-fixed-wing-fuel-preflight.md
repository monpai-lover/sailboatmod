# Fixed wing fuel preflight

User requirement: aircraft must consume real fuel. Its actual engine supply must cover the planned journey before takeoff. This supersedes the earlier assumption that guided transport need not depend on fuel. No cosmetic spinning of an unpowered propeller will be introduced.

Current evidence: original Polartt completed actual delivery and automatic return under fix58. Purchase is CLAIMED, shipment DELIVERED, buyer private warehouse received four wheat, carrier cargo is empty. Seller wallet increased 40 to 48 and stayed unchanged over two normal saves 6000 ticks apart. Its propeller stopped during landing and remained stopped during return. Two fresh server tick samples now show the actual portable engine has an empty fuel slot, BurnTime=0 and GeneratedSpeed=0; the actual propeller angle does not change. Earlier motor speed observations did not prove propeller rotation.

Plan:

- [x] Pure bounded duration estimator uses actual path advancement and arrival dwell, rather than distance divided by cruise speed. Include the actual prospective return path whenever the controller will return.
- [x] Pure fuel budget requires every necessary propulsion source to cover the complete planned time, with a default 20 percent reserve and at least 60 seconds. Unknown data and arithmetic overflow reject admission. Separate usable fuel from demonstrated real power.
- [x] Read-only optional native adapter inspects actual same-body propellers, their existing kinetic network and necessary portable fuel engines. Use the engine's real standard burn-time lookup without consuming items. Never create a network during observation.
- [x] Central controller preflight covers manual start, market dispatch, automatic return and resume. Initial checking may queue at the stand, but no movement is granted until fuel and power pass. Recheck before takeoff, and stop progression on actual fuel exhaustion. Keep cargo and escrow intact on rejection.
- [x] Prevent active AutoReturn changes from bypassing the initial fuel commitment. Reconstruct and validate fuel requirements after reload; execution observations are not persisted as trusted fuel proof.
- [x] UI displays planned and return duration, reserve, usable fuel per engine, shortage or unknown data, and actual propeller RPM. Initial absence of a plan must not permanently disable the check-and-start action.
- [ ] Actual insufficient-fuel test must reject without movement or cargo/order changes. Add real fuel through the engine's ordinary public inventory interface, wait normal ticks, then verify native engine power and changing real propeller angle before another full flight.
- [ ] Complete community v5 active rudder and another custom fixed-wing test. The previous engine-less trainer draft no longer meets this fuel requirement and must be adapted or rejected explicitly.

Build and runtime evidence remain separate. Preserve all existing map, berth collision and airport edits. Do not replay the delivered original order. Keep the v5 helper disabled until the original aircraft has been moved aside through a separately labelled ground setup operation.

Verified implementation checkpoint (fix59): releaseMod completed with 3445 tests, no failures/errors, and two pre-existing skipped tests; the new fuel/native contracts all ran. Main JarJar SHA256 is 8554aef7f0bec406504f5593f27995ac86f5fc8870353e8bb49e194e95d82b75. Inclusive native plot-bound chunk coverage was corrected before final build.

Actual negative: the normal START request first queued terrain preflight. It then stopped at the original stand with fuel_insufficient, required 19589 ticks versus 599 available, with three real powered engines and a rotating propeller. The initial helper's synchronous accepted=false assumption failed and its receipt is preserved; queued request acceptance must not be interpreted as granted motion. Strict after-the-fact custody/pose audit is recorded separately.

Actual replenishment: ordinary inventory insertion added 32 plain coal to each of the three real propulsion engines (96 total creative test items), with no burn/RPM/native force writes. After normal warm-up, cached Ready=true for all three engines and the propeller moved at about -256 RPM. Actual Chinese UI renders outbound, return, reserve, total, available duration, all three engines and propeller speed. The real owner disabled creative flying through normal double-space input and native readback now identifies the original tracked aircraft body. Full new-order flight/return and the v5/custom fleet checks remain pending at this checkpoint.
