package com.monpai.sailboatmod.market.web;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.market.logistics.ShippingTraceRecord;
import com.monpai.sailboatmod.market.logistics.ShippingTraceSavedData;
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
                        .then(Commands.literal("status")
                                .executes(context -> showMapRenderStatus(context.getSource()))))
                .then(Commands.literal("debugroute")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> debugRoute(context.getSource()))
                        .then(Commands.literal("clear")
                                .executes(context -> debugRouteClear(context.getSource())))));
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
        int cleared = MarketWebMapTileCache.forServer(source.getServer()).clearAllTiles();
        source.sendSuccess(() -> Component.literal("Market web map all cached tiles cleared: " + cleared), true);
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
        return queueSize;
    }

    /** 调试用 /marketweb debugroute 前缀,投到 webmap 的 trace id 都以此开头,便于 clear 时识别。 */
    private static final String DEBUG_TRACE_PREFIX = "debugroute_";

    /**
     * 调试:把玩家附近最近码头的所有航线投成 webmap 轨迹(manual=true 金色折线),用于实测看清航线走向/穿陆。
     * 每条航线一条 trace,起点=waypoints[0],终点=waypoints[last]。webmap 2 秒后自动刷新显示。
     */
    private static int debugRoute(CommandSourceStack source) {
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
        ShippingTraceSavedData data = ShippingTraceSavedData.get(level);
        long gameTime = level.getGameTime();
        int painted = 0;
        for (int i = 0; i < routes.size(); i++) {
            RouteDefinition route = routes.get(i);
            if (route.waypoints() == null || route.waypoints().size() < 2) {
                continue;
            }
            String traceId = DEBUG_TRACE_PREFIX + dockPos.asLong() + "_" + i;
            data.putTrace(new ShippingTraceRecord(
                    traceId,
                    player.getUUID().toString(),
                    MarketWebMapConstants.OVERWORLD,
                    "PORT",
                    "ACTIVE",
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
            painted++;
        }
        final int paintedFinal = painted;
        source.sendSuccess(() -> Component.literal("已在 webmap 投放 " + paintedFinal + " 条调试航线("
                + dock.getDockName() + ",含 " + routes.size() + " 条航线)。webmap 刷新后查看。"
                + " 清除:/marketweb debugroute clear"), false);
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
        source.sendSuccess(() -> Component.literal("已清除 " + ids.size() + " 条调试航线。"), false);
        return ids.size();
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
