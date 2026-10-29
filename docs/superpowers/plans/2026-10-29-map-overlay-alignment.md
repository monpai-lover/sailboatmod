# Map overlay alignment repair

**Goal:** Align territory, market and logistics overlays with Leaflet terrain at browser/display scales above 100%, without changing saved world geometry.

**Cause:** Leaflet uses CSS pixels per block, while the overlay's backing canvas is larger than its CSS rectangle. The old overlay projection used CSS zoom as backing-pixel zoom, shrinking all world deltas toward the centre by the display ratio.

**Approach:** Keep the existing CSS zoom contract and backing-pixel drawing. Capture separate effective X/Y backing-to-CSS ratios during resize, and consistently use them in world/screen transforms, inverse transforms, dragging, cursor-anchored zoom, static-cache offsets and territory highlights. Retain independent sizing and minimum-backing behavior.

- [x] Verify the supplied world claims and existing terrain pixels; neither needs migration.
- [x] Reproduce the offset with the actual map code against an independent Leaflet CRS.Simple oracle.
- [x] Add projection regressions and integrate them into the normal JUnit/Gradle test task.
- [x] Repair the shared projection and every dependent input/cache path, including padding culling and stale pointer tooltips during camera interaction.
- [x] Build fix_8, test its actual JARs with a world copy, and compare rendered territory edges during drag, zoom and resizing.
- [x] Review and package only relevant source, tests and documentation; source publication is recorded in Git history.

Validation must compare the overlay with independently projected terrain points. A forward/inverse round trip alone is insufficient because both directions can share the same error. Include the two saved claims, fractional canvas dimensions, unequal X/Y ratios, CSS resize without backing-size change, drag/cache reuse, hit tests, pointer coordinates and wheel anchoring. Preserve the original world and keep test copies, logs and screenshots out of Git.
