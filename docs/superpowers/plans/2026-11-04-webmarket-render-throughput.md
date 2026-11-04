# WebMarket render throughput implementation plan

> **For agentic workers:** Use subagent-driven development with disjoint files. Root alone runs Gradle, timed benchmarks and production-server launches.

**Goal:** Reduce repeated PNG work and time-to-visible without increasing server tick limits.

**Architecture:** Keep existing snapshots, atomic PNG persistence, per-tile journal and single image writer. Add a bounded exact-byte-validated decoded cache, local memory PNG codec, a short work/flush cadence, and an independent visible terrain poll.

**Tech Stack:** Java 21, NeoForge 1.21.1, ImageIO, JUnit, existing Leaflet/vanilla JS and Node tests.

## Codec and measurements (root)

- [x] Measure current PNG/default compression versus memory streams and lower compression on actual saved tiles.
- [x] Record a production-path baseline including cold/warm reads and 32 consecutive partial updates.
- [x] Add an actual failing no-temporary-directory regression before implementing memory streams.
- [x] Implement and verify `MarketWebMapPngCodec` and Tellus PNG integration; preserve default bytes.
- [x] Compare the same fixture/benchmark after implementation, recording medians, allocation and exact pixel witnesses.

## Decoded pixels (cache worker)

- [x] Add behavior regressions for bounded LRU, world paths, exact bytes, mutable inputs, external replacement and zero-encode writes.
- [x] Implement `MarketWebMapDecodedTileCache` and integrate `MarketWebMapTileCache` with the codec.
- [x] Verify PNG/zoom failure recovery still preserves retry work.

## Backend cadence (scheduler worker)

- [x] Test and implement saved-local-chunk cursors.
- [x] Test and implement five-tick work/partial flush cadence independently of checkpoint timing.
- [x] Add bounded loaded task selection during fullrender, preserving claims, total budget and recovery references.

## Visible latency and references (frontend worker)

- [x] Test and implement the independent two-second visible terrain feed poll, visibility wake and teardown.
- [x] Record exact Squaremap, BlueMap, Dynmap and Pl3xMap source references and licenses.

## Integration (root)

- [x] Independently review cache, cadence and browser changes for bounded resources and race conditions.
- [x] Run full Gradle/Node checks; build fix_15 and verify the actual JAR and isolated production-server behavior.
- [x] Package the server addon, measured results, config/commands and hashes; verify ZIP contents.
- [x] Commit scoped files, push non-force to `1.21.1-neoforge`, and verify remote SHA.
