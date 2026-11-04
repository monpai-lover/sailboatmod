# WebMarket render benchmark

Run from the repository with Java 21 and an existing compiled runtime classpath:

```powershell
./tools/benchmark_webmap_render/Run-MapRenderBenchmark.ps1 -Run -Warmup 2 -Iterations 5
./tools/benchmark_webmap_render/Run-MapRenderBenchmark.ps1 -Run -FixtureCache 'F:/Codex/sailboatmod/run-neoforge/saves/world/data/sailboatmod_market_web_map' -RegionX 55 -RegionZ -95 -Warmup 2 -Iterations 5 -MaxMilliseconds 60000
```

Run `./gradlew test` first. By default the script reads Gradle's generated test dependencies and mapped jars; no machine-specific audit file is required. The optional fixture is read only. All mutations are made in a unique directory under `-OutputRoot`; the tool rejects output inside the fixture. It does not start Minecraft or call Gradle. Without `-Run`, the script only compiles the benchmark. `-BaselineAddonJar` prepends the exact older addon to compare against current core classes. `-RuntimeClasspathFile` can alternatively provide a complete custom classpath.

Scenarios measure chunk rasterization, first full-region save, one-chunk updates, unchanged full/partial regions, missing zoom recovery, and one/five PNG reads. `warm_*` scenarios read the five tiles before timing to distinguish decoded-cache reuse from a new file path. `sequence_32_partial` performs 32 consecutive partial updates of the same region.

CSV contains wall/CPU time, allocated bytes, GC count, PNG count/bytes and SHA256 of every decoded pixel across five zoom levels. Setup, input copying and final pixel/size verification are outside the timed interval. Unchanged operations must emit no tile revision. Image construction is outside save timings; the raster-only scenario includes image/chunk array construction.

For comparison, interleave three independent JVM runs per version with identical input, warmup and settings. Use medians and keep all rows. Page-cache state is warm and not forcibly cleared. Check pixel hashes, PNG count and byte sizes in addition to time; do not extrapolate local codec speed to production TPS. Do not run timed measurements concurrently with builds or a game. The maximum-time limit is checked between operations, never by interrupting an atomic PNG write.

The measured fix_14/fix_15 results are described in `docs/handoff/WEBMARKET_RENDER_SPEED_FIX15.md`; source references are in `docs/handoff/WEBMAP_RENDER_SPEED_REFERENCES_20261104.md`.
