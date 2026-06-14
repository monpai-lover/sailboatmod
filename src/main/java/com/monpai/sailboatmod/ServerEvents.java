package com.monpai.sailboatmod;

import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.integration.bluemap.BlueMapIntegration;
import com.monpai.sailboatmod.map.SharedMapServerState;
import com.monpai.sailboatmod.market.analytics.MarketAnalyticsService;
import com.monpai.sailboatmod.market.db.MarketDatabase;
import com.monpai.sailboatmod.nation.service.ClaimPreviewTerrainService;
import com.monpai.sailboatmod.nation.service.ClaimMapTaskService;
import com.monpai.sailboatmod.nation.service.BankLoanService;
import com.monpai.sailboatmod.nation.service.RoadPlanningTaskService;
import com.monpai.sailboatmod.roadplanner.edit.RoadEditTaskService;
import com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlService;
import com.monpai.sailboatmod.roadplanner.service.RoadPlannerMapPreloadService;
import com.monpai.sailboatmod.route.water.WaterRouteTaskService;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

@Mod.EventBusSubscriber(modid = SailboatMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerEvents {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final MarketAnalyticsService MARKET_ANALYTICS = new MarketAnalyticsService();
    private static final BankLoanService BANK_LOANS = new BankLoanService();

    @FunctionalInterface
    interface StartupTask {
        void run() throws Exception;
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        com.monpai.sailboatmod.market.commodity.CommodityConfigLoader.load();
        runStartupTaskSafely("market SQLite database", () -> MarketDatabase.initialize(event.getServer()));
        BlueMapIntegration.onServerStarted(event.getServer());
        ClaimPreviewTerrainService.onServerStarted(event.getServer());
        ClaimMapTaskService.onServerStarted(event.getServer());
        SharedMapServerState.onServerStarted(event.getServer());
        RoadPlanningTaskService.onServerStarted(event.getServer());
        RoadPlannerMapPreloadService.onServerStarted(event.getServer());
        runStartupTaskSafely("road graph reconcile", () -> reconcileRoadGraphs(event.getServer()));
    }

    // 把已有的道路记录(RoadNetworkRecord)一次性补建进路由图，修复"驿站找不到已建道路、必须先开道路规划器"的问题。
    private static void reconcileRoadGraphs(MinecraftServer server) {
        if (server == null) {
            return;
        }
        int total = 0;
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            total += com.monpai.sailboatmod.roadplanner.graph.RoadGraphSync.reconcile(level);
        }
        if (total > 0) {
            LOGGER.info("Reconciled {} road(s) into the routing graph on startup.", total);
        }
    }

    private static int cleanupTickCounter;
    private static int loanTickCounter;
    private static int dispatchTickCounter;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            BlueMapIntegration.onServerTick(server);
            com.monpai.sailboatmod.nation.service.NationWarService.tickWars(server);
            com.monpai.sailboatmod.nation.service.NationWarService.tickProposalExpiry(
                    com.monpai.sailboatmod.nation.data.NationSavedData.get(server.overworld()));
            server.getAllLevels().forEach(level -> {
                com.monpai.sailboatmod.nation.service.StructureConstructionManager.tick(level);
                com.monpai.sailboatmod.nation.service.ClaimPreviewTerrainService.tick(level);
                RoadPlannerBuildControlService.global().tick(level);
                RoadEditTaskService.global().tick(level);
                RoadPlannerMapPreloadService.global().tick(level);
                WaterRouteTaskService.global().tick(level);
            });
            com.monpai.sailboatmod.network.packet.RequestClaimMapViewportPacket.onServerTick(server);
            MARKET_ANALYTICS.maybeRecordSnapshots(server);
            if (++loanTickCounter >= 1200) {
                loanTickCounter = 0;
                BANK_LOANS.tick(server);
            }
            if (++cleanupTickCounter >= 6000) {
                cleanupTickCounter = 0;
                cleanupOrphanClaims(server);
            }
            if (++dispatchTickCounter >= 300) { // 15s @20tps：后台运输调度
                dispatchTickCounter = 0;
                com.monpai.sailboatmod.market.logistics.TransportDispatchService.globalTick(server);
            }
        }
    }

    private static void cleanupOrphanClaims(MinecraftServer server) {
        com.monpai.sailboatmod.nation.data.NationSavedData data =
                com.monpai.sailboatmod.nation.data.NationSavedData.get(server.overworld());
        java.util.List<com.monpai.sailboatmod.nation.model.NationClaimRecord> toRemove = new java.util.ArrayList<>();
        for (com.monpai.sailboatmod.nation.model.NationClaimRecord claim : data.getAllClaims()) {
            boolean hasOwner = false;
            if (!claim.nationId().isBlank() && data.getNation(claim.nationId()) != null) hasOwner = true;
            if (!hasOwner && !claim.townId().isBlank() && data.getTown(claim.townId()) != null) hasOwner = true;
            if (!hasOwner) toRemove.add(claim);
        }
        for (com.monpai.sailboatmod.nation.model.NationClaimRecord claim : toRemove) {
            data.removeClaim(claim.dimensionId(), claim.chunkX(), claim.chunkZ());
        }
        if (shouldSyncAfterOrphanClaimCleanup(toRemove.size())) {
            com.monpai.sailboatmod.nation.service.ClaimHighlightSyncService.syncAll(server);
        }
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof SailboatEntity boat) {
            BlueMapIntegration.syncBoat(boat);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        com.monpai.sailboatmod.nation.service.StructureConstructionManager.clearRuntimeState();
        ClaimPreviewTerrainService.onServerStopping(event.getServer());
        com.monpai.sailboatmod.network.packet.RequestClaimMapViewportPacket.onServerStopping();
        ClaimMapTaskService.onServerStopping();
        RoadPlanningTaskService.onServerStopping();
        SharedMapServerState.onServerStopped();
        RoadPlannerMapPreloadService.onServerStopped();
        RoadEditTaskService.global().clear();
        WaterRouteTaskService.global().clear();
        com.monpai.sailboatmod.integration.minecolonies.MineColoniesIntegration.onServerStopped();
        MarketDatabase.shutdown();
        BlueMapIntegration.onServerStopped();
    }

    private ServerEvents() {
    }

    static boolean runStartupTaskSafelyForTest(String taskName, StartupTask task) {
        return runStartupTaskSafely(taskName, task);
    }

    static boolean shouldSyncAfterOrphanClaimCleanupForTest(int removedClaimCount) {
        return shouldSyncAfterOrphanClaimCleanup(removedClaimCount);
    }

    private static boolean shouldSyncAfterOrphanClaimCleanup(int removedClaimCount) {
        return removedClaimCount > 0;
    }

    private static boolean runStartupTaskSafely(String taskName, StartupTask task) {
        if (task == null) {
            return true;
        }
        try {
            task.run();
            return true;
        } catch (Throwable throwable) {
            LOGGER.error("Failed to initialize {}", taskName, throwable);
            return false;
        }
    }
}
