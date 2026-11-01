# Bounded progress-based route budgets

Base source: 3535ecc7cf45c3272ea715400eed89a22e8ccd1c (same production sources as the 52c728a1 artifact).

## Behavior

Hierarchical coarse and fine searches explicitly opt into an adaptive RouteStageBudget. Legacy users of the original constructor keep fixed limits. The base allowance remains the policy's search time plus 60 seconds of waiting (normally 90+60 = 150 seconds).

Only nodes actually expanded from a rooted search frontier can report progress. Arbitrary sampled points, pending edges, callbacks, failed reads and polling counters cannot earn credit. Each direction keeps its historical best distance to the opposite endpoint. The sum of the two best advances is capped by the initial endpoint separation; it is a heuristic for allocating resources, NOT proof that the fronts are connected or a route completion percentage.

- Every new 16 blocks of best aggregate approach earns 1 second of wall budget (128 blocks = 8 seconds).
- Exploring eight new 128x128 regions earns 5 seconds of detour allowance, at most 60 seconds. The two initial endpoint regions are excluded. Revisited regions and repeated refinements cannot claim this allowance again.
- Stage wall time is capped at the smaller of four times its base allowance and 480 seconds, without lowering a larger caller-specified base allowance. The standard stage caps at 480 seconds.
- Active search time earns at most half of awarded wall time and may at most double its original allowance (standard 90 -> 180 seconds). This measures time charged to search slices, not OS CPU time.
- 60 seconds with no new approach milestone or qualifying exploration milestone terminates the stage. Mere node growth, cache hits and requests do not renew this stall allowance.
- 600 seconds caps the whole planner independently of progress. Existing UI session total timeout, node/radius bounds, bounded workers and per-player task limits remain unchanged; the UI session may expire earlier because it includes queue/endpoint preparation time.
- Credit is retained across step-size retries within one stage. After a verified segment completes, the next stage gets an independent budget; the whole task's hard timer is never reset.
- The old deadline is checked before accepting a new progress observation. Expired tasks cannot resurrect from a late completion. Cancellation and terrain safety checks are unchanged.

Progress text appends accumulated award and remaining stage time using the existing protocol-19 packet. New diagnostics include best forward/backward remaining distance, approach, detour grant, current allowance and the precise stop cause.

## Validation scope

27 new JUnit-style test methods plus 45 selected existing methods executed through a lightweight isolated Java harness: 72 passed, zero failed. This is not a full Gradle/JUnit platform or NeoForge runtime test. Minecraft coordinate/logging classes and the annotation/assertion surface are minimal test doubles; actual production planner, policy, postprocessor and cache logic execute from the output JAR. Five existing 7000-block geometry cases and cancellation/unknown-terrain checks are included.

An injected-clock 7000-block cold-frontier case completed at simulated 168 seconds; the original fixed controller timed out under the same timeline. There was no 168-second live-server measurement. This round did NOT rerun actual Tellus online sources or the uploaded complete regions.

## Delivery and publishing

The provided JAR is an experimental incremental build over the previous complete, verified proto-region JAR. Four production classes and their nested classes were compiled with javac --release 21 against matching API test doubles. The existing WaterAutoRouteService received the exact two-instruction equivalent of the single source-level withBudget call. All non-private method descriptors of replaced existing classes were retained; all unrelated archive entries are byte-for-byte unchanged. No test doubles are shipped in the mod JAR.

Full source and a patch are provided for a clean Gradle rebuild. The current connector exposes reads but not writes/CI dispatch; no GitHub push or new full build was performed in this turn. Do not label the incremental package as CI-verified.

No protocol or WebMarket interface changed. Keep main mod versions aligned on both ends, back up first, and resubmit the route rather than editing region files.
