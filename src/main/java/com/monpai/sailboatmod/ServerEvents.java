package com.monpai.sailboatmod;

import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.integration.bluemap.BlueMapIntegration;
import com.monpai.sailboatmod.market.db.MarketDatabase;
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

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        try {
            MarketDatabase.initialize(event.getServer());
        } catch (Exception e) {
            LOGGER.error("Failed to initialize market SQLite database", e);
        }
        BlueMapIntegration.onServerStarted(event.getServer());
    }

    private static int cleanupTickCounter;

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
            server.getAllLevels().forEach(com.monpai.sailboatmod.nation.service.StructureConstructionManager::tick);
            if (++cleanupTickCounter >= 6000) {
                cleanupTickCounter = 0;
                cleanupOrphanClaims(server);
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
        MarketDatabase.shutdown();
        BlueMapIntegration.onServerStopped();
    }

    private ServerEvents() {
    }
}