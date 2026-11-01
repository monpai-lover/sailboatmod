# Verified far-town road autocomplete build

## Identity
- Uploaded input: `0003-fix-road-auto-complete-between-far-towns-no-longer-f.patch`, original commit `e0734fcf260bcd757dae98bb0d3aba2d1f8e747a` by Claude.
- Integrated production commit: `1bc3fc34007a0b7b326d0fcb09e17541da1bfc47`, on the current protocol-21 Tellus far-route branch.
- GitHub Actions run **41310572021**, job **124859884522**, artifact **13084589864**: success for full compilation, targeted regressions, native Tellus compatibility, both JARs and source publication.
- Artifact ZIP independently verified: SHA-256 `f15a128aca7613a49bf23928397a8a35c641893bed66265af12ad03570eae043`.
- Production main JAR: `sailboatmod-1.3.13-fix_road_autocomplete-neoforge-1.21.1.jar`; delivered unchanged except its filename as `sailboatmod-1.3.13-road-autocomplete-latest-neoforge-1.21.1.jar`.
- **27,702,912 bytes**, SHA-256 **`f787b46f8a115cf079122260367ed659f38a3b0557f77580e8b19b7914cdb50d`**. This is a full Gradle build, not an incremental bytecode replacement.

## Changes beyond the supplied patch
See `ROAD_AUTOCOMPLETE_FAR_TOWNS.md` for the detailed source design. The integration retains two bounded background workers and coarse-to-fine road search. It additionally protects delayed main-thread callback identity, removes cancelled queued tasks immediately, and cancels on logout, dimension changes and server stopping. All selectable pathfinders and terrain calls check a request-local cooperative deadline. Failed whole requests do not return a fabricated straight-line success. Coarse edge interiors and terminal connectors are sampled for water cost, and output smoothing may not substantially lengthen a continuous water crossing. Each terrain cache category has a per-request cap; oversized geometry fails instead of silently truncating.

The outer request budget is 45 seconds, search allocation 35 seconds, individual refinement windows 1.5 seconds. Third-party calls cannot be forcibly preempted: this is not a hard real-time or zero-tick-impact guarantee. Partial refinement is explicitly labelled as a draft; generation terrain is not certification of all player edits or construction permissions.

## Downloaded reports, independently counted
- **211 offline JUnit XML files: 1024 tests, 0 failures, 0 errors, 0 skipped.**
- **1 native Tellus JUnit XML file: 6 tests, 0 failures, 0 errors, 0 skipped.**
- **1030 targeted tests total**, not every repository test. The Taipei-Amsterdam mask benchmark is not part of this run.

### Synthetic road stress cases
These are rolling-hill fixtures with an inland sea, not actual Tellus road terrain or an online server benchmark. Segment interiors were checked independently at intervals no greater than two blocks.

| Horizontal endpoint distance | This run's planning time | Refined / total windows | Sampled water hits |
|---|---:|---:|---:|
| 5,000 blocks | 971 ms | 10 / 11 | 0 |
| 7,000 blocks, negative coordinates | 585 ms | 12 / 13 | 0 |
| 20,000 blocks | 1,784 ms | 28 / 35 | 0 |
| 50,000 blocks | 4,061 ms | 39 / 76 | 0 |

Complete guidance does not mean every window was finely solved. These are not claims of globally shortest roads, validated road width, bridge foundations, tunnel excavation, or multiplayer build completion.

### Compatibility
The six actual Tellus 0.8.3 tests cover existing water heights, long water routes, saved-region overrides, delayed terrain and reverse interior reuse. They are not a substitute for a real Tellus long-road integration test. Independent byte comparison against the previous verified far-route source found all **67 water-route source files**, `SailboatEntity.java`, **42 MarketWeb source files** and **24 web resources** unchanged. Protocol 21 and previous route reuse, boat following, offshore optimization, ProtoChunk reading, task queues and chat flags remain present. No test stubs or Minecraft implementation classes were packaged in the main JAR.

## Installation and repository state
Back up, stop both ends and replace the main mod on client and server. Protocol stays **21**. Do not keep multiple main JARs, delete regions, reset orders or erase the route network. Existing road drafts are not automatically recomputed. The independent **sailboatmod-marketweb addon does not need updating**; it was compiled for compatibility but its source/HTTP/DTO interface was unchanged.

No main-branch merge or deployment was performed. The follow-up documentation/CI cleanup removes the temporary source-transfer files and replaces the one-shot publisher with a read-only regression workflow; it does not modify the verified production code.
