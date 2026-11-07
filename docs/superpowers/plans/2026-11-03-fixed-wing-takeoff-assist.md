# Configurable fixed-wing takeoff assist implementation plan

Goal: apply bounded, real horizontal propulsion during native automatic takeoff; demonstrate actual departure and automatic shutdown on the original player-built aircraft.

Architecture: pure per-task impulse policy; optional reflected Sable pre-physics integration; owner-controlled controller settings; existing packet and native authority gates. Preserve pose, velocities, task progress, cargo, payments and native aerodynamic controls.

- [ ] Add assertion-failing tests for `transport/air/TakeoffAssistConfig` and `TakeoffAssistSession`, then implement finite bounds, actual-frame consumption, task identity, contact and terminal shutdown behavior.
- [ ] Add native lifecycle tests then implement `compat/flightcontrol/NativeTakeoffAssist`; subscribe to actual pre-physics events, renew bounded requests and recheck current native task/profile/body/authority/wheels/power/brakes before applying local `J=m*a*dt`.
- [ ] Append controller actions, provide translated fixed-wing navigation settings, preserve all old action ordinals, and test UI wiring.
- [ ] Integrate owner actions, persisted settings without persisted execution authority, per-tick requests, Pause/Stop suppression and removal cleanup in `RegisteredVehicleControllerBlockEntity`; register the optional callback in `SailboatMod`.
- [ ] Run focused policy/integration tests, review units and native API compatibility, then full tests and fix39 `releaseMod` with the existing bundled Minecraft asset source.
- [ ] Back up the actual stopped surface-endpoint world; install only in the isolated test client/server; start frozen for fresh readback and enable through normal owner UI.
- [ ] Run a real surface-assisted takeoff with independent physical telemetry. Record force, current VR, real speed, contacts and altitude; verify force ends airborne and cannot rearm on the same task.
- [ ] Continue genuine route/landing/stand delivery checks as the aircraft and airport state permit. Report remaining unverified stages explicitly.

Validation commands (Java 21): `./gradlew test --tests '*TakeoffAssist*'`; then `./gradlew releaseMod -PfixIndex=39 -PmarketWebMinecraftAssetsSource="${TEST_WORKTREE:?Set TEST_WORKTREE}/build/generated/marketWebMinecraftAssets" --no-daemon`.
