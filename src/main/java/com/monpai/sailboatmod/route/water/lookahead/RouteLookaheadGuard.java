package com.monpai.sailboatmod.route.water.lookahead;

import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.route.repair.RoutePatch;
import com.monpai.sailboatmod.route.water.NbtChunkWaterReader;
import com.monpai.sailboatmod.route.water.RealBlockWaterMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * <b>航行前瞻守卫</b>(Tellus 世界):沿航线前方加载走廊,在已加载区块上用真实方块逐段校验;发现障碍就后台局部重规划、
 * 主线程拼回航线(船不停),修正交给 Host 回写。等待结果期间船接近障碍 48 格内停住;绕不开交给 Host 的完整自救。
 * 主线程调用 {@link #tick}/{@link #extraChunks}/{@link #shouldHold};重规划在 background 执行器上跑。
 */
public final class RouteLookaheadGuard {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int LOOKAHEAD_BLOCKS = 160;
    public static final int SIDE_CHUNKS = 1;
    public static final int MAX_EXTRA_CHUNKS = 40;
    public static final int TICK_INTERVAL = 10;
    public static final double HOLD_DISTANCE = 48.0D;
    /** 与 SailboatEntity.MAX_AUTOPILOT_WAYPOINTS 一致;自动航线生成时被抽稀到 256,留出绕行插点的余量。 */
    public static final int MAX_WAYPOINTS = 512;
    private static final int HALF_WIDTH = 1;

    /** 守卫与载具之间的接口(载具实现;测试用假实现)。 */
    public interface Host {
        List<Vec3> route();

        int targetIndex();

        Vec3 position();

        boolean autopilotActive();

        /** 与载具现有自救共用的互斥位;拿到才可提交重规划。 */
        boolean claimRerouteSlot();

        void releaseRerouteSlot();

        void applySplice(RouteSplicer.Spliced spliced);

        void escalateToFullRescue();

        void onPatch(RoutePatch patch);
    }

    private final Host host;
    private final LongFunction<NbtChunkWaterReader.ChunkColumns> loadedColumns;
    private final Supplier<RealBlockWaterMap> mapFactory;
    private final Executor background;
    private final Executor mainThread;

    private int checkedUpTo;
    private List<Vec3> checkedRoute = List.of();
    private RouteLookahead.Obstacle pending;
    private Vec3 holdPoint;
    private long generation;

    public RouteLookaheadGuard(Host host, LongFunction<NbtChunkWaterReader.ChunkColumns> loadedColumns,
                               Supplier<RealBlockWaterMap> mapFactory, Executor background, Executor mainThread) {
        this.host = host;
        this.loadedColumns = loadedColumns;
        this.mapFactory = mapFactory;
        this.background = background;
        this.mainThread = mainThread;
    }

    /** 前方走廊区块(并入载具的强加载区块)。 */
    public Set<Long> extraChunks() {
        return RouteLookahead.corridorChunks(host.route(), host.targetIndex(), host.position(),
                LOOKAHEAD_BLOCKS, SIDE_CHUNKS, MAX_EXTRA_CHUNKS);
    }

    public boolean shouldHold() {
        Vec3 point = holdPoint;
        if (pending == null || point == null) {
            return false;
        }
        Vec3 boat = host.position();
        double dx = boat.x - point.x;
        double dz = boat.z - point.z;
        return dx * dx + dz * dz <= HOLD_DISTANCE * HOLD_DISTANCE;
    }

    public void reset() {
        generation++;
        checkedUpTo = 0;
        checkedRoute = List.of();
        boolean ownedSlot = pending != null;
        pending = null;
        holdPoint = null;
        if (ownedSlot) {
            host.releaseRerouteSlot();
        }
    }

    public void tick(long gameTime) {
        if (gameTime % TICK_INTERVAL != 0 || pending != null || !host.autopilotActive()) {
            return;
        }
        List<Vec3> route = host.route();
        if (route == null || route.size() < 2) {
            return;
        }
        if (!route.equals(checkedRoute)) {
            checkedRoute = List.copyOf(route); // 航线被换过(自救/拼接/新航次):已校验进度作废
            checkedUpTo = 0;
        }
        RealBlockWaterMap map = mapFactory.get();
        Set<Long> seeded = new HashSet<>();
        for (long chunk : extraChunks()) {
            NbtChunkWaterReader.ChunkColumns columns = loadedColumns.apply(chunk);
            if (columns != null) {
                map.seedChunk(net.minecraft.world.level.ChunkPos.getX(chunk), net.minecraft.world.level.ChunkPos.getZ(chunk), columns);
                seeded.add(chunk);
            }
        }
        RouteLookahead.Scan scan = RouteLookahead.scan(route, host.targetIndex(), host.position(), checkedUpTo,
                LOOKAHEAD_BLOCKS, map, seeded::contains, HALF_WIDTH);
        checkedUpTo = scan.checkedUpTo();
        if (scan.obstacle().isEmpty() || !host.claimRerouteSlot()) {
            return;
        }
        RouteLookahead.Obstacle obstacle = scan.obstacle().get();
        pending = obstacle;
        holdPoint = Vec3.atBottomCenterOf(obstacle.before());
        List<Vec3> snapshot = checkedRoute;
        BlockPos from = obstacle.before();
        BlockPos to = obstacle.after();
        LOGGER.info("[Lookahead] 前方航段 {}..{} 有障碍,后台绕行 {} → {}", obstacle.fromSegment(), obstacle.toSegment(), from, to);
        submit(generation, () -> {
            List<BlockPos> detour = safeCompute(map, from, to);
            return () -> complete(snapshot, obstacle, detour);
        });
    }

    private void complete(List<Vec3> snapshot, RouteLookahead.Obstacle obstacle, List<BlockPos> detour) {
        if (!host.autopilotActive() || !snapshot.equals(host.route()) || RouteSplicer.isStale(obstacle, host.targetIndex())) {
            LOGGER.info("[Lookahead] 绕行结果丢弃:已停驶、航线已更换或船已越过障碍段");
            finish(false); // 下一轮重扫
            return;
        }
        Optional<RouteSplicer.Spliced> spliced = detour.isEmpty() ? Optional.empty()
                : RouteSplicer.splice(host.route(), obstacle, detour, host.targetIndex(), MAX_WAYPOINTS);
        if (spliced.isEmpty()) {
            LOGGER.warn("[Lookahead] {} → 完整自救", detour.isEmpty() ? "局部绕行失败" : "航点超上限无法拼接");
            finish(true);
            return;
        }
        host.applySplice(spliced.get());
        host.onPatch(spliced.get().patch());
        LOGGER.info("[Lookahead] 绕行段已接入航线,总航点={}", spliced.get().route().size());
        finish(false);
    }

    /**
     * 后台跑 work,把它返回的主线程收尾交回主线程。任何一步出错都收尾释放互斥位:互斥位与载具自救共用,
     * 一旦漏放,前瞻与被动自救都会永久失效。
     */
    private void submit(long requestGeneration, Supplier<Runnable> work) {
        try {
            background.execute(() -> {
                Runnable onMain;
                try {
                    onMain = work.get();
                } catch (Throwable t) {
                    LOGGER.error("[Lookahead] 后台绕行异常", t);
                    onMain = () -> finish(false);
                }
                Runnable finalOnMain = onMain;
                mainThread.execute(() -> {
                    if (requestGeneration != generation) {
                        return; // reset 已释放旧互斥位;迟到回调不能修改新航次或释放它的互斥位。
                    }
                    try {
                        finalOnMain.run();
                    } catch (Throwable t) {
                        LOGGER.error("[Lookahead] 绕行收尾异常", t);
                        if (pending != null) {
                            finish(false);
                        }
                    }
                });
            });
        } catch (Throwable t) {
            LOGGER.error("[Lookahead] 无法提交后台绕行", t);
            finish(false);
        }
    }

    private boolean stillCurrent(List<Vec3> snapshot) {
        if (host.autopilotActive() && snapshot.equals(host.route())) {
            return true;
        }
        LOGGER.info("[Lookahead] 绕行结果丢弃:已停驶或航线已更换");
        return false;
    }

    private void apply(RouteSplicer.Spliced spliced) {
        host.applySplice(spliced);
        if (spliced.patch() != null) {
            host.onPatch(spliced.patch());
        }
        LOGGER.info("[Lookahead] 绕行段已接入航线,总航点={}", spliced.route().size());
    }

    /** 结束本次重规划:先释放互斥位再升级(完整自救与本守卫共用同一互斥位,占着它自救会直接返回)。 */
    private void finish(boolean escalate) {
        pending = null;
        holdPoint = null;
        host.releaseRerouteSlot();
        if (escalate) {
            host.escalateToFullRescue();
        }
    }

    private static List<BlockPos> safeCompute(RealBlockWaterMap map, BlockPos from, BlockPos to) {
        try {
            return LookaheadReroute.compute(map, from, to, HALF_WIDTH);
        } catch (Throwable t) {
            LOGGER.error("[Lookahead] 绕行计算异常", t);
            return List.of();
        }
    }
}
