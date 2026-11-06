# Fixed-wing bounce recovery implementation plan

**Goal:** Preserve an already ground-armed takeoff episode across temporary loss of all wheel support, without applying any airborne impulse or extending its original twenty-second budget.

**Scope:** Change only `TakeoffAssistSession.java`, directly affected tests, and this plan. Leave Lifecycle, native V1/VR guidance, task identity/authority, acceleration and impulse direction unchanged. fix51 source was preserved and verified before editing.

- [x] Add a trace-shaped regression: same task starts at native10.8, observes no support from23.5 through27.0, and regains actual ROLLING contact at27.1. Assert zero impulse throughout the gap, unchanged accumulated impulse, and resumed ground-only impulse inside the original budget.
- [x] Run the new behavior tests against the unchanged Session using standalone javac/JUnit; preserve RED output in `build/fixed-wing-bounce-recovery-52/red.log`.
- [x] Remove only the half-second AIRBORNE permanent-end rule and its unused counter. AIRBORNE returns zero `airborne_pending`; UNKNOWN remains zero. Existing native phase, authority, propulsion, identity, disable, expiration/suppression and duration termination stay permanent.
- [x] Update older tests which treated a half-second airborne interval as completed takeoff. Require actual native takeoff handoff before asserting permanent closure.
- [x] Run standalone focused JUnit over Session, liftoff/bounce and existing Lifecycle tests. Preserve GREEN output, exact command/classpath and source hashes.
- [x] Obtain an independent read-only review. Do not run Gradle, build a release, attach, operate the game, commit or push.

**Required boundaries:** Original deadline cannot move on contact return; native handoff/authority loss/propulsion loss cannot recover; config disable and Lifecycle expiration/suppression cannot reset this task. Both AIRBORNE and UNKNOWN remain zero even with large speed headroom. Missing physics frames cannot create catch-up impulse.

**Validation:** The unchanged production Session first failed four of five new behavior tests (`red.log`), including expected28.5/actual0 on real contact return. After the minimal change, standalone JDK21/JUnit Jupiter5.10.2 (Platform1.10.2) ran35 tests across bounce, Session, liftoff and the unchanged Lifecycle: zero failures, aborts or skips (`green.log`). Exact compilation/runtime commands and classpath are retained in `build/fixed-wing-bounce-recovery-52/run.py`, `classpath.txt` and those logs. Root independently approved the preserved51-versus52 production/test diff; air_integration_review independently found no concrete defect. No Gradle or game operation was performed.

**Limits:** Actual Lifecycle lease expiration, suppression or disable remains permanent for this task; wheel recontact does not revive it. This addresses the local assist episode ending before native takeoff handoff. It does not prove that restored ground acceleration will achieve liftoff, or that the transient `request_expired` diagnostic in flight51 represents an actual lease expiration.
