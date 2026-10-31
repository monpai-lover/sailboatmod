# Tellus routing pipeline and recoverable task queue

## Findings from the 195596 ms / 35588-node log

Prediction was already active (`predicted=3579`), so this was not a complete absence of Tellus support.
The <8192 block path used the legacy dense coarse ladder (24/12/8/4), with full-column prediction,
a shared 60-second prediction deadline and non-streaming unknown-to-land cache behaviour.
Lowest-f-only bidirectional selection could leave the reverse frontier unchanged for thousands of nodes.
`RUNNING` with `reason=NONE` was the external wall-clock cutoff, not proof that no route exists.

## Routing changes

* Every Tellus prediction route enters the hierarchical pipeline, regardless of distance; no new Tellus
  path falls back to vanilla noise. Native Tellus surface/LOD sampling is used for coarse guidance when
  the API is available; fine segments and final validation use real NBT first and generator columns second.
* Saved terrain has READY / ABSENT / UNAVAILABLE states. Failed or partial NBT cannot be mistaken for
  absence and replaced by predicted ocean. Pending prediction is retryable, with bounded backoff and caches.
* Streaming fine search checks the complete 2x2 same-height footprint. It no longer invents a water radius
  around berths. Grid attachment edges are checked rather than appended unchecked.
* Both search directions receive service after at most eight consecutive expansions of the other side.
  Stale priority-queue nodes are discarded; a waiting node is not charged as an expansion.
* Fine work is split at 2048 blocks and can retry smaller grids within one bounded stage budget. Search and
  data-wait time have separate monotonic budgets; exhausted waiting returns TERRAIN_NOT_READY, not NO_WATER_PATH.
* Final verification retains the same real-first map and checks every segment. Optional smoothing can fall
  back to a verified raw path. A data wait preserves the successful search instead of throwing it away.
* Periodic UI/log progress includes stage, grid step and expanded nodes. Custom tasks are called background
  routes rather than misleadingly calling every planner a three-segment planner.

## Task UI

The server owns stable UUIDs and a bounded per-player ledger (four active, sixteen recent). Closing a
selection window does not discard ownership or progress. A task-queue button exists both in the dock
route page and in the automatic-route dialog. Reopening queries the server; cancelling uses the recorded
UUID and an explicitly bound task object, never another player's task with the same endpoint key.
Late success cannot overwrite a cancelled entry. Slow replies cannot reopen a closed window or replace
another query. Progress bars bind to their own source/target pair when multiple jobs exist.

This is **server-session retention across UI closure**, not persistence of running searches across a
server restart. Logout/dimension changes still cancel workers and release tickets; cancelled history can
be read after reconnect until server shutdown. No search iterator or world object is serialized.

## Deployment / testing

Protocol is 19: update the main mod on both client and server. The marketweb addon, HTTP API and frontend
are unchanged. This does not provide an in-game test of the user's actual Tellus save or prove two ports
are connected. CI tests include the earlier chat/flags/port tests plus planner, streaming terrain,
monotonic budgets, ownership, history and packet roundtrips. A local Java harness uses minimal Minecraft
value/logging stubs only and is not equivalent to the real NeoForge build; CI results are reported separately.
