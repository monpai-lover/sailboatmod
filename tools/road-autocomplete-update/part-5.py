EDITS = [
('src/main/java/com/monpai/sailboatmod/road/pathfinding/impl/PotentialFieldPathfinder.java', 'eb235cdb786fab4efff816e52d8c58db6916eca27d832cc3d03ceff7b6ce1eca', '4af23587984b265b84484883ef3194eb8e5f41c8d3a49e4d7659c8c232e15c8b', [
(34, 35, r"""        RoadSearchBudget.check();
        int step = Math.max(1, config.getAStarStep());
"""),
(53, 53, r"""        if (cache.isBlocked(startX, startZ) || cache.isBlocked(goalX, goalZ))
            return PathResult.failure("Road endpoint is excluded");
"""),
(58, 58, r"""        // Weighted heuristic (x15) is inconsistent: without a closed set cheaper g-values keep re-opening
        // already expanded cells and the search churns on a few thousand cells until maxSteps (far towns failed).
        HashSet<Long> closed = new HashSet<>();
"""),
(64, 64, r"""            RoadSearchBudget.check();
            Node current = open.poll();
            // Stale queue entries (a cheaper route to this cell was found later) are skipped without being
            // charged to the expansion budget; counting them used to exhaust maxSteps on re-queued cells.
            double recorded = bestG.getOrDefault(key(current.x, current.z), Double.MAX_VALUE);
            if (current.g > recorded || !closed.add(key(current.x, current.z))) {
                continue;
            }
"""),
(65, 66, r""""""),
(68, 69, r"""            if (Math.abs(current.x - goalX) + Math.abs(current.z - goalZ) <= step * 3
                    && terminalConnectionAllowed(current.x, current.z, goalX, goalZ, cache)) {
"""),
(70, 75, r""""""),
(85, 85, r"""            int fromH = cache.getHeight(current.x, current.z);
"""),
(86, 86, r"""                RoadSearchBudget.check();
"""),
(88, 88, r"""                long nKey = key(nx, nz);
                if (closed.contains(nKey) || cache.isBlocked(nx, nz)) continue;
"""),
(96, 97, r""""""),
(140, 141, r"""                double waterCost = edgeWaterCost(current.x, current.z, nx, nz, cache);
                if (!Double.isFinite(waterCost)) continue;
"""),
(151, 152, r""""""),
(162, 162, r"""    }

    /** Sample the inside of coarse hops too: two dry endpoints are not a dry edge.
     * Water remains a bridge cost, not an unconditional prohibition. This is a draft planner,
     * not a substitute for the building stage's per-block collision/support validation. */
    private double edgeWaterCost(int ax, int az, int bx, int bz, TerrainSamplingCache cache) {
        int count = Math.max(1, (int)Math.ceil(Math.hypot(bx-(double)ax, bz-(double)az) / 8));
        double penalty = 0;
        for (int i = 1; i <= count; i++) {
            RoadSearchBudget.check();
            int x = (int)Math.round(ax + (bx-(double)ax)*i/count);
            int z = (int)Math.round(az + (bz-(double)az)*i/count);
            if (cache.isBlocked(x, z)) return Double.POSITIVE_INFINITY;
            penalty = Math.max(penalty, costModel.waterCostAggressive(cache, x, z));
        }
        return penalty;
    }
    private boolean terminalConnectionAllowed(int ax, int az, int bx, int bz, TerrainSamplingCache cache) {
        double endpoints = Math.max(costModel.waterCostAggressive(cache, ax, az),
                costModel.waterCostAggressive(cache, bx, bz));
        return edgeWaterCost(ax, az, bx, bz, cache) <= endpoints;
"""),
(177, 177, r"""            RoadSearchBudget.check();
""")
]),
('src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadService.java', '1eaae30c692356cf28484b659f61410b95bee791e570aa4526efe8fc54604633', '9291f71b227ece3f0381e793cc413ae60f2f9b4c3a5373db7fd7b607396469bd', [
(3, 6, r""""""),
(259, 267, r"""        // 这里在服务器主线程:不再同步跑完整道路寻路(超远两城会卡服)。没有节点时预载起终点直线走廊;
        // 自动补全(后台线程)返回真实节点后,客户端会按节点重新请求预载。
""")
])
]
