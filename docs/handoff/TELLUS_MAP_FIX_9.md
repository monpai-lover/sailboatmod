# Map tile shading and Tellus seams: fix_9

The bright horizontal line in the supplied screenshot is region-local row 64 in tile `55_-95`, at world `Z=-48576`. The saved terrain on both sides is Y=65. The old partial renderer discarded an internal north chunk's bottom row, then carried a distant region's Y=64 seed through absent chunks. It therefore shaded a flat row as a +1 slope: grass `#6D8D42` instead of `#597436`.

The renderer now caches every actual chunk bottom row and compares only immediately adjacent samples. Unknown gaps break the comparison; explicit missing observations invalidate both earlier snapshots and cached rows. Region seam files contain true south-edge heights. Incremental updates preserve other known columns, and the single writer reads the latest merged seam state when executing each task so delayed submissions cannot restore an older copy.

The second screenshot exposed two independent prediction/actual differences:

- Generated reduced tiles previously selected the first available north-west source pixel (the legacy writer selected the south-east). Tellus selected the pixel centre. Both generated paths now use the same centre, retaining a valid fallback only when a partial render has no centre data.
- Prediction multiplied the DEM resolution by the display pixel spacing. At world scale 100, the same ocean block could change from depth 4 to depth 3 when zooming, crossing a brightness threshold. Prediction now samples native `worldScale` resolution at every zoom. Its initial row also samples the matching northern halo so tile boundaries retain the same slope rule.

Actual renderer revision 6 rebuilds generated square PNGs and seam caches once. Failed cleanup is retried; legacy/client source rules remain intact. Prediction revision 2 uses `tellus/v2/` on disk and a stable revision in browser URLs, including retries. Initial requests receive the new revision before the layer is added; sampler-only upgrades also refresh the prediction layer.

Square PNG changes now advance the browser refresh epoch too. Previously only the older tile writer advanced it, so an already displayed transparent/old square tile could remain after new terrain was written. Byte-identical writes leave the epoch unchanged.

## Data comparison

Read-only comparison used the original supplied Tellus world, world scale 100, height offset/sea level 64, and the same enabled `global_mercator_land_v2` profile as production. One 512x512 zoom-1 tile, `(27,-48)`, took 10.7 seconds to sample at native resolution, including setup/downloads. Across 112,661 actual-water points, water-depth brightness differences decreased from 13,688 to 55 (99.6%). The prediction remains an estimate of shoreline and inland-water features: it does not run the full expensive feature/water resolver for each pixel. Actual generated terrain remains authoritative.

Regression, release and production-world validation evidence is kept under ignored `build/tile-height-test-20261029/`. Source changes affect both the main mod's Tellus compatibility sampler and the server marketweb addon; update both JARs together. GeckoLib 4.9.3 and LDLib2 2.2.42 remain required. No remote production deployment is included.

## Validation, 2026-10-29

- Full `test releaseMod -PfixIndex=9`: **370 suites, 1,718 tests, zero failures/errors/skips**. Six frontend suites: **95 tests passed**.
- Observed the expected failing regressions before each fix: real north-neighbor shading, stale/missing and concurrent seam updates, cache upgrade/retry, centre sampling, native prediction resolution, north halo and browser revision lifecycle.
- Installed the exact release JARs with Tellus 0.8.3 and TellusMinimap on production NeoForge 21.1.265, using a world copy. Simulated the revision-5 upgrade; startup rebuilt derived caches and published actual revision 6 / prediction revision 2. A subsequent real map write advanced the API tile epoch to 139.
- Generated tile `55_-95` row 64 now contains neutral grass `(89,116,54)` and dirt `(102,73,54)` rather than the old erroneous highlighted colours. Real browser inspection at 800% confirmed the horizontal band disappeared. Wide-view inspection checked the prediction/actual join and DOM tile URLs containing `v=2`.
- The original world remains separate. Its 84-file SHA-256 manifest is checked before delivery. Screenshots, source-comparison outputs and native logs remain in the evidence directory; the release folder includes the fixed boundary and wide-view screenshots.
