EDITS = [
('src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerPathfinderRunnerFactory.java', 'a24b7d275a16f15b4586f5871a56f4230596a3507b685b90b7487aabd061b502', '7ba0745cb5ab229bab0163533bd21baf112b06c18a98938a12bc3216f87a1b3f', [
(4, 7, r""""""),
(16, 16, r"""
    /** 一次自动补全的总时限(后台线程);超时的窗口沿用粗路节点。 */
    public static final long AUTO_COMPLETE_BUDGET_MILLIS = 45_000L;
"""),
(44, 46, r"""        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getSamplingPrecision()).withEntryLimit(65_536);
        // 远距离两城:粗步长找走向 + 分窗精寻,有总时限并响应中断(见 LongRangeRoadPathfinder)。只在后台线程运行。
        LongRangeRoadPathfinder pathfinder = new LongRangeRoadPathfinder(config);
        var lastPlan = new java.util.concurrent.atomic.AtomicReference<LongRangeRoadPathfinder.Result>();
"""),
(47, 49, r"""            var result = pathfinder.findPath(from, destination, cache, AUTO_COMPLETE_BUDGET_MILLIS - 10_000L);
            lastPlan.set(result);
            return result.path();
"""),
(55, 56, r"""        ).withPlanningNote(() -> {
            var result = lastPlan.get();
            return result != null && result.refinedWindows() < result.windows()
                    ? "（部分区段为粗略预览，建造前需复核）" : "";
        });
""")
]),
('src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerTerrainSampleCollector.java', '726cde16df52c1423c146f05161596a7930973e9f99d458f9605845a79324878', 'd3bd8e4905db9bee79b3b4182a944c45f26be5d3b472363632b8e41cc3709ce1', [
(4, 4, r"""import com.monpai.sailboatmod.road.pathfinding.RoadSearchBudget;
import java.util.concurrent.CancellationException;
"""),
(11, 11, r"""    static final int MAX_SAMPLES = 4096;
"""),
(19, 19, r"""        RoadSearchBudget.check();
        double total = 0;
        for (int i=1; i<nodes.size(); i++) {
            RoadSearchBudget.check();
            BlockPos a=nodes.get(i-1), b=nodes.get(i);
            if (a!=null && b!=null) total += Math.hypot(b.getX()-(double)a.getX(), b.getZ()-(double)a.getZ());
        }
        // Output is a bounded preview profile, not the authoritative build geometry.
        double spacing = Math.max(SAMPLE_SPACING_BLOCKS, total/(MAX_SAMPLES-1));
        double along=0, next=0;
"""),
(20, 26, r"""        for (int i=1; i<nodes.size() && samples.size()<MAX_SAMPLES-1; i++) {
            BlockPos from=nodes.get(i-1), to=nodes.get(i);
            if (from==null || to==null) continue;
            double length=Math.hypot(to.getX()-(double)from.getX(), to.getZ()-(double)from.getZ());
            while (next<=along+length && samples.size()<MAX_SAMPLES-1) {
                RoadSearchBudget.check();
                double t=length<1e-9?0:Math.clamp((next-along)/length,0,1);
                int x=(int)Math.round(from.getX()+(to.getX()-(double)from.getX())*t);
                int z=(int)Math.round(from.getZ()+(to.getZ()-(double)from.getZ())*t);
                addSample(cache,new BlockPos(x,from.getY(),z),samples);
                next+=spacing;
"""),
(27, 36, r"""            along+=length;
"""),
(37, 37, r"""        addSample(cache,nodes.getLast(),samples);
"""),
(48, 49, r"""        RoadSearchBudget.check();
        long key=columnKey(x,z);
        if (!samples.containsKey(key)) samples.put(key, sample(cache, x, z));
"""),
(68, 68, r"""        } catch (RoadSearchBudget.DeadlineExceeded | CancellationException stopped) {
            throw stopped;
""")
]),
('src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerAutoCompleteRequestPacket.java', 'ebc61415c7d0a40635b141a4896e100e67578a969c4f7787671c20a60ccac262', '7c4b0edb0f3b46e78aa5a646d78a2d0295f3d57d61a6f61605288cbdd7313671', [
(2, 2, r"""import com.monpai.sailboatmod.client.roadplanner.RoadPlannerAutoCompleteExecutor;
"""),
(6, 6, r"""import com.monpai.sailboatmod.road.pathfinding.RoadSearchBudget;
"""),
(56, 56, r"""            if (packet.manualNodes().size() > 1024) {
                ModNetwork.CHANNEL.send(player, new RoadPlannerAutoCompleteResultPacket(packet.sessionId(),
                        false, List.of(), List.of(), "手工节点超过1024，请分段规划", List.of()));
                return;
            }
            var requestedDimension = player.level().dimension();
"""),
(57, 68, r"""            RoadPlannerAutoCompleteService chosen = service == null ? new RoadPlannerAutoCompleteService() : service;
            var server = player.getServer();
            UUID playerId = player.getUUID();
            // 寻路在后台线程跑(超远两城曾在主线程卡服);同一玩家的新请求会中断旧请求;结果回主线程发送。
            RoadPlannerAutoCompleteExecutor.submit(playerId,
                    () -> {
                        try {
                            return RoadSearchBudget.run(RoadPlannerPathfinderRunnerFactory.AUTO_COMPLETE_BUDGET_MILLIS,
                                    () -> chosen.complete(packet.start(), packet.destination(), packet.manualNodes(), packet.spacingBlocks()));
                        } catch (RoadSearchBudget.DeadlineExceeded expired) {
                            return RoadPlannerAutoCompleteResult.failure("道路规划超时，请缩短范围或增加途经节点；未生成直线兜底");
                        }
                    },
                    server::execute,
                    result -> {
                        ServerPlayer target = server.getPlayerList().getPlayer(playerId);
                        if (target != player || !target.level().dimension().equals(requestedDimension)) return;
                        RoadPlannerAutoCompleteResult sent = result == null
                                ? RoadPlannerAutoCompleteResult.failure("自动寻路失败") : result;
                        ModNetwork.CHANNEL.send(target, new RoadPlannerAutoCompleteResultPacket(
                                packet.sessionId(), sent.success(), sent.nodes(), sent.segmentTypes(),
                                sent.message(), sent.terrainSamples()));
                    },
                    () -> ModNetwork.CHANNEL.send(player, new RoadPlannerAutoCompleteResultPacket(
                            packet.sessionId(), false, List.of(), List.of(), "服务器自动补全队列已满，请稍后再试", List.of())));
""")
]),
('src/main/java/com/monpai/sailboatmod/road/pathfinding/RoadSearchBudget.java', None, '0e248e8fa7b40319bef9773a4268d4087bf91aea8001495aba9191f3ba70768d', [
(0, 0, r"""package com.monpai.sailboatmod.road.pathfinding;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Request-local cooperative deadline, shared by search, terrain sampling and output processing.
 * Nested windows can shorten the allowance but cannot extend a caller's deadline. This cannot
 * preempt a third-party generator stuck inside one call; checks also run immediately on return.
 */
public final class RoadSearchBudget {
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private record Frame(Frame parent, LongSupplier clock, long started, long nanos) { }
    public static final class DeadlineExceeded extends RuntimeException {
        private DeadlineExceeded() { super("Road planning time budget exhausted", null, false, false); }
    }
    private RoadSearchBudget() { }
    public static <T> T run(long millis, Supplier<T> work) { return run(millis, System::nanoTime, work); }
    public static <T> T run(long millis, LongSupplier clock, Supplier<T> work) {
        Objects.requireNonNull(clock); Objects.requireNonNull(work);
        Frame previous = CURRENT.get();
        CURRENT.set(new Frame(previous, clock, clock.getAsLong(), Math.clamp(millis, 1, 600_000) * 1_000_000L));
        try { check(); T value = work.get(); check(); return value; }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
    public static void check() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Road request cancelled");
        for (Frame f = CURRENT.get(); f != null; f = f.parent())
            if (f.clock().getAsLong() - f.started() >= f.nanos()) throw new DeadlineExceeded();
    }
}
""")
]),
('src/main/java/com/monpai/sailboatmod/road/pathfinding/cache/AccurateHeightSampler.java', 'c39b88f9ae817357b9b4161120464479cc808504741388690e8dbafa02cdb393', '515db85e6a1a2ee332dc10721681673900c69b938dbf30383e239bee8bfb7804', [
(3, 3, r"""import com.monpai.sailboatmod.road.pathfinding.RoadSearchBudget;
"""),
(30, 31, r"""        RoadSearchBudget.check();
        int value = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState) - 1;
        RoadSearchBudget.check();
        return value;
"""),
(34, 35, r"""        RoadSearchBudget.check();
        int value = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState) - 1;
        RoadSearchBudget.check();
        return value;
""")
])
]
