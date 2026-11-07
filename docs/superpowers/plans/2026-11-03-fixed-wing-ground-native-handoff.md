# Fixed-wing ground and native flight handoff

The authorized goal remains a real player-built fixed-wing flight: select a usable configured taxi route, taxi, take off through native Flight Control, land, taxi to the assigned stand, and deliver the existing cargo through normal logistics.

## Observed failure

fix35 completed 572 test suites (2887 tests, zero failures/errors, two existing skips) and passed the complete package audit. The actual aircraft advanced from TAXI_OUT point 0 to point 1 and moved about 11 metres. It then stopped while the ground follower continued requesting full drive and zero brake. The terrain window continued clearing.

The existing native propeller state showed approximately +112 N while the rear wheels supplied approximately -108 N. Two independent force snapshots and the propeller moment arm identify the opposing native WORK output. An empty navigation task/broker therefore does not prove that native WORK actuation is neutral. Evidence is preserved in the isolated fix36 deployment directory's native-ground-force-conflict-rootcause35-01.json.

Turning WORK off is not the chosen fix: the installed native code also releases powered flap bearings, allowing ordinary kinetic rotation. The inspected no-active-controller path already provides neutral flap servo and cleared propulsion. Welded output clearing can release welded flap servo, so the final operation must re-establish native neutral flap control.

## Implementation contract

- Keep native WORK/default switches, gravity settings, solver budgets, session state, profiles, and navigation authority semantics intact.
- Use an optional, exact-version-compatible Mixin at the inspected allocation boundary after native pose/performance publication and before controller demands can produce outputs. Reuse native clearing and neutral-servo methods; do not inject physics forces, change poses, advance physics, or synthesize arrival.
- A short-lived ground-neutral lease identifies the exact registered controller, mission, physical body, linked FCC, and profile. Claim it only with fresh ground support and released native navigation authority. Reject manual takeover, foreign authority, stale observations, changed bindings, and unsupported compatibility.
- Native acknowledgement belongs to that lease generation and body and requires fresh independent physics observations after normal neutral processing. Wheel control waits for acknowledgement. A command being issued is not evidence of motion, arrival, or delivery.
- Before native START, wheel outputs must be neutral. Release the ground-neutral lease, wait for fresh strict native power/performance information, re-read the current expected native token, and use the existing normal START path. Never bypass its preflight checks.
- After exact native completion and safe rollout, use normal cancellation/release, then establish ground-neutral acknowledgement again before taxi-in.
- Pause, stop, manual takeover, completion, unload, and recovery remove only this controller's transient lease. Reboot starts with no live registry claim; the existing explicit owner Resume and binding/contact checks establish a fresh claim. No native default setting requires persistent override or restoration.
- Geometry selection remains generic. Production code must not contain this test aircraft's UUID, mass, blueprint, airport coordinates, or wheel count. Use the current supported native capabilities and calibrated ground configuration.

## Verification and rollout

1. Write meaningful failing lease, acknowledgement, compatibility, and RVC handoff tests; root alone runs Gradle and preserves RED evidence.
2. Implement the minimal fix, independently review authority and native actuator semantics, and run the affected tests. Preserve the existing map, math, budget, and logistics behavior.
3. Run the full release build with the existing Minecraft web-asset source property, inspect the actual JARs, and compare package/source/test bytes. No success claim from compilation alone.
4. The isolated fix35 client/server were normally stopped and the 451-file world was copied and byte-verified for fix36. Install only the isolated server main/addon and cloned client main.
5. Resume the same ground mission and order through the normal owner interface. Verify native propulsion neutral, stable flap servo, real wheel motion, takeoff, flight, landing, stand parking, and actual warehouse/payment settlement. The real flight and delivery remain unverified until these observations succeed.
