# Shared map resource guards (mapguard_1)

This patch belongs to the **main SailboatMod**, not the optional MarketWeb addon.
Minecraft 1.21.1 / NeoForge 21.1.259 / Java 21. Network protocol **24**: update the
server and every client together. Keep only one main SailboatMod JAR in each mods
folder. The existing MarketWeb addon is not rebuilt by this task.

## Limits and behavior

- Prediction tiles are sent in 16 strips of 256 x 16 pixels. A patch has at most
  4096 integer pixels (16 KiB pixel data, plus headers). Geometry/count validation
  precedes decode allocation. Defensive copying is retained but bounded. The
  client merges strips into one underlay without replacing actual terrain.
- Published prediction rasters are shared as immutable references, not cloned for
  each subscriber. The prediction cache holds at most 64 tiles (16 MiB pixel
  arrays), expiring after 1200 inactive ticks. Completed results and worker queues
  are bounded too; full prediction merge/scans execute on the prediction worker.
- The shared terrain outbox holds at most 512 transfers (128 per player), charges
  at most 16 MiB globally / 4 MiB per player, and expires transfers after 200 ticks.
  These byte charges are conservative, even when players share an array. Per tick
  it schedules at most 256 KiB globally / 64 KiB per player including a conservative
  header allowance, with packet-count limits. Unwritable channels pause without losing the cursor.
  Transport writes are additionally capped at4 per player /64 globally, released
  on write success/failure or connection close; normal completion removes its
  close listener. This is bounded transport admission, not delivery ACK proof.
- Terrain sampling shares8 advancement slots,8 changed-chunk slots and one2ms
  cooperative deadline across all dimension callbacks in a server tick.
- Terrain work is capped at 256 tiles globally and 128 subscriptions per player.
  Same-player/world/tile retries within 100 ticks do not reset the cursor or resend
  cached pixels. Inactive/offline/dimension-changed subscriptions are removed.
- Network-safe admission runs before main-thread scheduling. Terrain admission is
  40 requests/sec with burst 16 per player; preload admission is 2/sec with burst 4.
  Both share a 256/sec, burst-64 gate. The pending game-thread mailbox is additionally
  capped at 128 total / 16 per player, including during a stalled server tick.
- Legacy viewport selection is capped at 64 tiles, force selection at 4096 chunks,
  route-node lists at 1024 entries. Bounds use long arithmetic. Client requests may
  not impersonate the server-only BUILT_ROAD_REFRESH purpose. Explicit force-render
  now requires operator permission level 2; it can still intentionally generate
  chunks, unlike ordinary map viewing.
- Preload jobs are capped at 2 per player / 32 total. The key includes player,
  session, and purpose; duplicate request IDs are ignored. Built-road refreshes
  coalesce into a stable per-player/dimension job. Expiration is 12000 ticks;
  cancellation, disconnect and dimension changes release retained work/tickets.
- Preload raster capture uses a resumable color buffer, at most 512 columns per
  server tick with a 2 ms cooperative time slice, instead of a full-tile synchronous
  future join. Chunk lookup uses getChunkNow. This is cooperative budgeting between
  operations, not preemption or a hard upper bound on a single modded block callback.

## Validation

Run `./gradlew test jar -PfixIndex=mapguard_1 -PneoForge.disableRecompilation=true`.
Tests include wire validation/round trips, strip reconstruction, duplicate cursor
preservation, retained-byte/packet budgets, queue expiry, failed recipients,
parallel rate admission, a stalled-main-thread mailbox, per-player job isolation,
selection overflow, and incremental raster deadlines.

Unit/build checks do not constitute a live dedicated-server soak test with the
user's world, Tellus, other mods, real clients, or a slow network. Back up the world
and keep the previous main-mod JAR for rollback before deployment.

## Combined airport integration

Protocol24 retains the existing122 payload order, with airport planning at ordinals120/121.
The bounded client strip entry rejects more than4096 pixels before allocating its
underlay. Existing local full-underlay helpers remain available through their
original `setPredictionPixels` path; full-tile network patches remain rejected.
The immutable PR6 head and user ZIP overlap were reviewed separately and deduplicated;
see `build/shared-map-stream-hardening-integration/DEDUPE-AND-REVIEW.md` for exact
inputs, choices, failures and subsequent checks. Unit checks do not establish live
slow-client behavior or a complete world-generation latency guarantee.
