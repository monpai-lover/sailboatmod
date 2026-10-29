# Map overlay alignment: fix_8

Leaflet uses CSS pixels per world block. The overlay canvas stores more pixels on a scaled display, but the previous projection treated the same zoom as backing pixels per block. At an effective display ratio of 1.5, every overlay point was compressed toward the map centre to two thirds of the correct distance. Dragging and static-cache translation reused the same incorrect units.

The map now records the actual backing/CSS ratio separately for X and Y during resize. World projection, its inverse, territory outlines, market labels, route hit distances, hover highlights and cached-image translation share that conversion. Pointer dragging remains in CSS units; cursor-anchored zoom keeps its world point fixed. Resizing invalidates geometry even when the minimum backing dimensions stay unchanged.

The static cache also renders its full padding area, so a marker or claim can enter the viewport during a drag without disappearing or waiting for a rebuild. Camera interaction clears pointer hover and its DOM tooltip while retaining the selected world object. Drag coordinate readouts use the centre updated by that same event.

## Validation, 2026-10-29

- Independently checked the original claims, physical cores and generated terrain pixels. Their world coordinates were correct and were not changed.
- Added 15 projection regressions using a separate Leaflet CRS.Simple oracle. They cover actual saved claim bounds, multiple view centres, zoom levels, display ratios, rounded and unequal backing scales, small viewports, inverse transforms, clicks, readouts, dragging, wheel anchoring, hover, padding-cache reuse and resize. Running the original fix_7 map with the first 12 regressions produced 12 failures.
- `gradlew.bat test releaseMod --offline -x downloadAssets --console=plain -PfixIndex=8`: **368 suites, 1,686 tests, zero failures/errors/skips**. The new projection tests run through the regular JUnit task.
- Six frontend suites: **92 Node tests passed**, including the 15 projection cases.
- Tested the exact packaged JARs on production NeoForge 21.1.265, with Tellus 0.8.3 and a copy of the supplied world. At the browser's 1.5 backing/CSS ratio, the east claim aligned with the shore; clicking it selected **Dev Town 2, chunk 1761,-3038**. Repeated dragging and zooming from 85% to 133% preserved alignment. Opening the details panel and testing a 390x844 viewport exercised resizing and unequal effective ratios. The viewport override was reset afterwards.
- Independent source review found no remaining required changes. All 84 original world files retained their initial SHA-256 hashes.

Evidence and screenshots remain under ignored `build/map-alignment-test-20261029/`. Installation files, screenshots and checksums are under `artifacts/latest-fix_8/`.

## Install

This fix changes the marketweb addon; the packaged fix_8 main JAR is byte-identical to fix_7. A server already on the fix_7 main mod can replace only its marketweb addon with fix_8, then restart and refresh the website. For older installations, update the main mod and addon together. Keep one version of each. No remote production deployment was performed.
