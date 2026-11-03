# WebMarket map performance and suspension

The user approved proceeding directly with the optimizations proposed in the 2026-11-04 audit. The supplied addon SHA256 is `57c43e45691f2c057ef1a6984372659b504e409d45d916eed01fddc52a094aac`; its relevant classes and frontend match `0a789b29`.

## Required behavior

- Browsing a cached terrain image performs no world scan or server-thread round trip. Missing images may request bounded, deduplicated, cooled-down discovery; distant overview requests must not enqueue hundreds of thousands of chunks just to view the map.
- A persistent, addon-owned map update switch pauses new terrain sampling, job advancement, discovery, automatic repair, watcher draining and Tellus prediction. Cached map HTTP reads and market operations remain available. In-flight atomic image work may finish safely; pause must neither delete tiles nor lose work needed on resume.
- All server-thread map sampling and NBT decoding share one monotonic nanosecond budget per actual server tick. Loaded chunks and north-neighbor samples count too. Budget exhaustion defers work without marking real terrain missing. An individual atomic capture can exceed the soft deadline; subsequent captures must stop.
- Completed unchanged chunks have refresh cooldowns. Invalidated/dirty chunks bypass stale-unchanged suppression. There is no forced chunk generation.
- Terrain image cache keys are independent of territory/market/airport selection. A bounded change journal permits refreshing changed images only, with safe bounded visible-tile revalidation when history is insufficient. Conditional HTTP caching remains correct across updates.
- Render/state IO queues are bounded or coalesced; disk serialization is outside server-tick execution and dirty-queue monitors. Persistence preserves recoverable progress and does not touch economy/world data.
- Addon settings live in `sailboatmod-marketweb-common.toml`, so the replacement addon does not require newly added methods in the main mod. Defaults favor server tick time: 2 ms soft budget, 8 captures per tick, 4 probe starts, 32 in-flight probes, 1 render worker, 30 s unchanged-loaded cooldown, 60 s discovery cooldown.

## Ownership and interfaces

- Root: `MarketWebMapSettings`, addon config registration, `MarketWebCommands`, `MarketWebEvents`, Tellus suspension, public metadata caching if feasible, integration, native/server verification, packaging and GitHub push.
- HTTP/cache worker: `MarketWebServer`, `MarketWebService` only map HTTP methods, `MarketWebMapTileCache`, new bounded tile-change journal, `marketweb/map.js`, focused HTTP/JS tests.
- Scheduler worker: `MarketWebMapRenderService`, `MarketWebMapRenderManager`, render queue and new tick-budget/cooldown helpers, focused scheduler tests.
- Persistence worker: new coalescing `MarketWebMapStateWriter`, dirty queue save lock boundaries, focused persistence tests. Coordinate integration call sites with scheduler worker; do not edit its files concurrently.

Shared settings API, all static in `market.web.map.MarketWebMapSettings`:

`updatesEnabled()`, `setUpdatesEnabled(boolean)`, `mainThreadBudgetNanos()`, `maxSnapshotsPerTick()`, `maxProbeStartsPerTick()`, `maxInflightProbes()`, `renderThreads()`, `loadedRefreshCooldownMillis()`, `browserDiscoveryCooldownMillis()`.

## Validation and exclusions

Behavioral regression tests must cover cache hit without discovery, bounded overview misses, pause/resume, shared budget with a fake monotonic clock, cooldown invalidation, bounded change history, conditional cache correctness, concurrent/coalesced saves and retained pending work. Test the final production addon and preserve its dependencies and existing cache compatibility. Record native/game/server tests separately from static checks.

The latest parent has two known source-guard failures: Windows CRLF in the helm guard and a stale `service.mapTile` assertion. Only the map-related guard may be updated as part of its actual HTTP changes. No unrelated market/economy/air/logistics changes, no cache clearing and no production deployment.
