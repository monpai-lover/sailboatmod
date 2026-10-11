package com.monpai.sailboatmod.util;

import com.monpai.sailboatmod.SailboatMod;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.world.chunk.LoadingValidationCallback;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;

/** The registered ticket controller shared by transport and route scanners. */
public final class ModChunkLoading {
    private static TicketController controller;

    private ModChunkLoading() {}

    public static void register(IEventBus modEventBus, LoadingValidationCallback callback) {
        // NeoForge maps Forge's old Mod-owned tickets to the "default" controller.
        controller = new TicketController(ResourceLocation.fromNamespaceAndPath(SailboatMod.MODID, "default"), callback);
        modEventBus.addListener((RegisterTicketControllersEvent event) -> event.register(controller));
    }

    private static TicketController controller(String modId) {
        if (!SailboatMod.MODID.equals(modId) || controller == null) {
            throw new IllegalStateException("Transport ticket controller has not been registered");
        }
        return controller;
    }

    public static boolean forceChunk(ServerLevel level, String modId, Entity owner,
            int chunkX, int chunkZ, boolean add, boolean ticking) {
        return controller(modId).forceChunk(level, owner, chunkX, chunkZ, add, ticking);
    }

    public static boolean forceChunk(ServerLevel level, String modId, UUID owner,
            int chunkX, int chunkZ, boolean add, boolean ticking) {
        return controller(modId).forceChunk(level, owner, chunkX, chunkZ, add, ticking);
    }

    public static boolean forceChunk(ServerLevel level, String modId, BlockPos owner,
            int chunkX, int chunkZ, boolean add, boolean ticking) {
        return controller(modId).forceChunk(level, owner, chunkX, chunkZ, add, ticking);
    }
}
