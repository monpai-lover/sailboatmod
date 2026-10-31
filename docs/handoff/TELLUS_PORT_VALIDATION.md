# Tellus port candidate and berth validation

Extends PR #4; retains the earlier flag-preview fix. No save-format or network-payload changes.

## Runtime behaviour

- Only actual Tellus dimensions enable the new candidate refinement and asynchronous formal endpoint phase. Other worlds and post stations retain their prior behaviour.
- Permission/terminal checks still run first. The existing candidate packet is sent immediately with `[地形待验证]` rows. The background result marks `[泊位已验证]` (real terrain) or `[Tellus预检]`, or removes a definitively invalid port. These labels decorate packet copies, not persistent dock names; the existing 64 UTF-16-unit name limit is respected.
- Preview priority: captured loaded blocks (including unsaved edits) > existing FULL NBT > Tellus native surface/LOD sampling, **only after a successful disk read confirms absence**. IO failure, partial NBT, unavailable land mask, exhausted budgets and incompatible Tellus APIs remain pending, not land.
- The validator checks berth positions within the parking zone, a same-height 2x2 footprint, dock-core exclusion, and a connected local exit reaching 8 blocks away. This is NOT a proof of inter-port connectivity or open-ocean access. Formal pathfinding remains necessary.
- Formal Tellus submissions resolve both endpoints within the existing cancellable route task, using generator-column prediction (not only coarse LOD) when actual terrain is absent. Missing endpoint terrain is retried for up to 10 seconds, then reported as `TERRAIN_NOT_READY`; no route is saved. Valid endpoints continue through the existing full planner. Real port snapshots seed both ordinary/far detail maps and the far coarse layer.
- Completed routes recheck endpoint snapshots and diplomacy before persistence. Candidate replies recheck permissions. New requests invalidate stale replies; closed/unrelated client screens are not reopened. The UI has a retry button and keeps selection/mode on updates.

## Bounds and thread ownership

- Preview jobs: 2 daemon workers, queue 8, 1 request/player/second, 4-second cooperative sample budget. First 64 destination ports are examined; additional rows remain pending. At most 128 loaded chunks and 128 disk reads per probe. Search radius is clipped to 32 blocks around a dock; a truncated negative result remains pending.
- Snapshot capture uses getChunkNow on the server thread and the existing heightmap-based LoadedChunkWaterColumns helper. Terrain probing never force-generates a chunk and never schedules a global world save.
- Unloaded chunk reads use chunkMap.read. A present tag is copied on the server thread before worker decoding, or replaced with a freshly loaded chunk snapshot if it became loaded. Exceptions and timeouts are not interpreted as absent NBT.
- Tellus prefetch futures are checked before sampling. Budgets are cooperative: a synchronous third-party native sampling call already in progress cannot be given a hard execution-time guarantee. Queue/worker bounds prevent unbounded parallelism. Server stop cancels pending preview requests and shuts down their pool.
- **Existing distant-dock metadata lookup in RequestAutoRouteDocksPacket still uses getBlockEntity.** This change eliminates additional synchronous terrain/berth probing; it does not claim to eliminate every possible chunk-load stall or repair vehicle movement packets.

## Validation

Behavioural tests cover live/NBT/prediction precedence, IO-unknown vs truly absent, dry ports, unknown data, dock-on-land/water-next-to-it, 1-/2-block channels, enclosed pools, local exit uncertainty, negative coordinates, truncation/timeout, label size and non-mutation. Wiring-contract tests cover the native preview/detailed predictor paths, no-force/no-save terrain probe, planner seeding, and client late-update handling. These are not a macOS GPU test or a real Tellus-world playtest.

Manual acceptance: update client and server together; open the source dock at 28246,66,-43642 and verify the Constantinople candidate. Test first with cold Tellus data, retry, then create a full route. Modify water into land / dig a canal without saving and verify live blocks win. Close the dialog during a check; it must stay closed. Recheck the earlier nation/town flag preview.
