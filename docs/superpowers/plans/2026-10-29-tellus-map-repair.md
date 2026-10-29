# Tellus saved-world map repair

**Goal:** Display the supplied Earth save on the market website, recover verified legacy map positions, and push a tested release to `1.21.1-neoforge`.

**Architecture:** Keep the world unchanged and run copies on a production NeoForge 21.1.265 server. Recover a legacy dense position only when its exact physical block entity and identity match and the current position has no competing entity. Keep persistence evidence separate from runtime long-based nation models. Choose AutoNavi image zooms within its actual coverage.

**Stack:** Minecraft 1.21.1, Java 21, NeoForge, Tellus 0.8.3, JUnit, Node tests, the real browser.

## Reproduce

- [x] Hash the supplied world and copy it under ignored `build/map-world-test-20261029`.
- [x] Read real region block entities and compare their XYZ with saved packed positions.
- [x] Install an isolated production server and run the existing fix_6 main and web addon JARs.
- [x] Verify native dense decoding returns the actual market, then disable the profile in a separate copy to reproduce the remote API's incorrect coordinates.
- [x] Compare live AutoNavi images at zooms 0 through 12: the save's Greek location has content at 1 through 9 and blank HTTP-200 placeholders at 0 and 10 through 12.

## Repair and regression coverage

- [x] Add failing tests for portable positions, verified recovery, unknown IO, competing entities, save/reload, high-Y preservation, stale links, and provider coverage.
- [x] Implement `PersistedBlockPos` and integrate market/nation SavedData without changing the existing nation record contracts.
- [x] Preserve raw unverified records; do not turn a failed probe into authoritative XYZ during an unrelated save.
- [x] Preserve old market URLs and block terrain generation through unknown or unverified market coordinates.
- [x] Use AutoNavi zoom 1 or higher; cap international requests at 9 while retaining domestic detail. Keep the existing GCJ coverage rectangle explicit in the validation report.
- [x] Show coordinate and image-load diagnostics, protect against stale asynchronous completions, and expose non-sensitive tile draw counts in DOM attributes.

## Verify and deliver

- [x] Run `gradlew.bat test releaseMod --offline -x downloadAssets --console=plain -PfixIndex=7` and inspect every test result.
- [x] Run the packaged main and addon JARs against a fresh mismatched-profile copy; verify market/core recovery, canonical links, and original-save hashes, then verify the final artifacts after restart.
- [x] Inspect real browser map images at several scales and after save/restart, including a fresh normal dense-profile run with the final JARs.
- [x] Finish independent review and package the checked JARs. The final source commit and push are recorded in Git history.

The test worlds, account databases, logs, dependencies, and private data stay out of Git. This is a map-position compatibility repair, not a conversion of every third-party or vanilla packed-position field in an entire world.
