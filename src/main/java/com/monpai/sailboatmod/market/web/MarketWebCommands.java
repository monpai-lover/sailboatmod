package com.monpai.sailboatmod.market.web;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.market.logistics.ShippingTraceRecord;
import com.monpai.sailboatmod.market.logistics.ShippingTraceSavedData;
import com.monpai.sailboatmod.market.web.map.MarketWebMapBlackTileRepair;
import com.monpai.sailboatmod.market.web.map.MarketWebMapConstants;
import com.monpai.sailboatmod.market.web.map.MarketWebMapTileCache;
import com.monpai.sailboatmod.market.web.map.MarketWebMapRenderService;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.CopyMarketWebTokenPacket;
import com.monpai.sailboatmod.route.RouteDefinition;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;

public final class MarketWebCommands {
    private MarketWebCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerRoot(dispatcher, "marketweb");
        registerRoot(dispatcher, "webmarket");
    }

    private static void registerRoot(CommandDispatcher<CommandSourceStack> dispatcher, String root) {
        dispatcher.register(Commands.literal(root)
                .then(Commands.literal("token")
                        .requires(source -> source.getEntity() instanceof ServerPlayer)
                        .executes(context -> issueToken(context.getSource())))
                .then(Commands.literal("version")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> showVersion(context.getSource())))
                .then(Commands.literal("reload")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> reloadWeb(context.getSource())))
                .then(Commands.literal("map")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("repaircache")
                                .executes(context -> repairMapCache(context.getSource())))
                        .then(Commands.literal("clearlegacy")
                                .executes(context -> clearLegacyMapCache(context.getSource())))
                        .then(Commands.literal("clearclientuploads")
                                .executes(context -> clearClientUploadMapCache(context.getSource())))
                        .then(Commands.literal("clearstaleserver")
                                .executes(context -> clearStaleServerMapCache(context.getSource())))
                        .then(Commands.literal("clearall")
                                .executes(context -> clearAllMapCache(context.getSource())))
                        .then(Commands.literal("repaintall")
                                .executes(context -> repaintAllMapCache(context.getSource())))
                        .then(Commands.literal("fullrender")
                                .then(Commands.literal("start")
                                        .executes(context -> startFullMapRender(context.getSource())))
                                .then(Commands.literal("pause")
                                        .executes(context -> pauseMapRender(context.getSource())))
                                .then(Commands.literal("resume")
                                        .executes(context -> resumeMapRender(context.getSource())))
                                .then(Commands.literal("cancel")
                                        .executes(context -> cancelMapRender(context.getSource())))
                                .then(Commands.literal("status")
                                        .executes(context -> showMapRenderStatus(context.getSource()))))
                        .then(Commands.literal("radiusrender")
                                .then(Commands.argument("radiusBlocks", IntegerArgumentType.integer(1))
                                        .executes(context -> startRadiusMapRender(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "radiusBlocks")))))
                        .then(Commands.literal("arearender")
                                .then(Commands.argument("x1", IntegerArgumentType.integer())
                                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                                                .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                                .executes(context -> startAreaMapRender(
                                                                        context.getSource(),
                                                                        IntegerArgumentType.getInteger(context, "x1"),
                                                                        IntegerArgumentType.getInteger(context, "z1"),
                                                                        IntegerArgumentType.getInteger(context, "x2"),
                                                                        IntegerArgumentType.getInteger(context, "z2"))))))))
                        .then(Commands.literal("borderrender")
                                .executes(context -> startWorldBorderMapRender(context.getSource())))
                        .then(Commands.literal("scan")
                                .executes(context -> enqueueMapRegionScan(context.getSource())))
                        .then(Commands.literal("repairblack")
                                .executes(context -> repairBlackTiles(context.getSource(), 0))
                                .then(Commands.argument("budgetTiles", IntegerArgumentType.integer(1))
                                        .executes(context -> repairBlackTiles(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "budgetTiles")))))
                        .then(Commands.literal("autorepair")
                                .executes(context -> showBlackTileAutoRepair(context.getSource()))
                                .then(Commands.literal("on")
                                        .executes(context -> setBlackTileAutoRepair(context.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(context -> setBlackTileAutoRepair(context.getSource(), false)))
                                .then(Commands.literal("status")
                                        .executes(context -> showBlackTileAutoRepair(context.getSource()))))
                        .then(Commands.literal("status")
                                .executes(context -> showMapRenderStatus(context.getSource()))))
                .then(Commands.literal("debugroute")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> debugRoute(context.getSource(), null))
                        .then(Commands.literal("clear")
                                .executes(context -> debugRouteClear(context.getSource())))
                        .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(context -> debugRoute(context.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "name"))))));
    }

    private static int issueToken(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        MarketWebServer server = MarketWebServer.get();
        if (server == null || !server.isRunning()) {
            source.sendFailure(Component.literal("Market web service is not running."));
            return 0;
        }
        String token = server.auth().issueLoginToken(player, com.monpai.sailboatmod.ModConfig.marketWebLoginTokenTtlMinutes());
        String host = com.monpai.sailboatmod.ModConfig.marketWebBindHost();
        String urlHost = "0.0.0.0".equals(host) ? "127.0.0.1" : host;
        String url = "http://" + urlHost + ":" + com.monpai.sailboatmod.ModConfig.marketWebPort() + "/";
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new CopyMarketWebTokenPacket(token, url));
        source.sendSuccess(() -> Component.literal("Market web URL: ")
                .append(Component.literal(url).setStyle(Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)))), false);
        source.sendSuccess(() -> Component.literal("Fixed login token copied to clipboard."), false);
        source.sendSuccess(() -> Component.literal("Fallback token: ")
                .append(Component.literal(token).setStyle(Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, token)))), false);
        return 1;
    }

    private static int reloadWeb(CommandSourceStack source) {
        MarketWebServer server = MarketWebServer.get();
        if (server == null || !server.isRunning()) {
            source.sendFailure(Component.literal("Market web service is not running."));
            return 0;
        }
        server.reload();
        source.sendSuccess(() -> Component.literal("Market web caches reloaded. Refresh the browser to see changes."), true);
        return 1;
    }

    private static int repairMapCache(CommandSourceStack source) {
        int repaired = MarketWebMapTileCache.forServer(source.getServer()).repairLegacyMetadata();
        source.sendSuccess(() -> Component.literal("Market web map legacy metadata repaired: " + repaired), true);
        return repaired;
    }

    private static int clearLegacyMapCache(CommandSourceStack source) {
        int cleared = MarketWebMapTileCache.forServer(source.getServer()).clearLegacyPngs();
        source.sendSuccess(() -> Component.literal("Market web map legacy PNGs cleared: " + cleared), true);
        return cleared;
    }

    private static int clearClientUploadMapCache(CommandSourceStack source) {
        int cleared = MarketWebMapTileCache.forServer(source.getServer()).clearClientUploadChunks();
        source.sendSuccess(() -> Component.literal("Market web map client upload chunks cleared: " + cleared), true);
        return cleared;
    }

    private static int clearStaleServerMapCache(CommandSourceStack source) {
        int cleared = MarketWebMapTileCache.forServer(source.getServer()).clearStaleServerChunks();
        source.sendSuccess(() -> Component.literal("Market web map stale server chunks cleared: " + cleared), true);
        return cleared;
    }

    private static int clearAllMapCache(CommandSourceStack source) {
        // 先彻底静止渲染(停 job、清 dirty/queue/在途),否则后台渲染线程会边删边写 square 目录 →
        // DirectoryNotEmptyException + 清完又被立刻重写回半成品。静止后再删盘,干净。
        MarketWebMapRenderService.global().haltAndClearRenderState(source.getServer().overworld());
        int cleared = MarketWebMapTileCache.forServer(source.getServer()).clearAllTiles();
        source.sendSuccess(() -> Component.literal("Market web map all cached tiles cleared: " + cleared
                + " (rendering halted; run fullrender to rebuild)."), true);
        return cleared;
    }

    /**
     * 一键修复红水：清除所有客户端上传与遗留瓦片（含旧的红蓝颠倒"红水"），并触发区域扫描重渲。
     * 配合磁盘 region 监听，受影响区域会逐步用新配色重新生成。
     */
    private static int repaintAllMapCache(CommandSourceStack source) {
        MarketWebMapTileCache cache = MarketWebMapTileCache.forServer(source.getServer());
        int clearedUploads = cache.clearClientUploadChunks();
        int clearedLegacy = cache.clearLegacyPngs();
        int clearedStale = cache.clearStaleServerChunks();
        int queued = MarketWebMapRenderService.global().enqueueRegionRepairScan(source.getServer().overworld(), 64);
        int total = clearedUploads + clearedLegacy + clearedStale;
        source.sendSuccess(() -> Component.literal(
                "Market web map repaint: cleared " + total + " stale/upload tiles, queued " + queued
                        + " saved regions for background repaint. Generated chunks will be rebuilt gradually without HTTP force-loading."), true);
        return total + queued;
    }

    private static int enqueueMapRegionScan(CommandSourceStack source) {
        int queued = MarketWebMapRenderService.global().enqueueRegionRepairScan(source.getServer().overworld(), 16);
        source.sendSuccess(() -> Component.literal("Market web map region scan queued saved regions: " + queued), true);
        return queued;
    }

    private static int repairBlackTiles(CommandSourceStack source, int budgetTiles) {
        int queued = MarketWebMapBlackTileRepair.scanAndRepair(
                source.getServer(), source.getServer().overworld(), budgetTiles);
        source.sendSuccess(() -> Component.literal(
                "Market web map repairblack: scanned disk tiles, queued " + queued
                        + " black-hole region(s) for incremental re-render (no force-loading)."), true);
        return queued;
    }

    /** 切换黑块自动扫描修复开关(持久化到配置)。手动 /marketweb map repairblack 不受此开关影响,总能用。 */
    private static int setBlackTileAutoRepair(CommandSourceStack source, boolean enabled) {
        com.monpai.sailboatmod.ModConfig.setMarketWebBlackTileAutoRepairEnabled(enabled);
        source.sendSuccess(() -> Component.literal("Market web map black-tile auto-repair "
                + (enabled ? "ENABLED" : "DISABLED")
                + ". (Manual /marketweb map repairblack still works either way.)"), true);
        return enabled ? 1 : 0;
    }

    private static int showBlackTileAutoRepair(CommandSourceStack source) {
        boolean enabled = com.monpai.sailboatmod.ModConfig.marketWebBlackTileAutoRepairEnabled();
        source.sendSuccess(() -> Component.literal("Market web map black-tile auto-repair is "
                + (enabled ? "ENABLED" : "DISABLED") + "."), false);
        return enabled ? 1 : 0;
    }

    private static int startFullMapRender(CommandSourceStack source) {
        boolean started = MarketWebMapRenderService.global().startFullRender(source.getServer().overworld());
        if (!started) {
            source.sendFailure(Component.literal("Market web map fullrender could not start. A render may already be active or no region files were found."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Market web map fullrender started. Chunks will be queued gradually without force-loading."), true);
        return 1;
    }

    private static int startRadiusMapRender(CommandSourceStack source, int radiusBlocks) {
        BlockPos center = BlockPos.containing(source.getPosition());
        boolean started = MarketWebMapRenderService.global().startRadiusRender(
                source.getLevel(),
                center.getX(),
                center.getZ(),
                radiusBlocks);
        if (!started) {
            source.sendFailure(Component.literal("Market web map radiusrender could not start. A render may already be active."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Market web map radiusrender started for radius " + radiusBlocks + " blocks."), true);
        return 1;
    }

    private static int startAreaMapRender(CommandSourceStack source, int x1, int z1, int x2, int z2) {
        boolean started = MarketWebMapRenderService.global().startAreaRender(
                source.getServer().overworld(),
                x1,
                z1,
                x2,
                z2);
        if (!started) {
            source.sendFailure(Component.literal("Market web map arearender could not start. A render may already be active."));
            return 0;
        }
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        source.sendSuccess(() -> Component.literal("Market web map arearender started for blocks X "
                + minX + ".." + maxX + ", Z " + minZ + ".." + maxZ
                + ". Chunks will be queued gradually without force-loading."), true);
        return 1;
    }

    private static int startWorldBorderMapRender(CommandSourceStack source) {
        boolean started = MarketWebMapRenderService.global().startWorldBorderRender(source.getServer().overworld());
        if (!started) {
            source.sendFailure(Component.literal("Market web map borderrender could not start. A render may already be active or no saved chunks were found inside the world border."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Market web map borderrender started for saved chunks inside the overworld border. Chunks will be queued gradually without force-loading."), true);
        return 1;
    }

    private static int pauseMapRender(CommandSourceStack source) {
        boolean paused = MarketWebMapRenderService.global().pauseRender();
        if (!paused) {
            source.sendFailure(Component.literal("No market web map render is active."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Market web map render paused."), true);
        return 1;
    }

    private static int resumeMapRender(CommandSourceStack source) {
        boolean resumed = MarketWebMapRenderService.global().resumeRender();
        if (!resumed) {
            source.sendFailure(Component.literal("No market web map render is active."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Market web map render resumed."), true);
        return 1;
    }

    private static int cancelMapRender(CommandSourceStack source) {
        boolean canceled = MarketWebMapRenderService.global().cancelRender(source.getServer().overworld());
        if (!canceled) {
            source.sendFailure(Component.literal("No market web map render is active."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Market web map render canceled."), true);
        return 1;
    }

    private static int showMapRenderStatus(CommandSourceStack source) {
        MarketWebMapRenderService service = MarketWebMapRenderService.global();
        int queueSize = service.queueSize();
        var status = service.renderStatus();
        source.sendSuccess(() -> Component.literal("Market web map render job: " + status.activeJob()
                + " paused=" + status.paused()), false);
        source.sendSuccess(() -> Component.literal("Market web map render queue: " + queueSize
                + " snapshots(active/pending)=" + status.activeSnapshotRequests() + "/" + status.pendingSnapshotRequests()
                + " imageIO=" + status.pendingImageIo()), false);
        source.sendSuccess(() -> Component.literal("Progress chunks: " + status.processedChunks() + "/" + status.totalChunks()
                + " regions: " + status.processedRegions() + "/" + status.totalRegions()), false);
        source.sendSuccess(() -> Component.literal("Dirty regions/chunks: " + status.dirtyRegions() + "/" + status.dirtyChunks()
                + " trackedRegions=" + status.trackedRegions()
                + " failedChunks=" + status.failedChunks()), false);
        source.sendSuccess(() -> Component.literal("Current cursor: region " + status.currentRegionX() + "," + status.currentRegionZ()
                + " localChunk=" + status.currentLocalChunk()), false);
        source.sendSuccess(() -> Component.literal("Renderer version: " + MarketWebMapTileCache.RENDER_VERSION), false);
        source.sendSuccess(() -> Component.literal("Diag: " + service.renderDiagLine()), false);
        return queueSize;
    }

    /** 调试用 /marketweb debugroute 前缀,投到 webmap 的 trace id 都以此开头,便于 clear 时识别。 */
    private static final String DEBUG_TRACE_PREFIX = "debugroute_";

    /**
     * 调试:把玩家附近最近码头的航线投成 webmap 轨迹,用于实测看清航线走向/穿陆。webmap 2 秒后自动刷新显示。
     * @param nameFilter null=画该码头所有航线;非 null=只画名字含此串的航线(单独看一条)。
     *
     * <p>关键:status 必须用 {@code SAILING}(webmap 的 isMapVisibleStatus 只放行 SAILING/IN_TRANSIT/ARRIVED/
     * STUCK;之前误用 ACTIVE 被过滤掉 → webmap 看不到);shipperUuid=玩家 → visibleFor 的 ownShipment 放行。
     */
    private static int debugRoute(CommandSourceStack source, String nameFilter) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("需在游戏中以玩家身份执行。"));
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos dockPos = DockBlockEntity.findNearestRegisteredDock(level, player.position(), 64.0D);
        if (dockPos == null || !(level.getBlockEntity(dockPos) instanceof DockBlockEntity dock)) {
            source.sendFailure(Component.literal("附近 64 格内未找到码头。请站在码头旁执行。"));
            return 0;
        }
        List<RouteDefinition> routes = dock.getRoutesForMap();
        if (routes.isEmpty()) {
            source.sendFailure(Component.literal("该码头没有航线。先创建自动航线。"));
            return 0;
        }
        String filter = nameFilter == null ? null : nameFilter.trim().toLowerCase(java.util.Locale.ROOT);
        ShippingTraceSavedData data = ShippingTraceSavedData.get(level);
        long gameTime = level.getGameTime();
        int painted = 0;
        int matched = 0;
        for (int i = 0; i < routes.size(); i++) {
            RouteDefinition route = routes.get(i);
            if (filter != null && (route.name() == null || !route.name().toLowerCase(java.util.Locale.ROOT).contains(filter))) {
                continue;
            }
            matched++;
            if (route.waypoints() == null || route.waypoints().size() < 2) {
                continue;
            }
            String traceId = DEBUG_TRACE_PREFIX + dockPos.asLong() + "_" + i;
            data.putTrace(new ShippingTraceRecord(
                    traceId,
                    player.getUUID().toString(),
                    MarketWebMapConstants.OVERWORLD,
                    "PORT",
                    "SAILING",          // 必须是 isMapVisibleStatus 放行的状态,否则 webmap 不显示
                    "", "",
                    route.startDockName(),
                    route.endDockName(),
                    route.waypoints(),
                    0,                 // completedPointCount=0 → 全程未完成,整条都画出来
                    0.0D,
                    gameTime, gameTime,
                    0.0D, 0.0D, 0.0D,
                    List.of(),
                    true));            // manual=true → webmap 画成金色调试折线
            // 主线程逐航点采真实方块(按 chunk 去重 force 加载)+ 出身标记,写入旁路缓存供 webmap tooltip。
            sampleAndCacheDebugNodes(level, traceId, route);
            painted++;
        }
        if (filter != null && matched == 0) {
            source.sendFailure(Component.literal("该码头没有名字含「" + nameFilter + "」的航线。"));
            return 0;
        }
        final int paintedFinal = painted;
        final int total = routes.size();
        source.sendSuccess(() -> Component.literal("已在 webmap 投放 " + paintedFinal + " 条调试航线("
                + dock.getDockName() + (filter == null ? ",共 " + total + " 条" : ",筛选「" + nameFilter + "」")
                + ")。webmap 刷新后查看金色折线。清除:/marketweb debugroute clear"), false);
        return painted;
    }

    /** 清除所有 debugroute 投放的调试轨迹。 */
    private static int debugRouteClear(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        ShippingTraceSavedData data = ShippingTraceSavedData.get(level);
        List<String> ids = data.getTraces().stream()
                .map(ShippingTraceRecord::shippingOrderId)
                .filter(id -> id != null && id.startsWith(DEBUG_TRACE_PREFIX))
                .toList();
        ids.forEach(data::removeTrace);
        ids.forEach(DebugRouteNodeCache::remove);
        source.sendSuccess(() -> Component.literal("已清除 " + ids.size() + " 条调试航线。"), false);
        return ids.size();
    }

    /**
     * 主线程逐航点采真实方块,合成调试节点写入 {@link DebugRouteNodeCache}。
     *
     * <p>采样口径与船的可航判定一致:海平面那格 {@code getFluidState} 是否水(抄
     * {@link com.monpai.sailboatmod.route.water.RealBlockWaterMap#diagnosePathLandCrossings});
     * 表面方块 id 取 MOTION_BLOCKING_NO_LEAVES 高度处的方块。出身标记取
     * {@code route.waypointMetas()}(只水路自动航线有,缺位→默认段/原始,debug 降级)。
     *
     * <p>性能:按 chunk 分组,每个区块只 {@code forceChunk} 一次,读完该区块内所有落点再释放,
     * 避免对同一区块反复 force/卸载(平滑后航点可达 ~256 个,但落点集中在沿途少数区块)。
     */
    private static void sampleAndCacheDebugNodes(ServerLevel level, String traceId, RouteDefinition route) {
        List<net.minecraft.world.phys.Vec3> waypoints = route.waypoints();
        if (waypoints == null || waypoints.isEmpty()) {
            DebugRouteNodeCache.put(traceId, List.of());
            return;
        }
        List<com.monpai.sailboatmod.route.WaypointMeta> metas = route.waypointMetas();
        int seaLevel = level.getSeaLevel();
        int n = waypoints.size();
        // 预置数组,按 chunk 分组填充(保持与 waypoints 的 index 对齐)。
        DebugRouteNodeCache.Node[] nodes = new DebugRouteNodeCache.Node[n];
        // chunkKey -> 该 chunk 内航点的 index 列表。
        java.util.Map<Long, java.util.List<Integer>> byChunk = new java.util.LinkedHashMap<>();
        for (int idx = 0; idx < n; idx++) {
            net.minecraft.world.phys.Vec3 wp = waypoints.get(idx);
            int cx = ((int) Math.floor(wp.x)) >> 4;
            int cz = ((int) Math.floor(wp.z)) >> 4;
            byChunk.computeIfAbsent((((long) cx) << 32) ^ (cz & 0xffffffffL), k -> new java.util.ArrayList<>()).add(idx);
        }
        for (java.util.Map.Entry<Long, java.util.List<Integer>> e : byChunk.entrySet()) {
            long ck = e.getKey();
            int cx = (int) (ck >> 32);
            int cz = (int) ck;
            BlockPos owner = new BlockPos(cx << 4, seaLevel, cz << 4);
            net.minecraftforge.common.world.ForgeChunkManager.forceChunk(
                    level, com.monpai.sailboatmod.SailboatMod.MODID, owner, cx, cz, true, false);
            try {
                level.getChunk(cx, cz, net.minecraft.world.level.chunk.ChunkStatus.FULL, true);
                for (int idx : e.getValue()) {
                    net.minecraft.world.phys.Vec3 wp = waypoints.get(idx);
                    int x = (int) Math.floor(wp.x);
                    int z = (int) Math.floor(wp.z);
                    boolean water = level.getFluidState(new BlockPos(x, seaLevel, z))
                            .is(net.minecraft.tags.FluidTags.WATER);
                    int surfY = level.getHeight(
                            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    net.minecraft.world.level.block.state.BlockState st =
                            level.getBlockState(new BlockPos(x, surfY, z));
                    String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(st.getBlock()).toString();
                    byte segment = metaSegmentAt(metas, idx);
                    byte origin = metaOriginAt(metas, idx);
                    nodes[idx] = new DebugRouteNodeCache.Node(wp.x, wp.z, blockId, water, origin, segment);
                }
            } catch (Throwable t) {
                // 单区块采样失败不影响整条:该 chunk 内的点留 null,下方补降级节点。
            } finally {
                net.minecraftforge.common.world.ForgeChunkManager.forceChunk(
                        level, com.monpai.sailboatmod.SailboatMod.MODID, owner, cx, cz, false, false);
            }
        }
        // 补齐采样失败的点(无方块信息,但仍带坐标+出身标记,前端按缺方块降级)。
        java.util.List<DebugRouteNodeCache.Node> out = new java.util.ArrayList<>(n);
        for (int idx = 0; idx < n; idx++) {
            if (nodes[idx] != null) {
                out.add(nodes[idx]);
            } else {
                net.minecraft.world.phys.Vec3 wp = waypoints.get(idx);
                out.add(new DebugRouteNodeCache.Node(wp.x, wp.z, "",
                        false, metaOriginAt(metas, idx), metaSegmentAt(metas, idx)));
            }
        }
        DebugRouteNodeCache.put(traceId, out);
    }

    /** 容错取 metas 段标记:缺位/越界 → 默认起始段(0)。 */
    private static byte metaSegmentAt(List<com.monpai.sailboatmod.route.WaypointMeta> metas, int idx) {
        if (metas == null || idx < 0 || idx >= metas.size() || metas.get(idx) == null) {
            return com.monpai.sailboatmod.route.WaypointMeta.SEGMENT_START;
        }
        return metas.get(idx).segment();
    }

    /** 容错取 metas 来源标记:缺位/越界 → 默认原始节点(0)。 */
    private static byte metaOriginAt(List<com.monpai.sailboatmod.route.WaypointMeta> metas, int idx) {
        if (metas == null || idx < 0 || idx >= metas.size() || metas.get(idx) == null) {
            return com.monpai.sailboatmod.route.WaypointMeta.ORIGIN_RAW;
        }
        return metas.get(idx).origin();
    }

    private static int showVersion(CommandSourceStack source) {
        MarketWebServer server = MarketWebServer.get();
        if (server == null || !server.isRunning()) {
            source.sendFailure(Component.literal("Market web service is not running."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Market web addon " + MarketWebServer.addonVersion()), false);
        source.sendSuccess(() -> Component.literal("Resource version: " + server.resourceVersion()), false);
        source.sendSuccess(() -> Component.literal("Icon cache version: " + MarketWebServer.iconCacheVersion()), false);
        source.sendSuccess(() -> Component.literal("Dev mode: " + com.monpai.sailboatmod.ModConfig.marketWebDevMode()), false);
        return 1;
    }
}
