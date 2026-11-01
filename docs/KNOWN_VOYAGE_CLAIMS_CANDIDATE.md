# Known-voyage dispatch and claim spawning candidate

Based on 2ff1de7d. The packaged patch contains the previously reviewed known-route dispatch and open-water navigation source candidate plus the claimed-chunk monster-spawn permission. This bootstrap commit does not yet assert successful compilation or publish the changed production sources; the verification workflow applies the SHA-256 checked patch, builds both JARs, runs regressions and native Tellus checks, then commits sources to the same feature branch only if checks pass and the branch head has not changed. No force push or main-branch merge.

## Spawn permission

- Single and multi-selected claims expose `允许刷怪` / `Allow monster spawning` in nation/town claim permission panels.
- Defaults to true, including missing keys in older saves. Explicit false persists through save/reload, ownership changes and edits of other permissions.
- False vetoes automatic MONSTER-category spawning through NeoForge placement, position and finalize hooks. It does not erase existing entities or affect animals/villagers; player command/spawn-egg, conversion and breeding paths remain unchanged.
- The server verifies existing claim-management permissions. World-generation threads read a concurrent denied-claim index, without forcing chunk loads or reading mutable SavedData maps.
- Protocol 22 requires matching client/server main JARs. The separate WebMarket source is unchanged.

## Navigation scope

Dock dispatch uses only connected saved routes; new route creation remains separate. R-menu local connectors are bounded and checked against real water. A stopped active voyage must be ended before selecting another destination. A sea-originated temporary voyage does not invent a home port. Mid-route waypoints do not apply final-arrival stop gear. Navigation phases distinguish turning, terrain wait, recovery and blocked states; repeated repair without real displacement is bounded.

Acceptance requires full compilation and tests. No claim of live multiplayer driving validation is made.
