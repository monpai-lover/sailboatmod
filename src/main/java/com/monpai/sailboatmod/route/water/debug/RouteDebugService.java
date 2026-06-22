package com.monpai.sailboatmod.route.water.debug;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.dock.DockLocationSavedData;
import com.monpai.sailboatmod.route.water.debug.RouteDebugDtos.DockInfo;
import com.monpai.sailboatmod.route.water.debug.RouteDebugDtos.NodeDiag;
import com.monpai.sailboatmod.route.water.debug.RouteDebugDtos.RouteDebugBundle;
import com.monpai.sailboatmod.route.water.debug.RouteDebugDtos.StageResult;
import com.monpai.sailboatmod.route.PathSmoother;
import com.monpai.sailboatmod.route.water.DockBerthResolver;
import com.monpai.sailboatmod.route.water.RealBlockWaterMap;
import com.monpai.sailboatmod.route.water.RealBlockWaterWorld;
import com.monpai.sailboatmod.route.water.WaterColumn;
import com.monpai.sailboatmod.route.water.WaterRouteNbtVerifier;
import com.monpai.sailboatmod.route.water.WaterRoutePathfinder;
import com.monpai.sailboatmod.route.water.WaterRoutePolicy;
import com.monpai.sailboatmod.route.water.WaterRouteResult;
import com.monpai.sailboatmod.route.water.WaterRouteWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.world.ForgeChunkManager;

import java.util.ArrayList;
import java.util.List;

/** Orchestration for the route-debug tool: list docks, resolve berths, run the copied NBT pathfinder, render. */
public final class RouteDebugService {
    private static final String OVERWORLD = "minecraft:overworld";

    private RouteDebugService() {
    }

    /** Main-thread: list every overworld dock. Position is always known; name/zone read from BlockEntity if loaded. */
    public static List<DockInfo> listDocks(MinecraftServer server) {
        List<DockInfo> out = new ArrayList<>();
        if (server == null) {
            return out;
        }
        ServerLevel level = server.overworld();
        List<BlockPos> positions = DockLocationSavedData.get(level).positionsIn(OVERWORLD);
        for (BlockPos pos : positions) {
            String name = "Dock(" + pos.getX() + "," + pos.getZ() + ")";
            int minX = -DockBlockEntity.ZONE_HALF_X;
            int maxX = DockBlockEntity.ZONE_HALF_X;
            int minZ = -DockBlockEntity.ZONE_HALF_Z;
            int maxZ = DockBlockEntity.ZONE_HALF_Z;
            // Only read BlockEntity if the chunk is loaded; do not force-load (keeps listing fast).
            if (level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof DockBlockEntity dock) {
                String dn = dock.getDockName();
                if (dn != null && !dn.isBlank()) {
                    name = dn + " (" + pos.getX() + "," + pos.getZ() + ")";
                }
                minX = dock.getZoneMinX();
                maxX = dock.getZoneMaxX();
                minZ = dock.getZoneMinZ();
                maxZ = dock.getZoneMaxZ();
            }
            out.add(new DockInfo(pos, name, minX, maxX, minZ, maxZ));
        }
        return out;
    }

    /** Hand-written JSON (no third-party): [{"index":i,"x":..,"z":..,"name":".."},...]. */
    public static String docksJson(List<DockInfo> docks) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < docks.size(); i++) {
            DockInfo d = docks.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"index\":").append(i)
              .append(",\"x\":").append(d.pos().getX())
              .append(",\"z\":").append(d.pos().getZ())
              .append(",\"name\":\"").append(escapeJson(d.name())).append("\"}");
        }
        return sb.append(']').toString();
    }

    static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    // ===================== route orchestration (production route.water.*, two-stage NBT) =====================

    private static final int NBT_CORRIDOR_RADIUS = 96; // same as WaterAutoRouteService

    /**
     * Worker-thread: resolve berths -> coarse corridor -> corridor refine -> smooth -> NBT verify, calling the
     * PRODUCTION route.water classes the game actually uses. Per-node diagnosis hops to main thread internally.
     */
    public static RouteDebugBundle runRoute(MinecraftServer server, DockInfo a, DockInfo b) {
        return runRoute(server, a, b, com.monpai.sailboatmod.route.water.WaterMidMode.NBT);
    }

    /**
     * 按指定中段模式跑寻路:NBT=两阶段(粗渐进step+走廊精寻);HYBRID/NOISE=生产三段编排(runSerialPlanned),
     * 与游戏实际跑的【完全同口径】,这样 webmap 图就是游戏真实结果,可图像判断 HYBRID 判水对不对、在哪穿陆。
     */
    public static RouteDebugBundle runRoute(MinecraftServer server, DockInfo a, DockInfo b,
                                            com.monpai.sailboatmod.route.water.WaterMidMode mode) {
        if (mode != null && mode != com.monpai.sailboatmod.route.water.WaterMidMode.NBT) {
            return runRouteThreeSeg(server, a, b, mode);
        }
        ServerLevel level = server.overworld();
        int seaY = level.getSeaLevel();
        WaterRoutePolicy policy = WaterRoutePolicy.defaults();
        int halfWidth = Math.max(1, policy.boatHalfWidth());

        RealBlockWaterMap map = new RealBlockWaterMap(level, seaY).enableOnDemand();

        // berth: RealBlockWaterWorld.coarse implements BerthWorld; resolve both docks' water entry points.
        RealBlockWaterWorld berthProbe = RealBlockWaterWorld.coarse(map, seaY, null, null);
        BlockPos startBerth = resolveBerth(berthProbe, a, policy);
        BlockPos goalBerth = resolveBerth(berthProbe, b, policy);

        StringBuilder log = new StringBuilder();
        if (startBerth == null || goalBerth == null) {
            log.append("berth resolve failed: start=").append(startBerth).append(" goal=").append(goalBerth);
            StageResult berthFail = new StageResult("berth",
                    pair(startBerth, goalBerth), "FAILED", 0, "NO_BERTH");
            return new RouteDebugBundle(startBerth, goalBerth, berthFail, empty("coarse"), empty("fine"),
                    empty("smooth"), empty("verified"), List.of(), log.toString());
        }

        StageResult berth = new StageResult("berth", pair(startBerth, goalBerth), "OK", 0, "");
        log.append("start=").append(startBerth).append(" goal=").append(goalBerth).append('\n');

        // stage 1: coarse corridor —— 渐进 step(24→12→8→4)与游戏 WaterAutoRouteService 同口径,踩进窄海峡。
        WaterRoutePathfinder cpf = null;
        StageResult coarse = null;
        for (int step : com.monpai.sailboatmod.route.water.WaterAutoRouteService.COARSE_STEP_LADDER) {
            RealBlockWaterWorld coarseWorld = RealBlockWaterWorld.coarse(map, seaY, startBerth, goalBerth);
            cpf = runToCompletion(coarseWorld, startBerth, goalBerth, WaterRoutePolicy.nbtCoarseStep(step));
            coarse = stageOf("coarse", cpf);
            log.append("coarse(step=").append(step).append(") ").append(coarse.status())
               .append(" nodes=").append(coarse.expandedNodes())
               .append(" wp=").append(coarse.path().size()).append(" reason=").append(coarse.reason()).append('\n');
            if (cpf.status() == WaterRoutePathfinder.Status.SUCCESS) {
                break;
            }
        }
        if (cpf.status() != WaterRoutePathfinder.Status.SUCCESS) {
            List<NodeDiag> diags = diagnoseNodes(server, level, map, coarse.path(), seaY);
            appendNodeLog(log, diags);
            return new RouteDebugBundle(startBerth, goalBerth, berth, coarse, empty("fine"),
                    empty("smooth"), empty("verified"), diags, log.toString());
        }

        // stage 2: corridor refine (3x3 hull)
        RealBlockWaterWorld fineWorld = RealBlockWaterWorld.corridor(
                map, coarse.path(), NBT_CORRIDOR_RADIUS, seaY, halfWidth, startBerth, goalBerth);
        WaterRoutePathfinder fpf = runToCompletion(fineWorld, startBerth, goalBerth, WaterRoutePolicy.nbtRefine());
        StageResult fine = stageOf("fine", fpf);
        log.append("fine ").append(fine.status()).append(" nodes=").append(fine.expandedNodes())
           .append(" wp=").append(fine.path().size()).append(" reason=").append(fine.reason()).append('\n');
        if (fpf.status() != WaterRoutePathfinder.Status.SUCCESS) {
            List<NodeDiag> diags = diagnoseNodes(server, level, map, fine.path(), seaY);
            appendNodeLog(log, diags);
            return new RouteDebugBundle(startBerth, goalBerth, berth, coarse, fine,
                    empty("smooth"), empty("verified"), diags, log.toString());
        }

        // refine/coarse 择优兜底(与 WaterAutoRouteService 同口径,同一 pathQuality):refine 劣于 coarse 则退回 coarse,
        // 平滑/verify 的输入就是实际会走的那条线 → debug 图与游戏 100% 一致(否则可能显示 refine 图但实际退回 coarse)。
        List<BlockPos> raw = fine.path();
        if (raw != null && raw.size() >= 2) {
            long[] qFine = com.monpai.sailboatmod.route.water.WaterAutoRouteService.pathQuality(map, raw, halfWidth);
            long[] qCoarse = com.monpai.sailboatmod.route.water.WaterAutoRouteService.pathQuality(map, coarse.path(), halfWidth);
            boolean fineWorse = qFine[0] > qCoarse[0] || (qFine[0] == qCoarse[0] && qFine[1] > qCoarse[1]);
            log.append("prefer: fine(land=").append(qFine[0]).append(",len=").append(qFine[1])
               .append(") coarse(land=").append(qCoarse[0]).append(",len=").append(qCoarse[1])
               .append(") -> ").append(fineWorse ? "COARSE" : "FINE").append('\n');
            if (fineWorse) {
                raw = coarse.path();
            }
        }

        // 去回折(与游戏同口径:平滑前删开阔水回折赘点,治窄水道出口三角)
        raw = WaterRouteNbtVerifier.dropOpenWaterBackfolds(map, raw, halfWidth);
        // smooth + NBT verify (use production WaterPathSmoother so debug images match in-game behavior:
        // 撞陆感知平滑前/中/后三层,与 WaterAutoRouteService 同口径)
        List<BlockPos> smoothPath = com.monpai.sailboatmod.route.water.WaterPathSmoother.smooth(
                map, raw, seaY, 2.0D, halfWidth);
        StageResult smooth = new StageResult("smooth", smoothPath, "OK", 0, "");
        log.append("smooth wp=").append(smoothPath.size()).append('\n');

        List<BlockPos> verifiedPath = WaterRouteNbtVerifier.verify(map, smoothPath, halfWidth);
        StageResult verified = new StageResult("verified", verifiedPath, "OK", 0, "");
        log.append("verified wp=").append(verifiedPath.size()).append('\n');

        // per-node diagnosis (all nodes, main-thread real-block read)
        List<NodeDiag> diags = diagnoseNodes(server, level, map, verifiedPath, seaY);
        appendNodeLog(log, diags);

        return new RouteDebugBundle(startBerth, goalBerth, berth, coarse, fine, smooth, verified, diags, log.toString());
    }

    /**
     * HYBRID/NOISE 三段编排 debug(生产同口径 ThreeSegmentPlanner.runSerialPlanned)。
     * 阶段填充:coarse=三段原始航点(中段编排产物),fine=空(三段无独立精寻阶段),smooth=平滑,verified=NBT校验。
     * 校验前【预加载航线沿途真实区块】(与生产同),verify 红点标 NBT 判陆点 → 图像看 HYBRID 在哪与真实判水冲突。
     * RealChunkRouteWorld.load 需主线程,用 server.submit().join()。
     */
    private static RouteDebugBundle runRouteThreeSeg(MinecraftServer server, DockInfo a, DockInfo b,
                                                     com.monpai.sailboatmod.route.water.WaterMidMode mode) {
        ServerLevel level = server.overworld();
        int seaY = level.getSeaLevel();
        WaterRoutePolicy policy = WaterRoutePolicy.defaults();
        int halfWidth = Math.max(1, policy.boatHalfWidth());
        StringBuilder log = new StringBuilder();
        log.append("mode=").append(mode).append('\n');

        // berth 解析(与 NBT 同口径)
        RealBlockWaterMap probeMap = new RealBlockWaterMap(level, seaY).enableOnDemand();
        RealBlockWaterWorld berthProbe = RealBlockWaterWorld.coarse(probeMap, seaY, null, null);
        BlockPos startBerth = resolveBerth(berthProbe, a, policy);
        BlockPos goalBerth = resolveBerth(berthProbe, b, policy);
        if (startBerth == null || goalBerth == null) {
            log.append("berth resolve failed: start=").append(startBerth).append(" goal=").append(goalBerth);
            StageResult berthFail = new StageResult("berth", pair(startBerth, goalBerth), "FAILED", 0, "NO_BERTH");
            return new RouteDebugBundle(startBerth, goalBerth, berthFail, empty("coarse"), empty("fine"),
                    empty("smooth"), empty("verified"), List.of(), log.toString());
        }
        StageResult berth = new StageResult("berth", pair(startBerth, goalBerth), "OK", 0, "");
        log.append("start=").append(startBerth).append(" goal=").append(goalBerth).append('\n');

        // 三段编排:RealChunkRouteWorld.load 主线程预读港口快照,然后 runSerialPlanned(生产同口径)。
        int radius = com.monpai.sailboatmod.route.water.RealChunkRouteWorld.DEFAULT_RADIUS;
        int threshold = com.monpai.sailboatmod.route.water.RealChunkRouteWorld.OFFSHORE_THRESHOLD;
        final BlockPos sB = startBerth;
        final BlockPos gB = goalBerth;
        com.monpai.sailboatmod.route.water.RealChunkRouteWorld startWorld =
                server.submit(() -> com.monpai.sailboatmod.route.water.RealChunkRouteWorld.load(level, sB, radius)).join();
        com.monpai.sailboatmod.route.water.RealChunkRouteWorld endWorld =
                server.submit(() -> com.monpai.sailboatmod.route.water.RealChunkRouteWorld.load(level, gB, radius)).join();
        com.monpai.sailboatmod.route.water.ServerWaterRouteWorld midWorld =
                new com.monpai.sailboatmod.route.water.ServerWaterRouteWorld(level);

        WaterRouteResult<com.monpai.sailboatmod.route.water.ThreeSegmentPlanner.PlannedPath> raw =
                com.monpai.sailboatmod.route.water.ThreeSegmentPlanner.runSerialPlanned(
                        startWorld, endWorld, midWorld, startBerth, goalBerth, radius, threshold, mode,
                        com.monpai.sailboatmod.route.water.ThreeSegmentPlanner.ProgressSink.NOOP);
        if (!raw.successful()) {
            log.append("three-seg failed: ").append(raw.reason());
            StageResult midFail = new StageResult("coarse", List.of(), "FAILED", 0, raw.reason().name());
            return new RouteDebugBundle(startBerth, goalBerth, berth, midFail, empty("fine"),
                    empty("smooth"), empty("verified"), List.of(), log.toString());
        }
        com.monpai.sailboatmod.route.water.ThreeSegmentPlanner.PlannedPath planned = raw.value();
        StageResult coarse = new StageResult("coarse", planned.path(), "OK", 0, "");
        log.append("three-seg ok wp=").append(planned.path().size())
           .append(" jointA=").append(planned.jointA()).append(" jointB=").append(planned.jointB()).append('\n');

        // 平滑 + 校验(与生产同:校验前预加载航线沿途真实区块,verify 用真实 NBT)
        List<BlockPos> smoothPath = PathSmoother.smooth2DWithOrigin(planned.path(), seaY, 2.0D).points();
        StageResult smooth = new StageResult("smooth", smoothPath, "OK", 0, "");
        log.append("smooth wp=").append(smoothPath == null ? 0 : smoothPath.size()).append('\n');

        RealBlockWaterMap verifyMap = new RealBlockWaterMap(level, seaY).enableOnDemand();
        verifyMap.preloadAlongPath(smoothPath, halfWidth, 4000L);
        List<BlockPos> verifiedPath = WaterRouteNbtVerifier.verify(verifyMap, smoothPath, halfWidth);
        StageResult verified = new StageResult("verified", verifiedPath, "OK", 0, "");
        log.append("verified wp=").append(verifiedPath == null ? 0 : verifiedPath.size()).append('\n');

        List<NodeDiag> diags = diagnoseNodes(server, level, verifyMap, verifiedPath, seaY);
        appendNodeLog(log, diags);
        // fine 阶段三段编排没有,留空(图库不显示);coarse 即中段原始线。
        return new RouteDebugBundle(startBerth, goalBerth, berth, coarse, empty("fine"), smooth, verified, diags, log.toString());
    }

    private static WaterRoutePathfinder runToCompletion(WaterRouteWorld world, BlockPos start, BlockPos goal,
                                                        WaterRoutePolicy policy) {
        WaterRoutePathfinder pf = new WaterRoutePathfinder(world, start, goal, policy);
        long deadline = System.nanoTime() + (long) policy.timeoutTicks() * 50L * 1_000_000L;
        WaterRoutePathfinder.Status st;
        do {
            st = pf.step(policy.nodesPerTick(), policy.chunkLoadsPerTick());
        } while (st == WaterRoutePathfinder.Status.RUNNING && System.nanoTime() < deadline);
        return pf;
    }

    private static StageResult stageOf(String stage, WaterRoutePathfinder pf) {
        return new StageResult(stage, pf.path(), pf.status().name(), pf.expandedNodes(), pf.failureReason().name());
    }

    private static StageResult empty(String stage) {
        return new StageResult(stage, List.of(), "SKIPPED", 0, "");
    }

    private static List<BlockPos> pair(BlockPos a, BlockPos b) {
        List<BlockPos> out = new ArrayList<>(2);
        if (a != null) {
            out.add(a);
        }
        if (b != null) {
            out.add(b);
        }
        return out;
    }

    private static BlockPos resolveBerth(RealBlockWaterWorld world, DockInfo dock, WaterRoutePolicy policy) {
        DockBerthResolver.DockZone zone = new DockBerthResolver.DockZone(
                dock.pos(), dock.zoneMinX(), dock.zoneMaxX(), dock.zoneMinZ(), dock.zoneMaxZ());
        WaterRouteResult<DockBerthResolver.DockBerth> r = DockBerthResolver.resolve(world, zone, policy);
        if (!r.successful()) {
            return null;
        }
        Vec3 p = r.value().pos();
        return new BlockPos(Mth.floor(p.x), Mth.floor(p.y), Mth.floor(p.z));
    }

    /** Main-thread: force-load each verified node's chunk and read the real block, compared to the NBT verdict. */
    private static List<NodeDiag> diagnoseNodes(MinecraftServer server, ServerLevel level, RealBlockWaterMap map,
                                                List<BlockPos> verified, int seaLevel) {
        if (verified == null || verified.isEmpty()) {
            return List.of();
        }
        return server.submit(() -> {
            List<NodeDiag> out = new ArrayList<>();
            for (int i = 0; i < verified.size(); i++) {
                BlockPos p = verified.get(i);
                int x = p.getX();
                int z = p.getZ();
                int cx = x >> 4;
                int cz = z >> 4;
                WaterColumn col = map.sample(x, z);
                boolean nbtWater = col != null && col.passable();
                String how = i == 0 ? "start-berth(trusted)"
                        : (i == verified.size() - 1 ? "goal-berth(trusted)" : "NBT-offthread/cache");
                BlockPos owner = new BlockPos(cx << 4, seaLevel, cz << 4);
                ForgeChunkManager.forceChunk(level, SailboatMod.MODID, owner, cx, cz, true, false);
                boolean realNav;
                int realSurfY;
                String id;
                try {
                    level.getChunk(cx, cz, ChunkStatus.FULL, true);
                    realSurfY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    BlockState st = level.getBlockState(new BlockPos(x, realSurfY, z));
                    boolean surfW = st.getFluidState().is(FluidTags.WATER);
                    boolean seaW = level.getFluidState(new BlockPos(x, seaLevel, z)).is(FluidTags.WATER);
                    realNav = surfW || seaW;
                    id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                } finally {
                    ForgeChunkManager.forceChunk(level, SailboatMod.MODID, owner, cx, cz, false, false);
                }
                String routed = "verified#" + i + (nbtWater == realNav ? ""
                        : (nbtWater ? " [NBT=water but REAL=land => land-crossing!]" : " [NBT=land]"));
                out.add(new NodeDiag(i, p, nbtWater, realNav, realSurfY, id, how, routed));
            }
            return out;
        }).join();
    }

    // ===================== top-level: route -> 6 images + log -> JSON =====================

    /**
     * Worker-thread entry (called from HTTP handler): run the route, render terrain once on the main thread,
     * render each stage image on the worker, return a JSON object {images:{stage:dataUrl,...}, log:"..."}.
     */
    public static String renderBundle(MinecraftServer server, DockInfo a, DockInfo b) {
        return renderBundle(server, a, b, com.monpai.sailboatmod.route.water.WaterMidMode.NBT);
    }

    public static String renderBundle(MinecraftServer server, DockInfo a, DockInfo b,
                                      com.monpai.sailboatmod.route.water.WaterMidMode mode) {
        ServerLevel level = server.overworld();
        RouteDebugBundle bundle = runRoute(server, a, b, mode);
        BlockPos viewA = bundle.start() != null ? bundle.start() : a.pos();
        BlockPos viewB = bundle.goal() != null ? bundle.goal() : b.pos();
        // 1:1 pixel-per-block; 8192 is only an OOM safety clamp for extreme routes.
        var view = RouteDebugRenderer.computeView(viewA, viewB, 64, 8192);

        // terrain base: real-block sampling, must be main thread; render once and reuse.
        java.awt.image.BufferedImage terrain = server.submit(() -> RouteDebugRenderer.renderTerrain(level, view)).join();

        // water overlay + path lines: worker thread (RealBlockWaterMap off-thread NBT read + pure Java2D).
        // 底图判水用 hullClear(2×2),与 verifier 同标准 → 蓝色区=verifier 认可可航区(不再骗人)。
        int halfWidth = Math.max(1, WaterRoutePolicy.defaults().boatHalfWidth());
        RealBlockWaterMap map = new RealBlockWaterMap(level, level.getSeaLevel()).enableOnDemand();
        // 底图 map 预加载航线沿途真实区块,与 verify 同数据状态(否则底图 map 缓存空、按需读超时 fallback 判陆,又是假象)。
        if (bundle.verified() != null && bundle.verified().path() != null && bundle.verified().path().size() >= 2) {
            map.preloadAlongPath(bundle.verified().path(), halfWidth, 4000L);
        }

        StringBuilder json = new StringBuilder("{\"images\":{");
        boolean first = true;
        first = appendImage(json, first, "berth",
                RouteDebugRenderer.renderStage(terrain, view, map, null, bundle.berth(), bundle.start(), bundle.goal(), null, halfWidth));
        first = appendImage(json, first, "coarse",
                RouteDebugRenderer.renderStage(terrain, view, map, null, bundle.coarse(), bundle.start(), bundle.goal(), null, halfWidth));
        first = appendImage(json, first, "fine",
                RouteDebugRenderer.renderStage(terrain, view, map, bundle.coarse(), bundle.fine(), bundle.start(), bundle.goal(), null, halfWidth));
        first = appendImage(json, first, "smooth",
                RouteDebugRenderer.renderStage(terrain, view, map, bundle.fine(), bundle.smooth(), bundle.start(), bundle.goal(), null, halfWidth));
        // verified image: mark ONLY the nodes that verified as land.
        first = appendImage(json, first, "verified",
                RouteDebugRenderer.renderStage(terrain, view, map, bundle.smooth(), bundle.verified(), bundle.start(), bundle.goal(), bundle.nodeDiags(), halfWidth));
        appendImage(json, first, "overview",
                RouteDebugRenderer.renderOverview(terrain, view, map, bundle.coarse(), bundle.fine(), bundle.smooth(), bundle.verified(), bundle.start(), bundle.goal(), halfWidth));
        json.append("},\"mode\":\"").append(mode == null ? "nbt" : mode.name().toLowerCase())
            .append("\",\"log\":\"").append(escapeJson(bundle.summary())).append("\"}");
        return json.toString();
    }

    private static boolean appendImage(StringBuilder json, boolean first, String key, java.awt.image.BufferedImage img) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", baos);
            String b64 = java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
            if (!first) {
                json.append(',');
            }
            json.append('"').append(key).append("\":\"data:image/png;base64,").append(b64).append('"');
            return false;
        } catch (java.io.IOException e) {
            return first;
        }
    }

    private static void appendNodeLog(StringBuilder log, List<NodeDiag> diags) {
        int crossings = 0;
        log.append("--- per-node diagnosis (").append(diags.size()).append(" nodes) ---\n");
        for (NodeDiag d : diags) {
            boolean cross = d.nbtWater() && !d.realNavigable();
            if (cross) {
                crossings++;
            }
            log.append(cross ? "[LAND] " : "       ")
               .append('#').append(d.index())
               .append(" (").append(d.pos().getX()).append(',').append(d.pos().getZ()).append(") ")
               .append("NBT=").append(d.nbtWater() ? "water" : "land")
               .append(" REAL=").append(d.realNavigable() ? "navigable" : "land")
               .append(" surfY=").append(d.realSurfaceY())
               .append(" block=").append(d.blockId())
               .append(" via=").append(d.howSampled())
               .append('\n');
        }
        log.append("summary: land-crossings (NBT=water but REAL=land) = ").append(crossings).append('\n');
    }
}
