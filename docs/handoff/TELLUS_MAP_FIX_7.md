# Tellus map repair: fix_7

Three independent faults could leave a Tellus market map blank:

1. An old Tellus dense packed position read by a different coordinate profile produced incorrect market/core XYZ. Legacy recovery now requires a matching physical block entity, explicit coordinates, and owner/nation/town identity. A competing block entity or unknown disk result prevents recovery. It never generates terrain to validate a candidate.
2. AutoNavi returned HTTP 200 with a flat placeholder image at zoom 0 and at zoom 10+ around the supplied save's Greek location. Its source policy now requests zoom 1 or higher and caps areas outside the existing GCJ coverage rectangle at zoom 9. Domestic requests retain the previous zoom 18 limit. The rectangle is a conservative existing coverage heuristic, not a precise country-border polygon.
3. The terrain layer's supposed transparent error GIF was an opaque white pixel, stretched over the basemap. The replacement has alpha zero; a regression test decodes its transparency and actual pixel index.

## Persistence and links

Market records and nation/town SavedData carry independent XYZ alongside the native long. Unknown legacy records retain their original long without acquiring authoritative XYZ during unrelated saves. Original portable coordinates, including heights that the current runtime cannot pack losslessly, survive save/reload. Market registry keys use full XYZ so heights separated by 4096 cannot overwrite each other.

Verified market recovery preserves old market links and initial map focus. Unknown links return not-found without generating Earth chunks. This repair concerns market locations and nation/town map cores. It does not convert every vanilla, third-party, route, warehouse, or transport-network packed-position field in an entire world; keep the Tellus version and coordinate profile appropriate for the world.

## Verification, 2026-10-29

- `gradlew.bat test releaseMod --offline -x downloadAssets --console=plain -PfixIndex=7`: **367 suites, 1,685 tests, zero failures/errors/skips**.
- `node --test tools/test_tellus_basemap.js tools/test_map_session.js tools/test_marketweb_motion.js tools/test_zh_tw.js tools/test_logistics_render.js`: **77 passed**.
- Production NeoForge **21.1.265**, Java 21, the packaged main/addon JARs, Tellus 0.8.3, GeckoLib 4.9.3, LDLib2 2.2.42, and TellusMinimap were used with copies of the supplied world.
- The existing fix_6 JARs reproduced the incorrect API coordinates when the old dense profile was disabled in a control copy. The repaired code recovered the market and three cores using real block-entity evidence. Saved XYZ and old links remained correct after shutdown/restart.
- The final fix_7 JARs were also tested with a fresh copy using the world's original dense profile. Market coordinates, default focus, warehouse binding, real basemap image draws, and the browser's visible map were correct.
- Browser checks covered the default 85% view, 166% close view, and a 4% distant view where AutoNavi coastlines, roads, labels, and the market marker were visible. DOM draw counts matched successful image crops. Coordinate/load warning and stale-response cases have automated coverage.
- An unknown distant market URL returned HTTP 404 immediately; an old recovered market URL returned HTTP 200 and the correct snapshot focus.
- All **84 original world files** matched their initial SHA-256 hashes. The world, logs, test account databases, runtime libraries, and screenshots were not added to Git.
- Independent review findings concerning uncertain persistence, high Y values, stale focus, and registry-key collisions were fixed and covered by regressions.

Local evidence is under ignored `build/map-world-test-20261029/`. Checked installation files are under `artifacts/latest-fix_7/`, with `SHA256SUMS.txt` and installation instructions. Main and addon ZIP CRC checks and installed/build artifact hashes agree.

## Install

Replace the main SailboatMod JAR and the server-only marketweb addon together. Keep one version of each; GeckoLib and LDLib2 remain required. Default web port remains **11450**. The isolated regression server used loopback port 11459. Disabling the dense profile was a test control and is not an installation instruction. No remote production deployment was performed.
