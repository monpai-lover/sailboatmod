# Aegean / destination-structure regression: nonblocking terrain and local coast refinement

## Why the 113155ms / step=128 / nodes=0 request stalled

The old pathfinder rolled a node and both scheduling counters back when *any* neighbour was unknown.
It retried the same first neighbour and never visited the other seven, or the reverse frontier. The
planner also performed blocking NBT/native reads inside the interval charged as search time. The
printed map counters belonged to the unused detailed map, not the active LOD coarse source.

## Changes

- Unknown direction bits are retained separately from the completed node. Retry work is bounded,
  does not recount expansions, and does not prevent known neighbours/opposite-front progress.
- Request-owned, bounded future caches retain pending NBT, native-LOD and generator-column requests.
  A* polls with `getNow`, never `get`/`join`. IO/native calls use the bounded PortIO pool. Ready land
  remains authoritative; failures and unavailable partial NBT are not replaced by predicted ocean.
- Coarse LOD no longer depends on completion of a full-resolution 512x512 prefetch. Its native
  surface sampler fetches its own data on IO workers. Fine prediction retains the accurate column path.
- Cache capacity, pending counts, retry backoff and cancellation are bounded. Cancelling an IO future
  now also interrupts/removes its underlying executor task; native code may still defer interruption.
- Coarse coastal edges refine locally down to 4 blocks on *globally aligned nested grids*. Open water
  rejoins the coarse lattice. This avoids both missing the Dardanelles and creating many shifted grids.
  The original 32000 coarse-node cap is unchanged. Fine search remains strict 2x2 with final verification.
- Grid endpoints are sampled at their own positions, not relabelled copies of harbour water. Real
  surface heights are preserved. Simplification removes exactly collinear points only, not merely
  similarly signed directions (which can cut across land after adaptive steps).
- Every 10 seconds and on failure, logs identify NBT/LOD/GENERATOR source counters, in-flight requests,
  retries, queued/throttled polls, last coordinates and pending search edges. UI and task queue remain.

## Fixtures / verification

`junshi-surface.txt` is a minimal water/land/unknown surface extraction of the supplied 102x12x141
structure, not the user's full world. It retains only block-column classification and local water
height. The original file and its 55 entities, owner UUIDs, country data and inventories are NOT
committed. The dock at local (73,3,100) has zone X[-5,23], Z[-9,36]. The reported berth maps to (73,2,108)
when aligned with the dock in the log. Its strict 2x2 water component reaches the cropped boundary;
unknown space outside the structure is not assumed to be either water or land.

`aegean-gshhs-i.rle` is a real, simplified GSHHS intermediate coastline raster from Crete's north coast
to Istanbul, including the Aegean, Dardanelles and Marmara. Test Mercator scale is 100m/block; sample grid
is 4 blocks (~400m projected). It is NOT Tellus data, NOT a navigation chart, and NOT an online-world test.
The warm deterministic fixture passes coarse planning plus 39 detailed segments. Every resulting segment
is checked at half-block intervals against a strict 2x2 footprint, not just at waypoint vertices.

JUnit also exercises persistent unknown neighbours, resumption, reverse-front fairness, bounded/reused
futures, rejection/backoff/cancellation, and actual production async wiring. CI publishes the real
JUnit XML, checked-source archive, path CSV and resulting JAR. Local stub runs are supplementary only.

## Deployment / limits

Protocol remains 19. Preserve all earlier task-queue, flag-preview and chat changes. Update the main
JAR (server, and preferably matching clients); the independent marketweb addon and HTTP API are unchanged.
This is not a live Tellus-cache/network, macOS GPU, or multiplayer acceptance test. A third-party data
source that never returns still produces a bounded TERRAIN_NOT_READY failure, not fabricated navigability.
