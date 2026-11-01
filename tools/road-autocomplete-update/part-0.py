EDITS = [
('docs/handoff/ROAD_AUTOCOMPLETE_FAR_TOWNS.md', None, '722baac99dfc7b5e4f725e238d04f51cc26c70c1a58c1aaeea12ba56a7376153', [
(0, 0, r"""# Far-town road autocomplete: uploaded patch integration and hardening

Input: `0003-fix-road-auto-complete-between-far-towns-no-longer-f.patch`, original
commit `e0734fcf260bcd757dae98bb0d3aba2d1f8e747a` by Claude. Integrated on the
verified protocol-21 Tellus far-route branch, not an older destination branch.

## Retained from input
- Two background workers, queue capacity 16, newer per-player requests supersede old ones.
- Potential-field A* closed set; stale queue items do not spend expansion budget.
- Beyond 2048 blocks: distance-scaled coarse search, approximately 768-block fine windows.
- The map preload fallback no longer starts pathfinding on the server tick thread.
- Original synthetic hill/inland-sea fixture and executor regressions.

## Additional changes
- Identity is rechecked when the main-thread result callback actually executes, not merely when scheduled.
- Cancelled queued tasks are removed immediately. Logout, dimension changes and server stopping cancel work.
- All four selectable search algorithms and production terrain probes check a request-local deadline/interruption.
  Nested windows cannot extend the outer deadline; ThreadLocal state is restored after errors/cancellation.
- The request pipeline has a cooperative 45-second budget; search uses at most 35 seconds to leave room for
  classification/output. Fine windows have 1.5-second budgets. Third-party blocking calls are not forcibly
  preemptible; the deadline is checked immediately before/after each call. This is NOT a hard real-time bound.
- A whole-request timeout or failed configured pathfinder never returns a fabricated straight-line success.
  A fine window may retain its coarse guide; the UI explicitly labels partially refined output as a draft.
- Closed/excluded neighbours are skipped before terrain work. Coarse edge interiors and terminal snap
  connections are sampled (8-block spacing) for water cost, instead of inspecting endpoints alone.
- Per-request caches cap each category at 65,536 completed values. Other cache consumers retain default behaviour.
- The complete road geometry is limited to 16,384 output nodes, manual prefix to 1024 nodes; oversized requests
  fail explicitly, never truncate. Map preview profiles are resampled by arc length to at most 4096 columns.
- Smoothing cannot substantially extend a continuous water crossing compared with the raw road (4-block
  preview sampling). Cancellation/timeouts are not swallowed by optional terrain-profile collection.
- All custom pathfinding weights survive the coarse/fine configuration copy.

## Coverage and limitations
Tests cover delayed callback supersession, 100 queued replacements, cancellation, deadline inheritance,
short/coarse searches, all four algorithms' interruption, output/sample limits, bounded caches, excluded
endpoints, a water strip hidden between coarse nodes, and synthetic 5k/negative-7k/20k/50k hilly roads
around an inland sea. The long-road test independently checks every segment at <=2-block intervals.

Synthetic terrain is NOT a live Tellus road or a complete multiplayer world. Existing live Tellus water
regressions should also be rerun, but do not substitute for long-road real-world server load tests.
No world writes or force-generation are introduced. Road generators still read generation terrain, not
all player edits; draft success does not certify bridge supports, tunnel excavation, full road width or
construction permission. Weighted A* plus a closed set is not guaranteed globally shortest.

Protocol stays 21. No MarketWeb source/HTTP/DTO changes. Previous water routes, ship tracking, reuse,
postprocessing, ProtoChunk reading and chat features must remain byte-identical to the baseline.
""")
]),
('src/main/java/com/monpai/sailboatmod/ServerEvents.java', '2716f4f551effa5347b7acf9fa75e96366cd02244e717e9c4197c96930e48db7', '17e45202f843cea5446196894a6a1afb69d997f82b5bc61bf9f49b4f8efb11ab', [
(155, 155, r"""        com.monpai.sailboatmod.client.roadplanner.RoadPlannerAutoCompleteExecutor.cancel(player);
"""),
(161, 161, r"""    public static void onRoadPlayerChangedDimension(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event) {
        com.monpai.sailboatmod.client.roadplanner.RoadPlannerAutoCompleteExecutor.cancel(event.getEntity().getUUID());
    }

    @SubscribeEvent
"""),
(162, 162, r"""        com.monpai.sailboatmod.client.roadplanner.RoadPlannerAutoCompleteExecutor.cancelAll();
""")
])
]
