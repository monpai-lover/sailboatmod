package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.SailboatMod;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.common.world.ForgeChunkManager;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <b>真实方块水图</b>:区块→「该区块每列是否水」缓存,按 chunkPos 增量填充。供 WorldPainter「原版生成器+populate」
 * 地图的水路寻路用——这种地图原版噪声地形 ≠ 真实写入方块,只有读真实区块才准([[worldpainter_noise_mismatch]])。
 *
 * <p><b>两层读真实方块</b>:
 * <ol>
 *   <li><b>NBT 异步读(主力,后台线程)</b>:{@code chunkMap.read} 只发起磁盘 region 异步读盘、<b>不 join 主线程</b>,
 *       可在 worker 线程直接调 + {@code future.get(timeout)} 单向等([[chunkmap_read_offthread_nbt]])。
 *       航行过一次的区域都存盘 → 走这层,<b>完全脱离主线程、不卡服、可并行</b>。见 {@link #loadNbtOffThread}。</li>
 *   <li><b>force 兜底(主线程,仅真未存盘的极少区块)</b>:NBT 返回 empty(磁盘上没有)→ forceChunk+getChunk(FULL)
 *       生成读取释放。<b>必须主线程</b>。见 {@link #loadForcedOnMainThread}。</li>
 * </ol>
 * <b>关键修正</b>:旧版误用 {@code captureGenerated} 的 {@code getNow}(非阻塞,磁盘IO没完成必返 empty)→ 几乎全
 * escalate force(实测 force=6581/6930)→ 卡服 15 秒。现 NBT 用 {@code future.get(timeout)} 真等磁盘 IO。
 *
 * <p><b>限制</b>:仅<b>主世界(overworld)</b>(NbtReader 限定);非主世界 NBT 全空 → 全 force/或退回 NOISE。
 *
 * <p><b>线程</b>:cache 用 ConcurrentHashMap,ChunkWater 写一次不可变 → 后台 A* 无锁读 {@link #sample} 安全。
 */
public final class RealBlockWaterMap {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHUNK = 16;
    private static final long NBT_READ_TIMEOUT_MS = 4000; // 单区块磁盘 NBT 读超时(后台等,不卡主线程)

    /** 单区块水图:water[256](localZ*16+localX) + NBT 解出的表面 Y(诊断用)。写一次不可变。 */
    public static final class ChunkWater {
        final boolean[] water;
        final int[] surfaceY;

        ChunkWater(boolean[] water, int[] surfaceY) {
            this.water = water;
            this.surfaceY = surfaceY;
        }
    }

    private final ServerLevel level;
    private final int seaLevel;
    private final ConcurrentHashMap<Long, ChunkWater> cache = new ConcurrentHashMap<>();
    private final AtomicInteger hitNbt = new AtomicInteger();
    private final AtomicInteger hitForced = new AtomicInteger();
    private final AtomicInteger hitUnknown = new AtomicInteger();

    // ---- 按需加载 + 滑动窗口卸载(单段 NBT A* 用)----
    // onDemand=true:sample 未命中时后台 NBT 直读该区块填缓存(不预加载整走廊);读不到的区块本次不 force
    // (force 必须主线程,A* 在后台跑不能阻塞;读不到当陆缓存,A* 绕开)。
    private volatile boolean onDemandLoad = false;
    // 区块最近访问的「逻辑时钟」(每次 sample 命中自增);evict 时淘汰最久未访问的区块,保证常驻数有界。
    private final ConcurrentHashMap<Long, Long> lastAccess = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong accessClock = new java.util.concurrent.atomic.AtomicLong();
    private static final int MAX_RESIDENT_CHUNKS = 4096; // 常驻区块上限(超此 evict 最久未访问);4096 区块 ≈ 1M 列内存
    private static final int ON_DEMAND_NBT_TIMEOUT_MS = 1500; // 按需单区块 NBT 读超时(短,读不到当陆绕开)

    public RealBlockWaterMap(ServerLevel level, int seaLevel) {
        this.level = level;
        this.seaLevel = seaLevel;
    }

    /** 开按需加载:sample 未命中时后台直读该区块 NBT 填缓存,并启用滑动窗口 evict(常驻有界)。 */
    public RealBlockWaterMap enableOnDemand() {
        this.onDemandLoad = true;
        return this;
    }

    public int seaLevel() {
        return seaLevel;
    }

    public boolean isCached(int cx, int cz) {
        return cache.containsKey(key(cx, cz));
    }

    /**
     * 后台读:(x,z) 该格水/陆。<b>纯查缓存,不加载</b>。区块未缓存返回 null(调用方约束内 → 保守 blocked)。
     */
    /** 贴岸代价:离岸不足的水格加高代价,逼 A* 离岸走(不完全贴岸/不切岛角)。 */
    private static final int OFFSHORE_MIN = 2;          // 期望离岸格数(查此范围内有无陆)
    private static final double COAST_HUG_COST = 30.0D; // 完全贴岸(8邻有陆)的高代价

    public WaterColumn sample(int x, int z) {
        ChunkWater cw = chunkAt(x >> 4, z >> 4);
        if (cw == null) {
            return null;
        }
        if (!cw.water[(z & 15) * CHUNK + (x & 15)]) {
            return WaterColumn.blocked();
        }
        // 贴岸惩罚:OFFSHORE_MIN 范围内有陆则加代价(越贴越贵)→ A* 优先离岸,不死贴/切岛角。
        return WaterColumn.passable(new BlockPos(x, seaLevel, z), coastHugCost(x, z));
    }

    private static final double HULL_LAND_COST = 12.0D; // 3×3 占地内每个陆格的额外通行代价(软引导,越贴岸越贵)

    /**
     * <b>3×3 船宽软校验</b>(2026-06 调整,原硬约束太严):硬约束<b>只要求中心格是水</b>(船能浮);3×3 占地里有陆
     * 不再直接 blocked,而是<b>每个陆格加重通行代价</b>({@link #HULL_LAND_COST})。A* 优先走宽水(占地全水零附加),
     * 窄水道/河口实在没宽水也能挤过去(占地有陆但可通行,只是贵)+ 叠加 coastHugCost 贴岸惩罚双重引导离岸。
     * <p>Why:原 3×3 全水硬 blocked 要求航道≥5格宽,大量河口/窄道判不可航 → 双向 A* 前向闷死、寻路失败。
     * 改软代价后既保留「优先离岸不搁浅」的引导,又不把窄道堵死。中心非水/区块读不到 → blocked(真不能浮)。
     */
    public WaterColumn sampleHull(int x, int z, int halfWidth) {
        int hw = Math.max(0, halfWidth);
        ChunkWater center = chunkAt(x >> 4, z >> 4);
        if (center == null) {
            return null; // 中心格区块都没有(按需也读不到)→ 交调用方当 blocked
        }
        if (!center.water[(z & 15) * CHUNK + (x & 15)]) {
            return WaterColumn.blocked(); // 中心非水 = 船浮不起来,真 blocked
        }
        int landInHull = 0;
        for (int dx = -hw; dx <= hw; dx++) {
            for (int dz = -hw; dz <= hw; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (!isWaterAt(x + dx, z + dz)) {
                    landInHull++;
                }
            }
        }
        double extra = coastHugCost(x, z) + landInHull * HULL_LAND_COST;
        return WaterColumn.passable(new BlockPos(x, seaLevel, z), extra);
    }

    /**
     * <b>3×3 校验诊断</b>:返回 (x,z) 该格在船宽校验下不可航/可航的<b>精确原因</b>(给寻路失败定位用)。
     * <ul>
     *   <li>中心区块读不到(按需也读不到)→ {@code "区块(cx,cz)读不到(未存盘/超时)"}</li>
     *   <li>中心格非水(船浮不起来,真 blocked)→ {@code "中心(x,z)非水(NBT判陆)"}</li>
     *   <li>中心是水但占地有陆 → {@code "可航(占地N格陆:(x1,z1)...)"}(软代价,仍可航,只是贵)</li>
     *   <li>占地全水 → {@code "可航(占地全水)"}</li>
     * </ul>
     */
    public String sampleHullDiagnostic(int x, int z, int halfWidth) {
        int hw = Math.max(0, halfWidth);
        int cx = x >> 4, cz = z >> 4;
        ChunkWater center = chunkAt(cx, cz);
        if (center == null) {
            return "区块(" + cx + "," + cz + ")读不到(未存盘/超时)";
        }
        if (!center.water[(z & 15) * CHUNK + (x & 15)]) {
            int sy = center.surfaceY[(z & 15) * CHUNK + (x & 15)];
            return "中心(" + x + "," + z + ")非水(NBT判陆,表面Y=" + sy + ")";
        }
        StringBuilder landCells = new StringBuilder();
        int landInHull = 0;
        for (int dx = -hw; dx <= hw; dx++) {
            for (int dz = -hw; dz <= hw; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (!isWaterAt(x + dx, z + dz)) {
                    landInHull++;
                    if (landInHull <= 4) { // 最多列 4 个陆格坐标,避免日志爆
                        landCells.append("(").append(x + dx).append(",").append(z + dz).append(")");
                    }
                }
            }
        }
        if (landInHull == 0) {
            return "可航(占地全水)";
        }
        return "可航(占地" + landInHull + "格陆:" + landCells + ",软代价不blocked)";
    }

    /** 紧贴岸代价:(x,z) 周围 OFFSHORE_MIN 范围内有陆格则加代价(陆越近/越多越贵)。 */
    private double coastHugCost(int x, int z) {
        boolean adjacentLand = false; // 8 邻有陆 = 完全贴岸(离岸<1格)
        int nearLand = 0;
        for (int dx = -OFFSHORE_MIN; dx <= OFFSHORE_MIN; dx++) {
            for (int dz = -OFFSHORE_MIN; dz <= OFFSHORE_MIN; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (!isWaterAt(x + dx, z + dz)) {
                    nearLand++;
                    if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                        adjacentLand = true;
                    }
                }
            }
        }
        if (nearLand == 0) {
            return 0.0D; // 离岸足够,开阔水,零代价
        }
        return (adjacentLand ? COAST_HUG_COST : COAST_HUG_COST * 0.3D) + nearLand * 0.5D;
    }

    /** 某格是否水(跨区块查缓存;按需模式未命中触发加载;读不到当陆,保守)。 */
    private boolean isWaterAt(int x, int z) {
        ChunkWater cw = chunkAt(x >> 4, z >> 4);
        return cw != null && cw.water[(z & 15) * CHUNK + (x & 15)];
    }

    /** (x,z) 为中心 (2·halfWidth+1)² 占地内是否全水(3×3 船宽校验,任一非水即 false)。 */
    public boolean hullClear(int x, int z, int halfWidth) {
        int hw = Math.max(0, halfWidth);
        for (int dx = -hw; dx <= hw; dx++) {
            for (int dz = -hw; dz <= hw; dz++) {
                if (!isWaterAt(x + dx, z + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 连线 a→b 沿途每格(含端点)是否都满足 3×3 船宽全水。沿线按 1 格步进采样,任一采样格 3×3 有陆 → false。
     * 供平滑后逐段校验:平滑曲线可能在转弯处轻微贴岸/过冲,这里逐段抓出会搁浅的段交安全连线修。
     */
    public boolean segmentHullClear(BlockPos a, BlockPos b, int halfWidth) {
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt((double) dx * dx + (double) dz * dz)));
        for (int s = 0; s <= steps; s++) {
            double t = s / (double) steps;
            int x = (int) Math.round(a.getX() + dx * t);
            int z = (int) Math.round(a.getZ() + dz * t);
            if (!hullClear(x, z, halfWidth)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 从 (x,z) 螺旋向外找最近的 3×3 全水格(maxR 半径内)。供安全连线把贴岸/过冲点拉回水里。找不到返回 null。
     */
    public BlockPos nearestHullWater(int x, int z, int halfWidth, int maxR) {
        if (hullClear(x, z, halfWidth)) {
            return new BlockPos(x, seaLevel, z);
        }
        for (int r = 1; r <= maxR; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue; // 只查当前环
                    }
                    if (hullClear(x + dx, z + dz, halfWidth)) {
                        return new BlockPos(x + dx, seaLevel, z + dz);
                    }
                }
            }
        }
        return null;
    }

    /**
     * <b>NBT 局部绕行 BFS</b>(替代 RealWaterVerifier 的噪声版):在 a-b 局部包围盒(外扩 margin)内,沿
     * <b>3×3 船宽全水格</b>1 格步长 4 邻 BFS 从 a 找到 b 的绕行水路。判水全用 NBT {@link #hullClear}(真实方块,
     * 命中缓存的内存数组查;按需模式未命中触发后台读),<b>绝不用噪声密度</b>(WorldPainter 地图噪声≠真实方块)。
     * @return a..b 的绕行航点(含首尾,海平面 Y);找不到/超预算返回空。
     */
    public java.util.List<BlockPos> localRerouteNbt(BlockPos a, BlockPos b, int halfWidth, int margin, int maxCells) {
        int minX = Math.min(a.getX(), b.getX()) - margin;
        int maxX = Math.max(a.getX(), b.getX()) + margin;
        int minZ = Math.min(a.getZ(), b.getZ()) - margin;
        int maxZ = Math.max(a.getZ(), b.getZ()) + margin;
        java.util.Set<Long> visited = new java.util.HashSet<>();
        java.util.Map<Long, Long> parent = new java.util.HashMap<>();
        java.util.Deque<int[]> queue = new java.util.ArrayDeque<>();
        long startKey = packXZ(a.getX(), a.getZ());
        queue.add(new int[]{a.getX(), a.getZ()});
        visited.add(startKey);
        int[][] neighbors = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            if (visited.size() > maxCells) {
                return java.util.List.of(); // 超预算,绕不开
            }
            int[] cell = queue.poll();
            int x = cell[0], z = cell[1];
            if (Math.abs(x - b.getX()) <= 1 && Math.abs(z - b.getZ()) <= 1) {
                return reconstructNbt(parent, a, x, z, b);
            }
            for (int[] n : neighbors) {
                int nx = x + n[0], nz = z + n[1];
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) {
                    continue;
                }
                long nkey = packXZ(nx, nz);
                if (visited.contains(nkey)) {
                    continue;
                }
                if (!hullClear(nx, nz, halfWidth)) {
                    continue; // 3×3 占地有陆 → 不走
                }
                visited.add(nkey);
                parent.put(nkey, packXZ(x, z));
                queue.add(new int[]{nx, nz});
            }
        }
        return java.util.List.of();
    }

    private java.util.List<BlockPos> reconstructNbt(java.util.Map<Long, Long> parent, BlockPos a,
                                                    int endX, int endZ, BlockPos b) {
        java.util.List<BlockPos> path = new java.util.ArrayList<>();
        long startKey = packXZ(a.getX(), a.getZ());
        long cur = packXZ(endX, endZ);
        while (true) {
            int x = (int) (cur >> 32);
            int z = (int) (cur & 0xFFFFFFFFL);
            path.add(new BlockPos(x, seaLevel, z));
            if (cur == startKey) {
                break;
            }
            Long prev = parent.get(cur);
            if (prev == null) {
                break;
            }
            cur = prev;
        }
        java.util.Collections.reverse(path); // a → end
        BlockPos last = path.get(path.size() - 1);
        if (last.getX() != b.getX() || last.getZ() != b.getZ()) {
            path.add(new BlockPos(b.getX(), seaLevel, b.getZ())); // 接回真实终点
        }
        return path;
    }

    private static long packXZ(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    /**
     * 取 (cx,cz) 区块水图:命中直接返回;<b>按需模式</b>未命中则后台 NBT 直读填缓存(读不到则缓存全陆,
     * 避免反复重读同一缺盘区块)。每次访问更新逻辑时钟,常驻超 {@link #MAX_RESIDENT_CHUNKS} 触发 evict。
     * 非按需模式未命中返回 null(沿用预加载语义)。后台线程安全(loadNbtOffThread 走 chunkMap.read 不 join 主线程)。
     */
    private ChunkWater chunkAt(int cx, int cz) {
        long k = key(cx, cz);
        ChunkWater cw = cache.get(k);
        if (cw != null) {
            lastAccess.put(k, accessClock.incrementAndGet());
            return cw;
        }
        if (!onDemandLoad) {
            return null;
        }
        // 按需后台读:读到填真实水图;读不到(未存盘)填全陆,既绕开又不反复重读。
        if (!loadNbtOffThread(cx, cz)) {
            cache.putIfAbsent(k, allLand());
            hitUnknown.incrementAndGet();
        }
        lastAccess.put(k, accessClock.incrementAndGet());
        maybeEvict();
        return cache.get(k);
    }

    /** 常驻区块超上限 → 淘汰最久未访问的若干区块(滑动窗口,内存有界)。仅按需模式生效。 */
    private void maybeEvict() {
        if (cache.size() <= MAX_RESIDENT_CHUNKS) {
            return;
        }
        int over = cache.size() - MAX_RESIDENT_CHUNKS;
        int toEvict = over + MAX_RESIDENT_CHUNKS / 8; // 多淘汰 1/8 留缓冲,避免每次 sample 都触发
        // 按最近访问时钟升序选最旧的 toEvict 个(小批量遍历,A* 单次扩展不频繁触发)。
        java.util.List<java.util.Map.Entry<Long, Long>> entries = new java.util.ArrayList<>(lastAccess.entrySet());
        entries.sort(java.util.Map.Entry.comparingByValue());
        for (int i = 0; i < toEvict && i < entries.size(); i++) {
            long ek = entries.get(i).getKey();
            cache.remove(ek);
            lastAccess.remove(ek);
        }
    }

    /**
     * <b>详细全节点诊断</b>:对寻路出来的 path 每个航点(超 60 点则抽样),主线程 force 加载真实区块,打印
     * 坐标 + NBT 判定(水/陆 + NBT 以为的表面Y)+ 真实判定(getFluidState 水/陆 + 真实表面Y + 真实方块ID)。
     * 对比每个节点 NBT 与真实,精确定位 NBT 在哪些节点把陆判成水、那些点真实是什么方块。
     */
    public void diagnosePathLandCrossings(java.util.List<BlockPos> path) {
        if (level == null || level.getServer() == null || path == null || path.isEmpty()) {
            return;
        }
        try {
            java.util.concurrent.CompletableFuture<String> f = new java.util.concurrent.CompletableFuture<>();
            // 抽样:超 60 点按间隔抽,避免日志爆 + force 加载过多(每点 force 一个区块)。
            java.util.List<BlockPos> snapshot = new java.util.ArrayList<>();
            int stride = Math.max(1, path.size() / 60);
            for (int i = 0; i < path.size(); i += stride) {
                snapshot.add(path.get(i));
            }
            level.getServer().execute(() -> {
                try {
                    int crossings = 0;
                    StringBuilder sb = new StringBuilder("\n[WaterPath] 详细节点诊断(共采样 " + snapshot.size() + " 点):");
                    for (int idx = 0; idx < snapshot.size(); idx++) {
                        BlockPos p = snapshot.get(idx);
                        int x = p.getX(), z = p.getZ();
                        boolean nbtWater = isWaterAt(x, z);
                        ChunkWater cw = cache.get(key(x >> 4, z >> 4));
                        int nbtSurfY = cw == null ? -999 : cw.surfaceY[(z & 15) * CHUNK + (x & 15)];
                        int cx = x >> 4, cz = z >> 4;
                        BlockPos owner = new BlockPos(cx << 4, seaLevel, cz << 4);
                        ForgeChunkManager.forceChunk(level, SailboatMod.MODID, owner, cx, cz, true, false);
                        boolean realWaterSurf;
                        boolean realWaterSea;
                        int realSurfY;
                        String id;
                        try {
                            level.getChunk(cx, cz, ChunkStatus.FULL, true);
                            realSurfY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(new BlockPos(x, realSurfY, z));
                            realWaterSurf = st.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
                            realWaterSea = level.getFluidState(new BlockPos(x, seaLevel, z)).is(net.minecraft.tags.FluidTags.WATER);
                            id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                        } finally {
                            ForgeChunkManager.forceChunk(level, SailboatMod.MODID, owner, cx, cz, false, false);
                        }
                        // 口径修正([[worldpainter_noise_mismatch]]):真实水面不一定在 seaLevel(WorldPainter 地图水面 Y=62,
                        // getSeaLevel()=63 那格是水面上方空气 → 死查必误判非水)。船看的是「真实表面那格是不是水」:
                        // 表面方块含水(realWaterSurf)或海平面那格含水(realWaterSea)任一为真即可航(容 ±1 水面差)。
                        boolean realNav = realWaterSurf || realWaterSea;
                        boolean cross = nbtWater && !realNav; // NBT 判水但真实表面/海平面都非水 = 真穿陆
                        if (cross) {
                            crossings++;
                        }
                        sb.append("\n  ").append(cross ? "[穿陆]" : "      ")
                                .append("(").append(x).append(",").append(z).append(") ")
                                .append("NBT=").append(nbtWater ? "水" : "陆").append("(Y").append(nbtSurfY).append(") ")
                                .append("真实表面Y=").append(realSurfY)
                                .append(" 海平面含水=").append(realWaterSea)
                                .append(" 表面方块=").append(id);
                    }
                    sb.append("\n小结:穿陆(NBT水但海平面真实非水)=").append(crossings).append(" 处");
                    f.complete(sb.toString());
                } catch (Throwable t) {
                    f.complete("诊断异常:" + t);
                }
            });
            LOGGER.info("{}", f.get(15000, java.util.concurrent.TimeUnit.MILLISECONDS));
        } catch (Throwable ignored) {
        }
    }

    /**
     * <b>后台线程</b>:NBT 异步读盘一个区块填缓存(已缓存跳过)。成功 → true;磁盘上没有/超时 → false(交 force 兜底)。
     * 不碰主线程,可大批并行。
     */
    public boolean loadNbtOffThread(int cx, int cz) {
        long k = key(cx, cz);
        if (cache.containsKey(k)) {
            return true;
        }
        Optional<NbtChunkWaterReader.ChunkColumns> snap = NbtChunkWaterReader.readOffThread(level, cx, cz, NBT_READ_TIMEOUT_MS);
        if (snap.isPresent()) {
            cache.put(k, toChunkWater(snap.get()));
            hitNbt.incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * <b>主线程</b>:force 兜底加载一个区块填缓存(仅 NBT 读不到的极少区块)。force+FULL 后 captureGenerated(走已加载
     * 分支)读取,释放票据。读不到 → 保守判全陆缓存(不穿陆优先)。
     */
    public void loadForcedOnMainThread(int cx, int cz) {
        long k = key(cx, cz);
        if (cache.containsKey(k)) {
            return;
        }
        Optional<NbtChunkWaterReader.ChunkColumns> snap = NbtChunkWaterReader.readForcedOnMainThread(level, cx, cz);
        if (snap.isPresent()) {
            cache.put(k, toChunkWater(snap.get()));
            hitForced.incrementAndGet();
        } else {
            cache.put(k, allLand());
            hitUnknown.incrementAndGet();
        }
    }

    private final AtomicInteger totalWaterCells = new AtomicInteger();
    private final AtomicInteger totalCells = new AtomicInteger();

    private ChunkWater toChunkWater(NbtChunkWaterReader.ChunkColumns snap) {
        boolean[] water = snap.water();
        int[] surfY = snap.surfaceY();
        int w = 0;
        for (int i = 0; i < CHUNK * CHUNK; i++) {
            if (water[i]) {
                w++;
            }
        }
        totalWaterCells.addAndGet(w);
        totalCells.addAndGet(CHUNK * CHUNK);
        return new ChunkWater(water, surfY);
    }

    private ChunkWater allLand() {
        int[] sy = new int[CHUNK * CHUNK];
        java.util.Arrays.fill(sy, -999);
        return new ChunkWater(new boolean[CHUNK * CHUNK], sy); // 全 false = 全陆
    }

    public int nbtHits() {
        return hitNbt.get();
    }

    public int forcedHits() {
        return hitForced.get();
    }

    public void logLayerStats(String phase) {
        int tc = totalCells.get();
        int wc = totalWaterCells.get();
        LOGGER.info("[WaterPath] 真实水图层命中({}):NBT={} force={} unknown={} 缓存区块={} | 解码水格={}/{} ({}%)",
                phase, hitNbt.get(), hitForced.get(), hitUnknown.get(), cache.size(),
                wc, tc, tc == 0 ? 0 : wc * 100 / tc);
    }

    /**
     * 诊断:以 (cx,cz) 为心采样 9×9 格(step 间隔),打印 NBT 判定的水/陆图;并主线程乒乓查中心点真实
     * {@code getFluidState} 对比,定位「NBT 判水口径是否错」。仅在粗寻失败时调一次。
     */
    public void diagnoseAround(int cx, int cz, int step) {
        StringBuilder sb = new StringBuilder("\n[WaterPath] 诊断 NBT 判水图(中心 ").append(cx).append(",").append(cz)
                .append(" step=").append(step).append(",'~'=水 '#'=陆 '?'=未缓存):\n");
        int waterCount = 0, landCount = 0, missCount = 0;
        for (int dz = -4; dz <= 4; dz++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx * step, z = cz + dz * step;
                WaterColumn c = sample(x, z);
                if (c == null) {
                    sb.append('?');
                    missCount++;
                } else if (c.passable()) {
                    sb.append('~');
                    waterCount++;
                } else {
                    sb.append('#');
                    landCount++;
                }
            }
            sb.append('\n');
        }
        sb.append("水=").append(waterCount).append(" 陆=").append(landCount).append(" 未缓存=").append(missCount);
        LOGGER.info(sb.toString());
        // 主线程乒乓:中心点真实 getFluidState 对比 NBT 判定。
        if (level != null && level.getServer() != null) {
            try {
                java.util.concurrent.CompletableFuture<String> f = new java.util.concurrent.CompletableFuture<>();
                final int fx = cx, fz = cz;
                level.getServer().execute(() -> {
                    try {
                        // 真实表面 Y(motion-blocking 高度图)那一格方块,跟 NBT 判定对比。
                        int realSurfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, fx, fz) - 1;
                        net.minecraft.world.level.block.state.BlockState surfState = level.getBlockState(new BlockPos(fx, realSurfaceY, fz));
                        String surfId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(surfState.getBlock()).toString();
                        boolean realWaterAtSea = level.getFluidState(new BlockPos(fx, seaLevel, fz)).is(net.minecraft.tags.FluidTags.WATER);
                        boolean realWaterAtSurf = surfState.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
                        ChunkWater cw = cache.get(key(fx >> 4, fz >> 4));
                        boolean nbtWater = cw != null && cw.water[(fz & 15) * CHUNK + (fx & 15)];
                        boolean realNav = realWaterAtSurf || realWaterAtSea; // 口径同上,容 ±1 水面差
                        f.complete("seaLevel=" + seaLevel + " 真实表面Y=" + realSurfaceY + " 表面方块=" + surfId
                                + " 表面含水=" + realWaterAtSurf + " seaLevel处含水=" + realWaterAtSea
                                + " 真实可航=" + realNav + " | NBT判定=" + (nbtWater ? "水" : "陆"));
                    } catch (Throwable t) {
                        f.complete("对比异常:" + t);
                    }
                });
                LOGGER.info("[WaterPath] 诊断中心点 ({},{}) {}", cx, cz, f.get(3000, java.util.concurrent.TimeUnit.MILLISECONDS));
            } catch (Throwable ignored) {
            }
        }
    }

    private static long key(int cx, int cz) {
        return ChunkPos.asLong(cx, cz);
    }
}
