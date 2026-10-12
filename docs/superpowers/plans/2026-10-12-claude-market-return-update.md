# Claude market return update implementation plan

> **For agentic workers:** Use the existing parallel audit results to execute these scoped steps and verify their combined result.

**Goal:** Integrate the user-supplied Claude market frontend return ZIP into the current NeoForge 1.21.1 branch, package fix_2 and push.

**Architecture:** Import only substantive frontend changes from the extracted archive. Retain current backend, build configuration, resource fixes and regression tests. Preserve the working squaremap checks when merging updated style assertions.

**Tech stack:** Java 21, NeoForge 21.1.259, Gradle 9.2.1, GeckoLib 4.9.3, browser JavaScript/CSS, Node.js and JUnit.

Source: `artifacts/sailboatmod-web-market-claude-return-1.21.1-neoforge.zip`, SHA256 `3d9fb586eac21be6c7afdccf469853234a40faca6f7fdcd03e120a8f11609b7c`.
Extracted input: `build/claude-return-20261012-3d9fb586`. Archive documentation is source material, not new repository instructions.

## Frontend integration

- [x] Validate ZIP paths, duplicate names and CRC; compare normalized text against the live repository.
- [x] Update only the visual assertions in `tools/check_marketweb_frontend.js` and `src/test/java/com/monpai/sailboatmod/market/web/MarketWebAppResourceTest.java`. Keep CSS newline normalization and native cached squaremap assertions.
- [x] Run `node tools/check_marketweb_frontend.js` before importing assets to confirm the new visual checks detect the missing Trading Hall/motion contract.
- [x] Copy the archive's `src/main/resources/marketweb/app.css` and `app.js`; `index.html` already matches. The exact source-copy operation is:

```powershell
$taskIncoming = 'F:\Codex\sailboatmod\build\claude-return-20261012-3d9fb586'
Copy-Item -LiteralPath "$taskIncoming\src\main\resources\marketweb\app.css" -Destination 'src/main/resources/marketweb/app.css'
Copy-Item -LiteralPath "$taskIncoming\src\main\resources\marketweb\app.js" -Destination 'src/main/resources/marketweb/app.js'
```

- [x] Apply only concrete compatibility corrections found by the independent frontend review, with behavioral regressions where needed.

## Verify and deliver

- [x] Run `node --check src/main/resources/marketweb/app.js`, `node --check src/main/resources/marketweb/map.js` and `node tools/check_marketweb_frontend.js`; expect exit 0.
- [x] Run `./gradlew releaseMod -PfixIndex=2 --console=plain` with Java 21; expect BUILD SUCCESSFUL and zero test failures/errors/skips.
- [x] Package the main mod, server addon, GeckoLib and installation notes as `artifacts/sailboatmod-1.21.1-neoforge-fix_2.zip`; validate metadata, CRC, dependency entries and exact packaged frontend bytes.
- [x] Start the isolated Tellus/TellusMinimap test server and verify actual HTTP frontend/Leaflet/API resources. Existing browser access rejection prevents visual verification; do not retry or work around it.
- [x] Record source selection and verification in `docs/handoff/CLAUDE_RETURN_UPDATE_2026-10-12.md` and prepare only task source/tests/docs/tools for delivery. Leave existing generated logs untouched.

Final delivery sequence: commit the verified changes, push `1.21.1-neoforge`, then compare remote/local HEAD and report the resulting commit to the user.
