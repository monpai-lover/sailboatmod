# LDLib2 UI Repair Implementation Plan

> **For agentic workers:** Execute the independently owned tasks in parallel, reproduce each defect before fixing, and run a shared verification after integration.

**Goal:** Integrate the authorized all-UI return and repair the defects documented by the 2026-10-13 native-client audit.

**Architecture:** Keep the LDLib2 screens and both themes. Build headers before dependent bodies, restore theme subscriptions on each mount, make horizontal actions size to their rows, constrain and scroll UI content, and give market stock a component-aware identity validated by the server. Existing server economy/permission rules remain authoritative.

**Tech Stack:** Minecraft 1.21.1, NeoForge 21.1.259, Java 21, LDLib2 2.2.42, JUnit 5.

The user has explicitly asked Codex to implement the fixes after reviewing the diagnostic report. This plan proceeds within that authorization.

- [x] Import only the verified source/build delta from the all-UI return; back up each replaced/deleted baseline file under build/ui-repair-integration-backup-20261013. Preserve unrelated workspace changes.
- [x] Reproduce and fix first-open initialization and restored-screen theme subscriptions in ThemedScreen, ThemedContainerScreen and RoadPlannerChrome/Screen. Native Screen.init tests must initialize NationHomeScreen and TownHomeScreen without throwing, and remounting must update the theme without duplicate listeners.
- [x] Add a failing stock-selection regression for [book A x10, book B x9] becoming [B x9, A x8]. Add component-aware stock identity to the overview and listing request; reject a stale/mismatched request on the server instead of listing the wrong stack. Keep row identity independent of quantity. Verify encoded packet round trips and the accepted/rejected server selection logic. If packet layout changes, increment the protocol so mismatched client/server versions are rejected.
- [x] Fix ActionButton's full-width default where used in horizontal rows. Bank amount/loan fields must retain a usable width and all visible buttons must lie inside their parent row; keep vertical actions full width where intentional. Verify bank and incoming-trade rows in native UI geometry.
- [x] Fix market/catalog responsive layout, warehouse grid fit/scrolling, clipped title/status chips, and Ledger input background. At actual GUI scales 2/3/4 in a sufficiently large client window, all controls and visible slots must remain usable; input contrast must match the selected theme.
- [x] Convert the native audit harness from observation into assertions of corrected behavior. Test actual menu opening after client block-entity synchronization, raw numeric edits, stock selection refreshes, all market pages, both home screens, parent-screen remounts, and warehouse QUICK_MOVE count conservation.
- [x] Run focused failing tests before production edits and save RED/GREEN logs. Run full test/releaseMod after all fixes, then independent review, native-client assertions and a dedicated-server startup/normal-stop smoke.
- [x] Produce the fixed main/addon JARs and required dependencies in a versioned ZIP, with checksums and a concise validation report. Verify ZIP contents and ensure test-only handlers are absent from production artifacts.

Verification commands use JDK 21 and the project's gradlew.bat. Full verification is `gradlew.bat test releaseMod --console=plain -PfixIndex=3`; focused tests use `--tests <owned test class>`. Native verification uses a development-only init script and source set under ignored build/, with a separate game directory so existing user worlds are not part of the test.

Additional native findings repaired: literal width/height must be clamped before LDLib2 centres its root; Bank pages need a constrained body and scrolling loan/item detail panes; bound vanilla slot coordinates must be refreshed after final layout; style scope must also cover the road planner and market selector portals. A native layout-event probe reproduced a persistent Bank storage-heading height alternation of 9.000001/8.999999; fixed single-line metrics remove that feedback.

Completion evidence (2026-10-14): 1414 tests /324 suites, zero failures/errors/skips; 3534 native checks, zero failures; 99 final native screenshots; server start/normal stop exit0. Clear-filter widget synchronization was also reproduced via the actual Button callback and verified after the fix. Install ZIP and evidence ZIP CRC/SHA256 checked; both production jars exclude smoke handlers.
