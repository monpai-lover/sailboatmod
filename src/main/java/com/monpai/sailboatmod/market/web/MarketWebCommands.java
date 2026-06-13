package com.monpai.sailboatmod.market.web;

import com.monpai.sailboatmod.market.web.map.MarketWebMapTileCache;
import com.monpai.sailboatmod.market.web.map.MarketWebMapRenderService;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.CopyMarketWebTokenPacket;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

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
                        .then(Commands.literal("scan")
                                .executes(context -> enqueueMapRegionScan(context.getSource())))
                        .then(Commands.literal("status")
                                .executes(context -> showMapRenderStatus(context.getSource())))));
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
                        + " loaded chunks. Walk through old areas to let the disk watcher repaint them."), true);
        return total;
    }

    private static int enqueueMapRegionScan(CommandSourceStack source) {
        int queued = MarketWebMapRenderService.global().enqueueRegionRepairScan(source.getServer().overworld(), 16);
        source.sendSuccess(() -> Component.literal("Market web map region scan queued loaded chunks: " + queued), true);
        return queued;
    }

    private static int showMapRenderStatus(CommandSourceStack source) {
        int queueSize = MarketWebMapRenderService.global().queueSize();
        source.sendSuccess(() -> Component.literal("Market web map render queue: " + queueSize), false);
        source.sendSuccess(() -> Component.literal("Renderer version: " + MarketWebMapTileCache.RENDER_VERSION), false);
        return queueSize;
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
