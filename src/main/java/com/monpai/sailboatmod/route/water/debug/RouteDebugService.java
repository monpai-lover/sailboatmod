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
import com.monpai.sailboatmod.route.water.WaterRoutePolicy;
import com.monpai.sailboatmod.route.water.WaterRouteResult;
import com.monpai.sailboatmod.route.water.WaterAutoRouteService;
import com.monpai.sailboatmod.route.water.WorldWaterOverview;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import com.monpai.sailboatmod.util.ModChunkLoading;

import java.util.ArrayList;
import java.util.List;

/** Read-only route diagnostics. NBT delegates to the current production planner without publishing its result. */
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

    // ===================== read-only production route orchestration =====================

    /**
     * Worker-thread entry. NBT shares production berth validation, terrain, solver and final checks.
     */
    public static RouteDebugBundle runRoute(MinecraftServer server, DockInfo a, DockInfo b) {
        return runRoute(server, a, b, com.monpai.sailboatmod.route.water.WaterMidMode.NBT);
    }

    /**
     * NBT returns the final production result only; intermediate stages are deliberately left SKIPPED.
     * HYBRID/NOISE retain the existing three-segment diagnostics.
     */
    public static RouteDebugBundle runRoute(MinecraftServer server, DockInfo a, DockInfo b,
                                            com.monpai.sailboatmod.route.water.WaterMidMode mode) {
        if (mode != null && mode != com.monpai.sailboatmod.route.water.WaterMidMode.NBT) {
            return runRouteThreeSeg(server, a, b, mode);
        }
        long started = System.nanoTime();
        WaterAutoRouteService.ReadOnlyNbtPlan plan = WaterAutoRouteService.planReadOnlyNbt(server.overworld(),
                zone(a), zone(b));
        BlockPos start = plan.sourceBerth(), goal = plan.targetBerth();
        var result = plan.result();
        String reason = result.reason().name();
        boolean hasBerths = start != null && goal != null;
        StageResult berth = new StageResult("berth", pair(start, goal), hasBerths ? "OK" : "FAILED", 0,
                hasBerths ? "" : reason);
        List<BlockPos> path = result.successful() && result.value() != null ? result.value() : List.of();
        StageResult verified = new StageResult("verified", path, result.successful() ? "SUCCESS" : "FAILED", 0, reason);
        StringBuilder log = new StringBuilder("pipeline=production-nbt preference=OPTIMAL (fresh calculation)\n");
        log.append("start=").append(start).append(" goal=").append(goal).append('\n');
        log.append("result=").append(verified.status()).append(" reason=").append(reason)
                .append(" waypoints=").append(path.size()).append(" elapsedMs=")
                .append((System.nanoTime() - started) / 1_000_000L).append('\n');
        log.append("生产泊位验证、全局/分段求解及最终检查；未导入、注册或保存航线。\n")
                .append("仅显示生产求解的最终结果；中间阶段未单独采集，扩展节点数请查看服务器 [WaterPath] 日志。\n")
                .append("未逐点强制加载区块；底图仅为定位示意，不代表真实方块或船宽校验结果。\n");
        for (int i = 0; i < path.size(); i++) {
            BlockPos p = path.get(i);
            log.append("waypoint,").append(i).append(',').append(p.getX()).append(',')
                    .append(p.getY()).append(',').append(p.getZ()).append('\n');
        }
        return new RouteDebugBundle(start, goal, berth, empty("coarse"), empty("fine"), empty("smooth"),
                verified, List.of(), log.toString());
    }

    private static DockBerthResolver.DockZone zone(DockInfo dock) {
        return new DockBerthResolver.DockZone(dock.pos(), dock.zoneMinX(), dock.zoneMaxX(), dock.zoneMinZ(), dock.zoneMaxZ());
    }

    /**
     * HYBRID/NOISE 三段编排 debug(生产同口径 ThreeSegmentPlanner.runSerialPlanned)。
     * 阶段填充:coarse=三段原始航点(中段编排产物),fine=空(三段无独立精寻阶段),smooth=平滑,verified=NBT校验。
     * 校验前【预加载航线沿途真实区块】(与生产同),verify 红点标 NBT 判陆点 → 图像看 HYBRID 在哪与真实判水冲突。
     * RealChunkRouteWorld.load 需主线程,只通过 tick mailbox 投递并有界等待。
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
                com.monpai.sailboatmod.route.water.PortAsyncRuntime.onMain(server,
                        () -> com.monpai.sailboatmod.route.water.RealChunkRouteWorld.load(level, sB, radius))
                        .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join();
        com.monpai.sailboatmod.route.water.RealChunkRouteWorld endWorld =
                com.monpai.sailboatmod.route.water.PortAsyncRuntime.onMain(server,
                        () -> com.monpai.sailboatmod.route.water.RealChunkRouteWorld.load(level, gB, radius))
                        .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join();
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
        return com.monpai.sailboatmod.route.water.PortAsyncRuntime.onMain(server, () -> {
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
                ModChunkLoading.forceChunk(level, SailboatMod.MODID, owner, cx, cz, true, false);
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
                    ModChunkLoading.forceChunk(level, SailboatMod.MODID, owner, cx, cz, false, false);
                }
                String routed = "verified#" + i + (nbtWater == realNav ? ""
                        : (nbtWater ? " [NBT=water but REAL=land => land-crossing!]" : " [NBT=land]"));
                out.add(new NodeDiag(i, p, nbtWater, realNav, realSurfY, id, how, routed));
            }
            return out;
        }).orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join();
    }

    // ===================== top-level: route -> bounded NBT preview / legacy stage images + log =====================

    /**
     * Worker-thread entry. NBT renders a bounded schematic here; legacy modes retain their stage images.
     */
    public static String renderBundle(MinecraftServer server, DockInfo a, DockInfo b) {
        return renderBundle(server, a, b, com.monpai.sailboatmod.route.water.WaterMidMode.NBT);
    }

    public static String renderBundle(MinecraftServer server, DockInfo a, DockInfo b,
                                      com.monpai.sailboatmod.route.water.WaterMidMode mode) {
        ServerLevel level = server.overworld();
        RouteDebugBundle bundle = runRoute(server, a, b, mode);
        if (mode == null || mode == com.monpai.sailboatmod.route.water.WaterMidMode.NBT) {
            return renderProductionBundle(level, a, b, bundle, "nbt");
        }
        BlockPos viewA = bundle.start() != null ? bundle.start() : a.pos();
        BlockPos viewB = bundle.goal() != null ? bundle.goal() : b.pos();
        // 1:1 pixel-per-block; 8192 is only an OOM safety clamp for extreme routes.
        var view = RouteDebugRenderer.computeView(viewA, viewB, 64, 8192);

        // terrain base: real-block sampling, must be main thread; render once and reuse.
        java.awt.image.BufferedImage terrain = com.monpai.sailboatmod.route.water.PortAsyncRuntime.onMain(server,
                () -> RouteDebugRenderer.renderTerrain(level, view)).orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join();

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

    /** Tellus is an explicit HTTP choice for the existing production Tellus/NBT solver. */
    public static String renderBundle(MinecraftServer server, DockInfo a, DockInfo b, RouteDebugMode mode) {
        if (mode == RouteDebugMode.TELLUS) {
            if (!com.monpai.sailboatmod.route.terrain.TellusWorlds.isTellusLevel(server.overworld()))
                throw new IllegalArgumentException("Tellus mode requires a Tellus Earth world");
            RouteDebugBundle bundle = runRoute(server, a, b, mode.plannerMode());
            return renderProductionBundle(server.overworld(), a, b, bundle, mode.id());
        }
        return renderBundle(server, a, b, mode.plannerMode());
    }

    /** Fits the complete result, including detours, without a route-length-dependent image allocation. */
    static RouteDebugDtos.ViewTransform productionPreviewView(BlockPos start, BlockPos goal, List<BlockPos> path) {
        long minX = Math.min(start.getX(), goal.getX()), maxX = Math.max(start.getX(), goal.getX());
        long minZ = Math.min(start.getZ(), goal.getZ()), maxZ = Math.max(start.getZ(), goal.getZ());
        for (BlockPos p : path) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        minX = Math.max(Integer.MIN_VALUE, minX - 64); minZ = Math.max(Integer.MIN_VALUE, minZ - 64);
        maxX = Math.min(Integer.MAX_VALUE, maxX + 65); maxZ = Math.min(Integer.MAX_VALUE, maxZ + 65);
        long spanX = Math.max(1, maxX - minX), spanZ = Math.max(1, maxZ - minZ);
        int scale = (int) Math.max(1, (Math.max(spanX, spanZ) + 1023) / 1024);
        int width = (int) ((spanX + scale - 1) / scale), height = (int) ((spanZ + scale - 1) / scale);
        return new RouteDebugDtos.ViewTransform((int) minX, (int) minZ,
                (int) Math.min(Integer.MAX_VALUE, spanX), (int) Math.min(Integer.MAX_VALUE, spanZ), scale, width, height);
    }

    /** Worker-only schematic preview. The bundled overview never downloads or loads/generates Minecraft chunks. */
    private static String renderProductionBundle(ServerLevel level, DockInfo a, DockInfo b, RouteDebugBundle bundle, String webMode) {
        BlockPos start = bundle.start() == null ? a.pos() : bundle.start();
        BlockPos goal = bundle.goal() == null ? b.pos() : bundle.goal();
        List<BlockPos> path = bundle.verified().path();
        var view = productionPreviewView(start, goal, path);
        var scale = com.monpai.sailboatmod.route.terrain.TellusSurfaceSamplers.worldScale(level);
        WorldWaterOverview overview = scale.isPresent() ? WorldWaterOverview.get() : null;
        var water = overview == null ? null : overview.forTellusScale(scale.getAsDouble());
        var image = new java.awt.image.BufferedImage(view.width(), view.height(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int py = 0; py < view.height(); py++) {
            com.monpai.sailboatmod.route.water.WaterRouteCancellation.check();
            int z = (int) ((long) view.minZ() + (long) py * view.scale());
            for (int px = 0; px < view.width(); px++) {
                int x = (int) ((long) view.minX() + (long) px * view.scale());
                image.setRGB(px, py, water == null ? 0xFF26313C
                        : water.anyWater(x, z, x, z) ? 0xFF173F5D : 0xFF726B50);
            }
        }
        java.awt.Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new java.awt.Color(70, 255, 120));
            graphics.setStroke(new java.awt.BasicStroke(2.0f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            for (int i = 1; i < path.size(); i++) {
                BlockPos p = path.get(i - 1), q = path.get(i);
                graphics.drawLine(view.px(p.getX()), view.pz(p.getZ()), view.px(q.getX()), view.pz(q.getZ()));
            }
            graphics.setColor(java.awt.Color.CYAN);
            graphics.fillOval(view.px(start.getX()) - 4, view.pz(start.getZ()) - 4, 9, 9);
            graphics.setColor(java.awt.Color.MAGENTA);
            graphics.fillOval(view.px(goal.getX()) - 4, view.pz(goal.getZ()) - 4, 9, 9);
        } finally {
            graphics.dispose();
        }
        StringBuilder json = new StringBuilder("{\"images\":{");
        appendImage(json, true, "verified", image);
        json.append("},\"mode\":\"").append(webMode).append("\",\"pipeline\":\"production-nbt\",\"status\":\"")
                .append(bundle.verified().status()).append("\",\"reason\":\"").append(bundle.verified().reason())
                .append("\",\"log\":\"").append(escapeJson(bundle.summary()))
                .append("preview=").append(view.width()).append('x').append(view.height())
                .append(" blocksPerPixel=").append(view.scale())
                .append(water == null ? " schematic background" : " bundled global overview (schematic only)")
                .append("\"}");
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
            throw new java.io.UncheckedIOException("Route debug PNG encoding failed", e);
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
