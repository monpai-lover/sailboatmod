# Whole-route offshore quality pass

Base: bced316d79f4152e042dfc6ce84469afddd2826e. This adds a global stage after all
hierarchical detailed segments are stitched, before waypoint metadata and route saving.
The old local WaterPathSmoother alone did not optimize across segment seams.

## Pipeline

1. Mandatory exact strict-hull verification of the input (same real-first map).
2. Remove adjacent duplicates, repeated-vertex loops and safely reconnectable geometric crossings.
3. Bidirectional bounded visibility pruning across segment boundaries. Every shortcut
   checks all traversed cells, same water height and a conservative clearance threshold.
4. Add sparse relocation handles to long edges; shift controls away from known shore.
   Default target is eight extra hull-clear cells, maximum candidate shift 24 cells per
   pass (three passes).
   The target is soft: true narrow channels and port approaches remain navigable.
   Length growth for shore clearance is capped at 8% plus four blocks of rounding tolerance.
5. Verified local quadratic corner rounding; retain necessary sharp narrow-channel corners.
6. Clip any newly introduced safely reconnectable crossings; remove only exactly forward
   collinear points. No unconditional point thinning or unverified spline.

Actual start/end positions and water height are immutable. Every edit validates both
replacement edges, not just its moved vertex. Known land overrides Tellus predictions;
unknown cannot authorize a shortcut or create clearance. The optimizer has a bounded
ready-cell cache (262144), sample budget (2M), separate 30s cooperative input/quality wall budgets and checks
cancellation. Optional budget exhaustion retains the last fully checked stage. Failure
of mandatory input validation rejects the output; it cannot fabricate a fallback route.
Mandatory verification batches all missing input cells through the bounded asynchronous
terrain source, retaining completed cells between polls. It does not serialize a long
edge behind a fixed 1.5-second first-missing-cell retry loop.
The route worker may wait briefly for request-owned terrain futures; the tick thread
is never used for this pass. Progress is 90-99%, with stage labels and before/after metrics.

This stage covers the modern hierarchical pipeline (all Tellus distances, long NBT
routes and Tellus rescue); legacy non-Tellus short NBT and internal NOISE/HYBRID code
retain their existing local postprocessor. No marketweb, protocol, save schema or chat
changes. Protocol remains 19.

## Verification

New tests: global zigzags and crossing/repeated loops, islands/U-shaped coast, same-height
hull validation, two-wide odd/negative/right-angle channels, thin-bank offsets, fixed
endpoints, unknown future completion, cancellation, input/optional budgets and production
wiring. Existing 7000-block geometry tests now independently validate final output.
Native Tellus tests run the actual new final stage and export before-quality.csv,
route.csv and quality metrics; every quarter-block is independently checked against
Tellus generated columns. Final CI results are recorded separately in PR #4.

Geographic replay using already captured native data is not a new online run. Native
API tests do not replace multiplayer driving, turning-radius or macOS GPU validation.
Eight-cell clearance is a preference, not a guarantee everywhere. Necessary detours and
narrow corners are not loops to erase. No claim of a global shortest/smoothest optimum.
