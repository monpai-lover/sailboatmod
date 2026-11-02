# Saved ProtoChunk water routing / retry repair

Base: 3378bb8bdf81d906c5051e6cb5b1246c479ecef3. Preserve the current global offshore postprocessor, UI task queue and protocol 19.

## Observed files
The user supplied r.55.-86.mca and r.63.-98.mca. All 1725 present chunks decompressed/parsed; statuses were FULL=1183, initialize_light=50, carvers=44, biomes=40, structure_starts=408. The repeatedly logged positions are (1776,-2752): initialize_light and (2016,-3120): carvers. Both contain 256 navigable surface columns at block Y=64 (boat Y=65). They are neither corrupt nor absent. FULL-only decoding turned these stable states into repeatable UNAVAILABLE results.

Only five terrain-only test snapshots are published. They contain generation status, x/z/y, block palettes/properties and packed block arrays. No block entities, inventory, entities, nation/player IDs, scheduled ticks or structure metadata. The original region files are not published or modified.

## Policy
- Loaded, budgeted live snapshots win. Existing FULL NBT is authoritative.
- SURFACE/CARVERS/FEATURES/INITIALIZE_LIGHT/LIGHT/SPAWN with structurally valid, complete terrain columns are readable provisional saved terrain. Actual saved land is not replaced by predicted sea.
- EMPTY/STRUCTURE_STARTS/STRUCTURE_REFERENCES/BIOMES permit prediction only when their saved palettes are all air (or biome-only). This is PRE_TERRAIN, not ABSENT.
- NOISE, unknown status, malformed packed arrays, IO/copy/snapshot errors remain unknown; no unsafe ocean fallback.
- These are planning snapshots, not a promise that later world generation/features can never change a coast. Existing live lookahead and strict route checks remain necessary. No chunks are forced/generated or their Status changed by this fix.
- Coarse, detailed and berth reads use the same classification. Existing shared OfflineChunkPalette.decode remains FULL-only; an additive read-only terrain view is used only by validated routing inputs. The WebMarket add-on does not need rebuilding.

## Async and caching
The production request is a retained async chain: budgeted loaded snapshot -> private dependent of vanilla disk future -> budgeted owned copy -> decode worker. No route-loop get/join. Cancel stops owned futures without cancelling a shared vanilla read. Legacy worker callers decode on their existing worker to avoid recursively waiting on the same bounded pool.

Cooling cache entries and unconsumed failed completions cannot be evicted to bypass backoff. Persistent errors back off 250ms..8s. At capacity the cache throttles, rather than continually resubmitting. Local actual-class regression: 4097 keys x3 scans at fixed clock submit 4096 times (previously 12291); one failing key over simulated 150 seconds submits 23 times (previously 600).

Diagnostics include status/phase totals and last failure coordinate, separating IO_TIMEOUT, SNAPSHOT_TIMEOUT, COPY_TIMEOUT, decode and unknown-status cases. stage=LOD continues to name the algorithm stage; actual LOD requests may correctly be zero when saved terrain covers all sampled positions.

## Validation boundaries
CI is configured to run focused existing route/terrain/chat regressions, new actual-fixture and cooldown tests, full project compilation and packaging. The native test uses real pinned Tellus with five uploaded terrain snapshots overlaid; it is not the complete user's region directory nor a multiplayer server tick/GC/driver test. Consult the archived XML and native results for actual pass/fail outcomes, not just this plan.
