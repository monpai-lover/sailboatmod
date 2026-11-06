# WebMarket rendering throughput and visible latency

The user requested a second optimization pass after fix_14, explicitly asking to consult existing map projects. Continue the authorized performance work without changing map pixels or increasing the server sampling budget.

## Evidence and choice

At `9e811112`, the actual saved-world PNG probe created one ImageIO temporary file per encode and decode. Local memory streams removed these files and improved the measured codec path while preserving default PNG bytes. Lower compression made a coarse real tile roughly three times larger, so retain default compression. A production-path baseline for 32 one-chunk updates of the same real region took about 3.2 seconds and allocated about 1 GiB on this machine; keep raw measurements and pixel witnesses.

Choose bounded decoded-pixel reuse, local memory PNG streams and removal of unnecessary scheduling/visibility waits. Lower compression is deferred because of transfer/storage costs. Parallel parent writers and fullrender prefetch are deferred because they require ordered shared-parent updates and more extensive recovery changes. Reference mechanisms are independently implemented; source links and licenses are recorded separately.

## Required behavior

- PNG encoding and decoding use local memory streams, preserve alpha/hidden RGB and existing compression defaults, and never change global ImageIO settings. Reject unexpected dimensions before decoding the raster.
- Reuse up to 32 MiB of decoded ARGB and compressed bytes across short-lived cache handles. Use normalized world paths and exact current file bytes for validation; timestamps alone are insufficient. Return defensive copies. Unchanged direct writes can skip PNG encoding only when both current file bytes and decoded pixels match. Failed writes must not warm uncommitted pixels.
- Periodic region scan cursors use the already-discovered local chunk list. Sparse saved regions must not expand to 1024 candidate chunks. Stopping still retains conservative region recovery records.
- Advance non-fullrender work and inspect aged partial regions every five ticks. A small ready batch should not wait for unrelated global queues to empty. Keep per-region flush spacing and existing render admission/backpressure. Keep JSON checkpoint frequency separate from the faster work cadence and force final recovery persistence on shutdown.
- While fullrender has remaining tick budget, allow at most two ordinary loaded tasks without traversing the entire queue. All work continues to share the existing 2 ms soft deadline, eight captures and four probe starts.
- The visible map polls its bounded terrain change feed every two seconds independently of 30-second business metadata refresh. No requests while hidden/unmounted; visibility restoration polls immediately. Preserve one in-flight owner, stale session/dimension protection and 64-image refresh batches.

## Validation

Use real file-backed failures and exact pixel/byte witnesses. Compare the same benchmark and fixture before/after with warmup, medians, allocation counts and PNG sizes; distinguish cold decoded caches from warmed repeated updates. Verify external same-mtime rewrites, deletion, world isolation, memory eviction, mutable callers, unchanged zero-encode behavior, PNG and zoom failure retry, bounded loaded selection, short flush cadence, checkpoints and browser lifecycle. Run the full Gradle and Node suites, inspect the addon archive, then perform isolated production-server smoke with the actual release JAR. No production-world writes or claims of measured production TPS gain.
