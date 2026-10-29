# Map tile height seams implementation plan

> **For agentic workers:** Use systematic debugging and test-driven development for the tasks below.

**Goal:** Remove false bright south-side chunk rows while preserving real terrain slopes.

**Architecture:** Shade against the immediately adjacent north surface only. Cache actual chunk bottom rows for partial renders; unknown gaps break adjacency. Persist only actual region south-edge heights. Invalidate the derived square PNG and seam caches once on renderer upgrade.

**Tech Stack:** Java 21, NeoForge 1.21.1, JUnit 5, real saved Tellus world and Edge.

## Evidence

The user's screenshot matches square tile `55_-95`, row 64 (`Z=-48576`). The original NBT has grass/dirt surface Y=65 on both sides. The production test copy's row has grass RGB(109,141,66), while surrounding flat rows use RGB(89,116,54). A manager/renderer reproduction with distant seed64 and immediate north/current65 produces exactly this difference. `cacheBottomRow` currently discards internal chunk rows; the rendering loop carries the distant region seed through missing chunks.

## Tasks

- [x] Add failing manager and renderer regressions for immediate north rows, partial order, missing gaps, real slopes and negative coordinates. Run targeted JUnit and retain the failing report.
- [x] Cache each actual bottom row, use immediate adjacency for each render chunk, break unknown gaps, and retain only true south-edge seam data. Run the targeted tests again.
- [x] Add upgrade tests for old square/seam caches, same-version retention, explicit clear and failed migration retry. Observe failure before raising renderer revision and implementing derived-cache invalidation.
- [x] Run the complete JUnit suite and `releaseMod -PfixIndex=9`. Review the final diff independently.
- [x] Install the exact JARs in the existing production NeoForge21.1.265 test server. Repaint the affected area in a copy, inspect pixel heights and browser view, repeat partial rendering/restart, and confirm original world hashes remain unchanged.
- [x] Package fix_9 with dependencies, write the handoff and hashes, commit only task files and push `1.21.1-neoforge`. Verify remote SHA.

## Additional confirmed seam work

The second user screenshot extended this repair to prediction/actual centre alignment, native DEM resolution, cross-tile north halo and prediction disk/browser revision lifecycle. These paths have failing regressions before their changes. Native verification also exposed square writes missing the browser refresh epoch; the one-line fix has its own RED regression and is included in the final full suite.
